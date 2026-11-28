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
  * V2 is all or nothing (execution-apis osaka.md): one missing blob makes the whole result null, never a null element
  * (B-198: Lighthouse rejects a null inside the array). Fukuii does not index pool blobs by versioned hash, so a
  * non-empty request is answered null and the CL fetches the cells from its peers. The suite also verifies that
  * engine_getBlobsV2 is advertised in exchangeCapabilities.
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

    "answer null for the whole response when a blob is missing, not a null per hash (B-198)" taggedAs UnitTest in {
      val hashes = List(
        "0x01" + "00" * 31,
        "0x01" + "ff" * 31
      )
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV2", hashes)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JNull)
    }

    "return an empty array for an empty hash list" taggedAs UnitTest in {
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV2", Nil)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JArray(Nil))
    }

    "answer null, not [null], for a single requested hash" taggedAs UnitTest in {
      val hashes = List("0x01" + "ab" * 31)
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV2", hashes)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JNull)
    }

    "keep V1's per-hash nulls on engine_getBlobsV1" taggedAs UnitTest in {
      val hashes = List("0x01" + "00" * 31, "0x01" + "ff" * 31)
      val response = controller.handleRequest(blobsRequest("engine_getBlobsV1", hashes)).unsafeRunSync()

      response.error shouldBe None
      response.result shouldBe Some(JArray(List(JNull, JNull)))
    }
  }

  "engine_getBlobsV1 and V2" should {

    "refuse more than 128 hashes with -38004, as go-ethereum does (B-477)" taggedAs UnitTest in {
      val hashes = (0 until 129).map(i => "0x01" + f"$i%064x".drop(2)).toList
      for method <- Seq("engine_getBlobsV1", "engine_getBlobsV2") do
        val response = controller.handleRequest(blobsRequest(method, hashes)).unsafeRunSync()
        response.result shouldBe None
        response.error.map(_.code) shouldBe Some(-38004)
    }

    "serve exactly 128 hashes" taggedAs UnitTest in {
      val hashes = (0 until 128).map(i => "0x01" + f"$i%064x".drop(2)).toList
      controller.handleRequest(blobsRequest("engine_getBlobsV2", hashes)).unsafeRunSync().error shouldBe None
    }

    "refuse params that are not a list of hashes with -32602, not answer []" taggedAs UnitTest in {
      val notAList = JsonRpcRequest("2.0", "engine_getBlobsV2", Some(JArray(List(JString("0x01")))), Some(JInt(1)))
      val noParams = JsonRpcRequest("2.0", "engine_getBlobsV1", None, Some(JInt(1)))
      for request <- Seq(notAList, noParams) do
        controller.handleRequest(request).unsafeRunSync().error.map(_.code) shouldBe Some(-32602)
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
