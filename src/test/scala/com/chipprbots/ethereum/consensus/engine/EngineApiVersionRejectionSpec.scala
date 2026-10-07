package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** Version-mismatch rejection guards for the Engine API controller (T7-C).
  *
  * Five guards in EngineApiController fire at the RPC boundary — before any service call — and return -38005
  * (UNSUPPORTED_FORK) or -32602 (INVALID_PARAMS). Without unit tests, a guard regression lets a misconfigured CL push
  * wrong-version payloads silently. Timestamps used here are declared as far-future sentinels in
  * src/test/resources/application.conf.
  */
class EngineApiVersionRejectionSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  // Far-future timestamps matching src/test/resources/application.conf sentinels.
  // Order: shanghai (94) < cancun (95) < prague (98) < osaka (99) — all safely above
  // real-world test timestamps (~1701302272 max) and independently spaced.
  private val ShanghaiTs: Long = 9999999994L // shanghai but not cancun
  private val CancunTs: Long = 9999999995L // cancun but not prague
  private val PreForkTs: Long = 1001L // below all timestamp-fork thresholds

  private val zeroHash32 = "0x" + "00" * 32
  private val zeroAddr20 = "0x" + "00" * 20
  private val zeroBloom = "0x" + "00" * 256
  private val nonZeroHash32 = "0x" + "ab" * 32

  private def stubService: EngineApiService =
    new EngineApiService(null, null, null, null, None)(null, null) {}

  private def stubServiceWithBlock(block: Block): EngineApiService =
    new EngineApiService(null, null, null, null, None)(null, null):
      override def getPayload(payloadId: ByteString): IO[Either[String, Block]] =
        IO.pure(Right(block))

  private def makeBlock(timestamp: Long, hef: BlockHeader.HeaderExtraFields): Block =
    val header = BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(ByteString(new Array[Byte](32))),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(1),
      gasLimit = GasAmount(30000000),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(timestamp),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = hef
    )
    Block(header, BlockBody(Nil, Nil, withdrawals = Some(Nil)))

  private val shanghaiBlock: Block = makeBlock(
    ShanghaiTs,
    HefPostShanghai(baseFee = BigInt("1000000000"), withdrawalsRoot = BlockHeader.EmptyMpt)
  )

  private val cancunBlock: Block = makeBlock(
    CancunTs,
    HefPostCancun(
      baseFee = BigInt("1000000000"),
      withdrawalsRoot = BlockHeader.EmptyMpt,
      blobGasUsed = BigInt(0),
      excessBlobGas = BigInt(0),
      parentBeaconBlockRoot = ByteString(new Array[Byte](32))
    )
  )

  private def getPayloadRequest(method: String): JsonRpcRequest =
    JsonRpcRequest("2.0", method, Some(JArray(List(JString("0x0000000000000001")))), Some(JInt(1)))

  "Engine API version-mismatch rejection guards" should {

    // Guard: getPayloadV2 must not serve a Cancun payload; the CL must use V3.
    "return -38005 when getPayloadV2 is called for a Cancun-era block" taggedAs (UnitTest, ConsensusTest) in {
      val controller = new EngineApiController(stubServiceWithBlock(cancunBlock))
      val response = controller.handleRequest(getPayloadRequest("engine_getPayloadV2")).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
      response.error.map(_.message).exists(_.contains("Cancun")) shouldBe true
    }

    // Guard: getPayloadV3 can only serve Cancun-or-later payloads; V2 is required pre-Cancun.
    "return -38005 when getPayloadV3 is called for a pre-Cancun (Shanghai-era) block" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val controller = new EngineApiController(stubServiceWithBlock(shanghaiBlock))
      val response = controller.handleRequest(getPayloadRequest("engine_getPayloadV3")).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
      response.error.map(_.message).exists(_.contains("Cancun")) shouldBe true
    }

    // Guard: getPayloadV1 must not serve a Shanghai-or-later payload; the CL must use V2.
    "return -38005 when getPayloadV1 is called for a Shanghai-era block" taggedAs (UnitTest, ConsensusTest) in {
      val controller = new EngineApiController(stubServiceWithBlock(shanghaiBlock))
      val response = controller.handleRequest(getPayloadRequest("engine_getPayloadV1")).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
      response.error.map(_.message).exists(_.contains("Shanghai")) shouldBe true
    }

    // Guard: newPayloadV3 with a V2-shape payload (no blob fields) before Cancun → InvalidParams.
    // The CL is calling the wrong version for the fork; it should use V2 instead.
    "return -32602 when newPayloadV3 is called with a pre-Cancun payload (no blob fields)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val preCancunPayload = JObject(
        "parentHash" -> JString(zeroHash32),
        "feeRecipient" -> JString(zeroAddr20),
        "stateRoot" -> JString(zeroHash32),
        "receiptsRoot" -> JString(zeroHash32),
        "logsBloom" -> JString(zeroBloom),
        "prevRandao" -> JString(zeroHash32),
        "blockNumber" -> JString("0x1"),
        "gasLimit" -> JString("0x1c9c380"),
        "gasUsed" -> JString("0x0"),
        "timestamp" -> JString("0x" + PreForkTs.toHexString),
        "extraData" -> JString("0x"),
        "baseFeePerGas" -> JString("0x3b9aca00"),
        "blockHash" -> JString(zeroHash32),
        "transactions" -> JArray(Nil),
        "withdrawals" -> JArray(Nil)
        // intentionally absent: blobGasUsed, excessBlobGas
      )
      val request = JsonRpcRequest(
        "2.0",
        "engine_newPayloadV3",
        Some(JArray(List(preCancunPayload, JArray(Nil), JString(zeroHash32)))),
        Some(JInt(1))
      )
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(request).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-32602)
      response.result shouldBe None
    }

    // Guard: newPayloadV2 for a Shanghai (pre-Cancun) payload that carries EIP-4844 header fields is a params error
    // (go-ethereum NewPayloadV2), not an INVALID block. EEST cancun/eip4844_blobs/test_excess_blob_gas_fork_transition
    // ::test_invalid_pre_fork_block_with_blob_fields[ShanghaiToCancunAtTime15k] — all three field combinations.
    "return -32602 when newPayloadV2 carries blobGasUsed and/or excessBlobGas before Cancun" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      def shanghaiPayload(blobFields: (String, JString)*): JObject = JObject(
        List(
          "parentHash" -> JString(zeroHash32),
          "feeRecipient" -> JString(zeroAddr20),
          "stateRoot" -> JString(zeroHash32),
          "receiptsRoot" -> JString(zeroHash32),
          "logsBloom" -> JString(zeroBloom),
          "prevRandao" -> JString(zeroHash32),
          "blockNumber" -> JString("0x1"),
          "gasLimit" -> JString("0x1c9c380"),
          "gasUsed" -> JString("0x0"),
          "timestamp" -> JString("0x" + ShanghaiTs.toHexString),
          "extraData" -> JString("0x"),
          "baseFeePerGas" -> JString("0x3b9aca00"),
          "blockHash" -> JString(zeroHash32),
          "transactions" -> JArray(Nil),
          "withdrawals" -> JArray(Nil)
        ) ++ blobFields*
      )
      val variants = Seq(
        Seq("blobGasUsed" -> JString("0x0")),
        Seq("excessBlobGas" -> JString("0x0")),
        Seq("blobGasUsed" -> JString("0x0"), "excessBlobGas" -> JString("0x0"))
      )
      val controller = new EngineApiController(stubService)
      variants.foreach { fields =>
        val request =
          JsonRpcRequest("2.0", "engine_newPayloadV2", Some(JArray(List(shanghaiPayload(fields*)))), Some(JInt(1)))
        val response = controller.handleRequest(request).unsafeRunSync()
        withClue(fields.map(_._1).mkString("+")) {
          response.error.map(_.code) shouldBe Some(-32602)
          response.result shouldBe None
        }
      }
    }

    // Guard: forkchoiceUpdatedV3 with a non-zero parentBeaconBlockRoot before Cancun activation
    // is an UNSUPPORTED_FORK — the CL sent a Cancun-shaped attribute to a pre-Cancun chain.
    "return -38005 when forkchoiceUpdatedV3 carries parentBeaconBlockRoot before Cancun" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val fcs = JObject(
        "headBlockHash" -> JString(zeroHash32),
        "safeBlockHash" -> JString(zeroHash32),
        "finalizedBlockHash" -> JString(zeroHash32)
      )
      val attrs = JObject(
        "timestamp" -> JString("0x" + PreForkTs.toHexString),
        "prevRandao" -> JString(zeroHash32),
        "suggestedFeeRecipient" -> JString(zeroAddr20),
        "withdrawals" -> JArray(Nil),
        "parentBeaconBlockRoot" -> JString(nonZeroHash32)
      )
      val request = JsonRpcRequest(
        "2.0",
        "engine_forkchoiceUpdatedV3",
        Some(JArray(List(fcs, attrs))),
        Some(JInt(1))
      )
      val controller = new EngineApiController(stubService)
      val response = controller.handleRequest(request).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
      response.error.map(_.message).exists(_.contains("Cancun")) shouldBe true
    }
  }
