package com.chipprbots.ethereum.jsonrpc.graphql

import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.model.HttpOriginMatcher
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import io.circe.parser.parse

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.EthBlocksService
import com.chipprbots.ethereum.jsonrpc.EthFilterService
import com.chipprbots.ethereum.jsonrpc.EthInfoService
import com.chipprbots.ethereum.jsonrpc.EthTxService
import com.chipprbots.ethereum.jsonrpc.EthUserService
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcHealthChecker
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.ApisBase
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.http.{JsonRpcHttpServer, RateLimit}
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.JsonRpcHttpServerConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer.RateLimitConfig
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.FilterConfig
import com.chipprbots.ethereum.utils.Logger

/** End-to-end test for the POST /graphql HTTP route mounted on JsonRpcHttpServer. */
class GraphQLHttpRouteSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockFactory {

  implicit val runtime: IORuntime = IORuntime.global

  // Bump the Pekko HTTP test-kit timeout so Sangria has time to execute the query.
  implicit val routeTestTimeout: org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout =
    org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout(30.seconds)

  it should "respond with 200 and JSON body to a well-formed POST /graphql" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val body =
      """{"query":"{ chainID }"}"""
    val req = HttpRequest(
      method = HttpMethods.POST,
      uri = "/graphql",
      entity = HttpEntity(ContentTypes.`application/json`, body)
    )

    req ~> Route.seal(server.route) ~> check {
      status shouldBe StatusCodes.OK
      val json = parse(responseAs[String]).toOption.get
      json.hcursor.downField("data").downField("chainID").as[String].toOption.get should startWith("0x")
    }
  }

  it should "return 400 on invalid JSON body" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val req = HttpRequest(
      method = HttpMethods.POST,
      uri = "/graphql",
      entity = HttpEntity(ContentTypes.`application/json`, "not json")
    )
    req ~> Route.seal(server.route) ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "return 400 for syntactically invalid GraphQL" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val body = """{"query":"{ not valid graphql"}"""
    val req = HttpRequest(
      method = HttpMethods.POST,
      uri = "/graphql",
      entity = HttpEntity(ContentTypes.`application/json`, body)
    )
    req ~> Route.seal(server.route) ~> check {
      status shouldBe StatusCodes.BadRequest
    }
  }

  it should "return 404 when GraphQL is disabled" taggedAs (UnitTest, RPCTest) in new TestSetup(graphQLEnabled = false) {
    val body = """{"query":"{ chainID }"}"""
    val req = HttpRequest(
      method = HttpMethods.POST,
      uri = "/graphql",
      entity = HttpEntity(ContentTypes.`application/json`, body)
    )
    req ~> Route.seal(server.route) ~> check {
      status shouldBe StatusCodes.NotFound
    }
  }

  // -------------------------------------------------------------------------
  abstract class TestSetup(val graphQLEnabled: Boolean = true) extends EphemBlockchainTestSetup {

    implicit override lazy val system: ActorSystem = GraphQLHttpRouteSpec.this.system

    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    @annotation.unused val appStateStorage: AppStateStorage = mock[AppStateStorage]
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    val keyStore: KeyStore = mock[KeyStore]
    val syncProbe = TestProbe()
    val pendingTxProbe = TestProbe()
    val filterManagerProbe = TestProbe()

    lazy val ethBlocksService = new EthBlocksService(blockchain, blockchainReader, mining, blockQueue)
    lazy val ethTxService = new EthTxService(
      blockchain,
      blockchainReader,
      mining,
      pendingTxProbe.ref,
      1.second,
      storagesInstance.storages.transactionMappingStorage
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
      new FilterConfig {
        override val filterTimeout: FiniteDuration = 10.seconds
        override val filterManagerQueryTimeout: FiniteDuration = 2.seconds
      },
      blockchainReader
    )

    implicit val ec: ExecutionContext = system.dispatcher

    val graphQLSvc: Option[GraphQLService] =
      if (!graphQLEnabled) None
      else {
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
        Some(new GraphQLService(ctx, executionTimeout = 10.seconds))
      }

    val rateLimitConfig: RateLimitConfig = new RateLimitConfig {
      override val enabled: Boolean = false
      override val minRequestInterval: FiniteDuration = FiniteDuration.apply(20, TimeUnit.MILLISECONDS)
      override val latestTimestampCacheSize: Int = 1024
    }

    val cfg: JsonRpcHttpServerConfig = new JsonRpcHttpServerConfig {
      override val mode: String = "mockJsonRpc"
      override val enabled: Boolean = true
      override val interface: String = ""
      override val port: Int = 0
      override val corsAllowedOrigins = HttpOriginMatcher.*
      override val rateLimit: RateLimitConfig = rateLimitConfig
    }

    val controller: MockableJsonRpcControllerForGraphQL = mock[MockableJsonRpcControllerForGraphQL]
    val healthChecker: JsonRpcHealthChecker = mock[JsonRpcHealthChecker]

    val server: JsonRpcHttpServer = new GraphQLFakeServer(controller, healthChecker, cfg, graphQLSvc)(system)

    // Pre-populate the chain so { chainID } returns something deterministic.
    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val weight: ChainWeight = ChainWeight.totalDifficultyOnly(block.header.difficulty)
    blockchainWriter.storeBlock(block).and(blockchainWriter.storeChainWeight(block.header.hash, weight)).commit()
    blockchainWriter.saveBestKnownBlocks(block.hash, block.number)
  }
}

/** Mockable controller — matches the pattern in JsonRpcHttpServerSpec. */
class MockableJsonRpcControllerForGraphQL extends JsonRpcBaseController with ApisBase with Logger {
  override def apisHandleFns: Map[String, PartialFunction[JsonRpcRequest, cats.effect.IO[JsonRpcResponse]]] = Map.empty
  override def enabledApis: Seq[String] = Seq.empty
  override def available: List[String] = List.empty
  @SuppressWarnings(Array("scalafix:DisableSyntax.null"))
  override val config: JsonRpcConfig = null
}

class GraphQLFakeServer(
    val jsonRpcController: JsonRpcBaseController,
    val jsonRpcHealthChecker: JsonRpcHealthChecker,
    val config: JsonRpcHttpServerConfig,
    override val graphQLService: Option[GraphQLService]
)(implicit val actorSystem: ActorSystem)
    extends JsonRpcHttpServer
    with Logger {

  def run(): Unit = ()
  override def corsAllowedOrigins: HttpOriginMatcher = config.corsAllowedOrigins
  override protected val rateLimit: RateLimit = new RateLimit(config.rateLimit)
}
