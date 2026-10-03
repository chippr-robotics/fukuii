package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.AccountChanges
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** engine_newPayloadV5 (execution-apis amsterdam.md) and the ExecutionPayloadV4 codec.
  *
  * The version gate follows go-ethereum's `NewPayloadV5` (eth/catalyst/api.go): each field and parameter an Amsterdam
  * call must carry is -32602 when missing or null, THEN a non-Amsterdam timestamp is -38005. A block access list that
  * is present but does not strictly decode — the empty byte string included — is an INVALID payload with
  * latestValidHash null, decided before the block-hash check (go-ethereum `ExecutableDataToBlock`), and the header
  * commits to keccak256 of the list's bytes exactly as sent.
  *
  * The controller cases run against a recording stub on a copy of the test config that declares Amsterdam after its
  * Osaka sentinel. The service cases run a real EngineApiService on an EEST `Amsterdam` schedule, fed a payload from
  * tests@v21.0.0 whose blockHash was computed by execution-specs.
  */
class EngineApiNewPayloadV5Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  // The Prague and Osaka sentinels of src/test/resources/application.conf, and Amsterdam declared on a copy.
  private val PragueTs = 9999999998L
  private val OsakaTs = 9999999999L
  private val AmsterdamTs = 10000000003L
  private val testConfig = Config.blockchains.blockchainConfig
  private val amsterdamConfig: BlockchainConfig =
    testConfig.copy(forkTimestamps = testConfig.forkTimestamps.copy(amsterdamTimestamp = Some(AmsterdamTs)))

  private val zeroHash32 = "0x" + "00" * 32
  private val beaconRoot32 = "0x" + "ab" * 32
  private val zeroAddr20 = "0x" + "00" * 20
  private val zeroBloom = "0x" + "00" * 256

  private val InvalidParams = -32602
  private val UnsupportedFork = -38005

  /** Records the payload that reaches the service; answers SYNCING. */
  private class RecordingService extends EngineApiService(null, null, null, null, None)(null, null):
    val received = new AtomicReference[Option[ExecutionPayload]](None)
    override def newPayload(payload: ExecutionPayload): IO[PayloadStatusV1] =
      IO(received.set(Some(payload))).as(PayloadStatusV1(PayloadStatus.Syncing))

  /** A complete ExecutionPayloadV4 at `timestamp`. */
  private def v4Fields(timestamp: Long): List[(String, JValue)] = List(
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
    "excessBlobGas" -> JString("0x0"),
    "blockAccessList" -> JString("0xc0"),
    "slotNumber" -> JString("0x2a")
  )

  extension (fields: List[(String, JValue)])
    private def without(key: String): List[(String, JValue)] = fields.filterNot(_._1 == key)
    private def updated(key: String, value: JValue): List[(String, JValue)] = fields.without(key) :+ (key -> value)

  /** `[payload, expectedBlobVersionedHashes, parentBeaconBlockRoot, executionRequests]`, each replaceable. */
  private def params(
      payload: List[(String, JValue)],
      versionedHashes: JValue = JArray(Nil),
      beaconRoot: JValue = JString(beaconRoot32),
      requests: JValue = JArray(Nil)
  ): List[JValue] = List(JObject(payload), versionedHashes, beaconRoot, requests)

  private def call(method: String, ps: List[JValue], service: EngineApiService): JsonRpcResponse =
    new EngineApiController(service, None, amsterdamConfig)
      .handleRequest(JsonRpcRequest("2.0", method, Some(JArray(ps)), Some(JInt(1))))
      .unsafeRunSync()

  private def status(response: JsonRpcResponse): Option[String] =
    response.result.collect { case obj: JObject => obj \ "status" }.collect { case JString(s) => s }

  "engine_newPayloadV5" should {

    "hand the service the ExecutionPayloadV4: access-list bytes as sent, slot, beacon root, hashes and requests" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val service = new RecordingService
      val versionedHash = "0x01" + "cd" * 31
      val request = "0x00" + "11" * 4
      val response = call(
        "engine_newPayloadV5",
        params(
          v4Fields(AmsterdamTs).updated("slotNumber", JString("0xffffffffffffffff")),
          versionedHashes = JArray(List(JString(versionedHash))),
          requests = JArray(List(JString(request)))
        ),
        service
      )

      response.error shouldBe None
      status(response) shouldBe Some("SYNCING")
      val payload = service.received.get.getOrElse(fail("the payload never reached the service"))
      payload.blockAccessList shouldBe Some(ByteString(0xc0.toByte))
      payload.slotNumber shouldBe Some((BigInt(1) << 64) - 1)
      payload.parentBeaconBlockRoot shouldBe Some(ByteString(Hex.decode("ab" * 32)))
      payload.expectedBlobVersionedHashes shouldBe Some(Seq(ByteString(Hex.decode(versionedHash.drop(2)))))
      payload.executionRequests shouldBe Some(Seq(ByteString(Hex.decode(request.drop(2)))))
    }

    "answer -38005 for a complete call whose timestamp is not Amsterdam, before reaching the service" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      Seq("Prague" -> PragueTs, "Osaka" -> OsakaTs).foreach { case (fork, ts) =>
        val service = new RecordingService
        val response = call("engine_newPayloadV5", params(v4Fields(ts)), service)
        withClue(fork) {
          response.error.map(_.code) shouldBe Some(UnsupportedFork)
          service.received.get shouldBe None
        }
      }
    }

    "answer -32602 for each missing or null field and parameter, in go-ethereum's order" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val complete = v4Fields(AmsterdamTs)
      // (what is missing, the call without it, the name the error message must carry)
      val cases: Seq[(String, List[JValue], String)] = Seq(
        ("withdrawals", params(complete.without("withdrawals")), "withdrawals"),
        ("excessBlobGas", params(complete.without("excessBlobGas")), "excessBlobGas"),
        ("blobGasUsed", params(complete.without("blobGasUsed")), "blobGasUsed"),
        ("versioned hashes null", params(complete, versionedHashes = JNull), "expectedBlobVersionedHashes"),
        ("versioned hashes absent", List(JObject(complete)), "expectedBlobVersionedHashes"),
        ("beacon root null", params(complete, beaconRoot = JNull), "parentBeaconBlockRoot"),
        ("beacon root not 32 bytes", params(complete, beaconRoot = JString("0x" + "ab" * 31)), "parentBeaconBlockRoot"),
        ("requests null", params(complete, requests = JNull), "executionRequests"),
        ("requests absent", params(complete).take(3), "executionRequests"),
        ("slotNumber absent", params(complete.without("slotNumber")), "slotNumber"),
        ("slotNumber null", params(complete.updated("slotNumber", JNull)), "slotNumber"),
        ("blockAccessList absent", params(complete.without("blockAccessList")), "blockAccessList"),
        ("blockAccessList null", params(complete.updated("blockAccessList", JNull)), "blockAccessList")
      )
      cases.foreach { case (label, ps, named) =>
        val service = new RecordingService
        val response = call("engine_newPayloadV5", ps, service)
        withClue(label) {
          response.error.map(_.code) shouldBe Some(InvalidParams)
          response.error.map(_.message).getOrElse("") should include(named)
          service.received.get shouldBe None
        }
      }
    }

    "answer -32602, not -38005, for a V4-shaped payload sent to V5 before Amsterdam" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // go-ethereum checks the fields first, so a pre-Amsterdam payload without the Amsterdam fields is a params error,
      // as newPayloadV3 answers a pre-Cancun payload without the Cancun fields.
      val service = new RecordingService
      val response =
        call("engine_newPayloadV5", params(v4Fields(OsakaTs).without("slotNumber").without("blockAccessList")), service)
      response.error.map(_.code) shouldBe Some(InvalidParams)
    }

    "answer -32602 for execution requests that are not strictly ascending by type (EIP-7685)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val service = new RecordingService
      val response = call(
        "engine_newPayloadV5",
        params(v4Fields(AmsterdamTs), requests = JArray(List(JString("0x0111"), JString("0x0022")))),
        service
      )
      response.error.map(_.code) shouldBe Some(InvalidParams)
      service.received.get shouldBe None
    }

    "pass an access list that does not decode on to the service, which answers it (not a params error)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      Seq("0x", "0x80", "0xc1").foreach { bal =>
        val service = new RecordingService
        val response =
          call("engine_newPayloadV5", params(v4Fields(AmsterdamTs).updated("blockAccessList", JString(bal))), service)
        withClue(bal) {
          response.error shouldBe None
          service.received.get.flatMap(_.blockAccessList) shouldBe Some(ByteString(Hex.decode(bal.drop(2))))
        }
      }
    }

    "answer a malformed blockAccessList or slotNumber as a malformed payload: INVALID, latestValidHash null" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val malformed = Seq(
        "blockAccessList" -> JString("0xc"), // odd digit count
        "blockAccessList" -> JString("c0"), // no 0x
        "blockAccessList" -> JString("0xzz"),
        "slotNumber" -> JString("0x10000000000000000"), // 2^64
        "slotNumber" -> JString("0x")
      )
      malformed.foreach { case (key, value) =>
        val service = new RecordingService
        val response = call("engine_newPayloadV5", params(v4Fields(AmsterdamTs).updated(key, value)), service)
        withClue(s"$key=$value") {
          response.error shouldBe None
          status(response) shouldBe Some("INVALID")
          response.result.map(_ \ "latestValidHash") shouldBe Some(JNull)
          service.received.get shouldBe None
        }
      }
    }
  }

  // ── The service, through the controller ──────────────────────────────────────────────────────────────────────────

  /** tests@v21.0.0 `for_amsterdam/amsterdam/eip7843_slotnum/slotnum/slotnum_value.json`,
    * `test_slotnum_value[fork_Amsterdam-blockchain_test_engine_from_state_test-slot_max_u64]`, payload 0: one
    * transaction, an 11-account block access list and slotNumber 2^64-1. Its blockHash was computed by execution-specs
    * over the 23-field header, so reproducing it proves the header commits to keccak256 of these list bytes and to the
    * 8-byte slot.
    */
  private val goldenParams: List[JValue] = parse(
    """[{"parentHash":"0x30e043cfedffb4f58ddb5798096af5497873046b83f37426f8fe444f14df9f29",
      |"feeRecipient":"0x2adc25665018aa1fe0e6bc666dac8fc2697ff9ba",
      |"stateRoot":"0x69cae84ae3c89683397ee7fb3283d474b6b8bf420a343002bec29cdfca117b57",
      |"receiptsRoot":"0x3270f2fe25b4f7fbb44babea9eab710b25d45f1496b843c67c5c5effca19aa96",
      |"logsBloom":"0x""".stripMargin + "00" * 256 + """",
      |"blockNumber":"0x1","gasLimit":"0x7270e00","gasUsed":"0x69e1","timestamp":"0x3e8","extraData":"0x00",
      |"prevRandao":"0x0000000000000000000000000000000000000000000000000000000000000000",
      |"baseFeePerGas":"0x7","blobGasUsed":"0x0","excessBlobGas":"0x0",
      |"blockHash":"0xfa161424c7f6d6e134a784bb84b49c4c5cfb0aceac19933c4e19ec516f84c566",
      |"transactions":["0xf861800a8407270e009451fd499265c5a1beeabef07f27d2602a6b127f85808025a0b1cb0bf11823e29a9807d3a37f185b2de18793348a2cd59333994014c219b6cba055045b206156005993a1806f07b59413dcb3a7999d879b20b3e9b43e62c17152"],
      |"withdrawals":[],
      |"blockAccessList":"0xf9015dde9400000961ef480eb55e80d19ad83579a64c007002c0c480010203c0c0c0de94000064d678505ad48f8ccb093bc65613800e8282c0c480010203c0c0c0de940000bbddc7ce488642fb579f8b00f3a590007251c0c480010203c0c0c0de940000bff46984e3725691fa540a8c7589300d8282c0c480010203c0c0c0f840940000f90827f1c53a10cb7a02335b175320002935e6e580e3e280a030e043cfedffb4f58ddb5798096af5497873046b83f37426f8fe444f14df9f29c0c0c0c0e794000f3df6d732807ef1319fb7b8bb8522d0beac02cac98203e8c5c4808203e8c38223e7c0c0c0e0942adc25665018aa1fe0e6bc666dac8fc2697ff9bac0c0c6c50183013da3c0c0e89451fd499265c5a1beeabef07f27d2602a6b127f85cecd80cbca0188ffffffffffffffffc0c0c0c0ec94f6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ffc0c0cfce018c033b2e3c9fd0803ce7fbdd36c3c20101c0",
      |"slotNumber":"0xffffffffffffffff"},
      |[],"0x0000000000000000000000000000000000000000000000000000000000000000",[]]""".stripMargin
  ) match
    case JArray(items) => items
    case other         => throw new IllegalStateException(s"golden params are not an array: $other")

  private val goldenBlockHash = ByteString(
    Hex.decode("fa161424c7f6d6e134a784bb84b49c4c5cfb0aceac19933c4e19ec516f84c566")
  )
  private val goldenBal = ByteString(
    Hex.decode(
      (goldenParams.head \ "blockAccessList") match
        case JString(s) => s.drop(2)
        case other      => throw new IllegalStateException(s"no golden blockAccessList: $other")
    )
  )

  /** A real EngineApiService on EEST's `Amsterdam` schedule (every fork at genesis), with an empty chain: the golden
    * payload's parent is unknown, so a payload that passes the envelope checks is stored by hash and ACCEPTED without
    * execution.
    */
  private class Fixture extends EphemBlockchainTestSetup:
    val config: BlockchainConfig = EestBlockchainReplay
      .configFor(blockchainConfig, "Amsterdam", BigInt(1))
      .fold(err => throw new IllegalStateException(err), identity)
    val service = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      null, // never reached: an unknown parent is not executed
      new ForkChoiceManager(blockchainReader, blockchainWriter),
      None
    )(config, null)
    def newPayloadV5(ps: List[JValue]): JsonRpcResponse =
      new EngineApiController(service, None, config)
        .handleRequest(JsonRpcRequest("2.0", "engine_newPayloadV5", Some(JArray(ps)), Some(JInt(1))))
        .unsafeRunSync()

  private def withBal(ps: List[JValue], bal: ByteString): List[JValue] = ps match
    case JObject(fields) :: rest =>
      JObject(
        fields.filterNot(_._1 == "blockAccessList") :+ ("blockAccessList" -> JString(
          "0x" + Hex.toHexString(bal.toArray)
        ))
      ) :: rest
    case other => throw new IllegalStateException(s"not a newPayload params list: $other")

  "EngineApiService.newPayload for an Amsterdam payload" should {

    "reproduce execution-specs' block hash: the header commits to keccak256 of the list bytes and to slot 2^64-1" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture:
      val response = newPayloadV5(goldenParams)

      response.error shouldBe None
      // ACCEPTED, not INVALID "block hash mismatch": the parent is unknown, so the hash check is all that ran.
      status(response) shouldBe Some("ACCEPTED")
      val header = blockchainReader
        .getBlockHeaderByHash(BlockHash(goldenBlockHash))
        .getOrElse(fail("the block was not stored under the payload's blockHash"))
      header.blockAccessListHash shouldBe Some(ByteString(kec256(goldenBal.toArray)))
      header.slotNumber shouldBe Some((BigInt(1) << 64) - 1)

    "answer INVALID, latestValidHash null, when the list decodes but the header committed to other bytes" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture:
      // EEST bal_invalid_hash_mismatch: a well-formed list that is not the one the block hash commits to.
      val response = newPayloadV5(withBal(goldenParams, BlockAccessList.Empty.toBytes))

      status(response) shouldBe Some("INVALID")
      response.result.map(_ \ "latestValidHash") shouldBe Some(JNull)
      response.result.map(_ \ "validationError") shouldBe Some(JString("block hash mismatch"))
      blockchainReader.getBlockHeaderByHash(BlockHash(goldenBlockHash)) shouldBe None

    "answer INVALID, latestValidHash null, for a list that does not strictly decode, before the block-hash check" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture:
      val low = Address(ByteString(Array.fill(20)(0x01.toByte)))
      val high = Address(ByteString(Array.fill(20)(0x02.toByte)))
      def account(a: Address) = AccountChanges(a, Nil, Nil, Nil, Nil, Nil)
      // A balance change whose value carries a leading zero byte: RLP-valid, not the canonical integer.
      val nonMinimalBalance = ByteString(
        rlp.encode(
          RLPList(
            RLPList(
              RLPValue(low.toArray),
              RLPList(),
              RLPList(),
              RLPList(RLPList(RLPValue(Array[Byte](1)), RLPValue(Array[Byte](0, 1)))),
              RLPList(),
              RLPList()
            )
          )
        )
      )
      val undecodable: Seq[(String, ByteString)] = Seq(
        "empty byte string" -> ByteString.empty,
        "rlp non-list (0x80)" -> ByteString(0x80.toByte),
        "truncated list (0xc1)" -> ByteString(0xc1.toByte),
        "accounts out of order" -> BlockAccessList(Seq(account(high), account(low))).toBytes,
        "duplicate account" -> BlockAccessList(Seq(account(low), account(low))).toBytes,
        "non-minimal balance" -> nonMinimalBalance,
        "canonical list plus a trailing byte" -> (goldenBal ++ ByteString(0x00.toByte))
      )
      undecodable.foreach { case (label, bal) =>
        val response = newPayloadV5(withBal(goldenParams, bal))
        withClue(label) {
          response.error shouldBe None
          status(response) shouldBe Some("INVALID")
          response.result.map(_ \ "latestValidHash") shouldBe Some(JNull)
          response.result.map(_ \ "validationError") match
            case Some(JString(msg)) => msg should startWith("INVALID_BLOCK_ACCESS_LIST")
            case other              => fail(s"validationError: $other")
        }
      }
      // An envelope defect: nothing stored, nothing recorded as invalid (the block hash is not trustworthy).
      blockchainReader.getBlockHeaderByHash(BlockHash(goldenBlockHash)) shouldBe None
      service.invalidBlocksSnapshot shouldBe empty
  }
