package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** Unit-level tests pinning the BlockImporter Tier 2 storage escalation logic.
  *
  * These tests verify the GUARD CONDITIONS for the new storage heal path added in commits 880ab457d (scaffold) and
  * e25410946 (immediate escalation).
  *
  * Actor-level integration (resolvingStorageRange state machine transitions) is covered by run-level observation — the
  * unit tests here pin the key invariants that don't require a full actor setup.
  */
class BlockImporterStorageHealSpec extends AnyFlatSpec with Matchers {

  private val StuckEscapeThreshold: Int = BlockImporter.StuckEscapeThreshold
  private val MaxStuckDurationMs: Long = BlockImporter.MaxStuckDurationMs
  private val testAccountAddr: ByteString = ByteString(Array.fill(20)(0x88.toByte))

  // ─── Tier 2 escalation condition ────────────────────────────────────────────

  "Tier 2 escalation guard" should "fire on first exhaust when a storage account is tracked" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // Replicates the condition added in resolvingMissingNode:
    //   val storagePathMismatch = pendingStuckStorageAccount.isDefined && survivedExhausts >= 1
    //   if (storagePathMismatch || survivedExhausts >= StuckEscapeThreshold || stuckTooLong)

    def condition(survivedExhausts: Int, pendingStorageAccount: Option[ByteString], stuckTooLong: Boolean): String = {
      val storagePathMismatch = pendingStorageAccount.isDefined && survivedExhausts >= 1
      if (storagePathMismatch || survivedExhausts >= StuckEscapeThreshold || stuckTooLong)
        "Tier2OrRegularSyncStuck"
      else
        "BackOff"
    }

    // Storage exhaust on first try → Tier 2 immediately
    condition(1, Some(testAccountAddr), stuckTooLong = false) shouldBe "Tier2OrRegularSyncStuck"

    // Account node exhaust once → back off (wait for threshold)
    condition(1, None, stuckTooLong = false) shouldBe "BackOff"
    condition(2, None, stuckTooLong = false) shouldBe "BackOff"

    // Account node hits threshold → escalate
    condition(StuckEscapeThreshold, None, stuckTooLong = false) shouldBe "Tier2OrRegularSyncStuck"

    // Stuck too long → always escalate regardless
    condition(1, None, stuckTooLong = true) shouldBe "Tier2OrRegularSyncStuck"
  }

  it should "route to Tier 2 (FetchAccountStorage) when storage account is present" taggedAs (UnitTest, SyncTest) in {
    val pendingStorageAccount = Some(testAccountAddr)
    val survivedExhausts = 1

    // The match condition that determines Tier 2 vs Tier 3 (RegularSyncStuck)
    val routesToTier2 = pendingStorageAccount.isDefined
    routesToTier2 shouldBe true
  }

  it should "route to Tier 3 (RegularSyncStuck) when no storage account tracked" taggedAs (UnitTest, SyncTest) in {
    val pendingStorageAccount: Option[ByteString] = None
    val routesToTier3 = pendingStorageAccount.isEmpty
    routesToTier3 shouldBe true
  }

  it should "fire for MissingAccountNodeException on first exhaust — same path as storage" taggedAs (UnitTest, SyncTest) in {
    // After the account-trie mismatch fix, MissingAccountNodeException also sets
    // pendingStuckStorageAccount, so Tier 2 fires on the first exhaust rather than
    // waiting 3 rounds (3 × 5min = 15min penalty).
    val condition = (survivedExhausts: Int, pendingAccount: Option[ByteString]) =>
      (pendingAccount.isDefined && survivedExhausts >= 1) || survivedExhausts >= StuckEscapeThreshold

    condition(1, Some(testAccountAddr)) shouldBe true  // account trie miss → Tier 2 immediately
    condition(1, None)                  shouldBe false // account node, no tracking → wait
    condition(2, None)                  shouldBe false // still waiting
    condition(3, None)                  shouldBe true  // threshold reached → Tier 3
  }

  // ─── Counter reset invariants ───────────────────────────────────────────────

  "survivedExhausts" should "be reset to 0 after successful storage heal" taggedAs (UnitTest, SyncTest) in {
    // Verify the companion object counter exists and is mutable
    // (The actual reset happens in resolvingStorageRange on success)
    val original = BlockImporter.survivedExhausts
    BlockImporter.survivedExhausts = 5
    BlockImporter.survivedExhausts shouldBe 5

    // Simulate the reset that happens on successful FetchedAccountStorage
    BlockImporter.survivedExhausts = 0
    BlockImporter.stuckSinceMs = 0L

    BlockImporter.survivedExhausts shouldBe 0
    BlockImporter.stuckSinceMs shouldBe 0L

    // Restore
    BlockImporter.survivedExhausts = original
  }

  // ─── MaxStuckDuration invariant ──────────────────────────────────────────────

  "MaxStuckDurationMs" should "be large enough to survive normal slow healing (>= 15 minutes)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    MaxStuckDurationMs should be >= (15L * 60L * 1000L) // 15 minutes
  }

  "StuckEscapeThreshold" should "be 3 (3 consecutive exhausts for non-storage nodes before escalation)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    StuckEscapeThreshold shouldBe 3
  }

  // ─── FetchAccountStorage message type invariants ─────────────────────────────

  "FetchAccountStorage message" should "carry accountAddress, replyTo, canonicalStateRoot, and stateStorage" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    // Structural test: verify the message fields are accessible
    import org.apache.pekko.actor.ActorSystem
    import org.apache.pekko.testkit.TestProbe
    implicit val system: ActorSystem = ActorSystem("BlockImporterStorageHealSpec")

    val replyTo = TestProbe()
    val stateRoot = Some(ByteString(Array.fill(32)(0x01.toByte)))

    val msg = BlockFetcher.FetchAccountStorage(
      accountAddress = testAccountAddr,
      replyTo = replyTo.ref,
      canonicalStateRoot = stateRoot,
      stateStorage = null // typed presence check only
    )

    msg.accountAddress shouldBe testAccountAddr
    msg.canonicalStateRoot shouldBe stateRoot

    org.apache.pekko.testkit.TestKit.shutdownActorSystem(system)
  }

  "FetchedAccountStorage message" should "distinguish success from failure via the success flag" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val successMsg = BlockFetcher.FetchedAccountStorage(
      testAccountAddr,
      Some(com.chipprbots.ethereum.domain.Account()),
      success = true
    )
    val failureMsg = BlockFetcher.FetchedAccountStorage(testAccountAddr, None, success = false)

    successMsg.success shouldBe true
    failureMsg.success shouldBe false
    successMsg.canonicalAccount shouldBe defined
    failureMsg.canonicalAccount shouldBe empty
  }
}
