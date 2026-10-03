package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import com.typesafe.config.ConfigFactory
import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** WI-14 (#1430) acceptance: fukuii's EIP-7910 `eth_config` for Platåberget equals the live network's.
  *
  * Ground truth is `plataberget/eth-config-live.json`, the verbatim `eth_config` response of
  * https://rpc.plataberget.ethpandaops.io (reth v2.5.2), fetched 2026-09-27 with the network past Amsterdam: `current`
  * is Amsterdam, `next` and `last` are null. Against it, a fukuii node built from `7f8ed6644` matched on 29 of 31 keys.
  * The two it lacked were EIP-8282's `BUILDER_DEPOSIT_CONTRACT_ADDRESS` and `BUILDER_EXIT_CONTRACT_ADDRESS`, which
  * go-ethereum's `ActiveSystemContracts` also reports from Amsterdam on.
  *
  * The chain is built the way the node builds it: the SHIPPED `plataberget` chain config, and its genesis loaded by
  * `GenesisDataLoader`, so the fork id is computed from the real genesis hash (0xee33ef92…2b31) rather than a
  * fixture's. The comparison is on the whole rendered response, keys order-insensitive, so any field that moves fails
  * here.
  */
class EthConfigPlatabergetSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with OptionValues
    with MockFactory:

  implicit private val classicActorSystem: ActorSystem = system.toClassic

  private val GenesisTimestamp: Long = 1786622400L
  private val AmsterdamTimestamp: Long = 1787212224L

  private val BuilderKeys = Set("BUILDER_DEPOSIT_CONTRACT_ADDRESS", "BUILDER_EXIT_CONTRACT_ADDRESS")

  /** Loaded as the application loads it (see PlatabergetGenesisSpec): the chain file's `include required(...)` of the
    * genesis JSON resolves exactly as at runtime.
    */
  private lazy val plataberget: BlockchainConfig =
    BlockchainConfig.fromRawConfig(
      ConfigFactory
        .parseResources("conf/base/blockchains.conf")
        .atPath("fukuii")
        .resolve()
        .getConfig("fukuii.blockchains.plataberget")
    )

  /** The live response's `result`. */
  private lazy val live: JValue =
    val source = scala.io.Source.fromResource("plataberget/eth-config-live.json")
    try parse(source.mkString) \ "result"
    finally source.close()

  /** Object keys sorted at every level: JSON objects are unordered, and the live node (reth) and fukuii's encoder emit
    * keys in different orders. Array order and every value are compared as they are.
    */
  private def normalized(v: JValue): JValue = v match
    case JObject(fields) => JObject(fields.map((k, x) => k -> normalized(x)).sortBy(_._1))
    case JArray(items)   => JArray(items.map(normalized))
    case other           => other

  private def render(response: ConfigResponse): JValue = EthJsonMethodsImplicits.eth_config.encodeJson(response)

  "eth_config on Platåberget past Amsterdam" should "equal the live network's response" taggedAs (
    UnitTest,
    RPCTest
  ) in new PlatabergetSetup(headTimestamp = AmsterdamTimestamp + 12):
    genesisHash shouldBe "ee33ef92bbabcf07bcf44fea1d18a7925c5f7f9da8f81334ea19b0f3cb892b31"

    val response = ethService.config(ConfigRequest()).unsafeRunSync().toOption.value
    normalized(render(response)) shouldBe normalized(live)

  it should "advertise EIP-8282's two builder contracts, lower-case, at the addresses execution calls" taggedAs (
    UnitTest,
    RPCTest
  ) in new PlatabergetSetup(headTimestamp = AmsterdamTimestamp):
    val contracts = ethService.config(ConfigRequest()).unsafeRunSync().toOption.value.current.value.systemContracts
    contracts("BUILDER_DEPOSIT_CONTRACT_ADDRESS") shouldBe BlockExecution.BuilderDepositQueueAddress
    contracts("BUILDER_EXIT_CONTRACT_ADDRESS") shouldBe BlockExecution.BuilderExitQueueAddress
    contracts should have size 7

    val rendered = render(ethService.config(ConfigRequest()).unsafeRunSync().toOption.value)
    rendered \ "current" \ "systemContracts" \ "BUILDER_DEPOSIT_CONTRACT_ADDRESS" shouldBe
      JString("0x0000bff46984e3725691fa540a8c7589300d8282")
    rendered \ "current" \ "systemContracts" \ "BUILDER_EXIT_CONTRACT_ADDRESS" shouldBe
      JString("0x000064d678505ad48f8ccb093bc65613800e8282")

  "eth_config on Platåberget before Amsterdam" should "list the builder contracts under next only" taggedAs (
    UnitTest,
    RPCTest
  ) in new PlatabergetSetup(headTimestamp = AmsterdamTimestamp - 1):
    val response = ethService.config(ConfigRequest()).unsafeRunSync().toOption.value

    // Genesis-active fork (Osaka / BPO2 at 0): the five Prague contracts, no builder contracts.
    val current = response.current.value
    current.activationTime shouldBe Some(0L)
    current.systemContracts.keySet.intersect(BuilderKeys) shouldBe empty
    current.systemContracts should have size 5

    // Amsterdam is next, and is exactly the entry the live network reports as current.
    normalized(render(response) \ "next") shouldBe normalized(live \ "current")
    response.next.value.systemContracts.keySet should contain allElementsOf BuilderKeys

  "eth_config on an ETC chain" should "never list a builder contract" taggedAs (UnitTest, RPCTest) in
    new EtcSetup:
      val response = ethService.config(ConfigRequest()).unsafeRunSync().toOption.value
      Seq(response.current, response.next, response.last).flatten.foreach { fork =>
        fork.systemContracts.keySet.intersect(BuilderKeys) shouldBe empty
      }

  /** A node whose best block sits at `headTimestamp`, on the shipped Platåberget chain. */
  class PlatabergetSetup(headTimestamp: Long)(implicit system: ActorSystem) extends EthServiceSetup:
    override lazy val blockchainConfig: BlockchainConfig = plataberget

    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    ).loadGenesisData()(using plataberget)

    private val genesis = blockchainReader.genesisHeader
    genesis.unixTimestamp shouldBe Timestamp(GenesisTimestamp)
    val genesisHash: String = genesis.hashAsHexString.stripPrefix("0x")

    // `eth_config` reads only the best block's number and timestamp (and block 0 for the fork id).
    private val head: Block = Block(
      genesis.copy(
        parentHash = genesis.hash,
        number = BlockNumber(1),
        unixTimestamp = Timestamp(headTimestamp)
      ),
      BlockBody.empty
    )
    blockchainWriter.save(head, Nil, ChainWeight.zero, saveAsBestBlock = true)

  /** The loaded test chain: ETC, block-numbered forks, `eth_config`'s block-numbered path. */
  class EtcSetup(implicit system: ActorSystem) extends EthServiceSetup:
    override lazy val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
    blockchainWriter.save(
      Block(
        com.chipprbots.ethereum.Fixtures.Blocks.Genesis.header,
        com.chipprbots.ethereum.Fixtures.Blocks.Genesis.body
      ),
      Nil,
      ChainWeight.zero,
      saveAsBestBlock = true
    )

  abstract class EthServiceSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup:
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val keyStore: KeyStore = mock[KeyStore]
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val syncingController: TestProbe = TestProbe()

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
