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
/** validateRequests boundary checks at engine_newPayloadV4 (T10-D).
  *
  * go-ethereum catalyst/api.go:1257 rejects executionRequests entries that are:
  *   - too short (< 2 bytes — missing type prefix byte)
  *   - out of strict-ascending type-byte order (duplicate types included)
  *
  * These checks return -32602 (InvalidParams) at the RPC boundary, not a PayloadStatus INVALID. Only active for Prague+
  * timestamps.
  */
class EngineApiValidateRequestsSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val PragueTs: Long = 9999999998L
  private val PrePragueTs: Long = 9999999997L

  private val zeroHash32 = "0x" + "00" * 32
  private val zeroAddr20 = "0x" + "00" * 20
  private val zeroBloom = "0x" + "00" * 256

  private def praguePayloadJson: JObject =
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
      "timestamp" -> JString("0x" + PragueTs.toHexString),
      "extraData" -> JString("0x"),
      "baseFeePerGas" -> JString("0x3b9aca00"),
      "blockHash" -> JString(zeroHash32),
      "transactions" -> JArray(Nil),
      "withdrawals" -> JArray(Nil),
      "blobGasUsed" -> JString("0x0"),
      "excessBlobGas" -> JString("0x0")
    )

  private def newPayloadV4Request(executionRequests: List[String]): JsonRpcRequest =
    JsonRpcRequest(
      "2.0",
      "engine_newPayloadV4",
      Some(
        JArray(
          List(
            praguePayloadJson,
            JArray(Nil), // expectedBlobVersionedHashes
            JString(zeroHash32), // parentBeaconBlockRoot
            JArray(executionRequests.map(JString(_)))
          )
        )
      ),
      Some(JInt(1))
    )

  private def stubService: EngineApiService =
    new EngineApiService(null, null, null, null, None)(using null, null):
      override def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] =
        IO.pure(PayloadStatusV1(PayloadStatus.Syncing, latestValidHash = None, validationError = None))

  "engine_newPayloadV4 validateRequests" should {

    "return -32602 for an empty entry (0 bytes — no type prefix)" taggedAs UnitTest in {
      // "0x" decodes to an empty ByteString (length 0), which is < 2 bytes
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(List("0x"))).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-32602)
      response.result shouldBe None
      response.error.map(_.message).exists(_.contains("empty request")) shouldBe true
    }

    "return -32602 for duplicate type bytes (non-strictly-ascending order)" taggedAs UnitTest in {
      // 0x00aa and 0x00bb: both have type byte 0x00 → duplicate type, not strictly ascending
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(List("0x00aa", "0x00bb"))).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-32602)
      response.result shouldBe None
      response.error.map(_.message).exists(_.contains("invalid request order")) shouldBe true
    }

    "return -32602 for out-of-order type bytes (descending instead of ascending)" taggedAs UnitTest in {
      // type bytes 0x01 then 0x00 → out of order
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(List("0x01aa", "0x00bb"))).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-32602)
      response.result shouldBe None
    }

    "pass through to service for correctly ascending type bytes" taggedAs UnitTest in {
      // type bytes 0x00, 0x01, 0x02 — strictly ascending with data bytes present
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(List("0x00aa", "0x01bb", "0x02cc"))).unsafeRunSync()

      // No RPC-level error — validateRequests passes, control reaches the stub service
      response.error shouldBe None
      response.result.isDefined shouldBe true
    }

    "pass through for empty executionRequests list (no entries to validate)" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(newPayloadV4Request(Nil)).unsafeRunSync()

      response.error shouldBe None
      response.result.isDefined shouldBe true
    }

    "not apply validateRequests for pre-Prague timestamps (V4 already rejected by fork gate)" taggedAs UnitTest in {
      // Pre-Prague V4 is rejected with -38005 (UnsupportedFork), not -32602 from validateRequests.
      // This confirms validateRequests is never invoked for non-Prague timestamps.
      val request = JsonRpcRequest(
        "2.0",
        "engine_newPayloadV4",
        Some(
          JArray(
            List(
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
                "timestamp" -> JString("0x" + PrePragueTs.toHexString),
                "extraData" -> JString("0x"),
                "baseFeePerGas" -> JString("0x3b9aca00"),
                "blockHash" -> JString(zeroHash32),
                "transactions" -> JArray(Nil),
                "withdrawals" -> JArray(Nil),
                "blobGasUsed" -> JString("0x0"),
                "excessBlobGas" -> JString("0x0")
              ),
              JArray(Nil),
              JString(zeroHash32),
              JArray(List(JString("0x"))) // malformed: would trigger validateRequests if Prague
            )
          )
        ),
        Some(JInt(1))
      )
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(request).unsafeRunSync()

      // Must be the fork gate error (-38005), not the validateRequests error (-32602)
      response.error.map(_.code) shouldBe Some(-38005)
    }
  }
