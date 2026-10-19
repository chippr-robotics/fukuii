package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
class JvmFlagGuardSpec extends AnyFlatSpec with Matchers:

  "JvmFlagGuard.isAtRisk" should "flag JDK 25 started with no flags (option hidden, diagnostics locked)" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(25, None, Some("false")) shouldBe true
  }

  it should "flag JDK 25 with diagnostics unlocked but the optimisation left on" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(25, Some("true"), Some("true")) shouldBe true
  }

  it should "pass JDK 25 started with the workaround flags" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(25, Some("false"), Some("true")) shouldBe false
  }

  it should "pass JDKs before 23, which lack the optimisation" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(21, None, Some("false")) shouldBe false
    JvmFlagGuard.isAtRisk(22, Some("true"), Some("true")) shouldBe false
  }

  it should "flag the first affected release" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(23, None, Some("false")) shouldBe true
  }

  it should "stay quiet on a non-HotSpot JVM (no diagnostic bean)" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(25, None, None) shouldBe false
  }

  it should "stay quiet when diagnostics are unlocked but the option does not exist" taggedAs UnitTest in {
    JvmFlagGuard.isAtRisk(28, None, Some("true")) shouldBe false
  }

  "JvmFlagGuard.check" should "not throw on the running JVM" taggedAs UnitTest in {
    noException should be thrownBy JvmFlagGuard.check()
  }
