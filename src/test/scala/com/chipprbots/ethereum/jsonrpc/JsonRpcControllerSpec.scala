package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.*

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.json4s.JArray
import org.json4s.JObject
import org.json4s.JString
import org.json4s.JValue
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoRequest
import com.chipprbots.ethereum.jsonrpc.DebugService.ListPeersInfoResponse
import com.chipprbots.ethereum.jsonrpc.NetService.ListeningResponse
import com.chipprbots.ethereum.jsonrpc.NetService.PeerCountResponse
import com.chipprbots.ethereum.jsonrpc.NetService.VersionResponse
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.OptionNoneToJNullSerializer
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.QuantitiesSerializer
import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.UnformattedDataJsonSerializer
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcHttpServer
import com.chipprbots.ethereum.jsonrpc.server.ipc.JsonRpcIpcServer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.nodebuilder.TestNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testmode.TestModeComponentsProvider
import com.chipprbots.ethereum.transactions.PendingTransactionsManager

class JsonRpcControllerSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with JRCMatchers
    with org.scalamock.scalatest.MockFactory
    with JsonRpcControllerTestSupport
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with Eventually:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic
  implicit private val actorTestKitImpl: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  implicit val formats: Formats = DefaultFormats.preservingEmptyValues + OptionNoneToJNullSerializer +
    QuantitiesSerializer + UnformattedDataJsonSerializer

  "JsonRpcController" should "handle valid sha3 request" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("web3_sha3", JString("0x1234") :: Nil)

    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveStringResult("0x56570de287d73cd1cb6092bb8fdee6173974955fdef345ae579ee9f475ea7432")

  it should "fail when invalid request is received" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("web3_sha3", JString("asdasd") :: Nil)

    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveError(JsonRpcError.InvalidParams("invalid argument: hex string without 0x prefix"))

  it should "handle clientVersion request" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("web3_clientVersion")

    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveStringResult(version)

  it should "Handle net_peerCount request" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    netService.peerCountFn = _ => IO.pure(Right(PeerCountResponse(123)))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("net_peerCount")

    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveStringResult("0x7b")

  it should "Handle net_listening request" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    netService.listeningFn = _ => IO.pure(Right(ListeningResponse(false)))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("net_listening")
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveBooleanResult(false)

  it should "Handle net_version request" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val netVersion = "99"

    netService.versionFn = _ => IO.pure(Right(VersionResponse(netVersion)))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("net_version")
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveStringResult(netVersion)

  it should "only allow to call methods of enabled apis" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig = new JsonRpcConfig:
      override val apis: Seq[String] = Seq("web3")
      override val accountTransactionsMaxBlocks = 50000
      override def minerActiveTimeout: FiniteDuration = ???
      override def httpServerConfig: JsonRpcHttpServer.JsonRpcHttpServerConfig = ???
      override def wsServerConfig: com.chipprbots.ethereum.jsonrpc.server.http.JsonRpcWsServer.JsonRpcWsServerConfig =
        ???
      override def ipcServerConfig: JsonRpcIpcServer.JsonRpcIpcServerConfig = ???
      override def healthConfig: NodeJsonRpcHealthChecker.JsonRpcHealthConfig = ???

    val ethRpcRequest: JsonRpcRequest = newJsonRpcRequest("eth_protocolVersion")
    val ethResponse: JsonRpcResponse = jsonRpcController.handleRequest(ethRpcRequest).unsafeRunSync()

    ethResponse should haveError(JsonRpcError.MethodNotFound)

    val web3RpcRequest: JsonRpcRequest = newJsonRpcRequest("web3_clientVersion")
    val web3Response: JsonRpcResponse = jsonRpcController.handleRequest(web3RpcRequest).unsafeRunSync()

    web3Response should haveStringResult(version)

  private def modulesOf(fixture: JsonRpcControllerFixture, controller: JsonRpcController): Set[String] =
    controller.handleRequest(fixture.newJsonRpcRequest("rpc_modules")).unsafeRunSync().result match
      case Some(JObject(fields)) => fields.map(_._1).toSet
      case other                 => fail(s"unexpected rpc_modules result: $other")

  it should "answer MethodNotFound for personal, debug, admin, qa, txpool and trace over HTTP and WebSocket under the shipped defaults" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig()

    val http: JsonRpcController = jsonRpcController.forApis(config.httpApis)
    val ws: JsonRpcController = jsonRpcController.forApis(config.wsApis)

    val optIn: Seq[String] = Seq(
      "personal_listAccounts",
      "personal_newAccount",
      "debug_listPeersInfo",
      "admin_nodeInfo",
      "admin_peers",
      "qa_mineBlocks",
      "txpool_status",
      "trace_block",
      "test_mineBlocks",
      "miner_start",
      "miner_stop",
      "miner_getStatus",
      "miner_setEtherbase",
      "eth_setEtherbase",
      // the non-standard net_ peer methods are served with admin; net keeps net_version, net_listening, net_peerCount
      "net_nodeInfo",
      "net_listPeers",
      "net_disconnectPeer",
      "net_connectToPeer",
      "net_listBlacklistedPeers",
      "net_addToBlacklist",
      "net_removeFromBlacklist"
    )
    for
      controller <- Seq(http, ws)
      method <- optIn
    do
      withClue(method)(
        controller.handleRequest(newJsonRpcRequest(method)).unsafeRunSync() should haveError(
          JsonRpcError.MethodNotFound
        )
      )

    for controller <- Seq(http, ws) do
      // the mining queries stay in eth; only the control methods moved to miner
      controller.handleRequest(newJsonRpcRequest("eth_coinbase")).unsafeRunSync().error shouldBe None
      controller.handleRequest(newJsonRpcRequest("eth_protocolVersion")).unsafeRunSync().error shouldBe None
      controller.handleRequest(newJsonRpcRequest("web3_clientVersion")).unsafeRunSync() should haveStringResult(version)
      modulesOf(this, controller) shouldBe Set("eth", "net", "web3", "fukuii", "rpc")

    // the shared controller (the Engine API's eth_, net_ and web3_ pass-through) serves the same set
    modulesOf(this, jsonRpcController) shouldBe Set("eth", "net", "web3", "fukuii", "rpc")

  it should "serve every production namespace over IPC under the shipped defaults, but not qa, test or mcp" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig()

    personalService.listAccountsFn = _ => IO.pure(Right(PersonalService.ListAccountsResponse(Nil)))

    val ipc: JsonRpcController = jsonRpcController.forApis(config.ipcApis)

    modulesOf(this, ipc) shouldBe JsonRpcConfig.DefaultIpcApis.toSet + "rpc"
    ipc.handleRequest(newJsonRpcRequest("personal_listAccounts")).unsafeRunSync().error shouldBe None
    ipc.handleRequest(newJsonRpcRequest("qa_mineBlocks")).unsafeRunSync() should haveError(JsonRpcError.MethodNotFound)
    ipc.handleRequest(newJsonRpcRequest("test_mineBlocks")).unsafeRunSync() should haveError(
      JsonRpcError.MethodNotFound
    )

  it should "serve a namespace over one transport only when that transport's apis key lists it" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig =
      ShippedRpcConfig.jsonRpcConfig("""fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,personal"""")

    personalService.listAccountsFn = _ => IO.pure(Right(PersonalService.ListAccountsResponse(Nil)))

    val http: JsonRpcController = jsonRpcController.forApis(config.httpApis)
    val ws: JsonRpcController = jsonRpcController.forApis(config.wsApis)

    http.handleRequest(newJsonRpcRequest("personal_listAccounts")).unsafeRunSync().error shouldBe None
    http.handleRequest(newJsonRpcRequest("debug_listPeersInfo")).unsafeRunSync() should haveError(
      JsonRpcError.MethodNotFound
    )
    ws.handleRequest(newJsonRpcRequest("personal_listAccounts")).unsafeRunSync() should haveError(
      JsonRpcError.MethodNotFound
    )

  it should "serve miner_setEtherbase and the deprecated eth_setEtherbase alias only where miner is enabled" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig =
      ShippedRpcConfig.jsonRpcConfig("""fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,miner"""")

    val perTransport: JsonRpcController.PerTransport = jsonRpcController.perTransport
    val http: JsonRpcController = perTransport.http
    val ws: JsonRpcController = perTransport.ws
    val first = "0x00000000000000000000000000000000000000aa"
    val second = "0x00000000000000000000000000000000000000bb"

    // WebSocket keeps the default list: no miner, so neither name is served
    ws.handleRequest(newJsonRpcRequest("miner_setEtherbase", JString(first) :: Nil)).unsafeRunSync() should haveError(
      JsonRpcError.MethodNotFound
    )
    ws.handleRequest(newJsonRpcRequest("eth_setEtherbase", JString(first) :: Nil)).unsafeRunSync() should haveError(
      JsonRpcError.MethodNotFound
    )

    // HTTP lists miner: the new name works, and eth_coinbase (still in eth) reports the change
    http
      .handleRequest(newJsonRpcRequest("miner_setEtherbase", JString(first) :: Nil))
      .unsafeRunSync()
      .error shouldBe None
    http.handleRequest(newJsonRpcRequest("eth_coinbase")).unsafeRunSync() should haveStringResult(first)

    // the alias works too
    http
      .handleRequest(newJsonRpcRequest("eth_setEtherbase", JString(second) :: Nil))
      .unsafeRunSync()
      .error shouldBe None
    http.handleRequest(newJsonRpcRequest("eth_coinbase")).unsafeRunSync() should haveStringResult(second)

  it should "log the eth_setEtherbase deprecation once, however many calls and transports" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    // the shared key reaches all three transports, so every one of them serves miner
    override def config: JsonRpcConfig =
      ShippedRpcConfig.jsonRpcConfig("""fukuii.network.rpc.apis = "eth,net,web3,fukuii,miner"""")

    val logger: LogbackLogger = LoggerFactory.getLogger(classOf[JsonRpcController]) match
      case logback: LogbackLogger => logback
      case other                  => fail(s"expected a logback logger, got $other")
    val appender: ListAppender[ILoggingEvent] = new ListAppender[ILoggingEvent]
    val previousLevel: Level = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.INFO)
    try
      val perTransport = jsonRpcController.perTransport
      val alias = newJsonRpcRequest("eth_setEtherbase", JString("0x00000000000000000000000000000000000000aa") :: Nil)
      Seq(perTransport.http, perTransport.http, perTransport.ws, perTransport.ipc).foreach { controller =>
        controller.handleRequest(alias).unsafeRunSync().error shouldBe None
      }
      val deprecations = appender.list.asScala.filter { event =>
        event.getLevel == Level.WARN && event.getFormattedMessage.contains("eth_setEtherbase is deprecated")
      }
      deprecations should have size 1
    finally
      logger.detachAppender(appender)
      logger.setLevel(previousLevel)

  it should "let the test namespace answer miner_setEtherbase when test and miner are both enabled, in any order" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig()

    // A TestService with only what setEtherbase touches (its own etherbase): it never reaches the node's mining.
    val testService = new TestService(
      null: BlockchainImpl,
      null: BlockchainReader,
      null: BlockchainWriter,
      null: StateStorage,
      null: EvmCodeStorage,
      null: ActorRef[PendingTransactionsManager.Command],
      miningConfig,
      null: TestModeComponentsProvider,
      null: TransactionMappingStorage,
      null: TestNode,
      null: Scheduler
    )(using IORuntime.global)

    val base: JsonRpcController = jsonRpcController.copy(testServiceOpt = Some(testService))
    val coinbase: JsonRpcController = base.forApis(Seq("eth"))
    val address = "0x00000000000000000000000000000000000000aa"
    val setEtherbase: JsonRpcRequest = newJsonRpcRequest("miner_setEtherbase", JString(address) :: Nil)
    val before: Option[JValue] = coinbase.handleRequest(newJsonRpcRequest("eth_coinbase")).unsafeRunSync().result

    // when miner answered instead, the node's coinbase would change
    Seq(Seq("eth", "miner", "test"), Seq("eth", "test", "miner"), Seq("test", "miner"), Seq("miner", "test", "eth"))
      .foreach { apis =>
        withClue(apis.mkString(",") + ": ") {
          base.forApis(apis).handleRequest(setEtherbase).unsafeRunSync().error shouldBe None
          coinbase.handleRequest(newJsonRpcRequest("eth_coinbase")).unsafeRunSync().result shouldBe before
        }
      }

    // without the test namespace the same request reaches the node's mining
    base.forApis(Seq("eth", "miner")).handleRequest(setEtherbase).unsafeRunSync().error shouldBe None
    coinbase.handleRequest(newJsonRpcRequest("eth_coinbase")).unsafeRunSync() should haveStringResult(address)

  // Spec 018 Track C, C2b: a state that is not held, where the method did not answer it itself, is the error that says
  // so, and not an internal error. C4a part 4: and not an execution error, which a client takes for a revert.
  it should "answer a state fault that escapes a method with the error for a state that is not held" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
    val net: JsonRpcController = jsonRpcController.forApis(Seq("net"))

    netService.versionFn = _ => IO.raiseError(new StateUnavailable("the state of block 9 is not held"))
    net.handleRequest(newJsonRpcRequest("net_version")).unsafeRunSync() should haveError(JsonRpcError.StateNotHeld)
    // one that is held and not handed out right now is one to ask for again
    netService.versionFn = _ => IO.raiseError(new StateUnavailable("too many reads in flight", askAgain = true))
    net.handleRequest(newJsonRpcRequest("net_version")).unsafeRunSync() should haveError(JsonRpcError.StateBusy)

    // any other failure is still an internal error
    netService.versionFn = _ => IO.raiseError(new IllegalStateException("not about the state"))
    net.handleRequest(newJsonRpcRequest("net_version")).unsafeRunSync() should haveError(JsonRpcError.InternalError)

  it should "serve the non-standard net_ peer methods with admin, and keep only version, listening and peerCount in net" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    netService.versionFn = _ => IO.pure(Right(VersionResponse("1")))
    // answers that are errors, so a method that is served is told apart from one that is not
    netService.nodeInfoFn = _ => IO.pure(Left(JsonRpcError.InternalError))
    netService.listPeersFn = _ => IO.pure(Left(JsonRpcError.InternalError))
    netService.listBlacklistedPeersFn = _ => IO.pure(Left(JsonRpcError.InternalError))

    val peerMethods: Seq[String] = Seq(
      "net_nodeInfo",
      "net_listPeers",
      "net_disconnectPeer",
      "net_connectToPeer",
      "net_listBlacklistedPeers",
      "net_addToBlacklist",
      "net_removeFromBlacklist"
    )
    def answer(controller: JsonRpcController, method: String): JsonRpcResponse =
      controller.handleRequest(newJsonRpcRequest(method)).unsafeRunSync()

    val netOnly: JsonRpcController = jsonRpcController.forApis(Seq("net"))
    answer(netOnly, "net_version") should haveStringResult("1")
    peerMethods.foreach(method =>
      withClue(method)(answer(netOnly, method) should haveError(JsonRpcError.MethodNotFound))
    )

    // with admin they are served, under the same names (a method without its parameters is served, then refused)
    val adminOnly: JsonRpcController = jsonRpcController.forApis(Seq("admin"))
    peerMethods.foreach { method =>
      withClue(method)(answer(adminOnly, method).error should not be Some(JsonRpcError.MethodNotFound))
    }
    answer(adminOnly, "net_listPeers") should haveError(JsonRpcError.InternalError)
    // admin does not bring net_version with it
    answer(adminOnly, "net_version") should haveError(JsonRpcError.MethodNotFound)

  it should "give each transport a controller with its own enabled namespaces" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig("""
      fukuii.network.rpc.http.apis = "eth,net,web3,fukuii,admin"
      fukuii.network.rpc.ws.apis = "eth"
    """)

    val perTransport: JsonRpcController.PerTransport = jsonRpcController.perTransport
    perTransport.http.enabledApis shouldBe Seq("eth", "net", "web3", "fukuii", "admin", "rpc")
    perTransport.ws.enabledApis shouldBe Seq("eth", "rpc")
    perTransport.ipc.enabledApis shouldBe (JsonRpcConfig.DefaultIpcApis :+ "rpc")
    // the controller the Engine API's pass-through uses keeps the shared set
    jsonRpcController.enabledApis shouldBe (JsonRpcConfig.DefaultHttpWsApis :+ "rpc")

  it should "report the namespaces of the transport it was narrowed to in rpc_modules, without changing the original" taggedAs (
    UnitTest,
    RPCTest
  ) in new JsonRpcControllerFixture:
    override def config: JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig()

    val narrowed: JsonRpcController = jsonRpcController.forApis(Seq("web3", "net"))

    modulesOf(this, narrowed) shouldBe Set("web3", "net", "rpc")
    narrowed.handleRequest(newJsonRpcRequest("eth_protocolVersion")).unsafeRunSync() should haveError(
      JsonRpcError.MethodNotFound
    )
    modulesOf(this, jsonRpcController) shouldBe Set("eth", "net", "web3", "fukuii", "rpc")

  it should "debug_listPeersInfo" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val peerStatus: RemoteStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(10000),
      bestHash = Fixtures.Blocks.Block3125369.header.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value
    )
    val initialPeerInfo: PeerInfo = PeerInfo(
      remoteStatus = peerStatus,
      chainWeight = peerStatus.chainWeight,
      forkAccepted = true,
      maxBlockNumber = Fixtures.Blocks.Block3125369.header.number.value,
      bestBlockHash = peerStatus.bestHash
    )
    val peers: List[PeerInfo] = List(initialPeerInfo)

    debugService.listPeersInfo
      .expects(ListPeersInfoRequest())
      .returning(IO.pure(Right(ListPeersInfoResponse(peers))))

    val rpcRequest: JsonRpcRequest = newJsonRpcRequest("debug_listPeersInfo")
    val response: JsonRpcResponse = jsonRpcController.handleRequest(rpcRequest).unsafeRunSync()

    response should haveResult(JArray(peers.map(info => JString(info.toString))))

  it should "rpc_modules" taggedAs (UnitTest, RPCTest) in new JsonRpcControllerFixture:
    val request: JsonRpcRequest = newJsonRpcRequest("rpc_modules")

    val response: JsonRpcResponse = jsonRpcController.handleRequest(request).unsafeRunSync()

    response should haveResult(
      JObject(
        "net" -> JString("1.0"),
        "rpc" -> JString("1.0"),
        "personal" -> JString("1.0"),
        "eth" -> JString("1.0"),
        "web3" -> JString("1.0"),
        "fukuii" -> JString("1.0"),
        "debug" -> JString("1.0"),
        "qa" -> JString("1.0")
      )
    )
