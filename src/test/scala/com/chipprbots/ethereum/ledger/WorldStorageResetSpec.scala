package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b: a world tells its storage that a storage trie was replaced whole when the world is persisted,
  * and only then.
  *
  * A storage that keeps a trie by position has that trie's rows to drop when its account is deleted, or when a contract
  * is created over it. The world used to say so the moment it deleted the account. But a world is a value: execution
  * makes one for every call frame and throws away the ones that revert, and a creation happens inside a frame. A reset
  * said at once could not be taken back. So the world carries the accounts it has reset, as it carries everything else
  * it has not persisted, and says so first thing when it is persisted.
  *
  * A storage keyed by hash does nothing on a reset, so none of this changes a state root there.
  */
class WorldStorageResetSpec extends AnyFlatSpec with Matchers:

  private val alice = Address(0xa11ce)
  private val bob = Address(0xb0b)
  private def owner(address: Address): ByteString = ByteString(kec256(address.bytes.toArray))
  private def hex(bytes: ByteString): String = bytes.take(4).map("%02x".format(_)).mkString

  /** A storage that notes, in order, each reset it is told of and each write, with the trie that was written. */
  final private class Noting(
      under: MptStorage,
      trie: String = "accounts",
      val seen: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  ) extends MptStorage:
    override def get(nodeId: Array[Byte]): MptNode = under.get(nodeId)
    override def getAt(path: NibblePath, hash: Array[Byte]): MptNode = under.getAt(path, hash)
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      seen += s"write $trie"
      under.updateNodesInStorage(newRoot, toRemove)
    override def updateNodesAt(
        newRoot: Option[MptNode],
        replaced: Seq[(NibblePath, MptNode)],
        absorbed: Seq[(NibblePath, MptNode)]
    ): Option[MptNode] =
      seen += s"write $trie"
      under.updateNodesAt(newRoot, replaced, absorbed)
    override def persist(): Unit = under.persist()
    override def forOwner(forWhom: Option[ByteString]): MptStorage =
      new Noting(under.forOwner(forWhom), forWhom.fold("accounts")(hex), seen)
    override def resetOwner(forWhom: ByteString): Unit =
      seen += s"reset ${hex(forWhom)}"
      under.resetOwner(forWhom)

  final private class Fixture:
    val storage = new Noting(new ReferenceStore)
    private val codes = new EvmCodeStorage(EphemDataSource())

    def world(root: ByteString): InMemoryWorldStateProxy =
      InMemoryWorldStateProxy(
        codes,
        storage,
        (_: BigInt) => None,
        UInt256.Zero,
        root,
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )

    def stored(world: InMemoryWorldStateProxy, address: Address, slots: (Int, Int)*): InMemoryWorldStateProxy =
      world.saveStorage(
        address,
        slots.foldLeft(world.getStorage(address)) { case (storage, (k, v)) => storage.store(BigInt(k), BigInt(v)) }
      )

    /** alice with two slots and bob with none, persisted. */
    val root: ByteString =
      val empty = world(ByteString(MerklePatriciaTrie.EmptyRootHash))
      val peopled =
        stored(empty.saveAccount(alice, Account(0, 100)).saveAccount(bob, Account(0, 200)), alice, 1 -> 11, 2 -> 22)
      val persisted = InMemoryWorldStateProxy.persistState(peopled)
      storage.seen.clear()
      persisted.stateRootHash

    def resets: Seq[String] = storage.seen.toSeq.filter(_.startsWith("reset"))

  "A world that deletes an account" should
    "tell its storage of the replaced storage trie when it is persisted, before the trie that takes its place" taggedAs
    UnitTest in {
      val f = new Fixture
      val deleted = f.world(f.root).deleteAccount(alice)
      // nothing is said yet: the world is a value
      f.storage.seen shouldBe empty

      // the address is created again in the same block, with other storage
      val recreated = f.stored(deleted.saveAccount(alice, Account(0, 1)), alice, 3 -> 33)
      val persisted = InMemoryWorldStateProxy.persistState(recreated)

      f.resets shouldBe Seq(s"reset ${hex(owner(alice))}")
      val seen = f.storage.seen.toSeq
      seen.indexOf(s"reset ${hex(owner(alice))}") should be < seen.indexOf(s"write ${hex(owner(alice))}")
      // and the new trie is the new contract's alone
      persisted.getStorage(alice).load(BigInt(3)) shouldBe BigInt(33)
      persisted.getStorage(alice).load(BigInt(1)) shouldBe BigInt(0)
    }

  it should "say it once, however often the account was deleted, and not again at the next persist" taggedAs UnitTest in {
    val f = new Fixture
    val twice = f.world(f.root).deleteAccount(alice).deleteAccount(alice)
    val persisted = InMemoryWorldStateProxy.persistState(twice)
    f.resets shouldBe Seq(s"reset ${hex(owner(alice))}")

    f.storage.seen.clear()
    InMemoryWorldStateProxy.persistState(persisted.saveAccount(bob, Account(1, 201)))
    f.resets shouldBe empty
  }

  // The frame that reverts: execution goes on from the world before it, and the world that deleted is never persisted.
  "A world that is thrown away" should "leave no reset behind, whatever it deleted or created over" taggedAs UnitTest in {
    val f = new Fixture
    val before = f.world(f.root)
    before.deleteAccount(alice): Unit
    before.initialiseAccount(alice): Unit

    InMemoryWorldStateProxy.persistState(before.saveAccount(bob, Account(1, 201)))
    f.resets shouldBe empty
  }

  // On ETC the create rules let a CREATE land on an account that has storage and neither code nor nonce (core-geth
  // checks code and nonce; EIP-7610 is not ETC's). The new contract starts with an empty storage: the old trie is
  // replaced whole. The world had two answers for that, by whether it had read the old storage earlier in the block.
  "A creation over an account that has storage" should
    "replace that storage whole, the same whether or not the old storage was read first, and tell the storage so" taggedAs
    UnitTest in {
      def collide(touchFirst: Boolean): (InMemoryWorldStateProxy, Seq[String]) =
        val f = new Fixture
        val start = f.world(f.root)
        // read and write the old storage first, so that the world holds its trie
        val touched = if touchFirst then f.stored(start, alice, 7 -> 77) else start
        val created = touched.initialiseAccount(alice)
        // the new contract sees an empty storage
        created.getStorage(alice).load(BigInt(1)) shouldBe BigInt(0)
        created.getStorage(alice).load(BigInt(7)) shouldBe BigInt(0)
        (InMemoryWorldStateProxy.persistState(f.stored(created, alice, 9 -> 99)), f.resets)

      val (cold, coldResets) = collide(touchFirst = false)
      val (warm, warmResets) = collide(touchFirst = true)
      coldResets shouldBe Seq(s"reset ${hex(owner(alice))}")
      warmResets shouldBe coldResets
      // one slot, the new contract's: the state is the same either way
      warm.stateRootHash shouldBe cold.stateRootHash
      warm.getStorage(alice).load(BigInt(9)) shouldBe BigInt(99)
      warm.getStorage(alice).load(BigInt(1)) shouldBe BigInt(0)
      warm.getStorage(alice).load(BigInt(7)) shouldBe BigInt(0)
    }

  "A creation at an address with no storage" should "reset nothing" taggedAs UnitTest in {
    val f = new Fixture
    InMemoryWorldStateProxy.persistState(f.world(f.root).initialiseAccount(bob).initialiseAccount(Address(0xc0de)))
    f.resets shouldBe empty
  }
