package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.EvmConfig
import com.chipprbots.ethereum.vm.OpCodes

/** execution-spec-tests v5.4.0 `tests/frontier/opcodes/test_all_opcodes.py::test_all_opcodes`, executed, on the four
  * post-merge ETH forks hive's `consume-engine` drives.
  *
  * Ground truth is the fixture release itself (`fixtures_stable.tar.gz`, `blockchain_tests[_engine]/frontier/opcodes/
  * test_all_opcodes.json`): one legacy transaction from 0xcdef…cece into an entry contract that CALLs 257 one-opcode
  * callees with a 35,000-gas stipend each and SSTOREs each call's success flag into the slot numbered by the opcode.
  * The world below is rebuilt generatively, and the rebuild is proven verbatim — not assumed — by pinning the fixture's
  * genesis `stateRoot`, and by recovering the fixture's own `(v, r, s)` to its own sender.
  *
  * WHY THIS SPEC EXISTS. On commit 501368ec9 fukuii answered `newPayload` INVALID on exactly these four forks:
  *
  * | fork     | fixture gasUsed |    fukuii |       Δ | cause                                                          |
  * |:---------|----------------:|----------:|--------:|:---------------------------------------------------------------|
  * | Paris    |       8,298,977 | 8,313,979 | +15,002 | BASEFEE (0x48, EIP-3198) missing from `LondonConfigBuilder`    |
  * | Shanghai |       8,283,975 | 8,298,977 | +15,002 | same — the Shanghai overlay installed `SpiralOpCodes`, no 0x48 |
  * | Cancun   |       8,209,169 | 8,159,271 | −49,898 | CLZ (0x1e, EIP-7939, Osaka) leaked in via `OlympiaOpCodes`     |
  * | Prague   |       8,209,169 | 8,159,271 | −49,898 | same                                                           |
  *
  * Both deltas are exact, not approximate. A callee that halts on an undefined opcode burns its whole 35,000 stipend
  * and leaves slot 0 → 0 (cold no-op SSTORE, 2,200); one that succeeds spends 32 × PUSH1 + the opcode and pays 22,100
  * to set the slot. For BASEFEE that is 35,000 + 2,200 − (96 + 2 + 22,100) = 15,002. The fixture calls CLZ TWICE —
  * execution-spec-tests lists 0x1e once as CLZ and once as a raw byte, both writing slot 0x1e — so the second call's
  * SSTORE is warm (100 either way) and the swing is 2 × 35,000 + 2,200 + 100 − (2 × 101 + 22,100 + 100) = 49,898.
  *
  * Fukuii's wrong Shanghai figure equals the fixture's Paris figure only because PUSH0 (valid at Shanghai) is also a
  * 2-gas opcode, so its own validity swing is also 15,002 — a coincidence of costs, not a PUSH0 defect.
  *
  * Nothing asserted an opcode's ABSENCE on an ETH fork before this: the timestamp-fork spec checks CLZ is present at
  * Osaka, never that it is absent at Cancun/Prague, and nothing checked BASEFEE on ETH London at all.
  */
class EthAllOpcodesFixtureSpec extends AnyWordSpec with Matchers:

  private val setup: TestSetup = new TestSetup {}

  private def addr(hex: String): Address = Address(ByteString(Hex.decode(hex)))
  private def bytes(hex: String): ByteString = ByteString(Hex.decode(hex))

  // ── The fixture world, rebuilt ──────────────────────────────────────────────

  /** Callee k lives at CalleeBase + k·0x100; the entry contract is slot 257 in the same sequence. */
  private val CalleeBase: BigInt = BigInt("0eb78fd6fbb27ed4b4f9529d7c795f90f6cfa30f", 16)
  private def calleeAddress(k: Int): Address = Address(CalleeBase + BigInt(k) * 0x100)
  private val Entry: Address = calleeAddress(257)
  private val Sender: Address = addr("cdef04ef055f9353dab9b4b1043a4c4b255cbece")
  private val Coinbase: Address = addr("2adc25665018aa1fe0e6bc666dac8fc2697ff9ba")

  /** 0x00‥0xff with 0x1e appearing twice (CLZ, then the raw byte): 257 calls. */
  private val calledOpcodes: Seq[Int] = (0x00 to 0x1e) ++ (0x1e to 0xff)

  /** Every callee pushes 32 × `1` then runs its opcode, except the six whose operands must be shaped. */
  private def calleeCode(op: Int): ByteString = op match
    case 0x3e => bytes("6000600060003e00") // RETURNDATACOPY(0, 0, 0)
    case 0x56 => bytes("6003565b") // JUMP to a JUMPDEST
    case 0x57 => bytes("60016005575b") // JUMPI to a JUMPDEST
    case 0xf0 => bytes("6460016001556000526005601b6005f000") // CREATE a 5-byte initcode
    case 0xf5 => bytes("64600160015560005260016005601b6005f500") // CREATE2 the same
    case _    => ByteString(Hex.decode("6001" * 32 + f"$op%02x" + "00"))

  /** CALL(gas = 35,000, callee, 0, 0, 0, 0, 0); SSTORE(opcode, success) — then SSTORE(1000, 1); STOP. */
  private val entryCode: ByteString =
    val calls = calledOpcodes.zipWithIndex.map { case (op, k) =>
      "6000600060006000600073" + Hex.toHexString(calleeAddress(k).toArray) + "6188b8f1" + f"60$op%02x" + "55"
    }
    ByteString(Hex.decode(calls.mkString + "60016103e85500"))

  private def withContract(
      world: InMemoryWorldStateProxy,
      address: Address,
      code: ByteString,
      balance: BigInt
  ): InMemoryWorldStateProxy =
    world
      .saveAccount(address, Account(nonce = UInt256(1), balance = UInt256(balance), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)

  private val preWorld: InMemoryWorldStateProxy =
    val funded = setup.emptyWorld.saveAccount(Sender, Account(balance = UInt256(BigInt("3635c9adc5dea00000", 16))))
    val withCallees = calledOpcodes.zipWithIndex.foldLeft(funded) { case (w, (op, k)) =>
      withContract(w, calleeAddress(k), calleeCode(op), balance = 10)
    }
    InMemoryWorldStateProxy.persistState(withContract(withCallees, Entry, entryCode, balance = 0))

  /** The fixture's transaction, signature included, byte for byte. Unprotected (v = 28). */
  private val fixtureTx: SignedTransaction = SignedTransaction(
    LegacyTransaction(
      nonce = 0,
      gasPrice = GasPrice(10),
      gasLimit = GasAmount(BigInt("895440", 16)),
      receivingAddress = Entry,
      value = 0,
      payload = ByteString.empty
    ),
    ECDSASignature(
      BigInt("3e7673c79cc6dcb5ea2931abffd5daf0f76bb33150d4e70ac2eeb3120cbb6843", 16),
      BigInt("56e83af820a20e9d0e7d4ea848507f37904993bae8b1dd6ec34afffa3408f1fb", 16),
      BigInt(28)
    )
  )

  // ── Chain config: hive's runtime shape for these fixtures ───────────────────

  /** Every block fork at 0 (`HIVE_FORK_LONDON=0` → `olympiaBlockNumber`), ETC-only forks disabled, as
    * `hive/fukuii/fukuii.sh` builds it — which is what routes block 1 through `LondonConfigBuilder`.
    */
  private val parisConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETH, chainId = ChainId(1), forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip106BlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          muirGlacierBlockNumber = 0,
          olympiaBlockNumber = 0,
          atlantisBlockNumber = BigInt(Long.MaxValue),
          aghartaBlockNumber = BigInt(Long.MaxValue),
          phoenixBlockNumber = BigInt(Long.MaxValue),
          magnetoBlockNumber = BigInt(Long.MaxValue),
          mystiqueBlockNumber = BigInt(Long.MaxValue),
          spiralBlockNumber = BigInt(Long.MaxValue),
          ecip1099BlockNumber = BigInt(Long.MaxValue)
        )
      )

  private val shanghaiConfig = parisConfig.copy(forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(0L)))
  private val cancunConfig =
    parisConfig.copy(forkTimestamps = ForkTimestamps(shanghaiTimestamp = Some(0L), cancunTimestamp = Some(0L)))
  private val pragueConfig = parisConfig.copy(forkTimestamps =
    ForkTimestamps(shanghaiTimestamp = Some(0L), cancunTimestamp = Some(0L), pragueTimestamp = Some(0L))
  )
  private val osakaConfig =
    pragueConfig.copy(forkTimestamps = pragueConfig.forkTimestamps.copy(osakaTimestamp = Some(0L)))

  // ── Block 1 header, as in the fixture ───────────────────────────────────────

  private val emptyRoot = bytes("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")
  private val zero32 = ByteString(Array.fill[Byte](32)(0))
  private val emptyRequestsHash = bytes("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

  private def header(extra: HeaderExtraFields): BlockHeader =
    com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(1),
      difficulty = Difficulty(0),
      gasLimit = GasAmount(BigInt("07270e00", 16)),
      gasUsed = GasAmount.Zero,
      beneficiary = Coinbase.bytes,
      unixTimestamp = Timestamp(1000),
      extraFields = extra
    )

  private val parisHeader = header(HefPostOlympia(baseFee = 7))
  private val shanghaiHeader = header(HefPostShanghai(baseFee = 7, withdrawalsRoot = emptyRoot))
  private val cancunHeader = header(HefPostCancun(7, emptyRoot, 0, 0, zero32))
  private val pragueHeader = header(HefPostPrague(7, emptyRoot, 0, 0, zero32, emptyRequestsHash))

  private def run(h: BlockHeader, config: BlockchainConfig): BlockResult =
    setup.prep
      .executeTransactions(Seq(fixtureTx), preWorld, h)(config)
      .fold(err => throw new AssertionError(s"block execution failed: ${err.reason}"), identity)

  private def slot(result: BlockResult, opcode: Int): BigInt =
    result.worldState.getStorage(Entry).load(BigInt(opcode))

  private def rootHex(result: BlockResult): String =
    Hex.toHexString(InMemoryWorldStateProxy.persistState(result.worldState).stateRootHash.toArray)

  /** Execution gas of the entry frame alone, under an explicit opcode table — the lever the negative controls pull. */
  private def vmGasUsed(h: BlockHeader, config: BlockchainConfig, table: EvmConfig.OpCodeList): BigInt =
    val evm = EvmConfig.forBlock(h.number.value, h.unixTimestamp, config).copy(opCodeList = table)
    val context: PC = com.chipprbots.ethereum.vm.ProgramContext(fixtureTx, h, Sender, preWorld, evm)
    context.startGas - new VMImpl().run(context).gasRemaining

  "the rebuilt test_all_opcodes world" must {

    "be the fixture's genesis state, byte for byte" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      // Paris/Shanghai genesis stateRoot from the fixture. Cancun/Prague differ only by the
      // system contracts, which the transaction under test never touches.
      Hex.toHexString(preWorld.stateRootHash.toArray) shouldBe
        "34bd906b05b9a4e6ba48c06035ee3e22bb858f7acaa6e18cfb20fc949a75035a"
    }

    "carry the fixture's own signed transaction, recovering to the fixture's sender" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      SignedTransaction.getSender(fixtureTx)(using parisConfig) shouldBe Some(Sender)
    }
  }

  "test_all_opcodes block gasUsed on ETH post-merge forks" must {

    "be 8,298,977 on Paris, with BASEFEE (0x48) succeeding" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      val result = run(parisHeader, parisConfig)
      result.gasUsed shouldBe BigInt(8298977)
      slot(result, 0x48) shouldBe BigInt(1)
      slot(result, 0x5f) shouldBe BigInt(0) // PUSH0 is Shanghai
      slot(result, 0x1e) shouldBe BigInt(0) // CLZ is Osaka
      // Fixture block-1 stateRoot (no block reward post-merge, no system calls pre-Cancun).
      rootHex(result) shouldBe "9c139d8a7602af51ca6e68e50cff2ddb410ac2294a50191df4c215ce97f46af8"
    }

    "be 8,283,975 on Shanghai, with BASEFEE and PUSH0 succeeding" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      val result = run(shanghaiHeader, shanghaiConfig)
      result.gasUsed shouldBe BigInt(8283975)
      slot(result, 0x48) shouldBe BigInt(1)
      slot(result, 0x5f) shouldBe BigInt(1)
      slot(result, 0x1e) shouldBe BigInt(0)
      rootHex(result) shouldBe "c926edbec0f263a8c32946509066920b032aa3cb488748a1e26d09a50ef6c2f6"
    }

    "be 8,209,169 on Cancun, with CLZ (0x1e) undefined" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      val result = run(cancunHeader, cancunConfig)
      result.gasUsed shouldBe BigInt(8209169)
      slot(result, 0x1e) shouldBe BigInt(0)
      Seq(0x48, 0x49, 0x4a, 0x5c, 0x5d, 0x5e, 0x5f).foreach(op => slot(result, op) shouldBe BigInt(1))
    }

    "be 8,209,169 on Prague, with CLZ (0x1e) undefined" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      val result = run(pragueHeader, pragueConfig)
      result.gasUsed shouldBe BigInt(8209169)
      slot(result, 0x1e) shouldBe BigInt(0)
    }
  }

  "the pre-fix opcode tables (negative control)" must {

    "reproduce hive's +15,002 on Paris when BASEFEE is withheld, and nothing else" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      // MagnetoOpCodes is what LondonConfigBuilder inherited before the fix: 8,313,979 − 8,298,977.
      val delta = vmGasUsed(parisHeader, parisConfig, EvmConfig.MagnetoOpCodes) -
        vmGasUsed(parisHeader, parisConfig, EvmConfig.LondonOpCodes)
      delta shouldBe BigInt(15002)
    }

    "reproduce hive's +15,002 on Shanghai with the old SpiralOpCodes overlay" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      val delta = vmGasUsed(shanghaiHeader, shanghaiConfig, EvmConfig.SpiralOpCodes) -
        vmGasUsed(shanghaiHeader, shanghaiConfig, EvmConfig.ShanghaiOpCodes)
      delta shouldBe BigInt(15002)
    }

    "reproduce hive's −49,898 on Cancun with the old OlympiaOpCodes overlay (CLZ, twice)" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      val delta = vmGasUsed(cancunHeader, cancunConfig, EvmConfig.OlympiaOpCodes) -
        vmGasUsed(cancunHeader, cancunConfig, EvmConfig.CancunOpCodes)
      delta shouldBe BigInt(-49898)
    }

    "leave Osaka with CLZ: the fixture tx then costs exactly what fukuii wrongly charged at Cancun" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      // CLZ is legitimately valid at Osaka, and nothing else in this transaction changes between
      // Prague and Osaka — so 8,159,271 moves from "wrong at Cancun" to "right at Osaka".
      val result = run(pragueHeader, osakaConfig)
      slot(result, 0x1e) shouldBe BigInt(1)
      result.gasUsed shouldBe BigInt(8159271)
    }
  }

  "the ETH-only opcode tables" must {

    "leave Osaka's table exactly as it was (the old OlympiaOpCodes set)" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      EvmConfig.OsakaOpCodes.byteToOpCode shouldBe EvmConfig.OlympiaOpCodes.byteToOpCode
    }

    "differ from their predecessor by exactly the fork's EIPs" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      def codes(l: List[com.chipprbots.ethereum.vm.OpCode]): Set[Int] = l.map(_.code & 0xff).toSet
      codes(OpCodes.LondonOpCodes) -- codes(OpCodes.PhoenixOpCodes) shouldBe Set(0x48)
      codes(OpCodes.ShanghaiOpCodes) -- codes(OpCodes.LondonOpCodes) shouldBe Set(0x5f)
      codes(OpCodes.CancunOpCodes) -- codes(OpCodes.ShanghaiOpCodes) shouldBe Set(0x49, 0x4a, 0x5c, 0x5d, 0x5e)
      codes(OpCodes.OsakaOpCodes) -- codes(OpCodes.CancunOpCodes) shouldBe Set(0x1e)
    }

    "never be selected on a shipped ETC chain, at any block" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      // LondonConfigBuilder is reachable from forBlock() only when spiral > olympia. This pins that no
      // ETC chain has that shape, so the BASEFEE addition cannot reach ETC dispatch.
      val ethTables = Set(
        EvmConfig.LondonOpCodes,
        EvmConfig.ShanghaiOpCodes,
        EvmConfig.CancunOpCodes,
        EvmConfig.OsakaOpCodes
      )
      val etc = Config.blockchains.blockchains.filter(_._2.networkType == NetworkType.ETC)
      etc should not be empty
      etc.foreach { case (name, cfg) =>
        val f = cfg.forkBlockNumbers
        withClue(s"$name: ") {
          f.spiralBlockNumber should be <= f.olympiaBlockNumber
          Seq(BigInt(0), f.magnetoBlockNumber, f.spiralBlockNumber, f.olympiaBlockNumber, BigInt(Long.MaxValue))
            .foreach { n =>
              ethTables should not contain EvmConfig.forBlock(n, cfg).opCodeList
              ethTables should not contain EvmConfig.forBlock(n, Timestamp(4_102_444_800L), cfg).opCodeList
            }
        }
      }
    }

    "leave the ETC tables without BASEFEE where ETC never had it" taggedAs (UnitTest, VMTest, ConsensusTest) in {
      EvmConfig.MagnetoOpCodes.byteToOpCode.get(0x48.toByte) shouldBe None
      EvmConfig.SpiralOpCodes.byteToOpCode.get(0x48.toByte) shouldBe None
    }
  }
