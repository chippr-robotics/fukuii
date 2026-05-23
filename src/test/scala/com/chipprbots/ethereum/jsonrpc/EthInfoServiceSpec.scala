package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum._
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthInfoService._
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.ActorsTesting.simpleAutoPilot
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.PrecompiledContracts

class EthServiceSpec
    extends TestKit(ActorSystem("EthInfoServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals {

  implicit val runtime: IORuntime = IORuntime.global

  "EthInfoService" should "return ethereum protocol version" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val response: Either[JsonRpcError, ProtocolVersionResponse] =
      ethService.protocolVersion(ProtocolVersionRequest()).unsafeRunSync()
    val protocolVersion = response.toOption.get.value

    Integer.parseInt(protocolVersion.drop(2), 16) shouldEqual currentProtocolVersion
  }

  it should "return configured chain id" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val response: ChainIdResponse = ethService.chainId(ChainIdRequest()).unsafeRunSync().toOption.get

    assert(response === ChainIdResponse(blockchainConfig.chainId))
  }

  it should "return syncing info if the peer is syncing" taggedAs (UnitTest, RPCTest) in new TestSetup {
    syncingController.setAutoPilot(simpleAutoPilot { case SyncProtocol.GetStatus =>
      SyncProtocol.Status.Syncing(999, Progress(200, 10000), Some(Progress(100, 144)))
    })

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
  }

  // scalastyle:off magic.number
  it should "return no syncing info if the peer is not syncing" taggedAs (UnitTest, RPCTest) in new TestSetup {
    syncingController.setAutoPilot(simpleAutoPilot { case SyncProtocol.GetStatus =>
      SyncProtocol.Status.NotSyncing
    })

    val response: Either[JsonRpcError, SyncingResponse] = ethService.syncing(SyncingRequest()).unsafeRunSync()

    response shouldEqual Right(SyncingResponse(None))
  }

  it should "return no syncing info if sync is done" taggedAs (UnitTest, RPCTest) in new TestSetup {
    syncingController.setAutoPilot(simpleAutoPilot { case SyncProtocol.GetStatus =>
      SyncProtocol.Status.SyncDone
    })

    val response: Either[JsonRpcError, SyncingResponse] = ethService.syncing(SyncingRequest()).unsafeRunSync()

    response shouldEqual Right(SyncingResponse(None))
  }

  it should "execute call and return a value" taggedAs (UnitTest, RPCTest) in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val worldStateProxy: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    val txResult: TxResult = TxResult(worldStateProxy, 123, Nil, ByteString("return_value"), None)
    (stxLedger.simulateTransaction _).expects(*, *, *).returning(txResult)

    val tx: CallTx = CallTx(
      Some(ByteString(Hex.decode("da714fe079751fa7a1ad80b76571ea6ec52a446c"))),
      Some(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477"))),
      Some(1),
      2,
      3,
      ByteString("")
    )
    val response: ServiceResponse[CallResponse] = ethService.call(CallRequest(tx, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(CallResponse(ByteString("return_value")))
  }

  it should "execute estimateGas and return a value" taggedAs (UnitTest, RPCTest) in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    // `estimateGas` now runs a revert-check simulateTransaction FIRST, then a
    // binarySearchGasEstimation if the tx doesn't revert. Stub both halves.
    val worldStateProxy: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    val nonRevertResult: TxResult = TxResult(worldStateProxy, 123, Nil, ByteString.empty, None)
    (stxLedger.simulateTransaction _).expects(*, *, *).returning(nonRevertResult)

    val estimatedGas: BigInt = BigInt(123)
    (stxLedger.binarySearchGasEstimation _).expects(*, *, *).returning(estimatedGas)

    val tx: CallTx = CallTx(
      Some(ByteString(Hex.decode("da714fe079751fa7a1ad80b76571ea6ec52a446c"))),
      Some(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477"))),
      Some(1),
      2,
      3,
      ByteString("")
    )
    val response: ServiceResponse[EstimateGasResponse] = ethService.estimateGas(CallRequest(tx, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(EstimateGasResponse(123))
  }

  "eth_config" should "include all Olympia BLS12-381 and P-256 precompile addresses in current fork" taggedAs (
    OlympiaTest,
    RPCTest
  ) in new OlympiaConfigTestSetup {
    setBestBlock(101)
    val resp        = ethService.config(ConfigRequest()).unsafeRunSync().toOption.get
    val precompiles = resp.current.get.precompiles
    precompiles should contain key "bls12381G1Add"
    precompiles should contain key "bls12381G2Add"
    precompiles should contain key "bls12381Pairing"
    precompiles should contain key "p256Verify"
    precompiles("bls12381G1Add") shouldBe PrecompiledContracts.BlsG1AddAddr
    precompiles("bls12381G2Add") shouldBe PrecompiledContracts.BlsG2AddAddr
    precompiles("p256Verify")    shouldBe PrecompiledContracts.P256VerifyAddr
  }

  it should "include historyStorage address in Olympia system contracts" taggedAs (
    OlympiaTest,
    RPCTest
  ) in new OlympiaConfigTestSetup {
    setBestBlock(101)
    val resp         = ethService.config(ConfigRequest()).unsafeRunSync().toOption.get
    val sysContracts = resp.current.get.systemContracts
    sysContracts should contain key "historyStorage"
    sysContracts("historyStorage") shouldBe BlockExecution.HistoryStorageAddress
  }

  it should "return None for next and last when all forks are activated" taggedAs (
    OlympiaTest,
    RPCTest
  ) in new OlympiaConfigTestSetup {
    setBestBlock(101)
    val resp = ethService.config(ConfigRequest()).unsafeRunSync().toOption.get
    resp.next shouldBe None
    resp.last shouldBe None
  }

  it should "return Olympia as the next fork when chain head is before activation block" taggedAs (
    OlympiaTest,
    RPCTest
  ) in new OlympiaConfigTestSetup {
    setBestBlock(50)
    val resp = ethService.config(ConfigRequest()).unsafeRunSync().toOption.get
    resp.next should not be None
    resp.next.get.activationBlock shouldBe BigInt(100)
    resp.next.get.systemContracts should contain key "historyStorage"
  }

  // NOTE TestSetup uses Ethash consensus; check `consensusConfig`.
  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup {
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
      syncingController.ref,
      Capability.ETH63,
      Timeouts.shortTimeout
    )

    val blockToRequest: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val txToRequest = Fixtures.Blocks.Block3125369.body.transactionList.head
    val txSender: Address = SignedTransaction.getSender(txToRequest).get
  }

  class OlympiaConfigTestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup {
    override lazy val miningConfig = MiningConfigs.miningConfig

    // All pre-Olympia forks at block 0 — distinctBy(_._2) in config() keeps only
    // Frontier@0 and Olympia@100, giving clean two-entry fork schedule for assertions.
    override implicit lazy val blockchainConfig: BlockchainConfig =
      initBlockchainConfig.withUpdatedForkBlocks(fb =>
        fb.copy(
          frontierBlockNumber  = BigInt(0),
          homesteadBlockNumber = BigInt(0),
          atlantisBlockNumber  = BigInt(0),
          aghartaBlockNumber   = BigInt(0),
          phoenixBlockNumber   = BigInt(0),
          magnetoBlockNumber   = BigInt(0),
          mystiqueBlockNumber  = BigInt(0),
          spiralBlockNumber    = BigInt(0),
          olympiaBlockNumber   = BigInt(100)
        )
      )

    val keyStore: KeyStore                  = mock[KeyStore]
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    val syncingController: TestProbe        = TestProbe()

    lazy val ethService: EthInfoService = new EthInfoService(
      blockchain,
      blockchainReader,
      blockchainConfig,
      mining,
      stxLedger,
      keyStore,
      syncingController.ref,
      Capability.ETH63,
      Timeouts.shortTimeout
    )

    def setBestBlock(n: BigInt): Unit =
      storagesInstance.storages.appStateStorage.putBestBlockNumber(n).commit()
  }
}
