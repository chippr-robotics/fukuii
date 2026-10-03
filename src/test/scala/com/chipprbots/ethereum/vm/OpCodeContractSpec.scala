package com.chipprbots.ethereum.vm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.Generators.*

import Fixtures.blockchainConfig

/** The contracts `OpCode.execute` relies on to skip two per-instruction calls:
  *
  *   - `availableInContext` is true outside a static frame (EIP-214 restricts STATICCALL frames only), so `execute`
  *     asks only when `state.staticCtx`;
  *   - `stateGasDelta` is zero before Amsterdam (EIP-8037 state gas starts there), so `execute` asks only when
  *     `amsterdamEnabled`.
  *
  * Checked for every opcode of every opcode table either chain selects, against every pre-Amsterdam fee schedule.
  */
class OpCodeContractSpec extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks:

  // Every table either chain selects before Amsterdam — all of ETC's among them.
  private val preAmsterdamTables: List[EvmConfig.OpCodeList] = List(
    EvmConfig.FrontierOpCodes,
    EvmConfig.HomesteadOpCodes,
    EvmConfig.ByzantiumOpCodes,
    EvmConfig.ConstantinopleOpCodes,
    EvmConfig.PhoenixOpCodes,
    EvmConfig.SpiralOpCodes,
    EvmConfig.OlympiaOpCodes,
    EvmConfig.EtcOlympiaOpCodes,
    EvmConfig.LondonOpCodes,
    EvmConfig.ShanghaiOpCodes,
    EvmConfig.CancunOpCodes,
    EvmConfig.OsakaOpCodes
  )

  private val allOpCodes: List[OpCode] =
    (preAmsterdamTables :+ EvmConfig.AmsterdamOpCodes).flatMap(_.opCodes).distinct

  private val preAmsterdamConfigs: Seq[EvmConfig] = Seq(
    EvmConfig.FrontierConfigBuilder,
    EvmConfig.HomesteadConfigBuilder,
    EvmConfig.PostEIP150ConfigBuilder,
    EvmConfig.PostEIP160ConfigBuilder,
    EvmConfig.PostEIP161ConfigBuilder,
    EvmConfig.ByzantiumConfigBuilder,
    EvmConfig.AtlantisConfigBuilder,
    EvmConfig.ConstantinopleConfigBuilder,
    EvmConfig.AghartaConfigBuilder,
    EvmConfig.PetersburgConfigBuilder,
    EvmConfig.IstanbulConfigBuilder,
    EvmConfig.PhoenixConfigBuilder,
    EvmConfig.MagnetoConfigBuilder,
    EvmConfig.BerlinConfigBuilder,
    EvmConfig.MystiqueConfigBuilder,
    EvmConfig.SpiralConfigBuilder,
    EvmConfig.LondonConfigBuilder,
    EvmConfig.OlympiaConfigBuilder
  ).map(_(blockchainConfig))

  // Deep enough for every instruction's operands (CALL reads 7), with small words so memory and storage stay cheap.
  private val stackGen = getStackGen(minElems = 7, maxElems = 10, valueGen = getUInt256Gen(max = UInt256(64)))

  test("outside a static frame every instruction is available", UnitTest, VMTest) {
    allOpCodes should not be empty
    forAll(getProgramStateGen(stackGen = stackGen)) { state =>
      state.staticCtx shouldBe false
      for op <- allOpCodes do withClue(s"$op: ")(op.availableInContext(state) shouldBe true)
    }
  }

  test("before Amsterdam every instruction's state-gas delta is zero", UnitTest, VMTest) {
    for config <- preAmsterdamConfigs do
      config.amsterdamEnabled shouldBe false
      forAll(getProgramStateGen(stackGen = stackGen, evmConfig = config)) { state =>
        for op <- allOpCodes do withClue(s"$op: ")(op.stateGasDelta(state) shouldBe BigInt(0))
      }
  }

  test("SLOTNUM and DUPN/SWAPN/EXCHANGE are in the Amsterdam table and in no earlier one", UnitTest, VMTest) {
    val amsterdamOnly = Seq(0x4b, 0xe6, 0xe7, 0xe8).map(_.toByte)
    // ETC's tables (Frontier..Spiral, EtcOlympia) and ETH's before Amsterdam: none holds these bytes, so an ETC
    // chain executing any of them keeps its pre-existing InvalidOpCode halt.
    for table <- preAmsterdamTables; byte <- amsterdamOnly do
      withClue(f"0x${byte & 0xff}%02x: ")(table.byteToOpCode.get(byte) shouldBe None)
    EvmConfig.AmsterdamOpCodes.byteToOpCode.keySet should contain allElementsOf amsterdamOnly
    // Amsterdam adds exactly these four to Osaka.
    (EvmConfig.AmsterdamOpCodes.byteToOpCode.keySet -- EvmConfig.OsakaOpCodes.byteToOpCode.keySet) shouldBe
      amsterdamOnly.toSet
  }

  test("the state-gas check is not vacuous: from Amsterdam an SSTORE creating a slot has a delta", UnitTest, VMTest) {
    val amsterdam = EvmConfig.PhoenixConfigBuilder(blockchainConfig).copy(amsterdamEnabled = true)
    // The generated storage and original world are empty, so SSTORE(slot 7, value 1) creates a slot.
    val createSlot = Stack.empty().push(UInt256(1)).push(UInt256(7))
    forAll(getProgramStateGen(evmConfig = amsterdam)) { state =>
      SSTORE.stateGasDelta(state.withStack(createSlot)) should be > BigInt(0)
    }
  }
