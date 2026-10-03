package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JArray
import org.json4s.JsonAST.JInt
import org.json4s.JsonAST.JNothing
import org.json4s.JsonAST.JNull
import org.json4s.JsonAST.JObject
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JValue
import org.json4s.jvalue2monadic
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockAccessList.AccountChanges
import com.chipprbots.ethereum.domain.BlockAccessList.BalanceChange
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.jsonrpc.JsonRpcRequest
import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** engine_getPayloadBodiesByHashV2 and engine_getPayloadBodiesByRangeV2 (execution-apis amsterdam.md). Each "follows
  * the same specification as" its V1 method and answers ExecutionPayloadBodyV2: V1's `transactions` and `withdrawals`
  * plus `blockAccessList`, the EIP-7928 list stored for an Amsterdam block served byte for byte, and `null` "for blocks
  * that predate the Amsterdam fork activation" and "if the block access list has been pruned from storage".
  *
  * A real EngineApiService over ephemeral storage, so every answer is read from the store the import paths write. Block
  * 1 is the last before Amsterdam (one second before it), block 2 the first at it, holding a live Platåberget list;
  * block 3 is an Amsterdam block whose list this node does not hold.
  */
class EngineApiPayloadBodiesV2Spec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val AmsterdamTs = 10000000003L
  private val testConfig = Config.blockchains.blockchainConfig
  private val amsterdamConfig: BlockchainConfig =
    testConfig.copy(forkTimestamps = testConfig.forkTimestamps.copy(amsterdamTimestamp = Some(AmsterdamTs)))

  /** Platåberget block 275,654's list: 65,994 bytes, whose keccak256 its header commits to. */
  private val live = PlatabergetBalVectors.load(PlatabergetBalVectors.Block275654)

  /** A small list, committed to by block 3 and never stored. */
  private val unstoredList: ByteString = BlockAccessList(
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

  private val tx: SignedTransaction = SignedTransaction.sign(BlockHelpers.defaultTx, BlockHelpers.keyPair, None)

  private def hex(bytes: ByteString): String = "0x" + Hex.toHexString(bytes.toArray)

  private def data(value: JValue): ByteString = value match
    case JString(s) => ByteString(Hex.decode(s.stripPrefix("0x")))
    case other      => fail(s"not DATA: $other")

  /** The `blockAccessList` field of an ExecutionPayloadBodyV2: always present, as DATA or JSON null. */
  private def accessListField(entry: JValue): JValue = entry match
    case JObject(fields) => fields.toMap.getOrElse("blockAccessList", fail(s"no blockAccessList field in $entry"))
    case other           => fail(s"not an ExecutionPayloadBodyV2: $other")

  private val beaconRoot = ByteString(Array.fill(32)(0xab.toByte))
  private val requestsHash = ByteString(Array.fill(32)(0xcd.toByte))

  private def pragueFields: HeaderExtraFields =
    HefPostPrague(BigInt(7), BlockHeader.EmptyMpt, BigInt(0), BigInt(0), beaconRoot, requestsHash)

  private def amsterdamFields(accessList: ByteString): HeaderExtraFields =
    HefPostAmsterdam(
      baseFee = BigInt(7),
      withdrawalsRoot = BlockHeader.EmptyMpt,
      blobGasUsed = BigInt(0),
      excessBlobGas = BigInt(0),
      parentBeaconBlockRoot = beaconRoot,
      requestsHash = requestsHash,
      blockAccessListHash = ByteString(kec256(accessList.toArray)),
      slotNumber = BigInt(42)
    )

  /** A block with one transaction and one withdrawal. Only what it is stored under and served as matters here. */
  private def block(number: Int, timestamp: Long, extraFields: HeaderExtraFields): Block =
    Block(
      BlockHeader(
        parentHash = BlockHash(ByteString(Array.fill(32)((0x10 + number).toByte))),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(Array.fill(20)(0x07.toByte)),
        stateRoot = TrieRoot(ByteString(Array.fill(32)(0x44.toByte))),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(number),
        gasLimit = GasAmount(60000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(timestamp),
        extraData = ByteString("fukuii".getBytes),
        mixHash = BlockHash(ByteString(Array.fill(32)(0x55.toByte))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = extraFields
      ),
      BlockBody(
        Seq(tx),
        Nil,
        withdrawals = Some(
          Seq(Withdrawal(BigInt(number), BigInt(100 + number), Address(ByteString(Array.fill(20)(0x21.toByte))), 1000))
        )
      )
    )

  private trait Setup extends EphemBlockchainTestSetup:
    val preAmsterdam: Block = block(1, AmsterdamTs - 1, pragueFields)
    val withList: Block = block(2, AmsterdamTs, amsterdamFields(live.bytes))
    val withoutList: Block = block(3, AmsterdamTs + 12, amsterdamFields(unstoredList))
    val unknown: ByteString = ByteString(Array.fill(32)(0x99.toByte))

    Seq(preAmsterdam, withList, withoutList).foreach(b => blockchainWriter.storeBlock(b).commit())
    blockchainWriter.storeBlockAccessList(withList.header.hash, live.accessList).commit()
    blockchainWriter.saveBestKnownBlocks(withoutList.header.hash, BigInt(3))

    val controller = new EngineApiController(
      new EngineApiService(blockchainReader, blockchainWriter, null, null, None)(amsterdamConfig, null),
      None,
      amsterdamConfig
    )

    def call(method: String, params: JValue*): JsonRpcResponse =
      controller
        .handleRequest(JsonRpcRequest("2.0", method, Some(JArray(params.toList)), Some(JInt(1))))
        .unsafeRunSync()

    def entries(response: JsonRpcResponse): List[JValue] =
      response.error shouldBe None
      response.result match
        case Some(JArray(items)) => items
        case other               => fail(s"not an array of bodies: $other")

    def byHash(version: Int, hashes: ByteString*): List[JValue] =
      entries(call(s"engine_getPayloadBodiesByHashV$version", JArray(hashes.map(h => JString(hex(h))).toList)))

    def byRange(version: Int, start: Long, count: Long): JsonRpcResponse =
      call(
        s"engine_getPayloadBodiesByRangeV$version",
        JString(s"0x${start.toHexString}"),
        JString(s"0x${count.toHexString}")
      )

  "engine_getPayloadBodiesByHashV2" should {

    "serve an Amsterdam block's stored list byte for byte, null before Amsterdam and for an unknown block, in order" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val result =
        byHash(2, preAmsterdam.header.hash.value, withList.header.hash.value, unknown, withList.header.hash.value)

      result should have size 4
      accessListField(result(0)) shouldBe JNull
      val served = data(accessListField(result(1)))
      served shouldBe live.bytes
      // What the CL checks it against: the header's commitment, which is the live block's.
      ByteString(kec256(served.toArray)) shouldBe live.blockAccessListHash
      withList.header.blockAccessListHash shouldBe Some(live.blockAccessListHash)
      result(2) shouldBe JNull // an unknown block is a null entry, as in V1
      result(3) shouldBe result(1) // each position is answered on its own

    "answer null for an Amsterdam block whose list is not held (pruned, or never stored)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val result = byHash(2, withoutList.header.hash.value)

      result should have size 1
      result.head should not be JNull // the block is known: a body, with no list
      accessListField(result.head) shouldBe JNull

    "answer null before Amsterdam even with a list stored under the block's hash: the fork decides, not the store" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      blockchainWriter.storeBlockAccessList(preAmsterdam.header.hash, live.accessList).commit()
      blockchainReader.getBlockAccessListByHash(preAmsterdam.header.hash) shouldBe Some(live.bytes)

      accessListField(byHash(2, preAmsterdam.header.hash.value).head) shouldBe JNull

    "carry V1's transactions and withdrawals unchanged, while V1 gains no blockAccessList field" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val hashes = Seq(preAmsterdam, withList, withoutList).map(_.header.hash.value)
      val v1 = byHash(1, hashes*)
      val v2 = byHash(2, hashes*)

      v1 should have size 3
      v1.zip(v2).foreach { (one, two) =>
        two \ "transactions" shouldBe one \ "transactions"
        two \ "withdrawals" shouldBe one \ "withdrawals"
        (two \ "transactions").children should have size 1
        (two \ "withdrawals").children should have size 1
        one \ "blockAccessList" shouldBe JNothing
        two match
          case JObject(fields) => fields.map(_._1) shouldBe List("transactions", "withdrawals", "blockAccessList")
          case other           => fail(s"not an object: $other")
      }
  }

  "engine_getPayloadBodiesByRangeV2" should {

    "answer the canonical range with the entries by-hash gives for the same blocks" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val range = entries(byRange(2, 1, 3))

      range shouldBe byHash(
        2,
        preAmsterdam.header.hash.value,
        withList.header.hash.value,
        withoutList.header.hash.value
      )
      range.map(accessListField).map(_ == JNull) shouldBe List(true, false, true)
      data(accessListField(range(1))) shouldBe live.bytes

    "keep V1's range rules: cut at the tip, [] past it, null for a block below it that is not held" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      entries(byRange(2, 2, 10)) should have size 2 // blocks 2 and 3, no trailing null past the tip
      entries(byRange(2, 4, 1)) shouldBe Nil

      // The tip moves to 4, a block this node does not hold.
      blockchainWriter.saveBestKnownBlocks(BlockHash(ByteString(Array.fill(32)(0x77.toByte))), BigInt(4))
      Seq(1, 2).foreach { version =>
        withClue(s"V$version: ") {
          entries(byRange(version, 3, 5)).map(_ == JNull) shouldBe List(false, true)
        }
      }

    "answer -32602 for a start or a count below 1, as V1 does" taggedAs (UnitTest, ConsensusTest) in new Setup:
      for
        version <- Seq(1, 2)
        (start, count) <- Seq((0L, 1L), (1L, 0L))
      do
        withClue(s"V$version start=$start count=$count: ") {
          byRange(version, start, count).error.map(_.code) shouldBe Some(-32602)
        }

    "serve at most 1,024 bodies per call, as V1 does" taggedAs (UnitTest, ConsensusTest) in new Setup:
      blockchainWriter.saveBestKnownBlocks(BlockHash(ByteString(Array.fill(32)(0x78.toByte))), BigInt(5000))

      Seq(1, 2).foreach { version =>
        withClue(s"V$version: ") {
          entries(byRange(version, 1, 5000)) should have size 1024
        }
      }
  }

  "engine_exchangeCapabilities" should {

    "advertise both payload-bodies V2 methods beside their V1 methods" taggedAs UnitTest in {
      val service = new EngineApiService(null, null, null, null, None)(null, null)
      val response = new EngineApiController(service, None, amsterdamConfig)
        .handleRequest(
          JsonRpcRequest("2.0", "engine_exchangeCapabilities", Some(JArray(List(JArray(Nil)))), Some(JInt(1)))
        )
        .unsafeRunSync()
      val advertised = response.result match
        case Some(JArray(items)) => items.collect { case JString(s) => s }
        case other               => fail(s"not a method list: $other")

      (advertised should contain).allOf(
        "engine_getPayloadBodiesByHashV1",
        "engine_getPayloadBodiesByHashV2",
        "engine_getPayloadBodiesByRangeV1",
        "engine_getPayloadBodiesByRangeV2"
      )
    }
  }
