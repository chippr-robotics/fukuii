package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable.ArrayBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The stamp that tells basic pruning whether it may delete trie nodes by reference count (#1692, [[RefCountTrust]]):
  * where it is written, where it is taken away, and every write that must leave it alone.
  */
class RefCountTrustSpec extends AnyFlatSpec with Matchers:

  /** Records which writes were synced. */
  private class Recording extends EphemDataSource(Map.empty):
    val synced: ArrayBuffer[Seq[DataUpdate]] = ArrayBuffer.empty
    val plain: ArrayBuffer[Seq[DataUpdate]] = ArrayBuffer.empty
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      synced += dataSourceUpdates
      super.update(dataSourceUpdates)
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      plain += dataSourceUpdates
      super.update(dataSourceUpdates)

  private class Datadir:
    val source = new Recording
    val app = new AppStateStorage(source)
    def hash(n: Int): ByteString = ByteString(Array.fill[Byte](32)(n.toByte))

    /** A datadir whose genesis counted every position, as a fresh one does. */
    def stamped(): Datadir =
      app.markDatadirCreated(countsExact = true).commitSync()
      source.synced.clear()
      source.plain.clear()
      this

  "The genesis write" should "stamp the datadir exact, and say who made it, in one synced write" taggedAs UnitTest in {
    val d = new Datadir
    d.app.hasRefCountsExact() shouldBe false
    d.app.markDatadirCreated(countsExact = true).commitSync()
    d.app.hasRefCountsExact() shouldBe true
    d.app.isDatadirCreatedUnderInvariant() shouldBe true
    d.source.synced.size shouldBe 1
    d.source.plain shouldBe empty
  }

  it should "leave the datadir unstamped when the genesis does not count every position, still in one synced write" taggedAs UnitTest in {
    val d = new Datadir
    d.app.markDatadirCreated(countsExact = false).commitSync()
    d.app.isDatadirCreatedUnderInvariant() shouldBe true
    d.app.hasRefCountsExact() shouldBe false
    d.source.synced.size shouldBe 1
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false
  }

  "RefCountTrust.nodesMayBeDeleted" should "need the stamp, and say no without it" taggedAs UnitTest in {
    val d = new Datadir
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false
    d.app.markRefCountsExact().commit()
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe true
  }

  it should "say no on a datadir an older binary made, whatever SNAP left in it" taggedAs UnitTest in {
    val d = new Datadir
    d.app.snapSyncDone().and(d.app.putBestBlockNumber(1000)).commit()
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false
  }

  it should "say no when the read fails" taggedAs UnitTest in {
    val d = new Datadir
    val failing = new AppStateStorage(d.source):
      override def hasRefCountsExact(): Boolean = throw new RuntimeException("disk")
    RefCountTrust.nodesMayBeDeleted(failing) shouldBe false
  }

  "A Hash-scheme SNAP start" should "take the stamp away, in a synced write" taggedAs UnitTest in {
    val d = new Datadir().stamped()

    RefCountTrust.withdrawAtSnapStart(d.app, hashScheme = true) shouldBe true
    d.app.hasRefCountsExact() shouldBe false
    d.source.synced.size shouldBe 1
    d.source.plain shouldBe empty
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false

    withClue("a later SNAP start has nothing to take, and writes nothing: ") {
      RefCountTrust.withdrawAtSnapStart(d.app, hashScheme = true) shouldBe false
      d.source.synced.size shouldBe 1
    }
  }

  "A Path-scheme SNAP start" should "leave the stamp: SNAP writes the path-keyed store, and the publish decides" taggedAs UnitTest in {
    val d = new Datadir().stamped()
    RefCountTrust.withdrawAtSnapStart(d.app, hashScheme = false) shouldBe false
    d.app.hasRefCountsExact() shouldBe true
    d.source.synced shouldBe empty
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe true
  }

  "The publish of a Path hand-off that is not clean" should "take the stamp away, in a synced write, once" taggedAs UnitTest in {
    val d = new Datadir().stamped()

    RefCountTrust.withdrawAtPathPublish(d.app, handOffClean = false) shouldBe true
    d.app.hasRefCountsExact() shouldBe false
    d.source.synced.size shouldBe 1
    d.source.plain shouldBe empty
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false

    withClue("a second publish has nothing to take, and writes nothing: ") {
      RefCountTrust.withdrawAtPathPublish(d.app, handOffClean = false) shouldBe false
      d.source.synced.size shouldBe 1
    }
  }

  "The publish of a Path hand-off that is clean" should "leave the stamp, and write nothing" taggedAs UnitTest in {
    val d = new Datadir().stamped()
    RefCountTrust.withdrawAtPathPublish(d.app, handOffClean = true) shouldBe false
    d.app.hasRefCountsExact() shouldBe true
    d.source.synced shouldBe empty
    d.source.plain shouldBe empty
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe true
  }

  "The publish on an old Path datadir with no stamp" should "write nothing, and the datadir keeps every node" taggedAs UnitTest in {
    val d = new Datadir
    RefCountTrust.withdrawAtPathPublish(d.app, handOffClean = false) shouldBe false
    RefCountTrust.withdrawAtPathPublish(d.app, handOffClean = true) shouldBe false
    d.source.synced shouldBe empty
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false
  }

  "beginCheckpointImport" should "take the stamp away in the one synced write of its own key, on either scheme" taggedAs UnitTest in {
    val d = new Datadir().stamped()

    d.app.beginCheckpointImport(d.hash(7), 0L).commitSync()
    d.app.getCheckpointImportInProgress() shouldBe defined
    d.app.hasRefCountsExact() shouldBe false
    d.source.synced.size shouldBe 1
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false
  }

  /** Every write that clears or stamps something else in the app state. None of them may touch the stamp. */
  private def write(name: String)(f: AppStateStorage => Unit): (String, AppStateStorage => Unit) = (name, f)

  private val otherWrites: Seq[(String, AppStateStorage => Unit)] = Seq(
    write("clearSnapSyncDone")(_.clearSnapSyncDone().commit()),
    write("snapSyncDone")(_.snapSyncDone().commit()),
    write("clearFastSyncDone")(_.clearFastSyncDone().commit()),
    write("stampSnapHealInvariant")(_.stampSnapHealInvariant().commit()),
    write("clearSnapHealInvariant")(_.clearSnapHealInvariant().commit()),
    write("markSnapCodeScanOwed")(_.markSnapCodeScanOwed("test").commit()),
    write("clearSnapCodeScanOwed")(_.clearSnapCodeScanOwed().commit()),
    write("endCheckpointImport")(_.endCheckpointImport().commit()),
    write("recoveryDone")(_.recoveryDone().commit()),
    write("clearBytecodeRecoveryDone")(_.clearBytecodeRecoveryDone().commit()),
    write("clearStorageRecoveryDone")(_.clearStorageRecoveryDone().commit()),
    write("putSnapAuditCleanAnchor")(_.putSnapAuditCleanAnchor("5").commit()),
    write("markDatadirCreatedUnderInvariant")(_.markDatadirCreatedUnderInvariant().commit()),
    write("markDatadirCreated, not exact")(_.markDatadirCreated(countsExact = false).commit()),
    write("markHealingFrontierRetired")(_.markHealingFrontierRetired().commit()),
    write("putBestBlockInfo")(_.putBestBlockInfo(BlockInfo(ByteString(1), 5)).commit()),
    write("clearDoneOnStart") { a =>
      val _ = HealInvariant.clearDoneOnStart(a)
    },
    write("atSnapStart, can hold") { a =>
      val _ = HealInvariant.atSnapStart(a, None, canHold = true)
    },
    write("atSnapStart, cannot hold") { a =>
      val _ = HealInvariant.atSnapStart(a, None, canHold = false)
    }
  )

  "The stamp" should "survive every other write to the app state" taggedAs UnitTest in {
    otherWrites.foreach { case (name, act) =>
      val d = new Datadir().stamped()
      act(d.app)
      withClue(s"$name: ")(d.app.hasRefCountsExact() shouldBe true)
    }
  }

  "A datadir that lost the stamp" should "not get it back from any other write to the app state" taggedAs UnitTest in {
    otherWrites.foreach { case (name, act) =>
      val d = new Datadir().stamped()
      RefCountTrust.withdrawAtSnapStart(d.app, hashScheme = true) shouldBe true
      act(d.app)
      withClue(s"$name: ")(d.app.hasRefCountsExact() shouldBe false)
    }
  }

  it should "not get it back from a finished checkpoint import" taggedAs UnitTest in {
    val d = new Datadir().stamped()
    d.app.beginCheckpointImport(d.hash(7), 0L).commitSync()
    d.app
      .snapSyncDone()
      .and(d.app.bytecodeRecoveryDone())
      .and(d.app.storageRecoveryDone())
      .and(d.app.endCheckpointImport())
      .commitSync()
    d.app.hasRefCountsExact() shouldBe false
    RefCountTrust.nodesMayBeDeleted(d.app) shouldBe false
  }
