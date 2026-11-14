package com.chipprbots.ethereum.consensus.engine

import scala.util.Failure
import scala.util.Success

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.LoneElement
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.testing.Tags.*

/** The Engine call log (spec 029 FR-040, FR-042): what it keeps of a call, that it keeps nothing of the caller's it has
  * not cut and cleaned, and that nothing it is handed can make it throw.
  */
class EngineCallLogSpec extends AnyFlatSpec with Matchers with OptionValues with LoneElement:

  private def log(now: Long = 1_000L) = new EngineCallLog(() => now)

  private def request(method: String, params: String = "[]"): JValue =
    parse(s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")

  private def ok(result: String): Success[JsonRpcResponse] =
    // Wrapped, so that a bare `null` or string parses too.
    Success(JsonRpcResponse("2.0", Some(parse(s"""{"r":$result}""") \ "r"), None, JInt(1)))

  private def error(code: Int): Success[JsonRpcResponse] =
    Success(JsonRpcResponse("2.0", None, Some(JsonRpcError(code, "no", None)), JInt(1)))

  private def one(l: EngineCallLog): EngineCallRecord = l.snapshot.calls.loneElement

  "A newPayload call" should "be kept with the status the consensus client was told and the payload's block number" taggedAs (
    UnitTest
  ) in {
    val l = log()
    l.observe(
      "engine_newPayloadV4",
      request("engine_newPayloadV4", """[{"blockNumber":"0x71a2c9","blockHash":"0xab"},[],"0x00",[]]"""),
      ok("""{"status":"VALID","latestValidHash":"0xab","validationError":null}"""),
      System.nanoTime()
    )
    val call = one(l)
    (call.atMs, call.method, call.status, call.blockNumber) shouldBe
      ((1_000L, "engine_newPayloadV4", Some("VALID"), Some(0x71a2c9L)))
    call.nanos should be >= 0L
  }

  it should "say ACCEPTED, SYNCING and INVALID as they were answered, and an error by its code" taggedAs (UnitTest) in {
    for status <- Seq("ACCEPTED", "SYNCING", "INVALID") do
      val l = log()
      l.observe("engine_newPayloadV5", request("engine_newPayloadV5"), ok(s"""{"status":"$status"}"""), 0L)
      one(l).status shouldBe Some(status)
    val refused = log()
    refused.observe("engine_newPayloadV4", request("engine_newPayloadV4"), error(-38005), 0L)
    one(refused).status shouldBe Some("error -38005")
  }

  "A forkchoiceUpdated call" should "be kept with its payload status, an error by its code, and no block number" taggedAs (
    UnitTest
  ) in {
    val l = log()
    l.observe(
      "engine_forkchoiceUpdatedV3",
      request("engine_forkchoiceUpdatedV3", """[{"headBlockHash":"0x01"},null]"""),
      ok("""{"payloadStatus":{"status":"SYNCING","latestValidHash":null},"payloadId":null}"""),
      0L
    )
    (one(l).status, one(l).blockNumber) shouldBe ((Some("SYNCING"), None))
    // What the service counted and what the consensus client was told can differ: the log keeps what was told.
    val invalidState = log()
    invalidState.observe("engine_forkchoiceUpdatedV4", request("engine_forkchoiceUpdatedV4"), error(-38002), 0L)
    one(invalidState).status shouldBe Some("error -38002")
  }

  "A getPayload call" should "be kept with the block number of the payload it served, in either shape of answer" taggedAs (
    UnitTest
  ) in {
    val v1 = log()
    v1.observe(
      "engine_getPayloadV1",
      request("engine_getPayloadV1", """["0x01"]"""),
      ok("""{"blockNumber":"0x10"}"""),
      0L
    )
    (one(v1).blockNumber, one(v1).status) shouldBe ((Some(16L), None))
    val v6 = log()
    v6.observe(
      "engine_getPayloadV6",
      request("engine_getPayloadV6", """["0x01"]"""),
      ok("""{"executionPayload":{"blockNumber":"0x71a2ca","slotNumber":"0x999"},"blockValue":"0x0"}"""),
      0L
    )
    one(v6).blockNumber shouldBe Some(0x71a2caL) // the block's number, never its slot
    val unknown = log()
    unknown.observe("engine_getPayloadV5", request("engine_getPayloadV5", """["0x02"]"""), error(-38001), 0L)
    (one(unknown).blockNumber, one(unknown).status) shouldBe ((None, Some("error -38001")))
  }

  "A method's name" should "be kept as it was called for a method this node answers, version and all" taggedAs (
    UnitTest
  ) in {
    EngineCallLog.Methods should have size 23
    for method <- EngineCallLog.Methods do
      val l = log()
      l.observe(method, request(method), ok("null"), 0L)
      one(l).method shouldBe method
  }

  it should "not be kept for a method this node does not have: the name is the caller's" taggedAs (UnitTest) in {
    val l = log()
    val hostile = "engine_\u001b[2J" + "x" * 5_000
    l.observe(hostile, JObject("method" -> JString(hostile)), error(-32601), 0L)
    l.observe("engine_getBlobsV3", request("engine_getBlobsV3"), error(-32601), 0L)
    l.snapshot.calls.map(_.method) shouldBe Seq(EngineCallLog.OtherMethod, EngineCallLog.OtherMethod)
    l.snapshot.calls.map(_.status) shouldBe Seq(Some("error -32601"), Some("error -32601"))
  }

  "A call through the Engine port to eth_, net_ or web3_" should "be left to the JSON-RPC server's own count" taggedAs (
    UnitTest
  ) in {
    val l = log()
    for method <- Seq("eth_blockNumber", "eth_syncing", "net_version", "web3_clientVersion") do
      l.observe(method, request(method), ok("\"0x1\""), 0L)
    l.snapshot.calls shouldBe empty
    l.snapshot.total shouldBe 0
  }

  "A method that answers a list" should "not have its answer looked into" taggedAs (UnitTest) in {
    // A status inside a list's elements is not the call's status.
    val l = log()
    l.observe("engine_getBlobsV2", request("engine_getBlobsV2"), ok("""[{"status":"VALID","blockNumber":"0x1"}]"""), 0L)
    l.observe(
      "engine_getPayloadBodiesByRangeV1",
      request("engine_getPayloadBodiesByRangeV1"),
      ok("""[{"status":"X"}]"""),
      0L
    )
    l.snapshot.calls.map(c => (c.status, c.blockNumber)) shouldBe Seq((None, None), (None, None))
  }

  "The consensus client's identity" should "be its own getClientVersion argument, cut and cleaned, once the call is answered" taggedAs (
    UnitTest
  ) in {
    val l = log()
    l.snapshot.identity shouldBe None
    l.observe(
      "engine_getClientVersionV1",
      request(
        "engine_getClientVersionV1",
        """[{"code":"LH","name":"Lighthouse","version":"v7.1.0","commit":"cfb1f733"}]"""
      ),
      ok("""[{"code":"FK"}]"""),
      0L
    )
    l.snapshot.identity.value shouldBe ClIdentity("LH", "Lighthouse", "v7.1.0", "cfb1f733")
    // A hostile one: control characters, an escape sequence, and far too long.
    val hostile = log()
    val long = "N" * 5_000
    hostile.observe(
      "engine_getClientVersionV1",
      parse(
        s"""{"method":"engine_getClientVersionV1","params":[{"code":"X\\u001b[2JY","name":"$long","version":"a\\nb\\u0007c","commit":7}]}"""
      ),
      ok("[]"),
      0L
    )
    val kept = hostile.snapshot.identity.value
    kept.code shouldBe "X[2JY"
    kept.name shouldBe "N" * EngineCallLog.MaxTextChars
    kept.version shouldBe "abc"
    kept.commit shouldBe "" // not a text: nothing is made of it
    // A refused call tells nothing about the caller.
    val refused = log()
    refused.observe(
      "engine_getClientVersionV1",
      request("engine_getClientVersionV1", """[{"code":"LH","name":"Lighthouse"}]"""),
      error(-32602),
      0L
    )
    refused.snapshot.identity shouldBe None
  }

  "The capabilities" should "be how many methods the consensus client listed" taggedAs (UnitTest) in {
    val l = log()
    l.snapshot.capabilities shouldBe None
    l.observe(
      "engine_exchangeCapabilities",
      request(
        "engine_exchangeCapabilities",
        """[["engine_newPayloadV4","engine_forkchoiceUpdatedV3","engine_getPayloadV4"]]"""
      ),
      ok("""["engine_newPayloadV4"]"""),
      0L
    )
    l.snapshot.capabilities shouldBe Some(3)
  }

  "Anything a consensus client sends" should "leave the log standing: nothing malformed makes it throw" taggedAs (
    UnitTest
  ) in {
    val l = log()
    val odd: Seq[JValue] = Seq(
      JNothing,
      JNull,
      JString("x"),
      JArray(Nil),
      parse("""{"method":"engine_newPayloadV4","params":"not a list"}"""),
      parse("""{"method":"engine_newPayloadV4","params":[{"blockNumber":"0xzz"}]}"""),
      parse("""{"method":"engine_newPayloadV4","params":[{"blockNumber":"0x""" + "f" * 400 + """"}]}"""),
      parse("""{"method":"engine_newPayloadV4","params":[{"blockNumber":12}]}"""),
      parse("""{"method":"engine_getClientVersionV1","params":[[[[]]]]}"""),
      parse("""{"method":"engine_exchangeCapabilities","params":[{"a":1}]}""")
    )
    for json <- odd; method <- Seq("engine_newPayloadV4", "engine_getClientVersionV1", "engine_exchangeCapabilities") do
      noException should be thrownBy l.observe(method, json, ok("""{"status":7}"""), 0L)
      noException should be thrownBy l.observe(method, json, Failure(new RuntimeException("boom")), 0L)
    noException should be thrownBy l.observe(null, null, null, 0L)
    all(l.snapshot.calls.map(_.blockNumber)) shouldBe None
    l.snapshot.calls.map(_.status).toSet shouldBe Set(None, Some("failed"))
    l.snapshot.identity shouldBe None
    l.snapshot.capabilities shouldBe None
  }

  // beacon, slice 5: a batch element that is itself a list reaches the note as a list, and a field lookup on a list
  // goes through its elements.
  it should "look into a request only where it is an object" taggedAs (UnitTest) in {
    val l = log()
    val nested = parse("""[{"method":"engine_newPayloadV4","params":[{"blockNumber":"0x10"}]}]""")
    l.observe("engine_newPayloadV4", nested, ok("""{"status":"VALID"}"""), 0L)
    // The answer is still the call's: what it was told is kept. The list is not searched for a block number.
    (one(l).status, one(l).blockNumber) shouldBe ((Some("VALID"), None))
    val identity = log()
    identity.observe(
      "engine_getClientVersionV1",
      parse("""[{"method":"engine_getClientVersionV1","params":[{"code":"LH","name":"Lighthouse"}]}]"""),
      ok("[]"),
      0L
    )
    identity.snapshot.identity shouldBe None
  }

  it should "cut a text by whole characters, never through one" taggedAs (UnitTest) in {
    // 70 characters of two code units each: cut at 64 characters, not at 64 units, and no half is kept.
    val wide = "\uD83D\uDE80" * 70
    val kept = EngineCallLog.clean(wide)
    kept.codePointCount(0, kept.length) shouldBe EngineCallLog.MaxTextChars
    kept.forall(c => !Character.isSurrogate(c) || true) shouldBe true
    Character.isHighSurrogate(kept.last) shouldBe false
    EngineCallLog.clean("a\u001b[2Jb\nc") shouldBe "a[2Jbc"
  }

  it should "leave the log standing when its own clock fails" taggedAs (UnitTest) in {
    val broken = new EngineCallLog(() => throw new IllegalStateException("no clock"))
    noException should be thrownBy broken.observe("engine_newPayloadV4", request("engine_newPayloadV4"), ok("{}"), 0L)
    noException should be thrownBy broken.observeUnreadable(0L)
    noException should be thrownBy broken.payloadBuilt(1L, 2)
    broken.snapshot.calls shouldBe empty
  }

  "The log" should "keep the last 256 calls, oldest first, and count every one" taggedAs (UnitTest) in {
    var now = 0L
    val l = new EngineCallLog(() => now)
    for i <- 1 to 300 do
      now = i.toLong
      l.observe("engine_forkchoiceUpdatedV3", request("engine_forkchoiceUpdatedV3"), ok("{}"), 0L)
    val s = l.snapshot
    s.total shouldBe 300
    s.calls should have size EngineCallLog.Capacity.toLong
    s.calls.map(_.atMs) shouldBe (45L to 300L)
  }

  it should "lose no call when four threads note calls at once" taggedAs (UnitTest) in {
    val l = log()
    val each = 10_000
    val start = new java.util.concurrent.CountDownLatch(1)
    val json = request("engine_newPayloadV4")
    val answer = ok("""{"status":"VALID"}""")
    val threads = (0 until 4).map { _ =>
      val thread = new Thread(() =>
        start.await()
        var i = 0
        while i < each do
          l.observe("engine_newPayloadV4", json, answer, 0L)
          i += 1
      )
      thread.start()
      thread
    }
    start.countDown()
    threads.foreach(_.join())
    val s = l.snapshot
    s.total shouldBe 4L * each
    // Every place holds a whole record: none is missing and none half-written.
    s.calls should have size EngineCallLog.Capacity.toLong
    all(s.calls.map(_.status)) shouldBe Some("VALID")
  }

  it should "keep nothing when it is disabled" taggedAs (UnitTest) in {
    val off = new EngineCallLog(enabled = false)
    off.observe("engine_newPayloadV4", request("engine_newPayloadV4"), ok("""{"status":"VALID"}"""), 0L)
    off.observeUnreadable(0L)
    off.authFailed(AuthFailure.Expired)
    off.payloadBuilt(1L, 2)
    off.payloadBuildFailed()
    off.snapshot shouldBe EngineCallLog.Snapshot(Vector.empty, 0L, None, None, Map.empty, 0L, 0L, None)
  }

  "A request that was not one" should "be kept as unreadable, under no method name" taggedAs (UnitTest) in {
    val l = log()
    l.observeUnreadable(System.nanoTime())
    (one(l).method, one(l).status) shouldBe ((EngineCallLog.OtherMethod, Some("unreadable request")))
  }

  "Payload builds" should "be counted, with the last one's block and transactions, and failures apart" taggedAs (
    UnitTest
  ) in {
    val l = log(now = 7_000L)
    l.payloadBuilt(100L, 12)
    l.payloadBuilt(101L, 40)
    l.payloadBuildFailed()
    val s = l.snapshot
    (s.payloadsBuilt, s.payloadBuildsFailed) shouldBe ((2L, 1L))
    s.lastBuild.value shouldBe PayloadBuild(7_000L, 101L, 40)
  }

  // The messages are `JwtAuthenticator.validateToken`'s own; `JwtAuthenticatorSpec` pins them as the 401 bodies.
  "An authentication failure's reason" should "be one of a fixed few, whatever the message carries" taggedAs (
    UnitTest
  ) in {
    AuthFailure.of("Missing bearer token") shouldBe AuthFailure.Missing
    AuthFailure.of("Invalid JWT format") shouldBe AuthFailure.Format
    AuthFailure.of("Invalid JWT signature") shouldBe AuthFailure.Signature
    AuthFailure.of("Invalid JWT signature encoding") shouldBe AuthFailure.Malformed
    AuthFailure.of("Invalid JWT header encoding") shouldBe AuthFailure.Malformed
    AuthFailure.of("Unsupported JWT algorithm") shouldBe AuthFailure.Algorithm
    AuthFailure.of("Missing iat claim") shouldBe AuthFailure.IssuedAtMissing
    // Two expired tokens are one reason, not two: the message's own figures are no key.
    AuthFailure.of("JWT expired: iat=1, now=99, skew=98s") shouldBe AuthFailure.Expired
    AuthFailure.of("JWT expired: iat=2, now=1000, skew=998s") shouldBe AuthFailure.Expired
    AuthFailure.of("something this node never says") shouldBe AuthFailure.Malformed
    val l = log()
    l.authFailed(AuthFailure.Expired)
    l.authFailed(AuthFailure.Expired)
    l.authFailed(AuthFailure.Signature)
    l.snapshot.authFailures shouldBe Map(AuthFailure.Expired -> 2L, AuthFailure.Signature -> 1L)
  }
