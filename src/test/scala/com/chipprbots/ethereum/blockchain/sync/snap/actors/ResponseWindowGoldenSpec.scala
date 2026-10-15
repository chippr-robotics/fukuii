package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.actors.account.AccountPeerDispatch
import com.chipprbots.ethereum.blockchain.sync.snap.actors.account.StubAccountPeerDispatchState
import com.chipprbots.ethereum.blockchain.sync.snap.actors.storage.StoragePeerDispatch
import com.chipprbots.ethereum.blockchain.sync.snap.actors.storage.StubStoragePeerDispatchState
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Golden test of the adaptive response-size window of the three SNAP coordinators (spec 016 T075b, C-X0).
  *
  * AccountRangeCoordinator (ARC, in `AccountPeerDispatch`), StorageRangeCoordinator (SRC, in `StoragePeerDispatch`) and
  * TrieNodeHealingCoordinator (TNHC, inside the actor) each keep a per-peer `responseBytes` target: it starts at an
  * initial value, grows by 1.25x when a response fills at least 90% of the request, halves on a failure, and is clamped
  * to [min, 2 MiB]. C-X will extract one `AdaptiveResponseBytes` for all three, with the log label as a parameter.
  * These cases pin today's behaviour, in each coordinator, so that extraction can be shown to change nothing. They are
  * deliberately written against the observable surface only: the per-peer map, the requests the coordinator sends and
  * the DEBUG line it logs, so they hold unchanged across the extraction.
  *
  * ARC and SRC are driven through their module traits on the C-A1/C-S1 stubs; TNHC through the real actor, with a
  * `TrieNodes` response or a `HealingRequestTimeout` per step (its window is private, so the requests it sends and its
  * log lines are what is observed). Constants are the production defaults (`sync.conf`: account 524288/102400, storage
  * 1048576/131072; TNHC hard-codes 524288/51200). The ceiling is 2 MiB in all three.
  *
  * Differences found between the three (recorded here for C-X, which assumes identical bodies):
  *   - The log label is the only textual difference: `account`, `storage`, `healing`.
  *   - ARC and SRC take initial and min from configuration (lazy vals); TNHC hard-codes them. The grow and shrink
  *     factors (1.25, 0.5), the 90% rule, the 2 MiB ceiling and the rounding (`toDouble * factor` then `toLong`) are
  *     the same in all three.
  *   - Growth is computed from the `requested` argument, not from the stored target, in all three (a stored value below
  *     the request is overwritten upward).
  *   - ARC exposes `responseBytesTargetFor` publicly, SRC keeps it private and TNHC keeps everything private: the
  *     window is observed through the map (ARC, SRC) and the sent requests (TNHC).
  *   - TNHC reaches the window from two failure sites (`request timeout`, `empty healing response`) and one success
  *     site that is gated on at least one node healed; its `requested` is the value the request was sent with.
  */
class ResponseWindowGoldenSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val actorTestKit: ActorTestKit = testKit

  private val MaxBytes = BigInt(2 * 1024 * 1024)

  private def peer(id: String): Peer =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), TestInbox[PeerActor.Command]().ref, false)

  /** Every line the `snap` package logs while open, with its level (DEBUG and up), without printing it. A local
    * appender rather than `SnapLogCapture`, which keeps the message only and so cannot pin the level.
    */
  final private class LevelCapture extends AutoCloseable:
    private val logger =
      LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.sync.snap").asInstanceOf[LogbackLogger]
    private val previousLevel: Level = logger.getLevel
    private val previousAdditive: Boolean = logger.isAdditive
    private val events = new LinkedBlockingQueue[(Level, String)]()

    private val appender = new AppenderBase[ILoggingEvent]:
      override def append(event: ILoggingEvent): Unit = events.put((event.getLevel, event.getFormattedMessage))

    appender.setContext(logger.getLoggerContext)
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    logger.setAdditive(false)

    // `logback-test.xml` pins some loggers under the package explicitly (the TrieNodeHealingCoordinator at INFO), and an
    // explicit level beats the parent's, so a DEBUG line from the actor would be dropped. Raise every explicitly
    // levelled logger under the package, and the coordinator's own names whether or not they are configured, to DEBUG.
    private val snapPrefix = "com.chipprbots.ethereum.blockchain.sync.snap"
    private val coordinatorNames =
      Seq("TrieNodeHealingCoordinator", "TrieNodeHealingCoordinator$", "TrieNodeHealingCoordinator$Impl")
        .map(n => s"$snapPrefix.actors.$n")
    private val raised: List[(LogbackLogger, Level)] =
      (logger.getLoggerContext.getLoggerList.asScala.toList
        .filter(l => l.getName.startsWith(snapPrefix + ".") && l.getLevel != null) ++
        coordinatorNames.map(n => LoggerFactory.getLogger(n).asInstanceOf[LogbackLogger])).distinct
        .map(l => (l, l.getLevel))
    raised.foreach { case (l, _) => l.setLevel(Level.DEBUG) }

    /** The captured window-shrink lines of one peer (the peer id is unique per case, so parallel suites cannot mix). */
    def reductions(p: Peer): List[(Level, String)] =
      events.asScala.toList.filter { case (_, m) =>
        m.startsWith("Reducing ") && m.contains(s"responseBytes target for peer ${p.id.value}:")
      }

    /** Wait for the next line containing `substring` (consuming earlier lines), up to `max`. */
    def await(substring: String, max: FiniteDuration = 10.seconds): (Level, String) =
      val deadline = System.nanoTime() + max.toNanos
      @annotation.tailrec
      def loop(): (Level, String) =
        val remaining = deadline - System.nanoTime()
        if remaining <= 0 then throw new AssertionError(s"no log line containing '$substring' within $max")
        val next = events.poll(remaining, TimeUnit.NANOSECONDS)
        if next == null then throw new AssertionError(s"no log line containing '$substring' within $max")
        else if next._2.contains(substring) then next
        else loop()
      loop()

    override def close(): Unit =
      logger.detachAppender(appender)
      appender.stop()
      raised.foreach { case (l, previous) => l.setLevel(previous) }
      logger.setLevel(previousLevel)
      logger.setAdditive(previousAdditive)

  /** The window of one module, seen through its public members only. */
  private trait Window:
    def success(p: Peer, requested: BigInt, received: BigInt): Unit
    def failure(p: Peer, reason: String): Unit
    def stored(p: Peer): Option[BigInt]
    def seed(p: Peer, value: BigInt): Unit

  private def arcWindow(): Window =
    val controller = TestInbox[SNAPSyncController.Command]()
    val peerManager = TestInbox[NetworkPeerManagerActor.Command]()
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    var captured: StubAccountPeerDispatchState & AccountPeerDispatch = null
    var action: () => Unit = () => ()
    val kit = BehaviorTestKit(Behaviors.setup[AccountRangeCoordinator.Command] { ctx =>
      Behaviors.withTimers { timers =>
        captured = new StubAccountPeerDispatchState(ctx, timers, peerManager.ref, tracker, controller.ref, None)
          with AccountPeerDispatch
        Behaviors.receiveMessage { _ =>
          action()
          Behaviors.same
        }
      }
    })
    val module = captured
    new Window:
      def success(p: Peer, requested: BigInt, received: BigInt): Unit =
        module.adjustResponseBytesOnSuccess(p, requested, received)
      def failure(p: Peer, reason: String): Unit =
        action = () => module.adjustResponseBytesOnFailure(p, reason)
        kit.run(AccountRangeCoordinator.CheckCompletion)
      def stored(p: Peer): Option[BigInt] = module.peerResponseBytesTarget.get(p.id.value)
      def seed(p: Peer, value: BigInt): Unit = module.peerResponseBytesTarget(p.id.value) = value

  private def srcWindow(): Window =
    val controller = TestInbox[SNAPSyncController.Command]()
    val peerManager = TestInbox[NetworkPeerManagerActor.Command]()
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    var captured: StubStoragePeerDispatchState & StoragePeerDispatch = null
    var action: () => Unit = () => ()
    val kit = BehaviorTestKit(Behaviors.setup[StorageRangeCoordinator.Command] { ctx =>
      captured = new StubStoragePeerDispatchState(ctx, peerManager.ref, tracker, controller.ref, None)
        with StoragePeerDispatch
      Behaviors.receiveMessage { _ =>
        action()
        Behaviors.same
      }
    })
    val module = captured
    new Window:
      def success(p: Peer, requested: BigInt, received: BigInt): Unit =
        module.adjustResponseBytesOnSuccess(p, requested, received)
      def failure(p: Peer, reason: String): Unit =
        action = () => module.adjustResponseBytesOnFailure(p, reason)
        kit.run(StorageRangeCoordinator.StorageCheckCompletion)
      def stored(p: Peer): Option[BigInt] = module.peerResponseBytesTarget.get(p.id.value)
      def seed(p: Peer, value: BigInt): Unit = module.peerResponseBytesTarget(p.id.value) = value

  /** A storage whose raw-node flush fails. The healing actor flushes healed nodes on a Future and only a successful one
    * sends `FlushComplete`; until it does, `flushing` stays set and `HealingCheckCompletion` cannot start the
    * verification BFS, which would walk the (absent) root and send further `GetTrieNodes` into the window under test.
    * Failing here, by construction, makes that so rather than leaving it to what a test blob happens to decode to.
    */
  final private class FlushFailsStorage extends TestMptStorage:
    override def storeRawNodes(rawNodes: Seq[(ByteString, Array[Byte])]): Unit =
      throw new IllegalStateException("response-window golden: the flush is meant to fail, see FlushFailsStorage")

  /** The cases ARC and SRC share. `failures` is the (before, after) pair of each of the consecutive failures from a
    * fresh peer; `growth` the stored target after each consecutive full-size success from a fresh peer; `threshold` the
    * smallest `received` that is at least 90% of `initial`.
    */
  private def registerModuleCases(
      name: String,
      label: String,
      initial: BigInt,
      min: BigInt,
      threshold: BigInt,
      failures: Seq[(BigInt, BigInt)],
      growth: Seq[BigInt],
      newWindow: () => Window
  ): Unit =
    def reduction(p: Peer, before: BigInt, after: BigInt, reason: String): (Level, String) =
      (Level.DEBUG, s"Reducing $label responseBytes target for peer ${p.id.value}: $before -> $after ($reason)")

    s"$name response window" should "start a new peer at the initial target, which the first failure halves and logs at DEBUG" taggedAs UnitTest in {
      val cap = new LevelCapture
      try
        val w = newWindow()
        val p = peer(s"$name-initial")
        w.stored(p) shouldBe None
        w.failure(p, "timeout")
        w.stored(p) shouldBe Some(initial / BigInt(2))
        cap.reductions(p) shouldBe List(reduction(p, initial, initial / BigInt(2), "timeout"))
      finally cap.close()
    }

    it should "keep halving on each failure down to the floor, and stay there" taggedAs UnitTest in {
      val cap = new LevelCapture
      try
        val w = newWindow()
        val p = peer(s"$name-shrink")
        failures.foreach { case (_, after) =>
          w.failure(p, "timeout")
          w.stored(p) shouldBe Some(after)
        }
        failures.last._2 shouldBe min
        cap.reductions(p) shouldBe failures.map { case (before, after) =>
          reduction(p, before, after, "timeout")
        }.toList
      finally cap.close()
    }

    it should "grow by 1.25x on a response of at least 90% of the request, and not one byte below" taggedAs UnitTest in {
      val w = newWindow()
      val p = peer(s"$name-boundary")
      threshold shouldBe ((initial * 9 + 9) / 10)
      w.success(p, requested = initial, received = threshold - 1)
      w.stored(p) shouldBe None
      w.success(p, requested = initial, received = threshold)
      w.stored(p) shouldBe Some(initial * 5 / 4)
    }

    it should "grow from the request size rather than the stored target, and ignore an empty request" taggedAs UnitTest in {
      val w = newWindow()
      val p = peer(s"$name-from-request")
      w.seed(p, BigInt(1))
      w.success(p, requested = BigInt(0), received = BigInt(0))
      w.stored(p) shouldBe Some(BigInt(1))
      w.success(p, requested = initial, received = initial)
      w.stored(p) shouldBe Some(initial * 5 / 4)
    }

    it should "grow step by step to the 2 MiB ceiling, clamping the last step" taggedAs UnitTest in {
      val w = newWindow()
      val p = peer(s"$name-growth")
      var current = initial
      growth.foreach { expected =>
        w.success(p, requested = current, received = current)
        w.stored(p) shouldBe Some(expected)
        current = expected
      }
      growth.last shouldBe MaxBytes
    }

    it should "not grow once the request is at or over the ceiling" taggedAs UnitTest in {
      val w = newWindow()
      val p = peer(s"$name-ceiling")
      w.seed(p, BigInt(777))
      w.success(p, requested = MaxBytes, received = MaxBytes)
      w.success(p, requested = MaxBytes * 5 / 2, received = MaxBytes * 3)
      w.stored(p) shouldBe Some(BigInt(777))
    }

    it should "read a stored target outside [min, 2 MiB] clamped, before halving it" taggedAs UnitTest in {
      val cap = new LevelCapture
      try
        val w = newWindow()
        val low = peer(s"$name-clamp-low")
        w.seed(low, BigInt(1))
        w.failure(low, "empty response")
        w.stored(low) shouldBe Some(min)
        cap.reductions(low) shouldBe List(reduction(low, min, min, "empty response"))

        val high = peer(s"$name-clamp-high")
        w.seed(high, MaxBytes * 5)
        w.failure(high, "verification failed: bad proof")
        w.stored(high) shouldBe Some(MaxBytes / BigInt(2))
        cap.reductions(high) shouldBe List(
          reduction(high, MaxBytes, MaxBytes / BigInt(2), "verification failed: bad proof")
        )
      finally cap.close()
    }

    it should "grow with no floor: a small request that is filled is multiplied, not raised to the minimum" taggedAs UnitTest in {
      val w = newWindow()
      val p = peer(s"$name-no-floor")
      w.success(p, requested = BigInt(1000), received = BigInt(1000))
      w.stored(p) shouldBe Some(BigInt(1250))
    }

    it should "keep each peer's target separate" taggedAs UnitTest in {
      val w = newWindow()
      val a = peer(s"$name-peer-a")
      val b = peer(s"$name-peer-b")
      w.failure(a, "timeout")
      w.stored(a) shouldBe Some(initial / BigInt(2))
      w.stored(b) shouldBe None
    }

  registerModuleCases(
    name = "ARC",
    label = "account",
    initial = BigInt(524288),
    min = BigInt(102400),
    threshold = BigInt(471860),
    failures = Seq(
      (BigInt(524288), BigInt(262144)),
      (BigInt(262144), BigInt(131072)),
      (BigInt(131072), BigInt(102400)), // 65536 raised to the floor
      (BigInt(102400), BigInt(102400)) // 51200 raised to the floor
    ),
    growth = Seq(655360, 819200, 1024000, 1280000, 1600000, 2000000, 2097152).map(BigInt(_)), // 2500000 clamped
    newWindow = () => arcWindow()
  )

  registerModuleCases(
    name = "SRC",
    label = "storage",
    initial = BigInt(1048576),
    min = BigInt(131072),
    threshold = BigInt(943719),
    failures = Seq(
      (BigInt(1048576), BigInt(524288)),
      (BigInt(524288), BigInt(262144)),
      (BigInt(262144), BigInt(131072)),
      (BigInt(131072), BigInt(131072)) // 65536 raised to the floor
    ),
    growth = Seq(1310720, 1638400, 2048000, 2097152).map(BigInt(_)), // 2560000 clamped
    newWindow = () => srcWindow()
  )

  // ---- TNHC: driven through the actor --------------------------------------------------------------------------

  /** A healing coordinator with one peer; each queued node becomes a one-node request. */
  final private class Healing(peerName: String):
    val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    val network: TestProbe[NetworkPeerManagerActor.Command] =
      testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    private val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    private val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
    val healer: Peer = PeerTestHelpers.createTestPeer(peerName, peerProbe.ref.toClassic)
    private val stateRoot = kec256(ByteString(s"response-window-golden-$peerName"))
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = network.ref,
      requestTracker = tracker,
      mptStorage = new FlushFailsStorage(),
      batchSize = 1,
      snapSyncController = controller.ref
    )
    // Not needed for dispatch (the root is passed to the constructor); kept as the entry the other healing specs use.
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(stateRoot)
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(healer)

    /** Queue the given contents as missing nodes, each at its own path; the hash is the content's keccak. */
    def queue(startIndex: Int, contents: Seq[ByteString]): Unit =
      coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(contents.zipWithIndex.map { case (c, i) =>
        (Seq(ByteString(Array[Byte]((startIndex + i).toByte))), kec256(c))
      })

    /** The next GetTrieNodes the coordinator sent. */
    def nextRequest(): SNAP.GetTrieNodes =
      network.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](10.seconds).message match
        case enc: SNAP.GetTrieNodes.GetTrieNodesEnc => enc.underlyingMsg
        case other                                  => throw new AssertionError(s"expected a GetTrieNodes, got $other")

    def respond(requestId: BigInt, nodes: Seq[ByteString]): Unit =
      coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(SNAP.TrieNodes(requestId, nodes))

  private def blob(size: Int): ByteString = ByteString(Array.fill[Byte](size)((size % 127).toByte))

  "TNHC response window" should "start at 512 KiB, ignore a response just under 90% fill, and grow by 1.25x from exactly 90% up to the 2 MiB ceiling" taggedAs UnitTest in {
    val cap = new LevelCapture
    try
      val h = new Healing("tnhc-growth")
      val id = h.healer.id.value
      // (bytes served, bytes requested, target logged after the response). 524288 * 9 / 10 = 471859.2.
      val steps = Seq(
        (471859, 524288, 524288), // one byte under 90%: no growth
        (471860, 524288, 655360), // 90%: grows
        (655360, 655360, 819200),
        (819200, 819200, 1024000),
        (1024000, 1024000, 1280000),
        (1280000, 1280000, 1600000),
        (1600000, 1600000, 2000000),
        (2000000, 2000000, 2097152), // 2500000 clamped to the ceiling
        (2097152, 2097152, 2097152) // at the ceiling: unchanged
      )
      steps.zipWithIndex.foreach { case ((served, requested, after), i) =>
        val node = blob(served)
        h.queue(i, Seq(node))
        val request = h.nextRequest()
        request.responseBytes shouldBe BigInt(requested)
        h.respond(request.requestId, Seq(node))
        val (_, healed) = cap.await(s"Healed 1/1 trie nodes from peer $id")
        healed should endWith(s"responseBytes=$after)")
      }
      h.queue(steps.size, Seq(blob(1000)))
      h.nextRequest().responseBytes shouldBe BigInt(2097152)
    finally cap.close()
  }

  it should "halve on each timeout down to the 50 KiB floor, then on an empty response, logging each at DEBUG" taggedAs UnitTest in {
    val cap = new LevelCapture
    try
      val h = new Healing("tnhc-shrink")
      val id = h.healer.id.value
      h.queue(0, Seq.tabulate(5)(i => ByteString(s"never-served-$i")))
      val requests = (1 to 5).map(_ => h.nextRequest())
      requests.foreach(_.responseBytes shouldBe BigInt(524288))

      def line(before: Int, after: Int, reason: String): (Level, String) =
        (Level.DEBUG, s"Reducing healing responseBytes target for peer $id: $before -> $after ($reason)")

      h.coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(requests(0).requestId)
      cap.await("Reducing healing responseBytes") shouldBe line(524288, 262144, "request timeout")
      h.coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(requests(1).requestId)
      cap.await("Reducing healing responseBytes") shouldBe line(262144, 131072, "request timeout")
      h.coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(requests(2).requestId)
      cap.await("Reducing healing responseBytes") shouldBe line(131072, 65536, "request timeout")
      // 32768 raised to the 50 KiB floor
      h.coordinator ! TrieNodeHealingCoordinator.HealingRequestTimeout(requests(3).requestId)
      cap.await("Reducing healing responseBytes") shouldBe line(65536, 51200, "request timeout")
      // The fifth request is answered empty: the other failure site, with its own reason.
      h.respond(requests(4).requestId, Seq.empty)
      cap.await("Reducing healing responseBytes") shouldBe line(51200, 51200, "empty healing response")
    finally cap.close()
  }

  it should "count a response whose nodes match no request as empty: halve, with the empty-response reason" taggedAs UnitTest in {
    val cap = new LevelCapture
    try
      val h = new Healing("tnhc-mismatch")
      val id = h.healer.id.value
      h.queue(0, Seq(ByteString("requested-node")))
      val request = h.nextRequest()
      request.responseBytes shouldBe BigInt(524288)
      h.respond(request.requestId, Seq(ByteString("a-node-nobody-asked-for")))
      cap.await("Reducing healing responseBytes") shouldBe
        ((
          Level.DEBUG,
          s"Reducing healing responseBytes target for peer $id: 524288 -> 262144 (empty healing response)"
        ))
    finally cap.close()
  }
