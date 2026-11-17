package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1727 section 1: eth_simulateV1 is bounded the way go-ethereum bounds it, before it builds anything.
  *
  * go-ethereum v1.17.9, internal/ethapi: `api.go` SimulateV1 (`maxSimulateBlocks` 256, `maxSimulateCallsPerBlock` 5000,
  * `maxSimulateTotalCalls` 10000, all -38026) and `simulate.go` sanitizeChain (`Number - base.Number > 256` as big
  * integers, -38026 "too many blocks").
  *
  * The bug: the gap between a block override and the base block was narrowed with `.toInt` before the 256 check, so a
  * gap of 2^32 + 1 counted as 1 and the gap loop then built 2^32 blocks. Every request here must return, and it must
  * return at once: a request that spins fails the bound on [[Node.run]], it does not hang the suite.
  *
  * Chain: the Amsterdam fixture schedule with the base block at number 29, timestamp 300 (as
  * EthSimulateGethParitySpec).
  */
// scalastyle:off magic.number
class EthSimulateLimitsSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val BaseNumber = BigInt(29)

  /** How long a request that only has to be refused may take. It validates and returns: milliseconds. */
  private val PromptLimit = 10.seconds

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(Array.fill[Byte](32)(0))

  private val From = "0xc000000000000000000000000000000000000000"
  private val To = "0xc100000000000000000000000000000000000000"
  private val Funded = s""""$From":{"balance":"0x3635c9adc5dea00000"}"""

  private class Node extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = amsterdamConfig

    // The base state holds the four request contracts, as a real chain's does: past timestamp 360 the simulated blocks
    // are Amsterdam blocks, which run the request system calls and fail without them (see RequestContractStubs).
    private val baseStateRoot: ByteString =
      val empty = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      val stubs = RequestContractStubs.Addresses.foldLeft(empty) { (world, address) =>
        world
          .saveAccount(address, Account(codeHash = CodeHash(kec256(RequestContractStubs.Stop))))
          .saveCode(address, RequestContractStubs.Stop)
      }
      InMemoryWorldStateProxy.persistState(stubs).stateRootHash

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(BaseNumber),
          difficulty = Difficulty(0),
          beneficiary = ByteString(new Array[Byte](20)),
          stateRoot = TrieRoot(baseStateRoot),
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
      amsterdamConfig
    )

    /** Decodes and simulates, failing the test (TimeoutException) if the simulation outlives [[PromptLimit]]. */
    def run(payload: String): Either[JsonRpcError, EthSimulateResponse] =
      for
        req <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
          Some(JArray(List(parse(payload), JString("latest"))))
        )
        res <- Await.result(service.ethSimulate(req).unsafeToFuture(), PromptLimit)
      yield res

    def blocks(payload: String): Seq[SimulateBlockResult] =
      run(payload) match
        case Right(res) => res.blocks
        case Left(err)  => fail(s"expected simulated blocks, got ${err.code} ${err.message}")

    def error(payload: String): (Int, String) =
      run(payload) match
        case Left(err)  => (err.code, err.message)
        case Right(res) => fail(s"expected an error, got ${res.blocks.size} blocks")

  private def hex(n: BigInt): String = "0x" + n.toString(16)

  private def numbered(numbers: BigInt*): String =
    numbers.map(n => s"""{"blockOverrides":{"number":"${hex(n)}"}}""").mkString("""{"blockStateCalls":[""", ",", "]}")

  private val TooManyBlocks = (-38026, "too many blocks")

  // ── blocks: Number - base.Number <= 256 (simulate.go sanitizeChain), as big integers ───────────────────────────

  "eth_simulateV1" should "build exactly 256 blocks when a block override is 256 past the base" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val bs = new Node().blocks(numbered(BaseNumber + 256))
    bs should have size 256
    bs.map(_.header.number.value) shouldBe (1 to 256).map(BaseNumber + _)
  }

  it should "refuse a block override 257 past the base: -38026 \"too many blocks\"" taggedAs (UnitTest, RPCTest) in {
    new Node().error(numbered(BaseNumber + 257)) shouldBe TooManyBlocks
  }

  it should "count the gap across blocks: 200 + 57 is 257" taggedAs (UnitTest, RPCTest) in {
    new Node().error(numbered(BaseNumber + 200, BaseNumber + 257)) shouldBe TooManyBlocks
  }

  it should "build 256 blockStateCalls and refuse 257 (api.go: len(BlockStateCalls) > maxSimulateBlocks)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    def empties(n: Int) = Seq.fill(n)("{}").mkString("""{"blockStateCalls":[""", ",", "]}")
    new Node().blocks(empties(256)) should have size 256
    new Node().error(empties(257)) shouldBe TooManyBlocks
  }

  /** A gap that a 32-bit conversion reads as small, zero or negative: each of these once passed the 256 check. */
  private val WrappingGaps: Seq[(String, BigInt)] = Seq(
    "2^32 + 1 (counted as 1)" -> ((BigInt(1) << 32) + 1),
    "2^32 + 256 (counted as exactly the limit)" -> ((BigInt(1) << 32) + 256),
    "2^32 (counted as 0)" -> (BigInt(1) << 32),
    "2^31 (counted as Int.MinValue)" -> (BigInt(1) << 31),
    "2^63" -> (BigInt(1) << 63),
    "2^64 + 1" -> ((BigInt(1) << 64) + 1),
    "2^255" -> (BigInt(1) << 255)
  )

  for (name, gap) <- WrappingGaps do
    it should s"refuse a block override whose gap is $name, without building a block" taggedAs (UnitTest, RPCTest) in {
      new Node().error(numbered(BaseNumber + gap)) shouldBe TooManyBlocks
    }

  it should "refuse the largest block number a hexutil.Big carries (2^256 - 1)" taggedAs (UnitTest, RPCTest) in {
    new Node().error(numbered((BigInt(1) << 256) - 1)) shouldBe TooManyBlocks
  }

  it should "refuse two blocks whose 32-bit gaps cancel (2^31 then 2^31 again)" taggedAs (UnitTest, RPCTest) in {
    val half = BigInt(1) << 31
    new Node().error(numbered(BaseNumber + half, BaseNumber + 2 * half)) shouldBe TooManyBlocks
  }

  it should "return promptly for a gap that wraps to one block, not run a gap loop" taggedAs (UnitTest, RPCTest) in {
    val start = System.nanoTime()
    new Node().error(numbered(BaseNumber + (BigInt(1) << 32) + 1)) shouldBe TooManyBlocks
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    // the node setup and the refusal; generous because the loop it replaces never returns
    elapsedMs should be < PromptLimit.toMillis
  }

  it should "refuse an over-gap block before the timestamp check, in the order of geth sanitizeChain" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // time 0x1 is not above the base block timestamp 300 (-38021 on its own); the block count is checked first
    val payload =
      s"""{"blockStateCalls":[{"blockOverrides":{"number":"${hex(BaseNumber + 300)}","time":"0x1"}}]}"""
    new Node().error(payload) shouldBe TooManyBlocks
  }

  it should "still refuse a block number that does not increase with -38020, ahead of the gap check" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    new Node().error(numbered(BaseNumber)) shouldBe
      (-38020, s"block numbers must be in order: $BaseNumber <= $BaseNumber")
  }

  // ── calls: 5000 per block, 10000 in all (api.go SimulateV1) ──────────────────────────────────────────────────

  private def callsOf(n: Int): String =
    Seq.fill(n)(s"""{"from":"$From","to":"$To","gas":"0x5208"}""").mkString(",")

  private def blockOfCalls(n: Int): String = s"""{"stateOverrides":{$Funded},"calls":[${callsOf(n)}]}"""

  private def request(blocks: String*): String = blocks.mkString("""{"blockStateCalls":[""", ",", "]}")

  it should "refuse 5001 calls in a block: -38026" taggedAs (UnitTest, RPCTest) in {
    new Node().error(request(blockOfCalls(5001))) shouldBe (-38026, "too many calls in block: 5001 > 5000")
  }

  it should "let 5000 calls in a block through the cap" taggedAs (UnitTest, RPCTest) in {
    // The block gas of 30M stops the run well short of 5000 transfers (21000 each), so the outcome is not asserted:
    // only that the call cap is not what ended it.
    new Node().run(request(blockOfCalls(5000))) match
      case Left(err)  => err.code should not be -38026
      case Right(res) => res.blocks should have size 1
  }

  it should "refuse more than 10000 calls in all: -38026" taggedAs (UnitTest, RPCTest) in {
    new Node().error(request(blockOfCalls(5000), blockOfCalls(5000), blockOfCalls(1))) shouldBe
      (-38026, "too many calls: 10001 > 10000")
  }

  it should "check the calls before the block gap, as api.go checks them before sanitizeChain" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val tooManyCalls = s"""{"blockOverrides":{"number":"${hex(BaseNumber + 300)}"},"calls":[${callsOf(5001)}]}"""
    new Node().error(request(tooManyCalls)) shouldBe (-38026, "too many calls in block: 5001 > 5000")
  }

  // ── Error(string) revert data: the length word is a uint256 (decodeErrorString narrowed it to an Int) ──────────

  private def word(n: BigInt): String = ("0" * 64 + n.toString(16)).takeRight(64)

  /** Code that REVERTs with the 100-byte Error(string) payload: selector, offset 0x20, `length`, "hello" padded. */
  private def revertingWith(length: BigInt): String =
    val payload = "08c379a0" + word(0x20) + word(length) + "68656c6c6f" + "00" * 27
    // PUSH2 100 PUSH1 14 PUSH1 0 CODECOPY; PUSH2 100 PUSH1 0 REVERT (14 bytes), then the payload
    "0x" + "610064" + "600e" + "6000" + "39" + "610064" + "6000" + "fd" + payload

  private def revertMessage(length: BigInt): Option[String] =
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{$Funded,"$To":{"code":"${revertingWith(length)}"}},
         |"calls":[{"from":"$From","to":"$To","gas":"0x30000"}]}]}""".stripMargin
    new Node().blocks(payload).head.calls.head.error.map(_.message)

  it should "decode an Error(string) revert whose length word fits" taggedAs (UnitTest, RPCTest) in {
    revertMessage(5) shouldBe Some("execution reverted: hello")
  }

  it should "not read an Error(string) length of 2^32 + 5 as 5" taggedAs (UnitTest, RPCTest) in {
    revertMessage((BigInt(1) << 32) + 5) shouldBe Some("execution reverted")
  }

  it should "not read an Error(string) length of 2^31 - 32, whose Int end offset overflows, as empty" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    revertMessage(BigInt(0x7fffffe0L)) shouldBe Some("execution reverted")
  }

  it should "not read an Error(string) length of 2^256 - 1 as a string" taggedAs (UnitTest, RPCTest) in {
    revertMessage((BigInt(1) << 256) - 1) shouldBe Some("execution reverted")
  }
