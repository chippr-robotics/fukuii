package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.compiletime.asMatchable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

/** Regression coverage for the Platåberget soak SNAP-finalization bug (2026-09-27): a lazy-heal handoff
  * (`HealingRootUnservable`, or the moving-root-delta-heal re-peg-budget exhaustion) calling `completeSnapSync()`
  * directly from inside `StateHealing`, after one or more successful in-place re-pegs, used to abort with
  * `HealingImpossible` even though `pivotBlock`/`stateRoot` (in-memory) were already self-consistent with a real,
  * durably-stored header.
  *
  * Root cause: `completePivotRefreshWithStateRoot()` deliberately skips persisting `SnapSyncPivotBlock`/
  * `SnapSyncStateRoot` while `currentPhase == StateHealing` (the BUG-006 guard). A lazy handoff that fires AFTER at
  * least one re-peg therefore finalizes against a persisted anchor that still points at the PRE-healing pivot, while
  * `finalizeSnapSync`'s A5 guard looks up the pivot header for the CURRENT (re-pegged) in-memory pivot — comparing two
  * different pivots and aborting on a self-inflicted mismatch. `anchorPivotBeforeLazyHandoff` (see
  * `SNAPSyncController.scala`, next to `completeSnapSync()`) fixes this by re-anchoring the persisted keys to the
  * in-memory pivot/root immediately before the terminal handoff.
  *
  * Harness note: pivot selection here uses the peer-reported-best fallback (`currentNetworkBestFromSnapPeers()`), not
  * the CL-driven (`isPoSChain`) path — mirroring `SNAPDormantRecoverySpec`, which exercises the same fallback
  * successfully in this test environment. The anchor-persistence bug being tested lives in code shared byte-for-byte
  * between ETC and ETH-family chains (`completePivotRefreshWithStateRoot`, `anchorPivotBeforeLazyHandoff`,
  * `finalizeSnapSync`'s A5 guard); which pivot-selection path feeds it a target pivot is orthogonal to the bug.
  */
class SNAPLazyHealAnchorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  // Generous patience: repegUntil retries across a classic-actor messageAdapter hop (see its doc below).
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(50, Millis)))

  // Mirrors SNAPSyncController.MaxHealRepegNoRootAttempts (private, ~10). See that file for the 10 x 30s rationale.
  private val MaxHealRepegNoRootAttempts = 10
  private val PivotOffset = SNAPSyncConfig().pivotBlockOffset

  private def fakeRoot(tag: Int): ByteString = ByteString(Array.fill(32)(tag.toByte))

  "SNAPSyncController" should
    "anchor SnapSyncPivotBlock/SnapSyncStateRoot to the LAST re-pegged pivot before the HEAL-REPEG " +
    "budget-exhaustion lazy handoff, instead of aborting on the pre-healing anchor" taggedAs UnitTest in new Fixture:
      val pivot0 = BigInt(10_000)
      val root0 = fakeRoot(0x11)
      // height - PivotOffset(64) = 10_736 / 11_536 respectively.
      val pivot1 = BigInt(10_736)
      val root1 = fakeRoot(0x22)
      val pivot2 = BigInt(11_536)
      val root2 = fakeRoot(0x33)

      storeGenesis()
      storeHeaderAt(pivot0, root0)
      storeHeaderAt(pivot1, root1)
      storeHeaderAt(pivot2, root2)
      seedResumeState(pivot0, root0)

      peers.set(Map.empty)
      val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true))
      awaitFirstPoll()
      // Synchronous end-to-end inside the Start handler (accounts already complete, no peers to bootstrap a
      // header from): by the time this returns, currentPhase == StateHealing and the coordinator is spawned
      // against root0 — Pekko's per-actor mailbox ordering guarantees the sends below are processed after it.
      snap ! SNAPSyncController.Start

      // Two successful in-place re-pegs during StateHealing (pivot0 -> pivot1 -> pivot2). Per BUG-006, neither
      // persists — AppStateStorage's SnapSyncPivotBlock/SnapSyncStateRoot stay at pivot0/root0 while in-memory
      // pivotBlock/stateRoot advance to pivot2/root2. See Fixture.repegUntil's doc for the synchronization
      // rationale (GetProgress as a mailbox barrier, updateBestBlockForPivot as the fix-independent observable).
      repegUntil(snap, (pivot1 + PivotOffset).toLong, pivot1)
      repegUntil(snap, (pivot2 + PivotOffset).toLong, pivot2)

      // Exhaust the no-servable-root re-peg budget: with no peers, refreshPivotInPlace always resolves
      // newPivotOpt = None, so MaxHealRepegNoRootAttempts consecutive attempts trigger the lazy handoff. Each
      // send is drained (awaitProcessed) before the next, so the controller handles them strictly one at a time
      // against its latest peer view — no flood of concurrent polls that could reorder relative to each other.
      peers.set(Map.empty)
      snap ! SNAPSyncController.PollHandshakedPeers
      awaitProcessed(snap)
      awaitProcessed(snap)

      (1 to MaxHealRepegNoRootAttempts).foreach { _ =>
        snap ! SNAPSyncController.HealingAllPeersStateless
        awaitProcessed(snap)
      }

      // Before the fix: finalizeSnapSync's A5 guard compares persisted root0 against pivot2's real header
      // (root2) and aborts with HealingImpossible. After the fix: anchorPivotBeforeLazyHandoff persists
      // pivot2/root2 first, so A5 sees a match and finalization succeeds.
      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot2 => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible =>
          FishingOutcomes.fail(
            "SNAP finalization aborted (HealingImpossible) — the persisted anchor was not re-pegged " +
              "before the lazy-heal handoff (BUG-BC2 regression)"
          )
        case _ => FishingOutcomes.continueAndIgnore
      }

      appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot2)
      appStateStorage.getSnapSyncStateRoot() shouldBe Some(root2)

  it should
    "still abort finalization with HealingImpossible when the in-memory root genuinely diverges from the " +
    "pivot header's root (A5 guard must not be weakened)" taggedAs UnitTest in new Fixture:
      val pivotX = BigInt(10_000)
      val persistedRoot = fakeRoot(0x11) // what AppStateStorage says
      val realHeaderRoot = fakeRoot(0x99) // what the pivot header ACTUALLY stored on disk says — genuinely different

      storeGenesis()
      storeHeaderAt(pivotX, realHeaderRoot)
      seedResumeState(pivotX, persistedRoot)

      peers.set(Map.empty)
      val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true))
      awaitFirstPoll()
      snap ! SNAPSyncController.Start

      // No re-pegs at all — pivotBlock/stateRoot never move from the (deliberately corrupted) resume values, so
      // anchorPivotBeforeLazyHandoff (if it fires) only re-writes the SAME mismatched pair. Exhaust the budget
      // immediately to reach the lazy handoff.
      (1 to MaxHealRepegNoRootAttempts).foreach(_ => snap ! SNAPSyncController.HealingAllPeersStateless)

      parent.fishForMessage(10.seconds) {
        case SyncProtocol.HealingImpossible => FishingOutcomes.complete
        case f: SNAPSyncController.SnapSyncFinalized =>
          FishingOutcomes.fail(s"A5 guard should have aborted on a genuine root divergence, but finalized: $f")
        case _ => FishingOutcomes.continueAndIgnore
      }

  // forge review follow-up: HealingRootUnservable (~1443/1451) shares anchorPivotBeforeLazyHandoff with the
  // HEAL-REPEG budget-exhaustion site above, but had no direct coverage. HealingRootUnservable is a public case
  // class and its handler only guards on `currentPhase == StateHealing` (not movingRootDeltaHeal), so — unlike
  // trying to make the real TrieNodeHealingCoordinator emit it under the flag — the test can just send it
  // directly, exactly as HealingAllPeersStateless already is above.
  it should
    "anchor SnapSyncPivotBlock/SnapSyncStateRoot to the LAST re-pegged pivot before the HealingRootUnservable " +
    "lazy handoff, instead of aborting on the pre-healing anchor" taggedAs UnitTest in new Fixture:
      val pivot0 = BigInt(10_000)
      val root0 = fakeRoot(0x44)
      val pivot1 = BigInt(10_736)
      val root1 = fakeRoot(0x55)
      val pivot2 = BigInt(11_536)
      val root2 = fakeRoot(0x66)

      storeGenesis()
      storeHeaderAt(pivot0, root0)
      storeHeaderAt(pivot1, root1)
      storeHeaderAt(pivot2, root2)
      seedResumeState(pivot0, root0)

      peers.set(Map.empty)
      val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true))
      awaitFirstPoll()
      snap ! SNAPSyncController.Start

      // Two successful in-place re-pegs during StateHealing, same as the HEAL-REPEG test above.
      repegUntil(snap, (pivot1 + PivotOffset).toLong, pivot1)
      repegUntil(snap, (pivot2 + PivotOffset).toLong, pivot2)

      // Send the coordinator's absent-root signal directly for the CURRENT (pivot2) root — this is the 1443
      // handoff. The value carried on the message is only used for logging in the handler; the anchor decision
      // reads the controller's OWN in-memory pivotBlock/stateRoot, not this field.
      snap ! SNAPSyncController.HealingRootUnservable(root2)
      awaitProcessed(snap)

      // Before the fix: finalizeSnapSync's A5 guard compares persisted root0 against pivot2's real header
      // (root2) and aborts with HealingImpossible. After the fix: anchorPivotBeforeLazyHandoff persists
      // pivot2/root2 first, so A5 sees a match and finalization succeeds.
      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot2 => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible =>
          FishingOutcomes.fail(
            "SNAP finalization aborted (HealingImpossible) — the persisted anchor was not re-pegged before the " +
              "HealingRootUnservable lazy handoff"
          )
        case _ => FishingOutcomes.continueAndIgnore
      }

      appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot2)
      appStateStorage.getSnapSyncStateRoot() shouldBe Some(root2)

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPLazyHealAnchorSpec.this.system.classicSystem

    val appStateStorage = storagesInstance.storages.appStateStorage

    val peers: AtomicReference[Map[Peer, PeerInfo]] = new AtomicReference(Map.empty)
    private val pollsAnswered = new AtomicInteger(0)

    // Stands in for NetworkPeerManagerActor: answers each peer poll with the current `peers`. Mirrors
    // SNAPDormantRecoverySpec's Fixture — the reply is sent before the counter moves, so a test that waits on the
    // counter knows the reply is already in the controller's mailbox.
    private val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.setAutoPilot(
      new AutoPilot:
        override def run(sender: ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case GetHandshakedPeersCmd(replyTo) =>
              replyTo ! HandshakedPeers(peers.get())
              pollsAnswered.incrementAndGet()
            case _ => ()
          this
    )

    val parent: TypedTestProbe[SyncProtocol.SyncControllerReply] =
      testKit.createTestProbe[SyncProtocol.SyncControllerReply]()

    def storeGenesis(): Unit =
      val genesis = Fixtures.Blocks.Genesis.header
      blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )

    /** Store a synthetic header at `number` with `root` as its state root, durably enough that
      * `blockchainReader.getBlockHeaderByNumber` resolves it — same pattern `finalizeSnapSync` itself relies on.
      */
    def storeHeaderAt(number: BigInt, root: ByteString): Unit =
      val header = Fixtures.Blocks.Genesis.header.copy(
        number = BlockNumber(number),
        stateRoot = TrieRoot(root)
      )
      blockchainWriter.storeBlock(Block(header, BlockBody.empty)).commit()

    /** Seed AppStateStorage as if a prior SNAP run downloaded accounts + bytecodes + storage completely and left off at
      * (pivot, root) — the "accounts-complete recovery" branch of SNAPSyncController.startSnapSync() reads exactly
      * these keys and, with no peers connected (networkBest == 0, drift check skipped), adopts them directly into
      * pivotBlock/stateRoot before promoting straight to StateHealing.
      */
    def seedResumeState(pivot: BigInt, root: ByteString): Unit =
      appStateStorage
        .putSnapSyncAccountsComplete(true)
        .and(appStateStorage.putSnapSyncStorageComplete(true))
        .and(appStateStorage.putSnapSyncBytecodeComplete(true))
        .and(appStateStorage.putSnapSyncPivotBlock(pivot))
        .and(appStateStorage.putSnapSyncStateRoot(root))
        .commit()

    def spawnController(config: SNAPSyncConfig): TypedActorRef[SNAPSyncController.Command] =
      given ExecutionContext = system.executionContext
      testKit.spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          appStateStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.flatSlotStorage,
          networkPeerManager.ref.toTyped[NetworkPeerManagerActor.Command],
          testKit.spawn(PeerEventBusActor.behavior()),
          syncConfig,
          config,
          system.classicSystem.scheduler,
          CacheBasedBlacklist.empty(100),
          parent.ref
        )
      )

    /** Wait for `start()`'s immediate peer poll to be answered. */
    def awaitFirstPoll(): Unit = eventually(pollsAnswered.get() should be >= 1)

    private val progressProbe = testKit.createTestProbe[SyncProgress]()

    /** Barrier: GetProgress is processed in strict mailbox order relative to anything the TEST THREAD sent earlier
      * (Pekko's same-sender FIFO guarantee), so round-tripping it proves every send the test made before it has already
      * been handled by `snap`. It does NOT, by itself, order a reply crossing a DIFFERENT sender (e.g. the
      * classic-actor peer-poll reply, which hops through a messageAdapter) — callers that depend on that ordering call
      * this twice to give such a reply two round-trips' worth of opportunity to land first.
      */
    def awaitProcessed(snap: TypedActorRef[SNAPSyncController.Command]): Unit =
      snap ! SNAPSyncController.GetProgress(progressProbe.ref)
      progressProbe.receiveMessage(5.seconds)
      ()

    /** Poll `peers` at `targetHeight`, then retry (poll + a HealingAllPeersStateless re-peg attempt, each drained via
      * awaitProcessed) until the re-peg has actually landed. `updateBestBlockForPivot()` persists AppStateStorage's
      * best-block number on EVERY successful re-peg UNCONDITIONALLY (unlike SnapSyncPivotBlock/SnapSyncStateRoot, it is
      * not gated by the BUG-006 StateHealing guard), so it is a fix-independent, externally observable signal that a
      * specific re-peg landed. The outer `eventually` is the safety net for a CI machine slow enough that the two
      * awaitProcessed() barriers in a row aren't enough.
      */
    def repegUntil(snap: TypedActorRef[SNAPSyncController.Command], targetHeight: Long, targetPivot: BigInt): Unit =
      peers.set(peersAt(height = targetHeight.toInt, snap = true))
      eventually {
        snap ! SNAPSyncController.PollHandshakedPeers
        awaitProcessed(snap)
        awaitProcessed(snap)
        snap ! SNAPSyncController.HealingAllPeersStateless
        awaitProcessed(snap)
        appStateStorage.getBestBlockNumber() shouldBe targetPivot
      }

    def peersAt(height: Int, snap: Boolean): Map[Peer, PeerInfo] =
      val status = RemoteStatus(
        Capability.ETH68,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(height),
        bestHash = ByteString(s"best-$height"),
        genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
        supportsSnap = snap
      )
      val peer = Peer(
        PeerId("snap-peer"),
        new InetSocketAddress("127.0.0.1", 30303),
        TestProbe().ref.toTyped[PeerActor.Command],
        incomingConnection = false
      )
      Map(
        peer -> PeerInfo(
          remoteStatus = status,
          chainWeight = status.chainWeight,
          forkAccepted = true,
          maxBlockNumber = height,
          bestBlockHash = status.bestHash
        )
      )
