package com.chipprbots.ethereum.domain

import java.lang.management.ManagementFactory

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 021 T029 (US2; plan S1, research R-1, data-model §2.3): the allocation budget of `UInt256`'s members, measured
  * with `ThreadMXBean` over 10^5 operations on full-range operands.
  *   - `+`, `-`, `*`, `&` and `<<` allocate at most 64 bytes per result: one flat object (48 bytes with compressed
  *     class pointers, R-1);
  *   - `div`, `mulmod` and `**` allocate at most 64 bytes plus one fixed scratch array: the value type copies its limbs
  *     into one fresh scratch per call and runs the array kernel (R-1, data-model §2.2).
  *
  * '''Failing-first, and kept out of the way until the swap.''' On today's `BigInt`-backed type every one of these
  * allocates several objects per result (`UInt256`, `BigInt`, `BigInteger`, `int[]`, and the masking or division
  * temporaries), so each budget is red by design. Each budget therefore runs inside `pendingUntilFixed`: the
  * measurement runs and is reported with `info`, a breach reports the test as pending (so `testEssential`,
  * `testStandard` and the Coverage job stay green), and a budget that is met FAILS the test, saying the wrapper must
  * go. T035, the swap, removes every `pendingUntilFixed` here (tasks.md T029 note). The harness controls at the end are
  * ordinary tests, green now and after.
  */
// scalastyle:off magic.number
class UInt256AllocationSpec extends AnyFunSuite:

  /** A kernel over operand index `i`. A SAM with an `Int` parameter: a `Function1[Int, UInt256]` would box `i`. */
  trait Kernel:
    def apply(i: Int): UInt256

  private val Ops: Int = 100_000

  /** Bytes allowed per result for the cheap members: one flat object (R-1: 48 bytes; 40 with JEP 519 headers). */
  private val ResultBudget: Long = 64L

  /** The fixed scratch the value type allocates per heavy call: data-model §2.2's scratch, "which has a fixed size",
    * `un` 9 + `vn` 4 + `qt` 5 + `prod` 8 = 26 limbs (R-16), an `Array[Long](26)` of 16 + 208 bytes. The operands need
    * no room of their own: D1 normalises the dividend into `un` and the divisor into `vn` straight from the limb
    * fields, MULMOD's product is formed into `prod`, the quotient is `qt` and the remainder is `un` after D8. If T034's
    * layout needs more, that is a data-model change for RP-2 to sign, not a looser number here.
    */
  private val ScratchLimbs: Int = 26
  private val ScratchBytes: Long = 16L + 8L * ScratchLimbs

  private val mx: com.sun.management.ThreadMXBean =
    ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  /** Deterministic full-range operands (constitution III). */
  private val rnd = new java.util.Random(0x0215a11c0cL)
  private def word(bits: Int): UInt256 = UInt256(BigInt(bits, rnd))
  private val a: Array[UInt256] = Array.fill(Ops)(word(256))
  private val b: Array[UInt256] = Array.fill(Ops)(word(256))

  /** Divisors and moduli: bit length uniform in 1..256, never zero (a zero divisor is the cheap early exit). */
  private val d: Array[UInt256] = Array.fill(Ops)(UInt256(BigInt(1 + rnd.nextInt(256), rnd).max(BigInt(1))))
  private val shifts: Array[UInt256] = Array.fill(Ops)(UInt256(rnd.nextInt(256)))

  /** Allocated bytes per call of `k`, over [[Ops]] calls, after one warm-up pass. Results go to a sink so that none of
    * them can be scalar-replaced away.
    */
  private def bytesPerResult(k: Kernel): Double =
    val sink = new Array[UInt256](Ops)
    var i = 0
    while i < Ops do
      sink(i) = k(i)
      i += 1
    val before = mx.getCurrentThreadAllocatedBytes
    i = 0
    while i < Ops do
      sink(i) = k(i)
      i += 1
    val after = mx.getCurrentThreadAllocatedBytes
    (after - before).toDouble / Ops

  private def assumeMeasurable(): Unit =
    assume(mx.isThreadAllocatedMemorySupported, "this JVM cannot measure per-thread allocation")
    mx.setThreadAllocatedMemoryEnabled(true)

  private def budget(name: String, limit: Long)(k: Kernel): Unit =
    assumeMeasurable()
    val measured = bytesPerResult(k)
    info(f"$name: $measured%.1f bytes per result (budget $limit)")
    // Spec 021 T029, failing-first: red on the BigInt type by design. T035 (the swap) removes this wrapper.
    pendingUntilFixed {
      assert(measured <= limit.toDouble, f"$name allocates $measured%.1f bytes per result, over its budget of $limit")
    }

  test("+ allocates at most 64 bytes per result", VMTest) {
    budget("+", ResultBudget)(i => a(i) + b(i))
  }

  test("- allocates at most 64 bytes per result", VMTest) {
    budget("-", ResultBudget)(i => a(i) - b(i))
  }

  test("* allocates at most 64 bytes per result", VMTest) {
    budget("*", ResultBudget)(i => a(i) * b(i))
  }

  test("& allocates at most 64 bytes per result", VMTest) {
    budget("&", ResultBudget)(i => a(i) & b(i))
  }

  test("<< allocates at most 64 bytes per result", VMTest) {
    budget("<<", ResultBudget)(i => a(i) << shifts(i))
  }

  test("div allocates at most 64 bytes plus one fixed scratch array per result", VMTest) {
    budget("div", ResultBudget + ScratchBytes)(i => a(i).div(d(i)))
  }

  test("mulmod allocates at most 64 bytes plus one fixed scratch array per result", VMTest) {
    budget("mulmod", ResultBudget + ScratchBytes)(i => a(i).mulmod(b(i), d(i)))
  }

  test("** allocates at most 64 bytes plus one fixed scratch array per result", VMTest) {
    budget("**", ResultBudget + ScratchBytes)(i => a(i) ** b(i))
  }

  // ── Harness controls: ordinary tests, green before and after the swap ───────────────────────────────────────────

  test("the harness measures nothing for a kernel that allocates nothing", VMTest) {
    assumeMeasurable()
    val measured = bytesPerResult(i => a(i))
    info(f"identity: $measured%.2f bytes per result")
    assert(measured < 1.0)
  }

  test("the harness measures one Array[Long](4) as 32 bytes of data plus its header", VMTest) {
    assumeMeasurable()
    val arrays = new Array[Array[Long]](Ops)
    val measured = bytesPerResult { i =>
      arrays(i) = new Array[Long](4)
      a(i)
    }
    info(f"Array[Long](4): $measured%.2f bytes per result")
    assert(measured >= 40.0 && measured < 57.0)
  }
