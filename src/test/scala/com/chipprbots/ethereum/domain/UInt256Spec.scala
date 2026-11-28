package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.prop.TableFor2
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.Generators.*

class UInt256Spec extends AnyFunSuite with ScalaCheckPropertyChecks:

  val Modulus: BigInt = UInt256.MaxValue.toBigInt + 1
  val MaxSignedValue: BigInt = Modulus / 2 - 1

  val specialNumbers: Seq[BigInt] =
    Seq(BigInt(-1), BigInt(0), BigInt(1), MaxValue.toBigInt, -MaxValue.toBigInt, -MaxValue.toBigInt + 1)

  val pairs: Seq[(BigInt, BigInt)] = specialNumbers
    .combinations(2)
    .map { case Seq(n1, n2) => n1 -> n2 }
    .toSeq

  val specialCases: TableFor2[BigInt, BigInt] = Table(("n1", "n2"), pairs*)

  def toSignedBigInt(n: BigInt): BigInt = if n > MaxSignedValue then n - Modulus else n

  def toUnsignedBigInt(n: BigInt): BigInt = if n < 0 then n + Modulus else n

  /** For each operation (op) tests check a following property: For two BigInts (n1, n2): UInt256(n1) op UInt256(n2) ==
    * UInt256(n1 op n2)
    */
  test("&", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) & UInt256(n2)) == UInt256(n1 & n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) & UInt256(n2)) == UInt256(n1 & n2))
    }
  }

  test("|", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) | UInt256(n2)) == UInt256(n1 | n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) | UInt256(n2)) == UInt256(n1 | n2))
    }
  }

  test("^", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) ^ UInt256(n2)) == UInt256(n1 ^ n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1) ^ UInt256(n2)) == UInt256(n1 ^ n2))
    }
  }

  test("~", UnitTest) {
    forAll(bigIntGen) { (n: BigInt) =>
      assert(~UInt256(n) == UInt256(~n))
    }
    forAll(Table("n", specialNumbers*)) { (n: BigInt) =>
      assert(~UInt256(n) == UInt256(~n))
    }
  }

  test("negation", UnitTest) {
    forAll(bigIntGen) { (n: BigInt) =>
      assert(-UInt256(n) == UInt256(-n))
    }
    forAll(Table("n", specialNumbers*)) { (n: BigInt) =>
      assert(-UInt256(n) == UInt256(-n))
    }
    assert(-UInt256(1) == UInt256(-1))
    assert(-UInt256(-1) == UInt256(1))
    assert(-UInt256.Zero == UInt256.Zero)
  }

  test("+", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) + UInt256(n2) == UInt256(n1 + n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) + UInt256(n2) == UInt256(n1 + n2))
    }
  }

  test("-", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) - UInt256(n2) == UInt256(n1 - n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) - UInt256(n2) == UInt256(n1 - n2))
    }
  }

  test("*", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) * UInt256(n2) == UInt256(n1 * n2))
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) * UInt256(n2) == UInt256(n1 * n2))
    }
  }

  // Spec 021 T027: this test was named "/" but exercised `*` (GT-1 part A, flag-only note). `/` is retired at T032 (D1),
  // and its one src/main caller, calcMemCost's `MaxValue / 2`, becomes a constant built with `div` (P15).
  test("MaxValue div 2 is 2^255 - 1, the constant that replaces `MaxValue / 2` (P15)", UnitTest) {
    assert(MaxValue.div(UInt256(2)).toBigInt == MaxSignedValue)
    assert(MaxValue.div(UInt256(2)) == UInt256(MaxSignedValue))
  }

  test("div", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        assert((UInt256(n1).div(UInt256(n2))) == UInt256(n1 / n2))
      }
    }
    forAll(specialCases) { (n1: BigInt, n2: BigInt) =>
      whenever(n1 > 0 && n2 > 0) {
        assert((UInt256(n1).div(UInt256(n2))) == UInt256(n1 / n2))
      }
    }
    assert((UInt256(1).div(Zero)) == Zero)
  }

  test("sdiv", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        val expected: BigInt = toUnsignedBigInt(toSignedBigInt(n1) / toSignedBigInt(n2))
        assert((UInt256(n1).sdiv(UInt256(n2))) == UInt256(expected))
      }
    }
    assert((UInt256(-1).sdiv(UInt256(-MaxValue.toBigInt))) == UInt256(-1))
    assert((UInt256(-1).sdiv(Zero)) == Zero)
  }

  test("mod", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        assert((UInt256(n1).mod(UInt256(n2))) == UInt256(n1.mod(n2)))
      }
    }
    assert((UInt256(-1).mod(UInt256(MaxValue.toBigInt))) == Zero)
    assert((UInt256(1).mod(Zero)) == Zero)
  }

  test("smod", UnitTest) {
    assert((UInt256(Modulus - 1).smod(UInt256(3))) == UInt256(Modulus - 1))
    assert((UInt256(-1).smod(UInt256(MaxValue.toBigInt))) == Zero)
    assert((UInt256(1).smod(Zero)) == Zero)
  }

  test("addmod", UnitTest) {
    forAll(bigIntGen, bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt, n3: BigInt) =>
      whenever(n3 != 0) {
        assert(UInt256(n1).addmod(UInt256(n2), UInt256(n3)) == UInt256((n1 + n2).mod(n3)))
      }
    }
    assert(UInt256(42).addmod(UInt256(42), Zero) == Zero)
  }

  test("mulmod", UnitTest) {
    forAll(bigIntGen, bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt, n3: BigInt) =>
      whenever(n3 != 0) {
        assert(UInt256(n1).mulmod(UInt256(n2), UInt256(n3)) == UInt256((n1 * n2).mod(n3)))
      }
    }
    assert(UInt256(42).mulmod(UInt256(42), Zero) == Zero)
  }

  test("**", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1) ** UInt256(n2) == UInt256(n1.modPow(n2, Modulus)))
    }
  }

  test("signExtend", UnitTest) {
    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "extension", "result"),
      (42, 3, 42),
      (42, 1, 42),
      (42, -1, 42),
      (42, 0, 42),
      (42, Size, 42),
      (42, Size + 1, 42),
      (-42, Size, -42),
      (-42, Size + 1, -42),
      (-42, -11, -42),
      (-1, 1, -1),
      (-1, 1, -1),
      (0x1a81ff, 1, UInt256(Array.fill[Byte](30)(-1) ++ Array(0x81, 0xff).map(_.toByte))),
      (0x1a81ff, 2, 0x1a81ff),
      (0x1a81ff, 10, 0x1a81ff)
    )

    forAll(testData) { (uint, extension, result) =>
      assert(uint.signExtend(extension) == result)
    }
  }

  test("fillingAdd", UnitTest) {
    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "extension", "result"),
      (-9, 10, UInt256.MaxValue),
      (42, 3, 45),
      (-1, 1, UInt256.MaxValue),
      (1, -1, UInt256.MaxValue),
      (-10, 10, UInt256.MaxValue),
      (10, -10, UInt256.MaxValue),
      (UInt256.MaxValue - 10, 20, UInt256.MaxValue),
      (0, UInt256.MaxValue - 1, UInt256.MaxValue - 1),
      (UInt256.MaxValue, 1, UInt256.MaxValue)
    )

    forAll(testData) { (uint, extension, result) =>
      assert(uint.fillingAdd(extension) == result)
    }
  }

  test("slt", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1).slt(UInt256(n2))) == (toSignedBigInt(n1) < toSignedBigInt(n2)))
    }

    val testData = Table[UInt256, UInt256, Boolean](("a", "b", "result"), (-1, 1, true), (1, -1, false), (1, 0, false))

    forAll(testData) { (a, b, result) =>
      assert((a.slt(b)) == result)
    }
  }

  test("sgt", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert((UInt256(n1).sgt(UInt256(n2))) == (toSignedBigInt(n1) > toSignedBigInt(n2)))
    }

    val testData =
      Table[UInt256, UInt256, Boolean](("a", "b", "result"), (-1, 1, false), (1, -1, true), (0, 1, false), (1, 0, true))

    forAll(testData) { (a, b, result) =>
      assert((a.sgt(b)) == result)
    }
  }

  test("getByte", UnitTest) {
    val v1 = UInt256(ByteString((100 to 131).map(_.toByte).toArray))

    val testData = Table[UInt256, UInt256, UInt256](
      ("value", "idx", "result"),
      (42, -1, Zero),
      (42, Size, Zero),
      (42, Size + 1, Zero),
      (1, 287, Zero),
      (v1, 0, 100),
      (v1, 1, 101),
      (v1, 30, 130),
      (v1, 31, 131),
      (UInt256(Array.fill[Byte](32)(-50)), 8, 256 - 50)
    )

    forAll(testData) { (a, b, result) =>
      assert(a.getByte(b) == result)
    }
  }

  test("intValue", UnitTest) {
    assert(specialNumbers.map(UInt256(_).toInt) == Seq(Int.MaxValue, 0, 1, Int.MaxValue, 1, 2))
  }

  test("comparison", UnitTest) {
    type CFUI = (UInt256, UInt256) => Boolean
    type CFBI = (BigInt, BigInt) => Boolean
    case class Cmp(uint: CFUI, bi: CFBI)

    val cmpFuncUInt256 = Seq[CFUI](_ > _, _ >= _, _ < _, _ <= _)
    val cmpFuncBigInt = Seq[CFBI](_ > _, _ >= _, _ < _, _ <= _)
    val comparators = cmpFuncUInt256.zip(cmpFuncBigInt).map { case (uint, bi) => Cmp(uint, bi) }

    val uint256Gen = getUInt256Gen()

    forAll(Table("comparators", comparators*)) { cmp =>
      forAll(uint256Gen, uint256Gen) { (a, b) =>
        assert(cmp.uint(a, b) == cmp.bi(a.toBigInt, b.toBigInt))
      }

      forAll(specialCases) { (x, y) =>
        val (a, b) = (UInt256(x), UInt256(y))
        assert(cmp.uint(a, b) == cmp.bi(a.toBigInt, b.toBigInt))
      }
    }
  }

  test("Passing too long ByteString should throw an exception", UnitTest) {
    assertThrows[IllegalArgumentException] {
      UInt256(ByteString(Array.fill(Size + 1)(1.toByte)))
    }
  }

  test("UInt256 converted to a byte array should always have length 32 bytes", UnitTest) {
    forAll(bigIntGen) { n =>
      assert(UInt256(n).bytes.size == 32)
    }
    // regression
    assert(
      UInt256(BigInt("115792089237316195423570985008687907853269984665640564039457584007913129639935")).bytes.size == 32
    )
  }

  test("2-way bytes conversion", UnitTest) {
    forAll(getUInt256Gen()) { x =>
      val y = UInt256(x.bytes)
      assert(x === y)
    }

    forAll(getByteStringGen(0, 32)) { xs =>
      val ys = UInt256(xs).bytes
      assert(xs.dropWhile(_ == 0) === ys.dropWhile(_ == 0))
    }
  }

  test("byteSize", UnitTest) {
    val table = Table[BigInt, Int](
      ("x", "expected"),
      (0, 0),
      (1, 1),
      (255, 1),
      (256, 2),
      (65535, 2),
      (65536, 3),
      (BigInt(2).pow(256) - 1, 32),
      (BigInt(2).pow(256), 0)
    )
    forAll(table) { (x, expected) =>
      assert(UInt256(x).byteSize === expected)
    }

    forAll(getUInt256Gen(min = UInt256(1))) { x =>
      import math.*
      val byteSize = 1 + floor(log(x.doubleValue) / log(256)).toInt
      assert(x.byteSize === byteSize)
    }
  }

  // UInt256(n) reduces n by keeping its low 256 bits. It used to compute (n % Modulus + Modulus) % Modulus; the two must
  // agree on every integer, of either sign and any size (MUL reaches 2^512, SUB and negation go negative).
  test("construction from a BigInt is the non-negative residue mod 2^256", UnitTest) {
    def formerBound(n: BigInt): BigInt = (n % Modulus + Modulus) % Modulus
    def check(n: BigInt): Unit =
      val bounded = UInt256(n).toBigInt
      assert(bounded == formerBound(n), s"n = $n")
      assert(bounded == n.mod(Modulus), s"n = $n")

    val bases = Seq(BigInt(0), BigInt(2).pow(63), BigInt(2).pow(64), BigInt(2).pow(255), Modulus, Modulus * 2)
    for
      base <- bases :+ Modulus * Modulus
      delta <- -2 to 2
      sign <- Seq(1, -1)
    do check((base + delta) * sign)

    // Two's-complement byte strings up to 70 bytes: both signs, magnitudes to ~2^560.
    forAll(getByteStringGen(0, 70), minSuccessful(5000)) { bytes =>
      check(BigInt(bytes.toArray.prepended(0.toByte)))
      if bytes.nonEmpty then check(BigInt(bytes.toArray))
    }
  }

  // ── Spec 021 T027: the members GT-1 part A found untested ───────────────────────────────────────────────────────
  // Every expected value here is today's behaviour (the BigInt-backed type); S1's limb type must keep it.

  private val shiftWords: Seq[BigInt] =
    Seq(BigInt(0), BigInt(1), MaxSignedValue, MaxSignedValue + 1, Modulus - 1, BigInt("deadbeef" * 8, 16)) ++
      (0 to 3).map(k => BigInt(2).pow(64 * k) - 1) ++ (0 to 3).map(k => BigInt(2).pow(64 * k + 63))

  test("<< on shifts 0 to 255 is the left shift mod 2^256", UnitTest) {
    for x <- shiftWords; s <- 0 to 255 do assert((UInt256(x) << UInt256(s)).toBigInt == ((x << s) & (Modulus - 1)))
    forAll(bigIntGen, Gen.choose(0, 255)) { (x: BigInt, s: Int) =>
      assert((UInt256(x) << UInt256(s)).toBigInt == ((x << s) & (Modulus - 1)))
    }
  }

  test(">> on shifts 0 to 255 is the logical right shift", UnitTest) {
    for x <- shiftWords; s <- 0 to 255 do assert((UInt256(x) >> UInt256(s)).toBigInt == (x >> s))
    forAll(bigIntGen, Gen.choose(0, 255)) { (x: BigInt, s: Int) =>
      assert((UInt256(x) >> UInt256(s)).toBigInt == (x >> s))
    }
  }

  test("sshift on shifts 0 to 255 is the arithmetic right shift of the signed value", UnitTest) {
    for x <- shiftWords; s <- 0 to 255 do
      assert(UInt256(x).sshift(UInt256(s)) == UInt256(toSignedBigInt(x) >> s), s"x = $x, s = $s")
    forAll(bigIntGen, Gen.choose(0, 255)) { (x: BigInt, s: Int) =>
      assert(UInt256(x).sshift(UInt256(s)) == UInt256(toSignedBigInt(x) >> s))
    }
    assert(UInt256(-1).sshift(UInt256(255)) == MaxValue)
    assert(UInt256(MaxSignedValue + 1).sshift(UInt256(254)) == UInt256(-2))
  }

  test("toLong keeps the low 63 bits", UnitTest) {
    assert(UInt256(0).toLong == 0L)
    assert(UInt256(Long.MaxValue).toLong == Long.MaxValue)
    assert(UInt256(BigInt(2).pow(63)).toLong == 0L)
    assert(UInt256(BigInt(2).pow(64) + 7).toLong == 7L)
    assert(MaxValue.toLong == Long.MaxValue)
    forAll(bigIntGen) { (n: BigInt) =>
      assert(UInt256(n).toLong == (n & BigInt(Long.MaxValue)).toLong)
    }
  }

  test("toHexString is 0x and lowercase hex padded to an even length", UnitTest) {
    assert(Zero.toHexString == "0x00")
    assert(One.toHexString == "0x01")
    assert(UInt256(0xabc).toHexString == "0x0abc")
    assert(UInt256(0x100).toHexString == "0x0100")
    assert(MaxValue.toHexString == "0x" + "f" * 64)
    forAll(bigIntGen) { (n: BigInt) =>
      val hex = n.toString(16)
      assert(UInt256(n).toHexString == "0x" + (if hex.length % 2 == 0 then hex else "0" + hex))
    }
  }

  test("toString is the signed decimal; toDecString the unsigned one", UnitTest) {
    assert(MaxValue.toString == "-1")
    assert(UInt256(MaxSignedValue + 1).toString == (-(MaxSignedValue + 1)).toString)
    assert(UInt256(5).toString == "5")
    assert(MaxValue.toDecString == (Modulus - 1).toString)
    assert(MaxValue.toSignedDecString == "-1")
    forAll(bigIntGen) { (n: BigInt) =>
      assert(UInt256(n).toString == toSignedBigInt(n).toString)
      assert(UInt256(n).toDecString == n.toString)
    }
  }

  test("hashCode equals BigInt's hashCode, so it agrees with the cooperative equals", UnitTest) {
    for n <- specialNumbers.map(_.mod(Modulus)) ++ shiftWords do assert(UInt256(n).hashCode == n.hashCode, s"n = $n")
    forAll(bigIntGen) { (n: BigInt) =>
      assert(UInt256(n).hashCode == n.hashCode)
    }
    assert(UInt256(5).hashCode == 5.##)
    assert(UInt256(5L).hashCode == 5L.##)
  }

  test("equals with a non-UInt256 is BigInt's cooperative equality, one way only", UnitTest) {
    val five = UInt256(5)
    assert(five.equals(BigInt(5)))
    assert(five.equals(5))
    assert(five.equals(5L))
    assert(Zero.equals(0))
    assert(!five.equals(6))
    assert(!five.equals("5"))
    assert(!MaxValue.equals(-1))
    // Asymmetric, as today: a boxed number does not know UInt256.
    assert(!(5: Any).equals(five))
    assert((5: Any) != (five: Any))
    assert(!BigInt(5).equals(five))
  }

  test("max returns the larger, and on a tie the argument", UnitTest) {
    assert(UInt256(3).max(UInt256(7)) == UInt256(7))
    assert(UInt256(7).max(UInt256(3)) == UInt256(7))
    assert(MaxValue.max(Zero) == MaxValue)
    val a = UInt256(9)
    val b = UInt256(9)
    assert(a.max(b).eq(b))
    assert(a.min(b).eq(b))
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      assert(UInt256(n1).max(UInt256(n2)).toBigInt == n1.max(n2))
    }
  }

  test("apply(Boolean) and bool2UInt256 return the shared One and Zero", UnitTest) {
    assert(UInt256(true).eq(One))
    assert(UInt256(false).eq(Zero))
    assert(bool2UInt256(true) == One)
    assert(bool2UInt256(false) == Zero)
  }

  test("apply(Long) and the Int conversion sign-extend a negative argument", UnitTest) {
    assert(UInt256(-1L) == MaxValue)
    assert(UInt256(Long.MinValue).toBigInt == Modulus - BigInt(2).pow(63))
    assert(UInt256(-2L).toBigInt == Modulus - 2)
    assert(int2UInt256(-1) == MaxValue)
    assert(int2UInt256(Int.MinValue).toBigInt == Modulus - BigInt(2).pow(31))
    forAll(Gen.long) { (n: Long) =>
      assert(UInt256(n).toBigInt == BigInt(n).mod(Modulus))
    }
  }

  test("byte2UInt256 sign-extends", UnitTest) {
    for b <- Byte.MinValue.toInt to Byte.MaxValue.toInt do
      assert(byte2UInt256(b.toByte).toBigInt == BigInt(b).mod(Modulus), s"b = $b")
    assert(byte2UInt256(0x80.toByte).toBigInt == Modulus - 128)
    assert(byte2UInt256((-1).toByte) == MaxValue)
    assert(byte2UInt256(0x7f.toByte) == UInt256(127))
  }

  test("smod: the remainder of the absolute values, with the sign of the dividend", UnitTest) {
    forAll(bigIntGen, bigIntGen) { (n1: BigInt, n2: BigInt) =>
      whenever(n2 != 0) {
        val (a, b) = (toSignedBigInt(n1), toSignedBigInt(n2))
        val expected = toUnsignedBigInt(BigInt(a.signum) * (a.abs % b.abs))
        assert(UInt256(n1).smod(UInt256(n2)) == UInt256(expected))
      }
    }
    assert(UInt256(-7).smod(UInt256(2)) == UInt256(-1))
    assert(UInt256(7).smod(UInt256(-2)) == UInt256(1))
    assert(UInt256(-7).smod(UInt256(-2)) == UInt256(-1))
    assert(UInt256(MaxSignedValue + 1).smod(UInt256(-1)) == Zero)
  }

  test("SDIV(-2^255, -1) is -2^255: the one signed quotient that overflows wraps", UnitTest) {
    val minSigned = UInt256(MaxSignedValue + 1)
    assert(minSigned.sdiv(UInt256(-1)) == minSigned)
    assert(minSigned.sdiv(UInt256(-1)).toBigInt == BigInt(2).pow(255))
  }
