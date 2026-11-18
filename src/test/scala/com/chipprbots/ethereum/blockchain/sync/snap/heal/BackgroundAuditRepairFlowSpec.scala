package com.chipprbots.ethereum.blockchain.sync.snap.heal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.BackgroundAudit
import com.chipprbots.ethereum.blockchain.sync.BackgroundAudit.Command
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchedStateNode
import com.chipprbots.ethereum.blockchain.sync.regular.StateNodeFetcher
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState.*
import com.chipprbots.ethereum.db.storage.ArchiveStateStorage
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairFixture
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.testing.Tags.*

/** What the audit does with the answer of a repair (spec 018 C5b part 1 review): the hash scheme pushes a position back
  * whatever the repair said, as it always has; by position the write runs off the actor, a store that has failed closed
  * stops the audit and not the actor, and a position the anchor keeps declining is given up after a bound.
  */
class BackgroundAuditRepairFlowSpec
    extends ScalaTestWithActorTestKit(
      com.typesafe.config.ConfigFactory
        .parseString("sync-dispatcher { type = Dispatcher, executor = default-executor }")
    )
    with AnyFlatSpecLike
    with Matchers
    with Eventually:

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(10, Millis))

  private val settings = BackgroundAudit.Settings(readFactor = 0)

  private def serve(fetch: StateNodeFetcher.FetchStateNode, bytes: Array[Byte]): Unit =
    fetch.originalSender ! FetchedStateNode(NodeData(Seq(ByteString(bytes))))

  private def statusOf(audit: ActorRef[Command]): BackgroundAudit.Status =
    val probe = createTestProbe[BackgroundAudit.Status]()
    audit ! BackgroundAudit.GetStatus(probe.ref)
    probe.receiveMessage()

  private def logsOf[T](f: => T): (T, Seq[ILoggingEvent]) =
    val logger = LoggerFactory.getLogger(BackgroundAudit.LoggerName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val before = logger.getLevel
    logger.setLevel(Level.INFO)
    logger.addAppender(appender)
    try
      val result = f
      (result, appender.list.toArray(Array.empty[ILoggingEvent]).toSeq)
    finally
      logger.detachAppender(appender)
      logger.setLevel(before)

  private def fetcherTo(probe: TestProbe[StateNodeFetcher.FetchStateNode]): BackgroundAudit.FetcherFactory = ctx =>
    ctx.spawn(
      Behaviors.receiveMessage[StateNodeFetcher.StateNodeFetcherCommand] {
        case fetch: StateNodeFetcher.FetchStateNode =>
          probe.ref ! fetch
          Behaviors.same
        case _ => Behaviors.same
      },
      "fake-fetcher"
    )

  // ---- the hash scheme: the traversal after a repair is unchanged ----------------------------------------------------

  "The audit on the hash store" should "push a position back after a repair that was Rejected, and look at it again" taggedAs UnitTest in {
    val s = new AuditTestState
    s.app.putSnapSyncPivotBlock(5).commit()
    s.app.snapSyncDone().commit()
    val now = new AtomicLong(1_000_000_000_000L)
    val storage = s.update(None, 0, (0 until 30).map(j => trieKey(1000 + j) -> longValue(j)))
    val root =
      s.update(None, 0, (0 until 20).map(i => trieKey(i) -> accountBytes(if i == 3 then Some(storage) else None)))
    val (_, hash) = s.allNodes(root).filter((position, _) => position.isStorage && position.nibbles.length >= 2).last
    val bytes = s.reader.nodes(Seq(hash)).head.asInstanceOf[RawNodeRead.Bytes].encoded
    s.delete(hash)
    s.setHead(10, root, now.get / 1000)

    val repairs = new AtomicInteger(0)
    val rejecting = new ArchiveStateStorage(s.nodes):
      override def repairNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): RepairOutcome =
        repairs.incrementAndGet()
        RepairOutcome.Rejected
    val fetches = createTestProbe[StateNodeFetcher.FetchStateNode]()
    val audit = testKit.spawn(
      BackgroundAudit(
        BackgroundAudit.Deps(
          s.app,
          s.progress,
          rejecting,
          s.code,
          s.reader,
          fetcherTo(fetches),
          () => now.get,
          Some(ExecutionContext.parasitic),
          settings
        )
      )
    )
    // The position goes back on the stack with the hash it had, is read again, found absent again, and fetched again:
    // two requests for the one hash, where a position that was not pushed back would ask once.
    val first = fetches.expectMessageType[StateNodeFetcher.FetchStateNode]
    serve(first, bytes)
    val second = fetches.expectMessageType[StateNodeFetcher.FetchStateNode]
    second.hash shouldBe first.hash
    repairs.get() shouldBe 1
    testKit.stop(audit)
  }

  // ---- by position ---------------------------------------------------------------------------------------------------

  /** The real position store, with the answer of a repair and of a delete under a spec's hand. */
  private class StubRepairs(f: PositionRepairFixture) extends PositionRepairs:
    @volatile var onRepair: () => RepairOutcome = () => RepairOutcome.Written
    val repairCalls = new AtomicInteger(0)
    override def anchor: Option[BlockRef] = f.state.anchor
    override def rawRowsAt(requests: Seq[(PositionKey, ByteString)]): Seq[RawNodeRead] = f.state.rawRowsAt(requests)
    override def repairNodeAt(position: PositionKey, expectedHash: ByteString, bytes: Array[Byte]): RepairOutcome =
      repairCalls.incrementAndGet()
      onRepair()
    override def deleteBadNodeAt(position: PositionKey, expectedHash: ByteString): BadEntryDelete =
      f.state.deleteBadNodeAt(position, expectedHash)

  private class PositionNode(f: PositionRepairFixture, ec: ExecutionContext):
    val now = new AtomicLong(1_000_000_000_000L)
    f.appState.putSnapSyncPivotBlock(5).commit()
    f.appState.snapSyncDone().commit()
    val f0 = f
    val stub = new StubRepairs(f)
    val fetches: TestProbe[StateNodeFetcher.FetchStateNode] = createTestProbe[StateNodeFetcher.FetchStateNode]()
    val (position, bytes) = f.innerRow(None)
    f.remove(position)

    def spawn(): ActorRef[Command] =
      val code = new EvmCodeStorage(f.source)
      testKit.spawn(
        BackgroundAudit(
          BackgroundAudit.Deps(
            f.appState,
            new SnapSyncProgressStorage(f.source),
            new ArchiveStateStorage(new NodeStorage(f.source)),
            code,
            new BackgroundAudit.PositionReader(_ => Some(now.get / 1000), stub, code),
            fetcherTo(fetches),
            positions = Some(stub),
            nowMillis = () => now.addAndGet(1500),
            readEc = Some(ec),
            settings = settings
          )
        )
      )

  it should "run a repair off the actor: the audit still answers while it waits for a head move" taggedAs UnitTest in {
    val pool = Executors.newFixedThreadPool(2)
    try
      val n = new PositionNode(new PositionRepairFixture, ExecutionContext.fromExecutor(pool))
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      n.stub.onRepair = () =>
        entered.countDown()
        release.await(10, TimeUnit.SECONDS) shouldBe true
        RepairOutcome.Written
      val audit = n.spawn()
      serve(n.fetches.expectMessageType[StateNodeFetcher.FetchStateNode], n.bytes)
      entered.await(10, TimeUnit.SECONDS) shouldBe true
      // The write is held; an actor that made it itself could not answer.
      val held = statusOf(audit)
      held.fetching should not be empty
      held.counters.repaired shouldBe 0L
      release.countDown()
      eventually(statusOf(audit).counters.repaired shouldBe 1L)
      testKit.stop(audit)
    finally pool.shutdownNow()
  }

  it should "stop itself, logged, when the store has failed closed, and not kill the actor with the throw" taggedAs UnitTest in {
    val n = new PositionNode(new PositionRepairFixture, ExecutionContext.parasitic)
    n.stub.onRepair = () => throw new StateUnavailable("this node's state takes no write")
    val (_, events) = logsOf {
      val audit = n.spawn()
      serve(n.fetches.expectMessageType[StateNodeFetcher.FetchStateNode], n.bytes)
      // A throw out of the actor would restart it under its supervisor: it would not terminate.
      createTestProbe().expectTerminated(audit, 10.seconds)
    }
    val refused = events.filter(e => e.getLevel == Level.ERROR && e.getFormattedMessage.contains("refused"))
    refused should have size 1
    refused.head.getFormattedMessage should include("this node's state takes no write")
    n.f0.appState.getSnapAuditCleanAnchor() shouldBe None
  }

  it should "give a position up as unresolved after a bounded number of declines, and end the pass" taggedAs UnitTest in {
    val n = new PositionNode(new PositionRepairFixture, ExecutionContext.parasitic)
    // The anchor's root never agrees with the walk about this position: every repair is declined.
    n.stub.onRepair = () => RepairOutcome.NotReached
    val audit = n.spawn()
    (1 to 3).foreach(_ => serve(n.fetches.expectMessageType[StateNodeFetcher.FetchStateNode], n.bytes))
    val st = eventually {
      val st = statusOf(audit)
      st.waitingForNextPass shouldBe true
      st
    }
    n.stub.repairCalls.get() shouldBe 3
    st.counters.unresolved shouldBe 1L
    st.counters.moved shouldBe 3L
    n.f0.appState.getSnapAuditCleanAnchor() shouldBe None
    testKit.stop(audit)
  }
