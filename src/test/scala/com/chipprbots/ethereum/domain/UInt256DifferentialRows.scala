package com.chipprbots.ethereum.domain

import java.util.concurrent.Callable
import java.util.concurrent.Executors

import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.hashing.MurmurHash3

import org.scalacheck.Gen
import org.scalacheck.rng.Seed

import com.chipprbots.ethereum.domain.oracle.BigIntUInt256
import com.chipprbots.ethereum.vm.CLZ
import com.chipprbots.ethereum.vm.EtcForkMatrix
import com.chipprbots.ethereum.vm.MockWorldState
import com.chipprbots.ethereum.vm.PinHarness

/** Spec 021 GT-2 (T025; research R-12, data-model §9): the generators, the operations and the named rows of the
  * differential between `UInt256` and its oracle, `domain.oracle.BigIntUInt256` (today's class, copied verbatim).
  *
  * Shared by the fast suite (`UInt256DifferentialSpec`, `VMTest`, about 10^4 cases per operation) and the full suite
  * (`UInt256DifferentialFullSpec`, `DifferentialTest`, at least 10^7).
  *
  * '''Comparison''' (R-12): word results by `toBigInt` and by `bytes`; `Int`, `Long`, `Boolean`, `Byte` and `String`
  * results by value; `hashCode` by value; throws by exception type, for the named exclusions only. Every word on either
  * side must be in range: `signum >= 0`, `bitLength <= 256`, and 32 bytes (forge S1).
  *
  * '''Named exclusions''' (R-12, nothing else is excluded):
  *   - `<<`, `>>` and `sshift` are compared on shifts 0 to 255 only (E29, E30; D2);
  *   - `apply` of 33 or more bytes compares "both throw `IllegalArgumentException`" (C04, P04; D3);
  *   - `/` has no case (E06; D1). It is retired at T032.
  *
  * '''Determinism''' (constitution III): each operation's cases come from fixed seeds derived from its name and a fixed
  * chunk count, so the case set does not depend on the host's core count; chunks only run in parallel. Nothing shrinks.
  * Operations over a finite domain (`Boolean`, `Byte`) are enumerated exhaustively instead of sampled.
  *
  * Row expectations are the "current result" of GT-1 part A §5 and data-model §9, computed here from integer arithmetic
  * where a row says "the low 256 bits" or similar. Both the oracle and the type under test must equal them.
  */
// scalastyle:off magic.number
object UInt256DifferentialRows:

  // ── Values ──────────────────────────────────────────────────────────────────────────────────────────────────────

  def pow2(n: Int): BigInt = BigInt(1) << n
  val TwoPow256: BigInt = pow2(256)
  val MaxWord: BigInt = TwoPow256 - 1
  private val TwoPow64: BigInt = pow2(64)
  private val LimbMask: BigInt = TwoPow64 - 1

  /** The two's-complement negation of `x`, as a word: `-x mod 2^256`. */
  def neg(x: BigInt): BigInt = (-x).mod(TwoPow256)

  /** The word whose signed reading is `x`. */
  def signed(x: BigInt): BigInt = x.mod(TwoPow256)

  /** The signed reading of a word. */
  def toSigned(x: BigInt): BigInt = if x.testBit(255) then x - TwoPow256 else x

  def limb(w: Long): BigInt = BigInt(w) & LimbMask

  /** The word with little-endian limbs (w0, w1, w2, w3), each read unsigned. */
  def fromLimbs(w0: Long, w1: Long, w2: Long, w3: Long): BigInt =
    (limb(w3) << 192) | (limb(w2) << 128) | (limb(w1) << 64) | limb(w0)

  /** 32 big-endian bytes, built byte by byte (independent of `BigInt.toByteArray`). */
  def bytes32(x: BigInt): ByteString =
    ByteString(Array.tabulate[Byte](32)(i => ((x >> (8 * (31 - i))) & 0xff).toByte))

  // ── Outcomes ────────────────────────────────────────────────────────────────────────────────────────────────────

  /** What one side of a comparison produced. `Value` carries its type in the rendering, so `Int` 1 ≠ `Long` 1. */
  enum Outcome derives CanEqual:
    case Word(value: BigInt, bytes: ByteString)
    case Value(rendered: String)
    case Threw(exception: String)

  def word(x: UInt256): Outcome = Outcome.Word(x.toBigInt, x.bytes)
  def word(x: BigIntUInt256): Outcome = Outcome.Word(x.toBigInt, x.bytes)

  /** The expected outcome for a word result `x` (taken mod 2^256). */
  def w(x: BigInt): Outcome =
    val v = x.mod(TwoPow256)
    Outcome.Word(v, bytes32(v))

  def int(x: Int): Outcome = Outcome.Value(s"Int:$x")
  def long(x: Long): Outcome = Outcome.Value(s"Long:$x")
  def bool(x: Boolean): Outcome = Outcome.Value(s"Boolean:$x")
  def byte(x: Byte): Outcome = Outcome.Value(s"Byte:$x")
  def str(x: String): Outcome = Outcome.Value(s"String:$x")
  def big(x: BigInt): Outcome = Outcome.Value(s"BigInt:$x")
  def bytesOut(x: ByteString): Outcome = Outcome.Value(s"Bytes:${x.length}:${x.map(b => f"$b%02x").mkString}")
  def threw[E <: Throwable](using ct: scala.reflect.ClassTag[E]): Outcome = Outcome.Threw(ct.runtimeClass.getName)

  private def attempt(f: => Outcome): Outcome =
    try f
    catch case NonFatal(e) => Outcome.Threw(e.getClass.getName)

  private def wordProblem(side: String, o: Outcome): Option[String] = o match
    case Outcome.Word(v, bs) if v.signum < 0 || v.bitLength > 256 || bs.length != 32 =>
      Some(s"$side's word is out of range: value $v, ${bs.length} bytes")
    case _ => None

  // ── Operations ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** One operation of the differential: its operand generator, and the same member on each side.
    *
    * @param domain
    *   when set, the whole (finite) domain, enumerated instead of sampled
    * @param mayThrow
    *   true only for the named exclusion D3 (`apply` of 33+ bytes). Elsewhere a throw on either side is a failure.
    */
  final class Op[A](
      val name: String,
      val gen: Gen[A],
      show: A => String,
      val domain: Option[Seq[A]] = None,
      mayThrow: Boolean = false
  )(under: A => Outcome, oracle: A => Outcome):

    /** The problems with one case, or None. */
    def mismatch(a: A): Option[String] = check(a, None)

    /** The problems with one case against an expected outcome as well, or None. */
    def check(a: A, expected: Option[Outcome]): Option[String] =
      val u = attempt(under(a))
      val o = attempt(oracle(a))
      val problems = Seq(
        Option.when(u != o)(s"UInt256 gave $u but the oracle gave $o"),
        expected.flatMap(e => Option.when(u != e)(s"UInt256 gave $u, expected $e")),
        expected.flatMap(e => Option.when(o != e)(s"the oracle gave $o, expected $e")),
        Option.when(!mayThrow && (u.isInstanceOf[Outcome.Threw] || o.isInstanceOf[Outcome.Threw]))(
          s"a throw outside the named exclusions (UInt256 $u, oracle $o)"
        ),
        wordProblem("UInt256", u),
        wordProblem("the oracle", o)
      ).flatten
      Option.when(problems.nonEmpty)(s"$name(${show(a)}): ${problems.mkString("; ")}")

  def hex(x: BigInt): String = if x.signum < 0 then s"-0x${(-x).toString(16)}" else s"0x${x.toString(16)}"
  private def hex2(p: (BigInt, BigInt)): String = s"${hex(p._1)}, ${hex(p._2)}"
  private def hex3(p: (BigInt, BigInt, BigInt)): String = s"${hex(p._1)}, ${hex(p._2)}, ${hex(p._3)}"

  private def u(n: BigInt): UInt256 = UInt256(n)
  private def o(n: BigInt): BigIntUInt256 = BigIntUInt256(n)

  private def unaryWord(name: String, g: Gen[BigInt])(
      fu: UInt256 => UInt256,
      fo: BigIntUInt256 => BigIntUInt256
  ): Op[BigInt] =
    Op(name, g, hex)(a => word(fu(u(a))), a => word(fo(o(a))))

  private def unaryValue(name: String, g: Gen[BigInt])(
      fu: UInt256 => Outcome,
      fo: BigIntUInt256 => Outcome
  ): Op[BigInt] =
    Op(name, g, hex)(a => fu(u(a)), a => fo(o(a)))

  private def binaryWord(name: String, ga: Gen[BigInt], gb: Gen[BigInt])(
      fu: (UInt256, UInt256) => UInt256,
      fo: (BigIntUInt256, BigIntUInt256) => BigIntUInt256
  ): Op[(BigInt, BigInt)] =
    Op(name, Gen.zip(ga, gb), hex2)({ case (a, b) => word(fu(u(a), u(b))) }, { case (a, b) => word(fo(o(a), o(b))) })

  private def binaryValue(name: String, ga: Gen[BigInt], gb: Gen[BigInt])(
      fu: (UInt256, UInt256) => Outcome,
      fo: (BigIntUInt256, BigIntUInt256) => Outcome
  ): Op[(BigInt, BigInt)] =
    Op(name, Gen.zip(ga, gb), hex2)({ case (a, b) => fu(u(a), u(b)) }, { case (a, b) => fo(o(a), o(b)) })

  private def ternaryWord(name: String, g: Gen[BigInt], gm: Gen[BigInt])(
      fu: (UInt256, UInt256, UInt256) => UInt256,
      fo: (BigIntUInt256, BigIntUInt256, BigIntUInt256) => BigIntUInt256
  ): Op[(BigInt, BigInt, BigInt)] =
    Op(name, Gen.zip(g, g, gm), hex3)(
      { case (a, b, m) => word(fu(u(a), u(b), u(m))) },
      { case (a, b, m) => word(fo(o(a), o(b), o(m))) }
    )

  // ── Generators (R-12) ───────────────────────────────────────────────────────────────────────────────────────────

  private val uniformWord: Gen[BigInt] =
    Gen.zip(Gen.long, Gen.long, Gen.long, Gen.long).map((a, b, c, d) => fromLimbs(a, b, c, d))

  /** Bit-length strata: L uniform in 0..256, then uniform below 2^L. */
  private val bitLengthWord: Gen[BigInt] =
    Gen.zip(Gen.choose(0, 256), uniformWord).map((l, x) => if l == 0 then BigInt(0) else x >> (256 - l))

  private val limbPattern: Gen[Long] =
    Gen.oneOf(
      Gen.const(0L),
      Gen.const(1L),
      Gen.const(-1L),
      Gen.const(Long.MinValue),
      Gen.const(Long.MaxValue),
      Gen.long
    )

  /** Each limb independently from {0, 1, -1L, Long.MinValue, Long.MaxValue, uniform}: carries, borrows, signed limbs.
    */
  private val limbPatternWord: Gen[BigInt] =
    Gen.zip(limbPattern, limbPattern, limbPattern, limbPattern).map((a, b, c, d) => fromLimbs(a, b, c, d))

  val Anchors: Seq[BigInt] = Seq(BigInt(0), BigInt(1), pow2(64), pow2(128), pow2(192), pow2(255), MaxWord)

  /** k ± δ, δ in 0..64, for each anchor k; each also negated in two's complement. */
  private val anchorWord: Gen[BigInt] =
    for
      k <- Gen.oneOf(Anchors)
      d <- Gen.choose(0, 64)
      up <- Gen.oneOf(true, false)
      negate <- Gen.oneOf(true, false)
    yield
      val x = (if up then k + d else k - d).mod(TwoPow256)
      if negate then neg(x) else x

  private val smallWord: Gen[BigInt] = Gen.choose(0, 300).map(BigInt(_))

  /** The operand mixture of R-12: uniform, bit-length strata, limb patterns, anchors ± δ, small values. */
  val wordGen: Gen[BigInt] = Gen.oneOf(uniformWord, bitLengthWord, limbPatternWord, anchorWord, smallWord)

  /** Divisors and moduli: 0 and 1 at 1/8 weight, otherwise the word mixture. */
  val divisorGen: Gen[BigInt] = Gen.frequency(1 -> Gen.oneOf(BigInt(0), BigInt(1)), 7 -> wordGen)

  val ShiftBoundaries: Seq[Int] = Seq(0, 1, 63, 64, 65, 127, 128, 129, 191, 192, 193, 254, 255)

  /** Shift amounts 0..255 only: the named exclusion D2 (E29, E30). */
  val shiftGen: Gen[BigInt] =
    Gen.frequency(3 -> Gen.choose(0, 255), 1 -> Gen.oneOf(ShiftBoundaries)).map(BigInt(_))

  /** SIGNEXTEND and BYTE indices: mostly 0..40, and full words (E24, E25: 2^64, 2^255, MaxValue). */
  val indexGen: Gen[BigInt] = Gen.frequency(3 -> Gen.choose(0, 40).map(BigInt(_)), 1 -> wordGen)

  /** `apply(BigInt)` inputs: uniform in [-2^600, 2^600], k·2^256 ± δ, and words of either sign. */
  val applyBigIntGen: Gen[BigInt] = Gen.frequency(
    4 -> Gen.choose(-pow2(600), pow2(600)),
    1 -> Gen.zip(Gen.choose(-3, 3), Gen.choose(-64, 64)).map((k, d) => TwoPow256 * k + d),
    1 -> wordGen,
    1 -> wordGen.map(x => -x)
  )

  private val longGen: Gen[Long] =
    Gen.frequency(4 -> Gen.long, 1 -> Gen.oneOf(0L, 1L, -1L, Long.MinValue, Long.MaxValue, Int.MinValue.toLong))
  private val intGen: Gen[Int] = Gen.frequency(4 -> Gen.long.map(_.toInt), 1 -> Gen.oneOf(0, 1, -1, Int.MinValue))

  /** `apply(ByteString)` inputs: lengths 0..33, with leading zeros. */
  val byteStringGen: Gen[ByteString] =
    for
      len <- Gen.choose(0, 33)
      zeros <- Gen.choose(0, len)
      body <- Gen.listOfN(len - zeros, Gen.long.map(_.toByte))
    yield ByteString(Array.fill[Byte](zeros)(0) ++ body)

  private def showBytes(b: ByteString): String = s"${b.length} bytes 0x${b.map(x => f"$x%02x").mkString}"

  /** A non-`UInt256` operand for the cooperative `equals` (P11): the other word or this one, as BigInt, Long, Int or a
    * String.
    */
  private def other(kind: Int, a: BigInt, b: BigInt): Any = kind match
    case 0 => b
    case 1 => b.toLong
    case 2 => b.toInt
    case 3 => a
    case 4 => a.toLong
    case 5 => a.toInt
    case _ => a.toString

  // ── The operations, one per member (45 + the conversions) ───────────────────────────────────────────────────────

  val Add: Op[(BigInt, BigInt)] = binaryWord("+", wordGen, wordGen)(_ + _, _ + _)
  val Sub: Op[(BigInt, BigInt)] = binaryWord("-", wordGen, wordGen)(_ - _, _ - _)
  val Mul: Op[(BigInt, BigInt)] = binaryWord("*", wordGen, wordGen)(_ * _, _ * _)
  val And: Op[(BigInt, BigInt)] = binaryWord("&", wordGen, wordGen)(_ & _, _ & _)
  val Or: Op[(BigInt, BigInt)] = binaryWord("|", wordGen, wordGen)(_ | _, _ | _)
  val Xor: Op[(BigInt, BigInt)] = binaryWord("^", wordGen, wordGen)(_ ^ _, _ ^ _)
  val Div: Op[(BigInt, BigInt)] = binaryWord("div", wordGen, divisorGen)(_.div(_), _.div(_))
  val Sdiv: Op[(BigInt, BigInt)] = binaryWord("sdiv", wordGen, divisorGen)(_.sdiv(_), _.sdiv(_))
  val Mod: Op[(BigInt, BigInt)] = binaryWord("mod", wordGen, divisorGen)(_.mod(_), _.mod(_))
  val Smod: Op[(BigInt, BigInt)] = binaryWord("smod", wordGen, divisorGen)(_.smod(_), _.smod(_))
  val Exp: Op[(BigInt, BigInt)] = binaryWord("**", wordGen, wordGen)(_ ** _, _ ** _)
  val Min: Op[(BigInt, BigInt)] = binaryWord("min", wordGen, wordGen)(_.min(_), _.min(_))
  val Max: Op[(BigInt, BigInt)] = binaryWord("max", wordGen, wordGen)(_.max(_), _.max(_))
  val FillingAdd: Op[(BigInt, BigInt)] = binaryWord("fillingAdd", wordGen, wordGen)(_.fillingAdd(_), _.fillingAdd(_))
  val Shl: Op[(BigInt, BigInt)] = binaryWord("<<", wordGen, shiftGen)(_ << _, _ << _)
  val Shr: Op[(BigInt, BigInt)] = binaryWord(">>", wordGen, shiftGen)(_ >> _, _ >> _)
  val Sar: Op[(BigInt, BigInt)] = binaryWord("sshift", wordGen, shiftGen)(_.sshift(_), _.sshift(_))
  val SignExtend: Op[(BigInt, BigInt)] = binaryWord("signExtend", wordGen, indexGen)(_.signExtend(_), _.signExtend(_))
  val GetByte: Op[(BigInt, BigInt)] = binaryWord("getByte", wordGen, indexGen)(_.getByte(_), _.getByte(_))
  val AddMod: Op[(BigInt, BigInt, BigInt)] = ternaryWord("addmod", wordGen, divisorGen)(_.addmod(_, _), _.addmod(_, _))
  val MulMod: Op[(BigInt, BigInt, BigInt)] = ternaryWord("mulmod", wordGen, divisorGen)(_.mulmod(_, _), _.mulmod(_, _))

  val Neg: Op[BigInt] = unaryWord("unary_-", wordGen)(-_, -_)
  val Not: Op[BigInt] = unaryWord("unary_~", wordGen)(~_, ~_)

  /** `getBalance`'s `UInt256(a.balance)` (P16): a word through `toBigInt` and `apply(BigInt)` is itself. */
  val RoundTrip: Op[BigInt] =
    unaryWord("apply(toBigInt)", wordGen)(x => UInt256(x.toBigInt), x => BigIntUInt256(x.toBigInt))

  val Compare: Op[(BigInt, BigInt)] = binaryValue("compare", wordGen, wordGen)(
    (a, b) => int(a.compare(b)),
    (a, b) => int(a.compare(b))
  )
  val Lt: Op[(BigInt, BigInt)] = binaryValue("<", wordGen, wordGen)((a, b) => bool(a < b), (a, b) => bool(a < b))
  val Gt: Op[(BigInt, BigInt)] = binaryValue(">", wordGen, wordGen)((a, b) => bool(a > b), (a, b) => bool(a > b))
  val Le: Op[(BigInt, BigInt)] = binaryValue("<=", wordGen, wordGen)((a, b) => bool(a <= b), (a, b) => bool(a <= b))
  val Ge: Op[(BigInt, BigInt)] = binaryValue(">=", wordGen, wordGen)((a, b) => bool(a >= b), (a, b) => bool(a >= b))
  val Slt: Op[(BigInt, BigInt)] =
    binaryValue("slt", wordGen, wordGen)((a, b) => bool(a.slt(b)), (a, b) => bool(a.slt(b)))
  val Sgt: Op[(BigInt, BigInt)] =
    binaryValue("sgt", wordGen, wordGen)((a, b) => bool(a.sgt(b)), (a, b) => bool(a.sgt(b)))

  /** `equals` between two words; a third of the cases compare a word with an equal one. */
  val EqualsWord: Op[(BigInt, BigInt)] =
    val pairs = Gen.frequency(2 -> Gen.zip(wordGen, wordGen), 1 -> wordGen.map(a => (a, a)))
    Op("equals(UInt256)", pairs, hex2)(
      { case (a, b) => bool(u(a).equals(u(b))) },
      { case (a, b) => bool(o(a).equals(o(b))) }
    )

  /** The cooperative `equals` with a non-`UInt256` (S6, P11): BigInt, Long, Int and String operands. */
  val EqualsOther: Op[(Int, BigInt, BigInt)] =
    Op[(Int, BigInt, BigInt)](
      "equals(non-UInt256)",
      Gen.zip(Gen.choose(0, 6), wordGen, wordGen),
      (k, a, b) => s"kind $k, ${hex(a)}, ${hex(b)}"
    )(
      (k, a, b) => bool(u(a).equals(other(k, a, b))),
      (k, a, b) => bool(o(a).equals(other(k, a, b)))
    )

  val IsZero: Op[BigInt] = unaryValue("isZero", wordGen)(x => bool(x.isZero), x => bool(x.isZero))
  val ByteSize: Op[BigInt] = unaryValue("byteSize", wordGen)(x => int(x.byteSize), x => int(x.byteSize))
  val ToInt: Op[BigInt] = unaryValue("toInt", wordGen)(x => int(x.toInt), x => int(x.toInt))
  val ToLong: Op[BigInt] = unaryValue("toLong", wordGen)(x => long(x.toLong), x => long(x.toLong))

  /** `toByte` resolves through `uint256ToBigInt` today and is a member from S1 (FR-009); both must be `BigInt.toByte`.
    */
  val ToByte: Op[BigInt] = unaryValue("toByte", wordGen)(x => byte(x.toByte), x => byte(x.toByte))
  val ToBigInt: Op[BigInt] = unaryValue("toBigInt", wordGen)(x => big(x.toBigInt), x => big(x.toBigInt))
  val Uint256ToBigInt: Op[BigInt] = unaryValue("uint256ToBigInt", wordGen)(
    x => big(UInt256.uint256ToBigInt(x)),
    x => big(BigIntUInt256.uint256ToBigInt(x))
  )
  val Bytes: Op[BigInt] = unaryValue("bytes", wordGen)(x => bytesOut(x.bytes), x => bytesOut(x.bytes))
  val ToHexString: Op[BigInt] = unaryValue("toHexString", wordGen)(x => str(x.toHexString), x => str(x.toHexString))
  val ToStringOp: Op[BigInt] = unaryValue("toString", wordGen)(x => str(x.toString), x => str(x.toString))
  val ToDecString: Op[BigInt] = unaryValue("toDecString", wordGen)(x => str(x.toDecString), x => str(x.toDecString))
  val ToSignedDecString: Op[BigInt] =
    unaryValue("toSignedDecString", wordGen)(x => str(x.toSignedDecString), x => str(x.toSignedDecString))

  /** `toSign` is retired at S2 (T054); this operation goes with it. */
  val ToSign: Op[BigInt] = unaryValue("toSign", wordGen)(x => big(x.toSign), x => big(x.toSign))
  val HashCode: Op[BigInt] = unaryValue("hashCode", wordGen)(x => int(x.hashCode), x => int(x.hashCode))

  val ApplyBigInt: Op[BigInt] =
    Op("apply(BigInt)", applyBigIntGen, hex)(n => word(UInt256(n)), n => word(BigIntUInt256(n)))
  val ToUInt256: Op[BigInt] =
    Op("toUInt256", applyBigIntGen, hex)(n => word(UInt256.toUInt256(n)), n => word(BigIntUInt256.toUInt256(n)))
  val ApplyLong: Op[Long] = Op[Long]("apply(Long)", longGen, _.toString)(
    n => word(UInt256(n)),
    n => word(BigIntUInt256(n))
  )
  val Long2UInt256: Op[Long] = Op[Long]("long2UInt256", longGen, _.toString)(
    n => word(UInt256.long2UInt256(n)),
    n => word(BigIntUInt256.long2UInt256(n))
  )
  val Int2UInt256: Op[Int] = Op[Int]("int2UInt256", intGen, _.toString)(
    n => word(UInt256.int2UInt256(n)),
    n => word(BigIntUInt256.int2UInt256(n))
  )
  val Byte2UInt256: Op[Byte] = Op[Byte](
    "byte2UInt256",
    Gen.long.map(_.toByte),
    _.toString,
    domain = Some((Byte.MinValue.toInt to Byte.MaxValue.toInt).map(_.toByte))
  )(n => word(UInt256.byte2UInt256(n)), n => word(BigIntUInt256.byte2UInt256(n)))
  val ApplyBoolean: Op[Boolean] =
    Op[Boolean]("apply(Boolean)", Gen.oneOf(true, false), _.toString, domain = Some(Seq(true, false)))(
      b => word(UInt256(b)),
      b => word(BigIntUInt256(b))
    )
  val Bool2UInt256: Op[Boolean] =
    Op[Boolean]("bool2UInt256", Gen.oneOf(true, false), _.toString, domain = Some(Seq(true, false)))(
      b => word(UInt256.bool2UInt256(b)),
      b => word(BigIntUInt256.bool2UInt256(b))
    )
  val ApplyByteString: Op[ByteString] =
    Op[ByteString]("apply(ByteString)", byteStringGen, showBytes, mayThrow = true)(
      b => word(UInt256(b)),
      b => word(BigIntUInt256(b))
    )
  val ApplyArray: Op[ByteString] =
    Op[ByteString]("apply(Array[Byte])", byteStringGen, showBytes, mayThrow = true)(
      b => word(UInt256(b.toArray)),
      b => word(BigIntUInt256(b.toArray))
    )

  /** Every operation, in the order the suites run them. `/` has none (D1). */
  val ops: Seq[Op[?]] = Seq(
    Add,
    Sub,
    Mul,
    And,
    Or,
    Xor,
    Div,
    Sdiv,
    Mod,
    Smod,
    Exp,
    AddMod,
    MulMod,
    Shl,
    Shr,
    Sar,
    SignExtend,
    GetByte,
    FillingAdd,
    Min,
    Max,
    Neg,
    Not,
    Compare,
    Lt,
    Gt,
    Le,
    Ge,
    Slt,
    Sgt,
    EqualsWord,
    EqualsOther,
    IsZero,
    ByteSize,
    ToInt,
    ToLong,
    ToByte,
    ToBigInt,
    Uint256ToBigInt,
    Bytes,
    ToHexString,
    ToStringOp,
    ToDecString,
    ToSignedDecString,
    ToSign,
    HashCode,
    RoundTrip,
    ApplyBigInt,
    ToUInt256,
    ApplyLong,
    Long2UInt256,
    Int2UInt256,
    Byte2UInt256,
    ApplyBoolean,
    Bool2UInt256,
    ApplyByteString,
    ApplyArray
  )

  // ── The runner ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** Fixed: the case set must not depend on the host (constitution III). Chunks run on `availableProcessors` threads.
    */
  val Chunks: Int = 16

  /** At most this many failures are reported per chunk. */
  private val FailuresPerChunk: Int = 5

  /** The result of one operation's run: how many cases ran, and the first failures. */
  final case class RunResult(cases: Long, failures: Seq[String])

  private def chunkSeed(name: String, chunk: Int): Seed =
    Seed((MurmurHash3.stringHash(name).toLong << 32) ^ (MurmurHash3.stringHash(s"$name#$chunk").toLong & 0xffffffffL))

  /** Runs `op` over `cases` generated cases (or its whole domain), split into [[Chunks]] fixed-seed chunks. */
  def runOp[A](op: Op[A], cases: Long): RunResult =
    op.domain match
      case Some(all) => RunResult(all.size.toLong, all.flatMap(op.mismatch).take(FailuresPerChunk))
      case None =>
        val params = Gen.Parameters.default
        val perChunk = (cases + Chunks - 1) / Chunks
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors.max(1))
        try
          val tasks = (0 until Chunks).map { chunk =>
            new Callable[Seq[String]]:
              def call(): Seq[String] =
                var seed = chunkSeed(op.name, chunk)
                val failures = Seq.newBuilder[String]
                var found = 0
                var i = 0L
                while i < perChunk do
                  val a = op.gen.pureApply(params, seed)
                  seed = seed.next
                  op.mismatch(a) match
                    case Some(f) if found < FailuresPerChunk =>
                      failures += s"[chunk $chunk, case $i] $f"
                      found += 1
                    case _ => ()
                  i += 1
                failures.result()
          }
          val failures = pool.invokeAll(tasks.asJava).asScala.toSeq.flatMap(_.get())
          RunResult(perChunk * Chunks, failures)
        finally pool.shutdownNow()

  // ── Named rows ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** One named case. `failure` returns the problems, or None. */
  final case class Row(id: String, label: String, failure: () => Option[String])

  private def row[A](id: String, op: Op[A], a: A, expected: Outcome): Row =
    Row(id, op.name, () => op.check(a, Some(expected)))

  /** A differential-only case: GT-1 names no literal result (E21's "oracle" cells, L rows with neighbours). */
  private def same[A](id: String, op: Op[A], a: A): Row = Row(id, op.name, () => op.check(a, None))

  private def custom(id: String, label: String)(f: => Option[String]): Row = Row(id, label, () => f)

  private val MinSigned: BigInt = pow2(255) // -2^255 as a word
  private val MaxSigned: BigInt = pow2(255) - 1
  private val minus1: BigInt = MaxWord
  private val x0: BigInt = BigInt("123456789abcdef0fedcba9876543210", 16) * pow2(100) + 12345

  private def sdivModel(a: BigInt, b: BigInt): BigInt =
    if b == 0 then 0 else signed(toSigned(a) / toSigned(b)) // BigInt `/` truncates toward zero
  private def smodModel(a: BigInt, b: BigInt): BigInt =
    if b == 0 then 0 else signed(toSigned(a) % toSigned(b).abs) // `%`: the sign follows the dividend

  // E01–E35 (GT-1 part A §5; E06, E29 and E30 are the named exclusions)
  private val eRows: Seq[Row] = Seq(
    row("E01", Add, (MaxWord, BigInt(1)), w(0)),
    row("E02", Sub, (BigInt(0), BigInt(1)), w(MaxWord)),
    row("E03", Mul, (MaxWord, MaxWord), w(1)),
    row("E03", Mul, (pow2(128), pow2(128)), w(0)),
    row("E03", Mul, (pow2(255), BigInt(2)), w(0)),
    row("E04", Div, (BigInt(0), BigInt(0)), w(0)),
    row("E04", Div, (BigInt(1), BigInt(0)), w(0)),
    row("E04", Div, (MaxWord, BigInt(0)), w(0)),
    row("E05", Div, (MaxWord, BigInt(1)), w(MaxWord)),
    row("E05", Div, (BigInt(0), x0), w(0)),
    row("E05", Div, (x0, x0), w(1)),
    row("E05", Div, (x0, x0 + 1), w(0)),
    row("E05", Div, (MaxWord, BigInt(2)), w(MaxSigned)),
    row("E07", Sdiv, (MinSigned, minus1), w(MinSigned)),
    row("E08", Sdiv, (x0, BigInt(0)), w(0)),
    row("E08", Sdiv, (minus1, minus1), w(1)),
    row("E08", Sdiv, (minus1, MaxWord), w(1)),
    row("E09", Sdiv, (signed(-7), BigInt(2)), w(signed(-3))),
    row("E09", Sdiv, (BigInt(7), signed(-2)), w(signed(-3))),
    row("E09", Sdiv, (signed(-7), signed(-2)), w(3)),
    row("E10", Sdiv, (MinSigned, BigInt(1)), w(MinSigned)),
    row("E10", Sdiv, (MinSigned, MinSigned), w(1)),
    row("E10", Sdiv, (BigInt(1), MinSigned), w(0)),
    // Part A lists "2^255−1, −2^255 → −1"; truncation toward zero gives −1 for (−2^255) / (2^255−1) and 0 for the
    // reverse, so both orders are pinned.
    row("E10", Sdiv, (MinSigned, MaxSigned), w(signed(-1))),
    row("E10", Sdiv, (MaxSigned, MinSigned), w(0)),
    row("E11", Mod, (x0, BigInt(0)), w(0)),
    row("E11", Mod, (MaxWord, BigInt(1)), w(0)),
    row("E11", Mod, (x0, MaxWord), w(x0)),
    row("E12", Smod, (signed(-7), BigInt(2)), w(signed(-1))),
    row("E12", Smod, (BigInt(7), signed(-2)), w(1)),
    row("E12", Smod, (signed(-7), signed(-2)), w(signed(-1))),
    row("E13", Smod, (MinSigned, minus1), w(0)),
    row("E13", Smod, (MinSigned, MinSigned), w(0)),
    row("E13", Smod, (x0, BigInt(0)), w(0)),
    row("E13", Smod, (MinSigned, MaxSigned), w(signed(-1))),
    row("E13", Smod, (MaxSigned, MinSigned), w(MaxSigned)),
    row("E14", AddMod, (MaxWord, BigInt(1), MaxWord), w(1)),
    row("E15", AddMod, (MaxWord, BigInt(2), BigInt(3)), w(2)),
    row("E16", AddMod, (x0, MaxWord, BigInt(0)), w(0)),
    row("E16", AddMod, (x0, MaxWord, BigInt(1)), w(0)),
    row("E16", AddMod, (MaxWord, MaxWord, MaxWord), w(0)),
    row("E16", AddMod, (MaxWord, MaxWord, BigInt(2)), w(0)),
    row("E17", MulMod, (MaxWord, MaxWord, BigInt(12)), w(9)),
    row("E18", MulMod, (x0, MaxWord, BigInt(0)), w(0)),
    row("E18", MulMod, (x0, MaxWord, BigInt(1)), w(0)),
    row("E18", MulMod, (MaxWord, MaxWord, MaxWord), w(0)),
    row("E18", MulMod, (pow2(255), BigInt(2), MaxWord), w(1)),
    row("E19", Exp, (BigInt(0), BigInt(0)), w(1)),
    row("E20", Exp, (BigInt(0), BigInt(5)), w(0)),
    row("E20", Exp, (MaxWord, BigInt(0)), w(1)),
    row("E20", Exp, (BigInt(1), MaxWord), w(1)),
    row("E21", Exp, (BigInt(2), BigInt(255)), w(pow2(255))),
    row("E21", Exp, (BigInt(2), BigInt(256)), w(0)),
    row("E21", Exp, (MaxWord, BigInt(2)), w(1)),
    row("E21", Exp, (MaxWord, MaxWord), w(MaxWord)),
    same("E21", Exp, (BigInt(3), MaxWord)),
    same("E21", Exp, (x0, x0 * 7 + 3)),
    row("E22", ByteSize, BigInt(0), int(0)),
    row("E22", ByteSize, BigInt(1), int(1)),
    row("E22", ByteSize, BigInt(255), int(1)),
    row("E22", ByteSize, BigInt(256), int(2)),
    row("E22", ByteSize, pow2(64) - 1, int(8)),
    row("E22", ByteSize, pow2(64), int(9)),
    row("E22", ByteSize, MaxWord, int(32)),
    row("E23", SignExtend, (BigInt(0x80), BigInt(0)), w(TwoPow256 - 128)),
    row("E23", SignExtend, (BigInt(0x7f), BigInt(0)), w(0x7f)),
    row("E23", SignExtend, (BigInt(0x1ff), BigInt(0)), w(MaxWord)),
    row("E23", SignExtend, (pow2(247), BigInt(30)), w(TwoPow256 - pow2(247)))
  ) ++ (for
    b <- Seq(BigInt(31), BigInt(32), pow2(64), pow2(255), MaxWord)
    x <- Seq(BigInt(0x80), pow2(255) + 5)
  yield row("E24", SignExtend, (x, b), w(x))) ++ Seq(
    row("E25", GetByte, (pow2(255), BigInt(0)), w(0x80)),
    row("E25", GetByte, (BigInt(0xff), BigInt(31)), w(0xff)),
    row("E25", GetByte, (MaxWord, BigInt(32)), w(0)),
    row("E25", GetByte, (MaxWord, pow2(64)), w(0)),
    row("E25", GetByte, (MaxWord, MaxWord), w(0)),
    row("E26", Shl, (BigInt(1), BigInt(0)), w(1)),
    row("E26", Shl, (BigInt(1), BigInt(1)), w(2)),
    row("E26", Shl, (BigInt(1), BigInt(255)), w(pow2(255))),
    row("E27", Shr, (x0, BigInt(0)), w(x0)),
    row("E27", Shr, (x0, BigInt(1)), w(x0 >> 1)),
    row("E27", Shr, (MaxWord, BigInt(255)), w(1))
  ) ++ (for s <- 0 to 255 by 17 yield row("E28", Sar, (minus1, BigInt(s)), w(MaxWord))) ++ Seq(
    row("E28", Sar, (MinSigned, BigInt(255)), w(MaxWord)),
    row("E28", Sar, (MinSigned, BigInt(254)), w(signed(-2))),
    row("E28", Sar, (MaxSigned, BigInt(254)), w(1)),
    // E31 has no UInt256 member until S2 (`numberOfLeadingZeros`, T044, adds its member rows), so it is pinned here
    // at the opcode, on ETH Osaka and ETC Olympia: 256 - bitLength, across the limb boundaries.
    custom("E31", "CLZ (EIP-7939) across the limb boundaries") {
      val vectors = Seq(
        BigInt(0) -> 256,
        BigInt(1) -> 255,
        pow2(63) -> 192,
        (pow2(64) - 1) -> 192,
        pow2(64) -> 191,
        pow2(128) -> 127,
        pow2(192) -> 63,
        pow2(255) -> 0,
        MaxWord -> 0
      )
      val chains = Seq(
        ("ETH Osaka", PinHarness.ethOsaka._1, PinHarness.ethOsaka._2),
        ("ETC Olympia", EtcForkMatrix.Olympia.evmConfig, EtcForkMatrix.Olympia.header)
      )
      val fails = for
        (chain, config, header) <- chains
        (x, count) <- vectors
        out = CLZ.execute(PinHarness.state(config, header, PinHarness.operands(UInt256(x))))
        got = PinHarness.words(out.stack)
        if out.error.isDefined || got != Seq(UInt256(count))
      yield s"$chain: CLZ(${hex(x)}) = $got, error ${out.error}, expected $count"
      Option.when(fails.nonEmpty)(fails.mkString("; "))
    },
    row("E32", Gt, (pow2(255), BigInt(1)), bool(true)),
    row("E32", Lt, (pow2(63), pow2(64)), bool(true)),
    row("E32", Gt, (pow2(64), pow2(63)), bool(true)),
    row("E33", Slt, (pow2(255), BigInt(1)), bool(true)),
    row("E33", Slt, (MaxWord, BigInt(0)), bool(true)),
    row("E33", Sgt, (BigInt(0), MaxWord), bool(true)),
    row("E33", Slt, (x0, x0), bool(false)),
    row("E33", Sgt, (x0, x0), bool(false)),
    row("E33", Sgt, (MaxSigned, pow2(255)), bool(true)),
    row("E34", Not, BigInt(0), w(MaxWord)),
    row("E34", Neg, BigInt(0), w(0)),
    row("E34", Neg, BigInt(1), w(MaxWord)),
    row("E34", Neg, pow2(255), w(pow2(255))),
    row("E35", Le, (pow2(255), MaxSigned), bool(false)),
    row("E35", Gt, (pow2(255), BigInt(0)), bool(true))
  )

  // C01–C12 (GT-1 part A §5; C10 is E22)
  private val cRows: Seq[Row] = Seq(
    row("C01", ToInt, pow2(31) - 1, int(Int.MaxValue)),
    row("C01", ToInt, pow2(31), int(0)),
    row("C01", ToInt, pow2(32) + 5, int(5)),
    row("C01", ToInt, MaxWord, int(Int.MaxValue)),
    row("C01", ToInt, BigInt(0), int(0)),
    row("C02", ToLong, pow2(63), long(0)),
    row("C02", ToLong, pow2(64) + 7, long(7)),
    row("C02", ToLong, MaxWord, long(Long.MaxValue)),
    row("C03", ToByte, BigInt(255), byte(-1)),
    row("C03", ToByte, BigInt(256), byte(0)),
    row("C03", ToByte, BigInt(0x1ff), byte(-1)),
    row("C04", ApplyByteString, ByteString.empty, w(0)),
    row("C04", ApplyByteString, ByteString(0xff.toByte), w(255)),
    row("C04", ApplyByteString, ByteString(Array.fill[Byte](31)(-1)), w(pow2(248) - 1)),
    row("C04", ApplyByteString, bytes32(x0), w(x0)),
    row("C04", ApplyByteString, ByteString(0.toByte) ++ bytes32(x0), threw[IllegalArgumentException]),
    row("C04", ApplyByteString, ByteString(Array.fill[Byte](33)(1)), threw[IllegalArgumentException]),
    row("C04", ApplyArray, ByteString(Array.fill[Byte](33)(1)), threw[IllegalArgumentException]),
    row("C05", ApplyBigInt, BigInt(-1), w(MaxWord)),
    row("C05", ApplyBigInt, -TwoPow256, w(0)),
    row("C05", ApplyBigInt, -(TwoPow256 + 1), w(MaxWord)),
    row("C05", ApplyBigInt, TwoPow256, w(0)),
    row("C05", ApplyBigInt, pow2(512) + 3, w(3)),
    row("C06", ApplyLong, -1L, w(MaxWord)),
    row("C06", ApplyLong, Long.MinValue, w(TwoPow256 - pow2(63))),
    row("C06", Int2UInt256, -1, w(MaxWord)),
    row("C06", Byte2UInt256, 0x80.toByte, w(TwoPow256 - 128)),
    row("C07", Bytes, BigInt(0), bytesOut(ByteString(new Array[Byte](32)))),
    row("C07", Bytes, pow2(255), bytesOut(ByteString(0x80.toByte) ++ ByteString(new Array[Byte](31)))),
    row("C07", Bytes, MaxWord, bytesOut(ByteString(Array.fill[Byte](32)(-1)))),
    row("C07", Bytes, BigInt(1), bytesOut(ByteString(new Array[Byte](31)) ++ ByteString(1.toByte))),
    row("C08", ToHexString, BigInt(0), str("0x00")),
    row("C08", ToHexString, BigInt(1), str("0x01")),
    row("C08", ToHexString, BigInt(0x100), str("0x0100")),
    row("C08", ToHexString, MaxWord, str("0x" + "f" * 64)),
    row("C09", ToStringOp, MaxWord, str("-1")),
    row("C09", ToStringOp, pow2(255), str((-pow2(255)).toString)),
    row("C09", ToStringOp, BigInt(5), str("5")),
    row("C11", FillingAdd, (MaxWord - 10, BigInt(20)), w(MaxWord)),
    row("C11", FillingAdd, (BigInt(0), MaxWord - 1), w(MaxWord - 1)),
    row("C11", FillingAdd, (MaxWord, BigInt(1)), w(MaxWord)),
    custom("C12", "min/max on a tie return `that`") {
      val fails = for
        n <- Seq(BigInt(0), x0, MaxWord)
        (side, minThat, maxThat) <- Seq(
          {
            val (a, b) = (UInt256(n), UInt256(n)); ("UInt256", a.min(b).eq(b), a.max(b).eq(b))
          }, {
            val (a, b) = (BigIntUInt256(n), BigIntUInt256(n)); ("oracle", a.min(b).eq(b), a.max(b).eq(b))
          }
        )
        if !(minThat && maxThat)
      yield s"$side at ${hex(n)}: min returns that = $minThat, max returns that = $maxThat"
      Option.when(fails.nonEmpty)(fails.mkString("; "))
    }
  )

  // P01–P06 and P10–P16 (data-model §9; P07–P09 are S0's codec goldens in UInt256GoldenBytesSpec)
  private val pRows: Seq[Row] = Seq(
    row("P01", ApplyLong, -1L, w(MaxWord)),
    row("P01", ApplyLong, Long.MinValue, w(TwoPow256 - pow2(63))),
    row("P02", Int2UInt256, -1, w(MaxWord)),
    row("P02", Byte2UInt256, (-1).toByte, w(MaxWord)),
    row("P02", Byte2UInt256, 0x80.toByte, w(TwoPow256 - 128))
  ) ++ (for n <- Seq(-TwoPow256 - 1, -TwoPow256, -pow2(255), BigInt(-1), TwoPow256, TwoPow256 + 1, pow2(512) - 1) ++
      (for k <- -2 to 2; d <- -2 to 2 yield TwoPow256 * k + d)
  yield row("P03", ApplyBigInt, n, w(n))) ++ (for
    len <- 0 to 33
    zeros <- Seq(0, len / 2, len).distinct
  yield
    val bs = ByteString(Array.fill[Byte](zeros)(0) ++ Array.tabulate[Byte](len - zeros)(i => (0x80 + i).toByte))
    val expected = if len > 32 then threw[IllegalArgumentException] else w(BigInt(1, bs.toArray))
    row("P04", ApplyByteString, bs, expected)
  ) ++ (for n <- Seq(pow2(63), pow2(64) - 1, pow2(127), pow2(128) - 1, pow2(191), pow2(255), MaxWord)
  yield row("P05", ToBigInt, n, big(n))) ++ (for n <- Seq(pow2(255) + 1, pow2(192) + 5, pow2(64), pow2(255), MaxWord)
  yield row("P06", Bytes, n, bytesOut(bytes32(n)))) ++ Seq(
    row("P10", ToInt, pow2(31), int(0)),
    row("P10", ToInt, pow2(32) + 5, int(5)),
    row("P10", ToInt, MaxWord, int(Int.MaxValue)),
    row("P10", ToLong, pow2(63), long(0)),
    row("P10", ToLong, MaxWord, long(Long.MaxValue)),
    row("P11", EqualsOther, (3, BigInt(0), BigInt(0)), bool(true)), // BigInt(0)
    row("P11", EqualsOther, (4, BigInt(0), BigInt(0)), bool(true)), // 0L
    row("P11", EqualsOther, (5, BigInt(0), BigInt(0)), bool(true)) // 0
  ) ++ (for n <- Seq(
      BigInt(0),
      BigInt(1),
      BigInt(Int.MaxValue),
      pow2(31),
      pow2(63) - 1,
      pow2(63),
      pow2(64),
      pow2(255),
      MaxWord
    )
  yield row("P11", HashCode, n, int(n.hashCode))) ++ Seq(
    row("P12", Compare, (pow2(255), MaxSigned), int(1)),
    row("P12", Gt, (pow2(255), MaxSigned), bool(true)),
    custom("P13", "Timestamp(-1L).toUInt256") {
      val got = Timestamp(-1L).toUInt256
      Option.when(got.toBigInt != pow2(64) - 1)(s"Timestamp(-1L).toUInt256 = ${hex(got.toBigInt)}, expected 2^64 - 1")
    },
    custom("P14", "Address(UInt256) keeps the last 20 bytes") {
      val fails = for
        n <- Seq(pow2(160) + 0xabcdef, MaxWord, pow2(255) + 1, BigInt(0))
        got = Address(UInt256(n)).bytes
        exp = bytes32(n).drop(12)
        if got != exp
      yield s"Address(${hex(n)}) = $got, expected $exp"
      Option.when(fails.nonEmpty)(fails.mkString("; "))
    },
    custom("P15", "calcMemCost's out-of-gas sentinel is exactly the BigInt 2^255 - 1") {
      val configs = Seq("ETH Osaka" -> PinHarness.ethOsaka._1, "ETC Spiral" -> EtcForkMatrix.Spiral.evmConfig)
      val fails = for
        (chain, config) <- configs
        got = config.calcMemCost(0, MaxWord, 1)
        if got != pow2(255) - 1
      yield s"$chain: calcMemCost(0, 2^256 - 1, 1) = $got"
      Option.when(fails.nonEmpty)(fails.mkString("; "))
    },
    custom("P16", "WorldStateProxy.getBalance round-trips the stored balance") {
      val who = Address(0xba1)
      val fails = for
        n <- Anchors ++ Seq(x0, pow2(255) - 1, pow2(63), pow2(64) - 1)
        got = MockWorldState().saveAccount(who, Account(balance = UInt256(n))).getBalance(who)
        if got.toBigInt != n
      yield s"balance ${hex(n)} read back as ${hex(got.toBigInt)}"
      Option.when(fails.nonEmpty)(fails.mkString("; "))
    }
  ) ++ (for n <- Anchors ++ Seq(x0, pow2(63), pow2(255) - 1) yield row("P16", RoundTrip, n, w(n)))

  /** L07's and L08's operands, as little-endian limbs (data-model §9, research R-12). L07 is Hacker's Delight's
    * add-back example from `divmnu`, scaled from 32-bit to 64-bit digits, and its neighbours; L08's force D3's
    * correction twice (the first is Hacker's Delight's "qhat = b + 1" case, scaled). Both sets were checked with a
    * base-2^64 model of `divmnu` (Knuth's D3 test and D6 add-back) to reach the step they name; T039's scoverage report
    * confirms it on the real kernels.
    */
  private val H: Long = Long.MinValue // 2^63
  private val L07Cases: Seq[(BigInt, BigInt)] = Seq(
    (fromLimbs(0, 0, H, Long.MaxValue), fromLimbs(1, 0, H, 0)), // the example: q = 2^64 - 2, D6 taken
    (fromLimbs(1, 0, H, Long.MaxValue), fromLimbs(1, 0, H, 0)),
    (fromLimbs(0, 0, H, Long.MaxValue), fromLimbs(2, 0, H, 0)),
    (fromLimbs(3, 0, H, 0), fromLimbs(1, 0, 1L << 61, 0)),
    (fromLimbs(3, -2, -1, 0), fromLimbs(-2, -2, -1, 0)),
    (fromLimbs(H, 0, -2, 0), fromLimbs(Long.MaxValue, 0, Long.MaxValue, 0)),
    (fromLimbs(1, -2, -2, Long.MaxValue), fromLimbs(1, 1, H + 1, 0)),
    (fromLimbs(H + 1, H, 1, -2), fromLimbs((1L << 62) - 1, 0, 0x5555555555555555L, 0)),
    (fromLimbs(1, H + 1, Long.MaxValue, -2), fromLimbs((1L << 61) + 1, H + 1, Long.MaxValue, -2)),
    (fromLimbs(1L << 62, (1L << 62) - 1, 1, -2), fromLimbs(3, 0x5555555555555555L, H, (1L << 62) - 1)),
    (fromLimbs(1, 0, -1, H + 1), fromLimbs(2, -1, H + 1, 0)),
    (fromLimbs(0, 1L << 62, -1, H), fromLimbs(-2, 3, 2, 0))
  )
  private val L08Cases: Seq[(BigInt, BigInt)] = Seq(
    (fromLimbs(0, -2, H, 0), fromLimbs(-1, H, 0, 0)), // Hacker's Delight's "qhat = b + 1", scaled
    (fromLimbs(1, 1L << 62, H + 1, 0), fromLimbs(-1, H + 1, 0, 0)),
    (fromLimbs(H + 1, 1L << 62, H + 1, 0), fromLimbs(-2, 1L << 62, 0, 0)),
    (fromLimbs(3, H, 1L << 62, -1), fromLimbs(-2, H + 1, 0, 0)),
    (fromLimbs(0x5555555555555555L, 2, 1L << 62, 3), fromLimbs(-1, (1L << 61) + 1, 0, 0)),
    (fromLimbs(1, (1L << 61) + 1, -2, 0), fromLimbs(H + 1, 1, 1, 0)),
    (fromLimbs(0, -1, -1, -2), fromLimbs(H, -1, H + 1, 0)),
    (fromLimbs(2, H, -2, H), fromLimbs(H, -1, (1L << 61) + 1, 0)),
    (fromLimbs(H + 1, 0, 2, -1), fromLimbs(-1, (1L << 62) - 1, 3, 1))
  )

  /** L09: divisors whose top limb has its top bit set (normalisation shift 0), with one to four limbs. */
  private val L09Divisors: Seq[BigInt] = Seq(
    fromLimbs(H, 0, 0, 0),
    fromLimbs(-1, 0, 0, 0),
    fromLimbs(5, H + 7, 0, 0),
    fromLimbs(-1, -1, H, 0),
    fromLimbs(3, 0, 0, H + 5),
    MaxWord
  )

  private val limbWords: Seq[BigInt] = Seq(
    fromLimbs(-1, 0, -1, 0),
    fromLimbs(H, H, H, H),
    fromLimbs(1, -1, 1, -1),
    fromLimbs(Long.MaxValue, 0, 0, H),
    fromLimbs(0x0123456789abcdefL, 0xfedcba9876543210L, 0x0f0f0f0f0f0f0f0fL, 0x8000000000000001L)
  )

  // L01–L09 (data-model §9: limb boundaries)
  private val lRows: Seq[Row] = (for
    x <- limbWords
    s <- Seq(63, 64, 65, 127, 128, 129, 191, 192, 193)
    r <- Seq(
      row("L01", Shl, (x, BigInt(s)), w(x << s)),
      row("L01", Shr, (x, BigInt(s)), w(x >> s)),
      row("L01", Sar, (x, BigInt(s)), w(toSigned(x) >> s))
    )
  yield r) ++ (for
    k <- 1 to 3
    n <- Seq(pow2(64 * k) - 1, pow2(64 * k), pow2(64 * k) + 1)
  yield row("L02", ByteSize, n, int((n.bitLength + 7) / 8))) ++ Seq(
    row("L03", Add, (pow2(64) - 1, BigInt(1)), w(pow2(64))),
    row("L03", Add, (pow2(128) - 1, BigInt(1)), w(pow2(128))),
    row("L03", Add, (pow2(192) - 1, BigInt(1)), w(pow2(192))),
    row("L03", Add, (MaxWord, BigInt(1)), w(0)),
    row("L03", Mul, (pow2(64) - 1, pow2(64) - 1), w((pow2(64) - 1) * (pow2(64) - 1))),
    row("L03", Mul, (pow2(128) - 1, pow2(128) - 1), w((pow2(128) - 1) * (pow2(128) - 1))),
    row("L03", Mul, (pow2(192) - 1, pow2(64) + 1), w((pow2(192) - 1) * (pow2(64) + 1))),
    row("L03", Mul, (fromLimbs(-1, -1, -1, 0), fromLimbs(-1, -1, 0, 0)), w(fromLimbs(-1, -1, -1, 0) * (pow2(128) - 1))),
    row("L04", Sub, (pow2(64), BigInt(1)), w(pow2(64) - 1)),
    row("L04", Sub, (pow2(128), BigInt(1)), w(pow2(128) - 1)),
    row("L04", Sub, (pow2(192), BigInt(1)), w(pow2(192) - 1)),
    row("L04", Sub, (BigInt(0), BigInt(1)), w(MaxWord))
  ) ++ (for
    k <- 0 to 3
    hi = pow2(63) << (64 * k)
    lo = (pow2(63) - 1) << (64 * k)
    r <- Seq(
      row("L05", Compare, (hi, lo), int(1)),
      row("L05", Compare, (lo, hi), int(-1)),
      row("L05", Gt, (hi, lo), bool(true)),
      row("L05", Lt, (hi, lo), bool(false)),
      row("L05", Slt, (hi, lo), bool(toSigned(hi) < toSigned(lo)))
    )
  yield r) ++ (for
    top <- Seq(5L, -1L, H)
    k <- 0 to 2
    a = fromLimbs(0, 0, 0, top) + (pow2(63) << (64 * k))
    b = fromLimbs(0, 0, 0, top) + ((pow2(63) - 1) << (64 * k))
    r <- Seq(
      row("L06", Sgt, (a, b), bool(true)),
      row("L06", Slt, (a, b), bool(false)),
      row("L06", Slt, (b, a), bool(true))
    )
  yield r) ++ (for
    (id, cases) <- Seq("L07" -> L07Cases, "L08" -> L08Cases)
    (a, b) <- cases
    r <- Seq(
      row(id, Div, (a, b), w(a / b)),
      row(id, Mod, (a, b), w(a % b)),
      row(id, Sdiv, (a, b), w(sdivModel(a, b))),
      row(id, Smod, (a, b), w(smodModel(a, b))),
      same(id, MulMod, (a, a, b)),
      same(id, AddMod, (a, a, b))
    )
  yield r) ++ (for
    d <- L09Divisors
    a <- Seq(MaxWord, x0, d - 1, d, d + 1, fromLimbs(0, 0, H, Long.MaxValue)).map(_.mod(TwoPow256)).distinct
    r <- Seq(
      row("L09", Div, (a, d), w(a / d)),
      row("L09", Mod, (a, d), w(a % d)),
      same("L09", MulMod, (a, MaxWord, d)),
      same("L09", AddMod, (a, MaxWord, d))
    )
  yield r)

  /** Every named row: E01–E35, C01–C12, P01–P06, P10–P16 and L01–L09. */
  val rows: Seq[Row] = eRows ++ cRows ++ pRows ++ lRows

  /** The named exclusions, documented (R-12). They have no case. */
  val Exclusions: Seq[(String, String)] = Seq(
    "E06" -> "`/` (D1): retired at T032; no case",
    "E29" -> "`<<` and `>>` with a shift above 255 (D2): compared on 0..255 only",
    "E30" -> "`sshift` with a shift above 255 (D2): compared on 0..255 only"
  )
