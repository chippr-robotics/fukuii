package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 T033: what a coordinator decided, on its way to the disk. */
class ExcludedRecorderSpec extends AnyFlatSpec with Matchers:

  final private class FailingSource extends EphemDataSource(Map.empty):
    var failing = false
    var attempts = 0
    override def updateSync(updates: Seq[DataUpdate]): Unit =
      attempts += 1
      if failing then throw new RuntimeException("injected write failure")
      super.updateSync(updates)

  private val log = org.slf4j.LoggerFactory.getLogger(classOf[ExcludedRecorderSpec])
  private def entry(n: Int) = ExcludedAccount(ByteString(Array.fill(32)(n.toByte)), ExcludedReason.StorageSkipped)

  "A flush" should "write what was staged, once, and be nothing with nothing staged" taggedAs UnitTest in {
    val source = new FailingSource
    val recorder = new ExcludedRecorder(new SnapSyncProgressStorage(source), enabled = true, log)
    recorder.flush() shouldBe true
    source.attempts shouldBe 0
    recorder.stage(Seq(entry(1), entry(2)))
    recorder.stage(Seq(entry(1)))
    recorder.pending shouldBe true
    recorder.flush() shouldBe true
    recorder.pending shouldBe false
    source.attempts shouldBe 1
    new SnapSyncProgressStorage(source).excludedPage(None, 10) should have size 2
  }

  it should "keep what it could not write" taggedAs UnitTest in {
    val source = new FailingSource
    val recorder = new ExcludedRecorder(new SnapSyncProgressStorage(source), enabled = true, log)
    source.failing = true
    recorder.stage(Seq(entry(1)))
    recorder.flush() shouldBe false
    recorder.pending shouldBe true
    source.failing = false
    recorder.flush() shouldBe true
    new SnapSyncProgressStorage(source).hasExcluded shouldBe true
  }

  // Round 2 review vault N1: the end of every message tries, and a failing store is written to under its exclusive lock.
  "The end of a message" should "try a failing store at most once a second" taggedAs UnitTest in {
    val source = new FailingSource
    var now = 5000L
    val recorder = new ExcludedRecorder(new SnapSyncProgressStorage(source), enabled = true, log, () => now)
    recorder.stage(Seq(entry(1)))
    source.failing = true
    recorder.flushThrottled() shouldBe false
    source.attempts shouldBe 1
    now += 999
    recorder.flushThrottled() shouldBe false
    source.attempts shouldBe 1
    now += 1
    recorder.flushThrottled() shouldBe false
    source.attempts shouldBe 2
    source.failing = false
    now += 1000
    recorder.flushThrottled() shouldBe true
    recorder.pending shouldBe false
  }

  it should "not be held back after a write that worked" taggedAs UnitTest in {
    val source = new FailingSource
    val recorder = new ExcludedRecorder(new SnapSyncProgressStorage(source), enabled = true, log, () => 0L)
    recorder.stage(Seq(entry(1)))
    recorder.flushThrottled() shouldBe true
    recorder.stage(Seq(entry(2)))
    recorder.flushThrottled() shouldBe true
    source.attempts shouldBe 2
  }

  "A disabled recorder" should "stage nothing" taggedAs UnitTest in {
    val recorder = new ExcludedRecorder(throw new RuntimeException("not to be read"), enabled = false, log)
    recorder.stage(Seq(entry(1)))
    recorder.pending shouldBe false
    recorder.flush() shouldBe true
  }
