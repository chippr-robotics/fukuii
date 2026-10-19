package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration.*
import scala.io.Source

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.parse
import org.json4s.native.JsonMethods.render

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.engine.ForkChoiceState
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.jsonrpc.*
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.keystore.KeyStore.KeyStoreError
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.rlp.nextElementIndex
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.TxPoolConfig

/** The execution-apis test chain (`tests/genesis.json` + `tests/chain.rlp`, vendored under `rpc-compat/tests/`) that
  * hive's rpc-compat simulator serves its vectors from, imported in process.
  *
  * The chain config is `hive-chain.conf` with the overrides `hive/fukuii/fukuii.sh` derives from `tests/forkenv.json`,
  * the genesis the one `hive/fukuii/mapper.jq` hands the node. Every block goes through the import path's execution and
  * post-execution checks (state root, receipts root, gas used, logs bloom, EIP-7685 requests hash, EIP-7928 access list
  * hash), so the stored receipts and state are the ones the block headers commit to. The pre-execution header check is
  * skipped: blocks 1 to 35 are PoW blocks with fake Ethash seals, which hive's adapter waves through with
  * `fukuii.mining.skip-pow-validation`, a JVM-wide switch a test must not flip.
  *
  * The node behind the controller is the production wiring for every method the vectors call: real EthBlocksService,
  * EthTxService (with S0 pool admission), EthInfoService (eth_call, estimate, access list on the real VM),
  * TxPoolService and a real PendingTransactionsManager.
  */
class RpcCompatNode(using system: ActorSystem, testKit: ActorTestKit) extends EphemBlockchainTestSetup with ApisBuilder:
  import RpcCompatNode.str

  implicit override lazy val blockchainConfig: BlockchainConfig = RpcCompatNode.chainConfig
  override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
  override lazy val vm: VMImpl = new VMImpl

  /** Loads the genesis and imports every block of chain.rlp; returns the imported head. */
  def importChain(): Block =
    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    ).loadGenesisData()
    val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
    val blockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )
    for block <- RpcCompatNode.blocks do
      val imported = for
        executed <- blockExecution.executeBlockNoValidationWithRequests(block).left.map(_.describe)
        (receipts, gasUsed, stateRoot, requests, accessList) = executed
        _ <- blockValidation.validateBlockAfterExecution(block, stateRoot, receipts, gasUsed).left.map(_.toString)
        _ <- blockExecution.validateRequestsHash(block, requests).left.map(_.describe)
        _ <- blockExecution.validateBlockAccessList(block, accessList).left.map(_.describe)
      yield (receipts, accessList)
      imported match
        case Left(error) => throw new IllegalStateException(s"block ${block.header.number} not imported: $error")
        case Right((receipts, accessList)) =>
          val parentWeight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
          blockchainWriter.save(block, receipts, parentWeight.increase(block.header), saveAsBestBlock = true)
          accessList.foreach(bal => blockchainWriter.storeBlockAccessList(block.header.hash, bal).commit())
    // hive's rpc-compat sends tests/headfcu.json (engine_forkchoiceUpdatedV4) once the chain is in; it names the
    // head, safe and finalized blocks the "safe"/"finalized" vectors read.
    val forkchoice = (parse(RpcCompatNode.resource("headfcu.json")) \ "params")(0)
    def hash(field: String) = org.apache.pekko.util.ByteString(Hex.decode(str(forkchoice \ field).stripPrefix("0x")))
    forkChoiceManager
      .applyForkChoiceState(ForkChoiceState(hash("headBlockHash"), hash("safeBlockHash"), hash("finalizedBlockHash")))
      .fold(e => throw new IllegalStateException(s"headfcu.json: $e"), identity)
    blockchainReader.getBestBlock.get

  lazy val forkChoiceManager: ForkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

  private val txPoolConfig: TxPoolConfig = new TxPoolConfig:
    override val txPoolSize: Int = 1000
    override val pendingTxManagerQueryTimeout: FiniteDuration = 10.seconds
    override val transactionTimeout: FiniteDuration = 10.minutes
    override val getTransactionFromPoolTimeout: FiniteDuration = 10.seconds

  private val peerManager = TestProbe()
  private val networkPeerManager = TestProbe()
  private val peerEventBus = TestProbe()

  lazy val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
    testKit.spawn(
      PendingTransactionsManager(
        txPoolConfig,
        peerManager.ref,
        networkPeerManager.ref,
        peerEventBus.ref,
        testKit
          .spawn(Topic[NewPendingTransaction]("pending-tx-topic"), s"rpc-compat-topic-${java.util.UUID.randomUUID()}"),
        blockchainReader = blockchainReader,
        stateStorage = storagesInstance.storages.stateStorage,
        chainConfig = blockchainConfig
      ),
      s"rpc-compat-ptm-${java.util.UUID.randomUUID()}"
    )

  /** No accounts: the vectors never sign on the node. */
  private object NoKeys extends KeyStore:
    private val none = Left(KeyStore.KeyNotFound)
    def newAccount(passphrase: String): Either[KeyStoreError, Address] = none
    def importPrivateKey(key: org.apache.pekko.util.ByteString, passphrase: String): Either[KeyStoreError, Address] =
      none
    def listAccounts: Either[KeyStoreError, List[Address]] = Right(Nil)
    def unlockAccount(address: Address, passphrase: String): Either[KeyStoreError, Wallet] = none

  private val syncing = TestProbe()

  lazy val ethInfoService: EthInfoService = new EthInfoService(
    blockchain,
    blockchainReader,
    blockchainConfig,
    mining,
    stxLedger,
    NoKeys,
    syncing.ref.toTyped[com.chipprbots.ethereum.blockchain.sync.SyncController.Command],
    Capability.ETH68,
    org.apache.pekko.util.Timeout(10.seconds),
    system.toTyped.scheduler
  )
  lazy val ethBlocksService: EthBlocksService =
    new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue,
      Some(forkChoiceManager),
      blockchainConfig = blockchainConfig
    )
  lazy val ethTxService: EthTxService = new EthTxService(
    blockchain,
    blockchainReader,
    mining,
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    storagesInstance.storages.transactionMappingStorage,
    system.toTyped.scheduler
  )
  lazy val ethUserService: EthUserService =
    new EthUserService(blockchain, blockchainReader, mining, storagesInstance.storages.evmCodeStorage, this)
  lazy val txPoolService: TxPoolService = new TxPoolService(
    pendingTransactionsManager,
    txPoolConfig.getTransactionFromPoolTimeout,
    txPoolConfig,
    system.toTyped.scheduler
  )

  /** GraphQL over the same services, as NodeBuilder wires it. */
  lazy val graphQL: com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService =
    given scala.concurrent.ExecutionContext = system.dispatcher
    val filterManager = testKit.spawn(
      org.apache.pekko.actor.typed.scaladsl.Behaviors.ignore[FilterManager.Command],
      s"rpc-compat-filters-${java.util.UUID.randomUUID()}"
    )
    val filterConfig = new com.chipprbots.ethereum.utils.FilterConfig:
      override val filterTimeout: FiniteDuration = 10.seconds
      override val filterManagerQueryTimeout: FiniteDuration = 10.seconds
    new com.chipprbots.ethereum.jsonrpc.graphql.GraphQLService(
      com.chipprbots.ethereum.jsonrpc.graphql.GraphQLContext(
        blockchain,
        blockchainReader,
        mining,
        storagesInstance.storages.evmCodeStorage,
        blockchainConfig,
        ethBlocksService,
        ethTxService,
        ethInfoService,
        ethUserService,
        new EthFilterService(filterManager, filterConfig, blockchainReader)(system)
      ),
      maxQueryDepth = com.chipprbots.ethereum.jsonrpc.graphql.GraphQLSchema.MaxQueryDepth,
      executionTimeout = 30.seconds
    )

  lazy val controller: JsonRpcController =
    val personalService = new TestPersonalService
    personalService.listAccountsFn = _ => cats.effect.IO.pure(Right(PersonalService.ListAccountsResponse(Nil)))
    JsonRpcController(
      new Web3Service,
      new TestNetService,
      ethInfoService,
      null: EthMiningService,
      ethBlocksService,
      ethTxService,
      ethUserService,
      null: EthFilterService,
      personalService,
      None,
      None,
      null: DebugService,
      null: QAService,
      null: FukuiiService,
      null: McpService,
      ProofServiceDummy,
      null: EthSimulateService,
      null: AdminService,
      txPoolService,
      null: DebugTracingService,
      null: TraceService,
      JsonRpcConfig(
        Config.config.withValue("network.rpc.apis", ConfigValueFactory.fromAnyRef("eth,web3,net,debug,txpool")),
        available
      ),
      system
    )

object RpcCompatNode:

  val Resources = "rpc-compat/tests/"

  def resource(name: String): String =
    val source = Source.fromResource(Resources + name)
    try source.mkString
    finally source.close()

  private def resourceBytes(name: String): Array[Byte] =
    val in = getClass.getClassLoader.getResourceAsStream(Resources + name)
    try in.readAllBytes()
    finally in.close()

  private[conformance] def str(v: JValue): String = v match
    case JString(s) => s
    case JInt(n)    => n.toString
    case other      => throw new IllegalArgumentException(s"not a string or integer: $other")

  /** The genesis as `hive/fukuii/mapper.jq` rewrites execution-apis' geth-format `genesis.json` for fukuii. */
  lazy val genesisJson: String =
    val geth = parse(resource("genesis.json"))
    def field(name: String, default: Option[String] = None): Option[JField] =
      (geth \ name).toOption.collect { case JString(s) => s }.orElse(default).map(name -> JString(_))
    val zero32 = "0x" + "00" * 32
    val alloc = (geth \ "alloc").asInstanceOf[JObject].obj.map { case (address, account) =>
      address -> JObject(
        List("balance" -> (account \ "balance").toOption.getOrElse(JString("0"))) ++
          List("code", "nonce", "storage").flatMap(k => (account \ k).toOption.map(k -> _))
      )
    }
    val fields = List(
      field("difficulty"),
      field("gasLimit"),
      field("nonce", Some("0x0000000000000000")),
      field("timestamp", Some("0x0")),
      field("coinbase", Some("0x" + "00" * 20)),
      field("mixHash", Some(zero32)),
      field("extraData", Some("0x")),
      field("gasUsed", Some("0x0")),
      field("parentHash", Some(zero32)),
      field("baseFeePerGas"),
      field("excessBlobGas"),
      field("blobGasUsed"),
      field("slotNumber")
    ).flatten :+ ("alloc" -> JObject(alloc))
    compact(render(JObject(fields)))

  /** `hive-chain.conf` with the overrides `hive/fukuii/fukuii.sh` sets from `tests/forkenv.json`. */
  lazy val chainConfig: BlockchainConfig =
    val env: Map[String, String] =
      parse(resource("forkenv.json")).asInstanceOf[JObject].obj.map { case (k, v) => k -> str(v) }.toMap
    def e(key: String): String = env(key)
    val overrides = Map(
      "chain-id" -> e("HIVE_CHAIN_ID"),
      "network-id" -> e("HIVE_NETWORK_ID"),
      "homestead-block-number" -> e("HIVE_FORK_HOMESTEAD"),
      "eip150-block-number" -> e("HIVE_FORK_TANGERINE"),
      "eip155-block-number" -> e("HIVE_FORK_SPURIOUS"),
      "eip160-block-number" -> e("HIVE_FORK_SPURIOUS"),
      "eip161-block-number" -> e("HIVE_FORK_SPURIOUS"),
      "byzantium-block-number" -> e("HIVE_FORK_BYZANTIUM"),
      "constantinople-block-number" -> e("HIVE_FORK_CONSTANTINOPLE"),
      "petersburg-block-number" -> e("HIVE_FORK_PETERSBURG"),
      "istanbul-block-number" -> e("HIVE_FORK_ISTANBUL"),
      "muir-glacier-block-number" -> e("HIVE_FORK_MUIR_GLACIER"),
      "berlin-block-number" -> e("HIVE_FORK_BERLIN"),
      "olympia-block-number" -> e("HIVE_FORK_LONDON"),
      "terminal-total-difficulty" -> e("HIVE_TERMINAL_TOTAL_DIFFICULTY"),
      "shanghai-timestamp" -> e("HIVE_SHANGHAI_TIMESTAMP"),
      "cancun-timestamp" -> e("HIVE_CANCUN_TIMESTAMP"),
      "prague-timestamp" -> e("HIVE_PRAGUE_TIMESTAMP"),
      "osaka-timestamp" -> e("HIVE_OSAKA_TIMESTAMP"),
      "bpo1-timestamp" -> e("HIVE_BPO1_TIMESTAMP"),
      "bpo2-timestamp" -> e("HIVE_BPO2_TIMESTAMP"),
      "amsterdam-timestamp" -> e("HIVE_AMSTERDAM_TIMESTAMP"),
      "deposit-contract-address" -> e("HIVE_DEPOSIT_CONTRACT_ADDRESS"),
      "arrow-glacier-block-number" -> e("HIVE_FORK_ARROW_GLACIER"),
      "gray-glacier-block-number" -> e("HIVE_FORK_GRAY_GLACIER"),
      "merge-netsplit-block-number" -> e("HIVE_MERGE_BLOCK_ID")
    )
    val raw = overrides
      .foldLeft(ConfigFactory.parseResources("conf/base/chains/hive-chain.conf")) { case (c, (k, v)) =>
        c.withValue(k, ConfigValueFactory.fromAnyRef(v))
      }
      .resolve()
    BlockchainConfig.fromRawConfig(raw).copy(customGenesisJsonOpt = Some(genesisJson))

  /** chain.rlp: concatenated RLP blocks, decoded as `ChainImporter.decodeBlocks` does. */
  lazy val blocks: Seq[Block] =
    val bytes = resourceBytes("chain.rlp")
    Iterator
      .unfold(0)(pos => Option.when(pos < bytes.length)(nextElementIndex(bytes, pos)).map(next => ((pos, next), next)))
      .map { case (from, until) => bytes.slice(from, until).toBlock }
      .toSeq
