package com.chipprbots.ethereum.domain.oracle

// Spec 021 GT-2's oracle (T023, research R-12, data-model §4): `domain/UInt256.scala` as it stood before S1's swap,
// copied verbatim from staging c78c128d13 and renamed `UInt256` -> `BigIntUInt256`, package `domain` ->
// `domain.oracle`. Nothing else differs but the whitespace scalafmt changed after the rename. It lives in the test
// tree only, never on a main classpath. Do not "fix" or modernise it: every UInt256 member is judged against this
// file, so an edit here moves the reference.
// Its partial members are GT-2's named exclusions: `/` by zero throws (D1); `<<`, `>>` and `sshift` above 255 (D2);
// `apply` of more than 32 bytes throws (D3).

import org.apache.pekko.util.ByteString

import scala.language.implicitConversions

import com.chipprbots.ethereum.utils.ByteUtils

// scalastyle:off number.of.methods
object BigIntUInt256:

  /** Size of BigIntUInt256 byte representation */
  val Size: Int = 32

  private val Modulus: BigInt = BigInt(2).pow(256)

  /** 2^256 - 1. `n & Mask` is `n mod 2^256` for every integer `n`, negative ones included: `BigInteger.and` works on
    * the infinite two's-complement form, whose low 256 bits are exactly the non-negative residue.
    */
  private val Mask: BigInt = Modulus - 1

  val MaxValue: BigIntUInt256 = new BigIntUInt256(Modulus - 1)

  val Zero: BigIntUInt256 = new BigIntUInt256(0)

  val One: BigIntUInt256 = new BigIntUInt256(1)

  val Two: BigIntUInt256 = new BigIntUInt256(2)

  def apply(bytes: ByteString): BigIntUInt256 =
    require(bytes.length <= Size, s"Input byte array cannot be longer than $Size: ${bytes.length}")
    BigIntUInt256(ByteUtils.toBigInt(bytes))

  def apply(array: Array[Byte]): BigIntUInt256 =
    BigIntUInt256(ByteString(array))

  def apply(n: BigInt): BigIntUInt256 =
    new BigIntUInt256(boundBigInt(n))

  def apply(b: Boolean): BigIntUInt256 =
    if b then One else Zero

  def apply(n: Long): BigIntUInt256 =
    apply(BigInt(n))

  extension (bigInt: BigInt) def toUInt256: BigIntUInt256 = BigIntUInt256(bigInt)

  implicit def uint256ToBigInt(uint: BigIntUInt256): BigInt = uint.toBigInt

  implicit def byte2UInt256(b: Byte): BigIntUInt256 = BigIntUInt256(b)

  implicit def int2UInt256(i: Int): BigIntUInt256 = BigIntUInt256(i)

  implicit def long2UInt256(l: Long): BigIntUInt256 = BigIntUInt256(l)

  implicit def bool2UInt256(b: Boolean): BigIntUInt256 = BigIntUInt256(b)

  private val Zeros: ByteString = ByteString(Array.fill[Byte](Size)(0))

  /** `n mod 2^256`, non-negative. Formerly `(n % Modulus + Modulus) % Modulus`: the same value, but two BigInteger
    * divisions per call — and the second one always divides for real, its dividend being at least 2^256 — on every ADD,
    * SUB, MUL, PUSH, JUMP target and Long conversion. An in-range value is returned as it is; anything else keeps its
    * low 256 bits.
    */
  private def boundBigInt(n: BigInt): BigInt =
    if n.signum >= 0 && n.bitLength <= Size * 8 then n else n & Mask

  private val MaxSignedValue: BigInt = BigInt(2).pow(Size * 8 - 1) - 1

/** Represents 256 bit unsigned integers with standard arithmetic, byte-wise operation and EVM-specific extensions */
class BigIntUInt256 private (private val n: BigInt) extends Ordered[BigIntUInt256]:

  import BigIntUInt256.*
  require(n >= 0 && n < Modulus, s"Invalid BigIntUInt256 value: $n")

  // byte-wise operations

  /** Converts a BigInt to a ByteString. Output ByteString is padded with 0's from the left side up to
    * BigIntUInt256.Size bytes.
    */
  lazy val bytes: ByteString =
    val bs: ByteString = ByteString(n.toByteArray).takeRight(Size)
    val padLength: Int = Size - bs.length
    if padLength > 0 then Zeros.take(padLength) ++ bs
    else bs

  /** Used for gas calculation for EXP opcode. See YP Appendix H.1 (220) For n > 0: (n.bitLength - 1) / 8 + 1 == 1 +
    * floor(log_256(n))
    *
    * @return
    *   Size in bytes excluding the leading 0 bytes
    */
  def byteSize: Int = if isZero then 0 else (n.bitLength - 1) / 8 + 1

  def getByte(that: BigIntUInt256): BigIntUInt256 =
    if that.n > 31 then Zero else BigIntUInt256(bytes(that.n.toInt).toInt & 0xff)

  // standard arithmetic (note the use of new instead of apply where result is guaranteed to be within bounds)
  def &(that: BigIntUInt256): BigIntUInt256 = new BigIntUInt256(this.n & that.n)

  def |(that: BigIntUInt256): BigIntUInt256 = new BigIntUInt256(this.n | that.n)

  def ^(that: BigIntUInt256): BigIntUInt256 = new BigIntUInt256(this.n ^ that.n)

  def unary_- : BigIntUInt256 = BigIntUInt256(-n)

  def unary_~ : BigIntUInt256 = BigIntUInt256(~n)

  def +(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.n + that.n)

  def -(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.n - that.n)

  def *(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.n * that.n)

  def /(that: BigIntUInt256): BigIntUInt256 = new BigIntUInt256(this.n / that.n)

  def **(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.n.modPow(that.n, Modulus))

  def compare(that: BigIntUInt256): Int = this.n.compare(that.n)

  def min(that: BigIntUInt256): BigIntUInt256 = if compare(that) < 0 then this else that

  def max(that: BigIntUInt256): BigIntUInt256 = if compare(that) > 0 then this else that

  def isZero: Boolean = n == 0

  def <<(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.n.<<(that.toInt))

  def >>(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.n.>>(that.toInt))

  // EVM-specific arithmetic
  private lazy val signedN: BigInt = if n > MaxSignedValue then n - Modulus else n

  private def zeroCheck(x: BigIntUInt256)(result: => BigInt): BigIntUInt256 =
    if x.isZero then Zero else BigIntUInt256(result)

  def div(that: BigIntUInt256): BigIntUInt256 = zeroCheck(that)(new BigIntUInt256(this.n / that.n))

  def sdiv(that: BigIntUInt256): BigIntUInt256 = zeroCheck(that)(BigIntUInt256(this.signedN / that.signedN))

  def mod(that: BigIntUInt256): BigIntUInt256 = zeroCheck(that)(BigIntUInt256(this.n.mod(that.n)))

  def smod(that: BigIntUInt256): BigIntUInt256 = zeroCheck(that)(BigIntUInt256(this.signedN % that.signedN.abs))

  def addmod(that: BigIntUInt256, modulus: BigIntUInt256): BigIntUInt256 = zeroCheck(modulus) {
    new BigIntUInt256((this.n + that.n) % modulus.n)
  }

  def mulmod(that: BigIntUInt256, modulus: BigIntUInt256): BigIntUInt256 = zeroCheck(modulus) {
    new BigIntUInt256((this.n * that.n).mod(modulus.n))
  }

  def slt(that: BigIntUInt256): Boolean = this.signedN < that.signedN

  def sgt(that: BigIntUInt256): Boolean = this.signedN > that.signedN

  def sshift(that: BigIntUInt256): BigIntUInt256 = BigIntUInt256(this.signedN >> that.signedN.toInt)

  def signExtend(that: BigIntUInt256): BigIntUInt256 =
    if that.n < 0 || that.n > 31 then this
    else
      val idx = that.n.toByte
      val negative = n.testBit(idx * 8 + 7)
      val mask = (BigInt(1) << ((idx + 1) * 8)) - 1
      val newN = if negative then n | (MaxValue ^ mask) else n & mask
      new BigIntUInt256(newN)

  def fillingAdd(that: BigIntUInt256): BigIntUInt256 =
    val result = this.n + that.n
    if result > MaxValue then MaxValue
    else new BigIntUInt256(result)

  // standard methods
  override def equals(that: Any): Boolean = // §3h: FORGE-confirmed — java.lang.Object.equals signature is fixed by JVM
    that match
      case that: BigIntUInt256 => this.n.equals(that.n)
      case other               => other == n

  override def hashCode: Int = n.hashCode()

  override def toString: String = toSignedDecString

  def toDecString: String =
    n.toString

  def toSignedDecString: String =
    signedN.toString

  def toHexString: String =
    val hex = f"$n%x"
    // add zero if odd number of digits
    val extraZero = if hex.length % 2 == 0 then "" else "0"
    s"0x$extraZero$hex"

  def toSign: BigInt = signedN

  // conversions
  def toBigInt: BigInt = n

  /** @return
    *   an Int with MSB=0, thus a value in range [0, Int.MaxValue]
    */
  def toInt: Int = n.intValue & Int.MaxValue

  /** @return
    *   a Long with MSB=0, thus a value in range [0, Long.MaxValue]
    */
  def toLong: Long = n.longValue & Long.MaxValue
