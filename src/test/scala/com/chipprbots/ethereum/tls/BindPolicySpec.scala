package com.chipprbots.ethereum.tls

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-018, Edge Cases "What counts as loopback": an interface is loopback only when every address it resolves
  * to is; the wildcard addresses and a LAN address are not; a name that does not resolve is an error.
  */
class BindPolicySpec extends AnyFlatSpec with Matchers:

  "BindPolicy.isLoopback" should "accept localhost and the loopback addresses" taggedAs UnitTest in {
    Seq("localhost", "127.0.0.1", "127.0.0.2", "::1", "[::1]", " localhost ").foreach { i =>
      withClue(i)(BindPolicy.isLoopback(i) shouldBe Right(true))
    }
  }

  it should "refuse the wildcard addresses and LAN or public addresses" taggedAs UnitTest in {
    Seq("0.0.0.0", "::", "[::]", "192.168.1.10", "10.0.0.5", "203.0.113.7", "2001:db8::1").foreach { i =>
      withClue(i)(BindPolicy.isLoopback(i) shouldBe Right(false))
    }
  }

  it should "call a name that does not resolve an error, and an empty interface one too" taggedAs UnitTest in {
    BindPolicy.isLoopback("no-such-host.invalid").isLeft shouldBe true
    BindPolicy.isLoopback("  ").isLeft shouldBe true
  }
