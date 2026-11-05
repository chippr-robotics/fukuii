package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** engine_getBlobsV2 — EIP-7594 / PeerDAS cell-proof blob serving (T10-B).
  *
  * Fukuii does not index mempool blobs by versioned hash, so null is returned per entry. The CL falls back to CL-peer
  * gossip for missing blobs. The suite also verifies that engine_getBlobsV2 is advertised in exchangeCapabilities.
  */
class EngineApiGetBlobsV2Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val stubService: EngineApiService =
    new EngineApiService(null, null, null, null, None)(using null, null):
      override def getPayload(payloadId: ByteString): IO[Either[String, com.chipprbots.ethereum.domain.Block]] =
        IO.pure(Left("stub"))
      override def getPayloadReceipts(payloadId: ByteString) = Nil
      override def getPayloadBlobsBundle(payloadId: ByteString): BlobsBundleData = BlobsBundleData(Nil, Nil, Nil, Nil)
      override def getPayloadExecutionRequests(payloadId: ByteString) = Nil

  private def controller: EngineApiController = new EngineApiController(stubService)

  private def blobsRequest(method: String, hashes: List[String]): JsonRpcRequest =
    JsonRpcRequest(
      "2.0",
      method,
      Some(JArray(List(JArray(hashes.map(JString(_)))))),
      Some(JInt(1))
    )

  private def capabilitiesRequest: JsonRpcRequest =
    JsonRpcRequest("2.0", "engine_exchangeCapabilities", Some(JArray(List(JArray(Nil)))), Some(JInt(1)))

  "engine_getBlobsV2" should {

    "return null for each requested versioned hash (no mempool blob store by hash)" taggedAs UnitTest in {
      val hashes = List(
        "0x01" + "00" * 31,
        "0x01" + "ff" * 31
      )
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV2", hashes)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JArray(List(JNull, JNull)))
    }

    "return an empty array for an empty hash list" taggedAs UnitTest in {
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV2", Nil)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JArray(Nil))
    }

    "return a single null for a single requested hash" taggedAs UnitTest in {
      val hashes = List("0x01" + "ab" * 31)
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV2", hashes)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JArray(List(JNull)))
    }
  }

  "engine_exchangeCapabilities" should {

    "advertise engine_getBlobsV2 alongside engine_getBlobsV1" taggedAs UnitTest in {
      val response = controller.handleRequest(capabilitiesRequest).unsafeRunSync()

      response.error shouldBe None
      val capabilities = response.result.get.asInstanceOf[JArray].arr.collect { case JString(s) => s }
      capabilities should contain("engine_getBlobsV1")
      capabilities should contain("engine_getBlobsV2")
    }
  }
