package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T038: the access-list prefetch's reader of a parent state kept by position.
  *
  * A prefetch only warms the decoded-node cache that execution reads through, so two things decide whether it is safe:
  * what it puts in that cache, and when it stops. It caches only nodes its storage verified against the hash their
  * parent holds, and the first fault ends it for every trie of the block.
  */
class PathPrefetchReaderSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  /** The rows, with a step that runs once, before the next read of a row. */
  final private class Rows(dataSource: DataSource) extends PathNodeStorage(dataSource):
    var beforeNextRead: () => Unit = () => ()
    override def read(position: PositionKey): Option[Array[Byte]] =
      val step = beforeNextRead
      beforeNextRead = () => ()
      step()
      super.read(position)

  final private class Fixture(dataSource: DataSource):
    val hashed = new HashedState
    val rows = new Rows(dataSource)
    val positions: Map[Option[ByteString], Map[ByteString, ByteString]] = hashed.positions()
    writeState(rows, positions)
    val anchor: BlockRef = block(1, hashed.root)
    val log = new StateMoveLog(2, Some(anchor))
    val cache: DecodedNodeCache = DecodedNodeCache.withOwnBudget(64L << 20, strongValues = true)
    val loaded: ArrayBuffer[ByteString] = mutable.ArrayBuffer.empty[ByteString]

    def viewNow(): StateView =
      val (epoch, held) = log.atRest.get
      new StateView(held.get, epoch, log, rows)

    def reader(budgetBytes: Long = 64L << 20): PrefetchNodeReader =
      PathPrefetchReader(viewNow(), Some(cache), budgetBytes, loaded += _)

    def accounts(storage: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
      MerklePatriciaTrie[Array[Byte], Array[Byte]](hashed.root.toArray, storage.forOwner(None))

    def storageOf(address: Address, storage: MptStorage): MerklePatriciaTrie[Array[Byte], Array[Byte]] =
      val root = hashed.world(hashed.root).getGuaranteedAccount(address).storageRoot.value
      MerklePatriciaTrie[Array[Byte], Array[Byte]](root.toArray, storage.forOwner(Some(owner(address))))

    /** A stored position of a trie below its root, with a key of `keys` under it. */
    def below(trie: Option[ByteString], keys: Seq[Array[Byte]]): (PositionKey, Array[Byte]) =
      val position = positions(trie).keys.toSeq
        .map(PositionKey(trie, _))
        .filter(_.path.length > 0)
        .sortBy(position => (position.path.length, position.path.show))
        .last
      (position, keys.find(key => HexPrefix.bytesToNibbles(key).startsWith(position.path.toNibbles)).get)

    def cached(encoded: ByteString): Boolean = cache.get(ByteString(kec256(encoded.toArray))) != null

  private val accountKeys: Seq[Array[Byte]] =
    (crowd ++ Seq(alice, bob, carol, dave)).map(address => kec256(address.bytes.toArray))
  private val slotKeys: Seq[Array[Byte]] = manySlots.map((slot, _) => kec256(UInt256(slot).bytes.toArray))

  dataSources.foreach { case (name, onDataSource) =>
    s"The prefetch reader of a state kept by position on $name" should "put the nodes it reads into the cache, on one budget and one count for every trie of the block" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val reader = f.reader()
          accountKeys.foreach(key => f.accounts(reader).get(key) shouldBe defined)
          val afterAccounts = reader.nodesLoaded
          afterAccounts shouldBe f.positions(None).size

          slotKeys.foreach(key => f.storageOf(alice, reader).get(key) shouldBe defined)
          // the count is the block's, whichever trie's view did the reading
          reader.nodesLoaded shouldBe afterAccounts + f.positions(Some(owner(alice))).size
          reader.exhausted shouldBe false

          // every node read is in the cache, by hash, and was reported
          (f.positions(None).values ++ f.positions(Some(owner(alice))).values).foreach(f.cached(_) shouldBe true)
          f.loaded.toSet.size shouldBe f.loaded.size
          f.loaded.size.toLong shouldBe reader.nodesLoaded

          // bob's storage is node for node alice's: found in the cache, and not read again
          slotKeys.foreach(key => f.storageOf(bob, reader).get(key) shouldBe defined)
          reader.nodesLoaded shouldBe afterAccounts + f.positions(Some(owner(alice))).size
        }
      }

    it should "stop inserting once the block's budget is spent, across the tries" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val reader = f.reader(budgetBytes = 20_000)
        accountKeys.foreach(key => f.accounts(reader).get(key) shouldBe defined)
        reader.exhausted shouldBe true
        f.loaded.size.toLong should be < reader.nodesLoaded
        // a view of another trie is on the same spent budget
        val inserted = f.loaded.size
        otherSlots.foreach { case (slot, _) =>
          f.storageOf(carol, reader).get(kec256(UInt256(slot).bytes.toArray)) shouldBe defined
        }
        f.loaded.size shouldBe inserted
      }
    }

    it should "stop at a missing row, for every trie of the block, and cache nothing for it" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val (position, slot) = f.below(Some(owner(alice)), slotKeys)
        val gone = f.positions(position.owner)(position.hp)
        f.rows.deleteStorageNode(owner(alice), position.path.toNibbles)

        val reader = f.reader()
        reader.exhausted shouldBe false
        a[MissingNodeException] should be thrownBy f.storageOf(alice, reader).get(slot)
        // the reader the prefetch holds, and every view made of it, has stopped
        reader.exhausted shouldBe true
        f.cached(gone) shouldBe false
      }
    }

    it should "never cache a row that is not the node its parent names" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val (position, key) = f.below(None, accountKeys)
        val right = f.positions(None)(position.hp)
        // A valid node, of a trie this walk never visits: were it a node of the account trie, the walk could have read
        // it at its own position on the way down, and cached it there rightly.
        val wrong = f.positions(Some(owner(carol))).values.head
        f.rows.writeAccountNode(position.path.toNibbles, wrong.toArray)

        val reader = f.reader()
        a[CorruptNodeException] should be thrownBy f.accounts(reader).get(key)
        reader.exhausted shouldBe true
        f.cached(right) shouldBe false
        f.cached(wrong) shouldBe false
      }
    }

    it should "stop when its view has gone stale" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val reader = f.reader()
        // more moves than the log keeps
        (2 to 4).foreach { n =>
          f.log.publish(StateMove(block(n, f.hashed.root), Map.empty))
          f.log.settled()
        }
        a[StaleStateViewException] should be thrownBy f.accounts(reader).get(accountKeys.head)
        reader.exhausted shouldBe true
        f.loaded shouldBe empty
      }
    }

    it should "drop an insert that raced an eviction" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val reader = f.reader()
        val root = f.positions(None)(PositionKey.AccountRoot.hp)
        // An in-place commit evicts what it overwrites. One that runs while the reader is at the disk leaves the reader
        // holding a node it read before the eviction: it must not go into the cache after it.
        f.rows.beforeNextRead = () => f.cache.evict(Seq(ByteString(kec256(root.toArray))))
        f.accounts(reader).get(accountKeys.head) shouldBe defined
        f.cached(root) shouldBe false
        reader.nodesLoaded should be > 0L
      }
    }

    it should "refuse every write" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val reader = f.reader()
        an[UnsupportedOperationException] should be thrownBy reader.forOwner(None).updateNodesAt(None, Nil, Nil)
        an[UnsupportedOperationException] should be thrownBy reader.resetOwner(owner(alice))
        an[UnsupportedOperationException] should be thrownBy reader.storeRawNodes(Nil)
      }
    }
  }
