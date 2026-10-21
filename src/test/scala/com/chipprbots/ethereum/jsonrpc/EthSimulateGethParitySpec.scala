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

