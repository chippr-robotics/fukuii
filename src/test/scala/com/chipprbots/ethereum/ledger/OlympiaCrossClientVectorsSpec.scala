package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Cross-client vectors for ETC Olympia (#1444), each one a whole transaction through `BlockPreparator`, with the
  * figure core-geth's Olympia branch (white-b0x/core-geth `537e8dd65c`) and Besu's ETC Olympia must agree on.
  *
  *   1. KZG point evaluation 0x0a is NOT a precompile on ETC Olympia (B-548). ECIP-1121 defers EIP-4844 ("ETC does not
  *      implement blobs"). Besu's `MainnetPrecompiledContracts.populateForOlympia` registers Istanbul's 0x01-0x09,
  *      BLS12-381 0x0b-0x11 and P256VERIFY 0x100, and no 0x0a; core-geth adds 0x0a only on an EIP-4844 transition,
  *      which no ETC config sets. So on ETC 0x0a is an empty account: cold to BALANCE (EIP-2929, 2,600) and a CALL to
  *      it succeeds with no output. fukuii once gave Olympia the ETH Osaka set: 0x0a was pre-warmed (100) and a CALL
  *      ran the KZG check, which fails on empty input and burns the gas passed. The ETH Cancun run of the same contract
  *      shows ETH is unchanged.
  *
  * 2. EIP-7623's floor applies AFTER the refund (B-548 "floor-vs-refund"): `max(gasUsed - refund, floor)`, as EIP-7623,
  * execution-specs, go-ethereum and Besu (`PragueGasCalculator`) do (`BlockPreparator.executionGasToPayToMiner`).
  * core-geth's Olympia branch floors first and refunds `gasUsed / 5` of the floored value (`state_transition.go`
  * 530-533, 567-573), so it charges less: the figures it gives are recorded beside each vector, for Gorgoroth or
  * private-net interop to catch. fukuii is the spec side; the fix belongs in core-geth.
  */
// scalastyle:off magic.number
class OlympiaCrossClientVectorsSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val OlympiaBlock = BigInt(1000)

  /** Every ETC fork through Mystique at genesis, Spiral at 900, Olympia at 1,000: the shape Eip7623FloorValiditySpec
    * uses.
    */
  private val etcConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          berlinBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 900,
          olympiaBlockNumber = OlympiaBlock
        )
      )

  private def etcHeader(number: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount.Zero,
      extraFields = if number >= OlympiaBlock then HefPostOlympia(BigInt(1)) else HefEmpty
    )

  private val Probe: Address = Address(Hex.decode("00000000000000000000000000000000000c0de1"))

  private def code(hex: String): ByteString = ByteString(Hex.decode(hex))

  /** A world with the funded sender and `Probe` holding `probeCode` and `slots`. */
  private def worldWith(probeCode: ByteString, slots: Map[BigInt, BigInt] = Map.empty) =
    val w = setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24))))
      .saveAccount(Probe, Account(nonce = UInt256(0), balance = UInt256(0), codeHash = CodeHash(kec256(probeCode))))
      .saveCode(Probe, probeCode)
    w.saveStorage(Probe, slots.foldLeft(w.getStorage(Probe)) { case (s, (k, v)) => s.store(k, v) })

  private def slot(result: TxResult, key: Int): BigInt = result.worldState.getStorage(Probe).load(BigInt(key))

  // ── 1. 0x0a on ETC Olympia ─────────────────────────────────────────────────────────────────────────────────────────

  /** slot 0 = the gas of `PUSH1 0x0a; BALANCE; POP; GAS` (BALANCE's access cost + 7), slot 1 = the same for 0x09
    * (BLAKE2F, a precompile on every fork here: the warm control), slot 2 = the success flag of a zero-value CALL to
    * 0x0a with empty input and 100,000 gas.
    *
    * {{{
    * GAS PUSH1 0x0a BALANCE POP GAS SWAP1 SUB PUSH1 0 SSTORE
    * GAS PUSH1 0x09 BALANCE POP GAS SWAP1 SUB PUSH1 1 SSTORE
    * PUSH1 0 PUSH1 0 PUSH1 0 PUSH1 0 PUSH1 0 PUSH1 0x0a PUSH3 100000 CALL PUSH1 2 SSTORE STOP
    * }}}
    */
  private val KzgProbeCode: ByteString = code(
    "5a600a31505a9003600055" + // slot 0: BALANCE(0x0a)
      "5a600931505a9003600155" + // slot 1: BALANCE(0x09)
      "60006000600060006000600a620186a0f1600255" + // slot 2: CALL(100000, 0x0a, 0, 0, 0, 0, 0)
      "00"
  )

  /** Gas of the whole transaction on ETC Olympia, by hand: intrinsic 21,000; first line 2+3+2,600+2+2+3+3+3 and a fresh
    * cold SSTORE 22,100 = 24,718; second line the same with a warm BALANCE (100) = 22,218; third line six PUSH1 18,
    * PUSH3 3, CALL to the now-warm empty 0x0a 100 (no value, no new account, the callee runs nothing and returns its
    * gas), PUSH1 3, SSTORE 22,100 = 22,224. No refund, no calldata floor above 21,000. Total 90,160.
    */
  private val KzgProbeOlympiaGas = BigInt(90_160)

  "ETC Olympia: KZG 0x0a" should "be an empty account: cold to BALANCE, and a CALL to it succeeds (core-geth, Besu)" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val stx = legacyTx(Some(Probe), value = 0, gasLimit = 200_000, config = etcConfig)
    val result = execute(stx, etcHeader(OlympiaBlock), worldWith(KzgProbeCode), etcConfig)
    result.vmError shouldBe None
    slot(result, 0) shouldBe BigInt(2_607) // cold: 0x0a is not pre-warmed
    slot(result, 1) shouldBe BigInt(107) // warm: 0x09 is a precompile
    slot(result, 2) shouldBe BigInt(1) // CALL to an empty account succeeds
    result.gasUsed shouldBe KzgProbeOlympiaGas
  }

  it should "be the same before Olympia (Spiral), where 0x0a was never a precompile" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val stx = legacyTx(Some(Probe), value = 0, gasLimit = 200_000, config = etcConfig)
    val result = execute(stx, etcHeader(OlympiaBlock - 1), worldWith(KzgProbeCode), etcConfig)
    slot(result, 0) shouldBe BigInt(2_607)
    slot(result, 1) shouldBe BigInt(107)
    slot(result, 2) shouldBe BigInt(1)
    result.gasUsed shouldBe KzgProbeOlympiaGas
  }

  "ETH Cancun: KZG 0x0a" should "stay a pre-warmed precompile whose check fails on empty input (unchanged)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // preAmsterdamConfig: Cancun at timestamp 60, Prague at 120.
    val stx = dynamicFeeTx(Some(Probe), value = 0, gasLimit = 200_000, config = preAmsterdamConfig)
    val result = execute(stx, preAmsterdamHeader(46, 60), worldWith(KzgProbeCode), preAmsterdamConfig)
    slot(result, 0) shouldBe BigInt(107) // warm: 0x0a is a precompile from Cancun
    slot(result, 1) shouldBe BigInt(107)
    slot(result, 2) shouldBe BigInt(0) // the KZG check rejects a 0-byte input and the CALL fails
  }

  // ── 2. The calldata floor applies after the refund ─────────────────────────────────────────────────────────────────

  /** `PUSH1 0 PUSH1 0 SSTORE STOP` on a slot holding 1: 3 + 3 + a cold reset (2,100 + 2,900) = 5,006 gas, and the
    * EIP-3529 clearing refund of 4,800.
    */
  private val ClearSlotCode: ByteString = code("600060005500")

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  private def clearWithCalldata(bytes: Int): TxResult =
    val stx = legacyTx(Some(Probe), value = 0, gasLimit = 100_000, payload = nonZeros(bytes), config = etcConfig)
    execute(stx, etcHeader(OlympiaBlock), worldWith(ClearSlotCode, Map(BigInt(0) -> BigInt(1))), etcConfig)

  "ETC Olympia: the EIP-7623 floor" should "apply after the refund when the floor exceeds gas used before refund" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    // 1,000 non-zero bytes: intrinsic 21,000 + 16,000 = 37,000; used before refund 37,000 + 5,006 = 42,006; refund
    // min(4,800, 42,006 / 5) = 4,800; floor 21,000 + 4,000 x 10 = 61,000. Spec: max(42,006 - 4,800, 61,000) = 61,000.
    // core-geth Olympia: max(42,006, 61,000) - min(4,800, 61,000 / 5) = 56,200.
    val result = clearWithCalldata(1000)
    result.vmError shouldBe None
    slot(result, 0) shouldBe BigInt(0)
    result.gasUsed shouldBe BigInt(61_000)
  }

  it should "apply after the refund when only the refund takes gas used below the floor" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    // 100 non-zero bytes: intrinsic 21,000 + 1,600 = 22,600; used before refund 27,606; refund min(4,800, 5,521) =
    // 4,800; floor 21,000 + 400 x 10 = 25,000. Spec: max(27,606 - 4,800, 25,000) = 25,000.
    // core-geth Olympia: max(27,606, 25,000) - min(4,800, 27,606 / 5) = 22,806.
    val result = clearWithCalldata(100)
    result.vmError shouldBe None
    result.gasUsed shouldBe BigInt(25_000)
  }

  it should "leave a transaction above its floor after the refund charged gas used minus refund" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    // No calldata: used before refund 21,000 + 5,006 = 26,006; refund 4,800; floor 21,000. Both clients: 21,206.
    clearWithCalldata(0).gasUsed shouldBe BigInt(21_206)
  }
