package com.chipprbots.ethereum.consensus.engine

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JBool
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.jvalue2monadic
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockAccessList.AccountChanges
import com.chipprbots.ethereum.domain.BlockAccessList.BalanceChange
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** engine_getPayloadV6 (execution-apis amsterdam.md): `{executionPayload: ExecutionPayloadV4, blockValue, blobsBundle:
  * BlobsBundleV2, shouldOverrideBuilder, executionRequests}` for an Amsterdam payload, -38005 for any other.
  *
  * No Amsterdam payload is built yet (#1427), so these specs hand the controller one through a stub service. What they
  * pin is the wire shape that builder will be served through, and that it round-trips: the ExecutionPayloadV4 V6 serves
  * is accepted by engine_newPayloadV5 as the same block.
  */
class EngineApiGetPayloadV6Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val OsakaTs = 9999999999L
  private val AmsterdamTs = 10000000003L
  private val testConfig = Config.blockchains.blockchainConfig
  private val amsterdamConfig: BlockchainConfig =
    testConfig.copy(forkTimestamps = testConfig.forkTimestamps.copy(amsterdamTimestamp = Some(AmsterdamTs)))

  private val beaconRoot = ByteString(Array.fill(32)(0xab.toByte))
  private val request = ByteString(Hex.decode("00" + "11" * 8)) // a deposit request (type 0x00) of 8 bytes
  private val accessList = BlockAccessList(
    Seq(
      AccountChanges(
        Address(ByteString(Array.fill(20)(0x42.toByte))),
        Nil,
        Nil,
        Seq(BalanceChange(1, UInt256(BigInt(5)))),
        Nil,
        Nil
      )
    )
  ).toBytes

  private def sha256(bytes: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(bytes)

  /** An Amsterdam block with no transactions whose header commits to `accessList`, `request` and slot 42: every field
    * engine_newPayloadV5 recomputes (transactions root, withdrawals root, requestsHash, blockAccessListHash) agrees.
    */
  private def amsterdamBlock(timestamp: Long, parent: ByteString = ByteString(Array.fill(32)(0x33.toByte))): Block =
    Block(
      BlockHeader(
        parentHash = BlockHash(parent),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(Array.fill(20)(0x07.toByte)),
        stateRoot = TrieRoot(ByteString(Array.fill(32)(0x44.toByte))),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(9),
        gasLimit = GasAmount(60000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(timestamp),
        extraData = ByteString("fukuii".getBytes),
        mixHash = BlockHash(ByteString(Array.fill(32)(0x55.toByte))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostAmsterdam(
          baseFee = BigInt(7),
          withdrawalsRoot = BlockHeader.EmptyMpt,
          blobGasUsed = BigInt(0),
          excessBlobGas = BigInt(0),
          parentBeaconBlockRoot = beaconRoot,
          requestsHash = ByteString(sha256(sha256(request.toArray))),
          blockAccessListHash = ByteString(kec256(accessList.toArray)),
          slotNumber = BigInt(42)
        )
      ),
      BlockBody(Nil, Nil, withdrawals = Some(Nil))
    )

  private def bytes(values: Int*): ByteString = ByteString(values.map(_.toByte).toArray)
  private val bundle = BlobsBundleData(
    blobs = Seq(bytes(1, 2)),
    commitments = Seq(bytes(3, 4)),
    proofs = Seq(bytes(5, 6)),
    cellProofsPerBlob = Seq(Seq(bytes(7), bytes(8)))
  )

  /** Serves `block` with `accessList`, counting how often it is resolved. */
  private class StubService(block: Block, list: Option[ByteString])
      extends EngineApiService(null, null, null, null, None)(null, null):
    val resolved = new AtomicInteger(0)
    override def getPayload(payloadId: ByteString): IO[Either[String, Block]] = IO.pure(Right(block))
    override def resolvePayload(payloadId: ByteString): IO[Either[String, ServedPayload]] =
      IO(resolved.incrementAndGet()).as(Right(ServedPayload(block, Nil, Seq(request), bundle, list)))

  private def getPayload(version: Int, service: EngineApiService): JsonRpcResponse =
    new EngineApiController(service, None, amsterdamConfig)
      .handleRequest(
        JsonRpcRequest(
          "2.0",
          s"engine_getPayloadV$version",
          Some(JArray(List(JString("0x0000000000000001")))),
          Some(JInt(1))
        )
      )
      .unsafeRunSync()

  private def hex(bytes: ByteString): String = "0x" + Hex.toHexString(bytes.toArray)

  "engine_getPayloadV6" should {

    "serve an Amsterdam payload: ExecutionPayloadV4, blockValue, BlobsBundleV2, shouldOverrideBuilder, requests" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val block = amsterdamBlock(AmsterdamTs)
      val response = getPayload(6, new StubService(block, Some(accessList)))

      response.error shouldBe None
      val envelope = response.result.getOrElse(fail("no result"))
      val payload = envelope \ "executionPayload"
      payload \ "blockAccessList" shouldBe JString(hex(accessList))
      payload \ "slotNumber" shouldBe JString("0x2a")
      payload \ "blockHash" shouldBe JString(hex(block.header.hash.value))
      payload \ "blobGasUsed" shouldBe JString("0x0")
      envelope \ "blockValue" shouldBe JString("0x0")
      envelope \ "shouldOverrideBuilder" shouldBe JBool(false)
      envelope \ "executionRequests" shouldBe JArray(List(JString(hex(request))))
      // BlobsBundleV2: the EIP-7594 cell proofs, not the per-blob proofs.
      envelope \ "blobsBundle" \ "proofs" shouldBe JArray(List(JString("0x07"), JString("0x08")))
      envelope \ "blobsBundle" \ "commitments" shouldBe JArray(List(JString("0x0304")))
    }

    "answer -38005 for a payload built outside Amsterdam, before resolving it" taggedAs (UnitTest, ConsensusTest) in {
      val osaka = amsterdamBlock(OsakaTs)
      val prague = osaka.copy(header =
        osaka.header.copy(extraFields =
          HefPostPrague(
            BigInt(7),
            BlockHeader.EmptyMpt,
            BigInt(0),
            BigInt(0),
            beaconRoot,
            ByteString(sha256(Array.emptyByteArray))
          )
        )
      )
      val service = new StubService(prague, None)
      val response = getPayload(6, service)

      response.error.map(_.code) shouldBe Some(-38005)
      service.resolved.get shouldBe 0
    }

    "refuse (-32603, logged) an Amsterdam payload without its access list, or with one its header does not commit to" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      Seq(None, Some(BlockAccessList.Empty.toBytes)).foreach { list =>
        val response = getPayload(6, new StubService(amsterdamBlock(AmsterdamTs), list))
        withClue(s"accessList=$list") {
          response.error.map(_.code) shouldBe Some(-32603)
          response.result shouldBe None
        }
      }
    }
  }

  "engine_getPayloadV5 once Amsterdam is scheduled" should {

    "answer -38005 for an Amsterdam payload (V6 serves it)" taggedAs (UnitTest, ConsensusTest) in {
      val service = new StubService(amsterdamBlock(AmsterdamTs), Some(accessList))
      val response = getPayload(5, service)

      response.error.map(_.code) shouldBe Some(-38005)
      service.resolved.get shouldBe 0
    }
  }

  "the ExecutionPayloadV4 engine_getPayloadV6 serves" should {

    "be accepted by engine_newPayloadV5 as the same block (header hash reproduced)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EphemBlockchainTestSetup:
      // EEST's Amsterdam schedule: every fork at genesis, so the payload's timestamp is Amsterdam for both calls.
      val config: BlockchainConfig = EestBlockchainReplay
        .configFor(blockchainConfig, "Amsterdam", BigInt(1))
        .fold(err => throw new IllegalStateException(err), identity)
      val block = amsterdamBlock(12)

      val served = new EngineApiController(new StubService(block, Some(accessList)), None, config)
        .handleRequest(
          JsonRpcRequest("2.0", "engine_getPayloadV6", Some(JArray(List(JString("0x0000000000000001")))), Some(JInt(1)))
        )
        .unsafeRunSync()
      val envelope = served.result.getOrElse(fail(s"getPayloadV6 failed: ${served.error}"))

      // A real service with an empty chain: the parent is unknown, so ACCEPTED means the block-hash check passed.
      val service = new EngineApiService(
        blockchainReader,
        blockchainWriter,
        null,
        new ForkChoiceManager(blockchainReader, blockchainWriter),
        None
      )(config, null)
      val params: List[JValue] =
        List(envelope \ "executionPayload", JArray(Nil), JString(hex(beaconRoot)), envelope \ "executionRequests")
      val response = new EngineApiController(service, None, config)
        .handleRequest(JsonRpcRequest("2.0", "engine_newPayloadV5", Some(JArray(params)), Some(JInt(2))))
        .unsafeRunSync()

      response.error shouldBe None
      response.result.map(_ \ "status") shouldBe Some(JString("ACCEPTED"))
      blockchainReader.getBlockHeaderByHash(block.header.hash) shouldBe Some(block.header)
  }

  "engine_exchangeCapabilities" should {

    "advertise engine_newPayloadV5, engine_forkchoiceUpdatedV4 and engine_getPayloadV6" taggedAs UnitTest in {
      val service = new EngineApiService(null, null, null, null, None)(null, null)
      val response = new EngineApiController(service, None, amsterdamConfig)
        .handleRequest(
          JsonRpcRequest("2.0", "engine_exchangeCapabilities", Some(JArray(List(JArray(Nil)))), Some(JInt(1)))
        )
        .unsafeRunSync()
      val advertised = response.result match
        case Some(JArray(items)) => items.collect { case JString(s) => s }
        case other               => fail(s"not a method list: $other")

      (advertised should contain).allOf("engine_newPayloadV5", "engine_forkchoiceUpdatedV4", "engine_getPayloadV6")
    }
  }
