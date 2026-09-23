package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.ReceiptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
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
    chainWeightStorage: ChainWeightStorage
) extends Logger:

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

  /** Returns the receipts based on a block hash
    * @param blockhash
    * @return
    *   Receipts if found
    */
  def getReceiptsByHash(blockhash: BlockHash): Option[Seq[Receipt]] = receiptStorage.get(blockhash.value)

  /** get the current best stored branch */
  def getBestBranch: Branch =
    val number = getBestBlockNumber
    blockNumberMappingStorage
      .get(number)
      .map(hash => BestBranch(hash, number))
      .getOrElse(EmptyBranch)

  def getBestBlockNumber: BigInt = appStateStorage.getBestBlockNumber()

  def getSnapSyncPivotBlock: Option[BigInt] = appStateStorage.getSnapSyncPivotBlock()

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
    */
  def getAccountAtStateRoot(stateRoot: ByteString, address: Address): Option[Account] =
    MerklePatriciaTrie[Address, Account](
      rootHash = stateRoot.toArray,
      source = stateStorage.getReadOnlyStorage
    ).get(address)

  def getAccountProof(branch: Branch, address: Address, blockNumber: BigInt): Option[Vector[MptNode]] =
    branch match
      case BestBranch(_, tipBlockNumber) =>
        if blockNumber <= tipBlockNumber then getAccountMpt(blockNumber).flatMap(_.getProof(address))
        else None
      case EmptyBranch => None

  /** Looks up ChainWeight for a given chain
    * @param blockhash
    *   Hash of top block in the chain
    * @return
    *   ChainWeight if found
    */
  def getChainWeightByHash(blockhash: BlockHash): Option[ChainWeight] = chainWeightStorage.get(blockhash.value)

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

  def apply(
      storages: BlockchainStorages
  ): BlockchainReader = new BlockchainReader(
    storages.blockHeadersStorage,
    storages.blockBodiesStorage,
    storages.blockNumberMappingStorage,
    storages.stateStorage,
    storages.receiptStorage,
    storages.appStateStorage,
    storages.chainWeightStorage
  )
