package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.AmsterdamGas
import com.chipprbots.ethereum.vm.EvmConfig

/** #1727 PR 4 (B-130): `TxResult.maxUsedGas` is go-ethereum's `ExecutionResult.MaxUsedGas` (core/state_transition.go
  * `settleGas`, v1.17.9): the gas used before the refund, raised to the EIP-7623 calldata floor where one is in force.
  *
  * The same seven transactions as `TxResultBlockImportPinSpec` (which pins what block import does with them), executed
  * one after another with `BlockPreparator.executeTransaction`, so each `TxResult` is checked against the figure the
  * pin spec records as that transaction's receipt gas:
  *   - tx 0: 1,000 non-zero calldata bytes to an EOA. Intrinsic 37,000, floor 61,000.
  *   - tx 1: clears a storage slot: 26,006 before the 4,800 refund, 21,206 after.
  *   - tx 2: the same with 200 calldata bytes: 29,206 before the refund, 24,406 after, floor 29,000.
  *   - tx 3: the same with 1,000 bytes: 42,006 before, 37,206 after, floor 61,000.
  *   - tx 4: a REVERT. tx 5: an exceptional halt. tx 6: a plain value transfer.
  */
// scalastyle:off magic.number
class TxResultMaxUsedGasSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Payee = Address(0xe1)
  private val Clearers = Seq(Address(0xc1), Address(0xc2), Address(0xc3))
  private val Reverter = Address(0xd1)
  private val Spinner = Address(0xd2)

  private val ClearerCode = ByteString(Hex.decode("600060005500"))
  private val ReverterCode = ByteString(Hex.decode("60006000fd"))
  private val SpinnerCode = ByteString(Hex.decode("5b600056"))

  private def withCode(world: InMemoryWorldStateProxy, address: Address, code: ByteString) =
    world
      .saveAccount(address, Account(nonce = UInt256(0), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)

  private def world: InMemoryWorldStateProxy =
    val base = setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24))))
      .saveAccount(Payee, Account(nonce = UInt256(0), balance = UInt256(1)))
    val withClearers = Clearers.foldLeft(base) { (w, address) =>
      val coded = withCode(w, address, ClearerCode)
      coded.saveStorage(address, coded.getStorage(address).store(UInt256(0), UInt256(1)))
    }
    InMemoryWorldStateProxy.persistState(
      withCode(withCode(withClearers, Reverter, ReverterCode), Spinner, SpinnerCode)
    )

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  private def transactions(config: BlockchainConfig): Seq[SignedTransaction] =
    val calls = Seq(
      (Payee, BigInt(0), nonZeros(1000), BigInt(100000)),
      (Clearers(0), BigInt(0), ByteString.empty, BigInt(100000)),
      (Clearers(1), BigInt(0), nonZeros(200), BigInt(100000)),
      (Clearers(2), BigInt(0), nonZeros(1000), BigInt(100000)),
      (Reverter, BigInt(0), ByteString.empty, BigInt(100000)),
      (Spinner, BigInt(0), ByteString.empty, BigInt(100000)),
      (Payee, BigInt(1), ByteString.empty, BigInt(21000))
    )
    calls.zipWithIndex.map { case ((to, value, payload, gas), nonce) =>
      legacyTx(Some(to), value, gas, payload, nonce = BigInt(nonce), config = config)
    }

  /** The seven results, each transaction executed on the world the previous one left. */
  private def results(header: BlockHeader, config: BlockchainConfig): Seq[TxResult] =
    transactions(config)
      .foldLeft((world, Vector.empty[TxResult])) { case ((w, done), stx) =>
        val result = execute(stx, header, w, config)
        (result.worldState, done :+ result)
      }
      ._2

  private def gas(result: TxResult): (BigInt, BigInt) = (result.gasUsed, result.maxUsedGas)

  /** (gasUsed, maxUsedGas) of the seven transactions where no calldata floor is in force. */
  private val WithoutFloor: Seq[(BigInt, BigInt)] = Seq(
    (BigInt(37000), BigInt(37000)),
    (BigInt(21206), BigInt(26006)),
    (BigInt(24406), BigInt(29206)),
    (BigInt(37206), BigInt(42006)),
    (BigInt(21006), BigInt(21006)),
    (BigInt(100000), BigInt(100000)),
    (BigInt(21000), BigInt(21000))
  )

  /** The same where the EIP-7623 floor is in force: it lifts tx 0 and tx 3 to 61,000 in both figures, and lifts tx 2's
    * receipt (24,406) to 29,000 while its peak stays at the 29,206 it reached before the refund.
    */
  private val WithFloor: Seq[(BigInt, BigInt)] = Seq(
    (BigInt(61000), BigInt(61000)),
    (BigInt(21206), BigInt(26006)),
    (BigInt(29000), BigInt(29206)),
    (BigInt(61000), BigInt(61000)),
    (BigInt(21006), BigInt(21006)),
    (BigInt(100000), BigInt(100000)),
    (BigInt(21000), BigInt(21000))
  )

  // ── ETH: the Amsterdam fixture schedule (Cancun 60, Prague 120, Osaka 180, Amsterdam 360) ───────────────────────

  "TxResult.maxUsedGas on ETH Cancun" should "be the gas before the refund, with no floor" taggedAs (
    UnitTest,
    StateTest
  ) in {
    results(preAmsterdamHeader(46, 60), amsterdamConfig).map(gas) shouldBe WithoutFloor
  }

  "TxResult.maxUsedGas on ETH Prague" should "be the gas before the refund, raised to the EIP-7623 floor" taggedAs (
    UnitTest,
    StateTest
  ) in {
    results(preAmsterdamHeader(46, 120), amsterdamConfig).map(gas) shouldBe WithFloor
  }

  "TxResult.maxUsedGas on ETH Osaka" should "be the gas before the refund, raised to the EIP-7623 floor" taggedAs (
    UnitTest,
    StateTest
  ) in {
    results(preAmsterdamHeader(46, 300), amsterdamConfig).map(gas) shouldBe WithFloor
  }

  "TxResult.maxUsedGas on ETH Amsterdam" should "follow the EIP-8037 pre-refund figure and never fall below gasUsed, at zero state gas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val rs = results(amsterdamHeader(46, 460), amsterdamConfig)
    // EIP-7976 floor for 1,000 non-zero bytes to an EOA: 79,000, above the pre-refund cost; no refund, no state gas.
    gas(rs(0)) shouldBe ((BigInt(79000), BigInt(79000)))
    // A revert keeps its remaining gas, a halt consumes it all, a transfer has nothing to refund.
    Seq(4, 5, 6).foreach(i => gas(rs(i)) shouldBe ((rs(i).gasUsed, rs(i).gasUsed)))
    gas(rs(4)) shouldBe ((BigInt(15006), BigInt(15006)))
    gas(rs(5)) shouldBe ((BigInt(100000), BigInt(100000)))
    // The refunded transaction: the peak is above what was paid, by no more than the EIP-3529 cap (a fifth of the peak).
    rs(1).maxUsedGas should be > rs(1).gasUsed
    (rs(1).maxUsedGas - rs(1).gasUsed) should be <= (rs(1).maxUsedGas / 5)
    // go-ethereum core/eip8037_test.go assertPoolSane: the receipt figure and each block dimension are bounded by the
    // peak. With no state gas here, the execution dimension IS the peak (EIP-7778: counted before refunds).
    rs.foreach { r =>
      r.gasUsed should be <= r.maxUsedGas
      r.executionGasUsed should be <= r.maxUsedGas
      r.stateGasUsed should be <= r.maxUsedGas
    }
    // THIS HOLDS AT ZERO STATE GAS ONLY (B-162). None of the seven transactions creates state, so the execution
    // dimension is the whole peak. With state gas the two do not add up to the peak in general: execution is
    // max(before - state, floor) and the peak is max(before, floor), and max(before - state, floor) + state exceeds
    // max(before, floor) whenever the floor lands between before - state and before. See the cases below.
    rs.foreach(_.stateGasUsed shouldBe BigInt(0))
    rs.map(r => r.executionGasUsed + r.stateGasUsed) shouldBe rs.map(_.maxUsedGas)
  }

  // ── ETH Amsterdam with state gas (EIP-8037) ─────────────────────────────────────────────────────────────────────
  //
  // BlockPreparator: gasUsedBeforeRefund = gasLimit - gasRemaining - stateGasReservoir, and
  //   executionGasUsed = max(gasUsedBeforeRefund - stateGas, floor)
  //   maxUsedGas       = max(gasUsedBeforeRefund, floor)
  // so exec + state == maxUsedGas holds exactly while the floor is below before - state, and no longer once it is not.

  private val SlotStateGas: BigInt = AmsterdamGas.StateBytesPerStorageSet * AmsterdamGas.Cpsb

  private def emitTx(payload: ByteString, gasLimit: BigInt): SignedTransaction =
    legacyTx(Some(Emit), value = 2, gasLimit = gasLimit, payload = payload, config = amsterdamConfig)

  private val Block45Payload: ByteString = ByteString(Hex.decode("580abd8a903ed7d3656d6974"))

  "TxResult.maxUsedGas on ETH Amsterdam with state gas" should "be the whole peak, state gas included, while the floor is below the execution gas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    // Block 45 tx-emit-legacy at a gas limit that affords it: its first SSTORE creates a slot, 64 x 1,530 = 97,920 of
    // state gas, and the measured total is 144,888 (AmsterdamGasAccountingSpec). The floor for its 12 bytes is far
    // below the 46,968 that is left for execution.
    val r = execute(emitTx(Block45Payload, 200000), amsterdamHeader(45, 450), block45World, amsterdamConfig)
    r.vmError shouldBe None
    r.stateGasUsed shouldBe BigInt(97920)
    r.stateGasUsed shouldBe SlotStateGas
    r.maxUsedGas shouldBe BigInt(144888)
    r.executionGasUsed shouldBe BigInt(144888) - BigInt(97920)
    r.executionGasUsed + r.stateGasUsed shouldBe r.maxUsedGas
    // Nothing is refunded, so what the sender pays is the peak
    r.gasUsed shouldBe r.maxUsedGas
  }

  it should "also be the whole peak for block 41, whose CREATE costs 120 x 1,530 of state gas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val stx = dynamicFeeTx(Some(CallTree), value = 0, gasLimit = 600000, config = amsterdamConfig)
    val r = execute(stx, amsterdamHeader(41, 410), block41World, amsterdamConfig)
    r.vmError shouldBe None
    r.stateGasUsed shouldBe AmsterdamGas.StateBytesPerNewAccount * AmsterdamGas.Cpsb
    // The measured receipt figure, which is the pre-refund total here, and the 143,347 left once the 183,600 is out
    r.maxUsedGas shouldBe BigInt(326947)
    r.executionGasUsed shouldBe BigInt(326947) - BigInt(183600)
    r.executionGasUsed + r.stateGasUsed shouldBe r.maxUsedGas
  }

  it should "not be exec + state once the calldata floor lands between before - state and before" taggedAs (
    UnitTest,
    StateTest
  ) in {
    // The block 45 transaction with 1,000 non-zero calldata bytes instead of 12. What 988 more bytes add to the
    // measured 144,888: 16 gas each of calldata, and in the contract the memory (3 per word plus words squared over
    // 512: 98 for the 32 words, 3 for the one), the CALLDATACOPY words (3 each) and the KECCAK256 words (6 each).
    val extraBytes = BigInt(988)
    val beforeRefund = BigInt(144888) + extraBytes * 16 + (BigInt(98) - 3) + (BigInt(32) - 1) * 3 + (BigInt(32) - 1) * 6
    beforeRefund shouldBe BigInt(161070)
    val payload = ByteString(Array.fill[Byte](1000)(0x01))
    val header = amsterdamHeader(45, 450)
    val r = execute(emitTx(payload, 300000), header, block45World, amsterdamConfig)
    r.vmError shouldBe None

    val floor = EvmConfig
      .forBlock(header.number.value, header.unixTimestamp, amsterdamConfig)
      .calcAmsterdamCalldataFloorGas(payload, Nil, Some(Emit), UInt256(2), senderAddress)
    // The case is the one the note is about: the floor is above what is left for execution, and below the peak
    floor should be > beforeRefund - SlotStateGas
    floor should be < beforeRefund

    r.stateGasUsed shouldBe SlotStateGas
    // Execution is lifted to the floor; the peak is the gas actually used, since the floor is below it
    r.executionGasUsed shouldBe floor
    r.maxUsedGas shouldBe beforeRefund
    r.gasUsed shouldBe beforeRefund
    // So the two dimensions add up to more than the peak, by exactly how far the floor lifted execution
    (r.executionGasUsed + r.stateGasUsed) should be > r.maxUsedGas
    (r.executionGasUsed + r.stateGasUsed) - r.maxUsedGas shouldBe floor - (beforeRefund - SlotStateGas)
    // Block gas is the larger of the two, as for any block
    r.executionGasUsed.max(r.stateGasUsed) should be <= r.maxUsedGas
  }

  // ── ETC: Olympia at block 1,000 (ECIP-1121), Spiral at 900 ──────────────────────────────────────────────────────

  private val OlympiaBlock = BigInt(1000)

  private val etcOlympiaConfig: BlockchainConfig =
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

  "TxResult.maxUsedGas on ETC before Olympia" should "be the gas before the refund, with no floor" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    results(etcHeader(OlympiaBlock - 1), etcOlympiaConfig).map(gas) shouldBe WithoutFloor
  }

  "TxResult.maxUsedGas on ETC at Olympia" should "be the gas before the refund, raised to the ECIP-1121 floor" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    results(etcHeader(OlympiaBlock), etcOlympiaConfig).map(gas) shouldBe WithFloor
  }

  "TxResult.maxUsedGas" should "never be below gasUsed, on any fork, for any of the seven transactions" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val all = Seq(
      results(preAmsterdamHeader(46, 60), amsterdamConfig),
      results(preAmsterdamHeader(46, 120), amsterdamConfig),
      results(preAmsterdamHeader(46, 300), amsterdamConfig),
      results(amsterdamHeader(46, 460), amsterdamConfig),
      results(etcHeader(OlympiaBlock - 1), etcOlympiaConfig),
      results(etcHeader(OlympiaBlock), etcOlympiaConfig)
    )
    all.flatten.foreach(r => r.maxUsedGas should be >= r.gasUsed)
  }
