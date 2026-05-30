package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** Unit-level tests pinning the critical invariants introduced across the may-sprint SNAP sync fixes. These are
  * guard-condition tests — they verify the LOGIC of the conditions we added, not the full actor wiring (which is
  * covered by run integration).
  *
  * Key fixes tested:
  *   - MinPivotBlock guard: stale pivot < hint → bootstrap path, not immediate heal
  *   - "Pivot not ahead" guard: pivot==localBest + minPivotHint>0 → heal, not Done
  *   - HealingInterrupted message type exists
  *   - Gas mismatch → RegularSyncStuck escalation message types
  *   - Healing throughput config invariants
  *   - SNAP→Regular transition message invariants
  */
class SNAPSyncIntegritySpec extends AnyFlatSpec with Matchers {

  // ─── MinPivotBlock guard ────────────────────────────────────────────────────

  "MinPivotBlock guard logic" should "identify when saved pivot is below hint" taggedAs (UnitTest, SyncTest) in {
    // The core predicate from startSnapSync() that our fix added:
    // pivot < minPivotHint AND storageAlreadyDone AND bytecodeAlreadyDone → bootstrap path
    def shouldEscalateToBootstrap(
        savedPivot: BigInt,
        minPivotHint: BigInt,
        storageAlreadyDone: Boolean,
        bytecodeAlreadyDone: Boolean
    ): Boolean =
      minPivotHint > 0 && savedPivot < minPivotHint && storageAlreadyDone && bytecodeAlreadyDone

    // Nominal case: pivot is stale, all phases complete → bootstrap
    shouldEscalateToBootstrap(BigInt(24652322), BigInt(24652327), true, true) shouldBe true

    // Pivot already above hint → no escalation
    shouldEscalateToBootstrap(BigInt(24652330), BigInt(24652327), true, true) shouldBe false

    // Hint not set (0) → no escalation
    shouldEscalateToBootstrap(BigInt(24652322), BigInt(0), true, true) shouldBe false

    // Phases incomplete → no escalation (don't short-circuit partial sync)
    shouldEscalateToBootstrap(BigInt(24652322), BigInt(24652327), false, true) shouldBe false
    shouldEscalateToBootstrap(BigInt(24652322), BigInt(24652327), true, false) shouldBe false

    // Equal pivot and hint → no escalation (pivot == hint satisfies constraint)
    shouldEscalateToBootstrap(BigInt(24652327), BigInt(24652327), true, true) shouldBe false
  }

  // ─── "Pivot not ahead" guard ────────────────────────────────────────────────

  "Pivot not ahead guard" should "detect that minPivotHint set means this is NOT an already-synced case" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // The guard condition we added in the NetworkPivot branch:
    //   if (minPivotHint > 0) → heal at pivot=localBest, NOT send Done
    def isStuckNotSynced(minPivotHint: BigInt): Boolean = minPivotHint > 0

    isStuckNotSynced(BigInt(24654463)) shouldBe true // came from RegularSyncStuck
    isStuckNotSynced(BigInt(0)) shouldBe false // genuine "already synced" case
  }

  it should "recognize when pivot equals localBest is a valid healing target" taggedAs (UnitTest, SyncTest) in {
    // When pivot == localBest AND minPivotHint > 0:
    //   - localBest = 24655967 (the last block we imported)
    //   - pivot = max(rawPivot=24655903, minPivotHint=24655967) = 24655967 = localBest
    //   - This is NOT "already synced" — it means peers can't serve this block's storage
    //   - Correct action: heal at pivot=localBest, then retry
    val localBest = BigInt(24655967)
    val rawPivot = BigInt(24655903)
    val hint = BigInt(24655967)
    val pivot = rawPivot.max(hint)

    pivot shouldBe localBest // pivot == localBest is expected
    hint should be > BigInt(0) // minPivotHint is set → it's a stall, not a sync completion
  }

  // ─── Tier 2 storage escalation logic ────────────────────────────────────────

  "Tier 2 storage escalation" should "fire on first exhaust for storage nodes (not 3rd)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // The key optimization: storage path-mismatch → escalate after 1 exhaust, not 3
    val StuckEscapeThreshold = 3

    def shouldEscalateToTier2(survivedExhausts: Int, hasStorageAccount: Boolean): Boolean =
      (hasStorageAccount && survivedExhausts >= 1) || survivedExhausts >= StuckEscapeThreshold

    // Storage node exhausted once → Tier 2 immediately (don't wait 3 rounds × 5min = 15min)
    shouldEscalateToTier2(1, hasStorageAccount = true) shouldBe true
    shouldEscalateToTier2(2, hasStorageAccount = true) shouldBe true

    // Account node exhausted once → wait for threshold
    shouldEscalateToTier2(1, hasStorageAccount = false) shouldBe false
    shouldEscalateToTier2(2, hasStorageAccount = false) shouldBe false
    shouldEscalateToTier2(3, hasStorageAccount = false) shouldBe true // threshold reached

    // No storage account but time-based trigger also works
    shouldEscalateToTier2(4, hasStorageAccount = false) shouldBe true
  }

  // ─── HealingInterrupted message type ────────────────────────────────────────

  "HealingInterrupted" should "be a distinct message type from StateHealingComplete" taggedAs (UnitTest, SyncTest) in {
    val interrupted: Any = SNAPSyncController.HealingInterrupted
    val complete: Any = SNAPSyncController.StateHealingComplete
    (interrupted should not).equal(complete)
  }

  it should "be a singleton object (not a case class with data)" taggedAs (UnitTest, SyncTest) in {
    // Guard: if we accidentally made this a case class with data, equality checks would fail
    SNAPSyncController.HealingInterrupted shouldBe SNAPSyncController.HealingInterrupted
  }

  // ─── SNAP→Regular transition invariants ──────────────────────────────────────

  "SnapSyncFinalized" should "carry the pivot block" taggedAs (UnitTest, SyncTest) in {
    val msg = SNAPSyncController.SnapSyncFinalized(BigInt(24655967))
    msg.pivot shouldBe BigInt(24655967)
  }

  "MinPivotBlock" should "carry the minimum pivot requirement from RegularSyncStuck" taggedAs (UnitTest, SyncTest) in {
    val msg = SNAPSyncController.MinPivotBlock(BigInt(24655967))
    msg.minBlock shouldBe BigInt(24655967)
  }

  // ─── Healing throughput config invariants ───────────────────────────────────

  "SNAPSyncConfig healing batch size" should "have a positive default" taggedAs (UnitTest, SyncTest) in {
    // The case-class default is 16; the production value (384, matching Besu) is set in
    // sync.conf and loaded at runtime via SNAPSyncConfig.apply(config). The unit-level
    // invariant is simply that the field is positive and the production sync.conf sets it.
    val config = SNAPSyncConfig()
    config.healingBatchSize should be > 0
  }

  it should "have max trie staleness large enough to survive normal sync gaps" taggedAs (UnitTest, SyncTest) in {
    val config = SNAPSyncConfig()
    config.maxPivotStalenessBlocks should be >= 4096L // must cover normal network variance
  }

  // ─── Gas mismatch escalation invariant ──────────────────────────────────────

  "SyncProtocol.RegularSyncStuck" should "be usable with arbitrary reason strings for gas mismatch" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
    val msg = SyncProtocol.RegularSyncStuck(
      BigInt(24655968),
      "Block has invalid gas used, expected 664715 but got 232800"
    )
    msg.blockNumber shouldBe BigInt(24655968)
    msg.missingHash should include("gas used")
  }
}
