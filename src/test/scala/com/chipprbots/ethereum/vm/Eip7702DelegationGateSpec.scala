package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.SetCodeTransaction
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkTimestamps

import Fixtures.blockchainConfig

/** EIP-7702 delegation designators (0xef0100 ++ address) are followed only once EIP-7702 is active: Olympia on ETC
  * (block number), Prague on ETH (timestamp) — the forks that admit Type-4 transactions.
  *
  * Before that, 0xef0100-prefixed code is ordinary bytecode and 0xEF is an undefined opcode. This matters on ETC
  * mainnet: before Mystique (EIP-3541) anyone could deploy a 23-byte contract starting 0xef0100, and core-geth — which
  * has no EIP-7702 — executes it as INVALID (exceptional halt, all gas consumed). Following it as a delegation instead
  * runs someone else's code and forks the chain.
  *
  * The oracle for "behaves as ordinary bytecode" is a control contract whose code also starts with 0xEF but is NOT a
  * designator (0xef0200 ++ address): pre-activation the two must be indistinguishable, gas included.
  */
class Eip7702DelegationGateSpec extends AnyWordSpec with Matchers:

  private val callerAddr = Address(0xca11e4)
  private val designatorAddr = Address(0xde1e6a7e) // code = 0xef0100 ++ targetAddr
  private val controlAddr = Address(0xc0417201) // code = 0xef0200 ++ targetAddr (never a designator)
  private val targetAddr = Address(0x7a46e7)

  private val designatorCode: ByteString = SetCodeTransaction.addressToDelegation(targetAddr)
  private val controlCode: ByteString = ByteString(0xef.toByte, 0x02.toByte, 0x00.toByte) ++ targetAddr.bytes

  // Target: SSTORE(1, 1) — in the delegating account's storage when followed as a delegation.
  private val targetCode: ByteString = Assembly(PUSH1, 1, PUSH1, 1, SSTORE, STOP).code

  private val CallGas = 50000

  // CALL(CallGas, callee, 0, 0, 0, 0, 0); SSTORE(0, success); STOP
  private def callerCode(callee: Address): ByteString =
    Assembly(
      PUSH1,
      0,
      PUSH1,
      0,
      PUSH1,
      0,
      PUSH1,
      0,
      PUSH1,
      0,
      PUSH20,
      callee.bytes,
      PUSH3,
      ByteString((CallGas >> 16).toByte, (CallGas >> 8).toByte, CallGas.toByte),
      CALL,
      PUSH1,
      0,
      SSTORE,
      STOP
    ).code

  private val world: MockWorldState = MockWorldState()
    .saveAccount(callerAddr, Account(balance = UInt256(1000000), nonce = 1))
    .saveAccount(designatorAddr, Account(nonce = 1))
    .saveAccount(controlAddr, Account(nonce = 1))
    .saveAccount(targetAddr, Account(nonce = 1))
    .saveCode(designatorAddr, designatorCode)
    .saveCode(controlAddr, controlCode)
    .saveCode(targetAddr, targetCode)

  private def context(
      recipient: Address,
      code: Option[ByteString],
      config: EvmConfig,
      blockNumber: BigInt,
      ts: Long
  ): MockWorldState.PC =
    val w = code.fold(world)(c => world.saveCode(recipient, c))
    ProgramContext(
      callerAddr = callerAddr,
      originAddr = callerAddr,
      recipientAddr = Some(recipient),
      gasPrice = 1,
      startGas = 1000000,
      inputData = ByteString.empty,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = false,
      blockHeader =
        BlockFixtures.ValidBlock.header.copy(number = BlockNumber(blockNumber), unixTimestamp = Timestamp(ts)),
      callDepth = 0,
      world = w,
      initialAddressesToDelete = Set(),
      evmConfig = config,
      originalWorld = w,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

  /** A contract CALLs `callee`; returns (CALL success flag, callee slot 1, total gas used, accessed addresses). */
  private def callVia(callee: Address, config: EvmConfig, blockNumber: BigInt, ts: Long) =
    val ctx = context(callerAddr, Some(callerCode(callee)), config, blockNumber, ts)
    val result = new VM[MockWorldState, MockStorage].run(ctx)
    result.error shouldBe None
    (
      result.world.getStorage(callerAddr).load(0),
      result.world.getStorage(callee).load(1),
      ctx.startGas - result.gasRemaining,
      result.accessedAddresses
    )

  /** The designator account called directly, as a transaction's top-level frame. */
  private def callDirect(callee: Address, config: EvmConfig, blockNumber: BigInt, ts: Long) =
    new VM[MockWorldState, MockStorage].run(context(callee, None, config, blockNumber, ts))

  private def assertInert(config: EvmConfig, blockNumber: BigInt, ts: Long = 0L): Unit =
    config.eip7702Enabled shouldBe false

    // CALL: the frame halts on 0xEF, the CALL fails, the target's code never runs.
    val (success, slot1, gasUsed, accessed) = callVia(designatorAddr, config, blockNumber, ts)
    success shouldBe 0
    slot1 shouldBe 0
    accessed should not contain targetAddr

    // Indistinguishable from any other 0xEF-prefixed code — same failure, same gas (no delegation access charge,
    // all CALL gas consumed by the halt).
    val (controlSuccess, _, controlGasUsed, _) = callVia(controlAddr, config, blockNumber, ts)
    controlSuccess shouldBe 0
    gasUsed shouldBe controlGasUsed

    // Top-level frame: exceptional halt on INVALID 0xEF, all gas consumed.
    val direct = callDirect(designatorAddr, config, blockNumber, ts)
    direct.error shouldBe Some(InvalidOpCode(0xef.toByte))
    direct.gasRemaining shouldBe 0
    direct.world.getStorage(designatorAddr).load(1) shouldBe 0

  private def assertFollowed(config: EvmConfig, blockNumber: BigInt, ts: Long = 0L): Unit =
    config.eip7702Enabled shouldBe true

    val (success, slot1, gasUsed, accessed) = callVia(designatorAddr, config, blockNumber, ts)
    success shouldBe 1
    slot1 shouldBe 1 // target code ran in the designator account's context
    accessed should contain(targetAddr)

    val (controlSuccess, _, controlGasUsed, _) = callVia(controlAddr, config, blockNumber, ts)
    controlSuccess shouldBe 0 // 0xef0200 is never a designator
    gasUsed should not be controlGasUsed

    val direct = callDirect(designatorAddr, config, blockNumber, ts)
    direct.error shouldBe None
    direct.world.getStorage(designatorAddr).load(1) shouldBe 1

  "ETC (block-number dispatch)" when {

    "Magneto (a 0xef0100 contract is still deployable: pre-EIP-3541)" should {
      "execute 0xef0100 code as INVALID, not as a delegation" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        assertInert(EvmConfig.forBlock(Fixtures.MagnetoBlockNumber, blockchainConfig), Fixtures.MagnetoBlockNumber)
      }
    }

    "Spiral (last fork before Olympia)" should {
      "execute 0xef0100 code as INVALID, not as a delegation" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        assertInert(EvmConfig.forBlock(Fixtures.SpiralBlockNumber, blockchainConfig), Fixtures.SpiralBlockNumber)
      }
    }

    "Olympia (ECIP-1121 activates EIP-7702)" should {
      "follow the delegation" taggedAs (UnitTest, VMTest, ConsensusTest, OlympiaTest) in {
        assertFollowed(EvmConfig.forBlock(Fixtures.OlympiaBlockNumber, blockchainConfig), Fixtures.OlympiaBlockNumber)
      }
    }
  }

  "ETH (timestamp dispatch)" when {
    val CancunTs = 2000L
    val PragueTs = 3000L
    val ethConfig = Config.blockchains.blockchainConfig.copy(
      forkTimestamps = ForkTimestamps(
        shanghaiTimestamp = Some(1000L),
        cancunTimestamp = Some(CancunTs),
        pragueTimestamp = Some(PragueTs)
      )
    )

    "Cancun (pre-Prague)" should {
      "execute 0xef0100 code as INVALID, not as a delegation" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        assertInert(EvmConfig.forBlock(0, Timestamp(CancunTs), ethConfig), 0, CancunTs)
      }
    }

    "Prague" should {
      "follow the delegation (unchanged)" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        assertFollowed(EvmConfig.forBlock(0, Timestamp(PragueTs), ethConfig), 0, PragueTs)
      }
    }
  }
