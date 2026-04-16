package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

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
) extends Logger {

  def save(block: Block, receipts: Seq[Receipt], weight: ChainWeight, saveAsBestBlock: Boolean): Unit = {
    val updateBestBlocks = if (saveAsBestBlock) {
      log.debug(
        "New best known block number - {}",
        block.header.number
      )
      appStateStorage.putBestBlockInfo(BlockInfo(block.header.hash, block.header.number))
    } else {
      appStateStorage.emptyBatchUpdate
    }

    log.debug("Saving new block {} to database", block.idTag)
    storeBlock(block)
      .and(storeReceipts(block.header.hash, receipts))
      .and(storeChainWeight(block.header.hash, weight))
      .and(updateBestBlocks)
      .commit()
  }

  def storeReceipts(blockHash: ByteString, receipts: Seq[Receipt]): DataSourceBatchUpdate =
    receiptStorage.put(blockHash, receipts)

  def storeChainWeight(blockHash: ByteString, weight: ChainWeight): DataSourceBatchUpdate =
    chainWeightStorage.put(blockHash, weight)

  /** Persists a block in the underlying Blockchain Database Note: all store* do not update the database immediately,
    * rather they create a [[com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate]] which then has to be
    * committed (atomic operation)
    *
    * @param block
    *   Block to be saved
    */
  def storeBlock(block: Block): DataSourceBatchUpdate =
    storeBlockHeader(block.header).and(storeBlockBody(block.header.hash, block.body))

  /** Store block by hash only (no number→hash mapping). Used for optimistic/accepted
    * blocks that shouldn't appear in eth_getBlockByNumber until fully validated.
    */
  def storeBlockByHashOnly(block: Block): DataSourceBatchUpdate =
    blockHeadersStorage.put(block.header.hash, block.header)
      .and(blockBodiesStorage.put(block.header.hash, block.body))

  /** Remove block header and body stored by hash. Inverse of storeBlockByHashOnly.
    * Idempotent — no-op if the hash doesn't exist in storage.
    */
  def removeBlockByHash(blockHash: ByteString): DataSourceBatchUpdate =
    blockHeadersStorage.remove(blockHash)
      .and(blockBodiesStorage.remove(blockHash))

  def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate = {
    val hash = blockHeader.hash
    blockHeadersStorage.put(hash, blockHeader).and(saveBlockNumberMapping(blockHeader.number, hash))
  }

  def storeBlockBody(blockHash: ByteString, blockBody: BlockBody): DataSourceBatchUpdate =
    blockBodiesStorage.put(blockHash, blockBody).and(saveTxsLocations(blockHash, blockBody))

  def saveBestKnownBlocks(
      bestBlockHash: ByteString,
      bestBlockNumber: BigInt
  ): Unit =
    appStateStorage.putBestBlockInfo(BlockInfo(bestBlockHash, bestBlockNumber)).commit()

  private def saveBlockNumberMapping(number: BigInt, hash: ByteString): DataSourceBatchUpdate =
    blockNumberMappingStorage.put(number, hash)

  private def saveTxsLocations(blockHash: ByteString, blockBody: BlockBody): DataSourceBatchUpdate =
    blockBody.transactionList.zipWithIndex.foldLeft(transactionMappingStorage.emptyBatchUpdate) {
      case (updates, (tx, index)) =>
        updates.and(transactionMappingStorage.put(tx.hash, TransactionLocation(blockHash, index)))
    }
}

object BlockchainWriter {
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
}
