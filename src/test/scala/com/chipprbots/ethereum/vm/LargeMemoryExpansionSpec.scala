package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

/** A paid-for gigabyte-scale memory expansion must execute, not exhaust the heap.
  *
  * ethereum/tests `randomStatetest94` (LegacyTests/Constantinople BlockchainTests/ValidBlocks/bcStateTests; every fork
  * Homestead..ConstantinopleFix) runs `DIFFICULTY DIFFICULTY TIMESTAMP NUMBER SHA3 GASLIMIT SSTORE`: SHA3 over
  * `timestamp` (~1.57e9) never-written bytes at offset NUMBER = 1. The genesis gas limit is 2^63-1, so the ~4.8e12 gas
  * of expansion is PAID, and the fixture's post-state stores the resulting hash — the reference answer is success, not
  * out-of-gas. fukuii zero-filled and copied that region (and hashed a third copy), and hive's client died at chain
  * import with `OutOfMemoryError: Java heap space`.
  *
  * These cases are meant to run under a small heap; a region this size cannot be materialised even once in 512 MB.
  */
class LargeMemoryExpansionSpec extends AnyFunSuite with Matchers:

  private def stateWith(stack: Seq[UInt256]): MockWorldState.PS =
    Generators
      .getProgramStateGen(
        stackGen = Gen.const(Stack.empty().push(stack)),
        memGen = Gen.const(Memory.empty),
        evmConfig = EvmConfig.FrontierConfigBuilder(Fixtures.blockchainConfig)
      )
      .sample
      .get

  test("SHA3 over a paid-for 1.57 GB unwritten region returns randomStatetest94's hash", UnitTest, VMTest) {
    // randomStatetest94_Homestead: timestamp 0x5db6f811, expected storage value from the fixture's postState.
    val size = UInt256(BigInt("5db6f811", 16))
    val offset = UInt256(1)
    val out = SHA3.execute(stateWith(Seq(size, offset))) // pop(2) yields (offset, size): offset on top

    out.error shouldBe None
    out.stack.pop()._1 shouldBe UInt256(
      ByteString(Hex.decode("53fc3f66dc406bad05fbcb05d111b27a10730520cd6d80920c77101f2c883f6d"))
    )
    out.memory.size shouldBe (offset + size).toInt
  }

  test("SHA3 over a partly written region hashes the written bytes followed by the zero tail", UnitTest, VMTest) {
    val written = ByteString((1 to 100).map(_.toByte).toArray)
    val base = stateWith(Seq(UInt256(300), UInt256(10)))
    val in = base.withMemory(Memory.empty.store(UInt256(0), written))
    val out = SHA3.execute(in)

    val expected = kec256((written ++ ByteString(new Array[Byte](210))).drop(10).take(300).toArray)
    out.stack.pop()._1 shouldBe UInt256(expected)
    out.memory.size shouldBe 310
    out.memory.load(UInt256(0), UInt256(310))._1 shouldBe written ++ ByteString(new Array[Byte](210))
  }

  test("expansion without a write (CALL output region) does not allocate the region", UnitTest, VMTest) {
    val big = UInt256(BigInt(1500000000))
    val expanded = Memory.empty.expand(UInt256(0), big)
    expanded.size shouldBe big.toInt
    expanded.load(big - UInt256(32))._1 shouldBe UInt256.Zero
    expanded shouldBe Memory.empty.store(UInt256(0), ByteString.empty).expand(UInt256(0), big)
  }
