package com.chipprbots.ethereum.crypto

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class Kec256ZeroPaddedSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  "kec256ZeroPadded" should "equal kec256 over the explicitly padded input, across the 64 KiB chunk boundary" in {
    val sizes = Gen.oneOf(0, 1, 135, 136, 137, 65535, 65536, 65537, 200000)
    forAll(Gen.containerOf[Array, Byte](Gen.choose(Byte.MinValue, Byte.MaxValue)), sizes) { (prefix, zeros) =>
      kec256ZeroPadded(prefix, zeros) shouldBe kec256(prefix ++ new Array[Byte](zeros))
    }
  }

  it should "leave the shared digest clean for the next caller" in {
    kec256ZeroPadded(Array[Byte](1, 2, 3), 70000)
    kec256(Array.emptyByteArray) shouldBe kec256ZeroPadded(Array.emptyByteArray, 0)
  }
}
