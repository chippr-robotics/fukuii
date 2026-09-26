package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.testing.Tags.*

/** EIP-7976 (calldata floor 64 gas per byte) and EIP-7981 (access-list data cost) at Amsterdam.
  *
  * Every figure is hand-computed from execution-specs `forks/amsterdam` `calculate_intrinsic_cost` (which go-ethereum's
  * `IntrinsicGas` / `FloorDataGas` match):
  * {{{
  * base_execution_gas    = TX_BASE 12,000 + recipient_execution_gas
  *                           (COLD_ACCOUNT_ACCESS 3,000 [+ TX_VALUE_COST 6,000 if value > 0] for a call,
  *                            CREATE_ACCESS 12,000 for a creation, 0 for a self-transfer)
  * access_list_data_cost = (80 * addresses + 128 * storage_keys) * 16      -- 1,280 / 2,048
  * intrinsic             = base_execution_gas + calldata (4 / 16) + initcode words * 2
  *                           + access list entries (2,900 / 2,000) + access_list_data_cost + auths * 7,816
  * calldata_floor        = base_execution_gas + len(data) * 64 + access_list_data_cost
  * }}}
  * Where the EIP texts differ (EIP-7976 anchors the floor at 21,000; EIP-7981 lists EIP-2930's 2,400 / 1,900),
  * execution-specs is followed, because the conformance fixtures are generated from it.
  */
// scalastyle:off magic.number
class AmsterdamCalldataFloorSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val recipient: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))
  private val other: Address = Address(Hex.decode("00000000000000000000000000000000000000aa"))

  private def key(n: Int): StorageKey = StorageKey(BigInt(n))
  private def zeros(n: Int): ByteString = ByteString(new Array[Byte](n))
  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  /** Osaka at 180, Amsterdam at 360 on the fixture schedule; 400 is past both. */
  private val amsterdam: EvmConfig = EvmConfig.forBlock(BigInt(1), Timestamp(400L), amsterdamConfig)
  private val osaka: EvmConfig = EvmConfig.forBlock(BigInt(1), Timestamp(400L), preAmsterdamConfig)

  /** An existing, code-less recipient plus a funded sender. */
  private def transferWorld: InMemoryWorldStateProxy =
    setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000"))))
      .saveAccount(recipient, Account(nonce = UInt256(0), balance = UInt256(BigInt(1000))))

  // ── The pure functions ────────────────────────────────────────────────────

  "AmsterdamGas.accessListDataCost" should "charge 1,280 per address and 2,048 per storage key" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    AmsterdamGas.accessListDataCost(Nil) shouldBe BigInt(0)
    AmsterdamGas.accessListDataCost(List(AccessListItem(other, Nil))) shouldBe BigInt(1280)
    AmsterdamGas.accessListDataCost(List(AccessListItem(other, List(key(0))))) shouldBe BigInt(1280 + 2048)
    // EEST `multiple_access_lists_multiple_storage_keys`: 10 addresses x 10 keys = 12,800 + 204,800.
    val tenByTen = (0 until 10).toList.map(a => AccessListItem(Address(a), (0 until 10).toList.map(key)))
    AmsterdamGas.accessListDataCost(tenByTen) shouldBe BigInt(217600)
  }

  "AmsterdamGas.calldataFloorGas" should "charge 64 gas per calldata byte, zero or not" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    AmsterdamGas.calldataFloorGas(15000, ByteString.empty, Nil) shouldBe BigInt(15000)
    AmsterdamGas.calldataFloorGas(15000, zeros(20), Nil) shouldBe BigInt(16280)
    AmsterdamGas.calldataFloorGas(15000, nonZeros(20), Nil) shouldBe BigInt(16280)
    AmsterdamGas.calldataFloorGas(15000, zeros(10) ++ nonZeros(10), Nil) shouldBe BigInt(16280)
  }

  it should "add the access list's data cost" taggedAs (VMTest, ConsensusTest) in {
    // 15,000 + 100 x 64 + (1,280 + 2,048)
    AmsterdamGas.calldataFloorGas(15000, nonZeros(100), List(AccessListItem(other, List(key(0))))) shouldBe
      BigInt(24728)
  }

  // ── EvmConfig: intrinsic gas and floor, Amsterdam against Osaka ──────────

  "calcTransactionIntrinsicGas under Amsterdam" should "add the EIP-7981 data cost on top of the per-entry charges" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val accessList = List(AccessListItem(other, List(key(1), key(2), key(3))), AccessListItem(recipient, Nil))
    // 15,000 + (2 x 2,900 + 3 x 2,000) + (2 x 1,280 + 3 x 2,048) = 15,000 + 11,800 + 8,704
    amsterdam.calcTransactionIntrinsicGas(
      ByteString.empty,
      false,
      accessList,
      0,
      Some(recipient),
      UInt256(0),
      other
    ) shouldBe
      BigInt(35504)
    // Osaka is untouched: 21,000 + 2 x 2,400 + 3 x 1,900, no data cost.
    osaka.calcTransactionIntrinsicGas(
      ByteString.empty,
      false,
      accessList,
      0,
      Some(recipient),
      UInt256(0),
      other
    ) shouldBe
      BigInt(31500)
  }

  "calcAmsterdamCalldataFloorGas" should "sit on EIP-2780's decomposed base, never on 21,000" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val data = nonZeros(100) // 6,400 of floor
    def floor(to: Option[Address], value: BigInt): BigInt =
      amsterdam.calcAmsterdamCalldataFloorGas(data, Nil, to, UInt256(value), other)
    floor(Some(recipient), 1) shouldBe BigInt(12000 + 3000 + 6000 + 6400) // value-bearing call
    floor(Some(recipient), 0) shouldBe BigInt(12000 + 3000 + 6400) // zero-value call
    floor(Some(other), 1) shouldBe BigInt(12000 + 6400) // self-transfer: no recipient, no value term
    floor(None, 1) shouldBe BigInt(12000 + 12000 + 6400) // creation: CREATE_ACCESS, no value term
  }

  it should "leave initcode-word charges out of the floor (they stay intrinsic-only)" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val initcode = nonZeros(100) // 4 words
    // intrinsic: 24,000 + 100 x 16 + 4 x 2 = 25,608; floor: 24,000 + 100 x 64 = 30,400
    amsterdam.calcTransactionIntrinsicGas(initcode, true, Nil, 0, None, UInt256(0), other) shouldBe BigInt(25608)
    amsterdam.calcAmsterdamCalldataFloorGas(initcode, Nil, None, UInt256(0), other) shouldBe BigInt(30400)
  }

  it should "reproduce the Plataberget 65,552-byte deployment floor of 4,219,328" taggedAs (VMTest, ConsensusTest) in {
    // Block 275,654, tx 0x33e82ff8…2680: a type-2 creation whose execution dimension is this floor rather than the
    // 1,109,644 it executed (receipt 101,563,324 minus 100,453,680 of state gas). 24,000 + 65,552 x 64.
    amsterdam.calcAmsterdamCalldataFloorGas(zeros(65552), Nil, None, UInt256(0), other) shouldBe BigInt(4219328)
  }

  // ── End to end: what the sender pays and what the block counts ───────────

  "A data-heavy transaction under Amsterdam" should "pay the 64-per-byte floor on its receipt and its execution dimension" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // 1,000 zero bytes to a code-less EOA: intrinsic 15,000 + 4,000 = 19,000, floor 15,000 + 64,000 = 79,000.
    val stx =
      dynamicFeeTx(Some(recipient), value = 0, gasLimit = 100000, payload = zeros(1000), config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(46, 460), transferWorld, amsterdamConfig)
    result.gasUsed shouldBe BigInt(79000)
    result.executionGasUsed shouldBe BigInt(79000)
    result.stateGasUsed shouldBe BigInt(0)
  }

  it should "have paid EIP-7623's 10-per-token floor before activation" taggedAs (VMTest, ConsensusTest) in {
    // intrinsic 21,000 + 4,000 = 25,000; EIP-7623 floor 21,000 + 1,000 x 10 = 31,000.
    val stx =
      dynamicFeeTx(Some(recipient), value = 0, gasLimit = 100000, payload = zeros(1000), config = preAmsterdamConfig)
    execute(stx, preAmsterdamHeader(46, 300), transferWorld, preAmsterdamConfig).gasUsed shouldBe BigInt(31000)
  }

  "An access-list transaction under Amsterdam" should "pay the data surcharge even where the floor does not bind" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // intrinsic 15,000 + (2,900 + 2 x 2,000) + (1,280 + 2 x 2,048) = 27,276; floor 15,000 + 5,376 = 20,376.
    val accessList = List(AccessListItem(recipient, List(key(1), key(2))))
    val stx = dynamicFeeTx(Some(recipient), 0, 100000, accessList = accessList, config = amsterdamConfig)
    val result = execute(stx, amsterdamHeader(47, 470), transferWorld, amsterdamConfig)
    result.gasUsed shouldBe BigInt(27276)
    result.executionGasUsed shouldBe BigInt(27276)
  }

  it should "have paid EIP-2930's charges alone before activation" taggedAs (VMTest, ConsensusTest) in {
    // 21,000 + 2,400 + 2 x 1,900
    val accessList = List(AccessListItem(recipient, List(key(1), key(2))))
    val stx = dynamicFeeTx(Some(recipient), 0, 100000, accessList = accessList, config = preAmsterdamConfig)
    execute(stx, preAmsterdamHeader(47, 300), transferWorld, preAmsterdamConfig).gasUsed shouldBe BigInt(27200)
  }
