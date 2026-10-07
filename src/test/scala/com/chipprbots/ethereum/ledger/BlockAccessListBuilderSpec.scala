package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockAccessList.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.BlockAccessRecorder

/** EIP-7928 per-index diff netting — the part the corpus cannot isolate, because a fixture only ever sees the header
  * hash, never how a block access index's reads and writes were netted into the list.
  *
  * The builder folds one index at a time: what its [[BlockAccessRecorder]] read, and every change between the state the
  * index started from and the state it ended on, for the accounts and slots it touched. These vectors drive
  * [[BlockAccessListBuilder.addIndex]] with hand-built before/after worlds, so each netting rule (execution-specs
  * `update_builder_from_tx`, HEAD a9792ab) is checked on its own.
  */
class BlockAccessListBuilderSpec extends AnyFlatSpec with Matchers:

  private trait Setup extends EphemBlockchainTestSetup:
    def emptyWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    def persist(w: InMemoryWorldStateProxy): InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(w)

    def onlyAccount(builder: BlockAccessListBuilder): AccountChanges =
      val built = builder.build
      built.accounts should have size 1
      built.accounts.head

  private def addr(last: Int): Address = Address(ByteString(Array.fill[Byte](19)(0) :+ last.toByte))

  private def recorderFor(accounts: Seq[Address], slots: Seq[(Address, UInt256)] = Nil): BlockAccessRecorder =
    val r = new BlockAccessRecorder
    accounts.foreach(r.recordAccount)
    slots.foreach { case (a, s) => r.recordSlot(a, s) }
    r

  "the per-index diff" should "record a balance change only when the balance actually moves" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val alice = addr(1)
    val before = persist(emptyWorld.saveAccount(alice, Account.empty().increaseBalance(UInt256(100))))
    val after = persist(before.saveAccount(alice, before.getGuaranteedAccount(alice).increaseBalance(UInt256(5))))
    val builder = new BlockAccessListBuilder
    builder.addIndex(1L, recorderFor(Seq(alice)), before, after)
    val account = onlyAccount(builder)
    account.address shouldBe alice
    account.balanceChanges shouldBe Seq(BalanceChange(1L, UInt256(105)))
    account.nonceChanges shouldBe empty

  it should "record the account with empty change lists when it is read but nothing changes" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val bob = addr(2)
    val world = persist(emptyWorld.saveAccount(bob, Account.empty().increaseBalance(UInt256(7))))
    val builder = new BlockAccessListBuilder
    // Same world before and after: a pure read (e.g. a zero-value transfer recipient, or an EXTCODEHASH target).
    builder.addIndex(1L, recorderFor(Seq(bob)), world, world)
    val account = onlyAccount(builder)
    account.address shouldBe bob
    account.balanceChanges shouldBe empty
    account.nonceChanges shouldBe empty
    account.storageChanges shouldBe empty
    account.storageReads shouldBe empty

  it should "record nonce and code changes for a freshly deployed contract" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val deployed = addr(3)
    val code = ByteString(0x60, 0x00, 0x60, 0x00)
    val before = persist(emptyWorld)
    val after = persist(
      before.saveAccount(deployed, Account.empty().copy(nonce = UInt256(1))).saveCode(deployed, code)
    )
    val builder = new BlockAccessListBuilder
    builder.addIndex(2L, recorderFor(Seq(deployed)), before, after)
    val account = onlyAccount(builder)
    account.nonceChanges shouldBe Seq(NonceChange(2L, BigInt(1)))
    account.codeChanges shouldBe Seq(CodeChange(2L, code))

  it should "net a storage write against the value the index started from" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val c = addr(4)
    val before = persist(emptyWorld.saveAccount(c, Account.empty()))
    // slot 1: 0 -> 9 (a real change); slot 2: read only.
    val after = persist(before.saveStorage(c, before.getStorage(c).store(1, 9)))
    val builder = new BlockAccessListBuilder
    builder.addIndex(1L, recorderFor(Seq(c), slots = Seq(c -> UInt256(1), c -> UInt256(2))), before, after)
    val account = onlyAccount(builder)
    account.storageChanges shouldBe Seq(SlotChanges(UInt256(1), Seq(StorageChange(1L, UInt256(9)))))
    account.storageReads shouldBe Seq(UInt256(2)) // slot 1 excluded: it is written

  it should "demote a slot written and restored within the index to a read (net-zero)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val c = addr(5)
    // slot 1 holds 9 at the index start; within the index the tx set it to 0 then back to 9, so the two ends of the
    // index carry the same value and the write nets to no change — it surfaces as a read.
    val start = persist(emptyWorld.saveAccount(c, Account.empty()).saveStorage(c, emptyWorld.getStorage(c).store(1, 9)))
    val end = start
    val builder = new BlockAccessListBuilder
    builder.addIndex(1L, recorderFor(Seq(c), slots = Seq(c -> UInt256(1))), start, end)
    val account = onlyAccount(builder)
    account.storageChanges shouldBe empty
    account.storageReads shouldBe Seq(UInt256(1))

  it should "turn a selfdestructed-in-tx account's storage writes into reads" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    // A contract created and destroyed in the same index: its slots are recorded (they were touched) but end where
    // they started (at 0), so they surface as reads, not changes (EIP-7928 in-transaction SELFDESTRUCT).
    val victim = addr(6)
    val world = persist(emptyWorld)
    val builder = new BlockAccessListBuilder
    builder.addIndex(
      1L,
      recorderFor(Seq(victim), slots = Seq(victim -> UInt256(1), victim -> UInt256(2))),
      world,
      world
    )
    val account = onlyAccount(builder)
    account.storageChanges shouldBe empty
    account.storageReads shouldBe Seq(UInt256(1), UInt256(2))
    account.balanceChanges shouldBe empty

  it should "keep a separate change entry per index for one account touched twice" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val coinbase = addr(7)
    val start = persist(emptyWorld.saveAccount(coinbase, Account.empty()))
    val afterTx1 =
      persist(start.saveAccount(coinbase, start.getGuaranteedAccount(coinbase).increaseBalance(UInt256(3))))
    val afterTx2 =
      persist(afterTx1.saveAccount(coinbase, afterTx1.getGuaranteedAccount(coinbase).increaseBalance(UInt256(4))))
    val builder = new BlockAccessListBuilder
    builder.addIndex(1L, recorderFor(Seq(coinbase)), start, afterTx1)
    builder.addIndex(2L, recorderFor(Seq(coinbase)), afterTx1, afterTx2)
    val account = onlyAccount(builder)
    account.balanceChanges shouldBe Seq(BalanceChange(1L, UInt256(3)), BalanceChange(2L, UInt256(7)))

  it should "assemble accounts by address and slots numerically" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val high = addr(0xff)
    val low = addr(0x01)
    val before = persist(emptyWorld.saveAccount(low, Account.empty()).saveAccount(high, Account.empty()))
    // low: write slot 0x100 then slot 0x02 — numeric order is 0x02 before 0x100 (they sort the other way as RLP bytes).
    val after = persist(
      before
        .saveStorage(low, before.getStorage(low).store(0x100, 1).store(0x02, 1))
        .saveAccount(high, before.getGuaranteedAccount(high).increaseBalance(UInt256(1)))
    )
    val builder = new BlockAccessListBuilder
    builder.addIndex(
      1L,
      recorderFor(Seq(low, high), slots = Seq(low -> UInt256(0x100), low -> UInt256(0x02))),
      before,
      after
    )
    val built = builder.build
    built.accounts.map(_.address) shouldBe Seq(low, high) // 0x01 before 0xff
    built.accounts.head.storageChanges.map(_.slot) shouldBe Seq(UInt256(0x02), UInt256(0x100))
