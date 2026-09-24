package com.chipprbots.ethereum.domain

import scala.annotation.tailrec

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

  /** Make `head` the canonical head, in ONE atomic batch: afterwards the number→hash index is exactly `head`'s ancestry
    * — every height up to `head` names `head`'s ancestor, and no height above `head` has an entry — and the best block
    * is `head`. go-ethereum's `BlockChain.SetCanonical` (core/blockchain.go: `reorg`, then `writeHeadBlock`). The
    * post-merge fork choice's writer; `ForkChoiceManager.applyForkChoiceState` is its caller.
    *
    * THE BRANCH. Walk back from `head` along parent hashes to the first height AT OR BELOW the current best block whose
    * entry already names the walked block. Up to the best block the index is the best block's ancestry, so that is
    * where the two chains meet and nothing below it needs rewriting. Above the best block nothing guarantees the index
    * — `engine_newPayload` writes entries ahead of the head, and the p2p import path's designated-head arm moves the
    * best block without clearing what lies above it — so a match up there is not trusted: a forkchoiceUpdated back to a
    * longer chain used to stop at such a leftover entry and keep the other branch's hashes below it. Every walked block
    * gets its entry and its transaction locations rewritten; without the latter, eth_getTransactionReceipt answers from
    * the block a side chain re-pointed the transaction at (hive 'Transaction Re-Org, Re-Org to Different Block'). The
    * walk also ends at a missing header, and at genesis.
    *
    * ABOVE THE HEAD. Every height from `head + 1` up to the old best block, and on up while the index has an entry
    * there, is deleted: go-ethereum `reorg` "Delete all hash markers that are not part of the new canonical chain". A
    * head that moves DOWN — a forkchoiceUpdated to an ancestor, or to a shorter side chain — used to leave the old
    * chain's entries above it.
    *
    * Only the index and the best-block pointer move; headers, bodies and receipts stay where they are.
    */
  def promoteToCanonicalHead(head: BlockHeader, reader: BlockchainReader): Unit =
    val oldBest = reader.getBestBlockNumber
    val headNumber = head.number.value

    @tailrec
    def branch(header: BlockHeader, above: List[(BigInt, BlockHash)]): List[(BigInt, BlockHash)] =
      val number = header.number.value
      if number <= oldBest && reader.getCanonicalHashByNumber(number).contains(header.hash) then above
      else
        val withHeader = (number, header.hash) :: above
        if number == 0 then withHeader
        else
          reader.getBlockHeaderByHash(header.parentHash) match
            case Some(parent) => branch(parent, withHeader)
            case None         => withHeader

    val put = branch(head, Nil)
    val remove =
      ((headNumber + 1) to oldBest).toList ++
        LazyList
          .iterate(headNumber.max(oldBest) + 1)(_ + 1)
          .takeWhile(n => reader.getCanonicalHashByNumber(n).isDefined)
          .toList
    // A plain extension rewrites only heights above the old best and clears nothing: not worth a line.
    if remove.nonEmpty || put.exists(_._1 <= oldBest) then
      log.info(
        "Canonical head moved: head=#{} {} previousBest=#{} rewritten={} clearedAboveHead={}",
        headNumber,
        head.hash.toHexString,
        oldBest,
        put.size,
        remove.size
      )
    rewriteCanonicalIndex(put = put, remove = remove, newBest = Some((head.hash, headNumber)), reader = reader)

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
