package com.chipprbots.ethereum.domain

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockAccessListStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.ReceiptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.branch.BestBranch
import com.chipprbots.ethereum.domain.branch.Branch
import com.chipprbots.ethereum.domain.branch.EmptyBranch
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

class BlockchainReader(
    blockHeadersStorage: BlockHeadersStorage,
    blockBodiesStorage: BlockBodiesStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    stateStorage: StateStorage,
    receiptStorage: ReceiptStorage,
    appStateStorage: AppStateStorage,
    chainWeightStorage: ChainWeightStorage,
    blockAccessListStorage: BlockAccessListStorage,
    pathState: Option[PathStateStorage] = None
) extends Logger:

  // ─── The state of a block (spec 018 Track C, C2b) ────────────────────────────────────────────────────────────────
  //
  // A reader asks for the state of a BLOCK, by its header, and reads tries over what it is handed. It does not take a
  // store and a root: on the unified layout there is one state on disk, a position says nothing about which block it
  // belongs to, and only a view of a block can say whether what was read is that block's.

  /** The state of `header`'s block, for a reader that writes nothing.
    *
    * With the in-development guard off, which is how the node ships, this is the read-only store every reader could
    * already take: a buffer over the hash store, so that a reader that persists by accident writes nowhere. With it on,
    * a view of the position store: of the block the disk holds, for any caller; and of a canonical block '''behind'''
    * it that the changeset records lead back to, for a caller that reads inside a
    * [[com.chipprbots.ethereum.db.storage.pathstate.ReadScope]], which is how the reads behind the head that are in
    * flight are counted and bounded. Never of an executed block that is kept beside the chain.
    *
    * @throws com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
    *   with the guard on, when that block's state is not held, or is behind the head and the caller is in no scope;
    *   and with `askAgain` when as many reads behind the head as may be are in flight
    */
  def stateAt(header: BlockHeader): MptStorage =
    pathState.fold(stateStorage.getReadOnlyStorage)(_.readOnlyInScope(refOf(header)))

  /** The same state, for an execution that commits nothing (`eth_call`, a simulation, a trace): what it persists is
    * kept beside the state, read back by that execution, and dropped with the storage.
    */
  def scratchStateAt(header: BlockHeader): MptStorage =
    pathState.fold(stateStorage.getReadOnlyStorage)(_.throwawayInScope(refOf(header)))

  /** The state a block is BUILT on, when the unified layout holds it: `parent`'s state under a layer of this build's
    * own, which takes what the build persists, is read back by it, and is dropped with the storage. `None` with the
    * in-development guard off, where each builder goes on taking the store it always took.
    *
    * A fresh view and a fresh layer at every call: an attempt of a lenient build and a block the miner prepares each
    * have their own. Nothing built here is committed, no row is written and no layer is registered: a block that was
    * built is executed again when it is imported (spec 018 data-model, "Block generation and mining").
    *
    * @throws com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
    *   when `parent` is not the block whose state is held
    */
  def proposerStateOn(parent: BlockHeader): Option[MptStorage] = pathState.map(_.throwaway(refOf(parent)))

  /** Whether the state is held in place, one block's at a time (the unified layout): what a caller asks when what it
    * does depends on a block's state being the one on disk. Always false with the in-development guard off.
    */
  def holdsStateInPlace: Boolean = pathState.isDefined

  /** Whether `blockHash` is the block whose state is held in place; false with the guard off, where no block is. */
  def isHeldInPlace(blockHash: ByteString): Boolean = pathState.exists(_.isApplied(blockHash))

  /** The block whose state is held in place, by number and hash; `None` with the guard off or with no state yet. */
  def heldInPlace: Option[(BigInt, ByteString)] = pathState.flatMap(_.anchor).map(held => (held.number, held.hash))

  /** The state at `root`, for serving it to a peer (SNAP); `None` when it is not served.
    *
    * Guard off, the read-only store whatever the root: the serve finds the root's node there or answers with nothing,
    * as it always has.
    *
    * Guard on, the state of '''the canonical block that has that state root''', among the blocks from the one the disk
    * holds back to [[BlockchainReader.ServedRootWindow]] behind the best block: the disk's own rows for the block it
    * holds, and for a block behind it a view through the changeset records. A root of no such block is not served, nor
    * one the records no longer lead back to (it is past the retention floor, or behind a record that lists nothing). An
    * executed block that is kept beside the disk is never served: its root is of no canonical block. Where two blocks
    * of the window have one state root (an empty block after the merge), the newer is read: it is the same state,
    * through fewer records.
    */
  def stateForRoot(root: ByteString): Option[MptStorage] = pathState match
    case None => Some(stateStorage.getReadOnlyStorage)
    case Some(state) =>
      state.anchor.flatMap { held =>
        servedRoots(held).get(root).flatMap { block =>
          // the disk may move between the lookup and the view: a state that is no longer held is not served
          try
            Some(
              if block == held then state.readOnly(held) else state.readOnlyBehind(block, PathStateStorage.Reader.Peer)
            )
          catch case _: StateFault => None
        }
      }

  /** The state roots served while `held` is the block the disk holds and the best block is where it is, each with the
    * block it is read at: kept for the next request, and made again when either moves. Keyed by the block and not by
    * its number: a block that takes another's place at one height has other roots behind it.
    */
  private val servedWindow = new AtomicReference[Option[(BlockRef, BigInt, Map[ByteString, BlockRef])]](None)

  private def servedRoots(held: BlockRef): Map[ByteString, BlockRef] =
    val best = getBestBlockNumber
    servedWindow.get match
      case Some((`held`, `best`, roots)) => roots
      case _ =>
        val oldest = (best - BlockchainReader.ServedRootWindow).max(BigInt(0))
        // Newest first, and the first block that has a root keeps it. The block the disk holds is known without the
        // index, and is the newest whatever the best block's number says: where that number is behind it (a pointer
        // not yet written), the block is served and the window still ends 128 behind the best block.
        val roots = Iterator
          .iterate(held.number)(_ - 1)
          .takeWhile(_ >= oldest)
          .flatMap(number => if number == held.number then Some(held) else getBlockHeaderByNumber(number).map(refOf))
          .foldLeft(Map.empty[ByteString, BlockRef]) { (found, block) =>
            if found.contains(block.stateRoot) then found else found.updated(block.stateRoot, block)
          }
        servedWindow.set(Some((held, best, roots)))
        roots

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  /** The account trie of `header`'s block over `state`, which is that block's state. */
  def accountsIn(state: MptStorage, header: BlockHeader): MerklePatriciaTrie[Address, Account] =
    MerklePatriciaTrie[Address, Account](rootHash = header.stateRoot.value.toArray, source = state.forOwner(None))

  /** The account at `address` in the state of `header`'s block; `None` when that state has no such account.
    *
    * By the header and not by a block number: a number is resolved through the canonical index, which names another
    * block whenever the caller holds a side block, or one that is not canonical yet.
    */
  def accountAt(header: BlockHeader, address: Address): Option[Account] =
    accountsIn(stateAt(header), header).get(address)

  /** What a request about a block is answered with for the account at `address`: the account in `state`, the state of
    * `header`'s block, and no account for a block above the best block. Such a block is the pending one, its state is
    * not a state the node holds, and the read by block number that this replaces answered it so.
    *
    * It takes the state so that a reader that goes on to the account's storage, or to its proof, reads all of it from
    * the one state.
    */
  def accountIn(state: MptStorage, header: BlockHeader, address: Address): Option[Account] =
    if aboveBest(header) then None else accountsIn(state, header).get(address)

  /** The proof of that account, under the same rule and from the same state. */
  def accountProofIn(state: MptStorage, header: BlockHeader, address: Address): Option[Vector[MptNode]] =
    if aboveBest(header) then None else accountsIn(state, header).getProof(address)

  private def aboveBest(header: BlockHeader): Boolean = header.number.value > getBestBlockNumber

  /** Allows to query a blockHeader by block hash
    *
    * @param hash
    *   of the block that's being searched
    * @return
    *   [[BlockHeader]] if found
    */
  def getBlockHeaderByHash(hash: BlockHash): Option[BlockHeader] =
    blockHeadersStorage.get(hash.value)

  /** Allows to query a blockBody by block hash
    *
    * @param hash
    *   of the block that's being searched
    * @return
    *   [[com.chipprbots.ethereum.domain.BlockBody]] if found
    */
  def getBlockBodyByHash(hash: BlockHash): Option[BlockBody] =
    blockBodiesStorage.get(hash.value)

  /** Allows to query for a block based on it's hash
    *
    * @param hash
    *   of the block that's being searched
    * @return
    *   Block if found
    */
  def getBlockByHash(hash: BlockHash): Option[Block] =
    for
      header <- getBlockHeaderByHash(hash)
      body <- getBlockBodyByHash(hash)
    yield Block(header, body)

  def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] =
    for
      hash <- getHashByBlockNumber(number)
      header <- getBlockHeaderByHash(BlockHash(hash))
    yield header

  /** Returns MPT node searched by it's hash
    * @param hash
    *   Node Hash
    * @return
    *   MPT node
    */
  def getMptNodeByHash(hash: ByteString): Option[MptNode] =
    stateStorage.getNode(hash)

  /** Whether the state of `header`'s block can be built on: its root node is in the hash store or, on the unified
    * layout, the block is the one whose state the disk holds. There a root is not found by its hash in the hash store,
    * which holds nothing of the chain's state.
    */
  def holdsStateOf(header: BlockHeader): Boolean =
    pathState.fold(getMptNodeByHash(header.stateRoot.value).isDefined)(_.isApplied(header.hash.value))

  /** Returns the receipts based on a block hash
    * @param blockhash
    * @return
    *   Receipts if found
    */
  def getReceiptsByHash(blockhash: BlockHash): Option[Seq[Receipt]] = receiptStorage.get(blockhash.value)

  /** Whether receipts are stored for the block `blockhash`, without decoding them
    * ([[com.chipprbots.ethereum.db.storage.TransactionalKeyValueStorage.containsKey]]).
    *
    * Not quite `getReceiptsByHash(blockhash).isDefined`: stored receipts that cannot be decoded count as present here,
    * where `getReceiptsByHash` would fail on them. The decode failure moves to the first real read of them.
    */
  def hasReceipts(blockhash: BlockHash): Boolean = receiptStorage.containsKey(blockhash.value)

  /** EIP-7928: the block access list of the Amsterdam block `blockHash`, as the canonical RLP its header's
    * `blockAccessListHash` commits to — the bytes `engine_getPayloadBodiesByHash/RangeV2` and eth/71 serve. `None` for
    * a pre-Amsterdam or ETC block, and for one whose list was never stored or has been pruned.
    */
  def getBlockAccessListByHash(blockHash: BlockHash): Option[ByteString] = blockAccessListStorage.get(blockHash.value)

  /** get the current best stored branch */
  def getBestBranch: Branch =
    val number = getBestBlockNumber
    blockNumberMappingStorage
      .get(number)
      .map(hash => BestBranch(hash, number))
      .getOrElse(EmptyBranch)

  def getBestBlockNumber: BigInt = appStateStorage.getBestBlockNumber()

  def getSnapSyncPivotBlock: Option[BigInt] = appStateStorage.getSnapSyncPivotBlock()

  /** Whether this node's state came from a completed SNAP sync. */
  def isSnapSyncDone: Boolean = appStateStorage.isSnapSyncDone()

  /** Bulk bytecode recoveries that finished with the triggering code still missing (see SyncController). */
  def bulkBytecodeRecoveryFailures: Int = appStateStorage.bulkBytecodeRecoveryFailures()

  // returns the best known block if it's available in the storage
  def getBestBlock: Option[Block] =
    val bestKnownBlockinfo = appStateStorage.getBestBlockInfo()
    log.debug("Trying to get best block with number {}", bestKnownBlockinfo.number)
    val bestBlock = getBlockByHash(BlockHash(bestKnownBlockinfo.hash))
    if bestBlock.isEmpty then
      log.debug(
        "Best block {} (number: {}) not found in storage — expected during SNAP sync (pivot header only).",
        Hex.toHexString(bestKnownBlockinfo.hash.toArray),
        bestKnownBlockinfo.number
      )
    bestBlock

  /** Returns the best-block header even when the body isn't stored locally. This is the common state right after
    * PivotHeaderBootstrap completes for SNAP sync — only the pivot header is persisted (no body, no receipts) until the
    * SNAP→regular handoff or post-SNAP block import populates them. Callers that only need the header (e.g.
    * ConsensusAdapter for branch-resolution) should prefer this over `getBestBlock()`, which returns None in that state
    * and forces them into a `BlockImportFailed` retry loop. Closes #1201's post-bootstrap follow-up.
    */
  def getBestBlockHeader: Option[BlockHeader] =
    val bestKnownBlockinfo = appStateStorage.getBestBlockInfo()
    getBlockHeaderByHash(BlockHash(bestKnownBlockinfo.hash))

  def genesisHeader: BlockHeader =
    getBlockHeaderByNumber(0).getOrElse(throw new IllegalStateException("Genesis header not found"))

  def genesisBlock: Block =
    getBlockByNumber(0).getOrElse(throw new IllegalStateException("Genesis block not found"))

  /** Returns a block inside this branch based on its number */
  def getBlockByNumber(branch: Branch, number: BigInt): Option[Block] = branch match
    case BestBranch(_, tipBlockNumber) if tipBlockNumber >= number && number >= 0 =>
      for
        hash <- getHashByBlockNumber(number)
        block <- getBlockByHash(BlockHash(hash))
      yield block
    case EmptyBranch | BestBranch(_, _) => None

  /** The raw canonical number→hash index entry at `number`, whatever the current best block is — including entries
    * above it, which [[getHashByBlockNumber]] hides. For code that has to read, and later put back, the index exactly
    * as it stands (`ConsensusImpl.reorganise`).
    */
  def getCanonicalHashByNumber(number: BigInt): Option[BlockHash] =
    blockNumberMappingStorage.get(number).map(BlockHash.apply)

  /** Returns a block hash for the block at the given height if any */
  def getHashByBlockNumber(branch: Branch, number: BigInt): Option[BlockHash] = branch match
    case BestBranch(_, tipBlockNumber) =>
      if tipBlockNumber >= number && number >= 0 then blockNumberMappingStorage.get(number).map(BlockHash.apply)
      else None

    case EmptyBranch => None

  /** Checks if given block hash is in this chain. (i.e. is an ancestor of the tip block) */
  def isInChain(branch: Branch, hash: BlockHash): Boolean = branch match
    case BestBranch(_, tipBlockNumber) =>
      (for
        header <- getBlockHeaderByHash(hash) if header.number.value <= tipBlockNumber
        hashFromBestChain <- getHashByBlockNumber(branch, header.number.value)
      yield header.hash == hashFromBestChain).getOrElse(false)
    case EmptyBranch => false

  /** Get an account for an address and a block number
    *
    * @param branch
    *   branch for which we want to get the account
    * @param address
    *   address of the account
    * @param blockNumber
    *   the block that determines the state of the account
    *
    * Spec 018 Track C: the readers behind RPC moved to [[accountAt]] and [[accountIn]], by header (C2b). What is left
    * on this form is the importer's read of a parent's account, on the execution side, which moves with the importer
    * (C5b).
    */
  def getAccount(branch: Branch, address: Address, blockNumber: BigInt): Option[Account] = branch match
    case BestBranch(_, tipBlockNumber) =>
      if blockNumber <= tipBlockNumber then getAccountMpt(blockNumber).flatMap(_.get(address))
      else None
    case EmptyBranch => None

  /** The account at `address` in the state committed to by `stateRoot` — read-only, keyed by the ROOT, not by a block
    * number.
    *
    * Why not [[getAccount]]: that resolves a block NUMBER through the canonical number→hash index, which names the
    * wrong block whenever the caller holds a specific header that is not (or not yet, or no longer) the one the index
    * points at. A proposer building on an explicit parent must read that parent's state and nothing else.
    *
    * `None` means the account does not exist in that state. A trie node missing from storage throws `MPTException`;
    * callers decide what "cannot judge" means for them.
    *
    * Spec 018 Track C: a reader that holds the header uses [[accountAt]] (C2b), and so does the proposer's nonce read
    * since the proposer view (C3c). What is left on this form is the importer's placing of a storage hole (C5b).
    */
  def getAccountAtStateRoot(stateRoot: ByteString, address: Address): Option[Account] =
    MerklePatriciaTrie[Address, Account](
      rootHash = stateRoot.toArray,
      source = stateStorage.getReadOnlyStorage
    ).get(address)

  /** Looks up ChainWeight for a given chain
    * @param blockhash
    *   Hash of top block in the chain
    * @return
    *   ChainWeight if found
    */
  def getChainWeightByHash(blockhash: BlockHash): Option[ChainWeight] = chainWeightStorage.get(blockhash.value)

  /** Whether every stored canonical weight is the genesis weight plus the difficulties up to its block (#1654; see
    * `AppStateStorage.hasChainWeightInvariant`).
    */
  def hasChainWeightInvariant: Boolean = appStateStorage.hasChainWeightInvariant()

  // Batched reads for a walk over the whole chain (`ChainWeightReconcile`, #1654): one `multiGet` per call, one result
  // per key in order, without filling the block cache, so the walk does not push import's working set out.

  /** The canonical hash at each height, as [[getBlockHeaderByNumber]] reads it. */
  def getHashesByNumbersNoFill(numbers: Seq[BigInt]): Seq[Option[ByteString]] =
    blockNumberMappingStorage.getManyNoFill(numbers)

  def getBlockHeadersByHashesNoFill(hashes: Seq[ByteString]): Seq[Option[BlockHeader]] =
    blockHeadersStorage.getManyNoFill(hashes)

  def getChainWeightsByHashesNoFill(hashes: Seq[ByteString]): Seq[Option[ChainWeight]] =
    chainWeightStorage.getManyNoFill(hashes)

  /** ETH/69 TD resolution for PoW chains (ETC). Returns the best available ChainWeight and a source label.
    *
    * Tier 1 (PoW + PoS): exact hash lookup — succeeds when peer's block is in our ChainWeightStorage. Tier 2 (PoW
    * only): canonical block-number lookup — accurate post-bootstrap for any peer ≤ pivot height. Tier 3 (PoW only):
    * proportional estimate — startup fallback when DB has no chain data yet. PoS chains fall back directly to
    * block-number proxy (TD frozen at merge — standard ETH69 behaviour).
    */
  def resolveETH69ChainWeight(
      latestBlockHash: ByteString,
      latestBlock: BigInt,
      isPoWChain: Boolean
  ): (ChainWeight, String) =
    getChainWeightByHash(BlockHash(latestBlockHash)) match
      case Some(cw)            => (cw, "DB_LOOKUP")
      case None if !isPoWChain => (ChainWeight.totalDifficultyOnly(latestBlock), "POS_PROXY")
      case None =>
        getBlockHeaderByNumber(latestBlock).flatMap(h => getChainWeightByHash(h.hash)) match
          case Some(cw) => (cw, "CANONICAL_NUMBER")
          case None =>
            val ourBestNum = getBestBlockNumber
            val bestHeaderOpt = getBestBlockHeader
            val ourBestTD: BigInt = bestHeaderOpt
              .flatMap(h => getChainWeightByHash(h.hash))
              .map(_.totalDifficulty.value)
              .getOrElse(BigInt(1))
            if ourBestNum > 0 then
              val rate = rollingMedianDifficulty.orElse(bestHeaderOpt.map(_.difficulty.value)).getOrElse(BigInt(1))
              val gap = (latestBlock - ourBestNum).max(BigInt(0))
              val estimatedTD = ourBestTD + rate * gap
              (ChainWeight.totalDifficultyOnly(estimatedTD), "POW_SCALING")
            else
              // DB not yet bootstrapped — TD=0 gives peer lowest priority rather than a
              // wrong-magnitude block-number proxy. ETH69_CHAINWEIGHT_REFRESH corrects within 120s.
              (ChainWeight.totalDifficultyOnly(BigInt(0)), "COLD_START")

  private val RollingMedianCapacity = 1_000
  private val difficultyRingBuffer = scala.collection.mutable.ArrayDeque.empty[BigInt]

  /** Record a newly-imported block's difficulty in the in-memory ring buffer.
    *
    * Called by BlockExecution and ChainImporter after each successful block save. Thread-safe via intrinsic lock.
    */
  def recordBlockDifficulty(difficulty: Difficulty): Unit = synchronized {
    difficultyRingBuffer.addOne(difficulty.value)
    if difficultyRingBuffer.length > RollingMedianCapacity then difficultyRingBuffer.removeHead()
  }

  /** Median difficulty of the last 1,000 imported blocks for ETH69 Tier-3 POW_SCALING estimates.
    *
    * Returns None until the buffer has accumulated 1,000 entries — Tier3 falls back to head.difficulty during the
    * cold-start window. For even-length arrays the two middle elements are averaged, giving the true mean for any
    * symmetric bimodal oscillation (e.g. ETC flex-load). This reduces Tier3 estimate variance from ±50% (point-in-time
    * head difficulty) to near-zero under sustained flex-on/flex-off cycling.
    */
  def rollingMedianDifficulty: Option[BigInt] = synchronized {
    if difficultyRingBuffer.length < RollingMedianCapacity then None
    else
      val sorted = difficultyRingBuffer.toVector.sorted
      val mid = sorted.length / 2
      Some((sorted(mid - 1) + sorted(mid)) / 2)
  }

  /** Allows to query for a block based on it's number
    *
    * @param number
    *   Block number
    * @return
    *   Block if it exists
    */
  private def getBlockByNumber(number: BigInt): Option[Block] =
    for
      hash <- getHashByBlockNumber(number)
      block <- getBlockByHash(BlockHash(hash))
    yield block

  /** Returns a block hash given a block number
    *
    * @param number
    *   Number of the searched block
    * @return
    *   Block hash if found
    */
  private def getHashByBlockNumber(number: BigInt): Option[ByteString] =
    blockNumberMappingStorage.get(number)

  private def getAccountMpt(blockNumber: BigInt): Option[MerklePatriciaTrie[Address, Account]] =
    getBlockHeaderByNumber(blockNumber).map { bh =>
      val storage = stateStorage.getBackingStorage(blockNumber)
      MerklePatriciaTrie[Address, Account](
        rootHash = bh.stateRoot.toArray,
        source = storage
      )
    }

object BlockchainReader:

  /** How far behind the best block a state root may be and still be served to a peer: go-ethereum's 128 blocks. */
  val ServedRootWindow: Int = 128

  def apply(
      storages: BlockchainStorages
  ): BlockchainReader = new BlockchainReader(
    storages.blockHeadersStorage,
    storages.blockBodiesStorage,
    storages.blockNumberMappingStorage,
    storages.stateStorage,
    storages.receiptStorage,
    storages.appStateStorage,
    storages.chainWeightStorage,
    storages.blockAccessListStorage,
    storages.pathStateStorage
  )
