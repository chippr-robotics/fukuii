package com.chipprbots.ethereum.db.storage.pathstate

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T095): which changeset records a canonical commit deletes (data-model 4.5, 4.6). */
class RetentionPolicySpec extends AnyFlatSpec with Matchers:

  private def config(mode: String): StateConfig =
    StateConfig(
      ConfigFactory
        .parseString(s"""pruning.mode = "$mode"""")
        .withFallback(ConfigFactory.load().getConfig("fukuii"))
    )

  "Basic retention" should "keep the records of the last blocks, the anchor's own among them" taggedAs UnitTest in {
    val keepThree = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 3)
    // with block 10 the anchor: 8, 9 and 10 keep their records, so 8 is the floor
    keepThree.floor(10, None) shouldBe Some(BigInt(8))
    keepThree.floor(3, None) shouldBe Some(BigInt(1))
    // a chain shorter than what is kept deletes nothing
    keepThree.floor(2, None) shouldBe Some(BigInt(0))
    keepThree.floor(1, None) shouldBe Some(BigInt(0))
  }

  it should "never reach the block being committed, whatever it is told to keep" taggedAs UnitTest in {
    val keepNone = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 0)
    keepNone.floor(10, None) shouldBe Some(BigInt(10))
    val keepOne = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 1)
    keepOne.floor(10, None) shouldBe Some(BigInt(10))
  }

  it should "take what is kept from the configuration: the distance to the finalized block, or the floor without one" taggedAs
    UnitTest in {
      val basic = config("basic")
      val policy = RetentionPolicy(basic)
      // no finalized block: state.retention.without-finality
      policy.floor(10000, None) shouldBe Some(BigInt(10000 - basic.retention.withoutFinality + 1))
      // a finalized block 70 below: 70 and the margin
      policy.floor(10000, Some(9930)) shouldBe Some(BigInt(10000 - (70 + basic.retention.finalityMargin) + 1))
      // never fewer than the history
      policy.floor(10000, Some(9999)) shouldBe Some(BigInt(10000 - basic.pruning.history + 1))
    }

  "Archive retention" should "keep every record" taggedAs UnitTest in {
    RetentionPolicy(config("archive")).floor(10000, None) shouldBe None
    RetentionPolicy(config("archive")).floor(10000, Some(9000)) shouldBe None
    RetentionPolicy.KeepAll.floor(10000, None) shouldBe None
  }

  "A batch" should "prune a few heights while it follows the head, and many when the floor has jumped" taggedAs UnitTest in {
    val policy = RetentionPolicy.KeepAll
    policy.heightsPerBatch(1) shouldBe RetentionPolicy.FollowingHeights
    policy.heightsPerBatch(RetentionPolicy.FollowingHeights) shouldBe RetentionPolicy.FollowingHeights
    policy.heightsPerBatch(RetentionPolicy.FollowingHeights + 1) shouldBe RetentionPolicy.FollowingHeights + 1
    policy.heightsPerBatch(500) shouldBe 500
    policy.heightsPerBatch(BigInt(10).pow(12)) shouldBe RetentionPolicy.CatchUpHeights
  }
