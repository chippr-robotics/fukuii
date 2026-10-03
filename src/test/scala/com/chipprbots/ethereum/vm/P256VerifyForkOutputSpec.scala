package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1439: what a contract observes after calling P256VERIFY (0x100) — RETURNDATASIZE and the CALL's success flag — on
  * every fork where the precompile exists, and on the fork before it.
  *
  * execution-specs `p256verify.py` and go-ethereum answer a signature that does not verify, and any input that is not
  * exactly 160 bytes, with a successful call and NO output. fukuii answered a 32-byte zero word for the first, and
  * verified the first 160 bytes of a longer input. The EEST signature of the difference is a gas mismatch: a contract
  * that records RETURNDATASIZE writes a non-zero slot fukuii should not have written.
  */
// scalastyle:off magic.number
class P256VerifyForkOutputSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  type W = InMemoryWorldStateProxy
  type S = InMemoryWorldStateProxyStorage

  import Assembly.*

  private val Caller: Address = Address(0x256a)

  /** `CALL(0xffff, 0x100, 0, 0, CALLDATASIZE, 0, 0)` on the calldata; returns (RETURNDATASIZE, success). */
  private val probe: ByteString = Assembly(
    CALLDATASIZE,
    PUSH1,
    0,
    PUSH1,
    0,
    CALLDATACOPY, // mem[0..size] = calldata
    PUSH1,
    0, // retSize
    PUSH1,
    0, // retOffset
    CALLDATASIZE, // argsSize
    PUSH1,
    0, // argsOffset
    PUSH1,
    0, // value
    PUSH2,
    0x01,
    0x00, // P256VERIFY
    PUSH2,
    0xff,
    0xff,
    CALL,
    PUSH1,
    32,
    MSTORE, // mem[32..64] = success
    RETURNDATASIZE,
    PUSH1,
    0,
    MSTORE, // mem[0..32] = RETURNDATASIZE
    PUSH1,
    64,
    PUSH1,
    0,
    RETURN
  ).code

  private def h(s: String): ByteString = ByteString(Hex.decode(s))

  // Wycheproof ECDSA P-256 SHA-256 #1 (valid) and #3 (r and s modified: does not verify).
  private val valid: ByteString = h(
    "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" +
      "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18" +
      "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76" +
      "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" +
      "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"
  )
  private val notVerifying: ByteString = h(
    "bb5a52f42f9c9261ed4361f59422a1e30036e7c32b270c8807a419feca605023" +
      "d45c5740946b2a147f59262ee6f5bc90bd01ed280528b62b3aed5fc93f06f739" +
      "b329f479a2bbd0a5c384ee1493b1f5186a87139cac5df4087c134b49156847db" +
      "2927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" +
      "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"
  )
  private val tooLong: ByteString = valid ++ ByteString(0)

  private def observed(returnDataSize: Int): ByteString = UInt256(returnDataSize).bytes ++ UInt256(1).bytes

  // ── ETH ──────────────────────────────────────────────────────────────────────────────────────────────────────────

  private def runEth(config: BlockchainConfig, header: BlockHeader, input: ByteString): ProgramResult[W, S] =
    val world = block45World
      .saveAccount(Caller, Account(nonce = UInt256(1), balance = UInt256(0), codeHash = CodeHash(kec256(probe))))
      .saveCode(Caller, probe)
    val stx = dynamicFeeTx(Some(Caller), value = 0, gasLimit = 1_000_000, payload = input, config = config)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, config)
    new VMImpl().run(ProgramContext[W, S](stx, header, senderAddress, world, evmConfig).copy(startGas = 500_000))

  private val withPrecompile: Seq[(String, BlockchainConfig, BlockHeader)] = Seq(
    ("Osaka", preAmsterdamConfig, preAmsterdamHeader(500, 180)),
    ("Amsterdam", amsterdamConfig, amsterdamHeader(500, 360))
  )

  withPrecompile.foreach { case (fork, config, header) =>
    s"ETH $fork: P256VERIFY" should "return no output for a signature that does not verify" taggedAs (
      VMTest,
      ConsensusTest
    ) in {
      val result = runEth(config, header, notVerifying)
      result.error shouldBe None
      result.returnData shouldBe observed(0)
    }

    it should "return no output for a 161-byte input whose first 160 bytes verify" taggedAs (VMTest, ConsensusTest) in {
      val result = runEth(config, header, tooLong)
      result.error shouldBe None
      result.returnData shouldBe observed(0)
    }

    it should "return the 32-byte 0x01 word for a signature that verifies" taggedAs (VMTest, ConsensusTest) in {
      val result = runEth(config, header, valid)
      result.error shouldBe None
      result.returnData shouldBe observed(32)
    }
  }

  "ETH Prague (before EIP-7951)" should "have no precompile at 0x100: every call returns no output, as before" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    for input <- Seq(valid, notVerifying, tooLong) do
      val result = runEth(preAmsterdamConfig, preAmsterdamHeader(500, 120), input)
      result.error shouldBe None
      result.returnData shouldBe observed(0)
  }

  // ── ETC ──────────────────────────────────────────────────────────────────────────────────────────────────────────

  private def runEtc(
      config: EvmConfig,
      blockNumber: BigInt,
      input: ByteString
  ): ProgramResult[MockWorldState, MockStorage] =
    val world = MockWorldState()
      .saveAccount(Caller, Account(balance = UInt256(1000), nonce = 1))
      .saveCode(Caller, probe)
    val context = ProgramContext[MockWorldState, MockStorage](
      callerAddr = Address(0xca11),
      originAddr = Address(0xca11),
      recipientAddr = Some(Caller),
      gasPrice = 1,
      startGas = 500_000,
      inputData = input,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader = BlockFixtures.ValidBlock.header.copy(number = BlockNumber(blockNumber)),
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = config,
      originalWorld = world,
      warmAddresses = Set(Caller),
      warmStorage = Set.empty
    )
    new VM[MockWorldState, MockStorage].run(context)

  "ETC Olympia: P256VERIFY (ECIP-1121)" should "return no output for a signature that does not verify" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val olympia = EvmConfig.OlympiaConfigBuilder(Fixtures.blockchainConfig)
    runEtc(olympia, Fixtures.OlympiaBlockNumber, notVerifying).returnData shouldBe observed(0)
    runEtc(olympia, Fixtures.OlympiaBlockNumber, tooLong).returnData shouldBe observed(0)
    runEtc(olympia, Fixtures.OlympiaBlockNumber, valid).returnData shouldBe observed(32)
  }

  "ETC Spiral (before Olympia)" should "have no precompile at 0x100: every call returns no output, as before" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val spiral = EvmConfig.SpiralConfigBuilder(Fixtures.blockchainConfig)
    for input <- Seq(valid, notVerifying, tooLong) do
      val result = runEtc(spiral, Fixtures.SpiralBlockNumber, input)
      result.error shouldBe None
      result.returnData shouldBe observed(0)
  }
