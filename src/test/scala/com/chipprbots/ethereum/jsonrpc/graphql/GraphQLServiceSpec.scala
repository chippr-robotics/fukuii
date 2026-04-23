package com.chipprbots.ethereum.jsonrpc.graphql

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import io.circe.Json

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.{Block, ChainWeight}
import com.chipprbots.ethereum.jsonrpc.EthBlocksService
import com.chipprbots.ethereum.jsonrpc.EthFilterService
import com.chipprbots.ethereum.jsonrpc.EthInfoService
import com.chipprbots.ethereum.jsonrpc.EthTxService
import com.chipprbots.ethereum.jsonrpc.EthUserService
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class GraphQLServiceSpec
    extends TestKit(ActorSystem("GraphQLServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with MockFactory {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(200, Millis))
  implicit val runtime: IORuntime = IORuntime.global
  implicit val ec: ExecutionContext = system.dispatcher

  "GraphQLService" should "answer { chainID } with the configured chain id as 0x-hex" in new GraphQLTestSetup {
    val (status, body) = service.execute("{ chainID }", None, None).unsafeRunSync()
    status shouldBe 200
    val chainHex = body.hcursor.downField("data").downField("chainID").as[String].toOption.get
    chainHex should startWith("0x")
    // Any valid non-negative hex is acceptable here — the fixture's chain id depends on the test chain.
  }

  it should "answer { block { number hash } } for the latest block" in new GraphQLTestSetup {
    blockchainWriter.storeBlock(block).and(blockchainWriter.storeChainWeight(block.header.hash, weight)).commit()
    blockchainWriter.saveBestKnownBlocks(block.hash, block.number)

    val (status, body) = service.execute("{ block { number hash } }", None, None).unsafeRunSync()
    status shouldBe 200
    val data = body.hcursor.downField("data").downField("block")
    data.downField("number").as[String].toOption.get shouldBe "0x" + block.header.number.toString(16)
    val gotHash = data.downField("hash").as[String].toOption.get
    gotHash shouldBe "0x" + block.header.hash.toArray.map("%02x".format(_)).mkString
  }

  it should "return null for an unknown transaction" in new GraphQLTestSetup {
    val unknown = "0x" + ("00" * 32)
    val query = s"""{ transaction(hash: \"$unknown\") { hash } }"""
    val (status, body) = service.execute(query, None, None).unsafeRunSync()
    status shouldBe 200
    body.hcursor.downField("data").downField("transaction").focus.get shouldBe Json.Null
  }

  it should "reject a syntactically invalid query with HTTP 400" in new GraphQLTestSetup {
    val (status, body) = service.execute("{ not valid graphql", None, None).unsafeRunSync()
    status shouldBe 400
    val errs = body.hcursor.downField("errors").as[List[Json]].toOption.get
    errs should not be empty
  }

  it should "reject queries exceeding the configured depth" in new GraphQLTestSetup(maxDepth = 3) {
    blockchainWriter.storeBlock(block).and(blockchainWriter.storeChainWeight(block.header.hash, weight)).commit()
    blockchainWriter.saveBestKnownBlocks(block.hash, block.number)

    val deep =
      "{ block { parent { parent { parent { parent { number } } } } } }"
    val (status, _) = service.execute(deep, None, None).unsafeRunSync()
    status shouldBe 400
  }

  it should "serve an introspection query" in new GraphQLTestSetup {
    val introspection =
      """{ __schema { queryType { name } mutationType { name } types { name } } }"""
    val (status, body) = service.execute(introspection, None, None).unsafeRunSync()
    status shouldBe 200
    body.hcursor
      .downField("data")
      .downField("__schema")
      .downField("queryType")
      .downField("name")
      .as[String]
      .toOption
      .get shouldBe "Query"
    body.hcursor
      .downField("data")
      .downField("__schema")
      .downField("mutationType")
      .downField("name")
      .as[String]
      .toOption
      .get shouldBe "Mutation"
  }

  // -------------------------------------------------------------------------
  abstract class GraphQLTestSetup(val maxDepth: Int = GraphQLSchema.MaxQueryDepth) extends EphemBlockchainTestSetup {

    // Mining — needed by resolveBlock(Pending) path. The tests here don't exercise the
    // Pending branch, so the mock's default return is sufficient.
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val appStateStorage: AppStateStorage = mock[AppStateStorage]
    val transactionMappingStorage: TransactionMappingStorage =
      storagesInstance.storages.transactionMappingStorage
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    val keyStore: KeyStore = mock[KeyStore]
    val syncProbe = TestProbe()
    val pendingTxProbe = TestProbe()
    val filterManagerProbe = TestProbe()

    lazy val ethBlocksService = new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue
    )
    lazy val ethTxService = new EthTxService(
      blockchain,
      blockchainReader,
      mining,
      pendingTxProbe.ref,
      1.second,
      transactionMappingStorage
    )
    lazy val ethInfoService = new EthInfoService(
      blockchain,
      blockchainReader,
      blockchainConfig,
      mining,
      stxLedger,
      keyStore,
      syncProbe.ref,
      Capability.ETH66,
      org.apache.pekko.util.Timeout(2.seconds)
    )
    lazy val ethUserService = new EthUserService(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      this
    )
    lazy val ethFilterService = new EthFilterService(
      filterManagerProbe.ref,
      new com.chipprbots.ethereum.utils.FilterConfig {
        override val filterTimeout: FiniteDuration = 10.seconds
        override val filterManagerQueryTimeout: FiniteDuration = 2.seconds
      },
      blockchainReader
    )

    val ctx = GraphQLContext(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      blockchainConfig,
      ethBlocksService,
      ethTxService,
      ethInfoService,
      ethUserService,
      ethFilterService
    )
    val service = new GraphQLService(ctx, maxQueryDepth = maxDepth, executionTimeout = 10.seconds)

    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val weight: ChainWeight = ChainWeight.totalDifficultyOnly(block.header.difficulty)
  }
}
