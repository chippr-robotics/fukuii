package com.chipprbots.ethereum.domain

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 021 GT-2, the full suite (T026; research R-12, SC-001): every `UInt256` member against the `BigInt` oracle on
  * at least 10^7 generated cases per operation (operations over a finite domain are enumerated), plus every named row.
  *
  * Tagged `DifferentialTest`: excluded from `testEssential` and `testStandard` (build.sbt) and from the Coverage job,
  * and run by its own CI steps, nightly and on PRs that touch `domain/UInt256*` or `vm/` (T028). Locally: `sbt
  * "testOnly *UInt256DifferentialFullSpec"`. Each operation's wall time is reported with `info`; the total goes to
  * research.md §B row 1.
  */
class UInt256DifferentialFullSpec extends AnyFunSuite:
  import UInt256DifferentialRows.*

  private val CasesPerOp: Long = 10_000_000L

  for op <- ops do
    val scope = if op.domain.isDefined then "its whole domain" else ">= 10^7 cases"
    test(s"GT-2 full: ${op.name} equals the BigInt oracle on $scope", DifferentialTest) {
      val start = System.nanoTime()
      val result = runOp(op, CasesPerOp)
      val seconds = (System.nanoTime() - start) / 1e9
      info(f"${op.name}: ${result.cases}%,d cases, ${result.failures.size} failures, $seconds%.1f s")
      assert(result.failures.isEmpty, result.failures.mkString("\n  ", "\n  ", ""))
      assert(op.domain.isDefined || result.cases >= CasesPerOp, s"${result.cases} cases ran")
    }

  test("GT-2 full: every named row", DifferentialTest) {
    val failures = rows.flatMap(r => r.failure().map(f => s"${r.id}: $f"))
    assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
  }
