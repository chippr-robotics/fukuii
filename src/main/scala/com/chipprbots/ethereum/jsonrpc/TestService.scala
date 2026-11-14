package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.consensus.blocks.*
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Block.*
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testmode.SealEngineType
import com.chipprbots.ethereum.testmode.TestModeComponentsProvider
import com.chipprbots.ethereum.testmode.TestModeNode
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.GetPendingTransactionsReq
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.Logger

object TestService:
  case class GenesisParams(
      author: ByteString,
      difficulty: String,
      extraData: ByteString,
      gasLimit: BigInt,
      parentHash: ByteString,
      timestamp: ByteString,
      nonce: ByteString,
      mixHash: ByteString
  )
  case class BlockchainParams(
      EIP150ForkBlock: Option[BigInt],
      EIP158ForkBlock: Option[BigInt],
      accountStartNonce: BigInt,
      allowFutureBlocks: Boolean,
      blockReward: BigInt,
      byzantiumForkBlock: Option[BigInt],
      homesteadForkBlock: Option[BigInt],
      maximumExtraDataSize: BigInt,
      constantinopleForkBlock: Option[BigInt],
      istanbulForkBlock: Option[BigInt],
      berlinForkBlock: Option[BigInt]
  )

  case class ChainParams(
      genesis: GenesisParams,
      blockchainParams: BlockchainParams,
      sealEngine: SealEngineType,
      accounts: Map[ByteString, GenesisAccount]
  )

  case class AccountsInRangeRequestParams(
      blockHashOrNumber: Either[BigInt, ByteString],
      txIndex: BigInt,
      addressHash: ByteString,
      maxResults: Int
  )

  case class AccountsInRange(
      addressMap: Map[ByteString, ByteString],
      nextKey: ByteString
  )

  case class StorageRangeParams(
      blockHashOrNumber: Either[BigInt, ByteString],
      txIndex: BigInt,
      address: ByteString,
      begin: BigInt,
      maxResults: Int
  )

  case class StorageEntry(key: String, value: String)

  case class SetChainParamsRequest(chainParams: ChainParams)
  case class SetChainParamsResponse()

  case class MineBlocksRequest(num: Int)
  case class MineBlocksResponse()

  case class ModifyTimestampRequest(timestamp: Long)
  case class ModifyTimestampResponse()

  case class RewindToBlockRequest(blockNum: Long)
  case class RewindToBlockResponse()

  case class SetEtherbaseRequest(etherbase: Address)
  case class SetEtherbaseResponse()

  case class ImportRawBlockRequest(blockRlp: String)
  case class ImportRawBlockResponse(blockHash: String)

  case class AccountsInRangeRequest(parameters: AccountsInRangeRequestParams)
  case class AccountsInRangeResponse(addressMap: Map[ByteString, ByteString], nextKey: ByteString)

  case class StorageRangeRequest(parameters: StorageRangeParams)
  case class StorageRangeResponse(
      complete: Boolean,
      storage: Map[String, StorageEntry],
      nextKey: Option[String]
  )

  case class GetLogHashRequest(transactionHash: ByteString)
  case class GetLogHashResponse(logHash: ByteString)

  /** What a test-mode request that needs the state of a block other than the head is answered with on the unified state
    * layout, until the head can move to another branch (spec 018 C4b): a reorganisation to a branch that outweighs the
    * head, above all. Test mode reads in no scope of reads, so it reads the head's state and no other.
    */
  val HeadStateOnly: String =
    "on the unified state layout (fukuii.state.path-live) test mode reads the state of its head alone, and this " +
      "needs the state of another block"

  /** What `test_setChainParams` is answered with there when a write of it failed. */
  val ChainNotSet: String = "the chain was not set"

  /** What a rewind to a block between the genesis and the head is answered with there when the head could not be moved
    * back to it (spec 018 C4b part 3b): the move was refused, and nothing was changed.
    */
  val RewindRefused: String =
    "the unified state layout (fukuii.state.path-live) could not move its head back to that block"

  /** A block that was mined on the unified state layout and was not made the head. */
  final class NotImportedToTheHead(result: BlockImportResult)
      extends RuntimeException(
        s"the block that was mined was not imported as the head on the unified state layout: $result"
      )

class TestService(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    pendingTransactionsManager: ActorRef[PendingTransactionsManager.Command],
    miningConfig: MiningConfig,
    testModeComponentsProvider: TestModeComponentsProvider,
    transactionMappingStorage: TransactionMappingStorage,
    node: TestModeNode,
    scheduler: Scheduler
)(implicit ioRuntime: IORuntime)
    extends Logger:
  import node.*

  import TestService.*
  import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*

  private var etherbase: Address = miningConfig.coinbase
  private var accountHashWithAdresses: List[(ByteString, Address)] = List()
  private var blockTimestamp: Long = 0

  /** The chain the last `test_setChainParams` set: what a rewind to the genesis sets again on the unified layout. */
  private var genesisOfThisChain: Option[GenesisData] = None

  private val preimageCache: collection.concurrent.Map[ByteString, UInt256] =
    new collection.concurrent.TrieMap[ByteString, UInt256]()

  def setChainParams(request: SetChainParamsRequest): ServiceResponse[SetChainParamsResponse] =
    val genesisData = GenesisData(
      nonce = request.chainParams.genesis.nonce,
      mixHash = Some(request.chainParams.genesis.mixHash),
      difficulty = request.chainParams.genesis.difficulty,
      extraData = request.chainParams.genesis.extraData,
      gasLimit = "0x" + request.chainParams.genesis.gasLimit.toString(16),
      coinbase = request.chainParams.genesis.author,
      timestamp = Hex.toHexString(request.chainParams.genesis.timestamp.toArray[Byte]),
      alloc = request.chainParams.accounts.map { case (addr, acc) =>
        Hex.toHexString(addr.toArray[Byte]) -> acc
      }
    )

    refusalOnTheUnifiedLayout(genesisData) match
      case Some(refusal) => IO.pure(Left(JsonRpcError.LogicError(refusal)))
      case None          => setChain(request, genesisData)

  /** What the unified state layout refuses of a chain before anything is changed, of the datadir or of this service: a
    * genesis it cannot commit, and a state it would not destroy (a store that was not built for test mode, a state of
    * more keys than one batch deletes). The blocks of the chain before go first, and they must not go from a datadir
    * whose state stays. `None` on the hash store.
    */
  private def refusalOnTheUnifiedLayout(genesisData: GenesisData): Option[String] =
    node.pathStateStorage.flatMap { pathState =>
      GenesisDataLoader
        .unifiedLayoutRefusal(genesisData, GenesisDataLoader.AddressLength)
        .orElse(pathState.refusalToDestroyState())
    }

  private def setChain(
      request: SetChainParamsRequest,
      genesisData: GenesisData
  ): ServiceResponse[SetChainParamsResponse] =
    node.currentBlockchainConfig.set(buildNewConfig(request.chainParams.blockchainParams))

    // clear ledger's cache on test start
    // setChainParams is expected to be the first remote call for each test
    testModeComponentsProvider.clearState()

    // set coinbase for blocks that will be tried to mine
    etherbase = Address(genesisData.coinbase)

    node.currentSealEngine.set(request.chainParams.sealEngine)

    resetPreimages(genesisData)

    setGenesis(genesisData) match
      case Left(failed) =>
        // no chain is set: there is none to rewind to
        genesisOfThisChain = None
        IO.pure(Left(failed))
      case Right(()) =>
        genesisOfThisChain = Some(genesisData)
        accountHashWithAdresses = (etherbase.toUnprefixedString :: genesisData.alloc.keys.toList)
          .map { hexAddress =>
            val address = Address(hexAddress)
            crypto.kec256(address.bytes) -> address
          }
          .sortBy(v => UInt256(v._1))

        SetChainParamsResponse().rightNow

  /** Makes `genesisData` the genesis of this datadir, in place of whatever chain the test before left on it. */
  private def setGenesis(genesisData: GenesisData): Either[JsonRpcError, Unit] =
    node.pathStateStorage match
      case None =>
        // remove current genesis (Try because it may not exist)
        Try(blockchain.removeBlock(blockchainReader.genesisHeader.hash))

        // load the new genesis
        val genesisDataLoader = new GenesisDataLoader(blockchainReader, blockchainWriter, evmCodeStorage, stateStorage)
        genesisDataLoader.loadGenesisData(genesisData)

        // save account codes to world state
        storeGenesisAccountCodes(genesisData.alloc)
        storeGenesisAccountStorageData(genesisData.alloc)
        Right(())

      case Some(pathState) =>
        // The unified state layout (spec 018 Track C, C3c). A genesis is committed there only to a datadir that holds
        // no state, so the chain the test before left is taken away first, and in this order: the blocks go, from the
        // head down, so that no number is left naming a block of the old chain; then the state; then the node's own
        // loader commits the new genesis. The blocks before the state: a genesis block with no layout stamp beside it
        // is what a datadir of the other layout looks like. What the layout refuses was refused before this
        // (`refusalOnTheUnifiedLayout`); what fails here is a write, and it is answered as an error, with a datadir
        // that holds no chain or the state of the old one and none of its blocks. Setting a chain again mends either.
        Try {
          blockchain.removeChainForNewGenesis()
          pathState.destroyStateForNewGenesis()
          // Code is kept by its hash on both layouts, and the loader stores it. The storage tries are rows the genesis
          // commit writes: nothing of them goes to the hash store.
          node.genesisDataLoader.loadGenesisData(genesisData)
        }.flatten match
          case Failure(failed) =>
            log.error("Test mode on the unified state layout: the chain was not set", failed)
            Left(JsonRpcError.LogicError(s"${TestService.ChainNotSet}: ${failed.getMessage}"))
          case Success(()) => Right(())

  val neverOccurringBlock: Int = Int.MaxValue

  private def buildNewConfig(blockchainParams: BlockchainParams) =
    val byzantiumBlockNumber: BigInt = blockchainParams.byzantiumForkBlock.getOrElse(neverOccurringBlock)
    val istanbulForkBlockNumber: BigInt = blockchainParams.istanbulForkBlock.getOrElse(neverOccurringBlock)
    val berlinForkBlockNumber: BigInt = blockchainParams.berlinForkBlock.getOrElse(neverOccurringBlock)

    // For block number which are not specified by retesteth, we try to align the number to another fork
    node.blockchainConfig.copy(
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        homesteadBlockNumber = blockchainParams.homesteadForkBlock.getOrElse(neverOccurringBlock),
        eip150BlockNumber = blockchainParams.EIP150ForkBlock.getOrElse(neverOccurringBlock),
        eip155BlockNumber = byzantiumBlockNumber,
        eip160BlockNumber = byzantiumBlockNumber,
        eip161BlockNumber = byzantiumBlockNumber,
        byzantiumBlockNumber = byzantiumBlockNumber,
        constantinopleBlockNumber = blockchainParams.constantinopleForkBlock.getOrElse(neverOccurringBlock),
        petersburgBlockNumber = istanbulForkBlockNumber,
        aghartaBlockNumber = istanbulForkBlockNumber,
        istanbulBlockNumber = istanbulForkBlockNumber,
        atlantisBlockNumber = istanbulForkBlockNumber,
        phoenixBlockNumber = istanbulForkBlockNumber,
        berlinBlockNumber = berlinForkBlockNumber
      ),
      accountStartNonce = UInt256(blockchainParams.accountStartNonce),
      networkId = 1,
      bootstrapNodes = Set()
    )

  private def storeGenesisAccountCodes(accounts: Map[String, GenesisAccount]): Unit =
    accounts
      .collect { case (_, GenesisAccount(_, _, Some(code), _, _)) => code }
      .foreach(code => evmCodeStorage.put(kec256(code), code).commit())

  private def storeGenesisAccountStorageData(accounts: Map[String, GenesisAccount]): Unit =
    val emptyStorage = domain.EthereumUInt256Mpt.storageMpt(
      Account.EmptyStorageRootHash.value,
      stateStorage.getBackingStorage(0)
    )
    val storagesToPersist = accounts
      .flatMap(pair => pair._2.storage)
      .map(accountStorage => accountStorage.filterNot { case (_, v) => v.isZero })
      .filter(_.nonEmpty)

    val toBigInts: ((UInt256, UInt256)) => (BigInt, BigInt) = { case (a, b) => (a, b) }
    storagesToPersist.foreach(storage => emptyStorage.update(Nil, storage.toSeq.map(toBigInts)))

  def mineBlocks(
      request: MineBlocksRequest
  ): ServiceResponse[MineBlocksResponse] =
    // Deferred: each block is built on the head as it is when its turn comes. Read when this is defined, the head is
    // read once for the whole request, and `test_mineBlocks(n)` mined one block and n - 1 siblings of it.
    def mineBlock(): IO[Unit] =
      IO.defer(
        getBlockForMining(
          blockchainReader.getBestBlock.getOrElse(throw new IllegalStateException("No best block found"))
        )
      ).flatMap { blockForMining =>
        testModeComponentsProvider
          .getConsensus(preimageCache)
          .evaluateBranchBlock(blockForMining.block)
      }.map { res =>
        log.info("Block mining result: " + res)
        pendingTransactionsManager ! PendingTransactionsManager.ClearPendingTransactions
        blockTimestamp += 1
        // On the unified state layout a block that is mined is committed with its state as the head, or it is not
        // there at all: an answer that says it was mined when it was only queued would be found out a call later.
        res match
          case _: BlockImportedToTop => ()
          // answered as the state fault it is, as when it was thrown out of the import
          case BlockImportStateFault(fault, _)             => throw fault
          case other if blockchainReader.holdsStateInPlace => throw new TestService.NotImportedToTheHead(other)
          case _                                           => ()
      }

    def doNTimesF(n: Int)(fn: IO[Unit]): IO[Unit] = fn.flatMap { _ =>
      if n <= 1 then IO.unit
      else doNTimesF(n - 1)(fn)
    }

    doNTimesF(request.num)(mineBlock())
      .as[Either[JsonRpcError, MineBlocksResponse]](Right(MineBlocksResponse()))
      .recover(notOnTheUnifiedLayout)

  def modifyTimestamp(
      request: ModifyTimestampRequest
  ): ServiceResponse[ModifyTimestampResponse] =
    blockTimestamp = request.timestamp
    ModifyTimestampResponse().rightNow

  def rewindToBlock(request: RewindToBlockRequest): ServiceResponse[RewindToBlockResponse] =
    if blockchainReader.holdsStateInPlace then rewindInPlace(request.blockNum) else rewindOnTheHashStore(request)

  /** A rewind on the unified state layout (spec 018 C4b part 3b).
    *
    * The state on disk is the head's. A rewind to the head or above it removes no block, as on the hash store. A rewind
    * to a block between the genesis and the head moves the head, and the state with it, back to that block of the
    * head's chain, along the changeset records ([[BlockchainWriter.moveHeadInPlace]]): the best block, the index of
    * blocks by number and the transaction lookups follow it in the move's batches. The blocks above it are not removed:
    * they are known by their hash, and the changeset records are the way back up. A block imported again above the new
    * head is executed again, as on the hash store. A move that is refused changes nothing, and the pending transactions
    * stay. A rewind to the genesis sets the chain's genesis again, which is the same block and the same state. The
    * blocks in the queue stay, as they do on the hash store.
    */
  private def rewindInPlace(blockNum: Long): ServiceResponse[RewindToBlockResponse] =
    if blockchainReader.getBestBlockNumber <= blockNum then
      pendingTransactionsManager ! PendingTransactionsManager.ClearPendingTransactions
      RewindToBlockResponse().rightNow
    else if blockNum > 0 then IO.blocking(moveBackInPlace(blockNum)).recover(notOnTheUnifiedLayout)
    else
      genesisOfThisChain match
        case None => IO.pure(Left(JsonRpcError.LogicError("no chain was set: test_setChainParams comes first")))
        case Some(genesisData) =>
          pendingTransactionsManager ! PendingTransactionsManager.ClearPendingTransactions
          setGenesis(genesisData) match
            case Left(refused) => IO.pure(Left(refused))
            case Right(())     => RewindToBlockResponse().rightNow

  /** The head moved back to the block at `blockNum` of its own chain, found by the parent hashes of the block whose
    * state is held: never by the index of blocks by number, which is what the move rewrites.
    */
  private def moveBackInPlace(blockNum: Long): Either[JsonRpcError, RewindToBlockResponse] =
    val target = for
      (_, heldHash) <- blockchainReader.heldInPlace
      held <- blockchainReader.getBlockHeaderByHash(BlockHash(heldHash))
      ancestor <- blockchainReader.ancestorAt(held, blockNum)
    yield ancestor
    target match
      case None =>
        Left(JsonRpcError.LogicError(s"${TestService.RewindRefused}: the head's chain has no block $blockNum"))
      case Some(header) =>
        try
          blockchainWriter.moveHeadInPlace(header, blockchainReader)
          pendingTransactionsManager ! PendingTransactionsManager.ClearPendingTransactions
          Right(RewindToBlockResponse())
        catch
          // a block on the way that is held nowhere: said as what it is, a rewind that could not be made
          case unavailable: com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable =>
            log.warn("Test mode on the unified state layout: {}", unavailable.getMessage)
            Left(JsonRpcError.LogicError(s"${TestService.RewindRefused} (${unavailable.reason})"))

  private def rewindOnTheHashStore(request: RewindToBlockRequest): ServiceResponse[RewindToBlockResponse] =
    pendingTransactionsManager ! PendingTransactionsManager.ClearPendingTransactions
    (blockchainReader.getBestBlockNumber until request.blockNum by -1).foreach { n =>
      blockchainReader.getBlockHeaderByNumber(n).foreach { header =>
        blockchain.removeBlock(header.hash)
      }
    }
    RewindToBlockResponse().rightNow

  def importRawBlock(
      request: ImportRawBlockRequest
  ): ServiceResponse[ImportRawBlockResponse] =
    Try(decode(request.blockRlp).toBlock) match
      case Failure(_) =>
        IO.pure(Left(JsonRpcError(-1, "block validation failed!", None)))
      case Success(value) =>
        testModeComponentsProvider
          .getConsensus(preimageCache)
          .evaluateBranchBlock(value)
          .flatMap(handleResult(value))
          .recover(notOnTheUnifiedLayout)

  /** What the unified state layout cannot do before its overlay, said as that and not as a fault of the block: the
    * state of a block that is not the one held was asked for, and refused before anything was written (a branch that
    * outweighs the head reaches a reorganisation; a block is to be mined on a head whose state is not the one the disk
    * holds; a range of the state of a block below the head is read); and a mined block that was not made the head.
    * Never met on the hash store.
    */
  private def notOnTheUnifiedLayout[A]: PartialFunction[Throwable, Either[JsonRpcError, A]] =
    case fault: com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable if blockchainReader.holdsStateInPlace =>
      log.warn("Test mode on the unified state layout: {}", fault.getMessage)
      Left(JsonRpcError.LogicError(s"${TestService.HeadStateOnly} (${fault.getMessage})"))
    case refused: com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep if blockchainReader.holdsStateInPlace =>
      // a record that is not what it should be is this datadir's corruption, as the fork choice says by throwing it
      if refused.reason == com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal.Corrupt then
        log.error("[STATE-MOVE] Test mode on the unified state layout: {}", refused.getMessage)
      else log.warn("Test mode on the unified state layout: {}", refused.getMessage)
      Left(JsonRpcError.LogicError(s"${TestService.RewindRefused} (${refused.getMessage})"))
    case notImported: TestService.NotImportedToTheHead =>
      log.warn("Test mode on the unified state layout: {}", notImported.getMessage)
      Left(JsonRpcError.LogicError(notImported.getMessage))

  private def handleResult(
      block: Block
  )(blockImportResult: BlockImportResult): ServiceResponse[ImportRawBlockResponse] =
    blockImportResult match
      case BlockImportedToTop(blockImportData) =>
        val blockHash = s"0x${ByteStringUtils.hash2string(blockImportData.head.block.header.hash.value)}"
        ImportRawBlockResponse(blockHash).rightNow
      case BlockEnqueued | ChainReorganised(_, _, _) =>
        val blockHash = s"0x${ByteStringUtils.hash2string(block.hash.value)}"
        ImportRawBlockResponse(blockHash).rightNow
      // Not a verdict on the block: answered as the state fault it is, as when it was thrown
      case BlockImportStateFault(fault, _) => IO.raiseError(fault)
      case e =>
        log.warn("Block import failed with {}", e)
        IO.pure(Left(JsonRpcError(-1, "block validation failed!", None)))

  def setEtherbase(req: SetEtherbaseRequest): ServiceResponse[SetEtherbaseResponse] =
    etherbase = req.etherbase
    SetEtherbaseResponse().rightNow

  private def resetPreimages(genesisData: GenesisData): Unit =
    preimageCache.clear()
    for
      (_, account) <- genesisData.alloc
      storage <- account.storage
      storageKey <- storage.keys
    do preimageCache.put(crypto.kec256(storageKey.bytes), storageKey)

  private def getBlockForMining(parentBlock: Block): IO[PendingBlock] =
    given timeout: Timeout = Timeout(20.seconds)
    given sc: Scheduler = scheduler
    pendingTransactionsManager
      .askForTyped[PendingTransactionsResponse](GetPendingTransactionsReq(_))
      .timeout(timeout.duration)
      .recover { case ex =>
        log.error("Error getting transactions", ex)
        PendingTransactionsResponse(Nil)
      }
      .map { pendingTxs =>
        testModeComponentsProvider
          .consensus(blockTimestamp)
          .blockGenerator
          .generateBlock(
            parentBlock,
            pendingTxs.pendingTransactions.map(_.stx.tx),
            etherbase,
            Nil,
            None
          )
          .pendingBlock
      }
      .timeout(timeout.duration)

  /** Get the list of accounts of size _maxResults in the given _blockHashOrNumber after given _txIndex. In response
    * AddressMap contains addressHash - > address starting from given _addressHash. nexKey field is the next addressHash
    * (if any addresses left in the state).
    * @see
    *   https://github.com/ethereum/retesteth/wiki/RPC-Methods#debug_accountrange
    */
  def getAccountsInRange(request: AccountsInRangeRequest): ServiceResponse[AccountsInRangeResponse] =
    ofAStateHeld(accountsInRange(request))

  /** `answer`, which reads the state of a block. On the unified state layout that is the head's alone before the
    * overlay: for another block the read is refused as it starts, and the refusal is answered as that. On the hash
    * store `answer` is what it was.
    */
  private def ofAStateHeld[A](answer: => ServiceResponse[A]): ServiceResponse[A] =
    if blockchainReader.holdsStateInPlace then IO.defer(answer).recover(notOnTheUnifiedLayout) else answer

  private def accountsInRange(request: AccountsInRangeRequest): ServiceResponse[AccountsInRangeResponse] =
    // This implementation works by keeping a list of know account from the genesis state
    // It might not cover all the cases as an account created inside a transaction won't be there.

    val blockOpt = request.parameters.blockHashOrNumber
      .fold(
        number => blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, number),
        blockHash => blockchainReader.getBlockByHash(BlockHash(blockHash))
      )

    if blockOpt.isEmpty then AccountsInRangeResponse(Map(), ByteString(0)).rightNow
    else
      val header = blockOpt.get.header
      val state = blockchainReader.stateAt(header)
      val accountBatch: Seq[(ByteString, Address)] = accountHashWithAdresses.view
        .dropWhile { case (hash, _) => UInt256(hash) < UInt256(request.parameters.addressHash) }
        .filter { case (_, address) => blockchainReader.accountIn(state, header, address).isDefined }
        .take(request.parameters.maxResults + 1)
        .to(Seq)

      val addressMap: Map[ByteString, ByteString] = accountBatch
        .take(request.parameters.maxResults)
        .map { case (hash, address) => hash -> address.bytes }
        .to(Map)

      AccountsInRangeResponse(
        addressMap = addressMap,
        nextKey =
          if accountBatch.size > request.parameters.maxResults then accountBatch.last._1
          else UInt256(0).bytes
      ).rightNow

  /** Get the list of storage values starting from _begin and up to _begin + _maxResults at given block. nexKey field is
    * the next key hash if any key left in the state, or 0x00 otherwise.
    *
    * Normally, this RPC method is supposed to also be able to look up the state after after transaction _txIndex is
    * executed. This is currently not supported in fukuii.
    * @see
    *   https://github.com/ethereum/retesteth/wiki/RPC-Methods#debug_storagerangeat
    */
  def storageRangeAt(request: StorageRangeRequest): ServiceResponse[StorageRangeResponse] =
    ofAStateHeld(storageRange(request))

  private def storageRange(request: StorageRangeRequest): ServiceResponse[StorageRangeResponse] =

    val blockOpt = request.parameters.blockHashOrNumber
      .fold(
        number => blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, number),
        hash => blockchainReader.getBlockByHash(BlockHash(hash))
      )

    (for
      block <- blockOpt.toRight(StorageRangeResponse(complete = false, Map.empty, None))
      address = Address(request.parameters.address)
      state = blockchainReader.stateAt(block.header)
      accountOpt = blockchainReader.accountIn(state, block.header, address)
      account <- accountOpt.toRight(StorageRangeResponse(complete = false, Map.empty, None))
    yield
      // This implementation might be improved. It is working for most tests in ETS but might be
      // not really efficient and would not work outside of a test context. We simply iterate over
      // every key known by the preimage cache.
      val (valueBatch, next) = preimageCache.toSeq
        .sortBy(v => UInt256(v._1))
        .view
        .dropWhile { case (hash, _) => UInt256(hash) < request.parameters.begin }
        .map { case (keyHash, keyValue) =>
          (
            keyHash.toArray,
            keyValue,
            blockchain.getAccountStorageAt(state, address, account.storageRoot.value, keyValue, true)
          )
        }
        .filterNot { case (_, _, storageValue) => storageValue == ByteString(0) }
        .take(request.parameters.maxResults + 1)
        .splitAt(request.parameters.maxResults)

      val storage = valueBatch
        .map { case (keyHash, keyValue, value) =>
          UInt256(keyHash).toHexString -> StorageEntry(keyValue.toHexString, UInt256(value).toHexString)
        }
        .to(Map)

      StorageRangeResponse(
        complete = next.isEmpty,
        storage = storage,
        nextKey = next.headOption.map { case (hash, _, _) => UInt256(hash).toHexString }
      )
    ).fold(identity, identity).rightNow

  def getLogHash(request: GetLogHashRequest): ServiceResponse[GetLogHashResponse] =
    import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*

    val result = for
      transactionLocation <- transactionMappingStorage.get(request.transactionHash)
      block <- blockchainReader.getBlockByHash(BlockHash(transactionLocation.blockHash))
      _ <- block.body.transactionList.lift(transactionLocation.txIndex)
      receipts <- blockchainReader.getReceiptsByHash(block.header.hash)
      logs = receipts.flatMap(receipt => receipt.logs)
      rlpList: RLPList = RLPList(logs.map(_.toRLPEncodable).toList*)
    yield ByteString(crypto.kec256(rlp.encode(rlpList)))

    result.fold(GetLogHashResponse(emptyLogRlpHash))(rlpHash => GetLogHashResponse(rlpHash)).rightNow

  private val emptyLogRlpHash: ByteString = ByteString(crypto.kec256(rlp.encode(RLPList())))

  extension [A](response: A) private def rightNow: IO[Either[JsonRpcError, A]] = IO.pure(Right(response))
