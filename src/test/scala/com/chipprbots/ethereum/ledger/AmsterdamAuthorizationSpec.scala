package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.SetCodeTransaction.addressToDelegation
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.AmsterdamGas
import com.chipprbots.ethereum.vm.OutOfGas

/** EIP-2780's pre-execution phase for Type-4 transactions at Amsterdam (#1423), against execution-specs
  * `forks/amsterdam` (`set_delegation`, `create_evm`, `process_top_level`).
  *
  * Every figure is derived from execution-specs' rules, not measured from fukuii:
  *   - intrinsic: TX_BASE 12,000 + COLD_ACCOUNT_ACCESS 3,000 (+ TX_VALUE_COST 6,000 with value) + 7,816 per tuple;
  *   - per valid tuple, as it is applied: NEW_ACCOUNT 183,600 (state) if the authority does not exist, ACCOUNT_WRITE
  *     9,000 once per authority whose write is not already paid, AUTH_BASE 35,190 (state) for a net-new indicator;
  *   - then the frame commits, and the dispatch pays the delegation-target access: 100 warm / 3,000 cold.
  * A shortfall anywhere before the frame is entered rolls the whole phase back, authorizations included; a failure
  * after entry keeps them, and keeps their (committed) state gas charged.
  */
// scalastyle:off magic.number
class AmsterdamAuthorizationSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))

  private val NewAccount = AmsterdamGas.GasNewAccount // 183,600
  private val AuthBase = AmsterdamGas.GasAuthBase // 35,190
  private val AccountWrite = AmsterdamGas.AccountWrite // 9,000
  private val PerAuth = AmsterdamGas.ExecutionPerAuthBaseCost // 7,816

  /** A contract whose code is a lone STOP: the delegation target and the plain recipient of these vectors. */
  private val Stop: Address = Address(0x5700)

  /** A contract whose code is INVALID: entering it is an exceptional halt AFTER the pre-execution phase. */
  private val Halt: Address = Address(0x5701)
  private val Target2: Address = Address(0x5702)

  private def withCode(world: InMemoryWorldStateProxy, addr: Address, code: ByteString, nonce: BigInt = 1) =
    world
      .saveAccount(addr, Account(nonce = UInt256(nonce), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(addr, code)

  private def baseWorld: InMemoryWorldStateProxy =
    val w1 = withCode(block45World, Stop, ByteString(0x00))
    val w2 = withCode(w1, Halt, ByteString(0xfe.toByte))
    withCode(w2, Target2, ByteString(0x00))

  private def freshKey(): AsymmetricCipherKeyPair = generateKeyPair(setup.secureRandom)

  private def auth(keys: AsymmetricCipherKeyPair, target: Address, nonce: BigInt): SetCodeAuthorization =
    val chainId = amsterdamConfig.chainId.value
    val sigHash = kec256(
      encode(
        PrefixedRLPEncodable(0x05, RLPList(toEncodeable(chainId), toEncodeable(target.toArray), toEncodeable(nonce)))
      )
    )
    val sig = ECDSASignature.sign(sigHash, keys)
    val yParity = if sig.v == ECDSASignature.negativePointSign then BigInt(0) else BigInt(1)
    SetCodeAuthorization(chainId, target, nonce, yParity, sig.r, sig.s)

  private def setCodeTx(
      to: Address,
      authList: List[SetCodeAuthorization],
      gasLimit: BigInt,
      value: BigInt = 0
  ): SignedTransaction =
    val tx = SetCodeTransaction(
      chainId = amsterdamConfig.chainId.value,
      nonce = 0,
      maxPriorityFeePerGas = BigInt(0),
      maxFeePerGas = BigInt(1_000_000_000),
      gasLimit = GasAmount(gasLimit),
      receivingAddress = Some(to),
      value = value,
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = authList
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(amsterdamConfig.chainId.value))

  private def run(stx: SignedTransaction, world: InMemoryWorldStateProxy = baseWorld): TxResult =
    execute(stx, header, world, amsterdamConfig)

  // ── Failure after frame entry: the delegations stay ─────────────────────────────────────────

  "a Type-4 transaction whose frame halts" should "keep its delegations, and their state gas stays charged" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val a = freshKey()
    val result = run(setCodeTx(Halt, List(auth(a, Stop, 0)), gasLimit = 500_000))

    result.vmError should not be empty
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Stop)
    result.worldState.getAccount(Address(a)).map(_.nonce) shouldBe Some(UInt256(1))
    // The halt consumes everything; the committed authorization state gas is NOT refilled by the rollback.
    result.gasUsed shouldBe BigInt(500_000)
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.executionGasUsed shouldBe BigInt(500_000) - (NewAccount + AuthBase)
  }

  it should "do the same with the charges drawn from a reservoir" taggedAs (VMTest, ConsensusTest) in {
    // EEST `auth_state_charges_survive_dispatch_halt_with_reservoir`: 300,000 of reservoir pays the 218,790; the halt
    // restores the reservoir only to the post-commit baseline (81,210), which goes back to the sender.
    val a = freshKey()
    val result = run(setCodeTx(Halt, List(auth(a, Stop, 0)), gasLimit = AmsterdamGas.TxMaxGasLimit + 300_000))

    result.vmError should not be empty
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Stop)
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.executionGasUsed shouldBe AmsterdamGas.TxMaxGasLimit
    result.gasUsed shouldBe AmsterdamGas.TxMaxGasLimit + NewAccount + AuthBase
  }

  // ── Failure before frame entry: everything is rolled back ───────────────────────────────────

  "a Type-4 transaction that runs out of gas while charging its authorizations" should "roll back every delegation and never reach the recipient" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // Intrinsic 36,632 (value-bearing, two tuples). The first tuple's 227,790 fits; the second's NEW_ACCOUNT does not.
    val (a1, a2) = (freshKey(), freshKey())
    val recipient = Address(0xbeef01)
    val gasLimit = BigInt(36_632) + (NewAccount + AccountWrite + AuthBase) + 100_000
    val stx = setCodeTx(recipient, List(auth(a1, Stop, 0), auth(a2, Stop, 0)), gasLimit, value = 1)
    val world = baseWorld
    val result = run(stx, world)

    result.vmError shouldBe Some(OutOfGas)
    result.worldState.getAccount(Address(a1)) shouldBe None
    result.worldState.getAccount(Address(a2)) shouldBe None
    // The recipient was never dispatched to: no account, no value.
    result.worldState.getAccount(recipient) shouldBe None
    result.gasUsed shouldBe gasLimit
    result.stateGasUsed shouldBe BigInt(0)
    result.executionGasUsed shouldBe gasLimit
    // The sender pays the gas and bumps its nonce; the 1 wei never left.
    val before = world.getBalance(senderAddress).toBigInt
    result.worldState.getBalance(senderAddress).toBigInt shouldBe before - gasLimit * 7
    result.worldState.getAccount(senderAddress).map(_.nonce) shouldBe Some(UInt256(1))
  }

  "the dispatch's delegation-target access" should "be charged, cold, after the authorizations commit" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The authority is the recipient: once its tuple applies, the top frame resolves a delegation to a cold target.
    // Intrinsic 22,816 + 227,790 + 3,000 cold access; STOP costs nothing.
    val a = freshKey()
    val gasLimit = BigInt(22_816) + NewAccount + AccountWrite + AuthBase + 3000
    val result = run(setCodeTx(Address(a), List(auth(a, Stop, 0)), gasLimit))

    result.vmError shouldBe None
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Stop)
    result.gasUsed shouldBe gasLimit
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.executionGasUsed shouldBe BigInt(22_816) + AccountWrite + 3000
  }

  it should "roll the authorizations back when IT is what runs out of gas" taggedAs (VMTest, ConsensusTest) in {
    // One gas short of the access: a pre-execution failure after the commit (EEST
    // `preparation_rollback_restores_block_state_budget[...failure_point_dispatch_access]`). The state gas the
    // commit protected is refilled with everything else, so nothing counts toward the block's state dimension.
    val a = freshKey()
    val gasLimit = BigInt(22_816) + NewAccount + AccountWrite + AuthBase + 3000 - 1
    val result = run(setCodeTx(Address(a), List(auth(a, Stop, 0)), gasLimit))

    result.vmError shouldBe Some(OutOfGas)
    result.worldState.getAccount(Address(a)) shouldBe None
    result.gasUsed shouldBe gasLimit
    result.stateGasUsed shouldBe BigInt(0)
  }

  it should "cost 100 when the target is warm, 3,000 when cold, for any call to a delegated account" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // EEST `transaction_gas[...DELEGATION_7702]` (18,000) and `top_frame_charges_delegation_in_access_list`.
    val delegated = Address(0xde1e)
    val world = withCode(baseWorld, delegated, addressToDelegation(Stop))
    val cold =
      execute(dynamicFeeTx(Some(delegated), 0, 100_000, config = amsterdamConfig), header, world, amsterdamConfig)
    cold.vmError shouldBe None
    cold.gasUsed shouldBe BigInt(15_000 + 3000)

    // Stop in the access list: 2,900 per address + 1,280 of EIP-7981 data cost, then a warm 100.
    val warmTx = dynamicFeeTx(
      Some(delegated),
      0,
      100_000,
      accessList = List(AccessListItem(Stop, Nil)),
      config = amsterdamConfig
    )
    val warm = execute(warmTx, header, world, amsterdamConfig)
    warm.vmError shouldBe None
    warm.gasUsed shouldBe BigInt(15_000 + 2900 + 1280 + 100)
  }

  // ── Sequential validation and the per-tuple charges ─────────────────────────────────────────

  "the authorization list" should "be validated in order: a clear, then a set with the next nonce, pays AUTH_BASE" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The second tuple (nonce 1) is only valid once the first has applied. Validated against the transaction-start
    // world it would be skipped and its AUTH_BASE lost.
    val a = freshKey()
    val gasLimit = BigInt(300_000)
    val result = run(setCodeTx(Stop, List(auth(a, Address(0L), 0), auth(a, Stop, 1)), gasLimit))

    result.vmError shouldBe None
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Stop)
    result.worldState.getAccount(Address(a)).map(_.nonce) shouldBe Some(UInt256(2))
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.gasUsed shouldBe BigInt(15_000 + 2 * 7816) + NewAccount + AccountWrite + AuthBase
  }

  it should "charge a set -> clear -> set sequence for one authority exactly once" taggedAs (VMTest, ConsensusTest) in {
    val a = freshKey()
    val result = run(setCodeTx(Stop, List(auth(a, Stop, 0), auth(a, Address(0L), 1), auth(a, Target2, 2)), 400_000))

    result.vmError shouldBe None
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Target2)
    result.worldState.getAccount(Address(a)).map(_.nonce) shouldBe Some(UInt256(3))
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.gasUsed shouldBe BigInt(15_000) + 3 * PerAuth + NewAccount + AccountWrite + AuthBase
  }

  it should "skip a duplicate tuple whose nonce the first one used up" taggedAs (VMTest, ConsensusTest) in {
    val a = freshKey()
    val result = run(setCodeTx(Stop, List(auth(a, Stop, 0), auth(a, Target2, 0)), 400_000))

    result.vmError shouldBe None
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Stop)
    result.worldState.getAccount(Address(a)).map(_.nonce) shouldBe Some(UInt256(1))
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.gasUsed shouldBe BigInt(15_000) + 2 * PerAuth + NewAccount + AccountWrite + AuthBase
  }

  it should "charge NEW_ACCOUNT by existence, not emptiness" taggedAs (VMTest, ConsensusTest) in {
    // execution-specs `account_exists`: an authority that is present but empty is not a new leaf.
    val a = freshKey()
    val world = baseWorld.saveAccount(Address(a), Account(nonce = UInt256(0), balance = UInt256(0)))
    val result = run(setCodeTx(Stop, List(auth(a, Stop, 0)), 300_000), world)

    result.vmError shouldBe None
    result.stateGasUsed shouldBe AuthBase
    result.gasUsed shouldBe BigInt(15_000) + PerAuth + AccountWrite + AuthBase
  }

  "an authority that is the sender" should "pay only AUTH_BASE: it exists and its write is paid by TX_BASE" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The sender's nonce is already bumped when the list is processed, so its own tuple carries nonce 1.
    val result = run(setCodeTx(Stop, List(auth(senderKeyPair, Stop, 1)), 300_000))

    result.vmError shouldBe None
    result.worldState.getCode(senderAddress) shouldBe addressToDelegation(Stop)
    result.worldState.getAccount(senderAddress).map(_.nonce) shouldBe Some(UInt256(2))
    result.stateGasUsed shouldBe AuthBase
    result.gasUsed shouldBe BigInt(15_000) + PerAuth + AuthBase
  }

  "an authority that is the value-bearing recipient" should "pay NEW_ACCOUNT but not ACCOUNT_WRITE, and the dispatch pays the access" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // EEST `tx_installs_delegation_on_empty_recipient[non-zero_value]`. TX_VALUE_COST paid the recipient's write, but
    // the account still does not exist when its tuple applies. After it applies the recipient is alive, so the value
    // transfer creates nothing further; the dispatch then resolves the freshly set delegation (cold, 3,000).
    val a = freshKey()
    val result = run(setCodeTx(Address(a), List(auth(a, Stop, 0)), 1_000_000, value = 1))

    result.vmError shouldBe None
    result.worldState.getCode(Address(a)) shouldBe addressToDelegation(Stop)
    result.worldState.getBalance(Address(a)) shouldBe UInt256(1)
    result.stateGasUsed shouldBe NewAccount + AuthBase
    result.gasUsed shouldBe BigInt(12_000 + 3000 + 6000) + PerAuth + NewAccount + AuthBase + 3000
  }

  "an authority that is the zero-value recipient" should "pay ACCOUNT_WRITE: nothing else writes it first" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // EEST `account_write_authority_is_recipient[zero_value]`, with a funded recipient: no NEW_ACCOUNT.
    val a = freshKey()
    val world = baseWorld.saveAccount(Address(a), Account(nonce = UInt256(0), balance = UInt256(100)))
    val result = run(setCodeTx(Address(a), List(auth(a, Stop, 0)), 300_000), world)

    result.vmError shouldBe None
    result.stateGasUsed shouldBe AuthBase
    result.gasUsed shouldBe BigInt(15_000) + PerAuth + AccountWrite + AuthBase + 3000
  }
