package com.chipprbots.ethereum.rlp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression for the unbounded-recursion DoS in [[RLP.rawDecode]]: a peer could send a small, deeply nested RLP payload
  * that overflowed the call stack with a `StackOverflowError`, which `scala.util.Try` does not catch, so it escaped the
  * decode call sites and — with Pekko's default `jvm-exit-on-fatal-error = on` — killed the node.
  */
class RLPNestingDepthSpec extends AnyFlatSpec with Matchers {

  /** `depth` nested single-element lists around an empty innermost list, built inside-out with correct RLP length
    * prefixes, without recursing (so the builder itself never overflows).
    */
  private def nestedListBytes(depth: Int): Array[Byte] = {
    var payload = Array[Byte](0x80.toByte) // innermost: empty string (a value, so `depth` counts list levels exactly)
    var i = 0
    while (i < depth) {
      val len = payload.length
      val prefix =
        if (len <= 55) Array[Byte]((0xc0 + len).toByte)
        else {
          var v = len
          var lb = Array.empty[Byte]
          while (v > 0) { lb = (v & 0xff).toByte +: lb; v >>= 8 }
          ((0xf7 + lb.length).toByte) +: lb
        }
      payload = prefix ++ payload
      i += 1
    }
    payload
  }

  "RLP.rawDecode" should "decode a structure nested exactly at the maximum depth" in {
    // nestedListBytes(n) has the outermost list at nesting level 1, so n == MaxNestingDepth is the deepest accepted input.
    val bytes = nestedListBytes(RLP.MaxNestingDepth)
    val decoded = RLP.rawDecode(bytes)
    // round-trips byte-for-byte
    RLP.encode(decoded) shouldBe bytes
  }

  it should "reject a structure nested one level beyond the maximum with an RLPException, not a StackOverflowError" in {
    val bytes = nestedListBytes(RLP.MaxNestingDepth + 1)
    val ex = intercept[RLPException](RLP.rawDecode(bytes))
    ex.getMessage should include("nesting depth")
  }

  it should "reject a pathologically deep payload (the DoS vector) with an RLPException, not a StackOverflowError" in {
    // The compact attack shape: a run of 0xc1, each byte one more nested single-element list. 1,000,000 of them is far
    // past what overflows the call stack on the node's -Xss1M dispatcher, and well within a peer's 16 MB
    // decompressed-frame budget. It must fail as an ordinary exception the decode call sites already handle. The guard
    // trips at the cap, so the decoder reads only the first ~64 bytes and returns immediately rather than descending.
    val bytes = Array.fill(1000000)(0xc1.toByte)
    val ex = intercept[RLPException](RLP.rawDecode(bytes))
    ex.getMessage should include("nesting depth")
  }

  it should "leave ordinary shallow structures unchanged" in {
    val encoded = RLP.encode(RLPList(RLPValue("cat".getBytes), RLPList(RLPValue("puppy".getBytes)), RLPValue(Array.empty)))
    RLP.encode(RLP.rawDecode(encoded)) shouldBe encoded
  }
}
