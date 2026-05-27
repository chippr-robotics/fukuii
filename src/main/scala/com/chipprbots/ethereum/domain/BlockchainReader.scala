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
) extends Logger {

  /** Allows to query a blockHeader by block hash
    *
    * @param hash
    *   of the block that's being searched
    * @return
    *   [[BlockHeader]] if found
    */
  def getBlockHeaderByHash(hash: ByteString): Option[BlockHeader] =
    blockHeadersStorage.get(hash)

  /** Allows to query a blockBody by block hash
    *
    * @param hash
    *   of the block that's being searched
    * @return
    *   [[com.chipprbots.ethereum.domain.BlockBody]] if found
    */
  def getBlockBodyByHash(hash: ByteString): Option[BlockBody] =
    blockBodiesStorage.get(hash)

  /** Allows to query for a block based on it's hash
    *
    * @param hash
    *   of the block that's being searched
    * @return
    *   Block if found
    */
  def getBlockByHash(hash: ByteString): Option[Block] =
    for {
      header <- getBlockHeaderByHash(hash)
      body <- getBlockBodyByHash(hash)
    } yield Block(header, body)

  def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] =
    for {
      hash <- getHashByBlockNumber(number)
      header <- getBlockHeaderByHash(hash)
    } yield header

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
  def getReceiptsByHash(blockhash: ByteString): Option[Seq[Receipt]] = receiptStorage.get(blockhash)

  /** get the current best stored branch */
  def getBestBranch(): Branch = {
    val number = getBestBlockNumber()
    blockNumberMappingStorage
      .get(number)
      .map(hash => BestBranch(hash, number))
      .getOrElse(EmptyBranch)
  }

  def getBestBlockNumber(): BigInt = appStateStorage.getBestBlockNumber()

  // returns the best known block if it's available in the storage
  def getBestBlock(): Option[Block] = {
    val bestKnownBlockinfo = appStateStorage.getBestBlockInfo()
    log.debug("Trying to get best block with number {}", bestKnownBlockinfo.number)
    val bestBlock = getBlockByHash(bestKnownBlockinfo.hash)
    if (bestBlock.isEmpty) {
      log.debug(
        "Best block {} (number: {}) not found in storage — expected during SNAP sync (pivot header only).",
        Hex.toHexString(bestKnownBlockinfo.hash.toArray),
        bestKnownBlockinfo.number
      )
    }
    bestBlock
  }

  /** Returns the best-block header even when the body isn't stored locally. This is the common state right after
    * PivotHeaderBootstrap completes for SNAP sync — only the pivot header is persisted (no body, no receipts) until the
    * SNAP→regular handoff or post-SNAP block import populates them. Callers that only need the header (e.g.
    * ConsensusAdapter for branch-resolution) should prefer this over `getBestBlock()`, which returns None in that state
    * and forces them into a `BlockImportFailed` retry loop. Closes #1201's post-bootstrap follow-up.
    */
  def getBestBlockHeader(): Option[BlockHeader] = {
    val bestKnownBlockinfo = appStateStorage.getBestBlockInfo()
    getBlockHeaderByHash(bestKnownBlockinfo.hash)
  }

  def genesisHeader: BlockHeader =
    getBlockHeaderByNumber(0).getOrElse(throw new IllegalStateException("Genesis header not found"))

  def genesisBlock: Block =
    getBlockByNumber(0).getOrElse(throw new IllegalStateException("Genesis block not found"))

  /** Returns a block inside this branch based on its number */
  def getBlockByNumber(branch: Branch, number: BigInt): Option[Block] = branch match {
    case BestBranch(_, tipBlockNumber) if tipBlockNumber >= number && number >= 0 =>
      for {
        hash <- getHashByBlockNumber(number)
        block <- getBlockByHash(hash)
      } yield block
    case EmptyBranch | BestBranch(_, _) => None
  }

  /** Returns a block hash for the block at the given height if any */
  def getHashByBlockNumber(branch: Branch, number: BigInt): Option[ByteString] = branch match {
    case BestBranch(_, tipBlockNumber) =>
      if (tipBlockNumber >= number && number >= 0) {
        blockNumberMappingStorage.get(number)
      } else None

    case EmptyBranch => None
  }

  /** Checks if given block hash is in this chain. (i.e. is an ancestor of the tip block) */
  def isInChain(branch: Branch, hash: ByteString): Boolean = branch match {
    case BestBranch(_, tipBlockNumber) =>
      (for {
        header <- getBlockHeaderByHash(hash) if header.number <= tipBlockNumber
        hashFromBestChain <- getHashByBlockNumber(branch, header.number)
      } yield header.hash == hashFromBestChain).getOrElse(false)
    case EmptyBranch => false
  }

  /** Get an account for an address and a block number
    *
    * @param branch
    *   branch for which we want to get the account
    * @param address
    *   address of the account
    * @param blockNumber
    *   the block that determines the state of the account
    */
  def getAccount(branch: Branch, address: Address, blockNumber: BigInt): Option[Account] = branch match {
    case BestBranch(_, tipBlockNumber) =>
      if (blockNumber <= tipBlockNumber)
        getAccountMpt(blockNumber).flatMap(_.get(address))
      else
        None
    case EmptyBranch => None
  }

  def getAccountProof(branch: Branch, address: Address, blockNumber: BigInt): Option[Vector[MptNode]] =
    branch match {
      case BestBranch(_, tipBlockNumber) =>
        if (blockNumber <= tipBlockNumber)
          getAccountMpt(blockNumber).flatMap(_.getProof(address))
        else
          None
      case EmptyBranch => None
    }

  /** Looks up ChainWeight for a given chain
    * @param blockhash
    *   Hash of top block in the chain
    * @return
    *   ChainWeight if found
    */
  def getChainWeightByHash(blockhash: ByteString): Option[ChainWeight] = chainWeightStorage.get(blockhash)

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
    getChainWeightByHash(latestBlockHash) match {
      case Some(cw)            => (cw, "DB_LOOKUP")
      case None if !isPoWChain => (ChainWeight.totalDifficultyOnly(latestBlock), "POS_PROXY")
      case None =>
        getBlockHeaderByNumber(latestBlock).flatMap(h => getChainWeightByHash(h.hash)) match {
          case Some(cw) => (cw, "CANONICAL_NUMBER")
          case None =>
            val ourBestNum = getBestBlockNumber()
            val bestHeaderOpt = getBestBlockHeader()
            val ourBestTD = bestHeaderOpt
              .flatMap(h => getChainWeightByHash(h.hash))
              .map(_.totalDifficulty)
              .getOrElse(BigInt(1))
            if (ourBestNum > 0) {
              val gap = (latestBlock - ourBestNum).max(BigInt(0))
              val rate = bestHeaderOpt.map(h => rollingWindowDiff(h, ourBestTD)).getOrElse(BigInt(1))
              val estimatedTD = ourBestTD + rate * gap
              (ChainWeight.totalDifficultyOnly(estimatedTD), "POW_SCALING")
            } else {
              // DB not yet bootstrapped — TD=0 gives peer lowest priority rather than a
              // wrong-magnitude block-number proxy. ETH69_CHAINWEIGHT_REFRESH corrects within 120s.
              (ChainWeight.totalDifficultyOnly(BigInt(0)), "COLD_START")
            }
        }
    }

  private val Tier3RollingWindow: BigInt = BigInt(10_000)

  /** Mean block difficulty over a rolling window for Tier-3 POW_SCALING estimates.
    *
    * @remarks
    *   Avoids two failure modes: (1) all-time average (headTd/headNumber) is contaminated by ETC's pre-merge
    *   low-hashrate era (~5–30 TH/s before block 15.4M, ~150–250 TH/s after); (2) point-in-time headDifficulty rides
    *   25-35% weekly hashrate swings. The 10K-block window (~36 hours) stays within the current difficulty regime and
    *   transitions naturally through the merge boundary during initial sync. Falls back to head.difficulty when the
    *   window start block is not in our DB (evicted or not yet synced).
    */
  private def rollingWindowDiff(head: BlockHeader, headTd: BigInt): BigInt =
    if (head.number == 0) head.difficulty
    else if (head.number < Tier3RollingWindow) headTd / head.number
    else
      getBlockHeaderByNumber(head.number - Tier3RollingWindow) match {
        case Some(windowHeader) =>
          getChainWeightByHash(windowHeader.hash) match {
            case Some(windowWeight) => (headTd - windowWeight.totalDifficulty) / Tier3RollingWindow
            case None               => head.difficulty
          }
        case None => head.difficulty
      }

  /** Allows to query for a block based on it's number
    *
    * @param number
    *   Block number
    * @return
    *   Block if it exists
    */
  private def getBlockByNumber(number: BigInt): Option[Block] =
    for {
      hash <- getHashByBlockNumber(number)
      block <- getBlockByHash(hash)
    } yield block

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
}

object BlockchainReader {

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

}
