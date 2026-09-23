package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.ReceiptStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.utils.Logger

class BlockchainWriter(
    blockHeadersStorage: BlockHeadersStorage,
    blockBodiesStorage: BlockBodiesStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    transactionMappingStorage: TransactionMappingStorage,
    receiptStorage: ReceiptStorage,
    chainWeightStorage: ChainWeightStorage,
    appStateStorage: AppStateStorage
) extends Logger:

  def save(block: Block, receipts: Seq[Receipt], weight: ChainWeight, saveAsBestBlock: Boolean): Unit =
    val updateBestBlocks = if saveAsBestBlock then
      log.debug(
        "New best known block number - {}",
        block.header.number
      )
      appStateStorage.putBestBlockInfo(BlockInfo(block.header.hash.value, block.header.number.value))
    else appStateStorage.emptyBatchUpdate

    log.debug("Saving new block {} to database", block.idTag)
    storeBlock(block)
      .and(storeReceipts(block.header.hash, receipts))
      .and(storeChainWeight(block.header.hash, weight))
      .and(updateBestBlocks)
      .commit()

  def storeReceipts(blockHash: BlockHash, receipts: Seq[Receipt]): DataSourceBatchUpdate =
    receiptStorage.put(blockHash.value, receipts)

  def storeChainWeight(blockHash: BlockHash, weight: ChainWeight): DataSourceBatchUpdate =
    chainWeightStorage.put(blockHash.value, weight)

  /** Persists a block in the underlying Blockchain Database Note: all store* do not update the database immediately,
    * rather they create a [[com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate]] which then has to be
    * committed (atomic operation)
    *
    * @param block
    *   Block to be saved
    */
  def storeBlock(block: Block): DataSourceBatchUpdate =
    storeBlockHeader(block.header).and(storeBlockBody(block.header.hash, block.body))

  /** Store block by hash only (no number→hash mapping). Used for optimistic/accepted blocks that shouldn't appear in
    * eth_getBlockByNumber until fully validated.
    */
  def storeBlockByHashOnly(block: Block): DataSourceBatchUpdate =
    blockHeadersStorage
      .put(block.header.hash.value, block.header)
      .and(blockBodiesStorage.put(block.header.hash.value, block.body))

  /** Remove block header and body stored by hash. Inverse of storeBlockByHashOnly. Idempotent — no-op if the hash
    * doesn't exist in storage.
    */
  def removeBlockByHash(blockHash: BlockHash): DataSourceBatchUpdate =
    blockHeadersStorage
      .remove(blockHash.value)
      .and(blockBodiesStorage.remove(blockHash.value))

  def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate =
    val hash = blockHeader.hash
    blockHeadersStorage.put(hash.value, blockHeader).and(saveBlockNumberMapping(blockHeader.number.value, hash))

  def storeBlockBody(blockHash: BlockHash, blockBody: BlockBody): DataSourceBatchUpdate =
    blockBodiesStorage.put(blockHash.value, blockBody).and(saveTxsLocations(blockHash, blockBody))

  def saveBestKnownBlocks(
      bestBlockHash: BlockHash,
      bestBlockNumber: BigInt
  ): Unit =
    appStateStorage.putBestBlockInfo(BlockInfo(bestBlockHash.value, bestBlockNumber)).commit()

  /** Roll back the canonical chain index to `targetNumber`, removing number→hash entries for all blocks above
    * `targetNumber`. Used by fork recovery (SYNC-FORK) to truncate stale canonical chain entries before re-syncing from
    * the fork point.
    *
    * Only the number→hash index is modified — block headers/bodies/receipts are kept in storage. Orphaned header
    * entries are benign in RocksDB and will be compacted away in time.
    *
    * No-op if `currentBest <= targetNumber`.
    */
  def setCanonicalChainHead(targetNumber: BigInt, targetHash: BlockHash, currentBest: BigInt): Unit =
    if currentBest > targetNumber then
      val batch = ((targetNumber + 1) to currentBest).foldLeft(blockNumberMappingStorage.emptyBatchUpdate) { (acc, n) =>
        acc.and(blockNumberMappingStorage.remove(n))
      }
      batch.and(appStateStorage.putBestBlockInfo(BlockInfo(targetHash.value, targetNumber))).commit()

  /** Rewrite part of the canonical number→hash index in ONE atomic batch: write `put` (and re-point the transaction
    * locations of those blocks at them), delete the `remove` heights, and move the best block if `newBest` is given.
    *
    * The index half of core-geth's `reorg()` + `writeHeadBlock` (core/blockchain.go): after it, the index is exactly
    * the head's ancestry — no entry from another chain below the head, none at all above it. `ConsensusImpl.reorganise`
    * uses it both to adopt a new head and to put the old head's entries back when it keeps the old head.
    */
  def rewriteCanonicalIndex(
      put: Seq[(BigInt, BlockHash)],
      remove: Seq[BigInt],
      newBest: Option[(BlockHash, BigInt)],
      reader: BlockchainReader
  ): Unit =
    val withPuts = put.foldLeft(blockNumberMappingStorage.emptyBatchUpdate) { case (acc, (number, hash)) =>
      val withNumber = acc.and(blockNumberMappingStorage.put(number, hash.value))
      reader.getBlockBodyByHash(hash).fold(withNumber)(body => withNumber.and(saveTxsLocations(hash, body)))
    }
    val withRemoves = remove.foldLeft(withPuts)((acc, number) => acc.and(blockNumberMappingStorage.remove(number)))
    newBest
      .fold(withRemoves) { case (hash, number) =>
        withRemoves.and(appStateStorage.putBestBlockInfo(BlockInfo(hash.value, number)))
      }
      .commit()

  /** Promote a block previously stored by hash only (sidechain) to the canonical chain. Walks back from `headHash`
    * along parent pointers until it meets the current canonical chain (i.e. finds a header whose number→hash mapping
    * already points to it) and rewrites number→hash for every block on the new branch. Receipts are already indexed by
    * hash so no extra work.
    *
    * Intended for use by `ForkChoiceManager.applyForkChoiceState` on reorgs.
    *
    * @return
    *   unit; caller is responsible for also updating best-block pointer.
    */
  def promoteBranchToCanonical(
      headHash: BlockHash,
      reader: com.chipprbots.ethereum.domain.BlockchainReader
  ): Unit =
    var cursor: Option[BlockHash] = Some(headHash)
    val buf = scala.collection.mutable.ListBuffer.empty[(BigInt, BlockHash)]
    while cursor.isDefined do
      val hash = cursor.get
      reader.getBlockHeaderByHash(hash) match
        case None => cursor = None
        case Some(header) =>
          val canonicalHashAtNumber = reader.getBlockHeaderByNumber(header.number.value).map(_.hash)
          if canonicalHashAtNumber.contains(hash) then
            // reached existing canonical ancestor — stop
            cursor = None
          else
            buf += ((header.number.value, hash))
            if header.number == BlockNumber.Zero then cursor = None
            else cursor = Some(header.parentHash)
    if buf.nonEmpty then
      // Rewrite number→hash AND tx-location for every block on the newly canonical branch.
      // Without the tx-location rewrite, eth_getTransactionReceipt returns the old (now
      // sidechain) block via the stale mapping — hive's 'Transaction Re-Org, Re-Org to
      // Different Block' checks that the receipt reflects the new canonical block.
      val batch = buf.foldLeft(blockNumberMappingStorage.emptyBatchUpdate) { case (acc, (num, hash)) =>
        val withNumberMapping = acc.and(blockNumberMappingStorage.put(num, hash.value))
        reader.getBlockBodyByHash(hash) match
          case Some(body) =>
            body.transactionList.zipWithIndex.foldLeft(withNumberMapping) { case (a, (tx, idx)) =>
              a.and(transactionMappingStorage.put(tx.hash.value, TransactionLocation(hash.value, idx)))
            }
          case None => withNumberMapping
      }
      batch.commit()

  private def saveBlockNumberMapping(number: BigInt, hash: BlockHash): DataSourceBatchUpdate =
    blockNumberMappingStorage.put(number, hash.value)

  private def saveTxsLocations(blockHash: BlockHash, blockBody: BlockBody): DataSourceBatchUpdate =
    blockBody.transactionList.zipWithIndex.foldLeft(transactionMappingStorage.emptyBatchUpdate) {
      case (updates, (tx, index)) =>
        updates.and(transactionMappingStorage.put(tx.hash.value, TransactionLocation(blockHash.value, index)))
    }

object BlockchainWriter:
  def apply(storages: BlockchainStorages): BlockchainWriter =
    new BlockchainWriter(
      storages.blockHeadersStorage,
      storages.blockBodiesStorage,
      storages.blockNumberMappingStorage,
      storages.transactionMappingStorage,
      storages.receiptStorage,
      storages.chainWeightStorage,
      storages.appStateStorage
    )
