package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import scala.collection.mutable.ListBuffer

import ch.qos.logback.classic.Level
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.testing.LogCapture
import com.chipprbots.ethereum.testing.Tags.*

/** B-462 (forge, 2026-11-24): a writer refused because another thread's adoption holds the head is refused as a
  * [[StateBusy]], which no escalation counts; the age of the attempt that is open is told for its gauge, and a refusal
  * by an attempt older than [[PathStateStorage.CommitSettings.attemptAgeWarnMillis]] is said once at WARN.
  */
class StateBusySpec extends AnyFlatSpec with Matchers:
  import PathStateStorage.*
  import PathStateTestKit.*

  final private class Told extends CommitObserver:
    val openSince: ListBuffer[Option[Long]] = ListBuffer.empty
    override def attemptOpenSince(openedAt: Option[Long]): Unit = synchronized(openSince += openedAt)

  private class Fixture(warnAfterMillis: Long = 60000L):
    val now = new AtomicLong(1000000L)
    val told = new Told
    val state: PathStateStorage =
      val source = new EphemDataSource(Map.empty)
      new PathStateStorage(
        new PathNodeStorage(source),
        new AppStateStorage(source),
        settings = CommitSettings(observer = told, nowMillis = () => now.get, attemptAgeWarnMillis = warnAfterMillis)
      )
    val fork: BlockRef = block(5, EmptyRoot)

    /** `body` on a thread of its own, waited for. */
    def elsewhere[A](body: => A): scala.util.Try[A] =
      val result = new AtomicReference[scala.util.Try[A]]()
      val thread = new Thread(() => result.set(scala.util.Try(body)), "the-other-writer")
      thread.start()
      thread.join()
      result.get

    def busy(attempt: scala.util.Try[?]): StateBusy = attempt.failed.toOption match
      case Some(refused: StateBusy) => refused
      case other                    => fail(s"not refused as busy: $other")

  "A writer on another thread while an attempt holds the records" should "be refused as busy, naming the attempt, and never as a StateUnavailable" taggedAs
    (UnitTest, ConsensusTest) in (new Fixture:
      state.withRecordsHeld(fork) {
        val refused = busy(elsewhere(state.withRecordsHeld(fork)(fail("a second attempt ran"))))
        refused.holder should include(Thread.currentThread().getName)
        refused.holder should include("the records above block")
        refused.reason should include("is moving the head")
        (refused: StateFault) should not be a[StateUnavailable]
      }
      // once the attempt has ended, the same hold is made
      elsewhere(state.withRecordsHeld(fork)(42)).get shouldBe 42
    )

  it should "tell the observer when the oldest open attempt was opened, and none once it has ended" taggedAs UnitTest in (new Fixture:
    state.oldestAttemptAgeMillis shouldBe None
    state.withRecordsHeld(fork) {
      now.addAndGet(7000L)
      state.oldestAttemptAgeMillis shouldBe Some(7000L)
    }
    state.oldestAttemptAgeMillis shouldBe None
    told.openSince.toList shouldBe List(Some(1000000L), None)
  )

  it should "say once at WARN, tagged, that an attempt older than the bound refuses writers, and not before" taggedAs
    UnitTest in (new Fixture(warnAfterMillis = 60000L):
      def warnings(events: List[ch.qos.logback.classic.spi.ILoggingEvent]): List[String] =
        events.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage).filter(_.contains("[STATE-BUSY]"))
      state.withRecordsHeld(fork) {
        // young: refused, and nothing said
        val (_, young) = LogCapture.capture(classOf[PathStateStorage])(busy(elsewhere(state.withRecordsHeld(fork)(()))))
        warnings(young) shouldBe empty
        // older than the bound: said once, with its age and the bound
        now.addAndGet(60001L)
        val (_, old) = LogCapture.capture(classOf[PathStateStorage]) {
          busy(elsewhere(state.withRecordsHeld(fork)(())))
          busy(elsewhere(state.withRecordsHeld(fork)(())))
        }
        val said = warnings(old)
        said should have size 1
        said.head should (include("60s").and(include("more than 60s")))
        // nothing of it is at ERROR
        old.filter(_.getLevel == Level.ERROR) shouldBe empty
      }
    )
