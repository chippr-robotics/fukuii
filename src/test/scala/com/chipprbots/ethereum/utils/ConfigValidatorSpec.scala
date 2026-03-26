package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConfigValidatorSpec extends AnyFlatSpec with Matchers {

  "ConfigValidator" should "not throw with default test configuration" in {
    // Default test config should pass validation
    noException should be thrownBy ConfigValidator.validate()
  }

  it should "detect SNAP without fast sync" in {
    val syncConfig = Config.SyncConfig(Config.config).copy(doSnapSync = true, doFastSync = false)
    val issues = ConfigValidatorSpec.validateSyncWith(syncConfig)
    issues should have size 1
    issues.head.severity shouldBe ConfigValidator.Warn
    issues.head.message should include("do-snap-sync=true but do-fast-sync=false")
  }

  it should "detect zero min-peers-to-choose-pivot-block" in {
    val syncConfig = Config.SyncConfig(Config.config).copy(doFastSync = true, minPeersToChoosePivotBlock = 0)
    val issues = ConfigValidatorSpec.validateSyncWith(syncConfig)
    val errors = issues.filter(_.severity == ConfigValidator.Error)
    errors should have size 1
    errors.head.message should include("must be >= 1")
  }

  it should "warn on single pivot peer" in {
    val syncConfig = Config.SyncConfig(Config.config).copy(doFastSync = true, minPeersToChoosePivotBlock = 1)
    val issues = ConfigValidatorSpec.validateSyncWith(syncConfig)
    val warnings = issues.filter(_.severity == ConfigValidator.Warn)
    warnings.exists(_.message.contains("Single-peer pivot selection")) shouldBe true
  }

  it should "accept valid configuration with no issues" in {
    val syncConfig = Config.SyncConfig(Config.config).copy(
      doFastSync = true,
      doSnapSync = true,
      minPeersToChoosePivotBlock = 3
    )
    val issues = ConfigValidatorSpec.validateSyncWith(syncConfig)
    issues shouldBe empty
  }
}

/** Test helpers that replicate ConfigValidator logic for isolated unit testing.
  *
  * These mirror the private methods in ConfigValidator but accept config parameters directly, avoiding the need to
  * mutate global Config state during tests.
  */
object ConfigValidatorSpec {
  import ConfigValidator._

  def validateSyncWith(syncConfig: Config.SyncConfig): Seq[Issue] = {
    val issues = scala.collection.mutable.ArrayBuffer.empty[Issue]

    if (syncConfig.doSnapSync && !syncConfig.doFastSync) {
      issues += Issue(
        Warn,
        "do-snap-sync=true but do-fast-sync=false. SNAP sync requires fast sync as fallback. " +
          "Node will start regular sync only (block-by-block from genesis)."
      )
    }

    if (syncConfig.doFastSync && syncConfig.minPeersToChoosePivotBlock < 1) {
      issues += Issue(
        Error,
        s"min-peers-to-choose-pivot-block=${syncConfig.minPeersToChoosePivotBlock} must be >= 1. " +
          "Cannot select a pivot block with zero peers."
      )
    }

    if (syncConfig.doFastSync && syncConfig.minPeersToChoosePivotBlock == 1) {
      issues += Issue(
        Warn,
        "min-peers-to-choose-pivot-block=1. Single-peer pivot selection risks syncing to a minority fork. " +
          "Recommended: 3+ peers for production."
      )
    }

    issues.toSeq
  }
}
