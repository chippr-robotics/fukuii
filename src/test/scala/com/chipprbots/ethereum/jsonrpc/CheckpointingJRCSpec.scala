package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.checkpointing.CheckpointingTestHelpers
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.jsonrpc.CheckpointingService._
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config

class CheckpointingJRCSpec
    extends AnyFlatSpec
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience
    with JRCMatchers
    with JsonMethodsImplicits
    with SecureRandomBuilder {

  implicit val runtime: IORuntime = IORuntime.global

  import Req._

  "CheckpointingJRC" should "getLatestBlock" in new TestSetup {
    val request = getLatestBlockRequestBuilder(JArray(JInt(4) :: JNull :: Nil))
    val servResp = GetLatestBlockResponse(Some(BlockInfo(block.hash, block.number)))
    (checkpointingService.getLatestBlock _)
      .expects(GetLatestBlockRequest(4, None))
      .returning(IO.pure(Right(servResp)))

    val expectedResult = JObject(
      "block" -> JObject(
        "hash" -> JString("0x" + ByteStringUtils.hash2string(block.hash)),
        "number" -> JInt(block.number)
      )
    )

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveResult(expectedResult)
  }

  it should "return invalid params when checkpoint parent is of the wrong type" in new TestSetup {
    val request = getLatestBlockRequestBuilder(JArray(JInt(1) :: JBool(true) :: Nil))

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(notSupportedTypeError)
  }

  it should "return invalid params when checkpoint interval is not positive (getLatestBlock)" in new TestSetup {
    val request = getLatestBlockRequestBuilder(JArray(JInt(-1) :: JNull :: Nil))

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(expectedPositiveIntegerError)
  }

  it should "return invalid params when checkpoint interval is too big (getLatestBlock)" in new TestSetup {
    val request = getLatestBlockRequestBuilder(JArray(JInt(BigInt(Int.MaxValue) + 1) :: JNull :: Nil))

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(expectedPositiveIntegerError)
  }

  it should "return invalid params when checkpoint interval is missing (getLatestBlock)" in new TestSetup {
    val request = getLatestBlockRequestBuilder(JArray(Nil))

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(InvalidParams())
  }

  it should "pushCheckpoint" in new TestSetup {
    val request = pushCheckpointRequestBuilder(
      JArray(
        JString(ByteStringUtils.hash2string(block.hash))
          :: JArray(signatures.map(sig => JString(ByteStringUtils.hash2string(sig.toBytes))))
          :: Nil
      )
    )
    val servResp = PushCheckpointResponse()
    val servReq = PushCheckpointRequest(
      block.hash,
      signatures
    )

    (checkpointingService.pushCheckpoint _)
      .expects(servReq)
      .returning(IO.pure(Right(servResp)))

    val expectedResult = JBool(true)

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveResult(expectedResult)
  }

  it should "return invalid params when some arguments are missing (pushCheckpoint)" in new TestSetup {
    val request = pushCheckpointRequestBuilder(
      JArray(JString(ByteStringUtils.hash2string(block.hash)) :: Nil)
    )

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(InvalidParams())
  }

  it should "return invalid params when hash has bad length (pushCheckpoint)" in new TestSetup {
    val badHash = ByteStringUtils.hash2string(block.hash).dropRight(2)
    val request = pushCheckpointRequestBuilder(
      JArray(
        JString(badHash)
          :: JArray(signatures.map(sig => JString(ByteStringUtils.hash2string(sig.toBytes))))
          :: Nil
      )
    )

    val expectedError = InvalidParams(s"Invalid value [$badHash], expected 32 bytes")

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(expectedError)
  }

  it should "return invalid params when hash has bad format (pushCheckpoint)" in new TestSetup {
    val badHash = ByteStringUtils.hash2string(block.hash).replaceAll("0", "X")
    val request = pushCheckpointRequestBuilder(
      JArray(
        JString(badHash)
          :: JArray(signatures.map(sig => JString(ByteStringUtils.hash2string(sig.toBytes))))
          :: Nil
      )
    )

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(InvalidParams())
  }

  it should "return invalid params when signatures are not strings (pushCheckpoint)" in new TestSetup {
    val request = pushCheckpointRequestBuilder(
      JArray(
        JString(ByteStringUtils.hash2string(block.hash))
          :: JArray(signatures.map(_ => JBool(true)))
          :: Nil
      )
    )

    val expectedError = InvalidParams("Unable to extract a signature from: JBool(true)")

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(expectedError)
  }

  it should "return invalid params when signatures have bad format (pushCheckpoint)" in new TestSetup {
    val request = pushCheckpointRequestBuilder(
      JArray(
        JString(ByteStringUtils.hash2string(block.hash))
          :: JArray(signatures.map(sig => JString(ByteStringUtils.hash2string(sig.toBytes).replaceAll("0", "X"))))
          :: Nil
      )
    )

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(InvalidParams())
  }

  it should "return invalid params when signatures have bad length (pushCheckpoint)" in new TestSetup {
    val request = pushCheckpointRequestBuilder(
      JArray(
        JString(ByteStringUtils.hash2string(block.hash))
          :: JArray(signatures.map(sig => JString(ByteStringUtils.hash2string(sig.toBytes).dropRight(2))))
          :: Nil
      )
    )

    val expectedError = InvalidParams("Bad signature length")

    val response = jsonRpcController.handleRequest(request).unsafeRunSync()
    response should haveError(expectedError)
  }

  object Req {
    val block = Fixtures.Blocks.ValidBlock.block

    val keys: Seq[AsymmetricCipherKeyPair] = Seq(
      crypto.generateKeyPair(secureRandom),
      crypto.generateKeyPair(secureRandom)
    )

    val signatures: List[ECDSASignature] = CheckpointingTestHelpers.createCheckpointSignatures(keys, block.hash).toList

    def getLatestBlockRequestBuilder(json: JArray): JsonRpcRequest = JsonRpcRequest(
      "2.0",
      "checkpointing_getLatestBlock",
      Some(json),
      Some(1)
    )

    val expectedPositiveIntegerError: JsonRpcError = InvalidParams("Expected positive integer")
    val notSupportedTypeError: JsonRpcError = InvalidParams("Not supported type for parentCheckpoint")

    def pushCheckpointRequestBuilder(json: JArray): JsonRpcRequest = JsonRpcRequest(
      "2.0",
      "checkpointing_pushCheckpoint",
      Some(json),
      Some(1)
    )
  }

  trait TestSetup extends ApisBuilder {
    def config: JsonRpcConfig = JsonRpcConfig(Config.config, available)

    val web3Service: Web3Service = mock[Web3Service]
    val netService: NetService = mock[NetService]
    val personalService: PersonalService = mock[PersonalService]
    val debugService: DebugService = mock[DebugService]
    val ethService: EthInfoService = mock[EthInfoService]
    val ethMiningService: EthMiningService = mock[EthMiningService]
    val ethBlocksService: EthBlocksService = mock[EthBlocksService]
    val ethTxService: EthTxService = mock[EthTxService]
    val ethUserService: EthUserService = mock[EthUserService]
    val ethFilterService: EthFilterService = mock[EthFilterService]
    val qaService: QAService = mock[QAService]
    val checkpointingService: CheckpointingService = mock[CheckpointingService]
    val mantisService: MantisService = mock[MantisService]

    val jsonRpcController =
      new JsonRpcController(
        web3Service,
        netService,
        ethService,
        ethMiningService,
        ethBlocksService,
        ethTxService,
        ethUserService,
        ethFilterService,
        personalService,
        None,
        debugService,
        qaService,
        checkpointingService,
        mantisService,
        ProofServiceDummy,
        config
      )

  }
}
