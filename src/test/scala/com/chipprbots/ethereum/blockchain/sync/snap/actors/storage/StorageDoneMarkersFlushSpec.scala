package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.FlatBatchFlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.FlatBatchFlushFailed
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** An in-memory store whose writes can be made to fail, to drive a real flat-batch commit failure (the technique of
  * `StorageRangeCoordinatorSpec`'s `FailingWritesDataSource`, which covers the core's flush-failed arm).
  */
final private class FailingFlatWritesDataSource extends EphemDataSource(Map.empty):
  @volatile var failWrites: Boolean = false
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    if failWrites then throw new java.io.IOException("synthetic write failure")
    else super.update(dataSourceUpdates)

/** The ordering and the failure arm of `StorageDoneMarkers.flushPendingFlatBatch` (spec 016 C-S3, T068), on the
  * `StubStorageDoneMarkersState` of `StorageDoneMarkersSpec`: the trie nodes are written before the slots commit, and a
  * commit that throws reaches `self` as `FlatBatchFlushFailed`, never as a completion.
  */
class StorageDoneMarkersFlushSpec extends AnyFlatSpec with Matchers:

  private val AccountA = ByteString(Array.fill[Byte](32)(0x0a))
  private val AccountB = ByteString(Array.fill[Byte](32)(0x0b))

  /** A 32-byte slot key whose last byte is `b` (the rest zero). */
  private def key(b: Int): ByteString = ByteString(Array.fill[Byte](31)(0) :+ b.toByte)

  private def slots(bs: Int*): Seq[(ByteString, ByteString)] = bs.map(b => key(b) -> ByteString(s"value-$b"))

  private def module(
      self: TestInbox[StorageRangeCoordinator.Command],
      store: FlatSlotStorage
  ): StubStorageDoneMarkersState & StorageDoneMarkers =
    new StubStorageDoneMarkersState(self.ref, store, true, 3) with StorageDoneMarkers

  "StorageDoneMarkers.flushPendingFlatBatch" should "write the trie nodes while the slots are still uncommitted" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val store = new FlatSlotStorage(EphemDataSource())
    val m = module(self, store)
    var slotsAtTrieFlush: Seq[Option[ByteString]] = Nil
    m.onTrieFlush = () => slotsAtTrieFlush = Seq(store.getSlot(AccountA, key(0x10)), store.getSlot(AccountB, key(0x30)))
    m.stageFlatSlotChunk(AccountA, slots(0x10, 0x20))
    m.stageFlatSlotChunk(AccountB, slots(0x30)) // reaches the threshold of 3: the batch is submitted

    m.trieFlushes shouldBe 1
    slotsAtTrieFlush shouldBe Seq(None, None)
    store.getSlot(AccountA, key(0x10)) shouldBe Some(ByteString("value-16"))
    store.getSlot(AccountB, key(0x30)) shouldBe Some(ByteString("value-48"))
    self.receiveAll().map {
      case c: FlatBatchFlushComplete => c.copy(elapsedMs = 0L)
      case other                     => other
    } shouldBe Seq(FlatBatchFlushComplete(m.stateRoot, 3, 0L, 1L))
  }

  it should "report a failed commit to self as FlatBatchFlushFailed, not as a completion" taggedAs UnitTest in {
    val self = TestInbox[StorageRangeCoordinator.Command]()
    val ds = new FailingFlatWritesDataSource
    val store = new FlatSlotStorage(ds)
    val m = module(self, store)
    m.stageFlatSlotChunk(AccountA, slots(0x10, 0x20))
    ds.failWrites = true
    m.flushPendingFlatBatch()
    ds.failWrites = false

    // The batch was handed off: the accumulator is reset and it counts as in flight until the core's arm runs.
    m.trieFlushes shouldBe 1
    m.flatBatchSeq shouldBe 1L
    m.inFlightFlatBatches shouldBe 1
    m.pendingFlatBatchAccounts shouldBe empty
    store.getSlot(AccountA, key(0x10)) shouldBe None
    self.receiveAll() shouldBe Seq(FlatBatchFlushFailed(m.stateRoot, 2, "synthetic write failure"))
  }
