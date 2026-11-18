package com.chipprbots.ethereum.blockchain.sync.snap.heal

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.ExecutionContext

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.BackgroundAudit
import com.chipprbots.ethereum.blockchain.sync.BackgroundAudit.Command
import com.chipprbots.ethereum.blockchain.sync.regular.StateNodeFetcher
import com.chipprbots.ethereum.db.storage.ArchiveStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairFixture
import com.chipprbots.ethereum.testing.Tags.*

/** The audit as an actor over the position store (spec 018 C5b, T210): a first pass over a clean store ends clean,
  * moves the clean anchor, and records the time it took. A pass over a store with a hole is not clean, and the hole is
  * left for a fetch (no peer is asked here).
  */
class BackgroundAuditPositionSpec
    extends ScalaTestWithActorTestKit(
      com.typesafe.config.ConfigFactory
        .parseString("sync-dispatcher { type = Dispatcher, executor = default-executor }")
    )
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  private class Node(f: PositionRepairFixture):
    val now = new AtomicLong(1_000_000_000_000L)
    f.appState.putSnapSyncPivotBlock(5).commit()
    f.appState.snapSyncDone().commit()
    val progress = new SnapSyncProgressStorage(f.source)
    val code = new EvmCodeStorage(f.source)

    /** Every look at the clock takes a second and a half: the pass has a duration. */
    def spawn(): ActorRef[Command] = testKit.spawn(
      BackgroundAudit(
        BackgroundAudit.Deps(
          f.appState,
          progress,
          new ArchiveStateStorage(new NodeStorage(f.source)),
          code,
          new BackgroundAudit.PositionReader(_ => Some(now.get / 1000), f.state, code),
          // a hole is asked of nobody: the request goes to a fetcher that never answers
          ctx => ctx.spawn(Behaviors.ignore[StateNodeFetcher.StateNodeFetcherCommand], "silent-fetcher"),
          positions = Some(f.state),
          nowMillis = () => now.addAndGet(1500),
          readEc = Some(ExecutionContext.parasitic),
          settings = BackgroundAudit.Settings(readFactor = 0)
        )
      )
    )

  private def logsOf[T](f: => T): (T, Seq[String]) =
    val logger = LoggerFactory.getLogger(BackgroundAudit.LoggerName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val before = logger.getLevel
    logger.setLevel(Level.INFO)
    logger.addAppender(appender)
    try
      val result = f
      (result, appender.list.toArray(Array.empty[ILoggingEvent]).toSeq.map(_.getFormattedMessage))
    finally
      logger.detachAppender(appender)
      logger.setLevel(before)

  "The audit over a clean position store" should "end its first pass clean, record the anchor, and record its time" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val n = new Node(f)
    f.appState.isSnapAuditOwed() shouldBe true
    val anchor = f.appState.snapAuditAnchor()

    val (_, lines) = logsOf {
      val audit = n.spawn()
      createTestProbe().expectTerminated(audit)
    }

    f.appState.getSnapAuditCleanAnchor() shouldBe Some(anchor)
    f.appState.isSnapAuditOwed() shouldBe false
    val ended = lines.find(_.contains("ended clean")).getOrElse(fail(s"no clean end in: $lines"))
    ended should include(s"nodes=${f.rowsOf().size}")
    ended should include("holes=0")
    withClue(ended)("elapsed=0s" should not be ended.drop(ended.indexOf("elapsed=")).take(9))
  }

  "The audit over a position store with a hole" should "not end the pass clean" taggedAs UnitTest in {
    val f = new PositionRepairFixture
    val n = new Node(f)
    val (position, _) = f.innerRow(None)
    f.remove(position)
    val anchor = f.appState.snapAuditAnchor()

    val (audit, lines) = logsOf {
      val audit = n.spawn()
      eventually(holesOf(audit) should be > 0L)
      audit
    }

    f.appState.getSnapAuditCleanAnchor() should not be Some(anchor)
    lines.exists(line => line.contains("fails its hash") || line.contains("is absent")) shouldBe true
    testKit.stop(audit)
  }

  private def holesOf(audit: org.apache.pekko.actor.typed.ActorRef[BackgroundAudit.Command]): Long =
    val probe = createTestProbe[BackgroundAudit.Status]()
    audit ! BackgroundAudit.GetStatus(probe.ref)
    probe.receiveMessage().counters.holes
