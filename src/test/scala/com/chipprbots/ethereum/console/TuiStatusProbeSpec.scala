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
import com.chipprbots.ethereum.crypto.generateKeyPair
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
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.testing.Tags.*

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
    // An emoji (a surrogate pair) across the cut is dropped whole, not split.
    val emoji = TuiStatusProbe.reason(new RuntimeException("x" * (TuiStatusProbe.MaxReasonChars - 4) + "🪱" * 10))
    emoji shouldBe "x" * (TuiStatusProbe.MaxReasonChars - 4) + "..."
    emoji.exists(Character.isSurrogate) shouldBe false
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

  private val keyPair = generateKeyPair(new java.security.SecureRandom())

  private def pendingTx(nonce: Int) =
    val tx = LegacyTransaction(nonce, GasPrice(1), GasAmount(21_000), Address("0x1234"), 0, ByteString())
    PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, None), Address(keyPair)),
      0L
    )

  /** The txpool, answering `GetPendingTransactionsReq` with `txs` transactions, `blobs` of them carrying a sidecar. */
  private def txPool(txs: Int, blobs: Int): Behavior[PendingTransactionsManager.Command] =
    Behaviors.receiveMessage {
      case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
        replyTo ! PendingTransactionsManager.PendingTransactionsResponse(
          (0 until txs).map(pendingTx),
          (0 until blobs).map(i => ByteString(s"h$i") -> ByteString(s"sidecar$i")).toMap
        )
        Behaviors.same
      case _ => Behaviors.same
    }

  private def detailedProbe(
      details: Option[Behavior[NetworkPeerManagerActor.Command]] = None,
      pool: Option[Behavior[PendingTransactionsManager.Command]] = None,
      local: TuiStatusProbe.LocalSources = TuiStatusProbe.LocalSources()
  ) =
    new TuiStatusProbe(
      spawn(peerManager(PeerActor.Status.Handshaked)),
      spawn(syncController(SyncProtocol.Status.SyncDone)),
      () => 42,
      50,
      () => None,
      300.millis,
      clock,
      local,
      networkPeerManager = details.map(spawn(_)),
      txPool = pool.map(spawn(_))
    )(using system.scheduler)

  it should "carry the txpool's size and its blob count from PendingTransactionsResponse (T089)" taggedAs (UnitTest) in {
    detailedProbe(pool = Some(txPool(txs = 3, blobs = 1)))().txpool shouldBe
      Some(Sourced.Fresh(TxpoolSample(size = 3, blobs = 1), now))
  }

  it should "say the txpool is Unresponsive when it does not answer, and leave it unasked when not wired" taggedAs (
    UnitTest
  ) in {
    detailedProbe(pool = Some(silent.narrow[PendingTransactionsManager.Command]))().txpool shouldBe Some(Sourced.Unresponsive("no reply within 300ms"))
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

    NetworkMetrics.publishPeerScores(published)
    TuiStatusProbe.LocalSources.networkScores() shouldBe Some(PeerScores(takenAt, Map("ab12" -> 0.912)))
    detailedProbe(local = TuiStatusProbe.LocalSources(peerScores = TuiStatusProbe.LocalSources.networkScores))()
      .local
      .peerScores shouldBe Some(PeerScores(takenAt, Map("ab12" -> 0.912)))
  }

  it should "read the last import from RegularSyncMetrics" taggedAs (UnitTest) in {
    com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics.recordLastImport(blocks = 3, nanos = 61_000_000L)
    TuiStatusProbe.LocalSources.regularSyncImport() shouldBe Some(ImportSample(3, java.time.Duration.ofMillis(61)))
    detailedProbe(local = TuiStatusProbe.LocalSources(lastImport = () => Some(ImportSample(1, java.time.Duration.ZERO))))()
      .local
      .lastImport shouldBe Some(ImportSample(1, java.time.Duration.ZERO))
  }
