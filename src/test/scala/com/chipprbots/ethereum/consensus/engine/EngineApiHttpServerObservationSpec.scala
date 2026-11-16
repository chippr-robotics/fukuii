package com.chipprbots.ethereum.consensus.engine

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.pekko.http.scaladsl.model.ContentType
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest

import cats.effect.IO

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.testing.Tags.*

/** The Engine API's HTTP layer with and without the dashboard's observation (spec 029 FR-042): for each request, the
  * answer's bytes, status and content type are the same with the call log keeping calls, keeping nothing, and failing
  * at every note; and what the log keeps is what was on the wire.
  *
  * The handler is a stand-in that answers each method with a fixed, typed answer of the shape the real one gives: this
  * slice changes nothing in `EngineApiController`, whose own specs drive it. What is under test is
  * `EngineApiHttpServer.processRequest` and the route around it.
  */
class EngineApiHttpServerObservationSpec
    extends AnyFlatSpec
    with Matchers
    with ScalatestRouteTest
    with Eventually
    with BeforeAndAfterAll:

  // The note of a call is made by a callback of its own, beside the answer: a test waits for it. ScalaTest's own
  // patience is 150 ms, which a loaded runner does not always give a callback (the suite failed once in CI, in a run of
  // 890 suites, and passes alone); a note that never comes still fails, a few seconds later.
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = org.scalatest.time.Span(5, org.scalatest.time.Seconds),
      interval = org.scalatest.time.Span(15, org.scalatest.time.Millis)
    )

  private val secretHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
  private val secret: Array[Byte] = secretHex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private def b64(text: String): String = Base64.getUrlEncoder.withoutPadding.encodeToString(text.getBytes(UTF_8))

  private def token: String =
    val input = s"${b64("""{"alg":"HS256","typ":"JWT"}""")}.${b64(s"""{"iat":${Instant.now().getEpochSecond}}""")}"
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret, "HmacSHA256"))
    s"$input.${Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(input.getBytes(UTF_8)))}"

  private def json(text: String): JValue = parse(s"""{"r":$text}""") \ "r"
  private def answer(result: String, id: JValue): JsonRpcResponse = JsonRpcResponse("2.0", Some(json(result)), None, id)
  private def refusal(code: Int, id: JValue): JsonRpcResponse =
    JsonRpcResponse("2.0", None, Some(JsonRpcError(code, s"refused $code", None)), id)

  /** A handler with the real one's shapes of answer. */
  private def controller: EngineApiController =
    new EngineApiController(new EngineApiService(null, null, null, null, None)(using null, null) {}):
      override def handleRequest(request: JsonRpcRequest): IO[JsonRpcResponse] =
        val id = request.id.getOrElse(JNull)
        val first = request.params.flatMap(_.arr.headOption)
        request.method match
          case m if m.startsWith("engine_newPayload") =>
            val status = first.map(_ \ "extraData") match
              case Some(JString("accepted")) => "ACCEPTED"
              case Some(JString("invalid"))  => "INVALID"
              case Some(JString("refuse"))   => "refuse"
              case _                         => "VALID"
            if status == "refuse" then IO.pure(refusal(-38005, id))
            else
              IO.pure(answer(s"""{"status":"$status","latestValidHash":"0x${"ab" * 32}","validationError":null}""", id))
          case m if m.startsWith("engine_forkchoiceUpdated") =>
            first.map(_ \ "headBlockHash") match
              case Some(JString("0xsyncing")) =>
                IO.pure(
                  answer("""{"payloadStatus":{"status":"SYNCING","latestValidHash":null},"payloadId":null}""", id)
                )
              case Some(JString("0xbadstate")) => IO.pure(refusal(-38002, id))
              case Some(JString("0xbadattrs")) => IO.pure(refusal(-38003, id))
              case _ =>
                IO.pure(
                  answer(
                    s"""{"payloadStatus":{"status":"VALID","latestValidHash":"0x${"cd" * 32}"},"payloadId":"0x0000000000000001"}""",
                    id
                  )
                )
          case "engine_getPayloadV1" => IO.pure(answer("""{"blockNumber":"0x10","transactions":[]}""", id))
          case m if m.startsWith("engine_getPayloadV") =>
            IO.pure(
              answer("""{"executionPayload":{"blockNumber":"0x71a2ca","transactions":[]},"blockValue":"0x0"}""", id)
            )
          case "engine_getClientVersionV1" =>
            IO.pure(answer("""[{"code":"FK","name":"Fukuii","version":"0.11.0","commit":"abcdef1"}]""", id))
          case "engine_exchangeCapabilities" => IO.pure(answer("""["engine_newPayloadV4","engine_getPayloadV4"]""", id))
          case "engine_getBlobsV2"           => IO.pure(answer("""[null,null]""", id))
          case "engine_throws"               => IO.raiseError(new IllegalStateException("handler failed"))
          case "engine_throwsNow"            => throw new IllegalStateException("handler failed before its IO")
          case _ => IO.pure(JsonRpcResponse("2.0", None, Some(JsonRpcError.MethodNotFound), id))

  private val config =
    EngineApiHttpServer.Config(enabled = true, interface = "127.0.0.1", port = 0, jwtSecretPath = None)

  private def server(log: EngineCallLog): EngineApiHttpServer =
    new EngineApiHttpServer(controller, new JwtAuthenticator(secretHex, log), config, log)

  private val keeping = new EngineCallLog(() => 42_000L)
  private val off = new EngineCallLog(enabled = false)
  private val failing = new EngineCallLog(() => throw new IllegalStateException("the log's clock failed"))
  private val servers = Seq(server(keeping), server(off), server(failing))

  override def afterAll(): Unit =
    servers.foreach(_.stopSync())
    super.afterAll()

  /** What a client receives for `body` from `server`. */
  private def reply(server: EngineApiHttpServer, body: String): (StatusCode, ContentType, String) =
    Post("/", HttpEntity(ContentTypes.`application/json`, body))
      .addHeader(Authorization(OAuth2BearerToken(token))) ~> server.route ~> check(
      (status, contentType, responseAs[String])
    )

  private def call(method: String, params: String = "[]", id: String = "7"): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

  private val requests: Seq[(String, String)] = Seq(
    "newPayloadV3 VALID" -> call(
      "engine_newPayloadV3",
      """[{"blockNumber":"0x71a2c9","extraData":"valid"},[],"0x00"]"""
    ),
    "newPayloadV4 INVALID" -> call(
      "engine_newPayloadV4",
      """[{"blockNumber":"0x71a2ca","extraData":"invalid"},[],"0x00",[]]"""
    ),
    "newPayloadV5 ACCEPTED" -> call(
      "engine_newPayloadV5",
      """[{"blockNumber":"0x71a2cb","extraData":"accepted"},[],"0x00",[]]"""
    ),
    "newPayloadV4 refused for its fork" -> call(
      "engine_newPayloadV4",
      """[{"blockNumber":"0x71a2cc","extraData":"refuse"}]"""
    ),
    "forkchoiceUpdatedV3 VALID with a payload id" -> call(
      "engine_forkchoiceUpdatedV3",
      """[{"headBlockHash":"0x01"},{"timestamp":"0x1"}]"""
    ),
    "forkchoiceUpdatedV4 SYNCING" -> call("engine_forkchoiceUpdatedV4", """[{"headBlockHash":"0xsyncing"},null,[]]"""),
    "forkchoiceUpdatedV3 invalid state" -> call(
      "engine_forkchoiceUpdatedV3",
      """[{"headBlockHash":"0xbadstate"},null]"""
    ),
    "forkchoiceUpdatedV4 invalid attributes" -> call(
      "engine_forkchoiceUpdatedV4",
      """[{"headBlockHash":"0xbadattrs"},{}]"""
    ),
    "getPayloadV1" -> call("engine_getPayloadV1", """["0x0000000000000001"]"""),
    "getPayloadV5" -> call("engine_getPayloadV5", """["0x0000000000000001"]"""),
    "getPayloadV6" -> call("engine_getPayloadV6", """["0x0000000000000001"]"""),
    "getClientVersionV1" -> call(
      "engine_getClientVersionV1",
      """[{"code":"LH","name":"Lighthouse","version":"v7.1.0","commit":"cfb1f733"}]"""
    ),
    "getClientVersionV1 with a malformed argument" -> call("engine_getClientVersionV1", """[[7,"x",{"name":null}]]"""),
    "exchangeCapabilities" -> call(
      "engine_exchangeCapabilities",
      """[["engine_newPayloadV4","engine_forkchoiceUpdatedV3"]]"""
    ),
    "getBlobsV2" -> call("engine_getBlobsV2", """[["0x01","0x02"]]"""),
    "a method the node has not" -> call("engine_getBlobsV9"),
    "a pass-through method" -> call("eth_blockNumber"),
    "a handler whose IO fails" -> call("engine_throws"),
    "a handler that throws before its IO" -> call("engine_throwsNow"),
    "a request with no id and no params" -> """{"jsonrpc":"2.0","method":"engine_exchangeCapabilities"}""",
    "a request with no method" -> """{"jsonrpc":"2.0","id":1}""",
    "a batch of three, in order" -> s"[${call("engine_newPayloadV4", """[{"blockNumber":"0x1"}]""", "1")},${call("engine_forkchoiceUpdatedV3", """[{"headBlockHash":"0xsyncing"},null]""", "2")},${call("engine_getPayloadV1", "[]", "3")}]",
    "an empty batch" -> "[]",
    "a body that is JSON but no request" -> "\"hello\"",
    "a body that is not JSON" -> "{not json"
  )

  for (name, body) <- requests do
    "The Engine API's answer" should s"be the same bytes, status and type with and without the observation: $name" taggedAs (
      UnitTest
    ) in {
      val kept = reply(servers(0), body)
      val unobserved = reply(servers(1), body)
      val broken = reply(servers(2), body)
      kept shouldBe unobserved
      broken shouldBe unobserved
    }

  "The batch" should "be answered in the order it was asked" taggedAs (UnitTest) in {
    val (_, _, body) = reply(servers(1), requests.find(_._1.startsWith("a batch")).get._2)
    (parse(body) \ "id") shouldBe JArray(List(JInt(1), JInt(2), JInt(3)))
  }

  "The call log" should "hold what was on the wire for each call: the method, the status told, the block" taggedAs (
    UnitTest
  ) in {
    val log = new EngineCallLog(() => 1_000L)
    val observed = server(log)
    try
      def told(body: String): (String, Option[String], Option[Long]) =
        val before = log.snapshot.total
        val (_, _, wire) = reply(observed, body)
        // The note is made beside the answer, by a callback of its own: it is there a moment after.
        eventually(log.snapshot.total shouldBe before + 1)
        val last = log.snapshot.calls.last
        // What the log says the client was told is what the client was told.
        val onWire = parse(wire)
        val statusOnWire = (
          onWire \ "result" \ "status",
          onWire \ "result" \ "payloadStatus" \ "status",
          onWire \ "error" \ "code"
        ) match
          case (JString(s), _, _) => Some(s)
          case (_, JString(s), _) => Some(s)
          case (_, _, JInt(code)) => Some(s"error $code")
          case _                  => None
        last.status shouldBe statusOnWire
        (last.method, last.status, last.blockNumber)
      val by = requests.toMap
      told(by("newPayloadV3 VALID")) shouldBe (("engine_newPayloadV3", Some("VALID"), Some(0x71a2c9L)))
      told(by("newPayloadV5 ACCEPTED")) shouldBe (("engine_newPayloadV5", Some("ACCEPTED"), Some(0x71a2cbL)))
      told(by("newPayloadV4 refused for its fork")) shouldBe ((
        "engine_newPayloadV4",
        Some("error -38005"),
        Some(0x71a2ccL)
      ))
      told(by("forkchoiceUpdatedV4 SYNCING")) shouldBe (("engine_forkchoiceUpdatedV4", Some("SYNCING"), None))
      told(by("forkchoiceUpdatedV3 invalid state")) shouldBe ((
        "engine_forkchoiceUpdatedV3",
        Some("error -38002"),
        None
      ))
      told(by("getPayloadV6")) shouldBe (("engine_getPayloadV6", None, Some(0x71a2caL)))
      told(by("a method the node has not")) shouldBe ((EngineCallLog.OtherMethod, Some("error -32601"), None))
      told(by("a handler whose IO fails")) shouldBe ((EngineCallLog.OtherMethod, Some("error -32603"), None))
      // The consensus client's identity and capability count come from its own calls.
      told(by("getClientVersionV1"))
      told(by("exchangeCapabilities"))
      log.snapshot.identity shouldBe Some(ClIdentity("LH", "Lighthouse", "v7.1.0", "cfb1f733"))
      log.snapshot.capabilities shouldBe Some(2)
      // A pass-through call is not an Engine call.
      val before = log.snapshot.total
      reply(observed, by("a pass-through method"))
      reply(observed, by("getBlobsV2"))
      eventually(log.snapshot.total shouldBe before + 1)
      log.snapshot.calls.last.method shouldBe "engine_getBlobsV2"
    finally observed.stopSync()
  }

  it should "count each call of a batch, and a body that was no request once" taggedAs (UnitTest) in {
    val log = new EngineCallLog(() => 1_000L)
    val observed = server(log)
    try
      reply(observed, requests.find(_._1.startsWith("a batch")).get._2)
      eventually(log.snapshot.total shouldBe 3)
      log.snapshot.calls.map(_.method).toSet shouldBe
        Set("engine_newPayloadV4", "engine_forkchoiceUpdatedV3", "engine_getPayloadV1")
      reply(observed, "{not json")._1 shouldBe StatusCodes.BadRequest
      eventually(log.snapshot.total shouldBe 4)
      log.snapshot.calls.last.status shouldBe Some("unreadable request")
    finally observed.stopSync()
  }

  // beacon, slice 5: the stand-in's answers have the real ones' shapes by reading; this goes through the real,
  // unmodified controller for the two methods that need no chain behind them, so a field renamed there is caught.
  "The real controller behind the route" should "answer as it does without the observation, and be noted from the request" taggedAs (
    UnitTest
  ) in {
    val real = new EngineApiController(new EngineApiService(null, null, null, null, None)(using null, null) {})
    val log = new EngineCallLog(() => 1_000L)
    val observed = new EngineApiHttpServer(real, new JwtAuthenticator(secretHex, log), config, log)
    val unobserved = new EngineApiHttpServer(real, new JwtAuthenticator(secretHex, off), config, off)
    try
      val version = call(
        "engine_getClientVersionV1",
        """[{"code":"LH","name":"Lighthouse","version":"v7.1.0","commit":"cfb1f733"}]"""
      )
      val capabilities = call(
        "engine_exchangeCapabilities",
        """[["engine_newPayloadV4","engine_forkchoiceUpdatedV3","engine_getPayloadV4"]]"""
      )
      for body <- Seq(version, capabilities) do reply(observed, body) shouldBe reply(unobserved, body)
      // The node's own answer names itself; the log holds who asked.
      (parse(reply(observed, version)._3) \ "result")(0) \ "code" shouldBe JString("FK")
      eventually(log.snapshot.total shouldBe 3) // the two compared above, and the one just read
      log.snapshot.identity shouldBe Some(ClIdentity("LH", "Lighthouse", "v7.1.0", "cfb1f733"))
      log.snapshot.capabilities shouldBe Some(3)
      all(log.snapshot.calls.map(_.status)) shouldBe None // both were answered, and neither answer is a status
    finally
      observed.stopSync()
      unobserved.stopSync()
  }

  "A request that fails authentication" should "be answered as before, and its reason kept" taggedAs (UnitTest) in {
    val log = new EngineCallLog(() => 1_000L)
    val observed = server(log)
    try
      def unauthenticated(s: EngineApiHttpServer) =
        Post("/", HttpEntity(ContentTypes.`application/json`, call("engine_newPayloadV4"))) ~> s.route ~> check(
          (status, responseAs[String])
        )
      unauthenticated(observed) shouldBe unauthenticated(servers(1))
      unauthenticated(observed) shouldBe ((StatusCodes.Unauthorized, "Missing bearer token"))
      log.snapshot.authFailures shouldBe Map(AuthFailure.Missing -> 2L)
      log.snapshot.calls shouldBe empty // it never became a call
    finally observed.stopSync()
  }
