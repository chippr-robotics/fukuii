package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.ReorgStateHandler
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

  /** The value at `position` in the storage of `address`.
    *
    * A storage trie is read under its account (spec 018 Track C): on a position-keyed store a storage root says nothing
    * about where its trie is, and two accounts with the same storage hold two tries.
    *
    * @param state
    *   the state the account was read from ([[BlockchainReader.stateAt]]): the account and its storage are read from
    *   the same state, or a state that moved between the two reads would answer for two blocks
    * @param storageRoot
    *   the account's storage root in that state
    */
  def getAccountStorageAt(
      state: MptStorage,
      address: Address,
      storageRoot: ByteString,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): ByteString

  /** The value at `position` in the storage of `address`, with its proof: the path from the storage root to the last
    * matching node. `state` and `storageRoot` as for [[getAccountStorageAt]].
    */
  def getStorageProofAt(
      state: MptStorage,
      address: Address,
      storageRoot: ByteString,
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

  /** Removes a block, and moves the best block to its parent where it was the best. Refused on the unified state
    * layout, where the best block moves only with the state: see [[removeChainForNewGenesis]].
    */
  def removeBlock(hash: BlockHash): Unit

  /** Removes every block of the chain, from the best down to the genesis, before test mode sets a new genesis
    * (`test_setChainParams`, a rewind to the genesis). On the unified state layout only where the state may be
    * destroyed (test mode), and only with it: the caller destroys the state next.
    */
  def removeChainForNewGenesis(): Unit

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
    blockchainReader: BlockchainReader,
    pathState: Option[pathstate.PathStateStorage] = None
) extends Blockchain
    with ReorgStateHandler
    with Logger:

  override def getAccountStorageAt(
      state: MptStorage,
      address: Address,
      storageRoot: ByteString,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): ByteString =
    val bigIntValue = storageOf(state, address, storageRoot, ethCompatibleStorage).get(position).getOrElse(BigInt(0))
    val byteArrayValue = bigIntValue.toByteArray

    // BigInt.toArray actually might return one more byte than necessary because it adds a sign bit, which in our case
    // will always be 0. This would add unwanted 0 bytes and might cause the value to be 33 byte long while an EVM
    // word is 32 byte long.
    if bigIntValue != 0 then ByteString(byteArrayValue.dropWhile(_ == 0))
    else ByteString(byteArrayValue)

  override def getStorageProofAt(
      state: MptStorage,
      address: Address,
      storageRoot: ByteString,
      position: BigInt,
      ethCompatibleStorage: Boolean
  ): StorageProof =
    val slots = storageOf(state, address, storageRoot, ethCompatibleStorage)
    val value: Option[BigInt] = slots.get(position)
    val proof: Option[Vector[MptNode]] = slots.getProof(position)
    StorageProof(position, value, proof)

  /** The storage trie of `address`, over the view of `state` that is under that account. */
  private def storageOf(
      state: MptStorage,
      address: Address,
      storageRoot: ByteString,
      ethCompatibleStorage: Boolean
  ): MerklePatriciaTrie[BigInt, BigInt] =
    val nodes = state.forOwner(Some(InMemoryWorldStateProxy.storageOwner(address)))
    if ethCompatibleStorage then domain.EthereumUInt256Mpt.storageMpt(storageRoot, nodes)
    else domain.ArbitraryIntegerMpt.storageMpt(storageRoot, nodes)

  /** The hash store, for a block executed with nothing staged.
    *
    * @throws IllegalStateException
    *   on the unified layout (spec 018 data-model 7.6): a path-live store has one writer, the staged commit, and an
    *   execution that asks for this would write, or read, a store that is not this chain's state
    */
  def getBackingMptStorage(blockNumber: BigInt): MptStorage =
    if pathState.isDefined then
      throw new IllegalStateException(
        s"Block $blockNumber is executed with nothing staged, on the unified state layout: " +
          "every execution there runs on a staged layer or on a view"
      )
    stateStorage.getBackingStorage(blockNumber)

  def getReadOnlyMptStorage(): MptStorage = stateStorage.getReadOnlyStorage

  /** State storage for executing the block of `header`, its writes held back until committed. See [[StagedBlockState]].
    *
    * By the header: the unified layout stages a block over its parent's state, which it finds by the parent's hash, and
    * holds what the block wrote to the state root the header carries. The hash store needs the number alone.
    */
  def stageBlockState(header: BlockHeader): StagedBlockState = staging(header, apart = false)

  /** [[stageBlockState]] for a block whose state is committed apart from the block ([[StagedBlockState.commitApart]]):
    * a payload executed ahead of the head. On the unified layout its parent may be any block whose state is held: the
    * anchor, an executed block kept beside it, a block behind it that the changeset records lead to, or a block on a
    * fork from one of those (spec 018 C4d part 2a). The hash store stages it as [[stageBlockState]] does.
    */
  def stageBlockStateApart(header: BlockHeader): StagedBlockState = staging(header, apart = true)

  private def staging(header: BlockHeader, apart: Boolean): StagedBlockState =
    pathState.fold(stateStorage.stageBlock(header.number.value)) { state =>
      new pathstate.PathStagedBlock(
        state,
        refOf(header),
        () => blockchainReader.getBlockHeaderByHash(header.parentHash).map(refOf),
        apart
      )
    }

  private def refOf(header: BlockHeader): pathstate.BlockRef =
    pathstate.BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  /** See [[StateStorage.onBlocksAbandoned]]. */
  override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = stateStorage.onBlocksAbandoned(blocks)

  /** See [[StateStorage.onBlocksReadopted]]. */
  override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = stateStorage.onBlocksReadopted(blocks)

  override def saveBlockState(bn: BigInt): Unit =
    stateStorage.onBlockSave(bn, appStateStorage.getBestBlockNumber())(() => ())

  private def removeBlockNumberMapping(number: BigInt): DataSourceBatchUpdate =
    blockNumberMappingStorage.remove(number)

  override def removeBlock(blockHash: BlockHash): Unit =
    if pathState.isDefined then
      throw new IllegalStateException(
        s"Block ${ByteStringUtils.hash2string(blockHash.value)} cannot be removed on the unified state layout: removing " +
          "it moves the best block without the state, which moves only with a move of its head"
      )
    removeAnyLayout(blockHash)

  override def removeChainForNewGenesis(): Unit =
    pathState.flatMap(_.refusalToDestroyState()).foreach(refused => throw new IllegalStateException(refused))
    (blockchainReader.getBestBlockNumber to BigInt(0) by -1).foreach { number =>
      blockchainReader.getBlockHeaderByNumber(number).foreach(header => removeAnyLayout(header.hash))
    }

  private def removeAnyLayout(blockHash: BlockHash): Unit =
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

  /** The read side of the unified, position-keyed state layout (spec 018 Track C). Present only behind the
    * in-development guard `fukuii.state.path-live`; nothing reads it otherwise.
    */
  def pathStateStorage: Option[pathstate.PathStateStorage] = None

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
      blockchainReader = blockchainReader,
      pathState = storages.pathStateStorage
    )
