package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.scalamock.scalatest.MockFactory

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.CheckpointBlockGenerator
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.Checkpoint
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.BloomFilter
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.FilterConfig

/** Factory for creating JsonRpcControllerFixture instances with mocks.
  * This is needed because in Scala 3, MockFactory requires TestSuite self-type,
  * which anonymous classes created by 'new' don't satisfy.
  */
object JsonRpcControllerFixture {
  def apply()(implicit system: ActorSystem, mockFactory: org.scalamock.scalatest.MockFactory): JsonRpcControllerFixture = {
    new JsonRpcControllerFixture()(system, mockFactory)
  }
}

class JsonRpcControllerFixture(implicit system: ActorSystem, mockFactory: org.scalamock.scalatest.MockFactory)
    extends EphemBlockchainTestSetup
    with JsonMethodsImplicits
    with ApisBuilder {

  // Import all mockFactory members to enable mock creation and expectations
  import mockFactory._

  def config: JsonRpcConfig = JsonRpcConfig(Config.config, available)

  def rawTrnHex(xs: Seq[SignedTransaction], idx: Int): Option[JString] =
    xs.lift(idx)
      .map(encodeSignedTrx)

  def encodeSignedTrx(x: SignedTransaction): JString =
    encodeAsHex(RawTransactionCodec.asRawTransaction(x))

  val version = Config.clientVersion
  val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]

  val syncingController: TestProbe = TestProbe()

  override lazy val stxLedger: StxLedger = mock[StxLedger]
  override lazy val validators: ValidatorsExecutor = {
    val v = mock[ValidatorsExecutor]
    (() => v.signedTransactionValidator)
      .expects()
      .returns(null)
      .anyNumberOfTimes()
    v
  }

  override lazy val mining: TestMining = buildTestMining()
    .withValidators(validators)
    .withBlockGenerator(blockGenerator)

  val keyStore: KeyStore = mock[KeyStore]

  val pendingTransactionsManager: TestProbe = TestProbe()
  val ommersPool: TestProbe = TestProbe()
  val filterManager: TestProbe = TestProbe()

  val ethashConfig = MiningConfigs.ethashConfig
  override lazy val miningConfig = MiningConfigs.miningConfig
  val fullMiningConfig = MiningConfigs.fullMiningConfig
  val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds

  val filterConfig: FilterConfig = new FilterConfig {
    override val filterTimeout: FiniteDuration = Timeouts.normalTimeout
    override val filterManagerQueryTimeout: FiniteDuration = Timeouts.normalTimeout
  }

  val appStateStorage: AppStateStorage = mock[AppStateStorage]
  val web3Service = new Web3Service
  // MIGRATION: Scala 3 mock cannot infer AtomicReference type parameter - create real instance
  val netService: NetService = new NetService(
    new java.util.concurrent.atomic.AtomicReference(com.chipprbots.ethereum.utils.NodeStatus(
      com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom),
      com.chipprbots.ethereum.utils.ServerStatus.NotListening, 
      com.chipprbots.ethereum.utils.ServerStatus.NotListening
    )),
    org.apache.pekko.testkit.TestProbe()(system).ref,
    com.chipprbots.ethereum.jsonrpc.NetService.NetServiceConfig(scala.concurrent.duration.DurationInt(5).seconds)
  )

  val ethInfoService = new EthInfoService(
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

  val ethMiningService = new EthMiningService(
    blockchainReader,
    mining,
    config,
    ommersPool.ref,
    syncingController.ref,
    pendingTransactionsManager.ref,
    getTransactionFromPoolTimeout,
    this
  )

  val ethBlocksService = new EthBlocksService(blockchain, blockchainReader, mining, blockQueue)

  val ethTxService = new EthTxService(
    blockchain,
    blockchainReader,
    mining,
    pendingTransactionsManager.ref,
    getTransactionFromPoolTimeout,
    storagesInstance.storages.transactionMappingStorage
  )

  val ethUserService = new EthUserService(
    blockchain,
    blockchainReader,
    mining,
    storagesInstance.storages.evmCodeStorage,
    this
  )

  val ethFilterService = new EthFilterService(
    filterManager.ref,
    filterConfig
  )
  val personalService: PersonalService = mock[PersonalService]
  val debugService: DebugService = mock[DebugService]
  val qaService: QAService = mock[QAService]
  val checkpointingService: CheckpointingService = mock[CheckpointingService]
  val mantisService: MantisService = mock[MantisService]

  def jsonRpcController: JsonRpcController =
    JsonRpcController(
      web3Service,
      netService,
      ethInfoService,
      ethMiningService,
      ethBlocksService,
      ethTxService,
      ethUserService,
      ethFilterService,
      personalService,
      None,
      debugService,
      qaService,
      checkpointingService,
      mantisService,
      ProofServiceDummy,
      config
    )

  val blockHeader: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
    logsBloom = BloomFilter.EmptyBloomFilter,
    difficulty = 10,
    number = 2,
    gasLimit = 0,
    gasUsed = 0,
    unixTimestamp = 0
  )

  val checkpoint: Checkpoint = ObjectGenerators.fakeCheckpointGen(2, 5).sample.get
  val checkpointBlockGenerator = new CheckpointBlockGenerator()
  val blockWithCheckpoint: Block = checkpointBlockGenerator.generate(Fixtures.Blocks.Block3125369.block, checkpoint)
  val blockWithTreasuryOptOut: Block =
    Block(
      Fixtures.Blocks.Block3125369.header.copy(extraFields = HefEmpty),
      Fixtures.Blocks.Block3125369.body
    )

  val parentBlock: Block = Block(blockHeader.copy(number = 1), BlockBody.empty)

  val r: ByteString = ByteString(Hex.decode("a3f20717a250c2b0b729b7e5becbff67fdaef7e0699da4de7ca5895b02a170a1"))
  val s: ByteString = ByteString(Hex.decode("2d887fd3b17bfdce3481f10bea41f45ba9f709d39ce8325427b57afcfc994cee"))
  val v: Byte = ByteString(Hex.decode("1b")).last
  val sig: ECDSASignature = ECDSASignature(r, s, v)

  def newJsonRpcRequest(method: String, params: List[JValue]): JsonRpcRequest =
    JsonRpcRequest("2.0", method, Some(JArray(params)), Some(JInt(1)))

  def newJsonRpcRequest(method: String): JsonRpcRequest =
    JsonRpcRequest("2.0", method, None, Some(JInt(1)))

  val fakeWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
    storagesInstance.storages.evmCodeStorage,
    blockchain.getReadOnlyMptStorage(),
    (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
    blockchainConfig.accountStartNonce,
    ByteString.empty,
    noEmptyAccounts = false,
    ethCompatibleStorage = true
  )
}
