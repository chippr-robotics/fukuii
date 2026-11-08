package com.chipprbots.ethereum.console

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
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

  private def probe(
      pm: Behavior[PeerManagerActor.Command],
      sc: Behavior[SyncController.Command],
      best: BigInt = 42,
      snap: Option[SyncProgress] = None
  ) =
    new TuiStatusProbe(spawn(pm), spawn(sc), () => best, 50, () => snap, 300.millis)(using system.scheduler)

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
        val frame = renderer.render(TuiState.initial.withSnapSyncState(s), 80, 24).map(_.toString)
        val overallRow = frame.find(_.startsWith("   Overall")).get
        overallRow.drop(12).trim shouldBe WormToBrainBar.renderKnown(p.globalProgress)
      }
    // Chain download is 4 of 5 stages done, not 0 %.
    val chainDownload = snapSample.copy(phase = SyncPhase.ChainDownloadCompletion, phaseProgress = 0)
    TuiStatusProbe.toSnapSyncState(chainDownload).overall shouldBe 0.8 +- 1e-9
  }

  it should "say so when syncing has no target yet (no peers to pick a pivot from)" taggedAs (UnitTest) in {
    val status = SyncProtocol.Status.Syncing(0, Progress(0, 0), None)
    val s = probe(peerManager(), syncController(status), best = 0)()
    (s.bestBlock, s.syncStatus) shouldBe ((0L, "Syncing (no target yet)"))
  }
