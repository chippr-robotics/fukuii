package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.*
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.ActorsTesting.syncStatusAutoPilot
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.PreCompiledContractFail
import com.chipprbots.ethereum.vm.PrecompiledContracts

class EthServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic

  "EthInfoService" should "return ethereum protocol version" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val response: Either[JsonRpcError, ProtocolVersionResponse] =
      ethService.protocolVersion(ProtocolVersionRequest()).unsafeRunSync()
    val protocolVersion = response.toOption.get.value

    Integer.parseInt(protocolVersion.drop(2), 16) shouldEqual currentProtocolVersion

  it should "return configured chain id" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val response: ChainIdResponse = ethService.chainId(ChainIdRequest()).unsafeRunSync().toOption.get

    assert(response === ChainIdResponse(blockchainConfig.chainId.value))

  it should "return syncing info if the peer is syncing" taggedAs (UnitTest, RPCTest) in new TestSetup:
    syncingController.setAutoPilot(
      syncStatusAutoPilot(SyncProtocol.Status.Syncing(999, Progress(200, 10000), Some(Progress(100, 144))))
    )

    val response: SyncingResponse = ethService.syncing(SyncingRequest()).unsafeRunSync().toOption.get

    response shouldEqual SyncingResponse(
      Some(
        EthInfoService.SyncingStatus(
          startingBlock = 999,
          currentBlock = 200,
          highestBlock = 10000,
          knownStates = 144,
          pulledStates = 100
        )
      )
    )

  // scalastyle:off magic.number
  it should "return no syncing info if the peer is not syncing" taggedAs (UnitTest, RPCTest) in new TestSetup:
    syncingController.setAutoPilot(syncStatusAutoPilot(SyncProtocol.Status.NotSyncing))

    val response: Either[JsonRpcError, SyncingResponse] = ethService.syncing(SyncingRequest()).unsafeRunSync()

    response shouldEqual Right(SyncingResponse(None))

  it should "return no syncing info if sync is done" taggedAs (UnitTest, RPCTest) in new TestSetup:
    syncingController.setAutoPilot(syncStatusAutoPilot(SyncProtocol.Status.SyncDone))

    val response: Either[JsonRpcError, SyncingResponse] = ethService.syncing(SyncingRequest()).unsafeRunSync()

    response shouldEqual Right(SyncingResponse(None))

  it should "execute call and return a value" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    val worldStateProxy: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    val txResult: TxResult = TxResult(worldStateProxy, 123, Nil, ByteString("return_value"), None)
    stxLedger.simulateCall
      .expects(*, *, *, *, *)
      .returning(Right(StxLedger.CallOutcome(txResult, codeDepositShortfall = false)))

    val tx: TxArgs = TxArgs(
      from = Some(Address("0xda714fe079751fa7a1ad80b76571ea6ec52a446c")),
      to = Some(Address("0xabbb6bebfa05aa13e908eaa492bd7a8343760477")),
      gas = Some(GasAmount(1)),
      gasPrice = Some(GasPrice(2)),
      value = Some(3),
      data = Some(ByteString(""))
    )
    val response: ServiceResponse[CallResponse] = ethService.call(CallRequest(tx, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(CallResponse(ByteString("return_value")))

  // go-ethereum `applyMessageWithEVM` (internal/ethapi/api.go:838-840) wraps every error `core.ApplyMessage` returns
  // before the frame runs (preCheck, buyGas, intrinsic gas, the floor, the top-frame transfer check) as
  // `err: %w (supplied gas %d)`, the gas being the message's gas limit after CallDefaults. eth_call (`DoCall`) is the
  // one endpoint that wraps; eth_createAccessList and eth_estimateGas have their own wording.
  it should "wrap a call refused before execution as go-ethereum's eth_call does: err: ... (supplied gas N)" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)
    val refused: String = "insufficient funds for transfer: address 0xdA714fE079751FA7a1aD80b76571EA6Ec52a446c"
    stxLedger.simulateCall.expects(*, *, *, *, *).returning(Left(StxLedger.CallPreCheckError(refused)))
    val tx: TxArgs = TxArgs(
      from = Some(Address("0xda714fe079751fa7a1ad80b76571ea6ec52a446c")),
      to = Some(Address("0xabbb6bebfa05aa13e908eaa492bd7a8343760477")),
      gas = Some(GasAmount(50000)),
      value = Some(3)
    )
    ethService.call(CallRequest(tx, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Left(JsonRpcError(-32000, s"err: $refused (supplied gas 50000)", None))

  // A pre-Homestead creation that runs its init code but cannot pay its code deposit succeeds in the frame (ETC blocks
  // 0-1,149,999: no code, gas and state kept, `vmError` None). go-ethereum's and core-geth's `create` return
  // ErrCodeStoreOutOfGas as the message's error there, which each endpoint reports as it reports any VM error.
  it should "report a pre-Homestead creation short of its code deposit as go-ethereum's ErrCodeStoreOutOfGas" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)
    val world: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    val shortfall: StxLedger.CallOutcome = StxLedger.CallOutcome(
      TxResult(world, 41000, Nil, ByteString(Array.fill[Byte](1000)(0)), None),
      codeDepositShortfall = true
    )
    val creation: TxArgs = TxArgs(
      from = Some(Address("0xda714fe079751fa7a1ad80b76571ea6ec52a446c")),
      gas = Some(GasAmount(60000)),
      nonce = Some(0),
      data = Some(ByteString(org.bouncycastle.util.encoders.Hex.decode("6103e86000f3")))
    )
    val codeStorageOutOfGas: String = "contract creation code storage out of gas"

    // eth_call: Call returns result.Return() (nil) and result.Err: -32000, no data
    stxLedger.simulateCall.expects(*, *, *, *, *).returning(Right(shortfall))
    ethService.call(CallRequest(creation, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Left(JsonRpcError(-32000, codeStorageOutOfGas, None))

    // eth_createAccessList: the error member is vmerr.Error()
    stxLedger.worldAt.expects(*).returning(world)
    stxLedger.simulateCall.expects(*, *, *, *, *).returning(Right(shortfall))
    stxLedger.callFloorGas.expects(*, *).returning(None)
    ethService.createAccessList(CreateAccessListRequest(creation, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Right(CreateAccessListResponse(Nil, BigInt(41000), Some(codeStorageOutOfGas)))

    // eth_estimateGas: the estimator's answer at the ceiling (StxLedgerSpec runs it), -32000 with no data
    stxLedger.estimateCallGas
      .expects(*, *, *, *)
      .returning(Left(StxLedger.CallEstimateFailure.Failed(com.chipprbots.ethereum.vm.CodeStoreOutOfGas)))
    ethService.estimateGas(CallRequest(creation, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Left(JsonRpcError(-32000, codeStorageOutOfGas, None))

  // #1727 PR 7b: a failed precompile reads as the error its go-ethereum Run returns (vm.PrecompileFailureReasonSpec has
  // every precompile); eth_call answers it as any VM error, -32000 with no data.
  it should "report a failed precompile with go-ethereum's reason" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)
    val world: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    val notOnCurve: ByteString = UInt256(1).bytes ++ UInt256(1).bytes
    val failed: PreCompiledContractFail = PreCompiledContractFail(PrecompiledContracts.Bn128Add, notOnCurve)
    stxLedger.simulateCall
      .expects(*, *, *, *, *)
      .returning(
        Right(StxLedger.CallOutcome(TxResult(world, 50000, Nil, ByteString.empty, Some(failed)), false))
      )
    val call: TxArgs = TxArgs(
      from = Some(Address("0xda714fe079751fa7a1ad80b76571ea6ec52a446c")),
      to = Some(PrecompiledContracts.Bn128AddAddr),
      gas = Some(GasAmount(50000)),
      data = Some(notOnCurve)
    )
    ethService.call(CallRequest(call, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Left(JsonRpcError(-32000, "point is not on curve", None))

  it should "execute estimateGas and return a value" taggedAs (UnitTest, RPCTest) in new TestSetup:
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number.value)

    // eth_estimateGas runs StxLedger.estimateCallGas (#1629 S3), which does the revert check at the ceiling and the
    // binary search itself.
    val estimatedGas: BigInt = BigInt(123)
    stxLedger.estimateCallGas.expects(*, *, *, *).returning(Right(estimatedGas))

    val tx: TxArgs = TxArgs(
      from = Some(Address("0xda714fe079751fa7a1ad80b76571ea6ec52a446c")),
      to = Some(Address("0xabbb6bebfa05aa13e908eaa492bd7a8343760477")),
      gas = Some(GasAmount(1)),
      gasPrice = Some(GasPrice(2)),
      value = Some(3),
      data = Some(ByteString(""))
    )
    val response: ServiceResponse[EstimateGasResponse] = ethService.estimateGas(CallRequest(tx, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(EstimateGasResponse(123))

  // NOTE TestSetup uses Ethash consensus; check `consensusConfig`.
  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup:
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val appStateStorage: AppStateStorage = mock[AppStateStorage]
    val keyStore: KeyStore = mock[KeyStore]
    override lazy val stxLedger: StxLedger = mock[StxLedger]

    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val syncingController: TestProbe = TestProbe()

    val currentProtocolVersion = Capability.ETH63.version

    lazy val ethService = new EthInfoService(
      blockchain,
      blockchainReader,
      blockchainConfig,
      mining,
      stxLedger,
      keyStore,
      syncingController.ref.toTyped[SyncController.Command],
      Capability.ETH63,
      Timeouts.shortTimeout,
      system.toTyped.scheduler
    )

    val blockToRequest: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val txToRequest = Fixtures.Blocks.Block3125369.body.transactionList.head
    val txSender: Address = SignedTransaction.getSender(txToRequest).get
