package com.chipprbots.ethereum.domain

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 021 GT-2, the fast suite (T026; research R-12): every `UInt256` member against the `BigInt` oracle on at least
  * 10^4 generated cases per operation, plus every named row (E, C, P and L). Tier 1 (`VMTest`).
  *
  * The full suite, `UInt256DifferentialFullSpec`, runs the same operations at 10^7 cases each. Both share their
  * generators, operations and rows with `UInt256DifferentialRows`, and both check that every word on either side is in
  * range (`signum >= 0`, `bitLength <= 256`, 32 bytes).
  *
  * Before S1's swap the type under test runs the oracle's own code, so this suite pins the oracle, the rows and the
  * harness; from the swap on (T035) it is the differential that holds the limb type byte-identical (FR-002).
  */
class UInt256DifferentialSpec extends AnyFunSuite:
  import UInt256DifferentialRows.*

  private val CasesPerOp: Long = 10_000L

  for op <- ops do
    val scope = if op.domain.isDefined then "its whole domain" else ">= 10^4 cases"
    test(s"GT-2 fast: ${op.name} equals the BigInt oracle on $scope", VMTest) {
      val result = runOp(op, CasesPerOp)
      assert(result.failures.isEmpty, result.failures.mkString("\n  ", "\n  ", ""))
      assert(op.domain.isDefined || result.cases >= CasesPerOp, s"${result.cases} cases ran")
    }

  for id <- rows.map(_.id).distinct do
    test(s"GT-2 row $id", VMTest) {
      val failures = rows.filter(_.id == id).flatMap(_.failure())
      assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
    }

  test("GT-2 rows: every named row has a case, except the named exclusions", VMTest) {
    val ids = rows.map(_.id).toSet
    val expected: Set[String] =
      ((1 to 35).map(i => f"E$i%02d").toSet -- Exclusions.map(_._1)) ++
        (1 to 12).filterNot(_ == 10).map(i => f"C$i%02d") ++ // C10 is E22
        ((1 to 6) ++ (10 to 16)).map(i => f"P$i%02d") ++
        (1 to 9).map(i => f"L$i%02d")
    assert(expected -- ids == Set.empty[String], "named rows with no case")
  }
