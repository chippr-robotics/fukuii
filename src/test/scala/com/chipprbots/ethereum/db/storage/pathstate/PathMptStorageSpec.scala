package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T035: a state kept as rows of CF `t` and CF `u`, written with the members SNAP writes them with,
  * read through the trie.
  *
  * The reference is the same state on a hash-keyed store, built by the production world: every account and every slot
  * must read the same from both. (The task names the pinned jar's reads as the reference; that jar is a C0 deliverable
  * and is not available yet, so today's store stands in for it, as in C1.) Then each way a row can be wrong is given
  * the fault data-model 6.3.1 assigns it, and an execution that commits nothing runs on a throwaway layer.
  */
class PathMptStorageSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  /** The rows, with a step that runs once, before the next read of a row. */
  final private class Rows(dataSource: DataSource) extends PathNodeStorage(dataSource):
    var beforeNextRead: () => Unit = () => ()
    override def read(position: PositionKey): Option[Array[Byte]] =
      val step = beforeNextRead
      beforeNextRead = () => ()
      step()
      super.read(position)

  /** The reference state, and the same state as rows under an anchor. */
  final private class Fixture(dataSource: DataSource):
    val hashed = new HashedState
    val rows = new Rows(dataSource)
    val positions: Map[Option[ByteString], Map[ByteString, ByteString]] = hashed.positions()
    writeState(rows, positions)
    val anchor: BlockRef = block(1, hashed.root)
    val log = new StateMoveLog(8, Some(anchor))

    def viewNow(): StateView =
      val (epoch, held) = log.atRest.get
      new StateView(held.get, epoch, log, rows)

    def readOnly(): MptStorage = PathMptStorage.readOnly(viewNow())
    def throwaway(): MptStorage = PathMptStorage.throwaway(viewNow())
    def world(storage: MptStorage, root: ByteString = hashed.root): World = worldOver(storage, root, hashed.codes)

    /** The account trie as a trie of raw keys, so that what a read throws is the storage's and nothing else's. */
    def accountTrie(storage: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
      MerklePatriciaTrie[Array[Byte], Array[Byte]](hashed.root.toArray, storage.forOwner(None))

    /** The deepest stored position of a trie that is not its root, with a key of `keys` that lies under it. */
    def deepest(trie: Option[ByteString], keys: Seq[Array[Byte]]): (PositionKey, Array[Byte]) =
      val position = positions(trie).keys.toSeq
        .map(PositionKey(trie, _))
        .filter(_.path.length > 0)
        .sortBy(position => (position.path.length, position.path.show))
        .last
      val under = keys.find(key => HexPrefix.bytesToNibbles(key).startsWith(position.path.toNibbles))
      (position, under.getOrElse(fail(s"no key under ${position.show}")))

    def overwrite(position: PositionKey, encoded: Array[Byte]): Unit =
      position.owner.fold(rows.writeAccountNode(position.path.toNibbles, encoded))(
        rows.writeStorageNode(_, position.path.toNibbles, encoded)
      )

    def delete(position: PositionKey): Unit =
      position.owner.fold(rows.deleteAccountNode(position.path.toNibbles))(
        rows.deleteStorageNode(_, position.path.toNibbles)
      )

    /** Another stored node of the same trie: valid bytes, at the wrong position. */
    def someOtherNode(position: PositionKey): Array[Byte] =
      positions(position.owner).collectFirst {
        case (key, encoded) if key != position.hp && key != PositionKey.AccountRoot.hp => encoded.toArray
      }.get

  private val everyone: Seq[Address] = crowd ++ Seq(alice, bob, carol, dave)
  private val accountKeys: Seq[Array[Byte]] = everyone.map(address => kec256(address.bytes.toArray))
  private def slotKey(slot: Int): Array[Byte] = kec256(UInt256(slot).bytes.toArray)

  private val changes: World => World = world =>
    withSlots(alice, Seq(1 -> 5, 2 -> 0, 77 -> 9))(withSlots(Address(0xe11e), manySlots.take(12))(world))
      .deleteAccount(carol)
      .saveAccount(dave, Account(8, 9))

  dataSources.foreach { case (name, onDataSource) =>
    s"A state kept by position on $name" should "read every account and every slot as the hash-keyed store does" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val byPosition = f.world(f.readOnly())
          val byHash = f.hashed.world(f.hashed.root)

          everyone.foreach(address => byPosition.getAccount(address) shouldBe byHash.getAccount(address))
          byPosition.getAccount(Address(0xdead)) shouldBe None
          manySlots.foreach { case (slot, value) =>
            byPosition.getStorage(alice).load(slot) shouldBe BigInt(value)
            byPosition.getStorage(bob).load(slot) shouldBe BigInt(value)
          }
          otherSlots.foreach((slot, value) => byPosition.getStorage(carol).load(slot) shouldBe BigInt(value))
          byPosition.getStorage(carol).load(4) shouldBe BigInt(0)
          byPosition.getStorage(dave).load(1) shouldBe BigInt(0)

          // alice and bob hold the same storage: one trie each, under its own owner
          f.positions(Some(owner(alice))) shouldBe f.positions(Some(owner(bob)))
          f.positions.keySet shouldBe Set(None, Some(owner(alice)), Some(owner(bob)), Some(owner(carol)))
        }
      }

    it should "find by hash alone the root of the account trie, and no other node" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val storage = f.readOnly()
        ByteString(storage.get(f.hashed.root.toArray).hash) shouldBe f.hashed.root

        val (below, _) = f.deepest(None, accountKeys)
        val belowHash = kec256(f.positions(None)(below.hp).toArray)
        a[MissingNodeException] should be thrownBy storage.get(belowHash)
        val storageRoot = f.hashed.world(f.hashed.root).getGuaranteedAccount(alice).storageRoot.value
        a[MissingNodeException] should be thrownBy storage.get(storageRoot.toArray)

        // a batch of hashes cannot be answered at all, and must not be answered "absent"
        an[UnsupportedOperationException] should be thrownBy storage.multiGetNodes(Seq(belowHash))
        an[UnsupportedOperationException] should be thrownBy storage.rawNodes(Seq(ByteString(belowHash)))
      }
    }

    it should "still find the root of its own state by hash after the disk has moved on" taggedAs UnitTest in {
      onDataSource { dataSource =>
        // The next block's root takes the row at the root position, and the move says what was there. A view made
        // before it still answers for its own root, and the new root is no node of its state.
        val f = new Fixture(dataSource)
        val storage = f.readOnly()
        val oldRoot = f.positions(None)(PositionKey.AccountRoot.hp)
        val newRoot = f.someOtherNode(PositionKey.AccountRoot)
        f.log.publish(StateMove(block(2, ByteString(kec256(newRoot))), Map(PositionKey.AccountRoot -> Some(oldRoot))))
        f.overwrite(PositionKey.AccountRoot, newRoot)
        f.log.settled()

        ByteString(storage.get(f.hashed.root.toArray).hash) shouldBe f.hashed.root
        a[MissingNodeException] should be thrownBy storage.get(kec256(newRoot))
        ByteString(f.readOnly().get(kec256(newRoot)).hash) shouldBe ByteString(kec256(newRoot))
      }
    }

    it should "find by hash a root an execution wrote, however small" taggedAs UnitTest in {
      onDataSource { dataSource =>
        // A root is kept at any size. A trie of one short key has a root well under 32 bytes.
        val f = new Fixture(dataSource)
        val storage = f.throwaway().forOwner(Some(owner(dave)))
        val tiny = MerklePatriciaTrie[Array[Byte], Array[Byte]](storage).put(Array[Byte](1), Array[Byte](2))
        val root = tiny.getRootHash
        MerklePatriciaTrie[Array[Byte], Array[Byte]](root, storage).get(Array[Byte](1)).map(ByteString(_)) shouldBe
          Some(ByteString(2))
        ByteString(storage.get(root).hash) shouldBe ByteString(root)
      }
    }

    it should "refuse a trie that does not say which trie it is" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        an[IllegalStateException] should be thrownBy f.readOnly().getAt(NibblePath.Empty, f.hashed.root.toArray)
        an[IllegalStateException] should be thrownBy f.throwaway().updateNodesAt(None, Nil, Nil)
        an[IllegalStateException] should be thrownBy f.throwaway().forOwner(None).updateNodesInStorage(None, Nil)
      }
    }

    it should "report a row that is not there as a missing node, at its position" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val (position, key) = f.deepest(None, accountKeys)
        f.delete(position)

        val missing = the[MissingNodeException] thrownBy f.accountTrie(f.readOnly()).get(key)
        missing should not be a[CorruptNodeException]
        missing.location shouldBe Some(position.hp)

        // a storage trie's missing row is that account's, and the same trie under another owner is whole
        val g = new Fixture(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
        val (slotPosition, hashedSlot) = g.deepest(Some(owner(alice)), manySlots.map((slot, _) => slotKey(slot)))
        val slot = manySlots.map(_._1).find(candidate => slotKey(candidate).sameElements(hashedSlot)).get
        g.delete(slotPosition)
        val world = g.world(g.readOnly())
        val ofAlice = the[MissingStorageNodeException] thrownBy world.getStorage(alice).load(slot)
        ofAlice.accountAddress shouldBe alice.bytes
        world.getStorage(bob).load(slot) shouldBe BigInt(manySlots.toMap.apply(slot))
      }
    }

    it should "call a row that is another node corrupt, while nothing has moved since the view was made" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val (position, key) = f.deepest(None, accountKeys)
          f.overwrite(position, f.someOtherNode(position))

          val corrupt = the[CorruptNodeException] thrownBy f.accountTrie(f.readOnly()).get(key)
          corrupt shouldBe a[MissingNodeException]
          corrupt.location shouldBe Some(position.hp)
          corrupt.detail should include("another node")
        }
      }

    it should "call the same row stale, not corrupt, once the disk has moved or is moving" taggedAs UnitTest in {
      onDataSource { dataSource =>
        // moved: a batch committed after the view was made
        val moved = new Fixture(dataSource)
        val (position, key) = moved.deepest(None, accountKeys)
        val storage = moved.readOnly()
        moved.log.publish(StateMove(block(2, moved.hashed.root), Map.empty))
        moved.overwrite(position, moved.someOtherNode(position))
        moved.log.settled()
        val stale = the[StaleStateViewException] thrownBy moved.accountTrie(storage).get(key)
        stale.block shouldBe moved.anchor
        (stale.createdAtEpoch, stale.epochNow) shouldBe (0L, 2L)

        // moving: a batch is in flight
        val moving = new Fixture(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
        val (again, sameKey) = moving.deepest(None, accountKeys)
        val earlier = moving.readOnly()
        moving.log.publish(StateMove(block(2, moving.hashed.root), Map.empty))
        moving.overwrite(again, moving.someOtherNode(again))
        a[StaleStateViewException] should be thrownBy moving.accountTrie(earlier).get(sameKey)
      }
    }

    it should "decide between corrupt and stale after it has read the row, not before" taggedAs UnitTest in {
      onDataSource { dataSource =>
        // Nothing has moved when the read begins. A batch commits just before the row is fetched and leaves another
        // node there. Judged by what was true before the read, those new bytes would be called corrupt.
        val f = new Fixture(dataSource)
        val (position, _) = f.deepest(None, accountKeys)
        val expected = kec256(f.positions(None)(position.hp).toArray)
        val storage = f.readOnly().forOwner(None)
        f.rows.beforeNextRead = () =>
          f.log.publish(StateMove(block(2, f.hashed.root), Map.empty))
          f.overwrite(position, f.someOtherNode(position))
          f.log.settled()
        a[StaleStateViewException] should be thrownBy storage.getAt(position.path, expected)
      }
    }

    it should "serve a view what a position held, when the move that changed it said so" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val (position, key) = f.deepest(None, accountKeys)
        val before = f.positions(None)(position.hp)
        val storage = f.readOnly()
        val expected = f.accountTrie(f.hashed.nodes).get(key).map(ByteString(_))

        f.log.publish(StateMove(block(2, f.hashed.root), Map(position -> Some(before))))
        f.overwrite(position, f.someOtherNode(position))
        f.log.settled()

        f.accountTrie(storage).get(key).map(ByteString(_)) shouldBe expected
      }
    }

    it should "call bytes that hash to what the parent holds and do not decode corrupt" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val position = PositionKey.at(None, NibblePath(15, 15, 15, 15))
        val garbage = Array.fill[Byte](40)(0x7f)
        f.overwrite(position, garbage)
        val corrupt =
          the[CorruptNodeException] thrownBy f.readOnly().forOwner(None).getAt(position.path, kec256(garbage))
        corrupt.detail should include("does not decode")
        corrupt.location shouldBe Some(position.hp)
      }
    }

    it should "hand a caller that judges rows itself the row at each position" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val nowhere = NibblePath(15, 15, 15, 15)
        val read =
          f.readOnly().forOwner(None).rawNodesAt(Seq(NibblePath.Empty -> f.hashed.root, nowhere -> f.hashed.root))
        read.map {
          case RawNodeRead.Bytes(encoded) => Some(ByteString(encoded))
          case RawNodeRead.Absent         => None
          case other                      => fail(s"unexpected $other")
        } shouldBe Seq(Some(f.positions(None)(PositionKey.AccountRoot.hp)), None)
      }
    }

    it should "let an execution that commits nothing persist its worlds and read them back, and write no row" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val expected = f.hashed.change(f.hashed.root)(changes)
          val rowsBefore = allRows(f.rows)

          val storage = f.throwaway()
          val persisted = InMemoryWorldStateProxy.persistState(changes(f.world(storage)))
          persisted.stateRootHash shouldBe expected

          // the new world, read back from what this execution wrote
          val after = f.world(storage, expected)
          val afterByHash = f.hashed.world(expected)
          (everyone :+ Address(0xe11e)).foreach(address =>
            after.getAccount(address) shouldBe afterByHash.getAccount(address)
          )
          after.getAccount(carol) shouldBe None
          after.getStorage(alice).load(1) shouldBe BigInt(5)
          after.getStorage(alice).load(2) shouldBe BigInt(0)
          after.getStorage(alice).load(77) shouldBe BigInt(9)
          after.getStorage(alice).load(3) shouldBe BigInt(manySlots.toMap.apply(3))
          after.getStorage(Address(0xe11e)).load(12) shouldBe BigInt(manySlots.toMap.apply(12))

          // the world before it, read after the newer one was persisted (the access-list builder does this)
          val earlier = f.world(storage)
          earlier.getStorage(alice).load(1) shouldBe BigInt(manySlots.toMap.apply(1))
          earlier.getAccount(carol) shouldBe defined

          // a second change on top of the first, as the next transaction of a block
          val further: World => World = world => withSlots(bob, Seq(40 -> 1, 41 -> 2))(world).deleteAccount(alice)
          InMemoryWorldStateProxy.persistState(further(after)).stateRootHash shouldBe f.hashed.change(expected)(further)

          // none of it reached the rows, and another execution on the same view knows nothing of it
          allRows(f.rows) shouldBe rowsBefore
          a[MissingNodeException] should be thrownBy f.world(f.throwaway(), expected).getAccount(dave)
        }
      }

    it should "refuse to persist anything through a read-only view" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        an[UnsupportedOperationException] should be thrownBy InMemoryWorldStateProxy.persistState(
          changes(f.world(f.readOnly()))
        )
        an[UnsupportedOperationException] should be thrownBy f.readOnly().storeRawNodes(Nil)
      }
    }

    it should "read a row with the key the SNAP writer wrote it under" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val rows = new PathNodeStorage(dataSource)
        val someOwner = ByteString(Array.fill[Byte](32)(0x3c))
        val paths: Seq[Array[Byte]] = Seq(Seq(), Seq(10), Seq(10, 11), Seq(10, 11, 12)).map(_.map(_.toByte).toArray)
        paths.zipWithIndex.foreach { case (nibbles, i) =>
          val inAccounts = PositionKey.at(None, NibblePath.fromNibbles(nibbles))
          val inStorage = PositionKey.at(Some(someOwner), NibblePath.fromNibbles(nibbles))
          rows.writeAccountNode(nibbles, Array.fill[Byte](33)(i.toByte))
          rows.writeStorageNode(someOwner, nibbles, Array.fill[Byte](34)(i.toByte))

          rows.read(inAccounts).map(ByteString(_)) shouldBe Some(ByteString(Array.fill[Byte](33)(i.toByte)))
          rows.read(inStorage).map(ByteString(_)) shouldBe Some(ByteString(Array.fill[Byte](34)(i.toByte)))
          ByteString(inAccounts.rowKey) shouldBe ByteString(HexPrefix.encode(nibbles, isLeaf = false))
          ByteString(inStorage.rowKey) shouldBe someOwner ++ ByteString(HexPrefix.encode(nibbles, isLeaf = false))
          ByteString(inAccounts.path.toNibbles) shouldBe ByteString(nibbles)
        }
        rows.read(PositionKey.at(None, NibblePath(1))) shouldBe None
        rows.read(PositionKey.at(Some(ByteString(Array.fill[Byte](32)(0x3d))), NibblePath(10))) shouldBe None
      }
    }
  }

  "A position" should "equal another that names the same trie and path, whatever objects hold them" taggedAs
    UnitTest in {
      val someOwner = ByteString(Array.fill[Byte](32)(0x3c))
      val one = PositionKey.at(Some(someOwner), NibblePath(1, 2, 3))
      val same =
        PositionKey.at(Some(ByteString(Array.fill[Byte](32)(0x3c))), NibblePath.fromNibbles(Array[Byte](1, 2, 3)))
      one shouldBe same
      one.hashCode shouldBe same.hashCode
      Map(one -> 1).get(same) shouldBe Some(1)
      one should not be PositionKey.at(None, NibblePath(1, 2, 3))
      one should not be PositionKey.at(Some(someOwner), NibblePath(1, 2))
      PositionKey.AccountRoot.hp shouldBe ByteString(0)
      an[IllegalArgumentException] should be thrownBy PositionKey.at(Some(ByteString(1, 2, 3)), NibblePath.Empty)
    }

  "A closed RocksDB" should "fail a read as a closed database, and never as a missing node" taggedAs UnitTest in {
    dataSources.find(_._1 == "RocksDB").get._2 { dataSource =>
      val f = new Fixture(dataSource)
      val storage = f.readOnly()
      dataSource.close()
      a[RocksDbDataSourceClosedException] should be thrownBy storage.get(f.hashed.root.toArray)
      a[RocksDbDataSourceClosedException] should be thrownBy f.accountTrie(storage).get(accountKeys.head)
    }
  }
