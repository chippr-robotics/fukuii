package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** [[Tier]]: the size class of a terminal and what each class shows (spec 027 FR-003, research §3.1). */
class TierSpec extends AnyFlatSpec with Matchers:

  "Tier.of" should "pick S below 100 columns or below 32 rows" taggedAs (UnitTest) in {
    Tier.of(99, 40) shouldBe Tier.S
    Tier.of(100, 31) shouldBe Tier.S
    Tier.of(80, 24) shouldBe Tier.S
    Tier.of(1, 1) shouldBe Tier.S
    Tier.of(0, 0) shouldBe Tier.S
    Tier.of(400, 31) shouldBe Tier.S
  }

  it should "pick M from 100 columns and 32 rows up to the L boundary" taggedAs (UnitTest) in {
    Tier.of(100, 32) shouldBe Tier.M
    Tier.of(120, 40) shouldBe Tier.M
    Tier.of(139, 48) shouldBe Tier.M
    Tier.of(140, 44) shouldBe Tier.M
    Tier.of(400, 44) shouldBe Tier.M
  }

  it should "pick L from 140 columns and 45 rows" taggedAs (UnitTest) in {
    Tier.of(140, 45) shouldBe Tier.L
    Tier.of(160, 48) shouldBe Tier.L
    Tier.of(300, 100) shouldBe Tier.L
  }

  "Tier.logo" should "follow the §3.1 logo row" taggedAs (UnitTest) in {
    // S: none in the dashboard, the wordmark on the splash only.
    Tier.S.logo shouldBe TierLogo.WordmarkOnSplash
    // M: none in the dashboard, the 12-line logo on the splash.
    Tier.M.logo shouldBe TierLogo.SmallLogoOnSplash
    // L: the 12-line logo in a right-hand column of the Overview.
    Tier.L.logo shouldBe TierLogo.SmallLogoInOverview
    Tier.values.filter(_.logo.inDashboard) shouldBe Array(Tier.L)
  }

  "Tier.trackSlots" should "follow the §3.1 track lengths: 20, 40 and 60 cells" taggedAs (UnitTest) in {
    Tier.values.map(_.trackSlots) shouldBe Array(20, 40, 60)
  }
