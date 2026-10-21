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
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1660: eth_simulateV1 behaviours settled against go-ethereum (internal/ethapi/simulate.go, api.go,
  * override/override.go; master 5f62e55c9). Each test names the geth line it pins.
  *
  * Chain: the Amsterdam fixture schedule (ETH; Cancun 60, Prague 120, Osaka 180, Amsterdam 360) with the base block at
  * timestamp 300, so a simulated block is an Osaka block.
  */
// scalastyle:off magic.number
class EthSimulateGethParitySpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(Array.fill[Byte](32)(0))

  private val From = "0xc000000000000000000000000000000000000000"
  private val To = "0xc100000000000000000000000000000000000000"
  private val Funded = s""""stateOverrides":{"$From":{"balance":"0x3635c9adc5dea00000"}}"""

  /** `baseMiner` is the base block's beneficiary. */
  private class Node(
      config: BlockchainConfig = amsterdamConfig,
      baseMiner: ByteString = ByteString(new Array[Byte](20))
  ) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = Difficulty(0),
          beneficiary = baseMiner,
          stateRoot = TrieRoot(emptyRoot),
          gasLimit = GasAmount(30_000_000),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(300),
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
      config
    )

    def run(payload: String): Either[JsonRpcError, (EthSimulateResponse, JValue)] =
      for
        req <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
          Some(JArray(List(parse(payload), JString("latest"))))
        )
        res <- service.ethSimulate(req).unsafeRunSync()
      yield (res, EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(res))

    def blocks(payload: String): (EthSimulateResponse, List[JValue]) =
      run(payload) match
        case Right((res, JArray(bs))) => (res, bs)
        case other                    => fail(s"expected simulated blocks, got $other")

    def error(payload: String): (Int, String) =
      run(payload) match
        case Left(err)  => (err.code, err.message)
        case Right(res) => fail(s"expected an error, got $res")

  private def str(v: JValue): String = v match
    case JString(s) => s
    case other      => fail(s"expected a string, got $other")

  private def arr(v: JValue): List[JValue] = v match
    case JArray(xs) => xs
    case other      => fail(s"expected an array, got $other")

  // ── 9: empty input (api.go:886-887) ─────────────────────────────────────────

  "eth_simulateV1" should "refuse an empty blockStateCalls with -32602 \"empty input\" (api.go:886-887)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    new Node().error("""{"blockStateCalls":[]}""") shouldBe (-32602, "empty input")
  }

  it should "refuse a missing blockStateCalls the same way (geth's nil slice)" taggedAs (UnitTest, RPCTest) in {
    new Node().error("""{}""") shouldBe (-32602, "empty input")
  }

  // ── 3-4: state override validation (override.go:80-110) ────────────────────

  it should "reject an account override with both state and stateDiff, -32000 (override.go:108-110)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$To":{"state":{},"stateDiff":{}}},"calls":[{"from":"$From","to":"$To"}]}]}"""
    new Node().error(payload) shouldBe (-32000, s"account $To has both 'state' and 'stateDiff'")
  }

  it should "reject moving a precompile onto its own address, -32000 (override.go:84-88)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val one = "0x0000000000000000000000000000000000000001"
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$one":{"movePrecompileToAddress":"$one"}},"calls":[{"from":"$From","to":"$one"}]}]}"""
    new Node().error(payload) shouldBe (-32000, s"account $one is already overridden")
  }

  it should "reject moving a precompile onto any other overridden address, -32000 (override.go:86)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val one = "0x0000000000000000000000000000000000000001"
    val other = "0x0000000000000000000000000000000000000077"
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$one":{"movePrecompileToAddress":"$other"},"$other":{"balance":"0x1"}},"calls":[{"from":"$From","to":"$one"}]}]}"""
    new Node().error(payload) shouldBe (-32000, s"account $other is already overridden")
  }

  it should "still move a precompile to a fresh address" taggedAs (UnitTest, RPCTest) in {
    val one = "0x0000000000000000000000000000000000000001"
    val fresh = "0x00000000000000000000000000000000000000c0"
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$one":{"movePrecompileToAddress":"$fresh"}},"calls":[{"from":"$From","to":"$fresh"}]}]}"""
    new Node().run(payload).isRight shouldBe true
  }

  // ── 1: traceTransfers on a creation (geth's tracer `OnEnter` is given the created address) ──────────────

  /** Sender 0x6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0 at nonce 0 / 1 deploys to the widely published addresses. */
  it should "derive a creation's address from the sender and nonce" taggedAs UnitTest in {
    val sender = Address("0x6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0")
    createdContractAddress(sender, 0) shouldBe Address("0xcd234a471b72ba2f1ccf0a70fcaba648a5eecd8d")
    createdContractAddress(sender, 1) shouldBe Address("0x343c43a37d37dff08ae8c4a11544c718abb4fcf8")
  }

  it should "report a creation's traced transfer to the created address, not zero" taggedAs (UnitTest, RPCTest) in {
    val sender = "0x6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0"
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$sender":{"balance":"0x3635c9adc5dea00000"}},
         |"calls":[{"from":"$sender","input":"0x00","value":"0x1"}]}],"traceTransfers":true}""".stripMargin
    val (_, bs) = new Node().blocks(payload)
    val logs = arr(arr(bs.head \ "calls").head \ "logs")
    logs should have size 1
    arr(logs.head \ "topics").map(str) shouldBe List(
      "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
      "0x0000000000000000000000006ac7ea33f8831ea9dcc53393aaa88b25a785dbf0",
      "0x000000000000000000000000cd234a471b72ba2f1ccf0a70fcaba648a5eecd8d"
    )
  }

  // ── 7: default coinbase (simulate.go:680, `Coinbase: header.Coinbase`) ──────

  private val BaseMiner = "0x00000000000000000000000000000000000000aa"
  private val OtherMiner = "0x00000000000000000000000000000000000000bb"
  private def minerNode = new Node(baseMiner = ByteString(org.bouncycastle.util.encoders.Hex.decode(BaseMiner.drop(2))))

  it should "inherit the base block's miner as the default coinbase, through gap blocks" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val payload = """{"blockStateCalls":[{},{"blockOverrides":{"number":"0x21"}}]}"""
    val (_, bs) = minerNode.blocks(payload)
    bs.map(b => str(b \ "number")) shouldBe List("0x1e", "0x1f", "0x20", "0x21")
    bs.map(b => str(b \ "miner")) shouldBe List.fill(4)(BaseMiner)
  }

  it should "carry a feeRecipient override forward to later blocks" taggedAs (UnitTest, RPCTest) in {
    val payload =
      s"""{"blockStateCalls":[{"blockOverrides":{"feeRecipient":"$OtherMiner"}},{}]}"""
    val (_, bs) = minerNode.blocks(payload)
    bs.map(b => str(b \ "miner")) shouldBe List(OtherMiner, OtherMiner)
  }

  // ── 6: per-call gas against the block and the request budget (simulate.go:531-555) ──────────────────────

  private def gasBlock(gasLimit: String, calls: String*): String =
    s"""{"blockStateCalls":[{"blockOverrides":{"gasLimit":"$gasLimit"},$Funded,"calls":[${calls.mkString(
        ","
      )}]}],"returnFullTransactions":true}"""

  it should "refuse a call that asks for more gas than the block has, -38015 (simulate.go:547-548)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    new Node().error(gasBlock("0x10000", s"""{"from":"$From","to":"$To","gas":"0x20000"}""")) shouldBe
      (-38015, "block gas limit reached: remaining: 65536, required: 131072")
  }

  it should "count the gas earlier calls used against the block (remaining, not the limit)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    new Node().error(
      gasBlock(
        "0x10000",
        s"""{"from":"$From","to":"$To","gas":"0x5208"}""",
        s"""{"from":"$From","to":"$To","gas":"0x10000"}"""
      )
    ) shouldBe (-38015, "block gas limit reached: remaining: 44536, required: 65536")
  }

  it should "clamp a call's gas to the request-wide 50M budget, and the transaction carries the clamped gas" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // block limit 0x4000000 = 67,108,864; asked 0x3000000 = 50,331,648 > 50,000,000 (0x2faf080)
    val (_, bs) =
      new Node().blocks(gasBlock("0x4000000", s"""{"from":"$From","to":"$To","gas":"0x3000000"}"""))
    val tx = arr(bs.head \ "transactions").head
    str(tx \ "gas") shouldBe "0x2faf080"
  }

  it should "say so when a clamped call fails in the VM (simulate.go:470-474)" taggedAs (UnitTest, RPCTest) in {
    // 0x5b600056: JUMPDEST PUSH1 0 JUMP — spins until the gas is gone
    val payload =
      s"""{"blockStateCalls":[{"blockOverrides":{"gasLimit":"0x4000000"},
         |"stateOverrides":{"$From":{"balance":"0x3635c9adc5dea00000"},"$To":{"code":"0x5b600056"}},
         |"calls":[{"from":"$From","to":"$To","gas":"0x3000000"}]}]}""".stripMargin
    val (_, bs) = new Node().blocks(payload)
    val error = arr(bs.head \ "calls").head \ "error"
    str(error \ "message") shouldBe "out of gas (gas limit was capped by the RPC server's global gas cap)"
  }

  it should "leave an unclamped VM failure's message alone" taggedAs (UnitTest, RPCTest) in {
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$From":{"balance":"0x3635c9adc5dea00000"},"$To":{"code":"0x5b600056"}},
         |"calls":[{"from":"$From","to":"$To","gas":"0x30000"}]}]}""".stripMargin
    val (_, bs) = new Node().blocks(payload)
    str((arr(bs.head \ "calls").head \ "error") \ "message") shouldBe "out of gas"
  }
