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

  // coordinator follow-up (3rd round), BUG-BC3 2nd follow-up: pins clPivotNotYetAdvanced using the EXACT
  // Platåberget soak incident numbers (01:30:19.996 log line: "CL-based pivot 285486 not strictly newer than
  // current 285486 (CL head=285550, offset=64). Skipping refresh."), plus the companion "CL genuinely advanced"
  // case. See clPivotNotYetAdvanced's doc for why explicit parameters (rather than a live isPoSChain=true actor)
  // are how this module's test suite can exercise the CL-anchored branch's decision at all.
  "SNAPSyncController.clPivotNotYetAdvanced" should
    "be true for the exact stalled-CL incident numbers (CL head=285550, offset=64, current pivot=285486)" taggedAs UnitTest in {
      SNAPSyncController.clPivotNotYetAdvanced(
        clHead = BigInt(285550),
        pivotBlockOffset = 64,
        currentPivot = BigInt(285486)
      ) shouldBe true // target (285486) == currentPivot (285486): not STRICTLY newer
    }

  it should "be false once the CL head has advanced enough to produce a strictly newer target" taggedAs UnitTest in {
    SNAPSyncController.clPivotNotYetAdvanced(
      clHead = BigInt(285551), // one block further than the stalled incident value
      pivotBlockOffset = 64,
      currentPivot = BigInt(285486)
    ) shouldBe false // target (285487) > currentPivot (285486): strictly newer
  }

  // forge review follow-up (2nd round): the two lastHealingServeRootBlockToRecord unit tests above pin the
  // HELPER's own logic but not the CALL SITE's wiring to it — reverting the call site to the pre-fix
  // `Some(staleClockNow)` left them green, since they invoke the (still-correct) helper directly. This drives
  // the REAL maybeRequestHealingServeRoot -> refreshPivotInPlace path (via RequestTrieNodeHealing, not
  // HealingAllPeersStateless — the latter never touches lastHealingServeRootBlock at all) on the non-PoS test
  // config (isPoSChain = false throughout this suite, exercising the ETC path byte-for-byte): a first
  // stale-triggered re-peg (from lastHealingServeRootBlock = None, unconditionally stale — identical under old
  // and new code) sets the bookkeeping baseline, then networkBest advances by exactly 100 blocks — strictly
  // between the correct threshold (> margin = 64, base's target-based bookkeeping) and the pre-fix bug's
  // threshold (> 2margin = 128, from recording networkBest itself instead of target). Fixed: 100 > 64, a SECOND
  // re-peg fires. Pre-fix bug: 100 <= 128, it does not — observed via updateBestBlockForPivot's unconditional
  // AppStateStorage best-block write, the same fix-independent signal repegUntil uses elsewhere in this file.
  it should
    "fire a SECOND heal-root-stale re-peg once networkBest has advanced by more than margin (not 2xmargin) on a " +
    "non-PoS chain, pinning the exact call site forge flagged" taggedAs UnitTest in new Fixture:
      val pivot0 = BigInt(1_000)
      val root0 = fakeRoot(0xaa)
      val height1 = 2_000L
      val pivot1 = BigInt(height1 - PivotOffset) // 1_936 — first stale-triggered re-peg's target
      val root1 = fakeRoot(0xbb)
      val height2 = height1 + 100 // strictly between the correct (+64) and pre-fix-buggy (+128) thresholds
      val pivot2 = BigInt(height2 - PivotOffset) // 2_036 — reached only under the fix
      val root2 = fakeRoot(0xcc)

      storeGenesis()
      storeHeaderAt(pivot0, root0)
      storeHeaderAt(pivot1, root1)
      storeHeaderAt(pivot2, root2)
      seedResumeState(pivot0, root0)

      peers.set(Map.empty)
      val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true))
      awaitFirstPoll()
      snap ! SNAPSyncController.Start

      def staleRepegUntil(height: Long, targetPivot: BigInt): Unit =
        peers.set(peersAt(height = height.toInt, snap = true))
        eventually {
          snap ! SNAPSyncController.PollHandshakedPeers
          awaitProcessed(snap)
          awaitProcessed(snap)
          snap ! SNAPSyncController.RequestTrieNodeHealing
          awaitProcessed(snap)
          appStateStorage.getBestBlockNumber() shouldBe targetPivot
        }

      staleRepegUntil(height1, pivot1)
      staleRepegUntil(height2, pivot2)

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

  // coordinator follow-up (3rd round), BUG-BC3 2nd follow-up: regression guard for the SAME code path
  // emptyBecauseClNotAdvanced now gates — the RetryPivotRefresh/PivotBootstrapRetryKey generic retry timer,
  // rather than the direct HealingAllPeersStateless signal already covered above. Sends RetryPivotRefresh
  // directly (private[snap], the same message the 30s timer replays) with peers genuinely empty throughout, so
  // every attempt resolves via the peer-fallback branch (emptyBecauseClNotAdvanced always false there — never
  // reaches the CL-anchored branch this ticket's fix targets) and MUST still count and hand off. This is the
  // regression risk directly created by this round's fix: proving emptyBecauseClNotAdvanced does NOT also
  // suppress counting for the retry timer's OTHER (genuinely-no-peer) failure mode, only the CL-stalled one
  // clPivotNotYetAdvanced's tests pin (isPoSChain=false throughout this suite, so that branch cannot be driven
  // live here — see its doc for why the pure-function tests are this ticket's evidence for that specific claim).
  it should
    "still exhaust the budget and hand off when the GENERIC retry timer (RetryPivotRefresh), not " +
    "HealingAllPeersStateless, repeatedly finds genuinely no peer during StateHealing" taggedAs UnitTest in new Fixture:
      val pivot0 = BigInt(2_000)
      val root0 = fakeRoot(0xdd)
      storeGenesis()
      storeHeaderAt(pivot0, root0)
      seedResumeState(pivot0, root0)

      peers.set(Map.empty)
      val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true))
      awaitFirstPoll()
      snap ! SNAPSyncController.Start

      (1 to MaxHealRepegNoRootAttempts).foreach { _ =>
        snap ! SNAPSyncController.RetryPivotRefresh
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

  // forge review of f2583dc42 (BUG-BC3 3rd follow-up). isPoSChainOverride makes the PoS/CL-anchored paths drivable
  // live. A CL hint whose head - pivotBlockOffset == the current pivot is a STALLED CL (target not strictly newer),
  // the Platåberget state: EL SYNCING keeps Lighthouse from advancing.
  private def stalledClHint(f: Fixture, snap: TypedActorRef[SNAPSyncController.Command], pivot: BigInt): Unit =
    val header = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(pivot + PivotOffset))
    snap ! SNAPSyncController.CLPivotHint(header.hash.value, Some(header))
    f.awaitProcessed(snap)

  // (a) A GENUINE HealingAllPeersStateless with a stalled CL must count and reach the handoff after 10. With a live
  // CL hint newPivotOpt is empty ONLY via the CL-stalled branch, so any exemption keyed on "CL has not advanced"
  // makes the budget unreachable and wedges the node (the coordinator latches pivotRefreshRequested; only a
  // 15-minute watchdog retries).
  "SNAPSyncController on a PoS chain with a stalled CL" should
    "still exhaust the heal budget and hand off on genuine all-peers-stateless reports" taggedAs UnitTest in new Fixture:
      val pivot0 = BigInt(2_000)
      storeGenesis()
      storeHeaderAt(pivot0, fakeRoot(0xe1))
      seedResumeState(pivot0, fakeRoot(0xe1))
      peers.set(Map.empty)
      val snap =
        spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true), isPoS = true)
      awaitFirstPoll()
      snap ! SNAPSyncController.Start
      stalledClHint(this, snap, pivot0)

      (1 to MaxHealRepegNoRootAttempts).foreach { _ =>
        snap ! SNAPSyncController.HealingAllPeersStateless
        awaitProcessed(snap)
      }
      parent.fishForMessage(10.seconds) {
        case SNAPSyncController.SnapSyncFinalized(p) if p == pivot0 => FishingOutcomes.complete
        case SyncProtocol.HealingImpossible => FishingOutcomes.fail("unexpected HealingImpossible")
        case _                              => FishingOutcomes.continueAndIgnore
      }

  // (b) A leftover retry timer armed OUTSIDE StateHealing (provenance false -- the initial state here is exactly
  // that, since nothing counted has armed it) firing repeatedly during StateHealing with a stalled CL must NOT
  // count: healing is progressing on a still-served root.
  it should "not spend the heal budget on a leftover retry timer firing during StateHealing" taggedAs UnitTest in new Fixture:
    val pivot0 = BigInt(2_000)
    storeGenesis()
    storeHeaderAt(pivot0, fakeRoot(0xe2))
    seedResumeState(pivot0, fakeRoot(0xe2))
    peers.set(Map.empty)
    val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true), isPoS = true)
    awaitFirstPoll()
    snap ! SNAPSyncController.Start
    stalledClHint(this, snap, pivot0)

    (1 to MaxHealRepegNoRootAttempts * 2).foreach { _ =>
      snap ! SNAPSyncController.RetryPivotRefresh
      awaitProcessed(snap)
    }
    parent.expectNoMessage(500.millis)

  // (c) Provenance true: a timer armed by a COUNTED attempt keeps counting, so the chain a genuine report starts
  // reaches the handoff (1 counted report + 9 replays = 10).
  it should "count replays of a timer armed by a counted attempt, reaching the handoff" taggedAs UnitTest in new Fixture:
    val pivot0 = BigInt(2_000)
    storeGenesis()
    storeHeaderAt(pivot0, fakeRoot(0xe3))
    seedResumeState(pivot0, fakeRoot(0xe3))
    peers.set(Map.empty)
    val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true), isPoS = true)
    awaitFirstPoll()
    snap ! SNAPSyncController.Start
    stalledClHint(this, snap, pivot0)

    snap ! SNAPSyncController.HealingAllPeersStateless
    awaitProcessed(snap)
    (1 until MaxHealRepegNoRootAttempts).foreach { _ =>
      snap ! SNAPSyncController.RetryPivotRefresh
      awaitProcessed(snap)
    }
    parent.fishForMessage(10.seconds) {
      case SNAPSyncController.SnapSyncFinalized(p) if p == pivot0 => FishingOutcomes.complete
      case SyncProtocol.HealingImpossible => FishingOutcomes.fail("unexpected HealingImpossible")
      case _                              => FishingOutcomes.continueAndIgnore
    }

  // forge warning: retryRefreshCounts must reset when a re-peg succeeds. A counted timer armed before a successful
  // re-peg would otherwise replay with a stalled CL, count again, re-arm and reach a spurious handoff.
  it should "not count a replay of a counted timer armed before a successful re-peg" taggedAs UnitTest in new Fixture:
    val pivot0 = BigInt(2_000)
    val pivot1 = BigInt(2_100)
    storeGenesis()
    storeHeaderAt(pivot0, fakeRoot(0xE4))
    // Post-merge-shaped pivot header so the PoS gate in completePivotRefreshWithStateRoot accepts the re-peg.
    val posHeader = Fixtures.Blocks.Genesis.header.copy(
      number = BlockNumber(pivot1),
      stateRoot = TrieRoot(fakeRoot(0xE5)),
      difficulty = com.chipprbots.ethereum.domain.Difficulty.Zero,
      nonce = ByteString(new Array[Byte](8)),
      ommersHash = com.chipprbots.ethereum.domain.BlockHash(com.chipprbots.ethereum.domain.BlockHeader.EmptyOmmers),
      extraFields = com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
    )
    blockchainWriter.storeBlock(Block(posHeader, BlockBody.empty)).commit()
    seedResumeState(pivot0, fakeRoot(0xE4))
    peers.set(Map.empty)
    val snap = spawnController(SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true), isPoS = true)
    awaitFirstPoll()
    snap ! SNAPSyncController.Start
    stalledClHint(this, snap, pivot0)

    // One counted attempt (stalled CL): count 1, arms a COUNTED timer.
    snap ! SNAPSyncController.HealingAllPeersStateless
    awaitProcessed(snap)
    // CL advances: the next attempt re-pegs successfully (pivot0 -> pivot1) and must reset the provenance.
    val advanced = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(pivot1 + PivotOffset))
    snap ! SNAPSyncController.CLPivotHint(advanced.hash.value, Some(advanced))
    snap ! SNAPSyncController.HealingAllPeersStateless
    awaitProcessed(snap)
    appStateStorage.getBestBlockNumber() shouldBe pivot1
    // CL stalled again at the new pivot; the old timer's replays must not count.
    (1 to MaxHealRepegNoRootAttempts * 2).foreach { _ =>
      snap ! SNAPSyncController.RetryPivotRefresh
      awaitProcessed(snap)
    }
    parent.expectNoMessage(500.millis)

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

    def spawnController(config: SNAPSyncConfig, isPoS: Boolean = false): TypedActorRef[SNAPSyncController.Command] =
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
          parent.ref,
          isPoSChainOverride = Option.when(isPoS)(true)
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
