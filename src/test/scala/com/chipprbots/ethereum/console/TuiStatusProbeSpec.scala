package com.chipprbots.ethereum.console

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.network.NetworkMetrics
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerScoreSnapshot
import com.chipprbots.ethereum.network.ScoringStatistics
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager

/** [[TuiStatusProbe]] answers from the same actor queries as `net_peerCount` and `eth_syncing`. */
class TuiStatusProbeSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private def peer(id: String) =
    Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), createTestProbe[PeerActor.Command]().ref, false)

  private def peerManager(statuses: PeerActor.Status*): Behavior[PeerManagerActor.Command] =
    Behaviors.receiveMessage {
      case PeerManagerActor.GetPeersCmd(replyTo) =>
        replyTo ! PeerManagerActor.Peers(statuses.zipWithIndex.map((s, i) => peer(s"p$i") -> s).toMap)
        Behaviors.same
      case _ => Behaviors.same
    }

  private def syncController(status: SyncProtocol.Status): Behavior[SyncController.Command] =
    Behaviors.receiveMessage {
      case SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo)) =>
        replyTo ! status
        Behaviors.same
      case _ => Behaviors.same
    }

  private val silent: Behavior[Any] = Behaviors.ignore

  private val snapSample = SyncProgress(
    phase = SyncPhase.AccountRangeSync,
    accountsSynced = 1000,
    bytecodesDownloaded = 0,
    storageSlotsSynced = 0,
    nodesHealed = 0,
    elapsedSeconds = 60,
    phaseElapsedSeconds = 60,
    accountsPerSec = 16,
    bytecodesPerSec = 0,
    slotsPerSec = 0,
    nodesPerSec = 0,
    recentAccountsPerSec = 20,
    recentBytecodesPerSec = 0,
    recentSlotsPerSec = 0,
    recentNodesPerSec = 0,
    phaseProgress = 10,
    estimatedTotalAccounts = 10000,
    estimatedTotalBytecodes = 0,
    estimatedTotalSlots = 0,
    startTime = 0,
    phaseStartTime = 0
  )

  private val now = java.time.Instant.parse("2026-11-07T12:00:00Z")
  private val clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)

  /** A probe at `now`, whose SNAP sample, if any, was recorded `snapAgeSeconds` before it. */
  private def probe(
      pm: Behavior[PeerManagerActor.Command],
      sc: Behavior[SyncController.Command],
      best: BigInt = 42,
      snap: Option[SyncProgress] = None,
      snapAgeSeconds: Long = 5
  ) =
    val sampledAt = now.minusSeconds(snapAgeSeconds).toEpochMilli
    new TuiStatusProbe(spawn(pm), spawn(sc), () => best, 50, () => snap.map(_ -> sampledAt), 300.millis, clock)(using
      system.scheduler
    )

  "TuiStatusProbe" should "count only handshaked peers" taggedAs (UnitTest) in {
    import PeerActor.Status.*
    val s = probe(peerManager(Handshaked, Handshaked, Connecting), syncController(SyncProtocol.Status.SyncDone))()
    s.peerCount shouldBe 2
    s.maxPeers shouldBe 50
    s.connectionStatus shouldBe "Connected"
  }

  it should "report searching when no peer has handshaked" taggedAs (UnitTest) in {
    val s = probe(peerManager(), syncController(SyncProtocol.Status.SyncDone))()
    s.peerCount shouldBe 0
    s.connectionStatus shouldBe "Searching for peers"
  }

  it should "report block progress while syncing" taggedAs (UnitTest) in {
    val status = SyncProtocol.Status.Syncing(0, Progress(1000, 5000), Some(Progress(30, 90)))
    val s = probe(peerManager(), syncController(status))()
    s.currentBlock shouldBe 1000
    s.bestBlock shouldBe 5000
    s.syncStatus shouldBe "Syncing (state 30 / 90)"
  }

  it should "report the local best block as synced when sync is done" taggedAs (UnitTest) in {
    val s = probe(peerManager(), syncController(SyncProtocol.Status.SyncDone), best = 777)()
    (s.currentBlock, s.bestBlock, s.syncStatus) shouldBe ((777L, 777L, "Synced"))
  }

  it should "leave the network head unknown when not syncing" taggedAs (UnitTest) in {
    val s = probe(peerManager(), syncController(SyncProtocol.Status.NotSyncing), best = 9)()
    (s.currentBlock, s.bestBlock, s.syncStatus) shouldBe ((9L, 0L, "Not syncing"))
  }

  it should "degrade to 'unresponsive' instead of hanging when actors do not answer" taggedAs (UnitTest) in {
    val s = new TuiStatusProbe(spawn(silent), spawn(silent), () => 5, 50, () => None, 100.millis)(using
      system.scheduler
    )()
    s.connectionStatus shouldBe "Unresponsive (peer manager)"
    s.syncStatus shouldBe "Unresponsive (sync controller)"
    s.currentBlock shouldBe 5
    // Spec 027 FR-011: each source carries its own freshness.
    s.peers shouldBe Sourced.Unresponsive("no reply within 100ms")
    s.sync shouldBe Sourced.Unresponsive("no reply within 100ms")
  }

  it should "stamp each snapshot with its clock, and each answer Fresh at that time" taggedAs (UnitTest) in {
    val s = new TuiStatusProbe(
      spawn(peerManager(PeerActor.Status.Handshaked)),
      spawn(syncController(SyncProtocol.Status.SyncDone)),
      () => 9,
      50,
      () => None,
      300.millis,
      clock = clock
    )(using system.scheduler)()
    s.now shouldBe now
    s.peers should matchPattern { case Sourced.Fresh(Seq(_), `now`) => }
    s.sync shouldBe Sourced.Fresh(SyncSample(9, 9, "Synced"), now)
  }

  // Spec 027 T050: the sample's own time, for the SNAP sample-age alert (research §6).
  it should "carry the time the SNAP sample was recorded" taggedAs (UnitTest) in {
    val syncing = SyncProtocol.Status.Syncing(0, Progress(0, 100), None)
    val s = probe(peerManager(), syncController(syncing), snap = Some(snapSample), snapAgeSeconds = 12)()
    s.sync.value.flatMap(_.snapSampledAt) shouldBe Some(now.minusSeconds(12))
  }

  it should "hide a SNAP sample older than 90 s" taggedAs (UnitTest) in {
    val syncing = SyncProtocol.Status.Syncing(0, Progress(0, 100), None)
    probe(peerManager(), syncController(syncing), snap = Some(snapSample), snapAgeSeconds = 91)().snapSync shouldBe None
    probe(peerManager(), syncController(syncing), snap = Some(snapSample), snapAgeSeconds = 90)().snapSync should not be
      empty
  }

  // prism, slice 2 review (B-044): "Syncing (SNAP)" only on a sample from the SNAP monitor's last two 30 s rounds; an
  // older one still shows its rows until it is 90 s old, but no longer says SNAP is running.
  it should "say SNAP is running only on a sample younger than 60 s" taggedAs (UnitTest) in {
    val status = SyncProtocol.Status.Syncing(0, Progress(5, 0), None)
    def described(ageSeconds: Long) =
      probe(peerManager(), syncController(status), best = 5, snap = Some(snapSample), snapAgeSeconds = ageSeconds)()
    described(60).syncStatus shouldBe "Syncing (SNAP)"
    described(61).syncStatus shouldBe "Syncing (no target yet)"
    described(61).snapSync should not be empty
  }

  it should "show SNAP progress only while syncing" taggedAs (UnitTest) in {
    val syncing = SyncProtocol.Status.Syncing(0, Progress(0, 100), None)
    val shown = probe(peerManager(), syncController(syncing), snap = Some(snapSample))()
    shown.snapSync.map(_.phase) shouldBe Some("AccountRangeSync")
    shown.snapSync.map(_.accountsSynced) shouldBe Some(1000)

    val done = probe(peerManager(), syncController(SyncProtocol.Status.SyncDone), snap = Some(snapSample))()
    done.snapSync shouldBe None
  }

  it should "hide a finished SNAP run" taggedAs (UnitTest) in {
    val syncing = SyncProtocol.Status.Syncing(0, Progress(0, 100), None)
    val s = probe(peerManager(), syncController(syncing), snap = Some(snapSample.copy(phase = SyncPhase.Completed)))()
    s.snapSync shouldBe None
  }

  // Spec 027 FR-007: the five SNAP worm rows need the fields toSnapSyncState used to drop.
  it should "carry the eight SyncProgress fields the SNAP worm rows need" taggedAs (UnitTest) in {
    val sample = snapSample.copy(
      phase = SyncPhase.ByteCodeAndStorageSync,
      phaseElapsedSeconds = 4080,
      storageContractsCompleted = 2114,
      storageContractsTotal = 5702,
      chainHeaders = BigInt(4_210_000),
      chainBodies = BigInt(3_900_000),
      chainReceipts = BigInt(3_800_000),
      chainTarget = BigInt(9_118_000),
      bytecodeComplete = true
    )
    val s = TuiStatusProbe.toSnapSyncState(sample)
    s.storageContractsCompleted shouldBe 2114
    s.storageContractsTotal shouldBe 5702
    s.chainHeaders shouldBe BigInt(4_210_000)
    s.chainBodies shouldBe BigInt(3_900_000)
    s.chainReceipts shouldBe BigInt(3_800_000)
    s.chainTarget shouldBe BigInt(9_118_000)
    s.bytecodeComplete shouldBe true
    s.phaseElapsedSeconds shouldBe 4080.0
    // ... and still the fields it carried before.
    (s.phase, s.accountsSynced, s.phaseProgress, s.elapsedSeconds) shouldBe ((
      "ByteCodeAndStorageSync",
      1000L,
      10,
      60.0
    ))
  }

  // Slice 1 review W1: the TUI's own Overall estimate fell to 0 % in ChainDownloadCompletion and disagreed with the log
  // (45 % against 27 % on the storage fixture). The Overall worm now carries the log's own fraction.
  it should "draw the Overall worm the log draws, in every SNAP phase" taggedAs (UnitTest) in {
    val renderer = TuiRenderer(TuiConfig.default)
    for phase <- SyncPhase.values.toSeq; phaseProgress <- Seq(0, 37, 100, 150) do
      val p = snapSample.copy(phase = phase, phaseProgress = phaseProgress)
      val s = TuiStatusProbe.toSnapSyncState(p)
      withClue(s"$phase at $phaseProgress%: ") {
        s.overall shouldBe p.globalProgress
        // Without motion: the log's bar has no ripple.
        val state = TuiState.initial.withSnapSyncState(s).copy(motion = Motion.Reduced)
        val frame = renderer.render(state, 80, 24).map(_.toString)
        val overallRow = frame.find(_.startsWith("   Overall")).get
        overallRow.drop(12).trim shouldBe WormToBrainBar.renderKnown(p.globalProgress)
      }
    // Chain download is 4 of 5 stages done, not 0 %.
    val chainDownload = snapSample.copy(phase = SyncPhase.ChainDownloadCompletion, phaseProgress = 0)
    TuiStatusProbe.toSnapSyncState(chainDownload).overall shouldBe 0.8 +- 1e-9
  }

  // B-044 (live check 2026-11-07): while SNAP runs the sync controller answers GetStatus with a 0 block target
  // (SyncController.scala:874-881, runningSnapSync), and the TUI read that as "no target yet" for the whole run.
  it should "not say 'no target yet' while a SNAP sample shows SNAP running" taggedAs (UnitTest) in {
    val status = SyncProtocol.Status.Syncing(0, Progress(5, 0), None)
    val s = probe(peerManager(), syncController(status), best = 5, snap = Some(snapSample))()
    s.syncStatus shouldBe "Syncing (SNAP)"
    s.snapSync.map(_.phase) shouldBe Some("AccountRangeSync")
  }

  it should "say so when syncing has no target yet (no peers to pick a pivot from)" taggedAs (UnitTest) in {
    val status = SyncProtocol.Status.Syncing(0, Progress(0, 0), None)
    val s = probe(peerManager(), syncController(status), best = 0)()
    (s.bestBlock, s.syncStatus) shouldBe ((0L, "Syncing (no target yet)"))
  }

  // ---- Spec 027 T065: what the probe reads on the node itself, and the peers' own fields -------------------------

  private def localProbe(local: TuiStatusProbe.LocalSources, probeClock: java.time.Clock = clock) =
    new TuiStatusProbe(
      spawn(peerManager()),
      spawn(syncController(SyncProtocol.Status.SyncDone)),
      () => 9,
      50,
      () => None,
      300.millis,
      probeClock,
      local
    )(using system.scheduler)

  it should "read the disk free on the datadir through the injected File => Long" taggedAs (UnitTest) in {
    val dataDir = new java.io.File("/var/lib/fukuii/etc")
    val p = localProbe(
      TuiStatusProbe.LocalSources(dataDir = Some(dataDir), usableSpace = f => if f == dataDir then 42L else -1L)
    )
    p().local.diskFree shouldBe Some(42L)
    localProbe(TuiStatusProbe.LocalSources())().local.diskFree shouldBe None // no datadir, no reading
  }

  it should "read the JVM heap, and the GC share of the time between two polls" taggedAs (UnitTest) in {
    val testClock = new TuiFixtures.TestClock(now)
    val gc = Iterator(100L, 150L, 150L)
    val p = localProbe(
      TuiStatusProbe.LocalSources(heap = () => Some((2L << 30, 8L << 30)), gcMillis = () => Some(gc.next())),
      testClock
    )
    val first = p().local.jvm.get
    (first.heapUsed, first.heapMax, first.gcShare) shouldBe ((2L << 30, 8L << 30, None)) // no interval yet
    testClock.advanceMillis(1_000L)
    p().local.jvm.flatMap(_.gcShare).get shouldBe 0.05 +- 1e-9 // 50 ms of GC in 1,000 ms
    testClock.advanceMillis(1_000L)
    p().local.jvm.flatMap(_.gcShare).get shouldBe 0.0 +- 1e-9
  }

  it should "read the discovery counts from NetworkMetrics" taggedAs (UnitTest) in {
    import com.chipprbots.ethereum.network.NetworkMetrics
    val saved = (
      NetworkMetrics.DiscoveredPeersSize.get,
      NetworkMetrics.PendingPeersSize.get,
      NetworkMetrics.BlacklistedPeersSize.get
    )
    try
      NetworkMetrics.DiscoveredPeersSize.set(1_912L)
      NetworkMetrics.PendingPeersSize.set(14L)
      NetworkMetrics.BlacklistedPeersSize.set(3L)
      TuiStatusProbe.LocalSources.networkDiscovery() shouldBe Some(DiscoverySample(1_912L, 14L, 3L))
      localProbe(
        TuiStatusProbe.LocalSources(discovery = TuiStatusProbe.LocalSources.networkDiscovery)
      )().local.discovery shouldBe
        Some(DiscoverySample(1_912L, 14L, 3L))
    finally
      // The gauges are process-wide; the next spec finds them as they were.
      NetworkMetrics.DiscoveredPeersSize.set(saved._1)
      NetworkMetrics.PendingPeersSize.set(saved._2)
      NetworkMetrics.BlacklistedPeersSize.set(saved._3)
  }

  it should "read the best header's timestamp" taggedAs (UnitTest) in {
    localProbe(TuiStatusProbe.LocalSources(headTimestamp = () => Some(1_791_294_816L)))().local.headTimestamp shouldBe
      Some(1_791_294_816L)
  }

  it should "read the SNAP request counters from SNAPSyncMetrics" taggedAs (UnitTest) in {
    val counters = TuiStatusProbe.LocalSources.snapMetrics().get
    val p = localProbe(TuiStatusProbe.LocalSources(snapCounters = TuiStatusProbe.LocalSources.snapMetrics))
    p().local.snapCounters.map(_.accountRequests) should contain(counters.accountRequests)
  }

  it should "leave a local reading empty when it fails, and still answer" taggedAs (UnitTest) in {
    val failing = TuiStatusProbe.LocalSources(
      dataDir = Some(new java.io.File("/x")),
      usableSpace = _ => throw new SecurityException("denied"),
      heap = () => throw new IllegalStateException("no MXBean"),
      headTimestamp = () => throw new RuntimeException("storage closed")
    )
    val s = localProbe(failing)()
    s.local shouldBe LocalSample()
    s.sync shouldBe a[Sourced.Fresh[?]]
  }

  it should "carry each handshaked peer's id, address, direction and connection time from GetPeersCmd" taggedAs (
    UnitTest
  ) in {
    val connectedAt = now.minusSeconds(4 * 3_600L)
    val inbound =
      Peer(
        PeerId("3fa1e2"),
        new InetSocketAddress("203.0.113.7", 30303),
        createTestProbe[PeerActor.Command]().ref,
        incomingConnection = true,
        createTimeMillis = connectedAt.toEpochMilli
      )
    val pm: Behavior[PeerManagerActor.Command] = Behaviors.receiveMessage {
      case PeerManagerActor.GetPeersCmd(replyTo) =>
        replyTo ! PeerManagerActor.Peers(
          Map(inbound -> PeerActor.Status.Handshaked, peer("x") -> PeerActor.Status.Connecting)
        )
        Behaviors.same
      case _ => Behaviors.same
    }
    val s = probe(pm, syncController(SyncProtocol.Status.SyncDone))()
    s.peers.value shouldBe Some(Seq(TuiPeer("3fa1e2", "203.0.113.7:30303", incoming = true, connectedAt)))
    s.peerCount shouldBe 1
  }

  // Spec 027 FR-016: the SNAP sample-age alert needs the sample's time even when the sample is too old to show.
  it should "carry the SNAP sample's time past 90 s, while no longer showing the sample" taggedAs (UnitTest) in {
    val syncing = SyncProtocol.Status.Syncing(0, Progress(0, 0), None)
    val s = probe(peerManager(), syncController(syncing), snap = Some(snapSample), snapAgeSeconds = 91)()
    s.snapSync shouldBe None
    s.sync.value.flatMap(_.snapSampledAt) shouldBe Some(now.minusSeconds(91))
  }

  // ---- prism, slice 3 review -------------------------------------------------------------------------------------

  it should "read the real JVM's heap and GC time" taggedAs (UnitTest) in {
    val (used, max) = TuiStatusProbe.LocalSources.jvmHeap().get
    used should be >= 0L
    max should be >= 0L
    TuiStatusProbe.LocalSources.jvmGcMillis().get should be >= 0L
  }

  it should "count only the collectors that pause: not G1's concurrent cycles" taggedAs (UnitTest) in {
    val beans = Seq(
      "G1 Young Generation" -> 120L,
      "G1 Old Generation" -> 30L,
      "G1 Concurrent GC" -> 9_000L,
      "ZGC Major Cycles" -> 5_000L,
      "ZGC Major Pauses" -> 4L,
      "Unknown" -> -1L
    )
    TuiStatusProbe.LocalSources.pausingGcMillis(beans) shouldBe 154L
  }

  it should "read SNAP's pivot block" taggedAs (UnitTest) in {
    import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
    val saved = SNAPSyncMetrics.pivotBlockNumber
    try
      SNAPSyncMetrics.setPivotBlockNumber(BigInt(9_118_000))
      TuiStatusProbe.LocalSources.snapPivotBlock() shouldBe Some(9_118_000L)
      localProbe(TuiStatusProbe.LocalSources(snapPivot = () => Some(42L)))().local.snapPivot shouldBe Some(42L)
    finally SNAPSyncMetrics.setPivotBlockNumber(BigInt(saved)) // the gauge is process-wide
  }

  it should "clean a failure's reason before it reaches the screen: one line, no control character, capped" taggedAs (
    UnitTest
  ) in {
    // Slice 4: the one sanitiser (FR-020) removes the escape sequence whole and keeps the first line.
    TuiStatusProbe.reason(new RuntimeException("bad\u001b[31m peer\nreply")) shouldBe "bad peer…"
    val long = TuiStatusProbe.reason(new RuntimeException("x" * 500))
    long.codePointCount(0, long.length) shouldBe TuiStatusProbe.MaxReasonChars
    long should endWith("…")
    TuiStatusProbe.reason(new IllegalStateException()) shouldBe "IllegalStateException"
    // The cap counts code points: an emoji (a surrogate pair) across the cut is kept whole or not at all, never split.
    val emoji = TuiStatusProbe.reason(new RuntimeException("x" * (TuiStatusProbe.MaxReasonChars - 4) + "🪱" * 10))
    emoji shouldBe "x" * (TuiStatusProbe.MaxReasonChars - 4) + "🪱" * 3 + "…"
  }

  it should "take the local readings even when both asks miss their wait" taggedAs (UnitTest) in {
    val s = new TuiStatusProbe(
      spawn(silent),
      spawn(silent),
      () => 5,
      50,
      () => None,
      100.millis,
      clock,
      TuiStatusProbe.LocalSources(heap = () => Some((1L, 2L)))
    )(using system.scheduler)()
    s.peers should matchPattern { case Sourced.Unresponsive(_) => }
    s.local.jvm.map(_.heapUsed) shouldBe Some(1L)
  }

  // prism, slice 3 review: one test of "SNAP is running", from the sample alone, not from the controller's target.
  it should "say SNAP is running while its latest sample is younger than 5 minutes, whatever the target" taggedAs (
    UnitTest
  ) in {
    val snapStatus = SyncProtocol.Status.Syncing(0, Progress(5, 0), None)
    val regularStatus = SyncProtocol.Status.Syncing(0, Progress(2_000, 9_000), None)
    def running(status: SyncProtocol.Status, ageSeconds: Long) =
      probe(peerManager(), syncController(status), snap = Some(snapSample), snapAgeSeconds = ageSeconds)().sync.value
        .map(_.snapRunning)
    running(snapStatus, 91) shouldBe Some(true)
    running(snapStatus, 300) shouldBe Some(true)
    running(snapStatus, 301) shouldBe Some(false)
    // Regular sync after an in-process SNAP abort: the run's last sample ages out of "running" the same way.
    running(regularStatus, 301) shouldBe Some(false)
    // A finished run's Completed sample is not a running phase.
    probe(
      peerManager(),
      syncController(snapStatus),
      snap = Some(snapSample.copy(phase = SyncPhase.Completed))
    )().sync.value.map(_.snapRunning) shouldBe Some(false)
  }

  // ---- Spec 027 slice 4 (T089, T097): the peers' details, the txpool, the scores, the last import -------------------

  private def remote(clientId: String) = RemoteStatus(
    Capability.ETH68,
    1L,
    ChainWeight.totalDifficultyOnly(1),
    ByteString("best"),
    ByteString("genesis"),
    remoteClientId = clientId
  )

  /** The network peer manager, answering `GetHandshakedPeersCmd` with `p0`… and their client ids and best blocks. */
  private def networkPeerManager(peers: (String, BigInt)*): Behavior[NetworkPeerManagerActor.Command] =
    Behaviors.receiveMessage {
      case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
        replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers.zipWithIndex.map { case ((client, best), i) =>
          peer(s"p$i") -> PeerInfo(remote(client), ChainWeight.totalDifficultyOnly(1), true, best, ByteString("best"))
        }.toMap)
        Behaviors.same
      case _ => Behaviors.same
    }

  /** A fixed key: the transactions are the same on every run. */
  private val keyPair = keyPairFromPrvKey(BigInt(0x1234567890abcdefL))

  private def pendingTx(nonce: Int) =
    val tx = LegacyTransaction(nonce, GasPrice(1), GasAmount(21_000), Address("0x1234"), 0, ByteString())
    PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, None), Address(keyPair)),
      0L
    )

  /** How often a [[txPool]] was asked for its size, and for all of its transactions. */
  final private class PoolAsks:
    val size = new java.util.concurrent.atomic.AtomicInteger
    val whole = new java.util.concurrent.atomic.AtomicInteger

  /** The txpool: `txs` transactions, `blobs` of them carrying a sidecar, and room for 4,096. It answers
    * `GetPoolSizeReq` with the counts and `GetPendingTransactionsReq` with the transactions, and counts each asking.
    */
  private def txPool(
      txs: Int,
      blobs: Int,
      asks: PoolAsks = new PoolAsks,
      answersWhole: () => Boolean = () => true
  ): Behavior[PendingTransactionsManager.Command] =
    // Signed here, before the probe's wait starts: a first signing on a cold JVM can outlast the 300 ms wait.
    val response = PendingTransactionsManager.PendingTransactionsResponse(
      (0 until txs).map(pendingTx),
      (0 until blobs).map(i => ByteString(s"h$i") -> ByteString(s"sidecar$i")).toMap
    )
    Behaviors.receiveMessage {
      case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
        asks.whole.incrementAndGet()
        if answersWhole() then replyTo ! response
        Behaviors.same
      case PendingTransactionsManager.GetPoolSizeReq(replyTo) =>
        asks.size.incrementAndGet()
        replyTo ! PendingTransactionsManager.PoolSize(txs, blobs, 4096)
        Behaviors.same
      case _ => Behaviors.same
    }

  private def detailedProbe(
      details: Option[Behavior[NetworkPeerManagerActor.Command]] = None,
      pool: Option[Behavior[PendingTransactionsManager.Command]] = None,
      local: TuiStatusProbe.LocalSources = TuiStatusProbe.LocalSources(),
      poolWanted: () => Boolean = () => false,
      probeClock: java.time.Clock = clock
  ) =
    new TuiStatusProbe(
      spawn(peerManager(PeerActor.Status.Handshaked)),
      spawn(syncController(SyncProtocol.Status.SyncDone)),
      () => 42,
      50,
      () => None,
      300.millis,
      probeClock,
      local,
      networkPeerManager = details.map(spawn(_)),
      txPool = pool.map(spawn(_)),
      poolWanted = poolWanted
    )(using system.scheduler)

  it should "carry the txpool's size, its blob count and its capacity from the pool's own count (T089, B-196)" taggedAs (
    UnitTest
  ) in {
    detailedProbe(pool = Some(txPool(txs = 3, blobs = 1)))().txpool shouldBe
      Some(Sourced.Fresh(TxpoolSample(size = 3, blobs = 1, capacity = Some(4096)), now))
  }

  // Spec 029 FR-037: watching the pool must not burden it.
  it should "never ask for the pool's transactions while the Mempool view is not on screen" taggedAs (UnitTest) in {
    val asks = new PoolAsks
    val p = detailedProbe(pool = Some(txPool(txs = 3, blobs = 1, asks)))
    val snapshots = Seq(p(), p(), p())
    asks.whole.get shouldBe 0
    asks.size.get shouldBe 3
    all(snapshots.map(_.pool)) shouldBe None
  }

  it should "read the pool whole, once a poll, while the Mempool view is on screen, and stop when it is not" taggedAs (
    UnitTest
  ) in {
    val asks = new PoolAsks
    val wanted = new java.util.concurrent.atomic.AtomicBoolean(true)
    val time = new TuiFixtures.TestClock(now)
    val p = detailedProbe(
      pool = Some(txPool(txs = 3, blobs = 1, asks)),
      local = TuiStatusProbe.LocalSources(suggestedFee = () => Some(BigInt(1)), poolCapacity = 4096),
      poolWanted = () => wanted.get,
      probeClock = time
    )
    val open = p()
    (asks.whole.get, asks.size.get) shouldBe (1, 0) // one read gives the list and the count
    val pool = open.pool.get
    (pool.size, pool.capacity, pool.suggested) shouldBe (3, 4096, Some(BigInt(1)))
    pool.txs.map(_.nonce) should contain theSameElementsAs Seq(0, 1, 2).map(BigInt(_))
    // Every transaction pays the suggestion of 1 wei: all in the tier around it.
    pool.tiers shouldBe Map(FeeTier.Normal -> 3)
    open.txpool shouldBe Some(Sourced.Fresh(TxpoolSample(3, 1, Some(4096)), now))
    time.advance(1)
    p().pool.map(_.at) shouldBe Some(now.plusSeconds(1))
    asks.whole.get shouldBe 2
    wanted.set(false)
    time.advance(1)
    val closed = p()
    (asks.whole.get, asks.size.get) shouldBe (2, 1)
    closed.pool shouldBe None
    closed.txpool shouldBe Some(Sourced.Fresh(TxpoolSample(3, 1, Some(4096)), now.plusSeconds(2)))
  }

  // prism, slice 4: the probe's period can be set as low as 100 ms, and the whole pool was then copied ten times a
  // second. FR-037 says at most once.
  it should "read the pool whole at most once a second however often it polls, and show the last reading between" taggedAs (
    UnitTest
  ) in {
    val asks = new PoolAsks
    val time = new TuiFixtures.TestClock(now)
    val p = detailedProbe(pool = Some(txPool(txs = 3, blobs = 1, asks)), poolWanted = () => true, probeClock = time)
    p().pool.map(_.at) shouldBe Some(now)
    for _ <- 1 to 9 do
      time.advanceMillis(100)
      val between = p()
      between.pool.map(_.at) shouldBe Some(now) // the last reading, which says when it was read
      between.txpool.flatMap(_.value) shouldBe Some(TxpoolSample(3, 1, Some(4096))) // the count is still asked
    asks.whole.get shouldBe 1
    time.advanceMillis(100)
    p().pool.map(_.at) shouldBe Some(now.plusSeconds(1))
    asks.whole.get shouldBe 2
  }

  it should "leave the pool unread, not empty, when the whole pool does not answer" taggedAs (UnitTest) in {
    val s = detailedProbe(pool = Some(silent.narrow[PendingTransactionsManager.Command]), poolWanted = () => true)()
    s.pool shouldBe None
    s.txpool shouldBe Some(Sourced.Unresponsive("no reply within 300ms"))
  }

  // prism, slice 4: one missed answer emptied the view for a poll; the pool is busiest exactly while a block imports.
  it should "keep the last reading through a poll the pool does not answer, and give it up after five seconds" taggedAs (
    UnitTest
  ) in {
    val answers = new java.util.concurrent.atomic.AtomicBoolean(true)
    val time = new TuiFixtures.TestClock(now)
    val p = detailedProbe(
      pool = Some(txPool(txs = 3, blobs = 1, answersWhole = () => answers.get)),
      poolWanted = () => true,
      probeClock = time
    )
    p().pool.map(_.at) shouldBe Some(now)
    answers.set(false)
    for _ <- 1 to 5 do
      time.advance(1)
      p().pool.map(_.at) shouldBe Some(now) // the reading of then, with its time: the view says how old it is
    time.advance(1)
    p().pool shouldBe None // six seconds old: the observer would not compare against it any more
    answers.set(true)
    time.advance(1)
    val again = p().pool.get
    again.at shouldBe now.plusSeconds(7)
    again.watchedSince shouldBe now.plusSeconds(7) // a new watch: what moved meanwhile was not seen
  }

  // prism, slice 4: a read that threw was taken for an answer of "no".
  it should "not take a failed read for an answer: no fee is no tier, and an unread index is not a drop" taggedAs (
    UnitTest
  ) in {
    val time = new TuiFixtures.TestClock(now)
    val held = new java.util.concurrent.atomic.AtomicBoolean(true)
    val pool: Behavior[PendingTransactionsManager.Command] =
      val full = PendingTransactionsManager.PendingTransactionsResponse((0 until 3).map(pendingTx), Map.empty)
      val less = PendingTransactionsManager.PendingTransactionsResponse(full.pendingTransactions.take(1), Map.empty)
      Behaviors.receiveMessage {
        case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
          replyTo ! (if held.get then full else less)
          Behaviors.same
        case _ => Behaviors.same
      }
    val p = detailedProbe(
      pool = Some(pool),
      local = TuiStatusProbe.LocalSources(
        suggestedFee = () => throw new IllegalStateException("oracle"),
        isMined = _ => throw new java.io.IOException("the store is closed")
      ),
      poolWanted = () => true,
      probeClock = time
    )
    val first = p().pool.get
    (first.suggested, first.tiers) shouldBe (None, Map.empty)
    held.set(false)
    time.advance(1)
    val second = p().pool.get
    second.size shouldBe 1
    // Two left, and the index could not say what became of them.
    (second.leftUnchecked, second.awaiting) shouldBe (2L, 0)
    time.advance(5)
    p().pool.get.droppedPerSecond shouldBe Some(0.0)
  }

  it should "ask only for the count when it cannot tell whether the Mempool view is on screen" taggedAs (UnitTest) in {
    val asks = new PoolAsks
    val s = detailedProbe(
      pool = Some(txPool(txs = 3, blobs = 1, asks)),
      poolWanted = () => throw new IllegalStateException("no state")
    )()
    (asks.whole.get, asks.size.get) shouldBe (0, 1)
    s.pool shouldBe None
  }

  it should "carry the pool's counters, and none on a node that does not count" taggedAs (UnitTest) in {
    val counted =
      com.chipprbots.ethereum.transactions.TxPoolMetrics.Snapshot(Map("AlreadyKnown" -> 2L), 1, 0, 0, 5, 4, 0)
    detailedProbe(local = TuiStatusProbe.LocalSources(poolCounters = () => Some(counted)))().local.poolCounters shouldBe
      Some(counted)
    detailedProbe()().local.poolCounters shouldBe None
  }

  "A pending transaction's fee" should "be its gas price on a chain with no base fee, and what it offers above one" taggedAs (
    UnitTest
  ) in {
    val pending = pendingTx(7)
    val plain = TuiStatusProbe.poolTx(pending, None)
    (plain.nonce, plain.tip, plain.feeCap, plain.isBlob, plain.local) shouldBe (
      BigInt(7),
      BigInt(1),
      BigInt(1),
      false,
      false
    )
    plain.hash shouldBe "0x" + pending.stx.tx.hash.value.toArray.map("%02x".format(_)).mkString
    plain.sender shouldBe "0x" + Address(keyPair).bytes.toArray.map("%02x".format(_)).mkString
    // A legacy transaction pays its gas price whatever the base fee: what is left above it is its tip, never negative.
    val rich = LegacyTransaction(1, GasPrice(100), GasAmount(21_000), Address("0x1234"), 0, ByteString())
    val richPending = PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(SignedTransaction.sign(rich, keyPair, None), Address(keyPair)),
      0L
    )
    TuiStatusProbe.poolTx(richPending, Some(BigInt(30))).tip shouldBe BigInt(70)
    TuiStatusProbe.poolTx(pending, Some(BigInt(30))).tip shouldBe BigInt(0)
  }

  it should "say the txpool is Unresponsive when it does not answer, and leave it unasked when not wired" taggedAs (
    UnitTest
  ) in {
    detailedProbe(pool = Some(silent.narrow[PendingTransactionsManager.Command]))().txpool shouldBe Some(
      Sourced.Unresponsive("no reply within 300ms")
    )
    detailedProbe()().txpool shouldBe None
  }

  it should "carry each handshaked peer's client id and best block from GetHandshakedPeersCmd, by peer id" taggedAs (
    UnitTest
  ) in {
    val s = detailedProbe(details = Some(networkPeerManager("CoreGeth/v1.12.20" -> BigInt(21_884_102))))()
    s.details shouldBe Some(Sourced.Fresh(Map("p0" -> PeerDetails("CoreGeth/v1.12.20", 21_884_102L)), now))
  }

  it should "sanitise a peer's client id as it reads it (C-16)" taggedAs (UnitTest) in {
    val s = detailedProbe(details = Some(networkPeerManager("Geth\u001b[2J/v1.13\nmore" -> BigInt(1))))()
    s.details.flatMap(_.value).flatMap(_.get("p0")).map(_.clientId) shouldBe Some("Geth/v1.13…")
  }

  it should "say the peers' details are Unresponsive when the network peer manager does not answer" taggedAs (
    UnitTest
  ) in {
    val s = detailedProbe(details = Some(silent.narrow[NetworkPeerManagerActor.Command]))()
    s.details shouldBe Some(Sourced.Unresponsive("no reply within 300ms"))
    s.peers.value.map(_.size) shouldBe Some(1) // GetPeersCmd's own answer is unaffected
  }

  it should "age the details and the txpool with the rest of a late snapshot" taggedAs (UnitTest) in {
    val s = detailedProbe(
      details = Some(networkPeerManager("x" -> BigInt(1))),
      pool = Some(txPool(txs = 1, blobs = 0))
    )()
    val late = s.agedAt(now.plusSeconds(3), java.time.Duration.ofSeconds(2))
    late.details.map(_.isInstanceOf[Sourced.Stale[?]]) shouldBe Some(true)
    late.txpool.map(_.isInstanceOf[Sourced.Stale[?]]) shouldBe Some(true)
  }

  it should "read the peer scores slice 4a publishes to NetworkMetrics, keyed by peer id (Q5, D-1)" taggedAs (UnitTest) in {
    val takenAt = now.minusSeconds(4)
    TuiStatusProbe.peerScores(PeerScoreSnapshot.empty) shouldBe None // nothing published yet
    val published = PeerScoreSnapshot(takenAt, Map(PeerId("ab12") -> 0.912), ScoringStatistics.empty)
    TuiStatusProbe.peerScores(published) shouldBe Some(PeerScores(takenAt, Map("ab12" -> 0.912)))

    // NetworkMetrics is process-wide: the empty snapshot goes back, whatever the assertions do.
    val before = NetworkMetrics.peerScores
    NetworkMetrics.publishPeerScores(published)
    try
      TuiStatusProbe.LocalSources.networkScores() shouldBe Some(PeerScores(takenAt, Map("ab12" -> 0.912)))
      detailedProbe(local =
        TuiStatusProbe.LocalSources(peerScores = TuiStatusProbe.LocalSources.networkScores)
      )().local.peerScores shouldBe Some(PeerScores(takenAt, Map("ab12" -> 0.912)))
    finally NetworkMetrics.publishPeerScores(before)
  }

  it should "read the last import from RegularSyncMetrics" taggedAs (UnitTest) in {
    val metrics = com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics
    metrics.recordLastImport(blocks = 3, nanos = 61_000_000L)
    try TuiStatusProbe.LocalSources.regularSyncImport() shouldBe Some(ImportSample(3, java.time.Duration.ofMillis(61)))
    finally metrics.clearLastImport()
    TuiStatusProbe.LocalSources.regularSyncImport() shouldBe None
    detailedProbe(local =
      TuiStatusProbe.LocalSources(lastImport = () => Some(ImportSample(1, java.time.Duration.ZERO)))
    )().local.lastImport shouldBe Some(ImportSample(1, java.time.Duration.ZERO))
  }

  // ---- Spec 027 slice 5 (T108-T116): the Engine API and the mining metrics, read on the probe thread -------------

  private def hashOf(first: Int): ByteString = ByteString(Array.fill[Byte](32)(first.toByte))

  private val zeroHash: ByteString = ByteString(new Array[Byte](32))

  it should "carry the consensus-layer and mining readings its thunks give, and nothing where they are not wired" taggedAs (
    UnitTest
  ) in {
    val cl = ClSample(forkchoiceUpdated = Some(ClCall(now.minusSeconds(4), "VALID")))
    val mining = MiningSample(hashrate = 48_200_000L)
    val wired = localProbe(TuiStatusProbe.LocalSources(cl = () => Some(cl), mining = () => Some(mining)))().local
    (wired.cl, wired.mining) shouldBe ((Some(cl), Some(mining)))
    // The gate is the thunk: a probe given none (an ETC node's cl, an ETH node's mining) reads nothing.
    val unwired = localProbe(TuiStatusProbe.LocalSources())().local
    (unwired.cl, unwired.mining) shouldBe ((None, None))
  }

  it should "leave the consensus-layer and mining readings empty when they fail, and still answer" taggedAs (
    UnitTest
  ) in {
    val failing = TuiStatusProbe.LocalSources(
      cl = () => throw new IllegalStateException("storage closed"),
      mining = () => throw new RuntimeException("no metrics")
    )
    val s = localProbe(failing)()
    (s.local.cl, s.local.mining) shouldBe ((None, None))
    s.sync shouldBe a[Sourced.Fresh[?]]
  }

  private class CountingForkChoice(var state: Option[ForkChoiceHashes]):
    var reads = 0
    val read: () => Option[ForkChoiceHashes] = () =>
      reads += 1
      state

  private class Headers(numbers: Map[ByteString, Long]):
    var lookups: Seq[ByteString] = Nil
    val number: ByteString => Option[Long] = hash =>
      lookups :+= hash
      numbers.get(hash)

  "The consensus-layer reader" should "read the fork choice once a poll, and show the head in full hex" taggedAs (
    UnitTest
  ) in {
    val fc = new CountingForkChoice(Some(ForkChoiceHashes(hashOf(0x7b), hashOf(0x11), hashOf(0x22))))
    val headers = new Headers(Map(hashOf(0x11) -> 7_411_977L, hashOf(0x22) -> 7_411_945L))
    val reader = TuiStatusProbe.LocalSources.clReader(fc.read, headers.number)
    val sample = reader().get
    fc.reads shouldBe 1
    sample.head shouldBe Some("0x" + "7b" * 32)
    (sample.safe, sample.finalized) shouldBe ((Some(7_411_977L), Some(7_411_945L)))
    reader()
    fc.reads shouldBe 2
  }

  it should "show no head, safe or finalized before the consensus layer's first fork choice" taggedAs (UnitTest) in {
    val reader = TuiStatusProbe.LocalSources.clReader(() => None, _ => fail("no hash to look up"))
    val sample = reader().get
    (sample.head, sample.safe, sample.finalized) shouldBe ((None, None, None))
  }

  it should "look the head, a safe or a finalized hash up once, then remember its number" taggedAs (UnitTest) in {
    val fc = new CountingForkChoice(Some(ForkChoiceHashes(hashOf(1), hashOf(2), hashOf(3))))
    val headers = new Headers(Map(hashOf(1) -> 102L, hashOf(2) -> 100L, hashOf(3) -> 90L, hashOf(4) -> 101L))
    val reader = TuiStatusProbe.LocalSources.clReader(fc.read, headers.number)
    reader()
    reader()
    reader().get.headNumber shouldBe Some(102L)
    // Spec 029 slice 5: the head's number is read like the other two, once for a hash.
    headers.lookups.sortBy(_.head) shouldBe Seq(hashOf(1), hashOf(2), hashOf(3))
    // A new safe block is a new lookup; the finalized one is still remembered.
    fc.state = Some(ForkChoiceHashes(hashOf(1), hashOf(4), hashOf(3)))
    val sample = reader().get
    (sample.safe, sample.finalized) shouldBe ((Some(101L), Some(90L)))
    headers.lookups.count(_ == hashOf(3)) shouldBe 1
    headers.lookups.count(_ == hashOf(4)) shouldBe 1
  }

  it should "show a missing safe or finalized block for the zero hash, without a lookup" taggedAs (UnitTest) in {
    val fc = new CountingForkChoice(Some(ForkChoiceHashes(hashOf(1), zeroHash, zeroHash)))
    val headers = new Headers(Map(hashOf(1) -> 102L))
    val reader = TuiStatusProbe.LocalSources.clReader(fc.read, headers.number)
    val sample = reader().get
    (sample.safe, sample.finalized) shouldBe ((None, None))
    // The head was looked up; a zero hash is no block, and was not.
    headers.lookups shouldBe Seq(hashOf(1))
  }

  it should "try again next poll for a hash whose header is not stored yet" taggedAs (UnitTest) in {
    val fc = new CountingForkChoice(Some(ForkChoiceHashes(hashOf(1), hashOf(2), zeroHash)))
    var known = Map.empty[ByteString, Long]
    val reader = TuiStatusProbe.LocalSources.clReader(fc.read, hash => known.get(hash))
    reader().get.safe shouldBe None
    known = Map(hashOf(2) -> 55L)
    reader().get.safe shouldBe Some(55L)
  }

  it should "read what the Engine API metrics recorded: the last calls, the counts, and no ACCEPTED in INVALID" taggedAs (
    UnitTest
  ) in {
    val reader = TuiStatusProbe.LocalSources.clReader(() => None, _ => None)
    val before = reader().get
    val started = System.currentTimeMillis()
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordForkchoiceUpdated("SYNCING")
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordNewPayload("ACCEPTED", 9L, 9L)
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordNewPayload("VALID", 10L, 10L)
    val after = reader().get
    after.forkchoiceUpdated.map(_.status) shouldBe Some("SYNCING")
    after.newPayload.map(_.status) shouldBe Some("VALID")
    after.forkchoiceUpdated.map(_.at.toEpochMilli).get should be >= started
    after.payloads.total - before.payloads.total shouldBe 2L
    after.payloads.accepted - before.payloads.accepted shouldBe 1L
    after.payloads.valid - before.payloads.valid shouldBe 1L
    // B-193: ACCEPTED has its own count, and the invalid count is the INVALID answers alone.
    after.payloads.invalid - before.payloads.invalid shouldBe 0L
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordNewPayload("INVALID", 11L, 11L)
    reader().get.payloads.invalid - after.payloads.invalid shouldBe 1L
  }

  it should "read the authentication failures from the metrics" taggedAs (UnitTest) in {
    val reader = TuiStatusProbe.LocalSources.clReader(() => None, _ => None)
    val before = reader().get.authFailures
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordAuthFailure()
    reader().get.authFailures - before shouldBe 1L
  }

  it should "keep the calls and the counts when a header lookup fails, and show no number for that block" taggedAs (
    UnitTest
  ) in {
    val fc = new CountingForkChoice(Some(ForkChoiceHashes(hashOf(1), hashOf(2), hashOf(3))))
    var failing = true
    val reader = TuiStatusProbe.LocalSources.clReader(
      fc.read,
      hash => if failing then throw new IllegalStateException("storage closed") else Some(hash.head.toLong)
    )
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordForkchoiceUpdated("VALID")
    // The reading is still there: the panel and the disconnection alert do not flicker out for a failed lookup.
    val sample = reader().getOrElse(fail("a header lookup that throws must not empty the reading"))
    sample.forkchoiceUpdated.map(_.status) shouldBe Some("VALID")
    sample.head shouldBe Some("0x" + "01" * 32)
    (sample.safe, sample.finalized) shouldBe ((None, None))
    // It recovers on the next poll.
    failing = false
    val recovered = reader().get
    (recovered.safe, recovered.finalized) shouldBe ((Some(2L), Some(3L)))
  }

  it should "draw the status as text from the node, never as a control sequence" taggedAs (UnitTest) in {
    val reader = TuiStatusProbe.LocalSources.clReader(() => None, _ => None)
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordForkchoiceUpdated("VA\u001b[2JLID\nnext")
    val status = reader().get.forkchoiceUpdated.map(_.status).get
    status.exists(ch => Character.isISOControl(ch)) shouldBe false
    status should include("VA")
    com.chipprbots.ethereum.consensus.engine.EngineApiMetrics.recordForkchoiceUpdated("VALID")
  }

  it should "show the head string it showed, not a new one, while the head stands" taggedAs (UnitTest) in {
    val fc = new CountingForkChoice(Some(ForkChoiceHashes(hashOf(7), zeroHash, zeroHash)))
    val reader = TuiStatusProbe.LocalSources.clReader(fc.read, _ => None)
    val first = reader().get.head.get
    (reader().get.head.get eq first) shouldBe true
    fc.state = Some(ForkChoiceHashes(hashOf(8), zeroHash, zeroHash))
    reader().get.head shouldBe Some("0x" + "08" * 32)
  }

  "The mining reader" should "read the hashrate, the counts and the DAG from PoWMiningMetrics, and the coinbase from its thunk" taggedAs (
    UnitTest
  ) in {
    import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics as Pow
    Pow.updateHashrate(BigInt(48_200_000L))
    Pow.recordBlockMined(0L)
    Pow.recordDagStarted(364L)
    Pow.recordDagGenerating(37)
    val reader = TuiStatusProbe.LocalSources.miningReader(() => "0x4f2a0b9c" + "0" * 32)
    val sample = reader().get
    sample.coinbase shouldBe Some("0x4f2a0b9c" + "0" * 32)
    sample.hashrate shouldBe 48_200_000L
    sample.blocksMined should be >= 1L
    sample.lastMinedAt.map(_.getEpochSecond).get should be > 1_700_000_000L
    sample.dag shouldBe Some(DagSample(364L, DagPhase.Generating, 37, loaded = false))
    Pow.recordDagLoaded()
    reader().get.dag shouldBe Some(DagSample(364L, DagPhase.Generating, 100, loaded = true))
  }

  it should "let a failing coinbase thunk fail the reading, which the probe logs once and leaves empty" taggedAs (
    UnitTest
  ) in {
    val reader = TuiStatusProbe.LocalSources.miningReader(() => throw new IllegalStateException("no coinbase"))
    an[IllegalStateException] should be thrownBy reader()
  }

  it should "read the getWork rate over the last minute, once it has seen two readings far enough apart" taggedAs (
    UnitTest
  ) in {
    import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics as Pow
    val clock = new TuiFixtures.TestClock(now)
    val reader = TuiStatusProbe.LocalSources.miningReader(() => "0x00", clock)
    reader().get.getWorkPerMinute shouldBe None
    for _ <- 1 to 30 do Pow.recordGetWork()
    clock.advance(30)
    reader().get.getWorkPerMinute shouldBe Some(60L)
  }

  "The DAG sample" should "be absent before the first build and name the phase after it" taggedAs (UnitTest) in {
    TuiStatusProbe.dagSample(epoch = -1L, phase = 0L, progress = 0L, loaded = 0L) shouldBe None
    TuiStatusProbe.dagSample(0L, 0L, 0L, 0L) shouldBe Some(DagSample(0L, DagPhase.Pending, 0, loaded = false))
    TuiStatusProbe.dagSample(364L, 1L, 42L, 0L) shouldBe Some(DagSample(364L, DagPhase.Loading, 42, loaded = false))
    TuiStatusProbe.dagSample(364L, 2L, 37L, 0L) shouldBe Some(DagSample(364L, DagPhase.Generating, 37, loaded = false))
    TuiStatusProbe.dagSample(364L, 2L, 100L, 1L) shouldBe Some(DagSample(364L, DagPhase.Generating, 100, loaded = true))
  }

  it should "keep a progress outside 0 to 100, which no writer sends, inside it" taggedAs (UnitTest) in {
    TuiStatusProbe.dagSample(1L, 2L, 250L, 0L).map(_.progress) shouldBe Some(100)
    TuiStatusProbe.dagSample(1L, 2L, -3L, 0L).map(_.progress) shouldBe Some(0)
    TuiStatusProbe.dagSample(1L, 9L, 5L, 0L).map(_.phase) shouldBe Some(DagPhase.Pending)
  }

  "The getWork rate window" should "need two readings at least five seconds apart" taggedAs (UnitTest) in {
    val w = new TuiStatusProbe.RateWindow()
    w.add(0L, 100L) shouldBe None
    w.add(4_000L, 100L) shouldBe None
    w.add(5_000L, 100L) shouldBe Some(0L)
  }

  it should "be the change over the window, per minute" taggedAs (UnitTest) in {
    val w = new TuiStatusProbe.RateWindow()
    w.add(0L, 100L)
    w.add(10_000L, 110L) shouldBe Some(60L)
    w.add(70_000L, 190L) shouldBe Some(80L) // the reading at 0 s is a minute and more old: 10 s to 70 s, 80 requests
  }

  it should "start over when the clock goes backwards, as a stepped clock does" taggedAs (UnitTest) in {
    val w = new TuiStatusProbe.RateWindow()
    w.add(10_000L, 100L)
    w.add(20_000L, 110L) shouldBe Some(60L)
    // The newest reading is later than this one: time went back. The old readings say nothing about what comes next.
    w.add(5_000L, 120L) shouldBe None
    w.add(15_000L, 130L) shouldBe Some(60L)
  }

  it should "start over when the counter goes backwards, as a restarted node's does" taggedAs (UnitTest) in {
    val w = new TuiStatusProbe.RateWindow()
    w.add(0L, 500L)
    w.add(10_000L, 600L) shouldBe Some(600L)
    w.add(20_000L, 3L) shouldBe None
    w.add(30_000L, 13L) shouldBe Some(60L)
  }

  // ---- Spec 029 slice 5: the RPC figures, the Engine call log, the mining history ------------------------------------

  "The RPC sample" should "join the configured transports with what their servers counted and said" taggedAs (
    UnitTest
  ) in {
    import com.chipprbots.ethereum.jsonrpc.RpcCallStats
    val stats = new RpcCallStats(clock = () => now.toEpochMilli)
    stats.record(RpcCallStats.Http, "eth_blockNumber", known = true, 2_000_000L, failed = false)
    stats.record(RpcCallStats.Http, "eth_blockNumber", known = true, 4_000_000L, failed = false)
    stats.record(RpcCallStats.Engine, "eth_syncing", known = true, 1_000_000L, failed = true)
    stats.record(RpcCallStats.Ipc, "nope", known = false, 300_000L, failed = true)
    stats.rateLimited()
    stats.listening(RpcCallStats.Http, isListening = true)
    stats.listening(RpcCallStats.Ws, isListening = false)
    val configured = Seq(
      TransportStatus(
        TransportKind.Http,
        true,
        None,
        "127.0.0.1:8546",
        Seq("eth"),
        TransportAuth.None,
        exposed = Seq("admin")
      ),
      TransportStatus(TransportKind.Ws, true, None, "127.0.0.1:8552", Seq("eth"), TransportAuth.None),
      TransportStatus(TransportKind.Ipc, true, None, "/tmp/fukuii.ipc", Seq("eth"), TransportAuth.SocketFile),
      TransportStatus(TransportKind.Engine, true, None, "127.0.0.1:8551", Seq("engine"), TransportAuth.Jwt)
    )
    val sample = TuiStatusProbe.LocalSources
      .rpcReader(configured, Some(java.time.Duration.ofSeconds(5)), () => stats.snapshot)()
      .get
    val by = sample.transports.map(t => t.kind -> t).toMap
    // What its server said: bound, could not bind, and nothing yet.
    by(TransportKind.Http).listening shouldBe Some(true)
    by(TransportKind.Ws).listening shouldBe Some(false)
    by(TransportKind.Ipc).listening shouldBe None
    (by(TransportKind.Http).calls, by(TransportKind.Http).errors) shouldBe ((2L, 0L))
    (by(TransportKind.Engine).calls, by(TransportKind.Engine).errors) shouldBe ((1L, 1L))
    (by(TransportKind.Ipc).calls, by(TransportKind.Ipc).errors) shouldBe ((1L, 1L))
    // The configuration is carried as it was given: the address, the namespaces, the exposure.
    by(TransportKind.Http).copy(listening = None, calls = 0, errors = 0) shouldBe configured.head
    sample.rateLimit shouldBe Some(java.time.Duration.ofSeconds(5))
    sample.rateLimited shouldBe 1
    val blockNumber = sample.methods.find(_.method == "eth_blockNumber").get
    (blockNumber.calls, blockNumber.errors, blockNumber.lastAt) shouldBe ((2L, 0L, Some(now)))
    // The percentiles are the histogram's: within its 10 % of what was recorded, and never above the longest.
    blockNumber.p50.get.value.toNanos.toDouble shouldBe 2_000_000.0 +- 200_000.0
    blockNumber.p99.get.value.toNanos should be <= 4_000_000L
    blockNumber.p99.get.value.toNanos.toDouble shouldBe 4_000_000.0 +- 400_000.0
    blockNumber.p50.get.below shouldBe false
    // A method answered in 0.3 ms is under the histogram's range of 100 µs? No: within it. One of 30 µs is under it.
    val missing = sample.methods.find(_.method == RpcCallStats.NotFound).get
    missing.p50.get.below shouldBe false
    stats.record(RpcCallStats.Http, "eth_chainId", known = true, 30_000L, failed = false)
    val fast = TuiStatusProbe.rpcSample(configured, None, stats.snapshot).methods.find(_.method == "eth_chainId").get
    fast.p50.get shouldBe Quantile(java.time.Duration.ofNanos(30_000L), below = true)
    sample.methods.map(_.method) should contain(RpcCallStats.NotFound)
    sample.methods.exists(_.method == "nope") shouldBe false
  }

  it should "say the calls and the failures of the last minute, once it has watched ten seconds" taggedAs (UnitTest) in {
    val stats = new com.chipprbots.ethereum.jsonrpc.RpcCallStats
    val time = new TuiFixtures.TestClock(now)
    val reader = TuiStatusProbe.LocalSources.rpcReader(Nil, None, () => stats.snapshot, time)
    def call(failed: Boolean): Unit =
      stats.record(com.chipprbots.ethereum.jsonrpc.RpcCallStats.Http, "eth_chainId", known = true, 1_000L, failed)
    // What was counted before the reader first looked is not of its minute.
    (1 to 50).foreach(_ => call(failed = true))
    reader().get.recent shouldBe None
    time.advanceMillis(9_000L)
    reader().get.recent shouldBe None
    (1 to 30).foreach(i => call(failed = i <= 3))
    time.advanceMillis(1_000L)
    reader().get.recent shouldBe Some(RpcWindow(30, 3, java.time.Duration.ofSeconds(10)))
    // A minute on, with a poll a second, those calls have left the window.
    (1 to 61).foreach { _ =>
      time.advanceMillis(1_000L)
      reader()
    }
    val late = reader().get.recent.get
    late.calls shouldBe 0
    late.errorRate shouldBe None
    late.span shouldBe java.time.Duration.ofSeconds(60)
  }

  it should "pass a method's name through the screen's sanitiser" taggedAs (UnitTest) in {
    import com.chipprbots.ethereum.jsonrpc.RpcCallStats
    val stats = new RpcCallStats
    stats.record(RpcCallStats.Http, "eth_\u001b[2Jx\nq", known = true, 1_000_000L, failed = false)
    val name = TuiStatusProbe.rpcSample(Nil, None, stats.snapshot).methods.head.method
    (name should not).include("\u001b")
    (name should not).include("\n")
    name should startWith("eth_")
  }

  "The Engine call log" should "reach the consensus-layer sample: the calls, the client, the reasons, the builds" taggedAs (
    UnitTest
  ) in {
    import com.chipprbots.ethereum.consensus.engine.*
    import scala.util.Success
    import org.json4s.*
    import org.json4s.native.JsonMethods.parse
    import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
    val log = new EngineCallLog(() => now.toEpochMilli)
    def ok(result: String) =
      Success(JsonRpcResponse("2.0", Some(parse(s"""{"r":$result}""") \ "r"), None, JInt(1)))
    log.observe(
      "engine_newPayloadV4",
      parse("""{"method":"engine_newPayloadV4","params":[{"blockNumber":"0x10"}]}"""),
      ok("""{"status":"VALID"}"""),
      System.nanoTime() - 5_000_000L
    )
    log.observe(
      "engine_getClientVersionV1",
      parse(
        """{"method":"engine_getClientVersionV1","params":[{"code":"LH","name":"Light\u001b[2Jhouse","version":"v7","commit":"ab"}]}"""
      ),
      ok("[]"),
      System.nanoTime()
    )
    log.observe(
      "engine_exchangeCapabilities",
      parse("""{"method":"engine_exchangeCapabilities","params":[["engine_newPayloadV4","engine_getPayloadV4"]]}"""),
      ok("[]"),
      System.nanoTime()
    )
    log.authFailed(AuthFailure.Expired)
    log.authFailed(AuthFailure.Expired)
    log.payloadBuilt(17L, 84)
    log.payloadBuildFailed()
    val head = ByteString(Array.fill[Byte](32)(7))
    val reader = TuiStatusProbe.LocalSources.clReader(
      () => Some(ForkChoiceHashes(head, ByteString.empty, ByteString.empty)),
      hash => Option.when(hash == head)(7_412_011L),
      () => log.snapshot
    )
    val cl = reader().get
    cl.headNumber shouldBe Some(7_412_011L)
    cl.calls should have size 3
    val first = cl.calls.head
    (first.at, first.method, first.status, first.block) shouldBe ((
      now,
      "engine_newPayloadV4",
      Some("VALID"),
      Some(16L)
    ))
    first.duration.toMillis should be >= 5L
    cl.callsTotal shouldBe 3
    // The name the client gave, with the escape sequence gone: cleaned where it is kept, and again on its way here.
    cl.client shouldBe Some(ClClient("LH", "Light[2Jhouse", "v7", "ab"))
    cl.capabilities shouldBe Some(2)
    cl.authReasons shouldBe Map(AuthFailure.Expired.label -> 2L)
    cl.builds shouldBe PayloadBuilds(1, 1, Some(BuiltPayload(now, 17L, 84)))
  }

  // prism, slice 5: the earlier test fed the probe a name the log had already cleaned, so it proved nothing of the
  // probe's own pass. A snapshot built by hand has what the log would never keep.
  it should "pass every text of a call through the screen's sanitiser itself, whatever the log holds" taggedAs (
    UnitTest
  ) in {
    import com.chipprbots.ethereum.consensus.engine.*
    val raw = EngineCallLog.Snapshot(
      calls =
        Vector(EngineCallRecord(now.toEpochMilli, "engine_\u001b[2Jx\ny", Some("VA\u0007LID\u001b[0m"), 1_000L, None)),
      total = 1,
      identity = Some(ClIdentity("L\u001bH", "Light\nhouse" + "x" * 600, "v\u00077", "ab\rcd")),
      capabilities = None,
      authFailures = Map.empty,
      payloadsBuilt = 0,
      payloadBuildsFailed = 0,
      lastBuild = None
    )
    val cl = TuiStatusProbe.LocalSources.clReader(() => None, _ => None, () => raw)().get
    val texts = Seq(cl.calls.head.method, cl.calls.head.status.get) ++
      cl.client.toSeq.flatMap(k => Seq(k.code, k.name, k.version, k.commit))
    all(texts.map(_.exists(ch => Character.isISOControl(ch)))) shouldBe false
    cl.client.get.name.length should be <= TuiStatusProbe.MaxReasonChars + 1
    cl.calls.head.status.get should startWith("VALID")
  }

  it should "leave the sample empty of calls before any, and the head's number empty where its header is not held" taggedAs (
    UnitTest
  ) in {
    import com.chipprbots.ethereum.consensus.engine.EngineCallLog
    val quiet = new EngineCallLog
    val head = ByteString(Array.fill[Byte](32)(9))
    val cl = TuiStatusProbe.LocalSources
      .clReader(
        () => Some(ForkChoiceHashes(head, ByteString.empty, ByteString.empty)),
        _ => None,
        () => quiet.snapshot
      )()
      .get
    (cl.calls, cl.callsTotal, cl.client, cl.capabilities, cl.headNumber) shouldBe ((Vector.empty, 0L, None, None, None))
    cl.authReasons shouldBe empty
    cl.builds shouldBe PayloadBuilds()
  }

  "The mining history" should "gain a point each five seconds, keep ten minutes, and start again when the clock goes back" taggedAs (
    UnitTest
  ) in {
    val clock = new TuiFixtures.TestClock(now)
    val reader = TuiStatusProbe.LocalSources.miningReader(() => "0x00", clock)
    reader().get.history.map(_.at) shouldBe Vector(now)
    // Polls a second apart add nothing until five seconds have passed.
    for _ <- 1 to 4 do
      clock.advance(1)
      reader().get.history should have size 1
    clock.advance(1)
    val two = reader().get.history
    two.map(_.at) shouldBe Vector(now, now.plusSeconds(5))
    two.last.hashrate shouldBe reader().get.hashrate
    // Ten minutes of them, and no more.
    for _ <- 1 to 200 do
      clock.advance(5)
      reader()
    val full = reader().get.history
    full should have size TuiStatusProbe.MiningHistoryPoints.toLong
    full.map(_.at) shouldBe full.map(_.at).sorted
    java.time.Duration
      .between(full.head.at, full.last.at)
      .getSeconds shouldBe 5L * (TuiStatusProbe.MiningHistoryPoints - 1)
    // A clock that stepped back says nothing about what came before it.
    clock.advance(-3_600)
    reader().get.history should have size 1
  }
