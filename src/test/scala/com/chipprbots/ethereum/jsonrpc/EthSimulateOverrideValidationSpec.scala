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
