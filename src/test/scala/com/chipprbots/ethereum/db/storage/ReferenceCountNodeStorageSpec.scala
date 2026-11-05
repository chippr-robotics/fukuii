package com.chipprbots.ethereum.db.storage

import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.cache.MapCache
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.NodeCacheConfig

class ReferenceCountNodeStorageSpec extends AnyFlatSpec with Matchers:

  "ReferenceCountNodeStorage" should "not remove a key if no more references until pruning" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val (key1, val1) = inserted.head

    storage.remove(key1)
    storage.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(1, nodeStorage, inMemory = false)

    storage.get(key1) shouldBe None

  it should "not remove a key that was inserted after deletion when pruning" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(1, storage)
    val (key1, val1) :: Nil = inserted.toList: @unchecked

    val storage2 = new ReferenceCountNodeStorage(nodeStorage, bn = 2)
    storage2.remove(key1)
    storage2.get(key1).get shouldEqual val1

    val storage3 = new ReferenceCountNodeStorage(nodeStorage, bn = 3)
    storage3.put(key1, val1)
    storage3.get(key1).get shouldEqual val1

    val storage4 = new ReferenceCountNodeStorage(nodeStorage, bn = 4)
    storage4.remove(key1)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(1, nodeStorage, inMemory = false)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(2, nodeStorage, inMemory = false)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(3, nodeStorage, inMemory = false)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(4, nodeStorage, inMemory = false)
    storage3.get(key1) shouldEqual None

  it should "not remove a key that it's still referenced when pruning" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(1, storage)
    val (key1, val1) :: Nil = inserted.toList: @unchecked

    val storage2 = new ReferenceCountNodeStorage(nodeStorage, bn = 2)
    storage2.put(key1, val1)
    storage2.get(key1).get shouldEqual val1

    val storage3 = new ReferenceCountNodeStorage(nodeStorage, bn = 3)
    storage3.remove(key1)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(1, nodeStorage, inMemory = false)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(2, nodeStorage, inMemory = false)
    storage3.get(key1).get shouldEqual val1

    ReferenceCountNodeStorage.prune(3, nodeStorage, inMemory = false)
    storage3.get(key1).get shouldEqual val1

  it should "not delete a key that's was referenced in later blocks when pruning" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:

    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)
    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val (key1, val1) :: (key2, val2) :: (key3, val3) :: (key4, _) :: Nil = inserted.toList: @unchecked

    storage.remove(key1) // remove key1 at block 1
    storage.remove(key4) // remove key4 at block 1, it should be pruned

    val storage2 = new ReferenceCountNodeStorage(nodeStorage, bn = 2)

    storage2.put(key1, val1).remove(key1) // add key1 again and remove it at block 2
    storage2.remove(key2).put(key2, val2) // remove and add key2 at block 2
    storage2.remove(key3) // Remove at block 2

    storage2.get(key1).get shouldEqual val1
    storage2.get(key2).get shouldEqual val2
    storage2.get(key3).get shouldEqual val3

    ReferenceCountNodeStorage.prune(1, nodeStorage, inMemory = false)
    storage2.get(key1).get shouldEqual val1
    storage2.get(key2).get shouldEqual val2
    storage2.get(key3).get shouldEqual val3
    storage2.get(key4) shouldBe None

    ReferenceCountNodeStorage.prune(2, nodeStorage, inMemory = false)
    storage2.get(key1) shouldBe None
    storage2.get(key2).get shouldEqual val2
    storage2.get(key3) shouldBe None
    storage2.get(key4) shouldBe None

  it should "not throw an error when deleting a key that does not exist" in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)

    storage.remove(ByteString("doesnotexist"))

    dataSource.storage.size shouldEqual 0

  it should "allow to rollback operations" in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val (key1, val1) :: (key2, val2) :: _ = inserted.toList: @unchecked

    storage.remove(key1).remove(key2)

    val storage2 = new ReferenceCountNodeStorage(nodeStorage, bn = 2)
    val key3: ByteString = ByteString("anotherKey")
    val val3: Array[Byte] = ByteString("anotherValue").toArray[Byte]
    storage2.put(key3, val3)

    storage2.get(key3).get shouldEqual val3

    ReferenceCountNodeStorage.rollback(2, nodeStorage, inMemory = false)

    storage2.get(key1).get shouldEqual val1
    storage2.get(key2).get shouldEqual val2
    storage2.get(key3) shouldEqual None

  it should "allow rollbacks after pruning" in new TestSetup:

    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val (key1, _) :: (key2, _) :: _ = inserted.toList: @unchecked

    storage.remove(key1).remove(key2)

    val storage2 = new ReferenceCountNodeStorage(nodeStorage, bn = 2)
    val key3: ByteString = ByteString("anotherKey")
    val val3: Array[Byte] = ByteString("anotherValue").toArray[Byte]
    storage2.put(key3, val3)

    dataSource.storage.size shouldEqual (1 + 5 + 2 + 7) // 1 deathRowKey + 5 keys + 2 block index + 7 snapshots

    ReferenceCountNodeStorage.prune(1, nodeStorage, inMemory = false)
    dataSource.storage.size shouldEqual (3 + 1 + 1) // 3 keys + 1 block index + 1 snapshots

    // Data is correct
    storage2.get(key1) shouldEqual None
    storage2.get(key2) shouldEqual None
    storage2.get(key3).get shouldEqual val3

    // We can still rollback without error
    ReferenceCountNodeStorage.rollback(2, nodeStorage, inMemory = false)
    ReferenceCountNodeStorage.rollback(1, nodeStorage, inMemory = false)
    storage2.get(key3) shouldEqual None

  it should "not save snapshots when requested" in new TestSetup:
    val storage = new FastSyncNodeStorage(nodeStorage, bn = 1)
    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    dataSource.storage.size shouldEqual inserted.size // only inserted keys, no additional data

  it should "allow rollbacks after pruning in memory" in new TestSetup:

    val storage = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val (key1, _) :: (key2, _) :: _ = inserted.toList: @unchecked

    storage.remove(key1).remove(key2)

    val storage2 = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 2)
    val key3: ByteString = ByteString("anotherKey")
    val val3: Array[Byte] = ByteString("anotherValue").toArray[Byte]
    storage2.put(key3, val3)

    underlying.size shouldEqual (1 + 5 + 2 + 7) // 1 deathrowkey + 5 keys + 2 block index + 7 snapshots

    ReferenceCountNodeStorage.prune(1, cachedNodeStorage, inMemory = true)
    underlying.size shouldEqual (3 + 1 + 1) // 3 keys + 1 block index + 1 snapshots

    // Data is correct
    storage2.get(key1) shouldEqual None
    storage2.get(key2) shouldEqual None
    storage2.get(key3).get shouldEqual val3

    // We can still rollback without error
    ReferenceCountNodeStorage.rollback(2, cachedNodeStorage, inMemory = true)
    ReferenceCountNodeStorage.rollback(1, cachedNodeStorage, inMemory = true)
    storage2.get(key3) shouldEqual None

  it should "allow pruning which happens partially on disk, partially in memory" in new TestSetup:

    val storage = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 1)

    insertRangeKeys(1, storage)

    val storage2 = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 2)

    insertRangeKeys(1, storage2)

    val storage3 = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 3)

    insertRangeKeys(1, storage3)

    // we are still in memory as cache size = 7 < 10
    cachedNodeStorage.persist() shouldEqual false
    dataSource.storage.size shouldEqual 0
    underlying.size shouldEqual 7 // 1 key + 3 block indexex + 3 snapshots

    new ReferenceCountNodeStorage(cachedNodeStorage, bn = 4)

    insertRangeKeys(4, storage3)
    ReferenceCountNodeStorage.prune(1, cachedNodeStorage, inMemory = true)

    // Number of nodes in cache > maxsize, so everything goes to data source, including unpruned blocks 2,3,4
    cachedNodeStorage.persist() shouldEqual true
    dataSource.storage.size shouldEqual 12
    underlying.size shouldEqual 0

    // Now as our block to prune(2) is <= best saved block(4), we need to prune junk from disk
    new ReferenceCountNodeStorage(cachedNodeStorage, bn = 5)
    insertRangeKeys(4, storage3)
    ReferenceCountNodeStorage.prune(2, cachedNodeStorage, inMemory = false)

    cachedNodeStorage.persist() shouldEqual false //
    underlying.size shouldEqual 9 // 4 keys + 4 snapshots + 1 block index
    dataSource.storage.size shouldEqual 10 // pruned 1 snapshot and 1 block index from disk from block 2

  it should "allow to rollback operations which happens partially on disk, partially in memory" in new TestSetup:
    val storage = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 1)

    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val (key1, _) :: (key2, _) :: _ = inserted.toList: @unchecked

    storage.remove(key1).remove(key2)

    val storage2 = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 2)
    val key3: ByteString = ByteString("anotherKey")
    val val3: Array[Byte] = ByteString("anotherValue").toArray[Byte]
    storage2.put(key3, val3)
    storage2.get(key3).get shouldEqual val3

    cachedNodeStorage.persist() shouldEqual true
    underlying.size shouldEqual 0
    dataSource.storage.size shouldEqual 15

    val storage3 = new ReferenceCountNodeStorage(cachedNodeStorage, bn = 3)
    val key4: ByteString = ByteString("aanotherKey")
    val val4: Array[Byte] = ByteString("aanotherValue").toArray[Byte]
    storage3.put(key4, val4)
    storage3.get(key4).get shouldEqual val4

    cachedNodeStorage.persist() shouldEqual false
    underlying.size shouldEqual 3 // 1 key + 1 snapshot + 1 blockindex

    storage3.get(key4).get shouldEqual val4
    // Best saved block is 2, so all block 3 data are in memory
    ReferenceCountNodeStorage.rollback(3, cachedNodeStorage, inMemory = true)
    storage3.get(key4) shouldEqual None

    storage3.get(key3).get shouldEqual val3
    // Best saved block is 2, so all block 2 data are on disk
    ReferenceCountNodeStorage.rollback(2, cachedNodeStorage, inMemory = false)
    storage3.get(key3) shouldEqual None

  // ---- US7 (spec 002): batched multiGet must equal per-key get on basic pruning ----

  it should "batch multiGet identically to per-key get, incl. None for absent keys (US7/FR-021/FR-022)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)
    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(4, storage)
    val present: Seq[ByteString] = inserted.map(_._1)
    val absent: ByteString = kec256(ByteString("absent-key"))
    // interleave present/absent + a duplicate to exercise ordering and None handling
    val query: Seq[ByteString] = Seq(present(0), absent, present(2), present(1), absent, present(3), present(0))

    val viaMulti: Seq[Option[Array[Byte]]] = storage.multiGet(query)
    val viaGet: Seq[Option[Array[Byte]]] = query.map(storage.get)

    viaMulti.length shouldEqual query.length
    viaMulti.map(_.map(_.toSeq)) shouldEqual viaGet.map(_.map(_.toSeq))
    viaMulti(1) shouldBe None // absent
    viaMulti(4) shouldBe None // absent
    viaMulti.head.map(_.toSeq) shouldEqual Some(inserted.head._2.toSeq)

  it should "inherit the batched multiGet on FastSyncNodeStorage with results identical to get (US7/SC-006)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new FastSyncNodeStorage(nodeStorage, bn = 1)
    val inserted: Seq[(ByteString, Array[Byte])] = insertRangeKeys(3, storage)
    val keys: Seq[ByteString] = inserted.map(_._1) :+ kec256(ByteString("nope"))
    storage.multiGet(keys).map(_.map(_.toSeq)) shouldEqual keys.map(storage.get).map(_.map(_.toSeq))

  // ---- #1471 undo record: the per-node net deltas are kept only where something reads them ----

  it should "keep a net delta per node touched when asked to track them" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1, trackNetDeltas = true)
    val inserted = insertRangeKeys(4, storage)
    storage.remove(inserted.head._1)

    storage.trackedDeltaEntries shouldBe 4
    // One node went in once and out once: no net change, so it is not part of the undo record.
    storage.netDeltas.map(_._1).toSet shouldBe inserted.tail.map(_._1).toSet
    storage.netDeltas.map(_._2).toSet shouldBe Set(1)

  it should "keep no per-node state by default, however many nodes pass through, and refuse to report deltas" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 1)
    (1 to 50).foreach(_ => insertRangeKeys(20, storage))

    storage.trackedDeltaEntries shouldBe 0
    // Counted all the same: 50 passes of the same 20 nodes.
    storage.get(kec256(ByteString("key1"))) shouldBe defined
    encoding.storedNodeFromBytes(nodeStorage.get(kec256(ByteString("key1"))).get).references shouldBe 50
    // An empty answer would be a block's undo record dropped without a word.
    an[IllegalStateException] should be thrownBy storage.netDeltas

  it should "write the same bytes whether or not it tracks deltas" taggedAs (UnitTest, DatabaseTest) in new TestSetup:
    val tracked = new TestSetup {}
    val storage = new ReferenceCountNodeStorage(nodeStorage, bn = 3)
    val trackedStorage = new ReferenceCountNodeStorage(tracked.nodeStorage, bn = 3, trackNetDeltas = true)
    val inserted = insertRangeKeys(6, storage)
    insertRangeKeys(6, trackedStorage)
    storage.remove(inserted.head._1)
    trackedStorage.remove(inserted.head._1)
    insertRangeKeys(3, storage)
    insertRangeKeys(3, trackedStorage)

    def contents(source: EphemDataSource) = source.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }
    contents(dataSource) shouldBe contents(tracked.dataSource)

  trait TestSetup:
    val dataSource: EphemDataSource = EphemDataSource()
    val nodeStorage = new NodeStorage(dataSource)

    def insertRangeKeys(n: Int, storage: NodesKeyValueStorage): Seq[(ByteString, Array[Byte])] =
      val toInsert = (1 to n).map(i => kec256(ByteString(s"key$i")) -> ByteString(s"value$i").toArray[Byte])
      toInsert.foreach(i => storage.put(i._1, i._2))
      toInsert

    object testCacheConfig extends NodeCacheConfig:
      override val maxSize = 10
      override val maxHoldTime: FiniteDuration = FiniteDuration(5, TimeUnit.MINUTES)

    val underlying: mutable.Map[ByteString, Array[Byte]] = MapCache.getMap[ByteString, Array[Byte]]
    val cache = new MapCache[ByteString, Array[Byte]](underlying, testCacheConfig)
    val cachedNodeStorage = new CachedNodeStorage(nodeStorage, cache)
