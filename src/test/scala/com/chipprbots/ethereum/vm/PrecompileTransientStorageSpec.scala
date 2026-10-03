package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.StorageKey
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1439: a successful CALL to a precompile must leave the caller's EIP-1153 transient storage as it was.
  *
  * A successful CALL-family frame ADOPTS the child's transient storage, and the precompile frame result used to carry
  * none, so `TSTORE(0, 42); CALL 0x04; TLOAD(0)` read 0. execution-specs keeps transient storage per transaction and
  * only rolls it back when a frame fails, so the reference reads 42 (`process_message`: `begin_transaction` /
  * `rollback_transaction` on the state and the transient storage together; a precompile touches neither).
  *
  * The probe returns two words: the TLOAD result, then the CALL's success flag.
  */
// scalastyle:off magic.number
class PrecompileTransientStorageSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  type W = InMemoryWorldStateProxy
  type S = InMemoryWorldStateProxyStorage

  import Assembly.*

  private val Caller: Address = Address(0x7153)

  /** `TSTORE(0, 42); success := CALL(0xffff, target, 0, 0, 0, 0, 0); return (TLOAD(0), success)`. */
  private def probe(target: Address): ByteString =
    Assembly(
      PUSH1,
      42,
      PUSH1,
      0,
      TSTORE,
      PUSH1,
      0, // retSize
      PUSH1,
      0, // retOffset
      PUSH1,
      0, // argsSize
      PUSH1,
      0, // argsOffset
      PUSH1,
      0, // value
      PUSH1,
      target.bytes.last.toInt, // a precompile's address fits one byte
      PUSH2,
      0xff,
      0xff,
      CALL,
      PUSH1,
      32,
      MSTORE, // mem[32..64] = success
      PUSH1,
      0,
      TLOAD,
      PUSH1,
      0,
      MSTORE, // mem[0..32] = TLOAD(0)
      PUSH1,
      64,
      PUSH1,
      0,
      RETURN
    ).code

  private def word(n: Int): ByteString = UInt256(n).bytes

  /** TLOAD read 42 and the CALL succeeded. */
  private val TransientStorageKept: ByteString = word(42) ++ word(1)

  // ── ETH: timestamp forks (Shanghai 0 / Cancun 60 / Prague 120 / Osaka 180 / Amsterdam 360) ──────────────────────

  private def runEth(config: BlockchainConfig, header: BlockHeader, code: ByteString): ProgramResult[W, S] =
    val world = block45World
      .saveAccount(Caller, Account(nonce = UInt256(1), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(Caller, code)
    val stx = dynamicFeeTx(Some(Caller), value = 0, gasLimit = 1_000_000, config = config)
    val evmConfig = EvmConfig.forBlock(header.number.value, header.unixTimestamp, config)
    new VMImpl().run(ProgramContext[W, S](stx, header, senderAddress, world, evmConfig).copy(startGas = 500_000))

  private val ethForks: Seq[(String, BlockchainConfig, BlockHeader)] = Seq(
    ("Cancun", preAmsterdamConfig, preAmsterdamHeader(500, 60)),
    ("Prague", preAmsterdamConfig, preAmsterdamHeader(500, 120)),
    ("Osaka", preAmsterdamConfig, preAmsterdamHeader(500, 180)),
    ("Amsterdam", amsterdamConfig, amsterdamHeader(500, 360))
  )

  ethForks.foreach { case (fork, config, header) =>
    s"ETH $fork: TLOAD after a successful precompile CALL" should "read the value TSTORE wrote before the call" taggedAs (
      VMTest,
      ConsensusTest
    ) in {
      val result = runEth(config, header, probe(PrecompiledContracts.IdAddr))

      result.error shouldBe None
      result.returnData shouldBe TransientStorageKept
    }

    it should "keep it across a precompile that is not the identity (sha256)" taggedAs (VMTest, ConsensusTest) in {
      val result = runEth(config, header, probe(PrecompiledContracts.Sha256Addr))

      result.error shouldBe None
      result.returnData shouldBe TransientStorageKept
    }
  }

  "ETH Shanghai (before EIP-1153)" should "still reject TSTORE, so the probe cannot observe transient storage" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = runEth(preAmsterdamConfig, preAmsterdamHeader(500, 30), probe(PrecompiledContracts.IdAddr))

    result.error shouldBe Some(InvalidOpCode(TSTORE.code))
  }

  // ── ETC: block forks (EtcOlympiaOpCodes carries TLOAD/TSTORE; Spiral does not) ─────────────────────────────────

  private def runEtc(
      config: EvmConfig,
      blockNumber: BigInt,
      code: ByteString
  ): ProgramResult[MockWorldState, MockStorage] =
    val world = MockWorldState()
      .saveAccount(Caller, Account(balance = UInt256(1000), nonce = 1))
      .saveCode(Caller, code)
    val context = ProgramContext[MockWorldState, MockStorage](
      callerAddr = Address(0xca11),
      originAddr = Address(0xca11),
      recipientAddr = Some(Caller),
      gasPrice = 1,
      startGas = 500_000,
      inputData = ByteString.empty,
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

  "ETC Olympia: TLOAD after a successful precompile CALL" should "read the value TSTORE wrote before the call" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val result = runEtc(
      EvmConfig.OlympiaConfigBuilder(Fixtures.blockchainConfig),
      Fixtures.OlympiaBlockNumber,
      probe(PrecompiledContracts.IdAddr)
    )

    result.error shouldBe None
    result.returnData shouldBe TransientStorageKept
  }

  "ETC Spiral (before Olympia)" should "still reject TSTORE, so the probe cannot observe transient storage" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val result = runEtc(
      EvmConfig.SpiralConfigBuilder(Fixtures.blockchainConfig),
      Fixtures.SpiralBlockNumber,
      probe(PrecompiledContracts.IdAddr)
    )

    result.error shouldBe Some(InvalidOpCode(TSTORE.code))
  }

  // ── The frame result itself ────────────────────────────────────────────────────────────────────────────────────

  private val handed: Map[(Address, StorageKey), BigInt] = Map((Caller, StorageKey(BigInt(7))) -> BigInt(42))

  private def precompileFrame(target: Address, startGas: BigInt): ProgramContext[MockWorldState, MockStorage] =
    val world = MockWorldState()
    ProgramContext[MockWorldState, MockStorage](
      callerAddr = Caller,
      originAddr = Caller,
      recipientAddr = Some(target),
      gasPrice = 1,
      startGas = startGas,
      inputData = ByteString(1, 2, 3),
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader = BlockFixtures.ValidBlock.header.copy(number = BlockNumber(Fixtures.OlympiaBlockNumber)),
      callDepth = 1,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = EvmConfig.OlympiaConfigBuilder(Fixtures.blockchainConfig),
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty,
      transientStorage = handed
    )

  "a precompile frame" should "hand back the transient storage it was given when it succeeds" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = PrecompiledContracts.run(precompileFrame(PrecompiledContracts.IdAddr, 100))

    result.error shouldBe None
    result.transientStorage shouldBe handed
  }

  it should "hand it back when it fails too (the caller ignores it then, but the frame never touched it)" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = PrecompiledContracts.run(precompileFrame(PrecompiledContracts.EcDsaRecAddr, 0))

    result.error shouldBe Some(OutOfGas)
    result.transientStorage shouldBe handed
  }
