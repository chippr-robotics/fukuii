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

  // BUG-BC3 (Platåberget ePBS-devnet soak, 2026-09-27): the heal-root staleness clock used networkBest
  // (peer-reported) even under movingRootDeltaHeal on a PoS chain, where the actual re-peg decision is
  // CL-anchored. A CL that cannot advance (Lighthouse stuck behind an EL reporting SYNCING) never produces a
  // newer pivot, but peers kept gossiping a climbing STATUS height, so the mismatched clock kept re-triggering
  // "heal root stale" checks that were guaranteed to find nothing. staleReferenceHead is the extracted decision;
  // see its doc in SNAPSyncController.scala for why an actor-level (isPoSChain=true) test isn't used here — the
  // "test" network config has no terminal-total-difficulty, so isPoSChain is false for every actor spawned in
  // this module's test suite (confirmed by SNAPSyncControllerSpec's own PoSBlockHeaderValidator tests, which use
  // the same standalone-pure-function pattern for the identical reason).
  "SNAPSyncController.staleReferenceHead" should "use the CL head, not networkBest, under movingRootDeltaHeal on a PoS chain with a live CL hint" taggedAs UnitTest in {
    SNAPSyncController.staleReferenceHead(
      movingRootDeltaHeal = true,
      isPoSChain = true,
      clHeadNumber = Some(BigInt(284598)),
      networkBest = BigInt(284670)
    ) shouldBe BigInt(284598)
  }

  it should "fall back to networkBest when isPoSChain but no CL hint has arrived yet" taggedAs UnitTest in {
    SNAPSyncController.staleReferenceHead(
      movingRootDeltaHeal = true,
      isPoSChain = true,
      clHeadNumber = None,
      networkBest = BigInt(284670)
    ) shouldBe BigInt(284670)
  }

  it should "use networkBest, unchanged, on ETC/pre-merge (isPoSChain = false) regardless of movingRootDeltaHeal" taggedAs UnitTest in {
    SNAPSyncController.staleReferenceHead(
      movingRootDeltaHeal = true,
      isPoSChain = false,
      clHeadNumber = None,
      networkBest = BigInt(284670)
    ) shouldBe BigInt(284670)
  }

  it should "use networkBest, unchanged, on the decoupledHealServeRoot path (movingRootDeltaHeal = false) even on a PoS chain" taggedAs UnitTest in {
    SNAPSyncController.staleReferenceHead(
      movingRootDeltaHeal = false,
      isPoSChain = true,
      clHeadNumber = Some(BigInt(284598)),
      networkBest = BigInt(284670)
    ) shouldBe BigInt(284670)
  }

  // forge review follow-up on b8f0700f6 (BUG-BC3): pins the lastHealingServeRootBlock bookkeeping choice — the
  // value RECORDED after a stale-triggered re-peg, as distinct from staleReferenceHead (the value COMPARED
  // against). Getting this wrong on ETC (moving-root-delta-heal ships true by default, base/sync.conf, no ETC
  // override) silently doubled the effective networkBest-advance threshold between re-pegs (~64 -> ~128 blocks,
  // ~14 -> ~28 min), approaching peers' serve window — see lastHealingServeRootBlockToRecord's doc for the full
  // arithmetic. These pin BOTH branches directly and deterministically, without needing an actor at all.
  "SNAPSyncController.lastHealingServeRootBlockToRecord" should
    "record target (networkBest - margin), NOT networkBest, on a non-CL-anchored check (ETC/pre-merge, or PoS " +
    "before a CL hint arrives) — byte-identical to base" taggedAs UnitTest in {
      val networkBest = BigInt(284670)
      val margin = BigInt(64)
      val target = networkBest - margin // what recentRootTarget would have produced
      // staleClockNow == networkBest is exactly what staleReferenceHead returns whenever it did NOT switch to
      // the CL head (isPoSChain = false, or no CL hint yet) — see its own tests above.
      SNAPSyncController.lastHealingServeRootBlockToRecord(
        staleClockNow = networkBest,
        networkBest = networkBest,
        target = target
      ) shouldBe target
    }

  it should
    "record the CL head (staleClockNow) directly, NOT target, when the check WAS CL-anchored" taggedAs UnitTest in {
      val networkBest = BigInt(284670)
      val clHead = BigInt(284598) // staleReferenceHead's output when it switched to the CL head
      val target = networkBest - BigInt(64)
      SNAPSyncController.lastHealingServeRootBlockToRecord(
        staleClockNow = clHead,
        networkBest = networkBest,
        target = target
      ) shouldBe clHead
    }

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

  // BUG-BC3 regression: a "heal root stale" re-peg that finds no usable target must not count against the
  // HEAL-REPEG budget, however many times it happens, while a genuine all-peers-stateless failure still must.
  //
  // isPoSChain is false throughout this module's test suite (see the staleReferenceHead tests' doc above), so
  // this cannot drive the real-world trigger (a frozen CL head) — refreshPivotInPlace's peer-reported-best
  // branch has no "must be strictly newer than current" check the way the CL-anchored branch does, so a stable
  // or climbing peer height alone never reproduces a REPEATED no-target outcome there. Instead this drives
  // maybeRequestHealingServeRoot's "heal root stale" trigger to repeatedly and genuinely find no usable target,
  // using a large pivotBlockOffset (5000, comfortably above 2xHealingServeRootMarginBlocks=128) so that
  // `networkBest - offset <= 0` while networkBest still climbs by enough each step (200 > 128) to keep re-tripping
  // staleness: `refreshPivotInPlace`'s peer-fallback branch computes `networkBest - max(offset, margin)` with NO
  // clamp (unlike recentRootTarget's outer check, which clamps to a minimum of 1), so it resolves to None on
  // every one of these steps. Because countsTowardHealBudget is a plain boolean the call site passes — not a
  // property of WHY newPivotOpt ended up empty — exercising "repeated genuine empty result via the heal-root-stale
  // call site" this way exercises the exact same code (refreshPivotInPlace's countsTowardHealBudget=false branch)
  // that a frozen CL head would.
  it should
    "not count a preemptive heal-root-stale re-peg that finds no usable target against the HEAL-REPEG budget, " +
    "however many times it repeats, while a genuine all-peers-stateless failure still exhausts it" taggedAs UnitTest in new Fixture:
      val pivot0 = BigInt(100)
      val root0 = fakeRoot(0x77)
      storeGenesis()
      storeHeaderAt(pivot0, root0)
      seedResumeState(pivot0, root0)

      peers.set(Map.empty)
      val snap = spawnController(
        SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true, pivotBlockOffset = 5000)
      )
      awaitFirstPoll()
      snap ! SNAPSyncController.Start

      // 11 climbing heights (> MaxHealRepegNoRootAttempts), each re-tripping staleness (step 200 > margin*2=128)
      // while staying low enough (<= 5000 = pivotBlockOffset) that refreshPivotInPlace's peer-fallback branch
      // always resolves to None (target <= 0). Pre-fix, each of these would have counted, exhausting the budget
      // (>=10) and handing off well before this loop even finishes.
      (1 to 11).foreach { i =>
        peers.set(peersAt(height = i * 200, snap = true))
        snap ! SNAPSyncController.PollHandshakedPeers
        awaitProcessed(snap)
        awaitProcessed(snap)
        snap ! SNAPSyncController.RequestTrieNodeHealing
        awaitProcessed(snap)
      }

      // Not yet handed off: pivotBlock/stateRoot/AppStateStorage are all still exactly what they were seeded
      // with — none of the 11 "stale, no target" attempts above should have touched them or the budget.
      parent.expectNoMessage(500.millis)
      appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot0)
      appStateStorage.getSnapSyncStateRoot() shouldBe Some(root0)

      // Now exhaust the budget for real: MaxHealRepegNoRootAttempts consecutive genuine "all peers stateless"
      // failures (peers empty). If the 11 prior attempts had counted (pre-fix), the budget would already have
      // been spent and this would either hand off immediately on far fewer sends or have already fired above.
      peers.set(Map.empty)
      snap ! SNAPSyncController.PollHandshakedPeers
      awaitProcessed(snap)
      awaitProcessed(snap)
      (1 to MaxHealRepegNoRootAttempts).foreach { _ =>
        snap ! SNAPSyncController.HealingAllPeersStateless
        awaitProcessed(snap)
      }

      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot0 => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible =>
          FishingOutcomes.fail(
            "SNAP finalization aborted (HealingImpossible) — unexpected, since pivot/root never moved in this test"
          )
        case _ => FishingOutcomes.continueAndIgnore
      }

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
