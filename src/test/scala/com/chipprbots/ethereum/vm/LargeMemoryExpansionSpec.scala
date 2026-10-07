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

  // Bytes allocated by this thread (HotSpot); deterministic, unlike heap occupancy.
  private def allocatedBy(f: => Unit): Long =
    val mx = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    // getThreadAllocatedBytes returns -1 when unsupported or disabled, which would make every delta ~0 and pass.
    assume(mx.isThreadAllocatedMemorySupported && mx.isThreadAllocatedMemoryEnabled)
    val id = Thread.currentThread.threadId
    val before = mx.getThreadAllocatedBytes(id)
    f
    mx.getThreadAllocatedBytes(id) - before

  test("loading an unwritten region (a CALL's input) does not allocate the region", UnitTest, VMTest) {
    // ethereum/tests static_Call1MB1024Calldepth: every level of a ~550-deep STATICCALL recursion loads 1,000,000
    // never-written bytes as its callee's input, and each input stays live while the callee runs.
    val size = UInt256(64 * 1024 * 1024)
    val memory = Memory.empty.store(UInt256(0), UInt256(1)).expand(UInt256(0), size)
    memory.load(UInt256(0), size) // warm-up: class loading and the shared zero page are not the region
    var loaded = ByteString.empty
    val allocated = allocatedBy { loaded = memory.load(UInt256(0), size)._1 }
    loaded.length shouldBe size.toInt
    allocated should be < 1024L * 1024
  }

  test("a load returns the stored bytes followed by zeros, across zero-page boundaries", UnitTest, VMTest) {
    val page = 1 << 20
    val written = ByteString((1 to 100).map(_.toByte).toArray)
    val memory = Memory.empty.store(UInt256(50), written) // stored: [0, 150)
    val eager = ByteString(new Array[Byte](50)) ++ written
    def expected(offset: Int, size: Int): ByteString =
      val stored = eager.slice(offset, offset + size)
      stored ++ ByteString(new Array[Byte](size - stored.length))

    for
      offset <- Seq(0, 49, 50, 149, 150, 151, page - 1, page, page + 1)
      size <- Seq(0, 1, 31, 32, 100, 101, page - 150, page - 1, page, page + 1, 2 * page + 7)
    do
      val (bytes, after) = memory.load(UInt256(offset), UInt256(size))
      withClue(s"offset=$offset size=$size: ") {
        bytes shouldBe expected(offset, size)
        bytes.toArray.toSeq shouldBe expected(offset, size).toArray.toSeq
        after.size shouldBe (if size == 0 then memory.size else math.max(memory.size, offset + size))
      }
  }

  test("no load hands out the process-wide zero page through toArrayUnsafe or compact", UnitTest, VMTest) {
    // Pekko returns a ByteString's backing array from toArrayUnsafe()/compact only when the ByteString spans all of it.
    // A load of exactly one page of unwritten memory must therefore not be a full-length view of the shared page, or
    // a caller writing into that array would corrupt every later zero run in the process.
    val page = 1 << 20
    val memory = Memory.empty.expand(UInt256(0), UInt256(2 * page))
    for (offset, size) <- Seq((0, page), (page, page), (0, page - 1), (0, 2 * page)) do
      val first = memory.load(UInt256(offset), UInt256(size))._1
      val second = memory.load(UInt256(offset), UInt256(size))._1
      withClue(s"offset=$offset size=$size: ") {
        (first.toArrayUnsafe() eq second.toArrayUnsafe()) shouldBe false
        (first.compact.toArrayUnsafe() eq second.compact.toArrayUnsafe()) shouldBe false
      }
  }
