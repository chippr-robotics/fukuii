package com.chipprbots.ethereum.jsonrpc

import cats.effect.unsafe.implicits.global

import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** execution-apis `tests/eth_simulateV1/ethSimulate-{unknown-block-override-key,unknown-account-override-key,
  * difficulty-override-after-merge}-32602.io`: keys the schema does not define, and a post-merge `difficulty` override,
  * are rejected with -32602 and the vectors' messages.
  */
class EthSimulateOverrideValidationSpec extends AnyWordSpec with Matchers:

  private class PostMergeNode extends EphemBlockchainTestSetup:
    val base: Block = Block(
      Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(60),
        difficulty = Difficulty(0),
        gasLimit = GasAmount(30_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = BlockHeader.HeaderExtraFields.HefPostShanghai(7, EmptyWithdrawalsRoot)
      ),
      BlockBody.empty
    )
    blockchainWriter.save(base, Nil, ChainWeight.zero, saveAsBestBlock = true)
    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      blockchainConfig
    )

  private val Addr = "0xc100000000000000000000000000000000000000"
  private val From = "0xc000000000000000000000000000000000000000"

  private def decode(payload: String) =
    EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
      Some(JArray(List(parse(payload), JString("latest"))))
    )

  private val Call = s"""{"from":"$From","to":"$Addr"}"""

  "eth_simulateV1 decoding" should {
    "reject an unknown block override key (-32602)" taggedAs UnitTest in {
      val r = decode(s"""{"blockStateCalls":[{"blockOverrides":{"bogusField":"0x1"},"calls":[$Call]}]}""")
      r.left.map(e => (e.code, e.message)) shouldBe
        Left((-32602, "invalid argument 0: unknown field \"bogusField\" in block override"))
    }

    "reject an unknown account override key (-32602)" taggedAs UnitTest in {
      val r = decode(
        s"""{"blockStateCalls":[{"calls":[$Call],"stateOverrides":{"$Addr":{"balance":"0x1","bogusField":"0x1"}}}]}"""
      )
      r.left.map(e => (e.code, e.message)) shouldBe
        Left((-32602, s"invalid argument 0: unknown field \"bogusField\" in state override for $Addr"))
    }

    "still accept every schema-defined key" taggedAs UnitTest in {
      val r = decode(
        s"""{"blockStateCalls":[{"blockOverrides":{"number":"0x1","time":"0x1","gasLimit":"0x1","feeRecipient":"$Addr","prevRandao":"0x00","baseFeePerGas":"0x1","blobBaseFee":"0x1","withdrawals":[]},"stateOverrides":{"$Addr":{"balance":"0x1","nonce":"0x1","code":"0x00","stateDiff":{},"movePrecompileToAddress":"$From"}},"calls":[$Call]}]}"""
      )
      r.isRight shouldBe true
    }
  }

  // #1727 section 1: a decoded quantity is bounded as go-ethereum's hexutil types bound it. BlockOverrides.Number,
  // Difficulty, BaseFeePerGas, BlobBaseFee and OverrideAccount.Balance are hexutil.Big (at most 256 bits);
  // BlockOverrides.Time and GasLimit, OverrideAccount.Nonce and a Withdrawal's index, validator and amount are
  // hexutil.Uint64 (at most 64 bits). A value past its bound is -32602, not a number that wraps further down.
  private def maxOf(bits: Int): String = "0x" + "f" * (bits / 4)
  private def oneOver(bits: Int): String = "0x1" + "0" * (bits / 4)

  private def blockOverride(field: String, value: String) =
    s"""{"blockStateCalls":[{"blockOverrides":{"$field":"$value"},"calls":[$Call]}]}"""
  private def accountOverride(field: String, value: String) =
    s"""{"blockStateCalls":[{"stateOverrides":{"$Addr":{"$field":"$value"}},"calls":[$Call]}]}"""
  private def withdrawal(field: String, value: String) =
    val fields = Map("index" -> "0x0", "validatorIndex" -> "0x1", "amount" -> "0x2", field -> value)
    val body = fields.map { case (k, v) => s""""$k":"$v"""" }.mkString(",")
    s"""{"blockStateCalls":[{"blockOverrides":{"withdrawals":[{"address":"$Addr",$body}]},"calls":[$Call]}]}"""

  private val Big256Fields = Seq("number", "difficulty", "baseFeePerGas", "blobBaseFee")
  private val Uint64Fields = Seq("time", "gasLimit")

  "eth_simulateV1 decoding of quantity bounds" should {
    for field <- Big256Fields do
      s"accept a 256-bit blockOverrides.$field and reject one bit more (-32602)" taggedAs UnitTest in {
        decode(blockOverride(field, maxOf(256))).isRight shouldBe true
        val r = decode(blockOverride(field, oneOver(256)))
        r.left.map(e => (e.code, e.message)) shouldBe
          Left((-32602, s"invalid argument 0: invalid $field in block override: hex number > 256 bits"))
      }

    for field <- Uint64Fields do
      s"accept a 64-bit blockOverrides.$field and reject one bit more (-32602)" taggedAs UnitTest in {
        decode(blockOverride(field, maxOf(64))).isRight shouldBe true
        val r = decode(blockOverride(field, oneOver(64)))
        r.left.map(e => (e.code, e.message)) shouldBe
          Left((-32602, s"invalid argument 0: invalid $field in block override: hex number > 64 bits"))
      }

    "accept a 256-bit state override balance and reject one bit more (-32602)" taggedAs UnitTest in {
      decode(accountOverride("balance", maxOf(256))).isRight shouldBe true
      val r = decode(accountOverride("balance", oneOver(256)))
      r.left.map(e => (e.code, e.message)) shouldBe
        Left((-32602, s"invalid argument 0: invalid balance in state override for $Addr: hex number > 256 bits"))
    }

    "accept a 64-bit state override nonce and reject one bit more (-32602)" taggedAs UnitTest in {
      decode(accountOverride("nonce", maxOf(64))).isRight shouldBe true
      val r = decode(accountOverride("nonce", oneOver(64)))
      r.left.map(e => (e.code, e.message)) shouldBe
        Left((-32602, s"invalid argument 0: invalid nonce in state override for $Addr: hex number > 64 bits"))
    }

    for field <- Seq("index", "validatorIndex", "amount") do
      s"accept a 64-bit withdrawal $field and reject one bit more (-32602)" taggedAs UnitTest in {
        decode(withdrawal(field, maxOf(64))).isRight shouldBe true
        val r = decode(withdrawal(field, oneOver(64)))
        r.left.map(_.code) shouldBe Left(-32602)
        r.left.map(_.message) shouldBe
          Left(s"invalid argument 0: invalid $field in block override: hex number > 64 bits")
      }

    for field <- Seq("state", "stateDiff") do
      s"accept 256-bit $field slot keys and values and reject one bit more (-32602)" taggedAs UnitTest in {
        def slots(key: String, value: String) =
          s"""{"blockStateCalls":[{"stateOverrides":{"$Addr":{"$field":{"$key":"$value"}}},"calls":[$Call]}]}"""
        decode(slots(maxOf(256), maxOf(256))).isRight shouldBe true
        decode(slots(oneOver(256), "0x1")).left.map(_.code) shouldBe Left(-32602)
        decode(slots("0x1", oneOver(256))).left.map(_.code) shouldBe Left(-32602)
      }

    "refuse a multi-megabyte quantity without parsing it" taggedAs UnitTest in {
      // BigInt parses a hex string in quadratic time: two million digits take longer than any request should
      val start = System.nanoTime()
      val r = decode(accountOverride("balance", "0x1" + "0" * 2_000_000))
      val elapsedMs = (System.nanoTime() - start) / 1_000_000
      r.left.map(_.code) shouldBe Left(-32602)
      elapsedMs should be < 5000L
    }
  }

  "eth_simulateV1 execution" should {
    "reject a difficulty override after the merge (-32602)" taggedAs UnitTest in {
      val node = new PostMergeNode
      val req = EthSimulateRequest(
        Seq(BlockStateCall(blockOverrides = Some(BlockOverrides(difficulty = Some(BigInt(1))))))
      )
      val r = node.service.ethSimulate(req).unsafeRunSync()
      r.left.map(e => (e.code, e.message)) shouldBe
        Left((-32602, "block 61: difficulty override is not allowed after the merge"))
    }
  }
