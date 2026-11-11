package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2a (T039): execution says which trie each trie is. The account trie is built over
  * `forOwner(None)` and each account's storage trie over `forOwner(Some(keccak256(address)))` (data-model 8, "Owner
  * threading"), so a position-keyed store can keep them apart. A hash-keyed store answers `forOwner` with itself and
  * sees no difference.
  */
class InMemoryWorldStateProxyOwnerSpec extends AnyFlatSpec with Matchers:

  private val alice = Address(0xa11ce)
  private val bob = Address(0xb0b)
  private val carol = Address(0xca401)

  private def owner(address: Address): Option[ByteString] = Some(ByteString(kec256(address.bytes.toArray)))

  private class Worlds extends EphemBlockchainTestSetup:
    val positions = new OwnerPositionStore
    val reference = new ReferenceStore

    private def worldOver(storage: MptStorage, root: ByteString): InMemoryWorldStateProxy =
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        storage,
        (_: BigInt) => None,
        UInt256.Zero,
        root,
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )

    /** The same changes made to a world on the position store and to one on a hash-keyed store, and persisted: both
      * must end at the same root. Returns that root.
      */
    def change(from: ByteString)(changes: InMemoryWorldStateProxy => InMemoryWorldStateProxy): ByteString =
      val positioned = InMemoryWorldStateProxy.persistState(changes(worldOver(positions, from)))
      val hashed = InMemoryWorldStateProxy.persistState(changes(worldOver(reference, from)))
      positioned.stateRootHash shouldBe hashed.stateRootHash
      positioned.stateRootHash

    def read(root: ByteString): InMemoryWorldStateProxy = worldOver(positions, root)

    /** The stored positions of the trie with this root, by a walk of its raw RLP on the hash-keyed store. */
    def walk(root: ByteString): Rows = RawTrieWalk.storedPositions(root.toArray, reference.load)

    def storageRoot(root: ByteString, address: Address): ByteString =
      read(root).getGuaranteedAccount(address).storageRoot.value

    val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  private def withSlots(address: Address, slots: (Int, Int)*)(world: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
    val existing = if world.getAccount(address).isDefined then world else world.saveAccount(address, Account(0, 1))
    val storage = slots.foldLeft(existing.getStorage(address)) { case (acc, (key, value)) =>
      acc.store(BigInt(key), BigInt(value))
    }
    existing.saveStorage(address, storage)

  /** Enough slots that the trie has stored nodes below its root. */
  private val manySlots: Seq[(Int, Int)] = (1 to 40).map(i => i -> (i * 7919))

  "A world on a position store" should "keep the account trie and each storage trie under its own owner" taggedAs (
    UnitTest,
    StateTest
  ) in new Worlds:
    val root: ByteString = change(emptyRoot) { world =>
      withSlots(bob, manySlots.take(20)*)(withSlots(alice, manySlots*)(world)).saveAccount(carol, Account(0, 5))
    }

    positions.owners shouldBe Set(None, owner(alice), owner(bob))
    positions.rowsOf(None) shouldBe walk(root)
    positions.rowsOf(owner(alice)) shouldBe walk(storageRoot(root, alice))
    positions.rowsOf(owner(bob)) shouldBe walk(storageRoot(root, bob))
    // an account with no storage has no trie of its own
    positions.rowsOf(owner(carol)) shouldBe empty

    // and everything reads back, each node found under its owner at its position
    val fresh: InMemoryWorldStateProxy = read(root)
    manySlots.foreach { case (key, value) => fresh.getStorage(alice).load(BigInt(key)) shouldBe BigInt(value) }
    fresh.getStorage(bob).load(BigInt(3)) shouldBe BigInt(3 * 7919)
    fresh.getStorage(bob).load(BigInt(30)) shouldBe BigInt(0)
    fresh.getGuaranteedAccount(carol).balance shouldBe UInt256(5)

  // On a hash-keyed store two accounts with the same storage share every node of it. A position store that kept one
  // copy would lose the second account's trie the first time the first account's changed (spec 022 V3).
  it should "give two accounts with identical storage separate rows" taggedAs (UnitTest, StateTest) in new Worlds:
    val root: ByteString = change(emptyRoot)(world => withSlots(bob, manySlots*)(withSlots(alice, manySlots*)(world)))

    storageRoot(root, alice) shouldBe storageRoot(root, bob)
    positions.rowsOf(owner(alice)) should not be empty
    positions.rowsOf(owner(alice)) shouldBe positions.rowsOf(owner(bob))
    positions.rowsOf(owner(alice)) shouldBe walk(storageRoot(root, alice))

    // alice's storage changes; bob's rows are untouched and still read
    val bobBefore: Rows = positions.rowsOf(owner(bob))
    val changed: ByteString = change(root)(withSlots(alice, 1 -> 0, 2 -> 99, 500 -> 1))
    storageRoot(changed, alice) should not be storageRoot(changed, bob)
    positions.rowsOf(owner(bob)) shouldBe bobBefore
    positions.rowsOf(owner(alice)) shouldBe walk(storageRoot(changed, alice))
    val fresh: InMemoryWorldStateProxy = read(changed)
    manySlots.foreach { case (key, value) => fresh.getStorage(bob).load(BigInt(key)) shouldBe BigInt(value) }
    fresh.getStorage(alice).load(BigInt(2)) shouldBe BigInt(99)

  it should "report a deleted account's storage trie as replaced" taggedAs (UnitTest, StateTest) in new Worlds:
    val root: ByteString = change(emptyRoot)(world => withSlots(bob, manySlots*)(withSlots(alice, manySlots*)(world)))
    positions.resets shouldBe empty

    val afterDelete: ByteString = change(root)(_.deleteAccount(alice))

    positions.resets shouldBe Seq(owner(alice).get)
    positions.rowsOf(owner(alice)) shouldBe empty
    positions.rowsOf(owner(bob)) shouldBe walk(storageRoot(afterDelete, bob))
    positions.rowsOf(None) shouldBe walk(afterDelete)
    read(afterDelete).getAccount(alice) shouldBe None

  // SELFDESTRUCT, then a contract created again at the same address (CREATE2; possible on ETC since Agharta), in one
  // block: the account ends up present, with the new storage alone.
  it should "leave only the new trie's rows when an account is deleted and made again" taggedAs (
    UnitTest,
    StateTest
  ) in new Worlds:
    val root: ByteString = change(emptyRoot)(withSlots(alice, manySlots*))

    val recreated: ByteString = change(root) { world =>
      withSlots(alice, 7 -> 1, 8 -> 2)(world.deleteAccount(alice))
    }

    positions.resets shouldBe Seq(owner(alice).get)
    positions.rowsOf(owner(alice)) shouldBe walk(storageRoot(recreated, alice))
    val fresh: InMemoryWorldStateProxy = read(recreated)
    fresh.getStorage(alice).load(BigInt(7)) shouldBe BigInt(1)
    fresh.getStorage(alice).load(BigInt(1)) shouldBe BigInt(0)

  it should "report a missing storage node as that account's" taggedAs (UnitTest, StateTest) in new Worlds:
    val root: ByteString = change(emptyRoot)(withSlots(alice, manySlots*))
    positions.trie(owner(alice)).dropRows()

    val thrown: MissingStorageNodeException = intercept[MissingStorageNodeException] {
      read(root).getStorage(alice).load(BigInt(1))
    }
    thrown.accountAddress shouldBe alice.bytes

  // The hash-keyed store sees nothing of this: it is one store for every trie.
  "A world on a hash-keyed store" should "be told of a deleted account's owner, and ignore it" taggedAs (
    UnitTest,
    StateTest
  ) in new Worlds:
    val root: ByteString = change(emptyRoot)(withSlots(alice, manySlots*))
    val afterDelete: ByteString = change(root)(_.deleteAccount(alice))
    val hashed: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        reference,
        (_: BigInt) => None,
        UInt256.Zero,
        afterDelete,
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
    hashed.getAccount(alice) shouldBe None
    (reference.forOwner(owner(alice)) should be).theSameInstanceAs(reference)
