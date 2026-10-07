package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** engine_forkchoiceUpdated error codes: every outcome keeps the code execution-apis gives it.
  *
  * execution-apis `paris.md` (engine_forkchoiceUpdatedV1, point 7; `shanghai.md` and `cancun.md` extend that point with
  * their attribute checks) processes `payloadAttributes` only AFTER the forkchoice state has been applied, and only for
  * a VALID head. hive spells the order out in `suites/engine/payload_attributes.go`: SYNCING for an unknown head,
  * INVALID for an invalid head, apply the forkchoice state, THEN the attributes (-38003). So an attributes error can
  * never mask what applying the forkchoice state found: an inconsistent state is -38002, an invalidated head is an
  * INVALID payload status, an unknown head is SYNCING.
  *
  * The defect these pin: an attribute set the controller refuses by its version check (-38003) forwarded an
  * attribute-less forkchoiceUpdated to the service, and then answered -38003 for EVERY outcome but SYNCING — so a
  * -38002 raised while applying the forkchoice state, and an INVALID head, both came back as -38003.
  *
  * Every case runs a real [[EngineApiService]] and [[ForkChoiceManager]] over ephemeral storage, through
  * [[EngineApiController.handleRequest]], so the code is the one the wire sees.
  */
class EngineApiForkchoiceErrorCodeSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val InvalidParams = -32602
  private val InvalidForkchoiceState = -38002
  private val InvalidPayloadAttributes = -38003
  private val UnsupportedFork = -38005

  private val ShanghaiTs: Long = 9999999994L // Shanghai, not Cancun (src/test/resources/application.conf)
  private val CancunTs: Long = 9999999995L // Cancun, not Prague

  private val zero32 = ByteString(new Array[Byte](32))

  private trait Setup extends EphemBlockchainTestSetup:

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
    override lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)

    lazy val blockExec = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )
    lazy val forkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)
    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi = new EngineApiService(blockchainReader, blockchainWriter, blockExec, forkChoiceManager, None)(
      blockchainConfig,
      typedScheduler
    )
    lazy val controller = new EngineApiController(engineApi)

    private def posBlock(block: Block): Block =
      block.copy(header = block.header.copy(difficulty = Difficulty.Zero), body = block.body.copy(uncleNodesList = Nil))

    // genesis <- block1 (canonical, stored and indexed the way engine_newPayload stores a head-extending payload),
    // genesis <- side1 (executed side block: stored by hash only, with receipts).
    val genesis: Block = posBlock(BlockHelpers.genesis)
    blockchainWriter.storeBlock(genesis).commit()
    blockchainWriter.storeReceipts(genesis.header.hash, Nil).commit()
    blockchainWriter.saveBestKnownBlocks(genesis.header.hash, 0)

    val block1: Block = BlockHelpers.generateChain(1, genesis, posBlock).head
    blockchainWriter.storeBlock(block1).commit()
    blockchainWriter.storeReceipts(block1.header.hash, Nil).commit()

    val side1: Block = BlockHelpers.generateChain(1, genesis, posBlock).head
    blockchainWriter.storeBlockByHashOnly(side1).commit()
    blockchainWriter.storeReceipts(side1.header.hash, Nil).commit()

    def hex(bs: ByteString): String = "0x" + bs.toArray.map("%02x".format(_)).mkString

    def attrs(ts: Long, withdrawals: Boolean, beaconRoot: Boolean): JObject =
      JObject(
        List[(String, JValue)](
          "timestamp" -> JString("0x" + ts.toHexString),
          "prevRandao" -> JString(hex(zero32)),
          "suggestedFeeRecipient" -> JString("0x" + "00" * 20)
        ) ++ (if withdrawals then List("withdrawals" -> JArray(Nil)) else Nil)
          ++ (if beaconRoot then List("parentBeaconBlockRoot" -> JString("0x" + "ab" * 32)) else Nil)
      )

    /** V3 attributes the controller refuses by its version check with -38003 at ANY timestamp: no beacon root. */
    val missingBeaconRoot: JObject = attrs(CancunTs, withdrawals = true, beaconRoot = false)

    /** V3 attributes that pass the version check. */
    val wellFormedV3: JObject = attrs(CancunTs, withdrawals = true, beaconRoot = true)

    def fcu(
        version: Int,
        head: ByteString,
        safe: ByteString = zero32,
        finalized: ByteString = zero32,
        payloadAttributes: Option[JObject]
    ): JsonRpcResponse =
      val state = JObject(
        "headBlockHash" -> JString(hex(head)),
        "safeBlockHash" -> JString(hex(safe)),
        "finalizedBlockHash" -> JString(hex(finalized))
      )
      send(version, state, payloadAttributes)

    /** A forkchoiceUpdated with the forkchoiceState exactly as given, well-formed or not. */
    def send(version: Int, state: JObject, payloadAttributes: Option[JObject]): JsonRpcResponse =
      val params = JArray(state :: payloadAttributes.toList)
      controller
        .handleRequest(JsonRpcRequest("2.0", s"engine_forkchoiceUpdatedV$version", Some(params), Some(JInt(1))))
        .unsafeRunSync()

    /** A forkchoiceState naming block1 as head, zero safe/finalized, with `field` replaced by `value` (or dropped). */
    def stateWith(field: String, value: Option[JValue]): JObject =
      val base = List[(String, JValue)](
        "headBlockHash" -> JString(hex(block1.header.hash.value)),
        "safeBlockHash" -> JString(hex(zero32)),
        "finalizedBlockHash" -> JString(hex(zero32))
      )
      JObject(base.flatMap { case (k, v) => if k == field then value.map(k -> _) else Some(k -> v) })

    /** `result.payloadStatus.status`, when the response carries a result. */
    def payloadStatusOf(response: JsonRpcResponse): Option[String] =
      response.result.flatMap {
        case JObject(fields) =>
          fields
            .collectFirst { case ("payloadStatus", JObject(status)) => status }
            .flatMap(_.collectFirst { case ("status", JString(s)) => s })
        case _ => None
      }

  "engine_forkchoiceUpdated with attributes refused by the version check (-38003)" should {

    "answer -38002, not -38003, when the safe block is unknown" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val unknown = ByteString(Array.fill(32)(0x5a.toByte))
      val response = fcu(3, block1.header.hash.value, safe = unknown, payloadAttributes = Some(missingBeaconRoot))
      response.error.map(_.code) shouldBe Some(InvalidForkchoiceState)
      response.result shouldBe None
      // Nothing was applied: the forkchoice state was refused before it reached the ForkChoiceManager.
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -38002, not -38003, when the finalized block is unknown" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val unknown = ByteString(Array.fill(32)(0x5b.toByte))
      val response = fcu(3, block1.header.hash.value, finalized = unknown, payloadAttributes = Some(missingBeaconRoot))
      response.error.map(_.code) shouldBe Some(InvalidForkchoiceState)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -38002, not -38003, when the safe block is not an ancestor of the head" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      // hive `Inconsistent Safe in ForkchoiceState`, with attributes the version check refuses on top.
      val response =
        fcu(3, block1.header.hash.value, safe = side1.header.hash.value, payloadAttributes = Some(missingBeaconRoot))
      response.error.map(_.code) shouldBe Some(InvalidForkchoiceState)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer the INVALID payload status, not -38003, when the head was invalidated" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val invalidHead = ByteString(Array.fill(32)(0x6c.toByte))
      engineApi.invalidChainReporter.reportInvalid(invalidHead, genesis.header.hash.value)

      val response = fcu(3, invalidHead, payloadAttributes = Some(missingBeaconRoot))
      response.error shouldBe None
      payloadStatusOf(response) shouldBe Some("INVALID")

    "answer SYNCING, not -38003, when the head is unknown" taggedAs (UnitTest, ConsensusTest) in new Setup:
      // hive `Invalid PayloadAttributes, Missing BeaconRoot, Syncing=True`.
      val response = fcu(3, ByteString(Array.fill(32)(0x7d.toByte)), payloadAttributes = Some(missingBeaconRoot))
      response.error shouldBe None
      payloadStatusOf(response) shouldBe Some("SYNCING")

    "answer -38003 after applying a consistent forkchoice state" taggedAs (UnitTest, ConsensusTest) in new Setup:
      // hive `Invalid PayloadAttributes, *, Syncing=False`: the error, AND the head moved.
      val response = fcu(3, block1.header.hash.value, payloadAttributes = Some(missingBeaconRoot))
      response.error.map(_.code) shouldBe Some(InvalidPayloadAttributes)
      blockchainReader.getBestBlockNumber shouldBe BigInt(1)
      blockchainReader.getBestBlock.map(_.header.hash) shouldBe Some(block1.header.hash)
  }

  "engine_forkchoiceUpdated with attributes that pass the version check" should {

    "answer -38002 when the safe block is unknown" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val unknown = ByteString(Array.fill(32)(0x5c.toByte))
      val response = fcu(3, block1.header.hash.value, safe = unknown, payloadAttributes = Some(wellFormedV3))
      response.error.map(_.code) shouldBe Some(InvalidForkchoiceState)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -38003 for a zero timestamp, after applying the forkchoice state" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        // The service's own attribute check (timestamp), as opposed to the controller's version check.
        val response =
          fcu(1, block1.header.hash.value, payloadAttributes = Some(attrs(0L, withdrawals = false, beaconRoot = false)))
        response.error.map(_.code) shouldBe Some(InvalidPayloadAttributes)
        blockchainReader.getBestBlockNumber shouldBe BigInt(1)

    "answer -38005 for a fork outside the method's window, without applying anything" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      // hive `ForkchoiceUpdatedV3 To Request Shanghai Payload, Non-Null Beacon Root`.
      val response =
        fcu(
          3,
          block1.header.hash.value,
          payloadAttributes = Some(attrs(ShanghaiTs, withdrawals = true, beaconRoot = true))
        )
      response.error.map(_.code) shouldBe Some(UnsupportedFork)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)
  }

  /** execution-apis shanghai.md / cancun.md, engine_forkchoiceUpdatedV2/V3 point 1: "Client software MUST verify that
    * forkchoiceState matches the ForkchoiceStateV1 structure and return -32602: Invalid params on failure". The
    * structure is three required fields, each DATA of 32 bytes. go-ethereum answers -32602 for any hash it cannot
    * decode into `common.Hash` (`hexutil.UnmarshalFixedJSON`: 0x prefix, exactly 64 hex digits), in every version.
    *
    * These answered -38003 ("malformed forkchoice params") — or, for a hash of the wrong length or without its prefix,
    * were accepted and acted on. Nothing may be applied in any of them.
    */
  "engine_forkchoiceUpdated with a forkchoiceState that does not match ForkchoiceStateV1" should {

    "answer -32602 when a field is missing" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val response = send(3, stateWith("safeBlockHash", None), None)
      response.error.map(_.code) shouldBe Some(InvalidParams)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -32602 for a hash that is not hex" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val response = send(3, stateWith("headBlockHash", Some(JString("0x" + "zz" * 32))), None)
      response.error.map(_.code) shouldBe Some(InvalidParams)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -32602 for a hash that is not 32 bytes" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val twentyBytes = hex(block1.header.hash.value.take(20))
      val response = send(3, stateWith("headBlockHash", Some(JString(twentyBytes))), None)
      response.error.map(_.code) shouldBe Some(InvalidParams)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -32602 for a hash without its 0x prefix, instead of applying it" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        val unprefixed = hex(block1.header.hash.value).drop(2)
        val response = send(3, stateWith("headBlockHash", Some(JString(unprefixed))), None)
        response.error.map(_.code) shouldBe Some(InvalidParams)
        blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "answer -32602 for a hash that is not a string" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val response = send(3, stateWith("finalizedBlockHash", Some(JInt(0))), None)
      response.error.map(_.code) shouldBe Some(InvalidParams)
      blockchainReader.getBestBlockNumber shouldBe BigInt(0)

    "still accept the forkchoiceState when it is well formed (upper-case hex included)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val upperCase = "0x" + hex(block1.header.hash.value).drop(2).toUpperCase
      val response = send(3, stateWith("headBlockHash", Some(JString(upperCase))), None)
      response.error shouldBe None
      payloadStatusOf(response) shouldBe Some("VALID")
      blockchainReader.getBestBlockNumber shouldBe BigInt(1)

    "leave a payloadAttributes decoding failure at -38003" taggedAs (UnitTest, ConsensusTest) in new Setup:
      // Attributes that do not match their structure are -38003 (cancun.md point 2.1), not -32602; only the
      // forkchoiceState's structure is -32602.
      val noPrevRandao = JObject(
        "timestamp" -> JString("0x" + CancunTs.toHexString),
        "suggestedFeeRecipient" -> JString("0x" + "00" * 20),
        "withdrawals" -> JArray(Nil),
        "parentBeaconBlockRoot" -> JString("0x" + "ab" * 32)
      )
      val response = send(3, stateWith("safeBlockHash", Some(JString(hex(zero32)))), Some(noPrevRandao))
      response.error.map(_.code) shouldBe Some(InvalidPayloadAttributes)
  }
