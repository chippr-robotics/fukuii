package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.*
import com.chipprbots.ethereum.domain
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProof
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.Storage
import com.chipprbots.ethereum.vm.WorldStateProxy

/** Entity to be used to persist and query Blockchain related objects (blocks, transactions, ommers)
  */
trait Blockchain:

  type S <: Storage[S]
  type WS <: WorldStateProxy[WS, S]

  /** Get account storage at given position
    *
    * @param rootHash
    *   storage root hash
    * @param position
    *   storage position
    */
  def getAccountStorageAt(rootHash: ByteString, position: BigInt, ethCompatibleStorage: Boolean): ByteString

  /** Get a storage-value and its proof being the path from the root node until the last matching node.
    *
    * @param rootHash
    *   storage root hash
    * @param position
    *   storage position
    */
  def getStorageProofAt(
      rootHash: ByteString,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): StorageProof

  /** Get the MptStorage
    * @param blockNumber
    * @return
    *   MptStorage
    */
  def getBackingMptStorage(blockNumber: BigInt): MptStorage

  /** Get the MptStorage for read-only
    *
    * @return
    *   MptStorage
    */
  def getReadOnlyMptStorage(): MptStorage

  def removeBlock(hash: BlockHash): Unit

  /** Flush all in-memory MPT trie nodes written during block execution to RocksDB.
    *
    * Must be called after each block is saved. Without this, nodes written with `inMemory=true` by
    * ReferenceCountNodeStorage live only in the LRU cache and are lost on restart — no peer can serve them since they
    * were never part of a persisted state root.
    */
  def saveBlockState(bn: BigInt): Unit

class BlockchainImpl(
    protected val blockHeadersStorage: BlockHeadersStorage,
    protected val blockBodiesStorage: BlockBodiesStorage,
    protected val blockNumberMappingStorage: BlockNumberMappingStorage,
    protected val receiptStorage: ReceiptStorage,
    protected val chainWeightStorage: ChainWeightStorage,
    protected val transactionMappingStorage: TransactionMappingStorage,
    protected val appStateStorage: AppStateStorage,
    protected val stateStorage: StateStorage,
    blockchainReader: BlockchainReader
) extends Blockchain
    with Logger:

  override def getAccountStorageAt(
      rootHash: ByteString,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): ByteString =
    val storage = stateStorage.getBackingStorage(0)
    val mpt =
      if ethCompatibleStorage then domain.EthereumUInt256Mpt.storageMpt(rootHash, storage)
      else domain.ArbitraryIntegerMpt.storageMpt(rootHash, storage)

    val bigIntValue = mpt.get(position).getOrElse(BigInt(0))
    val byteArrayValue = bigIntValue.toByteArray

    // BigInt.toArray actually might return one more byte than necessary because it adds a sign bit, which in our case
    // will always be 0. This would add unwanted 0 bytes and might cause the value to be 33 byte long while an EVM
    // word is 32 byte long.
    if bigIntValue != 0 then ByteString(byteArrayValue.dropWhile(_ == 0))
    else ByteString(byteArrayValue)

  override def getStorageProofAt(
      rootHash: ByteString,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): StorageProof =
    val storage: MptStorage = stateStorage.getBackingStorage(0)
    val mpt: MerklePatriciaTrie[BigInt, BigInt] =
      if ethCompatibleStorage then domain.EthereumUInt256Mpt.storageMpt(rootHash, storage)
      else domain.ArbitraryIntegerMpt.storageMpt(rootHash, storage)
    val value: Option[BigInt] = mpt.get(position)
    val proof: Option[Vector[MptNode]] = mpt.getProof(position)
    StorageProof(position, value, proof)

  def getBackingMptStorage(blockNumber: BigInt): MptStorage = stateStorage.getBackingStorage(blockNumber)

  def getReadOnlyMptStorage(): MptStorage = stateStorage.getReadOnlyStorage

  /** State storage for executing block `blockNumber`, its writes held back until committed. See [[StagedBlockState]].
    */
  def stageBlockState(blockNumber: BigInt): StagedBlockState = stateStorage.stageBlock(blockNumber)

  override def saveBlockState(bn: BigInt): Unit =
    stateStorage.onBlockSave(bn, appStateStorage.getBestBlockNumber())(() => ())

  private def removeBlockNumberMapping(number: BigInt): DataSourceBatchUpdate =
    blockNumberMappingStorage.remove(number)

  override def removeBlock(blockHash: BlockHash): Unit =
    val maybeBlock = blockchainReader.getBlockByHash(blockHash)

    maybeBlock match
      case Some(block) => removeBlock(block)
      case None =>
        log.warn(
          s"Attempted removing block with hash ${ByteStringUtils.hash2string(blockHash.value)} that we don't have"
        )

  private def removeBlock(block: Block): Unit =
    val blockHash = block.hash

    log.debug(s"Trying to remove block ${block.idTag}")

    val txList = block.body.transactionList

    val blockNumberMappingUpdates =
      if blockchainReader.getHashByBlockNumber(blockchainReader.getBestBranch, block.number.value).contains(blockHash)
      then removeBlockNumberMapping(block.number.value)
      else blockNumberMappingStorage.emptyBatchUpdate

    val potentialNewBestBlockNumber: BigInt = (block.number.value - 1).max(0)
    val potentialNewBestBlockHash: ByteString = block.header.parentHash.value

    val bestBlockNumberUpdates =
      if appStateStorage.getBestBlockNumber() > potentialNewBestBlockNumber then
        appStateStorage.putBestBlockInfo(BlockInfo(potentialNewBestBlockHash, potentialNewBestBlockNumber))
      else appStateStorage.emptyBatchUpdate

    blockHeadersStorage
      .remove(blockHash.value)
      .and(blockBodiesStorage.remove(blockHash.value))
      .and(chainWeightStorage.remove(blockHash.value))
      .and(receiptStorage.remove(blockHash.value))
      .and(removeTxsLocations(txList))
      .and(blockNumberMappingUpdates)
      .and(bestBlockNumberUpdates)
      .commit()

    log.debug(
      "Removed block with hash {}. New best block number - {}",
      ByteStringUtils.hash2string(blockHash.value),
      potentialNewBestBlockNumber
    )

  private def removeTxsLocations(stxs: Seq[SignedTransaction]): DataSourceBatchUpdate =
    stxs.map(_.hash.value).foldLeft(transactionMappingStorage.emptyBatchUpdate) { case (updates, hash) =>
      updates.and(transactionMappingStorage.remove(hash))
    }

  override type S = InMemoryWorldStateProxyStorage
  override type WS = InMemoryWorldStateProxy

trait BlockchainStorages:
  val blockHeadersStorage: BlockHeadersStorage
  val blockBodiesStorage: BlockBodiesStorage
  val blockNumberMappingStorage: BlockNumberMappingStorage
  val receiptStorage: ReceiptStorage

  /** EIP-7928: the block access list of every Amsterdam block that validated here, by block hash. */
  val blockAccessListStorage: BlockAccessListStorage
  val evmCodeStorage: EvmCodeStorage
  val chainWeightStorage: ChainWeightStorage
  val transactionMappingStorage: TransactionMappingStorage
  val appStateStorage: AppStateStorage
  val stateStorage: StateStorage

object BlockchainImpl:
  def apply(
      storages: BlockchainStorages,
      blockchainReader: BlockchainReader
  ): BlockchainImpl =
    new BlockchainImpl(
      blockHeadersStorage = storages.blockHeadersStorage,
      blockBodiesStorage = storages.blockBodiesStorage,
      blockNumberMappingStorage = storages.blockNumberMappingStorage,
      receiptStorage = storages.receiptStorage,
      chainWeightStorage = storages.chainWeightStorage,
      transactionMappingStorage = storages.transactionMappingStorage,
      appStateStorage = storages.appStateStorage,
      stateStorage = storages.stateStorage,
      blockchainReader = blockchainReader
    )
