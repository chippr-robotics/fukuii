package com.chipprbots.ethereum.db.storage.pathstate

import scala.concurrent.duration.*

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.pathstate.StateConfig.Mode
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig.ReorgDepth
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T069: the `fukuii.state` keys of data-model 10 load with the defaults the node ships, the
  * retention floor is what the reorg depth resolves to, and the retired pruning mode is refused.
  */
class StateConfigSpec extends AnyFlatSpec with Matchers:

  /** The node's configuration with `overrides` on top, resolved as the node resolves it. */
  private def fukuii(overrides: String = ""): Config =
    ConfigFactory
      .load(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.parseResources("application.conf")))
      .getConfig("fukuii")

  "The state configuration" should "load every key with the default the node ships" taggedAs UnitTest in {
    val config = StateConfig(fukuii())
    config.pathLive shouldBe false
    config.pruning shouldBe StateConfig.Pruning(Mode.Basic, 64)
    config.changeset shouldBe StateConfig.Changeset(maxEntriesPerOwnerDelete = 1000000L, maxRecordBytes = 268435456L)
    config.retention shouldBe StateConfig.Retention(finalityMargin = 16, maxBlocks = 512)
    config.overlay shouldBe StateConfig.Overlay(
      maxBytes = 268435456L,
      pinnedMaxBytes = 268435456L,
      maxForwardLayers = 32,
      residentReverseBlocks = 16,
      maxLayerBytes = 67108864L,
      maxViewAgeMoves = 8
    )
    config.maxBlocksPerMoveBatch shouldBe 256
    config.archiveViewReach shouldBe 1024
    config.recovery shouldBe StateConfig.Recovery(maxBlocks = 64, escalationRefusals = 64, escalationCooldown = 6.hours)
    config.reorgMaxDepth shouldBe ReorgDepth.Floor
  }

  it should "have no key the code does not read" taggedAs UnitTest in {
    import scala.jdk.CollectionConverters.*
    fukuii().getConfig("state").entrySet.asScala.map(_.getKey).toSet shouldBe Set(
      "path-live",
      "changeset.max-entries-per-owner-delete",
      "changeset.max-record-bytes",
      "retention.finality-margin",
      "retention.max-blocks",
      "overlay.max-bytes",
      "overlay.pinned-max-bytes",
      "overlay.max-forward-layers",
      "overlay.resident-reverse-blocks",
      "overlay.max-layer-bytes",
      "overlay.max-view-age-moves",
      "move.max-blocks-per-batch",
      "archive.view-reach",
      "recovery.max-blocks",
      "recovery.escalation-refusals",
      "recovery.escalation-cooldown",
      "reorg.max-depth"
    )
  }

  it should "let a re-execution cover as many blocks as the history, whatever the history is set to" taggedAs
    UnitTest in {
      StateConfig(fukuii("fukuii.pruning.history = 128")).recovery.maxBlocks shouldBe 128
    }

  it should "keep changesets for the history, raised to reach the finalized block with a margin, up to the bound" taggedAs
    UnitTest in {
      val config = StateConfig(fukuii())
      // a chain with no finalized block (ETC) keeps the history
      config.retained(anchorNumber = 1000, finalized = None) shouldBe 64
      // a finalized block close behind: the history still
      config.retained(1000, Some(BigInt(990))) shouldBe 64
      // the usual lag of a consensus client, 90 blocks: 90 + the margin of 16
      config.retained(1000, Some(BigInt(910))) shouldBe 106
      // finality that has stopped: the bound
      config.retained(5000, Some(BigInt(1000))) shouldBe 512
      // an anchor behind the finalized block, while the node catches up: the history
      config.retained(1000, Some(BigInt(5000))) shouldBe 64
      // a bound below the history is refused when the configuration is read: the history is the least that is kept
      an[IllegalArgumentException] should be thrownBy StateConfig(fukuii("fukuii.state.retention.max-blocks = 32"))
    }

  it should "resolve the reorg depth to the retention floor, and honour a number only below it" taggedAs UnitTest in {
    val floor = StateConfig(fukuii())
    floor.maxReorgDepth(1000, None) shouldBe 64
    // a reorg at the finalized lag is inside the floor, where a bare history would refuse it
    floor.maxReorgDepth(1000, Some(BigInt(910))) shouldBe 106

    val shallow = StateConfig(fukuii("fukuii.state.reorg.max-depth = 10"))
    shallow.reorgMaxDepth shouldBe ReorgDepth.Fixed(10)
    shallow.maxReorgDepth(1000, Some(BigInt(910))) shouldBe 10

    // deeper than the records reach cannot be honoured
    StateConfig(fukuii("fukuii.state.reorg.max-depth = 5000")).maxReorgDepth(1000, None) shouldBe 64

    an[IllegalArgumentException] should be thrownBy StateConfig(fukuii("fukuii.state.reorg.max-depth = deepest"))
    an[IllegalArgumentException] should be thrownBy StateConfig(fukuii("fukuii.state.reorg.max-depth = 0"))
  }

  it should "take the two pruning modes of the unified layout, and refuse the retired one by name" taggedAs UnitTest in {
    StateConfig(fukuii("""fukuii.pruning.mode = "archive"""")).pruning.mode shouldBe Mode.Archive
    StateConfig(fukuii("""fukuii.pruning.mode = "basic"""")).pruning.mode shouldBe Mode.Basic

    val retired = the[IllegalArgumentException] thrownBy StateConfig(fukuii("""fukuii.pruning.mode = "inmemory""""))
    retired.getMessage should (include("inmemory").and(include("basic")).and(include("archive")))
    an[IllegalArgumentException] should be thrownBy StateConfig(fukuii("""fukuii.pruning.mode = "aggressive""""))
  }
