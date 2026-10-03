package com.chipprbots.ethereum.consensus.engine

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** Fork-version gating for engine_newPayloadV4 — Prague/Osaka acceptance window (T10-C).
  *
  * V4 must be accepted for Prague and Osaka timestamps, and rejected (UNSUPPORTED_FORK -38005) for any pre-Prague
  * timestamp and from Amsterdam on (execution-apis amsterdam.md "Update the methods of previous forks"; V5 takes over).
  * An ExecutionPayloadV4 field (`slotNumber`, `blockAccessList`) sent to V4 is -32602 (go-ethereum NewPayloadV4; EEST
  * `bal_invalid_engine_payload_field_before_fork`, `invalid_pre_fork_block_with_slot_number`).
  *
  * Timestamps used are far-future sentinels from src/test/resources/application.conf: prague-timestamp = 9999999998
  * osaka-timestamp = 9999999999. Amsterdam is declared on a copy of that config, which the controller is given.
  */
class EngineApiNewPayloadV4Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val PragueTs: Long = 9999999998L
  private val PrePragueTs: Long = 9999999997L // one second before Prague
  private val AmsterdamTs: Long = 10000000003L

  private val amsterdamConfig =
    val base = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
    base.copy(forkTimestamps = base.forkTimestamps.copy(amsterdamTimestamp = Some(AmsterdamTs)))

  private val zeroHash32 = "0x" + "00" * 32
  private val zeroAddr20 = "0x" + "00" * 20
  private val zeroBloom = "0x" + "00" * 256

  /** Minimal well-formed execution payload JSON for the given timestamp. */
  private def payloadJson(timestamp: Long): JObject =
    JObject(
      "parentHash" -> JString(zeroHash32),
      "feeRecipient" -> JString(zeroAddr20),
      "stateRoot" -> JString(zeroHash32),
      "receiptsRoot" -> JString(zeroHash32),
      "logsBloom" -> JString(zeroBloom),
      "prevRandao" -> JString(zeroHash32),
      "blockNumber" -> JString("0x1"),
      "gasLimit" -> JString("0x1c9c380"),
      "gasUsed" -> JString("0x0"),
      "timestamp" -> JString("0x" + timestamp.toHexString),
      "extraData" -> JString("0x"),
      "baseFeePerGas" -> JString("0x3b9aca00"),
      "blockHash" -> JString(zeroHash32),
      "transactions" -> JArray(Nil),
      "withdrawals" -> JArray(Nil),
      "blobGasUsed" -> JString("0x0"),
      "excessBlobGas" -> JString("0x0")
    )

  private def newPayloadV4Request(timestamp: Long, extraFields: (String, JString)*): JsonRpcRequest =
    JsonRpcRequest(
      "2.0",
      "engine_newPayloadV4",
      Some(
        JArray(
          List(
            JObject(payloadJson(timestamp).obj ++ extraFields), // params[0]: execution payload
            JArray(Nil), // params[1]: expectedBlobVersionedHashes
            JString(zeroHash32), // params[2]: parentBeaconBlockRoot
            JArray(Nil) // params[3]: executionRequests
          )
        )
      ),
      Some(JInt(1))
    )

  private def stubService: EngineApiService =
    new EngineApiService(null, null, null, null, None)(null, null):
      override def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] =
        IO.pure(PayloadStatusV1(PayloadStatus.Syncing, latestValidHash = None, validationError = None))

  "engine_newPayloadV4" should {

    "pass the Prague fork gate and reach the service for a Prague-era payload" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(PragueTs)).unsafeRunSync()

      // Fork gate must not fire — error code -38005 must not be present.
      response.error.map(_.code) should not be Some(-38005)
      response.result.isDefined shouldBe true
    }

    "reject a pre-Prague payload with -38005 UNSUPPORTED_FORK" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(PrePragueTs)).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
    }

    "reject a payload at an Amsterdam timestamp with -38005 UNSUPPORTED_FORK (V5 serves Amsterdam)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val controller = new EngineApiController(stubService, None, amsterdamConfig)
      val response = controller.handleRequest(newPayloadV4Request(AmsterdamTs)).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.error.map(_.message).getOrElse("") should include("V5")
      response.result shouldBe None
    }

    "still serve Prague once Amsterdam is scheduled" taggedAs (UnitTest, ConsensusTest) in {
      val controller = new EngineApiController(stubService, None, amsterdamConfig)
      val response = controller.handleRequest(newPayloadV4Request(PragueTs)).unsafeRunSync()

      response.error shouldBe None
      response.result.isDefined shouldBe true
    }

    "reject either ExecutionPayloadV4 field with -32602, before the fork window" taggedAs (UnitTest, ConsensusTest) in {
      val controller = new EngineApiController(stubService, None, amsterdamConfig)
      Seq("slotNumber" -> JString("0x1"), "blockAccessList" -> JString("0xc0")).foreach { field =>
        Seq(PragueTs, AmsterdamTs).foreach { ts =>
          val response = controller.handleRequest(newPayloadV4Request(ts, field)).unsafeRunSync()
          withClue(s"${field._1} at $ts: ") {
            response.error.map(_.code) shouldBe Some(-32602)
            response.error.map(_.message).getOrElse("") should include(field._1)
          }
        }
      }
    }
  }
