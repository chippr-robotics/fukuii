package com.chipprbots.ethereum.blockchain.sync.snap

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class SNAPSyncConfigSpec extends AnyFlatSpec with Matchers {

  // ========================================
  // Default Values
  // ========================================

  "SNAPSyncConfig" should "have sensible defaults" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.enabled shouldBe true
    config.pivotBlockOffset shouldBe 64
    config.maxPivotStalenessBlocks shouldBe 4096
    config.accountConcurrency shouldBe 16
    config.storageConcurrency shouldBe 16
    config.storageBatchSize shouldBe 128
    config.healingBatchSize shouldBe 16
    config.healingConcurrency shouldBe 16
    config.stateValidationEnabled shouldBe true
    config.maxRetries shouldBe 3
    config.timeout shouldBe 30.seconds
    config.maxSnapSyncFailures shouldBe 5
    config.snapCapabilityGracePeriod shouldBe 30.seconds
    config.accountStagnationTimeout shouldBe 10.minutes
    config.maxInFlightPerPeer shouldBe 5
    config.accountTrieFlushThreshold shouldBe 50000
    config.chainDownloadEnabled shouldBe true
    config.deferredMerkleization shouldBe true
  }

  // ========================================
  // Custom Config
  // ========================================

  it should "accept custom values" taggedAs UnitTest in {
    val config = SNAPSyncConfig(
      enabled = false,
      pivotBlockOffset = 128,
      accountConcurrency = 8,
      storageConcurrency = 4,
      storageBatchSize = 64,
      healingBatchSize = 32,
      healingConcurrency = 8,
      stateValidationEnabled = false,
      maxRetries = 5,
      timeout = 60.seconds,
      maxSnapSyncFailures = 10,
      snapCapabilityGracePeriod = 60.seconds,
      accountStagnationTimeout = 20.minutes,
      maxInFlightPerPeer = 10,
      deferredMerkleization = false
    )

    config.enabled shouldBe false
    config.pivotBlockOffset shouldBe 128
    config.accountConcurrency shouldBe 8
    config.storageConcurrency shouldBe 4
    config.maxRetries shouldBe 5
    config.maxSnapSyncFailures shouldBe 10
    config.deferredMerkleization shouldBe false
  }

  // ========================================
  // Response Byte Limits
  // ========================================

  it should "have correct response byte defaults" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.storageInitialResponseBytes shouldBe 1048576 // 1MB
    config.storageMinResponseBytes shouldBe 131072 // 128KB
    config.accountInitialResponseBytes shouldBe 524288 // 512KB
    config.accountMinResponseBytes shouldBe 102400 // 100KB
  }

  // ========================================
  // Chain Download Config
  // ========================================

  it should "have chain download settings" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.chainDownloadEnabled shouldBe true
    config.chainDownloadMaxConcurrentRequests shouldBe 2
    config.chainDownloadBoostedConcurrentRequests shouldBe 16
    config.chainDownloadTimeout shouldBe 10.seconds
  }

  // ========================================
  // Snap Peer Management
  // ========================================

  it should "have snap peer management settings" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.minSnapPeers shouldBe 3
    config.snapPeerEvictionInterval shouldBe 15.seconds
    config.maxEvictionsPerCycle shouldBe 3
  }

  // ========================================
  // Positive Value Constraints
  // ========================================

  it should "have positive concurrency values" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.accountConcurrency should be > 0
    config.storageConcurrency should be > 0
    config.healingConcurrency should be > 0
    config.healingBatchSize should be > 0
    config.storageBatchSize should be > 0
    config.maxRetries should be > 0
    config.maxSnapSyncFailures should be > 0
    config.maxInFlightPerPeer should be > 0
    config.accountTrieFlushThreshold should be > 0
  }

  it should "have positive timeout durations" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.timeout.toMillis should be > 0L
    config.snapCapabilityGracePeriod.toMillis should be > 0L
    config.accountStagnationTimeout.toMillis should be > 0L
    config.chainDownloadTimeout.toMillis should be > 0L
    config.snapPeerEvictionInterval.toMillis should be > 0L
  }

  it should "have positive byte limits" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.storageInitialResponseBytes should be > 0
    config.storageMinResponseBytes should be > 0
    config.accountInitialResponseBytes should be > 0
    config.accountMinResponseBytes should be > 0
  }

  it should "have initial response bytes >= min response bytes" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.storageInitialResponseBytes should be >= config.storageMinResponseBytes
    config.accountInitialResponseBytes should be >= config.accountMinResponseBytes
  }

  // ========================================
  // Geth Alignment
  // ========================================

  it should "align with geth defaults for critical sync parameters" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    // Geth uses 16 concurrent account tasks
    config.accountConcurrency shouldBe 16

    // Geth uses 5 requests per peer max
    config.maxInFlightPerPeer shouldBe 5

    // Geth pivot offset is 64 blocks
    config.pivotBlockOffset shouldBe 64
  }
}
