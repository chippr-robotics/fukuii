package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.ConcurrentLinkedQueue

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporterSpec
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.testing.Tags.*

/** #1692, the SNAP start. `SNAPSyncController.start()` takes the "reference counts are exact" stamp away on the Hash
  * scheme, in a synced write, before it does anything else: a node on disk from a SNAP writer, which skips what is
  * already there, then implies the stamp is gone. On the Path scheme, whose publish counts every position, it stays.
  *
  * The controller is the real one, over the `ChildFactories` seam (no child runs). Its store journals every write with
  * whether it was synced and whether the stamp stood after it.
  */
class SnapStartRefCountStampSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  /** Every write to the data source: whether it was synced, and whether the stamp stood after it. */
  final private class Journal extends EphemDataSource(Map.empty):
    val writes = new ConcurrentLinkedQueue[(Boolean, Boolean)]()
    private def stampStands: Boolean = new AppStateStorage(this).hasRefCountsExact()
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      super.update(dataSourceUpdates)
      writes.add((true, stampStands))
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      super.update(dataSourceUpdates)
      writes.add((false, stampStands))

  final private class Fixture(val journal: Journal) extends SnapControllerFixture(testKit):
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with CheckpointImporterSpec.FixedDataSource(journal)
        with LocalPruningConfigBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider

    def childSpawns: Int =
      factories.accountSpawns.get() + factories.storageSpawns.get() + factories.healingSpawns.get() +
        factories.byteCodeSpawns.get() + factories.chainSpawns.get()

  "A Hash-scheme SNAP start" should "take the stamp away, in a synced write, before any child is spawned" taggedAs UnitTest in {
    val f = new Fixture(new Journal)
    f.appStateStorage.markDatadirCreated(countsExact = true).commitSync()
    f.appStateStorage.hasRefCountsExact() shouldBe true
    f.storeGenesis()
    f.peers.set(f.peersAt(height = 10))
    val before = f.journal.writes.size

    f.spawnController(SNAPSyncConfig(storageScheme = StorageScheme.Hash))

    // `spawnController` returns once start() has run and its first peer poll was answered: nothing has been spawned.
    f.childSpawns shouldBe 0
    f.appStateStorage.hasRefCountsExact() shouldBe false
    withClue("the write that took it away was synced, and no earlier write of the run had: ") {
      val run = f.journal.writes.asScala.toSeq.drop(before)
      val removal = run.indexWhere { case (_, stampStands) => !stampStands }
      removal should be >= 0
      run(removal)._1 shouldBe true
    }
  }

  it should "leave it away when SNAP then spawns its first child" taggedAs UnitTest in {
    val f = new Fixture(new Journal)
    f.appStateStorage.markDatadirCreated(countsExact = true).commitSync()
    val snap = f.enterGenesisAccountSync(SNAPSyncConfig(storageScheme = StorageScheme.Hash))
    f.factories.accountSpawns.get() shouldBe 1
    f.appStateStorage.hasRefCountsExact() shouldBe false
    f.awaitProcessed(snap)
  }

  "A Path-scheme SNAP start" should "leave the stamp, and write nothing for it" taggedAs UnitTest in {
    val f = new Fixture(new Journal)
    f.appStateStorage.markDatadirCreated(countsExact = true).commitSync()
    f.storeGenesis()
    f.peers.set(f.peersAt(height = 10))
    val before = f.journal.writes.size

    f.spawnController(SNAPSyncConfig(storageScheme = StorageScheme.Path))

    f.appStateStorage.hasRefCountsExact() shouldBe true
    withClue("no write of the run took the stamp: ") {
      f.journal.writes.asScala.toSeq.drop(before).forall { case (_, stampStands) => stampStands } shouldBe true
    }
  }

  it should "leave it when SNAP then spawns its first child" taggedAs UnitTest in {
    val f = new Fixture(new Journal)
    f.appStateStorage.markDatadirCreated(countsExact = true).commitSync()
    val snap = f.enterGenesisAccountSync(SNAPSyncConfig(storageScheme = StorageScheme.Path))
    f.factories.accountSpawns.get() shouldBe 1
    f.appStateStorage.hasRefCountsExact() shouldBe true
    f.awaitProcessed(snap)
  }
