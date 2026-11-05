package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.jvalue2monadic
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** Fork-version gating for engine_getPayloadV5 / engine_getPayloadV4 — Osaka boundary.
  *
  * Timestamps used here are far-future sentinels declared in the test application.conf: prague-timestamp = 9999999998
  * osaka-timestamp = 9999999999
  */
class EngineApiGetPayloadV5Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  // Far-future timestamps declared in src/test/resources/application.conf
  private val PragueTs: Long = 9999999998L
  private val OsakaTs: Long = 9999999999L

  private def makeBlock(timestamp: Long): Block =
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
      extraFields = HefPostPrague(
        baseFee = BigInt("1000000000"),
        withdrawalsRoot = BlockHeader.EmptyMpt,
        blobGasUsed = BigInt(0),
        excessBlobGas = BigInt(0),
        parentBeaconBlockRoot = ByteString(new Array[Byte](32)),
        requestsHash = ByteString.empty
      )
    )
    Block(header, BlockBody(Nil, Nil, withdrawals = Some(Nil)))

  private def stubService(block: Block): EngineApiService =
    new EngineApiService(null, null, null, null, None)(using null, null):
      override def getPayload(payloadId: ByteString): IO[Either[String, Block]] =
        IO.pure(Right(block))
      override def getPayloadReceipts(payloadId: ByteString) = Nil
      override def getPayloadBlobsBundle(payloadId: ByteString): BlobsBundleData = BlobsBundleData(Nil, Nil, Nil, Nil)
      override def getPayloadExecutionRequests(payloadId: ByteString) = Nil

  private def getPayloadRequest(method: String): JsonRpcRequest =
    JsonRpcRequest("2.0", method, Some(JArray(List(JString("0x0000000000000001")))), Some(JInt(1)))

  "engine_getPayloadV4" should {

    "reject an Osaka payload with -38005 UNSUPPORTED_FORK" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService(makeBlock(OsakaTs)))
      val response = controller.handleRequest(getPayloadRequest("engine_getPayloadV4")).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
    }
  }

  "engine_getPayloadV5" should {

    "reject a pre-Osaka (Prague) payload with -38005 UNSUPPORTED_FORK" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService(makeBlock(PragueTs)))
      val response = controller.handleRequest(getPayloadRequest("engine_getPayloadV5")).unsafeRunSync()

      response.error.map(_.code) shouldBe Some(-38005)
      response.result shouldBe None
    }

    "return a BlobsBundleV2 envelope for an Osaka payload" taggedAs UnitTest in {
      val controller = new EngineApiController(stubService(makeBlock(OsakaTs)))
      val response = controller.handleRequest(getPayloadRequest("engine_getPayloadV5")).unsafeRunSync()

      response.error.shouldBe(None)
      response.result.isDefined.shouldBe(true)

      val envelope = response.result.get.asInstanceOf[JObject]
      (envelope \ "blobsBundle" \ "commitments").shouldBe(JArray(Nil))
      (envelope \ "blobsBundle" \ "proofs").shouldBe(JArray(Nil))
      (envelope \ "blobsBundle" \ "blobs").shouldBe(JArray(Nil))
      (envelope \ "executionRequests").shouldBe(JArray(Nil))
    }
  }
