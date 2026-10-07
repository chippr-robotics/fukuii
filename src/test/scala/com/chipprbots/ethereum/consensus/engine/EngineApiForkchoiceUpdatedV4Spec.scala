package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.ConcurrentLinkedQueue

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.jdk.CollectionConverters.*

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.jvalue2monadic
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** engine_forkchoiceUpdatedV4 (execution-apis amsterdam.md): `[ForkchoiceStateV1, PayloadAttributesV4|null,
  * custodyColumns|null]`.
  *
  *   - Attributes, in go-ethereum's `ForkchoiceUpdatedV4` order: withdrawals, beacon root, slotNumber missing -> -38003
  *     (the forkchoice state is still applied first); a timestamp outside Amsterdam -> -38005 (nothing applied).
  *     `targetGasLimit` is optional, as in go-ethereum.
  *   - `custodyColumns`: absent or null, or 16-byte DATA; anything else -32602 with nothing applied. A valid value
  *     never reaches the fork choice.
  *   - Without attributes V4 works at every fork: Lighthouse sends every attribute-less forkchoice update through the
  *     highest version the EL advertises, long before Amsterdam.
  *   - An Amsterdam build needs the slot its header carries (EIP-7843): the service refuses attributes without one
  *     (-38003, after the forkchoice state is applied), as go-ethereum's miner does, and so does buildBlockOnParent.
  *     The payload ID covers slotNumber and targetGasLimit. Amsterdam builds themselves: EngineApiAmsterdamBuildSpec.
  */
class EngineApiForkchoiceUpdatedV4Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  // The Osaka sentinel of src/test/resources/application.conf, and Amsterdam declared on a copy.
  private val OsakaTs = 9999999999L
  private val AmsterdamTs = 10000000003L
  private val testConfig = Config.blockchains.blockchainConfig
  private val amsterdamConfig: BlockchainConfig =
    testConfig.copy(forkTimestamps = testConfig.forkTimestamps.copy(amsterdamTimestamp = Some(AmsterdamTs)))

  private val zeroHash32 = "0x" + "00" * 32
  private val headHash32 = "0x" + "11" * 32
  private val beaconRoot32 = "0x" + "ab" * 32
  private val zeroAddr20 = "0x" + "00" * 20

  private val InvalidParams = -32602
  private val InvalidAttributes = -38003
  private val UnsupportedFork = -38005

  /** Records what reaches the service; answers VALID, with a payload ID when there are attributes. */
  private class RecordingService extends EngineApiService(null, null, null, null, None)(null, null):
    val calls = new ConcurrentLinkedQueue[(ForkChoiceState, Option[PayloadAttributes])]()
    override def forkchoiceUpdated(
        forkChoiceState: ForkChoiceState,
        payloadAttributes: Option[PayloadAttributes]
    ): IO[Either[String, ForkchoiceUpdatedResponse]] =
      calls.add((forkChoiceState, payloadAttributes))
      IO.pure(
        Right(
          ForkchoiceUpdatedResponse(
            PayloadStatusV1(PayloadStatus.Valid, latestValidHash = Some(forkChoiceState.headBlockHash)),
            payloadId = payloadAttributes.map(_ => ByteString(Array.fill(8)(0x01.toByte)))
          )
        )
      )

  private def fcsJson(head: String = headHash32): JObject = JObject(
    "headBlockHash" -> JString(head),
    "safeBlockHash" -> JString(zeroHash32),
    "finalizedBlockHash" -> JString(zeroHash32)
  )

  private def attrsJson(
      ts: Long,
      withdrawals: Boolean = true,
      beaconRoot: Boolean = true,
      slotNumber: Option[JValue] = Some(JString("0x2a")),
      targetGasLimit: Option[JValue] = Some(JString("0x3938700"))
  ): JObject =
    JObject(
      List[(String, JValue)](
        "timestamp" -> JString("0x" + ts.toHexString),
        "prevRandao" -> JString(zeroHash32),
        "suggestedFeeRecipient" -> JString(zeroAddr20)
      ) ++ Option.when(withdrawals)("withdrawals" -> JArray(Nil))
        ++ Option.when(beaconRoot)("parentBeaconBlockRoot" -> JString(beaconRoot32))
        ++ slotNumber.map("slotNumber" -> _)
        ++ targetGasLimit.map("targetGasLimit" -> _)
    )

  private def call(version: Int, params: List[JValue], service: EngineApiService): JsonRpcResponse =
    new EngineApiController(service, None, amsterdamConfig)
      .handleRequest(
        JsonRpcRequest("2.0", s"engine_forkchoiceUpdatedV$version", Some(JArray(params)), Some(JInt(1)))
      )
      .unsafeRunSync()

  private def payloadStatus(response: JsonRpcResponse): Option[JValue] =
    response.result.map(_ \ "payloadStatus" \ "status")

  "engine_forkchoiceUpdatedV4" should {

    "apply a forkchoice state without attributes at any fork, however custodyColumns is sent" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val forms: Seq[(String, List[JValue])] = Seq(
        "state only" -> List(fcsJson()),
        "null attributes" -> List(fcsJson(), JNull),
        "null attributes, null custody" -> List(fcsJson(), JNull, JNull),
        "null attributes, custody of all 128 columns" -> List(fcsJson(), JNull, JString("0x" + "ff" * 16)),
        "null attributes, custody in upper-case hex" -> List(fcsJson(), JNull, JString("0X" + "0F" * 16))
      )
      forms.foreach { case (label, params) =>
        val service = new RecordingService
        val response = call(4, params, service)
        withClue(label) {
          response.error shouldBe None
          payloadStatus(response) shouldBe Some(JString("VALID"))
          service.calls.asScala.toSeq.map(_._2) shouldBe Seq(None)
        }
      }
    }

    "answer -32602 for a custodyColumns that is not 16 bytes of DATA, applying nothing" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val malformed: Seq[JValue] = Seq(
        JString("0x" + "ff" * 15),
        JString("0x" + "ff" * 17),
        JString("ff" * 16), // no 0x
        JString("0x" + "zz" * 16),
        JString("0x"),
        JInt(5),
        JArray(Nil),
        JObject()
      )
      malformed.foreach { custody =>
        Seq(JNull, attrsJson(AmsterdamTs)).foreach { attrs =>
          val service = new RecordingService
          val response = call(4, List(fcsJson(), attrs, custody), service)
          withClue(s"custodyColumns=$custody, attributes=$attrs") {
            response.error.map(_.code) shouldBe Some(InvalidParams)
            service.calls shouldBe empty
          }
        }
      }
    }

    "hand the service the PayloadAttributesV4, slotNumber and targetGasLimit included" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val service = new RecordingService
      val response = call(
        4,
        List(
          fcsJson(),
          attrsJson(AmsterdamTs, slotNumber = Some(JString("0xffffffffffffffff"))),
          JString("0x" + "01" * 16)
        ),
        service
      )

      response.error shouldBe None
      response.result.map(_ \ "payloadId") shouldBe Some(JString("0x0101010101010101"))
      val attrs = service.calls.asScala.toSeq match
        case Seq((_, Some(a))) => a
        case other             => fail(s"expected one call with attributes, got $other")
      attrs.slotNumber shouldBe Some((BigInt(1) << 64) - 1)
      attrs.targetGasLimit shouldBe Some(BigInt(60000000))
      attrs.parentBeaconBlockRoot shouldBe Some(ByteString(Hex.decode("ab" * 32)))
    }

    "accept attributes without targetGasLimit, as go-ethereum does" taggedAs (UnitTest, ConsensusTest) in {
      Seq(None, Some(JNull)).foreach { target =>
        val service = new RecordingService
        val response = call(4, List(fcsJson(), attrsJson(AmsterdamTs, targetGasLimit = target)), service)
        withClue(s"targetGasLimit=$target") {
          response.error shouldBe None
          service.calls.asScala.toSeq.map(_._2.flatMap(_.targetGasLimit)) shouldBe Seq(None)
        }
      }
    }

    "answer -38003 for attributes missing withdrawals, beacon root or slotNumber, after applying the state" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val cases: Seq[(String, JObject)] = Seq(
        "withdrawals" -> attrsJson(AmsterdamTs, withdrawals = false),
        "beacon root" -> attrsJson(AmsterdamTs, beaconRoot = false),
        "slot number" -> attrsJson(AmsterdamTs, slotNumber = None),
        "slot number (null)" -> attrsJson(AmsterdamTs, slotNumber = Some(JNull)),
        // The structure is checked before the fork, as in go-ethereum.
        "slot number, pre-Amsterdam" -> attrsJson(OsakaTs, slotNumber = None)
      )
      cases.foreach { case (missing, attrs) =>
        val service = new RecordingService
        val response = call(4, List(fcsJson(), attrs), service)
        withClue(missing) {
          response.error.map(_.code) shouldBe Some(InvalidAttributes)
          response.error.map(_.message).getOrElse("") should include(missing.takeWhile(_ != ' '))
          // Applied first, without the attributes (paris.md forkchoiceUpdatedV1 point 7; hive 'Invalid PayloadAttributes').
          service.calls.asScala.toSeq.map(_._2) shouldBe Seq(None)
        }
      }
    }

    "answer -38005 for complete attributes outside Amsterdam, applying nothing" taggedAs (UnitTest, ConsensusTest) in {
      val service = new RecordingService
      val response = call(4, List(fcsJson(), attrsJson(OsakaTs)), service)

      response.error.map(_.code) shouldBe Some(UnsupportedFork)
      service.calls shouldBe empty
    }

    "answer -38003 for a slotNumber or targetGasLimit that is not a 64-bit QUANTITY" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val malformed = Seq(
        attrsJson(AmsterdamTs, slotNumber = Some(JString("0x10000000000000000"))),
        attrsJson(AmsterdamTs, slotNumber = Some(JString("0x"))),
        attrsJson(AmsterdamTs, targetGasLimit = Some(JString("0xzz"))),
        attrsJson(AmsterdamTs, targetGasLimit = Some(JArray(Nil)))
      )
      malformed.foreach { attrs =>
        val service = new RecordingService
        val response = call(4, List(fcsJson(), attrs), service)
        withClue(attrs.toString) {
          response.error.map(_.code) shouldBe Some(InvalidAttributes)
          service.calls shouldBe empty
        }
      }
    }
  }

  "engine_forkchoiceUpdatedV3 once Amsterdam is scheduled" should {

    "keep ignoring the PayloadAttributesV4 fields before Amsterdam" taggedAs (UnitTest, ConsensusTest) in {
      val service = new RecordingService
      val response = call(3, List(fcsJson(), attrsJson(OsakaTs, slotNumber = Some(JString("0xzz")))), service)

      response.error shouldBe None
      service.calls.asScala.toSeq.map(_._2.map(a => (a.slotNumber, a.targetGasLimit))) shouldBe Seq(Some((None, None)))
    }

    "answer -38005 at an Amsterdam timestamp (V4 serves it)" taggedAs (UnitTest, ConsensusTest) in {
      val service = new RecordingService
      val response = call(3, List(fcsJson(), attrsJson(AmsterdamTs)), service)

      response.error.map(_.code) shouldBe Some(UnsupportedFork)
      service.calls shouldBe empty
    }
  }

  // ── The service ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** A real EngineApiService on EEST's `Amsterdam` schedule (every fork at genesis) whose chain is an Amsterdam
    * genesis.
    */
  private class Fixture extends EphemBlockchainTestSetup:
    val config: BlockchainConfig = EestBlockchainReplay
      .configFor(blockchainConfig, "Amsterdam", BigInt(1))
      .fold(err => throw new IllegalStateException(err), identity)
    val forkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)
    val service = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      null, // never reached: nothing is executed or built
      forkChoiceManager,
      None
    )(config, null)
    val genesis: Block = Block(
      BlockHeader(
        parentHash = BlockHash(ByteString(new Array[Byte](32))),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(BlockHeader.EmptyMpt),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(0),
        gasLimit = GasAmount(60000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(0),
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostAmsterdam(
          baseFee = BigInt(7),
          withdrawalsRoot = BlockHeader.EmptyMpt,
          blobGasUsed = BigInt(0),
          excessBlobGas = BigInt(0),
          parentBeaconBlockRoot = ByteString(new Array[Byte](32)),
          requestsHash = ByteString(java.security.MessageDigest.getInstance("SHA-256").digest()),
          blockAccessListHash = BlockAccessList.EmptyHash,
          slotNumber = BigInt(0)
        )
      ),
      BlockBody(Nil, Nil, withdrawals = Some(Nil))
    )
    blockchainWriter.storeBlock(genesis).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()
    val genesisHash: String = "0x" + Hex.toHexString(genesis.header.hash.value.toArray)

    def fcuV4(params: List[JValue]): JsonRpcResponse =
      new EngineApiController(service, None, config)
        .handleRequest(JsonRpcRequest("2.0", "engine_forkchoiceUpdatedV4", Some(JArray(params)), Some(JInt(1))))
        .unsafeRunSync()

  private def attributes(ts: Long, slot: Option[BigInt], target: Option[BigInt] = None) =
    PayloadAttributes(
      timestamp = ts,
      prevRandao = ByteString(new Array[Byte](32)),
      suggestedFeeRecipient = Address(0),
      withdrawals = Some(Nil),
      parentBeaconBlockRoot = Some(ByteString(Array.fill(32)(0xab.toByte))),
      slotNumber = slot,
      targetGasLimit = target
    )

  "EngineApiService, asked for an Amsterdam payload" should {

    "apply the forkchoice state, then refuse attributes without a slot number with -38003 and store no payload" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture:
      // The controller refuses such attributes before the service is asked (see above); this is the service's own
      // guard, reached directly. go-ethereum: "no slot number set post-amsterdam", InvalidPayloadAttributes.
      val headHash = genesis.header.hash.value
      val zero = ByteString(new Array[Byte](32))
      val slotless = attributes(12, slot = None)
      val outcome = service.forkchoiceUpdated(ForkChoiceState(headHash, zero, zero), Some(slotless)).unsafeRunSync()

      outcome shouldBe Left("ATTR:" + EngineApiService.AmsterdamSlotNumberMissing)
      forkChoiceManager.getHeadBlockHash shouldBe Some(headHash)
      service.getPayload(EngineApiService.payloadId(headHash, slotless)).unsafeRunSync() shouldBe
        Left("Payload not available")

    "still answer VALID to the same call without attributes" taggedAs (UnitTest, ConsensusTest) in new Fixture:
      val response = fcuV4(List(fcsJson(genesisHash), JNull, JString("0x" + "ff" * 16)))

      response.error shouldBe None
      payloadStatus(response) shouldBe Some(JString("VALID"))
      response.result.map(_ \ "payloadId") shouldBe Some(JNull)

    "refuse Amsterdam attributes without a slot number in buildBlockOnParent, strict or lenient" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture:
      // Also what testing_buildBlockV1 gets at Amsterdam: its attributes decoder reads no slotNumber.
      Seq(true, false).foreach { strict =>
        withClue(s"strict=$strict: ") {
          service.buildBlockOnParent(
            genesis,
            attributes(12, slot = None),
            Nil,
            ByteString.empty,
            GasAmount(60000000),
            strict
          ) shouldBe Left(EngineApiService.AmsterdamSlotNumberMissing)
        }
      }
  }

  "EngineApiService.payloadId" should {

    "keep the V1-V3 IDs: without slotNumber and targetGasLimit it is the pre-Amsterdam derivation" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val head = ByteString(Array.fill(32)(0x11.toByte))
      val withdrawal = Withdrawal(BigInt(3), BigInt(9), Address(ByteString(Array.fill(20)(0x22.toByte))), BigInt(1000))
      val attrs = attributes(OsakaTs, slot = None).copy(withdrawals = Some(Seq(withdrawal)))
      // The derivation forkchoiceUpdated inlined before it was extracted.
      val before = ByteString(
        kec256(
          head.toArray ++ BigInt(attrs.timestamp).toByteArray ++ attrs.prevRandao.toArray ++
            attrs.suggestedFeeRecipient.bytes.toArray ++
            (withdrawal.index.toByteArray ++ withdrawal.validatorIndex.toByteArray ++
              withdrawal.address.bytes.toArray ++ withdrawal.amount.toByteArray) ++
            attrs.parentBeaconBlockRoot.get.toArray
        ).take(8)
      )
      EngineApiService.payloadId(head, attrs) shouldBe before
    }

    "differ for every slotNumber and targetGasLimit" taggedAs (UnitTest, ConsensusTest) in {
      val head = ByteString(Array.fill(32)(0x11.toByte))
      val variants = Seq(
        attributes(AmsterdamTs, slot = Some(BigInt(1)), target = None),
        attributes(AmsterdamTs, slot = Some(BigInt(2)), target = None),
        attributes(AmsterdamTs, slot = Some(BigInt(1)), target = Some(BigInt(30000000))),
        attributes(AmsterdamTs, slot = Some(BigInt(1)), target = Some(BigInt(60000000))),
        attributes(AmsterdamTs, slot = Some((BigInt(1) << 64) - 1), target = Some(BigInt(60000000)))
      )
      variants.map(EngineApiService.payloadId(head, _)).distinct should have size variants.size.toLong
    }
  }
