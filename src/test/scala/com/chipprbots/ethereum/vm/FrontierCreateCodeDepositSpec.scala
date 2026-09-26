package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.Assembly.*
import com.chipprbots.ethereum.vm.MockWorldState.*

/** Pins pre-Homestead (Frontier) CREATE code-deposit semantics for ETC.
  *
  * ETC mainnet blocks 0 - 1,149,999 run `FrontierConfigBuilder`, whose `exceptionalFailedCodeDeposit = false`
  * (`EvmConfig.scala`). Under that rule a CREATE whose init code completes but cannot afford `200 * len(code)` to
  * deposit the runtime bytes is treated as a SUCCESS: no code is stored, but the gas is kept, the frame's state changes
  * are kept, and the caller receives the NEW ADDRESS — not zero.
  *
  * go-ethereum `core/vm/instructions.go` `opCreate` states it outright:
  * {{{
  *   // Push item on the stack based on the returned error. If the ruleset is
  *   // homestead we must check for CodeStoreOutOfGasError (homestead only
  *   // rule) and treat as an error, if the ruleset is frontier we must
  *   // ignore this error and pretend the operation was successful.
  *   if evm.chainRules.IsHomestead && suberr == ErrCodeStoreOutOfGas { stackvalue.Clear() }
  *   else if suberr != nil && suberr != ErrCodeStoreOutOfGas { stackvalue.Clear() }
  *   else { stackvalue.SetBytes(addr.Bytes()) }
  * }}}
  * and `core/vm/evm.go` `create()` skips `RevertToSnapshot` on the same condition.
  *
  * This spec exists because nothing in the repo pinned any of it: `CreateOpcodeSpec` builds a Byzantium config
  * (`exceptionalFailedCodeDeposit = true`), no test anywhere constructs `FrontierConfigBuilder`, and the local
  * `ethereum-tests` corpus carries no Frontier CREATE code-deposit vector. A change that set a `ProgramError` on this
  * branch therefore passed every tier while silently diverging ETC consensus — `CreateOp` dispatches on `error` alone,
  * so the caller got 0 instead of the address and the init code's storage writes were discarded.
  */
class FrontierCreateCodeDepositSpec extends AnyFlatSpec with Matchers:

  behavior of "a pre-Homestead CREATE that cannot afford its code deposit"

  it should "return the new contract address to its caller, not zero" taggedAs (UnitTest, VMTest) in new TestSetup:
    shortfall.returnData shouldEqual addressAsWord(childAddr)

  it should "keep the state changes the init code made" taggedAs (UnitTest, VMTest) in new TestSetup:
    shortfall.world.getStorage(childAddr).load(UInt256.Zero) shouldEqual BigInt(42)

  it should "store no code" taggedAs (UnitTest, VMTest) in new TestSetup:
    shortfall.world.getCode(childAddr) shouldEqual ByteString.empty

  it should "not report a ProgramError — Frontier treats this as success" taggedAs (UnitTest, VMTest) in new TestSetup:
    shortfall.error shouldBe None

  it should "not burn the remaining gas" taggedAs (UnitTest, VMTest) in new TestSetup:
    shortfall.gasRemaining should be > BigInt(0)

  it should "not leak codeDepositShortfall out of the nested frame" taggedAs (UnitTest, VMTest) in new TestSetup:
    // go-ethereum's `opCreate` discards `ErrCodeStoreOutOfGas` rather than propagating it, so a nested CREATE's
    // shortfall never reaches the top-level result. Only a top-level contract creation carries the flag, and only
    // gas estimation reads it.
    shortfall.codeDepositShortfall shouldBe false

  behavior of "the same CREATE when the code deposit IS affordable"

  it should "also return the new contract address" taggedAs (UnitTest, VMTest) in new TestSetup:
    affordable.returnData shouldEqual addressAsWord(childAddr)

  it should "keep the init code's state changes and store the code" taggedAs (UnitTest, VMTest) in new TestSetup:
    affordable.world.getStorage(childAddr).load(UInt256.Zero) shouldEqual BigInt(42)
    affordable.world.getCode(childAddr).size shouldEqual runtimeCodeSize

  trait TestSetup:
    val vm: TestVM = new VM[MockWorldState, MockStorage]()

    /** Pure Frontier: `frontierBlockNumber = 0`, every later fork at the `Long.MaxValue` sentinel. This is the only
      * configuration in which `exceptionalFailedCodeDeposit` is false.
      */
    val blockchainConfig: BlockchainConfigForEvm = BlockchainConfigForEvm(
      frontierBlockNumber = 0,
      homesteadBlockNumber = Long.MaxValue,
      eip150BlockNumber = Long.MaxValue,
      eip160BlockNumber = Long.MaxValue,
      eip161BlockNumber = Long.MaxValue,
      byzantiumBlockNumber = Long.MaxValue,
      constantinopleBlockNumber = Long.MaxValue,
      istanbulBlockNumber = Long.MaxValue,
      maxCodeSize = None,
      accountStartNonce = 0,
      atlantisBlockNumber = Long.MaxValue,
      aghartaBlockNumber = Long.MaxValue,
      petersburgBlockNumber = Long.MaxValue,
      phoenixBlockNumber = Long.MaxValue,
      magnetoBlockNumber = Long.MaxValue,
      berlinBlockNumber = Long.MaxValue,
      mystiqueBlockNumber = Long.MaxValue,
      spiralBlockNumber = Long.MaxValue,
      olympiaBlockNumber = Long.MaxValue,
      chainId = ChainId(0x3d)
    )

    val config: EvmConfig = EvmConfig.forBlock(1, blockchainConfig)
    // Guards the premise of the whole spec: if this ever becomes true, these tests are exercising the Homestead
    // branch and prove nothing.
    config.exceptionalFailedCodeDeposit shouldBe false

    val blockHeader: BlockHeader = ValidBlock.header.copy(
      number = BlockNumber(1),
      gasLimit = GasAmount(10000000),
      unixTimestamp = Timestamp(0)
    )

    /** Runtime code size chosen so the deposit (200/byte = 200,000) is far beyond the gas the shortfall run has. */
    val runtimeCodeSize: Int = 1000

    /** `SSTORE(0, 42)` then `RETURN` `runtimeCodeSize` zero bytes. The SSTORE is the observable state change: under
      * Frontier it must survive the failed deposit.
      */
    val initCode: ByteString = Assembly(
      PUSH1,
      42,
      PUSH1,
      0,
      SSTORE,
      PUSH2,
      (runtimeCodeSize >> 8) & 0xff,
      runtimeCodeSize & 0xff,
      PUSH1,
      0,
      RETURN
    ).code

    /** CODECOPY the init code into memory, CREATE from it, MSTORE whatever CREATE pushed, RETURN those 32 bytes — so
      * the caller's stack value is observable as `returnData`.
      */
    val creatorPrefix: ByteString = Assembly(
      PUSH1,
      initCode.size,
      PUSH1,
      22, // offset of initCode within creatorCode; asserted below
      PUSH1,
      0,
      CODECOPY,
      PUSH1,
      initCode.size,
      PUSH1,
      0,
      PUSH1,
      0,
      CREATE,
      PUSH1,
      0,
      MSTORE,
      PUSH1,
      32,
      PUSH1,
      0,
      RETURN
    ).code
    creatorPrefix.size shouldEqual 22

    val creatorCode: ByteString = creatorPrefix ++ initCode

    val senderAddr: Address = Address(0xcafebabeL)
    val creatorAddr: Address = Address(0xc0ffeeL)

    val world: MockWorldState = MockWorldState()
      .saveAccount(senderAddr, Account(nonce = 1, balance = 1000000))
      .saveAccount(creatorAddr, Account(nonce = 1, balance = 0))
      .saveCode(creatorAddr, creatorCode)

    def context(startGas: BigInt): ProgramContext[MockWorldState, MockStorage] =
      ProgramContext(
        callerAddr = senderAddr,
        originAddr = senderAddr,
        recipientAddr = Some(creatorAddr),
        gasPrice = 1,
        startGas = startGas,
        inputData = ByteString.empty,
        value = 0,
        endowment = 0,
        doTransfer = true,
        blockHeader = blockHeader,
        callDepth = 0,
        world = world,
        initialAddressesToDelete = Set(),
        evmConfig = config,
        originalWorld = world,
        warmAddresses = Set.empty,
        warmStorage = Set.empty
      )

    /** 60,000 gas leaves the child well short of the 200,000 code deposit. */
    val shortfall: ProgramResult[MockWorldState, MockStorage] = vm.run(context(60000))

    /** 400,000 gas covers the deposit. Serves as the control that establishes the address the VM derives for this
      * CREATE, so the assertions above are not pinned to a hard-coded hash.
      */
    val affordable: ProgramResult[MockWorldState, MockStorage] = vm.run(context(400000))

    val childAddr: Address = Address(affordable.returnData.takeRight(Address.Length))
    // Non-vacuity guard: if the control ever pushed 0 too, every assertion above would compare zero to zero.
    childAddr should not be Address(0)

    /** The 32-byte word a CREATE pushes for a successful creation. */
    def addressAsWord(address: Address): ByteString = address.toUInt256.bytes
