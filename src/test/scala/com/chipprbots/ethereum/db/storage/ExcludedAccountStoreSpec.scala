package com.chipprbots.ethereum.db.storage

import java.nio.ByteBuffer
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 022 T032, data-model.md "Excluded account": the durable excluded set in the app-state column family. */
class ExcludedAccountStoreSpec extends AnyFlatSpec with Matchers:

  private def h(n: Int): ByteString = ByteString(Array.fill(32)(n.toByte))

  final private class SyncCountingDataSource extends EphemDataSource(Map.empty):
    var synced = 0
    var unsynced = 0
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      synced += 1
      super.update(dataSourceUpdates)
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      unsynced += 1
      super.update(dataSourceUpdates)

  "The excluded set" should "round-trip entries of every reason, with their claims" taggedAs UnitTest in {
    val store = new SnapSyncProgressStorage(EphemDataSource())
    val entries = Seq(
      ExcludedAccount(h(1), ExcludedReason.StorageAbandoned, claimedStorageRoot = h(0x11)),
      ExcludedAccount(h(2), ExcludedReason.StorageSkipped, claimedStorageRoot = h(0x12)),
      ExcludedAccount(h(3), ExcludedReason.StorageRootMismatch, claimedStorageRoot = h(0x13), claimedCodeHash = h(0x14)),
      ExcludedAccount(h(4), ExcludedReason.CodeMissing)
    )
    store.recordExcluded(entries)
    store.excludedPage(None, 10) should contain theSameElementsAs entries
    store.hasExcluded shouldBe true
  }

  it should "write and remove with a fsync, never an ordinary commit" taggedAs UnitTest in {
    val source = new SyncCountingDataSource
    val store = new SnapSyncProgressStorage(source)
    store.recordExcluded(Seq(ExcludedAccount(h(1), ExcludedReason.StorageSkipped)))
    source.synced shouldBe 1
    store.removeExcluded(Seq(ExcludedAccount(h(1), ExcludedReason.StorageSkipped)))
    source.synced shouldBe 2
    source.unsynced shouldBe 0
    store.hasExcluded shouldBe false
  }

  it should "do nothing for an empty write" taggedAs UnitTest in {
    val source = new SyncCountingDataSource
    val store = new SnapSyncProgressStorage(source)
    store.recordExcluded(Nil)
    store.removeExcluded(Nil)
    source.synced shouldBe 0
  }

  it should "page in key order, from after a cursor, and keep account and code entries of one hash apart" taggedAs UnitTest in {
    val store = new SnapSyncProgressStorage(EphemDataSource())
    val account = ExcludedAccount(h(5), ExcludedReason.StorageAbandoned)
    val code = ExcludedAccount(h(5), ExcludedReason.CodeMissing)
    store.recordExcluded(Seq(account, code, ExcludedAccount(h(6), ExcludedReason.StorageSkipped)))
    val first = store.excludedPage(None, 2)
    first should have size 2
    val second = store.excludedPage(first.lastOption.map(_.cursor), 2)
    second should have size 1
    (first ++ second).map(_.cursor).distinct should have size 3
    store.excludedPage(second.lastOption.map(_.cursor), 2) shouldBe empty
    store.removeExcluded(Seq(account))
    store.excludedPage(None, 10) should contain(code)
  }

  it should "live in the app-state column family: clearAll leaves it, and it leaves other app-state keys alone" taggedAs UnitTest in {
    val source = EphemDataSource()
    val store = new SnapSyncProgressStorage(source)
    val done = new SnapStorageDoneStorage(source)
    val appState = new AppStateStorage(source)
    done.markDone(Seq((h(1), h(2)))).commit()
    appState.putSnapSyncPivotBlock(BigInt(7)).commit()
    store.recordExcluded(Seq(ExcludedAccount(h(1), ExcludedReason.StorageSkipped)))
    store.writeAccountCursors(h(9), 7L, Map.empty)
    store.clearAll()
    withClue("the p namespace is wiped, not the set: ")(store.hasExcluded shouldBe true)
    store.excludedPage(None, 10) should have size 1 // not the done marker, not the pivot
    store.clearExcluded()
    store.hasExcluded shouldBe false
    done.isDone(h(1), h(2)) shouldBe true
    appState.getSnapSyncPivotBlock() shouldBe Some(BigInt(7))
  }

  it should "keep an entry whose value does not parse: the key carries what it needs" taggedAs UnitTest in {
    val source = EphemDataSource()
    val store = new SnapSyncProgressStorage(source)
    val entry = ExcludedAccount(h(3), ExcludedReason.StorageSkipped)
    store.recordExcluded(Seq(entry))
    val key = source.storage.keys.head
    source.storage = source.storage.updated(ByteBuffer.wrap(key.array()), Array[Byte](99))
    store.excludedPage(None, 10).map(_.subject) shouldBe Seq(h(3))
  }

  it should "survive a reopen of a real RocksDB, and page it with an early stop" taggedAs (UnitTest, DatabaseTest) in {
    val dir = Files.createTempDirectory("excluded-store").toAbsolutePath.toString
    val config = new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dir
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
    val first = RocksDbDataSource(config, Namespaces.nsSeq)
    new SnapSyncProgressStorage(first).recordExcluded(
      (1 to 5).map(i => ExcludedAccount(h(i), ExcludedReason.StorageAbandoned, claimedStorageRoot = h(0x20 + i)))
    )
    first.close()
    val second = RocksDbDataSource(config, Namespaces.nsSeq)
    try
      val store = new SnapSyncProgressStorage(second)
      val page = store.excludedPage(None, 2)
      page.map(_.subject) shouldBe Seq(h(1), h(2))
      page.head.claimedStorageRoot shouldBe h(0x21)
      store.excludedPage(page.lastOption.map(_.cursor), 10).map(_.subject) shouldBe Seq(h(3), h(4), h(5))
    finally second.destroy()
  }
