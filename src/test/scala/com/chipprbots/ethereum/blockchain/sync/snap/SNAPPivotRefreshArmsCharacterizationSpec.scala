package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 lift slice before M10: characterization of the `commonSyncingArms` arms that the slice lifts into class
  * members, as they behave today. It adds only what the existing suites leave unasserted:
  *   - the refresh arms of `BootstrapComplete`/`PivotBootstrapFailed` (guarded by `pendingPivotRefresh`): a missing
  *     header falls back to a full restart; a bootstrap failure backtracks by `pivotBlockOffset` and retries at that
  *     block after 5 s, or, when the backtracked block is not positive, retries the refresh after 60 s;
  *   - the readiness probe: an empty probe answer defers the roll without forwarding; a non-probe `AccountRange` is
  *     forwarded while a probe is pending; a probe timeout that fires after the probe answered is ignored;
  *   - `RetryPivotRefresh` on a PoS chain in `StateHealing`: a replay armed by a counted "all healing peers stateless"
  *     attempt keeps that attempt's unservable-pivot fallback (#1555), and counts toward the re-peg budget;
  *   - `PivotStateUnservable`: a stateless report counts and refreshes; a second report inside the debounce window
  *     re-arms the range coordinators on the current root without counting or refreshing; outside the download phases
  *     it is ignored;
  *   - `DelayedRestart`: restarts in `ByteCodeAndStorageSync`, is ignored in `StateHealing`.
  *
  * Already pinned elsewhere and not repeated: the refresh success path and the first probe timeout
  * (`SNAPPivotRefreshCharacterizationSpec`, T023), `DelayedRestart` in `AccountRangeSync`
  * (`SNAPResetCharacterizationSpec`), the budget exhaustion on stateless reports alone and the uncounted leftover
  * replay (`SNAPLazyHealAnchorSpec`), and the arm order for every command and phase (`SNAPSyncingDispatchTableSpec`,
  * T027).
  *
  * Not pinned (they need wall-clock time to pass, so a deterministic test cannot reach them): the forced roll after
  * `MaxProbeAttempts` deferrals (each new probe waits `ProbeCooldownMs` of wall-clock time), and the
  * `MaxConsecutivePivotRefreshes` stateless threshold (each counted report must fall outside the wall-clock
  * `MinPivotRestartInterval` debounce).
  */
class SNAPPivotRefreshArmsCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:
  import SnapControllerFixture.*

  private val Offset = SNAPSyncConfig().pivotBlockOffset // 64
  private val Margin = BigInt(50) // SNAPSyncController's SnapServeWindowMargin
  private val PoSHeal = SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true)

  private def expectBootstrapRequest(f: SnapControllerFixture, target: BigInt): Unit =
    f.parent.fishForMessage(10.seconds) {
      case SNAPSyncController.StartRegularSyncBootstrap(`target`) => FishingOutcomes.complete
      case _                                                      => FishingOutcomes.continueAndIgnore
    }
    ()

  /** The refresh messages the three download children received up to a fence. */
  private def downloadChildRefreshes(
      f: SnapControllerFixture,
      snap: ActorRef[SNAPSyncController.Command]
  ): (Seq[AccountRangeCoordinator.Command], Seq[StorageRangeCoordinator.Command], Seq[ByteCodeCoordinator.Command]) =
    val id = f.sendFence(snap)
    (
      f.accountBefore(id).collect { case m: AccountRangeCoordinator.PivotRefreshed => m },
      f.storageBefore(id).collect { case m: StorageRangeCoordinator.StoragePivotRefreshed => m },
      f.byteCodeBefore(id).filter(_ == ByteCodeCoordinator.ByteCodePivotRefreshed)
    )

  private def clHint(f: SnapControllerFixture, snap: ActorRef[SNAPSyncController.Command], head: BigInt): Unit =
    val header = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(head))
    snap ! SNAPSyncController.CLPivotHint(header.hash.value, Some(header))
    f.awaitProcessed(snap)

  // ── BootstrapComplete / PivotBootstrapFailed while a refresh is pending ────────────────────────

  "SNAPSyncController's pending pivot refresh (ETC)" should
    "fall back to a full restart when the refresh's header bootstrap returns no header" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(height = 1_000)
      val snap = entry.snap
      f.pollWith(snap, f.peersAt(height = 1_200))
      val newPivot = BigInt(1_200) - Offset
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.PivotStateUnservable(
          entry.header.stateRoot.value,
          "lift-m10",
          3,
          SNAPSyncController.UnservableCause.PeerScarcity
        )
        expectBootstrapRequest(f, newPivot) // the refresh's header fetch

        snap ! SNAPSyncController.BootstrapComplete(None)
        expectBootstrapRequest(f, newPivot) // startSnapSync again: a fresh network pivot
        f.awaitProcessed(snap)
        log.contains(
          s"Pivot header bootstrap for block $newPivot returned no header. Falling back to full restart."
        ) shouldBe
          true
        log.contains(
          s"Restarting SNAP sync with a fresher pivot: pivot refresh bootstrap returned no header for $newPivot: lift-m10"
        ) shouldBe true
      }
      f.stops.receiveMessages(3, 10.seconds).map(_.kind).toSet shouldBe
        Set(ChildStopped.Account, ChildStopped.ByteCode, ChildStopped.Storage)
      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(newPivot)
    }

  it should "backtrack by pivotBlockOffset on a refresh bootstrap failure and retry at that block after 5 s" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(height = 1_000)
    val snap = entry.snap
    f.pollWith(snap, f.peersAt(height = 1_200))
    val newPivot = BigInt(1_200) - Offset
    val backtracked = newPivot - Offset
    snap ! SNAPSyncController.PivotStateUnservable(
      entry.header.stateRoot.value,
      "lift-m10",
      3,
      SNAPSyncController.UnservableCause.PeerScarcity
    )
    expectBootstrapRequest(f, newPivot)

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.PivotBootstrapFailed("lift-m10 failure")
      f.awaitProcessed(snap)
      log.contains(s"Backtracking pivot to $backtracked") shouldBe true
      log.contains("Retrying bootstrap at backtracked block") shouldBe false

      f.manualTime.timePasses(5.seconds)
      expectBootstrapRequest(f, backtracked)
      f.awaitProcessed(snap)
      log.contains(s"Retrying bootstrap at backtracked block $backtracked...") shouldBe true
    }
    // Nothing reached the children: the old pivot is kept until the backtracked header arrives.
    downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(entry.header.number.value)
  }

  it should "retry the refresh after 60 s when the backtracked block is not positive" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    // Network head 100: pivot 36. A refresh at head 110 targets 46, and 46 - 64 is negative.
    val entry = f.enterAccountSync(height = 100)
    val snap = entry.snap
    f.pollWith(snap, f.peersAt(height = 110))
    snap ! SNAPSyncController.PivotStateUnservable(
      entry.header.stateRoot.value,
      "lift-m10",
      3,
      SNAPSyncController.UnservableCause.PeerScarcity
    )
    expectBootstrapRequest(f, BigInt(110) - Offset)

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.PivotBootstrapFailed("lift-m10 failure")
      f.awaitProcessed(snap)
      log.contains("Backtracked pivot below 0 — falling back to network-best recalculation after 60s.") shouldBe true

      // The head moves on (still within the proactive-roll window of the old pivot); the 60 s replay refreshes on it.
      f.pollWith(snap, f.peersAt(height = 120))
      f.manualTime.timePasses(60.seconds)
      expectBootstrapRequest(f, BigInt(120) - Offset)
      f.awaitProcessed(snap)
      log.contains("Retrying pivot refresh after bootstrap failure...") shouldBe true
    }
  }

  // ── The readiness probe ───────────────────────────────────────────────────────────────────────

  /** Proactive roll: a stagnation tick sees the pivot more than 100 blocks behind the network head. The new pivot's
    * header is local, so the refresh goes straight to the readiness probe. Returns the probe's request id.
    */
  private def startProbedRoll(
      f: SnapControllerFixture,
      snap: ActorRef[SNAPSyncController.Command]
  ): (BigInt, ByteString) =
    val newPivot = BigInt(1_200) - Offset
    val newRoot = root(0x55)
    f.storeHeaderAt(newPivot, newRoot)
    f.pollWith(snap, f.peersAt(height = 1_200))
    downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))
    snap ! SNAPSyncController.CheckDownloadStagnation
    val probe = f.fishFor(f.npm) {
      case NetworkPeerManagerActor.SendMessageCmd(_: GetAccountRangeEnc, _) => true
      case _                                                                => false
    }
    val request = probe match
      case NetworkPeerManagerActor.SendMessageCmd(enc: GetAccountRangeEnc, _) => enc.underlyingMsg
      case other                                                              => fail(s"not a probe: $other")
    request.rootHash shouldBe newRoot
    (request.requestId, newRoot)

  private def forwardedResponses(
      f: SnapControllerFixture,
      snap: ActorRef[SNAPSyncController.Command]
  ): Seq[AccountRangeCoordinator.Command] =
    f.accountBefore(f.sendFence(snap)).collect { case m: AccountRangeCoordinator.AccountRangeResponseMsg => m }

  "SNAPSyncController's pivot readiness probe (ETC)" should
    "defer the roll on an empty probe answer, without forwarding the answer to the account child" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(height = 1_000)
      val snap = entry.snap
      val (requestId, _) = startProbedRoll(f, snap)

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.AccountRangeResponse(SNAP.AccountRange(requestId, Seq.empty, Seq.empty))
        f.awaitProcessed(snap)
        log.contains("[PIVOT-PROBE] Snapshot not indexed yet (attempt 1/5)") shouldBe true

        // The probe is cleared: its 15 s timeout no longer counts.
        f.manualTime.timePasses(15.seconds)
        f.awaitProcessed(snap)
        log.contains("[PIVOT-PROBE] Probe timed out") shouldBe false
      }
      forwardedResponses(f, snap) shouldBe empty
      downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(entry.header.number.value)
      f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(entry.header.stateRoot.value)
    }

  it should "forward a non-probe AccountRange while the probe is pending, and ignore the probe's timeout once it " +
    "has answered" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(height = 1_000)
      val snap = entry.snap
      val (requestId, newRoot) = startProbedRoll(f, snap)
      val accounts = Seq(ByteString(Array.fill(32)(0x01.toByte)) -> Account.empty())

      val other = SNAP.AccountRange(requestId + 1_000, accounts, Seq.empty)
      snap ! SNAPSyncController.AccountRangeResponse(other)
      f.awaitProcessed(snap)
      forwardedResponses(f, snap) shouldBe Seq(AccountRangeCoordinator.AccountRangeResponseMsg(other))
      downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.AccountRangeResponse(SNAP.AccountRange(requestId, accounts, Seq.empty))
        f.awaitProcessed(snap)
        log.contains("[PIVOT-PROBE] Snapshot ready at root") shouldBe true
        downloadChildRefreshes(f, snap) shouldBe ((
          Seq(AccountRangeCoordinator.PivotRefreshed(newRoot)),
          Seq(StorageRangeCoordinator.StoragePivotRefreshed(newRoot)),
          Seq(ByteCodeCoordinator.ByteCodePivotRefreshed)
        ))

        f.manualTime.timePasses(15.seconds)
        f.awaitProcessed(snap)
        log.contains("[PIVOT-PROBE] Probe timed out") shouldBe false
      }
      // The answered probe is not forwarded.
      forwardedResponses(f, snap) shouldBe empty
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(BigInt(1_200) - Offset)
      f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(newRoot)
    }

  // ── RetryPivotRefresh (PoS, StateHealing, stalled CL) ──────────────────────────────────────────

  "SNAPSyncController's counted RetryPivotRefresh on a PoS chain" should
    "keep the unservable-pivot fallback of the stateless attempt that armed it (#1555)" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStateHealing(PoSHeal, isPoS = true)
      // CL stalled at pivot + margin and no snap peer: neither the CL target nor the fallback is newer.
      clHint(f, snap, Pivot + Margin)

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.HealingAllPeersStateless
        f.awaitProcessed(snap)
        log.contains("Cannot re-peg heal root: no suitable SNAP peers available (attempt 1/10)") shouldBe true
      }

      // A snap peer now advertises pivot + 100. The replay's fallback is min(100, CL head + 128) - margin = pivot + 50;
      // without the unservable flag it would find no newer pivot.
      f.pollWith(snap, f.peersAt(height = (Pivot + 100).toInt))
      snap ! SNAPSyncController.RetryPivotRefresh
      f.parent.fishForMessage(10.seconds) {
        case SNAPSyncController.StartRegularSyncBootstrap(n) if n == Pivot + Margin => FishingOutcomes.complete
        case SNAPSyncController.StartRegularSyncBootstrap(n) => FishingOutcomes.fail(s"unexpected re-peg target $n")
        case _: SNAPSyncController.SnapSyncFinalized => FishingOutcomes.fail("lazy hand-off instead of a re-peg")
        case _                                       => FishingOutcomes.continueAndIgnore
      }
    }

  it should "count toward the re-peg budget: nine stateless reports and one counted replay exhaust it" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing(PoSHeal, isPoS = true)
    clHint(f, snap, Pivot + Margin)

    Using.resource(new SnapLogCapture) { log =>
      (1 to 9).foreach { _ =>
        snap ! SNAPSyncController.HealingAllPeersStateless
        f.awaitProcessed(snap)
      }
      log.contains("(attempt 9/10)") shouldBe true
      log.contains("[HEAL-REPEG] No servable root") shouldBe false

      snap ! SNAPSyncController.RetryPivotRefresh
      log.awaitLine("[HEAL-REPEG] No servable root for 10 consecutive re-peg attempts")
    }
  }

  // ── PivotStateUnservable ──────────────────────────────────────────────────────────────────────

  "SNAPSyncController's PivotStateUnservable arm" should
    "count a stateless report and refresh, then debounce a second one by re-arming the range coordinators" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(height = 1_000)
      val snap = entry.snap
      val root0 = entry.header.stateRoot.value
      f.pollWith(snap, f.peersAt(height = 1_200))

      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.PivotStateUnservable(root0, "lift-m10 first", 3)
        expectBootstrapRequest(f, BigInt(1_200) - Offset)
        f.awaitProcessed(snap)
        log.contains("Consecutive stateless pivot refreshes: 1/10") shouldBe true
        downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil)) // the header fetch is pending

        // Within MinPivotRestartInterval (30 s of wall-clock time; the two reports are milliseconds apart).
        snap ! SNAPSyncController.PivotStateUnservable(root0, "lift-m10 second", 4)
        f.awaitProcessed(snap)
        log.contains(
          "Debouncing PivotStateUnservable (refresh in flight, phase=AccountRangeSync, emptyResponses=4, " +
            "reason=lift-m10 second) — re-arming coordinators"
        ) shouldBe true
        log.contains("Consecutive stateless pivot refreshes: 2/") shouldBe false
        log.count("Refreshing pivot in-place:") shouldBe 1
      }
      downloadChildRefreshes(f, snap) shouldBe ((
        Seq(AccountRangeCoordinator.PivotRefreshed(root0)),
        Seq(StorageRangeCoordinator.StoragePivotRefreshed(root0)),
        Nil
      ))
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(entry.header.number.value)
    }

  it should "ignore a report outside the download phases" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing()
    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.PivotStateUnservable(Root, "lift-m10", 3)
      f.awaitProcessed(snap)
      log.contains("Ignoring PivotStateUnservable in phase=StateHealing (reason=lift-m10)") shouldBe true
      log.contains("Refreshing pivot in-place:") shouldBe false
      log.contains("Consecutive stateless pivot refreshes") shouldBe false
    }
  }

  // ── DelayedRestart ────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController's DelayedRestart arm" should
    "restart SNAP sync in ByteCodeAndStorageSync" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterStorageResume()
      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
      Using.resource(new SnapLogCapture) { log =>
        snap ! SNAPSyncController.DelayedRestart("lift-m10")
        f.awaitProcessed(snap)
        log.contains("Restarting SNAP sync with a fresher pivot: lift-m10") shouldBe true
      }
      f.fishFor(f.stops)(_.kind == ChildStopped.Storage)
      f.appStateStorage.isSnapSyncAccountsComplete() shouldBe false
    }

  it should "be ignored in StateHealing" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing()
    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.DelayedRestart("lift-m10")
      f.awaitProcessed(snap)
      log.contains("Restarting SNAP sync") shouldBe false
    }
    f.stops.expectNoMessage(300.millis)
    f.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
  }
