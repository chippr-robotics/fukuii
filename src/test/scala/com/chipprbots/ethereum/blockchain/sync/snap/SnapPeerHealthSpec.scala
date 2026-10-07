package com.chipprbots.ethereum.blockchain.sync.snap

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Liveness scoring for SNAP peers (Sepolia 2026-10-07: one peer answered 0 of 386 storage requests and was revived by
  * the cooldown floor 230 times).
  */
class SnapPeerHealthSpec extends AnyFlatSpec with Matchers:

  private val peer = "aedda9"
  private def health() = new SnapPeerHealth(timeoutThreshold = 3, basePenalty = 2.minutes, maxPenalty = 30.minutes)

  "SnapPeerHealth" should "demote a peer after N consecutive timeouts with no success" taggedAs UnitTest in {
    val h = health()
    h.recordTimeout(peer, nowMs = 0L) shouldBe None
    h.recordTimeout(peer, nowMs = 0L) shouldBe None
    h.isPenalised(peer, 0L) shouldBe false
    h.recordTimeout(peer, nowMs = 0L) shouldBe Some(2.minutes)
    h.isPenalised(peer, 1L) shouldBe true
    h.isOnProbation(peer) shouldBe true
    h.penaltyUntilMs(peer) shouldBe 2.minutes.toMillis
  }

  it should "not demote a peer whose timeouts are interleaved with successes" taggedAs UnitTest in {
    val h = health()
    (1 to 10).foreach { _ =>
      h.recordTimeout(peer, 0L)
      h.recordTimeout(peer, 0L)
      h.recordSuccess(peer)
    }
    h.isPenalised(peer, 0L) shouldBe false
    h.isOnProbation(peer) shouldBe false
    h.trackedCount shouldBe 0 // fully healthy peers are not retained
  }

  it should "back off exponentially, capped, when a probation request times out again" taggedAs UnitTest in {
    val h = health()
    (1 to 3).foreach(_ => h.recordTimeout(peer, 0L))
    // Lapsed penalty: a single further timeout re-penalises immediately, doubled.
    val t1 = 2.minutes.toMillis
    h.isPenalised(peer, t1) shouldBe false
    h.recordTimeout(peer, t1) shouldBe Some(4.minutes)
    val t2 = t1 + 4.minutes.toMillis
    h.recordTimeout(peer, t2) shouldBe Some(8.minutes)
    val t3 = t2 + 8.minutes.toMillis
    h.recordTimeout(peer, t3) shouldBe Some(16.minutes)
    val t4 = t3 + 16.minutes.toMillis
    h.recordTimeout(peer, t4) shouldBe Some(30.minutes) // capped
    val t5 = t4 + 30.minutes.toMillis
    h.recordTimeout(peer, t5) shouldBe Some(30.minutes)
  }

  it should "ignore timeouts that arrive while the peer is already penalised" taggedAs UnitTest in {
    val h = health()
    (1 to 3).foreach(_ => h.recordTimeout(peer, 0L))
    // Other in-flight requests to the same peer time out moments later: same penalty, no escalation.
    h.recordTimeout(peer, 10L) shouldBe None
    h.recordTimeout(peer, 20L) shouldBe None
    h.level(peer) shouldBe 1
    h.penaltyUntilMs(peer) shouldBe 2.minutes.toMillis
  }

  it should "recover on success: clear the penalty, end probation and decay the level" taggedAs UnitTest in {
    val h = health()
    (1 to 3).foreach(_ => h.recordTimeout(peer, 0L))
    h.recordTimeout(peer, 2.minutes.toMillis) // level 2
    h.recordSuccess(peer) shouldBe Some(2)
    h.isPenalised(peer, 2.minutes.toMillis + 1) shouldBe false
    h.isOnProbation(peer) shouldBe false
    h.level(peer) shouldBe 1
    // A later relapse needs the full threshold again, and resumes from the decayed level.
    h.recordTimeout(peer, 10.minutes.toMillis) shouldBe None
    h.recordTimeout(peer, 10.minutes.toMillis) shouldBe None
    h.recordTimeout(peer, 10.minutes.toMillis) shouldBe Some(4.minutes)
    h.recordSuccess(peer) shouldBe Some(2)
    h.recordSuccess(peer) shouldBe Some(1)
    h.recordSuccess(peer) shouldBe None
    h.trackedCount shouldBe 0
  }

  it should "stay bounded" taggedAs UnitTest in {
    val h = new SnapPeerHealth(maxTracked = 3)
    (1 to 10).foreach(i => h.recordTimeout("p" + i, 0L))
    h.trackedCount shouldBe 3
    h.consecutiveTimeouts("p10") shouldBe 1
    h.consecutiveTimeouts("p1") shouldBe 0
  }

  it should "reject nonsensical bounds loudly" taggedAs UnitTest in {
    an[IllegalArgumentException] should be thrownBy new SnapPeerHealth(timeoutThreshold = 0)
    an[IllegalArgumentException] should be thrownBy new SnapPeerHealth(basePenalty = 5.minutes, maxPenalty = 1.minute)
  }

  // Storage in-flight budget during account sync

  "SNAPSyncConfig.fromConfig" should "default the storage in-flight budget during account sync to 3" taggedAs UnitTest in {
    val sync = ConfigFactory.load().getConfig("fukuii.sync")
    val cfg = SNAPSyncConfig.fromConfig(sync)
    cfg.storageMaxInFlightPerPeerDuringAccounts shouldBe 3
    SNAPSyncController.storageInFlightDuringAccounts(cfg) shouldBe 3
  }

  it should "read an override and clamp it to [1, max-inflight-per-peer]" taggedAs UnitTest in {
    val sync = ConfigFactory.load().getConfig("fukuii.sync")
    def withValue(n: Int) = SNAPSyncConfig.fromConfig(
      sync.withValue("snap-sync.storage-max-inflight-per-peer-during-accounts", ConfigValueFactory.fromAnyRef(n))
    )
    withValue(4).storageMaxInFlightPerPeerDuringAccounts shouldBe 4
    SNAPSyncController.storageInFlightDuringAccounts(withValue(4)) shouldBe 4
    SNAPSyncController.storageInFlightDuringAccounts(withValue(0)) shouldBe 1
    SNAPSyncController.storageInFlightDuringAccounts(withValue(50)) shouldBe withValue(50).maxInFlightPerPeer
  }
