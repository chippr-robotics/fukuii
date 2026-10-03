package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** EIP-8246 (Amsterdam, #1422): SELFDESTRUCT no longer burns.
  *
  *   - a same-transaction SELFDESTRUCT to self keeps the balance;
  *   - at finalization each account marked for deletion (EIP-6780: created in the same transaction) gets nonce 0, its
  *     code and storage cleared, and KEEPS its balance; with a zero balance it is empty and EIP-161 removes it.
  *
  * Reference: execution-specs `forks/amsterdam` (`selfdestruct`, `clear_account_preserving_balance`), go-ethereum
  * `opSelfdestruct6780` / `finaliseAmsterdam`. Case numbers are the EIP's own "Test Cases" list. The same scenarios on
  * the pre-Amsterdam schedule show the EIP-6780 burn, which must not change.
  */
// scalastyle:off magic.number
class AmsterdamSelfDestructSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))
  private val osakaHeader = preAmsterdamHeader(500, 300).copy(gasLimit = GasAmount(60_000_000))

  private val Entry: Address = Address(0xe0e0)
  private val Other: Address = Address(0x0bee)
  private val X: BigInt = 7 // the victim's endowment

  // ── A few opcodes, enough to script the scenarios ───────────────────────────────────────────

  private def op(b: Int): ByteString = ByteString(b.toByte)
  private def push(bytes: ByteString): ByteString = ByteString((0x5f + bytes.length).toByte) ++ bytes
  private def push(v: BigInt): ByteString =
    push(ByteString(v.toByteArray.dropWhile(_ == 0).padTo(1, 0.toByte)))
  private def push(a: Address): ByteString = push(a.bytes)

  private val MSTORE = op(0x52)
  private val POP = op(0x50)
  private val GAS = op(0x5a)
  private val CALL = op(0xf1)
  private val CREATE = op(0xf0)
  private val CREATE2 = op(0xf5)

  /** Victim runtime code: `if calldata is empty: STOP; else SELFDESTRUCT(CALLDATALOAD(0))`. A plain value send just
    * lands; a call carrying a 32-byte beneficiary self-destructs to it.
    */
  private val VictimRuntime = ByteString(0x36, 0x15, 0x60, 0x09, 0x57, 0x60, 0x00, 0x35, 0xff.toByte, 0x5b, 0x00)

  /** Initcode returning [[VictimRuntime]], after an optional prefix that runs first in the creation frame. */
  private def deployVictim(prefix: ByteString = ByteString.empty): ByteString =
    prefix ++ push(VictimRuntime) ++ push(0) ++ MSTORE ++ push(VictimRuntime.length) ++
      push(32 - VictimRuntime.length) ++ op(0xf3)

  /** `SELFDESTRUCT(ADDRESS)` straight from the initcode: the account never gets code. */
  private val InitcodeSelfDestructToSelf = ByteString(0x30, 0xff.toByte)

  private def create(initcode: ByteString, value: BigInt): ByteString =
    push(initcode) ++ push(0) ++ MSTORE ++ push(initcode.length) ++ push(32 - initcode.length) ++ push(value) ++
      CREATE ++ POP

  private def create2(initcode: ByteString, value: BigInt): ByteString =
    push(initcode) ++ push(0) ++ MSTORE ++ push(0) ++ push(initcode.length) ++ push(32 - initcode.length) ++
      push(value) ++ CREATE2 ++ POP

  private def call(to: Address, value: BigInt = 0, beneficiary: Option[Address] = None): ByteString =
    val args = beneficiary.fold(ByteString.empty)(b => push(b) ++ push(0) ++ MSTORE)
    val argsSize = if beneficiary.isDefined then 32 else 0
    args ++ push(0) ++ push(0) ++ push(argsSize) ++ push(0) ++ push(value) ++ push(to) ++ GAS ++ CALL ++ POP

  // ── Running a scenario ───────────────────────────────────────────────────────────────────────

  /** An EIP-161 world, as the node builds one for any post-Spurious-Dragon ETH block: a created contract starts at
    * nonce 1, and an emptied one is removed. (The shared test world has `noEmptyAccounts = false`, under which a
    * contract drained by its SELFDESTRUCT would look dead — not a world Amsterdam can see.)
    */
  private def worldWithEntry(code: ByteString): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      setup.storagesInstance.storages.evmCodeStorage,
      setup.blockchain.getBackingMptStorage(-1),
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = true,
      ethCompatibleStorage = true
    ).saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000"))))
      .saveAccount(Entry, Account(nonce = UInt256(1), balance = UInt256(100), codeHash = CodeHash(kec256(code))))
      .saveCode(Entry, code)

  /** The address `Entry`'s first CREATE deploys to (creator nonce 1). */
  private lazy val Victim: Address = worldWithEntry(ByteString.empty).increaseNonce(Entry).createAddress(Entry)

  private def run(
      entryCode: ByteString,
      config: BlockchainConfig = amsterdamConfig,
      hdr: BlockHeader = header
  ): TxResult =
    val stx = dynamicFeeTx(Some(Entry), value = 0, gasLimit = 3_000_000, config = config)
    execute(stx, hdr, worldWithEntry(entryCode), config)

  private def balanceOnly(result: TxResult, address: Address, balance: BigInt) =
    val account = result.worldState.getAccount(address)
    account.map(_.balance) shouldBe Some(UInt256(balance))
    account.map(_.nonce) shouldBe Some(UInt256(0))
    account.map(_.codeHash) shouldBe Some(Account.EmptyCodeHash)
    account.map(_.storageRoot) shouldBe Some(Account.EmptyStorageRootHash)
    result.worldState.getCode(address) shouldBe ByteString.empty

  // ── Instruction level ────────────────────────────────────────────────────────────────────────

  "a same-transaction SELFDESTRUCT to self (case 1)" should "keep the balance in a balance-only account" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = run(create(InitcodeSelfDestructToSelf, X))
    result.vmError shouldBe None
    balanceOnly(result, Victim, X)
  }

  it should "emit no EIP-7708 transfer log for the self-destruct, only for the endowment" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = run(create(InitcodeSelfDestructToSelf, X))
    // The CREATE endowment Entry -> Victim is the only value movement.
    result.logs.map(_.loggerAddress) shouldBe Seq(SystemAddress)
    result.logs.head.logTopics.drop(1) shouldBe Seq(
      ByteString(new Array[Byte](12)) ++ Entry.bytes,
      ByteString(new Array[Byte](12)) ++ Victim.bytes
    )
  }

  it should "still burn before Amsterdam (EIP-6780 unchanged)" taggedAs (VMTest, ConsensusTest) in {
    val result = run(create(InitcodeSelfDestructToSelf, X), preAmsterdamConfig, osakaHeader)
    result.vmError shouldBe None
    result.worldState.getAccount(Victim) shouldBe None
    result.worldState.getBalance(Entry) shouldBe UInt256(100 - X)
  }

  "a selfdestruct to self followed by one to another account (case 3)" should "move the kept balance and leave nothing" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result =
      run(
        create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Victim)) ++ call(Victim, beneficiary = Some(Other))
      )
    result.vmError shouldBe None
    result.worldState.getAccount(Victim) shouldBe None
    result.worldState.getBalance(Other) shouldBe UInt256(X)
  }

  "a selfdestruct to another account followed by one to self (case 4)" should "leave an empty account, removed" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result =
      run(
        create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Other)) ++ call(Victim, beneficiary = Some(Victim))
      )
    result.vmError shouldBe None
    result.worldState.getAccount(Victim) shouldBe None
    result.worldState.getBalance(Other) shouldBe UInt256(X)
  }

  // ── Transaction finalization ─────────────────────────────────────────────────────────────────

  "ether sent after a selfdestruct to another account (case 5)" should "stay with the account" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = run(create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Other)) ++ call(Victim, value = 1))
    result.vmError shouldBe None
    balanceOnly(result, Victim, 1)
    result.worldState.getBalance(Other) shouldBe UInt256(X)
  }

  it should "be burned before Amsterdam" taggedAs (VMTest, ConsensusTest) in {
    val result = run(
      create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Other)) ++ call(Victim, value = 1),
      preAmsterdamConfig,
      osakaHeader
    )
    result.vmError shouldBe None
    result.worldState.getAccount(Victim) shouldBe None
    result.worldState.getBalance(Entry) shouldBe UInt256(100 - X - 1)
  }

  "ether sent after a selfdestruct to another account, then another such selfdestruct (case 7)" should "follow the second selfdestruct out" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = run(
      create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Other)) ++ call(Victim, value = 1) ++
        call(Victim, beneficiary = Some(Other))
    )
    result.vmError shouldBe None
    result.worldState.getAccount(Victim) shouldBe None
    result.worldState.getBalance(Other) shouldBe UInt256(X + 1)
  }

  "ether sent after a selfdestruct to self (cases 9, 10)" should "add to the kept balance" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val result = run(
      create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Victim)) ++ call(Victim, value = 1) ++
        call(Victim, value = 1)
    )
    result.vmError shouldBe None
    balanceOnly(result, Victim, X + 2)
  }

  "ether sent between two selfdestructs to self (case 12)" should "all be kept" taggedAs (VMTest, ConsensusTest) in {
    val result = run(
      create(deployVictim(), X) ++ call(Victim, beneficiary = Some(Victim)) ++ call(Victim, value = 1) ++
        call(Victim, beneficiary = Some(Victim))
    )
    result.vmError shouldBe None
    balanceOnly(result, Victim, X + 1)
  }

  "an account that bumped its own nonce by creating (case 13)" should "come out with nonce 0" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // The initcode CREATEs an empty child first, taking the victim's nonce from 1 to 2.
    val bumpNonce = push(0) ++ push(0) ++ push(0) ++ CREATE ++ POP
    val result = run(create(deployVictim(bumpNonce), X) ++ call(Victim, beneficiary = Some(Victim)))
    result.vmError shouldBe None
    balanceOnly(result, Victim, X)
  }

  "an account that wrote storage (case 15)" should "keep only its balance: the storage is cleared" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val sstore = push(1) ++ push(0) ++ op(0x55) // SSTORE(0, 1)
    val result = run(create(deployVictim(sstore), X) ++ call(Victim, beneficiary = Some(Victim)))
    result.vmError shouldBe None
    balanceOnly(result, Victim, X)
    result.worldState.getStorage(Victim).load(0) shouldBe BigInt(0)
  }

  it should "be removed altogether when it self-destructs to another account (case 16)" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    val sstore = push(1) ++ push(0) ++ op(0x55)
    val result = run(create(deployVictim(sstore), X) ++ call(Victim, beneficiary = Some(Other)))
    result.vmError shouldBe None
    result.worldState.getAccount(Victim) shouldBe None
    result.worldState.getBalance(Other) shouldBe UInt256(X)
  }

  // ── Multi-transaction ────────────────────────────────────────────────────────────────────────

  "a CREATE2 over a balance-only remnant (case 17)" should "succeed and keep both endowments" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    // Tx 1 and tx 2 are the same: CREATE2 with value X, initcode SELFDESTRUCT(ADDRESS). The remnant tx 1 leaves has
    // nonce 0, no code and no storage, so it is not a collision; the second creation lands on it and keeps it all.
    val code = create2(InitcodeSelfDestructToSelf, X)
    val world = worldWithEntry(code)
    val victim2 = Address(
      kec256(
        ByteString(0xff.toByte) ++ Entry.bytes ++ ByteString(new Array[Byte](32)) ++ kec256(InitcodeSelfDestructToSelf)
      )
    )
    val txs = Seq(
      dynamicFeeTx(Some(Entry), value = 0, gasLimit = 3_000_000, nonce = 0, config = amsterdamConfig),
      dynamicFeeTx(Some(Entry), value = 0, gasLimit = 3_000_000, nonce = 1, config = amsterdamConfig)
    )
    val block = executeBlock(txs, header, world, amsterdamConfig)
    block.receipts.map(_.postTransactionStateHash) shouldBe Seq(SuccessOutcome, SuccessOutcome)
    val account = block.worldState.getAccount(victim2)
    account.map(_.balance) shouldBe Some(UInt256(2 * X))
    account.map(_.nonce) shouldBe Some(UInt256(0))
    block.worldState.getCode(victim2) shouldBe ByteString.empty
  }
