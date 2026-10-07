package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** `Address.hashCode` is computed once per instance; it must stay the value it always had (the bytes' hash), so that
  * hash-ordered iteration over sets and maps of addresses (and so anything derived from it) is unchanged.
  */
class AddressHashSpec extends AnyFlatSpec with Matchers:

  "Address.hashCode" should "equal the hash of its bytes, and agree with equals" taggedAs UnitTest in {
    val r = new java.util.Random(3)
    (1 to 500).foreach { _ =>
      val raw = new Array[Byte](20); r.nextBytes(raw)
      val a = Address(raw)
      val b = Address(ByteString(raw.clone()))
      a.hashCode shouldBe ByteString(raw).hashCode
      a shouldBe b
      a.hashCode shouldBe b.hashCode
    }
    Address(0x1234L).hashCode shouldBe Address(0x1234L).bytes.hashCode
  }
