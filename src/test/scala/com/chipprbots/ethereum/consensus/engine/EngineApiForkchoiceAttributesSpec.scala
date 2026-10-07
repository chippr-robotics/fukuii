package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.ConcurrentLinkedQueue

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.jdk.CollectionConverters.*

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Withdrawal
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** engine_forkchoiceUpdatedV1/V2/V3 payload-attribute validation, in go-ethereum's order (eth/catalyst/api.go
  * `ForkchoiceUpdatedV1/V2/V3`): attribute shape first, fork window last.
  *
  * The order is what hive observes. `ForkchoiceUpdatedV3 To Request Shanghai Payload, Null Beacon Root (Cancun)` sends
  * V3 at a Shanghai timestamp with withdrawals and WITHOUT a beacon root and expects -38003; fukuii answered VALID with
  * a payloadId, because its only V3 beacon-root check was confined to Cancun timestamps and its fork check only fired
  * when a beacon root WAS present.
  *
  * Timestamps are the far-future fork sentinels of src/test/resources/application.conf.
  */
class EngineApiForkchoiceAttributesSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val ParisTs: Long = 1001L // below every timestamp fork
  private val ShanghaiTs: Long = 9999999994L // Shanghai, not Cancun
  private val CancunTs: Long = 9999999995L // Cancun, not Prague

  private val zeroHash32 = "0x" + "00" * 32
  private val headHash32 = "0x" + "11" * 32
  private val beaconRoot32 = "0x" + "ab" * 32
  private val zeroAddr20 = "0x" + "00" * 20

  private val InvalidParams = -32602
  private val InvalidAttributes = -38003
  private val UnsupportedFork = -38005

  private val validResponse = ForkchoiceUpdatedResponse(
    payloadStatus =
      PayloadStatusV1(PayloadStatus.Valid, latestValidHash = Some(ByteString(Array.fill(32)(0x11.toByte)))),
    payloadId = Some(ByteString(Array.fill(8)(0x01.toByte)))
  )

  /** Records what reaches the service, so a test can tell "rejected at the boundary" from "applied, then rejected". */
  private class RecordingService extends EngineApiService(null, null, null, null, None)(null, null):
    val calls = new ConcurrentLinkedQueue[(ForkChoiceState, Option[PayloadAttributes])]()
    override def forkchoiceUpdated(
        forkChoiceState: ForkChoiceState,
        payloadAttributes: Option[PayloadAttributes]
    ): IO[Either[String, ForkchoiceUpdatedResponse]] =
      calls.add((forkChoiceState, payloadAttributes))
      IO.pure(Right(validResponse.copy(payloadId = payloadAttributes.flatMap(_ => validResponse.payloadId))))

  private def attrsJson(ts: Long, withdrawals: Boolean, beaconRoot: Boolean): JObject =
    JObject(
      List[(String, JValue)](
        "timestamp" -> JString("0x" + ts.toHexString),
        "prevRandao" -> JString(zeroHash32),
        "suggestedFeeRecipient" -> JString(zeroAddr20)
      ) ++ (if withdrawals then List("withdrawals" -> JArray(Nil)) else Nil)
        ++ (if beaconRoot then List("parentBeaconBlockRoot" -> JString(beaconRoot32)) else Nil)
    )

  private def fcu(version: Int, attrs: JObject): (JsonRpcResponse, RecordingService) =
    val service = new RecordingService
    val fcs = JObject(
      "headBlockHash" -> JString(headHash32),
      "safeBlockHash" -> JString(zeroHash32),
      "finalizedBlockHash" -> JString(zeroHash32)
    )
    val request =
      JsonRpcRequest("2.0", s"engine_forkchoiceUpdatedV$version", Some(JArray(List(fcs, attrs))), Some(JInt(1)))
    (new EngineApiController(service).handleRequest(request).unsafeRunSync(), service)

  /** go-ethereum's decision, transcribed from eth/catalyst/api.go for the forks the test config can express. `None`
    * means the attributes pass the version gate. V1 is fukuii's documented deviation (see
    * `EngineApiController.payloadAttributesVersionError`): go-ethereum accepts Shanghai on V1 and reports -32602 from
    * Cancun on; we answer -38005 from Shanghai on.
    */
  private def expected(version: Int, ts: Long, withdrawals: Boolean, beaconRoot: Boolean): Option[Int] =
    val latestIsParis = ts == ParisTs
    val latestIsShanghai = ts == ShanghaiTs
    val latestIsCancun = ts == CancunTs
    version match
      case 1 =>
        if withdrawals || beaconRoot then Some(InvalidParams)
        else if !latestIsParis then Some(UnsupportedFork)
        else None
      case 2 =>
        if beaconRoot then Some(InvalidAttributes)
        else if latestIsParis && withdrawals then Some(InvalidAttributes)
        else if latestIsShanghai && !withdrawals then Some(InvalidAttributes)
        else if !(latestIsParis || latestIsShanghai) then Some(UnsupportedFork)
        else None
      case _ =>
        if !withdrawals then Some(InvalidAttributes)
        else if !beaconRoot then Some(InvalidAttributes)
        else if !latestIsCancun then Some(UnsupportedFork)
        else None

  "engine_forkchoiceUpdatedV3" should {

    "answer -38003 for a Shanghai-timestamp request with withdrawals and a NULL beacon root" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // hive `ForkchoiceUpdatedV3 To Request Shanghai Payload, Null Beacon Root (Cancun)`.
      val (response, _) = fcu(3, attrsJson(ShanghaiTs, withdrawals = true, beaconRoot = false))
      response.error.map(_.code) shouldBe Some(InvalidAttributes)
      response.result shouldBe None
    }

    "answer -38005 for the same request once the beacon root is present" taggedAs (UnitTest, ConsensusTest) in {
      // hive `ForkchoiceUpdatedV3 To Request Shanghai Payload, Non-Null Beacon Root (Cancun)`.
      val (response, service) = fcu(3, attrsJson(ShanghaiTs, withdrawals = true, beaconRoot = true))
      response.error.map(_.code) shouldBe Some(UnsupportedFork)
      service.calls.asScala shouldBe empty
    }

    "apply the forkchoice state (without attributes) before answering -38003" taggedAs (UnitTest, ConsensusTest) in {
      // hive `Invalid PayloadAttributes, *` asserts the head moved even though the attributes were refused.
      val (response, service) = fcu(3, attrsJson(ShanghaiTs, withdrawals = true, beaconRoot = false))
      response.error.map(_.code) shouldBe Some(InvalidAttributes)
      service.calls.asScala.toList.map(_._2) shouldBe List(None)
    }

    "refuse an Amsterdam timestamp with -38005 (Amsterdam needs V4)" taggedAs (UnitTest, ConsensusTest) in {
      val base = Config.blockchains.blockchainConfig
      val amsterdam = base.copy(forkTimestamps = base.forkTimestamps.copy(amsterdamTimestamp = Some(CancunTs)))
      val attrs = PayloadAttributes(
        timestamp = CancunTs,
        prevRandao = ByteString(new Array[Byte](32)),
        suggestedFeeRecipient = Address(ByteString(new Array[Byte](20))),
        withdrawals = Some(Seq.empty[Withdrawal]),
        parentBeaconBlockRoot = Some(ByteString(new Array[Byte](32)))
      )
      EngineApiController.payloadAttributesVersionError(3, attrs, amsterdam).map(_._1) shouldBe Some(UnsupportedFork)
      EngineApiController.payloadAttributesVersionError(3, attrs, base) shouldBe None
    }
  }

  "engine_forkchoiceUpdatedV1/V2/V3" should {

    "match go-ethereum's error code for every (version, fork, withdrawals, beacon root) combination" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      for
        version <- Seq(1, 2, 3)
        ts <- Seq(ParisTs, ShanghaiTs, CancunTs)
        withdrawals <- Seq(false, true)
        beaconRoot <- Seq(false, true)
      do
        val (response, service) = fcu(version, attrsJson(ts, withdrawals, beaconRoot))
        val want = expected(version, ts, withdrawals, beaconRoot)
        withClue(s"V$version ts=$ts withdrawals=$withdrawals beaconRoot=$beaconRoot: ") {
          response.error.map(_.code) shouldBe want
          want match
            case None =>
              // Accepted: the attributes reach the service, which builds.
              service.calls.asScala.toList.map(_._2.isDefined) shouldBe List(true)
              response.result.isDefined shouldBe true
            case Some(InvalidAttributes) =>
              // Refused after applying the forkchoice state alone.
              service.calls.asScala.toList.map(_._2) shouldBe List(None)
            case Some(_) =>
              // -32602 / -38005: the wrong method or the wrong params; nothing is applied.
              service.calls.asScala shouldBe empty
        }
    }

    "leave a forkchoice without attributes alone, whatever the version" taggedAs (UnitTest, ConsensusTest) in {
      for version <- Seq(1, 2, 3) do
        val service = new RecordingService
        val fcs = JObject(
          "headBlockHash" -> JString(headHash32),
          "safeBlockHash" -> JString(zeroHash32),
          "finalizedBlockHash" -> JString(zeroHash32)
        )
        val request =
          JsonRpcRequest("2.0", s"engine_forkchoiceUpdatedV$version", Some(JArray(List(fcs))), Some(JInt(1)))
        val response = new EngineApiController(service).handleRequest(request).unsafeRunSync()
        withClue(s"V$version: ")(response.error shouldBe None)
    }
  }
