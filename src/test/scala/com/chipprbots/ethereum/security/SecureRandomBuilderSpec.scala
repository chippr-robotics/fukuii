package com.chipprbots.ethereum.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.UnitTest

/** B-339: the SecureRandom a builder makes follows the `secure-random-algo` it is given (a node passes its own
  * configuration's), not the process's global configuration.
  */
class SecureRandomBuilderSpec extends AnyFlatSpec with Matchers:

  private def builder(algo: Option[String]): SecureRandomBuilder =
    new SecureRandomBuilder:
      override protected def secureRandomAlgo: Option[String] = algo

  "SecureRandomBuilder" should "use the algorithm its configuration names" taggedAs UnitTest in {
    builder(Some("SHA1PRNG")).secureRandom.getAlgorithm shouldBe "SHA1PRNG"
  }

  it should "fall back to the default SecureRandom for an algorithm the JDK does not have" taggedAs UnitTest in {
    val default = new java.security.SecureRandom().getAlgorithm
    builder(Some("NO-SUCH-ALGORITHM")).secureRandom.getAlgorithm shouldBe default
  }

  it should "use the default SecureRandom when none is configured" taggedAs UnitTest in {
    builder(None).secureRandom.getAlgorithm shouldBe new java.security.SecureRandom().getAlgorithm
  }
