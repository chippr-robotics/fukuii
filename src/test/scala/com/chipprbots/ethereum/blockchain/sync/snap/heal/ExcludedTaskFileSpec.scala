package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import java.nio.file.Files

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 review hunter C1, round 2 review C: the durable lists read back. */
class ExcludedTaskFileSpec extends AnyFlatSpec with Matchers:

  private def h(n: Int): ByteString = ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, n + 1).array())
  private def file(bytes: Array[Byte]): java.nio.file.Path =
    val path = Files.createTempFile("excluded-task-file-spec", ".bin")
    path.toFile.deleteOnExit()
    Files.write(path, bytes)
    path
  private def storageFile(n: Int) = file((0 until n).flatMap(i => (h(i) ++ h(i + 1000)).toArray).toArray)
  private def done() = new SnapStorageDoneStorage(EphemDataSource())

  "A read of the task file" should "name what has no done marker, and be told apart from one with the wrong size" taggedAs UnitTest in {
    val markers = done()
    markers.markDone(Seq(h(0) -> h(1000))).commit()
    var found = Seq.empty[ExcludedAccount]
    ExcludedTaskFile.recordUnfinished(storageFile(3), Some(3L), markers, found ++= _) shouldBe
      ExcludedTaskFile.Outcome.Recorded(2L)
    found.map(_.subject) shouldBe Seq(h(1), h(2))
    found.map(_.claimedStorageRoot) shouldBe Seq(h(1001), h(1002))
  }

  it should "call a file of another size than recorded unreadable, and write nothing" taggedAs UnitTest in {
    var found = Seq.empty[ExcludedAccount]
    ExcludedTaskFile.recordUnfinished(storageFile(3), Some(4L), done(), found ++= _) shouldBe
      a[ExcludedTaskFile.Outcome.Unreadable]
    found shouldBe empty
  }

  it should "call a missing file unreadable" taggedAs UnitTest in {
    val path = storageFile(1)
    Files.delete(path)
    ExcludedTaskFile.recordUnfinished(path, None, done(), _ => ()) shouldBe a[ExcludedTaskFile.Outcome.Unreadable]
  }

  it should "say a store that fails is worth trying again" taggedAs UnitTest in {
    ExcludedTaskFile.recordUnfinished(storageFile(1), None, done(), _ => throw new RuntimeException("no")) shouldBe
      a[ExcludedTaskFile.Outcome.StoreFailed]
  }

  // Round 2 review C: after a restart of the cycle the markers are gone, and every entry reads as unfinished.
  it should "stop at the next chunk once cancelled" taggedAs UnitTest in {
    var chunks = 0
    val outcome = ExcludedTaskFile.recordUnfinished(
      storageFile(10),
      None,
      done(),
      _ => chunks += 1,
      cancelled = () => chunks >= 2,
      chunkEntries = 3
    )
    outcome shouldBe ExcludedTaskFile.Outcome.Cancelled
    chunks shouldBe 2
  }

  "A read of the code-hash file" should "name what the code store does not have, and never the empty code hash" taggedAs UnitTest in {
    val path = file((h(0) ++ h(1) ++ Account.EmptyCodeHash.value).toArray)
    var found = Seq.empty[ExcludedAccount]
    ExcludedTaskFile.recordMissingCode(path, Some(3L), _ == h(0), found ++= _) shouldBe
      ExcludedTaskFile.Outcome.Recorded(1L)
    found.map(_.subject) shouldBe Seq(h(1))
    found.foreach(_.isCodeOnly shouldBe true)
  }

  "A sweep" should "report through its token, and only the one running matches" taggedAs UnitTest in {
    val sweep = new ExcludedSweep
    var reported = Seq.empty[(Long, ExcludedTaskFile.Outcome)]
    val first = sweep.start(scala.concurrent.ExecutionContext.parasitic)(_ => ExcludedTaskFile.Outcome.Recorded(1L)) {
      (t, o) =>
        reported = reported :+ (t, o)
    }
    reported shouldBe Seq((first, ExcludedTaskFile.Outcome.Recorded(1L)))
    sweep.running shouldBe true
    sweep.finish(first + 1) shouldBe false
    sweep.running shouldBe true
    sweep.finish(first) shouldBe true
    sweep.running shouldBe false
    sweep.finish(first) shouldBe false
  }

  it should "tell its work to stop, when finished or cancelled" taggedAs UnitTest in {
    val sweep = new ExcludedSweep
    var flag: () => Boolean = () => false
    val token = sweep.start(scala.concurrent.ExecutionContext.parasitic) { work =>
      flag = work.cancelled
      ExcludedTaskFile.Outcome.Cancelled
    }((_, _) => ())
    flag() shouldBe false
    sweep.cancel()
    flag() shouldBe true
    sweep.finish(token) shouldBe true
  }

  // spec 022 T050a (vault W2): the sweep's limit is "no chunk finished", not a cap on the whole of it.
  it should "say whether a chunk finished since the watchdog last looked, and nothing of a sweep not running" taggedAs UnitTest in {
    val sweep = new ExcludedSweep
    var handle: Option[ExcludedSweep.Work] = None
    val token = sweep.start(scala.concurrent.ExecutionContext.parasitic) { work =>
      handle = Some(work)
      ExcludedTaskFile.Outcome.Cancelled
    }((_, _) => ())
    withClue("nothing finished yet: ")(sweep.chunkFinishedSinceLastLook(token) shouldBe Some(false))
    handle.get.chunkDone()
    handle.get.chunkDone()
    sweep.chunkFinishedSinceLastLook(token) shouldBe Some(true)
    withClue("nothing since that look: ")(sweep.chunkFinishedSinceLastLook(token) shouldBe Some(false))
    handle.get.chunkDone()
    sweep.chunkFinishedSinceLastLook(token) shouldBe Some(true)
    sweep.chunkFinishedSinceLastLook(token + 1) shouldBe None
    sweep.finish(token) shouldBe true
    sweep.chunkFinishedSinceLastLook(token) shouldBe None
    sweep.lastRun should include("3 chunk(s)")
  }

  it should "count each chunk that ends, and none that fails" taggedAs UnitTest in {
    var done = 0
    ExcludedTaskFile.recordMissingCode(
      file((h(0) ++ h(1) ++ h(2)).toArray),
      Some(3L),
      _ => true,
      _ => (),
      chunkEntries = 1,
      chunkDone = () => done += 1
    ) shouldBe ExcludedTaskFile.Outcome.Recorded(0L)
    done shouldBe 3

    done = 0
    ExcludedTaskFile.recordMissingCode(
      file((h(0) ++ h(1) ++ h(2)).toArray),
      Some(3L),
      _ => false,
      _ => throw new RuntimeException("the write failed"),
      chunkEntries = 1,
      chunkDone = () => done += 1
    ) shouldBe a[ExcludedTaskFile.Outcome.StoreFailed]
    done shouldBe 0
  }

  it should "report a refused executor as worth trying again" taggedAs UnitTest in {
    val refusing = new scala.concurrent.ExecutionContext:
      def execute(runnable: Runnable): Unit = throw new java.util.concurrent.RejectedExecutionException("no")
      def reportFailure(cause: Throwable): Unit = ()
    val sweep = new ExcludedSweep
    var reported: Option[ExcludedTaskFile.Outcome] = None
    sweep.start(refusing)(_ => ExcludedTaskFile.Outcome.Recorded(0L))((_, o) => reported = Some(o))
    reported.get shouldBe a[ExcludedTaskFile.Outcome.StoreFailed]
  }
