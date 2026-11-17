package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1727 PR 4 (B-130): a simulated call reports `maxUsedGas` as go-ethereum does, the peak gas before the refund,
  * raised to the EIP-7623 calldata floor from Prague (core/state_transition.go `settleGas`, v1.17.9: `peakUsed =
  * gasUsedBeforeRefund`, then `max(peakUsed, floorDataGas)` when Prague is active and the post-refund gas is under the
  * floor; internal/ethapi/simulate.go:463 copies it into the call result).
  *
  * `gasUsed` is the receipt figure: after the refund, and never below the floor. So the two differ exactly when a
  * refund was taken, and `maxUsedGas` is never below `gasUsed`. Until this change `maxUsedGas` was a copy of `gasUsed`.
  *
  * Chain: the Amsterdam fixture schedule (Cancun 60, Prague 120, Osaka 180, Amsterdam 360). The base block sits 12
  * seconds before the block under test, which puts it at Cancun (72), Prague (120) or Osaka (312).
  *
  * The contract at To is PUSH1 0, PUSH1 0, SSTORE, STOP with slot 0 preset to 1: clearing a slot costs 5,000 (2,100
  * cold access and 2,900 reset) and earns the 4,800 EIP-3529 refund. With no calldata that is 21,000 + 6 + 5,000 =
  * 26,006 before the refund and 21,206 after it. Each non-zero calldata byte adds 16 to the intrinsic cost and 40 to
  * the EIP-7623 floor (21,000 + 10 x 4 x bytes).
  */
// scalastyle:off magic.number
class EthSimulateMaxUsedGasSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(Array.fill[Byte](32)(0))

  private val From = "0xc000000000000000000000000000000000000000"
  private val To = "0xc100000000000000000000000000000000000000"
  private val Slot0 = "0x" + "00" * 32
  private val One = "0x" + "00" * 31 + "01"

  /** The base block is `baseTimestamp`; the simulated block follows it by 12 seconds. */
  private class Node(baseTimestamp: Long) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = amsterdamConfig

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = Difficulty(0),
          beneficiary = ByteString(new Array[Byte](20)),
          stateRoot = TrieRoot(emptyRoot),
          gasLimit = GasAmount(30_000_000),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(baseTimestamp),
          extraFields = HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)
        ),
        BlockBody.empty
      ),
      Nil,
      ChainWeight.zero,
      saveAsBestBlock = true
    )

    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      amsterdamConfig
    )

    /** What each call of one simulated block reports: (gasUsed, maxUsedGas, error code). */
    def calls(payload: String): List[(BigInt, BigInt, Option[Int])] =
      val request = EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
        Some(JArray(List(parse(payload), JString("latest"))))
      )
      val response = for
        req <- request
        res <- service.ethSimulate(req).unsafeRunSync()
      yield EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(res)
      response match
        case Right(JArray(block :: Nil)) =>
          (block \ "calls") match
            case JArray(cs) =>
              cs.map { c =>
                val error = (c \ "error" \ "code") match
                  case JInt(code) => Some(code.toInt)
                  case _          => None
                (quantity(c \ "gasUsed"), quantity(c \ "maxUsedGas"), error)
              }
            case other => fail(s"expected calls, got $other")
        case other => fail(s"expected one simulated block, got $other")

  private def quantity(v: JValue): BigInt = v match
    case JString(s) => BigInt(s.drop(2), 16)
    case other      => fail(s"expected a quantity, got $other")

  // ── Requests ────────────────────────────────────────────────────────────────

  private val ClearerCode = "0x600060005500"
  private def nonZeros(n: Int): String = "0x" + "01" * n

  /** One call to To. `code` is placed at To, with slot 0 preset to 1 when `clears` (so SSTORE 0 is a clear). */
  private def call(code: Option[String], clears: Boolean, input: String, gas: String = "0x186a0"): String =
    val account = code match
      case Some(c) if clears => s""""$To":{"code":"$c","stateDiff":{"$Slot0":"$One"}},"""
      case Some(c)           => s""""$To":{"code":"$c"},"""
      case None              => ""
    s"""{"blockStateCalls":[{"stateOverrides":{$account"$From":{"balance":"0x3635c9adc5dea00000"}},
       |"calls":[{"from":"$From","to":"$To","input":"$input","gas":"$gas"}]}]}""".stripMargin

  private def single(node: Node, request: String): (BigInt, BigInt, Option[Int]) =
    node.calls(request) match
      case one :: Nil => one
      case other      => fail(s"expected one call, got $other")

  // ── Before Prague: no floor, so maxUsedGas is the pre-refund gas and nothing else ──────────────────────────────

  "eth_simulateV1 before Prague" should "report the gas before the refund as maxUsedGas" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(baseTimestamp = 60)
    single(node, call(Some(ClearerCode), clears = true, "0x")) shouldBe ((BigInt(21206), BigInt(26006), None))
  }

  it should "not raise maxUsedGas to a floor that does not apply yet" taggedAs (UnitTest, RPCTest) in {
    // 1,000 non-zero bytes to an EOA: intrinsic 37,000, the EIP-7623 floor of 61,000 is not in force before Prague.
    val node = new Node(baseTimestamp = 60)
    single(node, call(None, clears = false, nonZeros(1000))) shouldBe ((BigInt(37000), BigInt(37000), None))
  }

  it should "keep the refund out of maxUsedGas when calldata is charged too" taggedAs (UnitTest, RPCTest) in {
    // 21,000 + 16,000 + 6 + 5,000 = 42,006 before the refund; the 4,800 refund leaves 37,206. No floor.
    val node = new Node(baseTimestamp = 60)
    single(node, call(Some(ClearerCode), clears = true, nonZeros(1000))) shouldBe
      ((BigInt(37206), BigInt(42006), None))
  }

  // ── From Prague: the EIP-7623 floor ────────────────────────────────────────────────────────────────────────────

  "eth_simulateV1 on Prague" should "report the gas before the refund as maxUsedGas, as it does before Prague" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // 26,006 before the refund, 21,206 after; a bare call has a floor of 21,000, which binds neither.
    val node = new Node(baseTimestamp = 108)
    single(node, call(Some(ClearerCode), clears = true, "0x")) shouldBe ((BigInt(21206), BigInt(26006), None))
  }

  it should "report the pre-refund peak when the floor falls between it and the post-refund gas" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // 200 non-zero bytes: pre-refund 21,000 + 3,200 + 6 + 5,000 = 29,206; post-refund 24,406; floor 21,000 + 8,000 =
    // 29,000. The receipt pays the floor (29,000); the peak is the pre-refund figure, which is above the floor.
    val node = new Node(baseTimestamp = 108)
    single(node, call(Some(ClearerCode), clears = true, nonZeros(200))) shouldBe
      ((BigInt(29000), BigInt(29206), None))
  }

  it should "raise maxUsedGas to the floor when the floor is above the pre-refund gas" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // 1,000 non-zero bytes: pre-refund 42,006, post-refund 37,206, floor 61,000. Both figures are the floor.
    val node = new Node(baseTimestamp = 108)
    single(node, call(Some(ClearerCode), clears = true, nonZeros(1000))) shouldBe
      ((BigInt(61000), BigInt(61000), None))
  }

  it should "raise a bare calldata call to the floor as well" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(baseTimestamp = 108)
    single(node, call(None, clears = false, nonZeros(1000))) shouldBe ((BigInt(61000), BigInt(61000), None))
  }

  "eth_simulateV1 on Osaka" should "report the pre-refund peak when the floor falls between it and the post-refund gas" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(baseTimestamp = 300)
    single(node, call(Some(ClearerCode), clears = true, nonZeros(200))) shouldBe
      ((BigInt(29000), BigInt(29206), None))
  }

  // ── A call that fails has no refund to take ────────────────────────────────────────────────────────────────────

  "eth_simulateV1" should "report a revert at the gas it used, with its remaining gas returned" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // PUSH1 0, PUSH1 0, REVERT: 21,000 + 6.
    val node = new Node(baseTimestamp = 108)
    single(node, call(Some("0x60006000fd"), clears = false, "0x")) shouldBe ((BigInt(21006), BigInt(21006), Some(3)))
  }

  it should "report an exceptional halt at the whole gas limit" taggedAs (UnitTest, RPCTest) in {
    // JUMPDEST, PUSH1 0, JUMP spins until the gas is gone.
    val node = new Node(baseTimestamp = 108)
    single(node, call(Some("0x5b600056"), clears = false, "0x")) shouldBe ((
      BigInt(100000),
      BigInt(100000),
      Some(-32015)
    ))
  }

  it should "never report maxUsedGas below gasUsed" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(baseTimestamp = 108)
    val requests = Seq(
      call(Some(ClearerCode), clears = true, "0x"),
      call(Some(ClearerCode), clears = true, nonZeros(200)),
      call(Some(ClearerCode), clears = true, nonZeros(1000)),
      call(None, clears = false, nonZeros(1000)),
      call(Some("0x60006000fd"), clears = false, "0x")
    )
    requests.foreach { request =>
      val (gasUsed, maxUsedGas, _) = single(node, request)
      maxUsedGas should be >= gasUsed
    }
  }
