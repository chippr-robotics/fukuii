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

  "JvmFlagGuard.verdict" should "be Safe when not at risk, whether or not the escape hatch is set" taggedAs UnitTest in {
    JvmFlagGuard.verdict(atRisk = false, allowed = false) shouldBe JvmFlagGuard.Verdict.Safe
    JvmFlagGuard.verdict(atRisk = false, allowed = true) shouldBe JvmFlagGuard.Verdict.Safe
  }

  it should "refuse an at-risk JVM by default" taggedAs UnitTest in {
    JvmFlagGuard.verdict(atRisk = true, allowed = false) shouldBe JvmFlagGuard.Verdict.Refuse
  }

  it should "start an at-risk JVM only with the escape hatch" taggedAs UnitTest in {
    JvmFlagGuard.verdict(atRisk = true, allowed = true) shouldBe JvmFlagGuard.Verdict.AllowedAtRisk
  }

  "JvmFlagGuard.allowedBy" should "accept true from the property or the environment, property first" taggedAs UnitTest in {
    JvmFlagGuard.allowedBy(Some("true"), None) shouldBe true
    JvmFlagGuard.allowedBy(None, Some("TRUE")) shouldBe true
    JvmFlagGuard.allowedBy(Some("false"), Some("true")) shouldBe false
    JvmFlagGuard.allowedBy(None, None) shouldBe false
    JvmFlagGuard.allowedBy(Some("yes"), None) shouldBe false
  }

  "JvmFlagGuard.evaluate" should "find the test JVM safe (build.sbt passes the workaround to forked tests)" taggedAs UnitTest in {
    JvmFlagGuard.evaluate() shouldBe JvmFlagGuard.Verdict.Safe
  }
