package com.chipprbots.ethereum.vm

import java.math.BigInteger
import java.util.Arrays

import scala.util.control.NonFatal

import supranational.blst.P1
import supranational.blst.P1_Affine
import supranational.blst.P2
import supranational.blst.P2_Affine
import supranational.blst.PT

import com.chipprbots.ethereum.vm.Bls12381MapToCurve.Fp2

/** The EIP-2537 operations on supranational blst through `tech.pegasys:jblst` (ADR VM-008, B-151): the backend for
  * platforms where `libeth_pairings` does not load, Windows above all. jblst ships blst for Windows x86_64, Linux and
  * macOS.
  *
  * jblst's Java API has point decoding, subgroup checks, addition, scalar multiplication and the pairing, but no
  * multi-scalar multiplication and no map from a raw field element to the curve. So:
  *   - the EIP-2537 codec is here: a 64-byte field element has 16 zero top bytes and a value below p, all zeros is the
  *     point at infinity, and any other point must be on the curve, checked here before blst sees it;
  *   - an MSM is the sum of `mult`s, after every point's subgroup check;
  *   - the maps are [[Bls12381MapToCurve]], and blst clears the cofactor.
  *
  * blst's own encoding differs from EIP-2537's, and its decoder is stricter (ADR VM-008):
  *   - it writes an Fp2 element as `c1 || c0` and the point at infinity as the flag 0x40, where EIP-2537 writes `c0 ||
  *     c1` and all zeros. The codec swaps both ways;
  *   - it refuses the G1 points (0, 2) and (0, -2), as "not in group". They are on the curve, of order 3, and G1ADD
  *     takes them (EIP-2537 has no subgroup check there), so they are built by arithmetic instead ([[g1Point]]).
  *
  * Operation ids are eth_pairings', as [[NativeBackends.BlsNative]] takes them. A rejected input is `None`. Anything
  * blst throws for an input this codec accepted is a backend failure: it propagates, and
  * [[NativeBackends.blsOperation]] turns it into `NativeBackendUnavailable`.
  */
private[vm] object BlstNative extends NativeBackends.BlsNative:

  val name: String = "blst (tech.pegasys:jblst 0.3.18)"

  /** Why blst did not load, if it did not. Reading it loads the library, once. jblst's static initialiser throws when
    * the native library is missing, and the class is unusable after.
    */
  lazy val loadFailure: Option[String] =
    try
      val g = P1.generator()
      Option.unless(g.on_curve() && g.in_group())("blst loaded, but its G1 generator fails its own checks")
    catch
      case NonFatal(e)     => Some(s"the blst native library did not load: $e")
      case e: LinkageError => Some(s"the blst native library did not load: $e")

  def enabled: Boolean = loadFailure.isEmpty

  def unavailableDetail: String = loadFailure.getOrElse("blst loaded")

  // eth_pairings' operation ids (LibEthPairings.BLS12_*_OPERATION_RAW_VALUE), literal so that this backend does not
  // initialise LibEthPairings.
  private val G1Add: Byte = 1
  private val G1Msm: Byte = 3
  private val G2Add: Byte = 4
  private val G2Msm: Byte = 6
  private val Pairing: Byte = 7
  private val MapFpToG1: Byte = 8
  private val MapFp2ToG2: Byte = 9

  private val G1Size = 128
  private val G2Size = 256
  private val ScalarSize = 32
  private val FpSize = 64

  def perform(operation: Byte, input: Array[Byte]): Option[Array[Byte]] = operation match
    case G1Add if input.length == 2 * G1Size =>
      for
        a <- decodeG1(input, 0)
        b <- decodeG1(input, G1Size)
      yield encodeG1(a.add(b))
    case G1Msm if input.nonEmpty && input.length % (G1Size + ScalarSize) == 0 =>
      msm(input, G1Size, decodeG1, (p: P1) => p.is_inf() || p.in_group(), new P1())((acc, p, k) => acc.add(p.mult(k)))
        .map(encodeG1)
    case G2Add if input.length == 2 * G2Size =>
      for
        a <- decodeG2(input, 0)
        b <- decodeG2(input, G2Size)
      yield encodeG2(a.add(b))
    case G2Msm if input.nonEmpty && input.length % (G2Size + ScalarSize) == 0 =>
      msm(input, G2Size, decodeG2, (p: P2) => p.is_inf() || p.in_group(), new P2())((acc, p, k) => acc.add(p.mult(k)))
        .map(encodeG2)
    case Pairing if input.nonEmpty && input.length % (G1Size + G2Size) == 0 =>
      pairingCheck(input).map { holds =>
        val out = new Array[Byte](32)
        if holds then out(31) = 1
        out
      }
    case MapFpToG1 if input.length == FpSize =>
      fp(input, 0).map { u =>
        Bls12381MapToCurve.mapToG1Uncleared(u) match
          case None         => new Array[Byte](G1Size)
          case Some((x, y)) => encodeG1(g1Point(x, y).mult(G1CofactorClearing))
      }
    case MapFp2ToG2 if input.length == 2 * FpSize =>
      for
        c0 <- fp(input, 0)
        c1 <- fp(input, FpSize)
      yield Bls12381MapToCurve.mapToG2Uncleared(Fp2(c0, c1)) match
        case None         => new Array[Byte](G2Size)
        case Some((x, y)) => encodeG2(g2Point(x, y).mult(G2CofactorClearing))
    case _ => None

  /** h_eff of RFC 9380 section 8.8.1. 64 bits, so blst multiplies by it with its generic window method, not GLV, which
    * would be wrong for a point outside the subgroup (blst uses GLV for 176 to 256 bits).
    */
  private val G1CofactorClearing = new BigInteger("d201000000010001", 16)

  /** h_eff of RFC 9380 section 8.8.2. 636 bits: blst's generic window method again (it uses GLS for 144 to 256 bits).
    */
  private val G2CofactorClearing = new BigInteger(
    "bc69f08f2ee75b3584c6a0ea91b352888e2a8e9145ad7689986ff031508ffe1329c2f178731db956d82bf015d1212b02ec0ec69d7477c1ae" +
      "954cbc06689f6a359894c0adebbf6b4e8020005aaa95551",
    16
  )

  // ── Codec ──────────────────────────────────────────────────────────────────────────────────────────────────────────

  private val P = Bls12381MapToCurve.P
  private val Four = BigInteger.valueOf(4)
  private val G2B = Fp2(Four, Four) // 4 (1 + i)

  /** A 64-byte field element at `offset`: 16 zero bytes, then a big-endian integer below p. */
  private def fp(in: Array[Byte], offset: Int): Option[BigInteger] =
    if (offset until offset + 16).exists(in(_) != 0) then None
    else
      val value = new BigInteger(1, Arrays.copyOfRange(in, offset + 16, offset + FpSize))
      Option.when(value.compareTo(P) < 0)(value)

  /** 48 big-endian bytes of a field element below p. */
  private def be48(value: BigInteger): Array[Byte] =
    val raw = value.toByteArray // big-endian, possibly with a leading sign byte
    val out = new Array[Byte](48)
    val n = math.min(raw.length, 48)
    System.arraycopy(raw, raw.length - n, out, 48 - n, n)
    out

  private def isZero(in: Array[Byte], offset: Int, size: Int): Boolean = (offset until offset + size).forall(in(_) == 0)

  private def mod(v: BigInteger): BigInteger = v.mod(P)

  /** The affine coordinates of blst's G1 generator. */
  private lazy val generatorX: BigInteger = new BigInteger(1, Arrays.copyOfRange(P1.generator().serialize(), 0, 48))
  private lazy val generatorY: BigInteger = new BigInteger(1, Arrays.copyOfRange(P1.generator().serialize(), 48, 96))

  /** The G1 point (x, y), on the curve and not the point at infinity, in blst's projective form.
    *
    * blst's decoder refuses x = 0: (0, 2) and (0, -2) have order 3 and it reports them "not in group". For those, the
    * sum S = (0, y) + G with the generator G is computed here in affine coordinates and decoded, and the point is S -
    * G. S has x != 0: S = (0, +-2) would make G of order 3 or 1.
    */
  private def g1Point(x: BigInteger, y: BigInteger): P1 =
    if x.signum != 0 then new P1(new P1_Affine(be48(x) ++ be48(y)))
    else
      val lambda = mod(generatorY.subtract(y).multiply(generatorX.modInverse(P))) // (y_G - y) / (x_G - 0)
      val sx = mod(lambda.multiply(lambda).subtract(generatorX)) // lambda^2 - 0 - x_G
      val sy = mod(lambda.negate().multiply(sx).subtract(y)) // lambda (0 - x_S) - y
      new P1(new P1_Affine(be48(sx) ++ be48(sy))).add(P1.generator().neg())

  /** A G1 point at `offset`: the point at infinity, or a point on y^2 = x^3 + 4. Not checked for the subgroup. */
  private def decodeG1(in: Array[Byte], offset: Int): Option[P1] =
    for
      x <- fp(in, offset)
      y <- fp(in, offset + FpSize)
      point <-
        if x.signum == 0 && y.signum == 0 then Some(new P1())
        else
          Option.when(mod(y.multiply(y).subtract(x.multiply(x).multiply(x)).subtract(Four)).signum == 0)(g1Point(x, y))
    yield point

  /** The G2 point (x, y), on the curve and not the point at infinity. blst's G2 decoder has no x = 0 rule. */
  private def g2Point(x: Fp2, y: Fp2): P2 =
    new P2(new P2_Affine(be48(x.c1) ++ be48(x.c0) ++ be48(y.c1) ++ be48(y.c0)))

  /** A G2 point at `offset`: the point at infinity, or a point on y^2 = x^3 + 4(1 + i). Not checked for the subgroup.
    */
  private def decodeG2(in: Array[Byte], offset: Int): Option[P2] =
    for
      x0 <- fp(in, offset)
      x1 <- fp(in, offset + FpSize)
      y0 <- fp(in, offset + 2 * FpSize)
      y1 <- fp(in, offset + 3 * FpSize)
      point <-
        if isZero(in, offset, G2Size) then Some(new P2())
        else
          val x = Fp2(x0, x1)
          val y = Fp2(y0, y1)
          Option.when(y * y == x * x * x + G2B)(g2Point(x, y))
    yield point

  /** blst's 48-byte coordinate `i` of `serialized`, as a 64-byte EIP-2537 field element written at `out(offset)`. */
  private def pad(serialized: Array[Byte], i: Int, out: Array[Byte], offset: Int): Unit =
    System.arraycopy(serialized, 48 * i, out, offset + 16, 48)

  private def encodeG1(point: P1): Array[Byte] =
    val s = point.serialize() // x || y, or the infinity flag
    val out = new Array[Byte](G1Size)
    if (s(0) & 0x40) == 0 then
      pad(s, 0, out, 0)
      pad(s, 1, out, FpSize)
    out

  private def encodeG2(point: P2): Array[Byte] =
    val s = point.serialize() // x.c1 || x.c0 || y.c1 || y.c0, or the infinity flag
    val out = new Array[Byte](G2Size)
    if (s(0) & 0x40) == 0 then
      pad(s, 1, out, 0)
      pad(s, 0, out, FpSize)
      pad(s, 3, out, 2 * FpSize)
      pad(s, 2, out, 3 * FpSize)
    out

  // ── MSM and pairing ────────────────────────────────────────────────────────────────────────────────────────────────

  /** Sums `scalar * point` over the (point, scalar) pairs. Every point must decode and pass `inSubgroup`, the point at
    * infinity included; then a point at infinity or a zero scalar adds nothing. The scalar is not reduced: every point
    * is in the subgroup. blst's `mult` and `add` overwrite the receiver, so each decoded point is used once.
    */
  private def msm[J](
      input: Array[Byte],
      pointSize: Int,
      decode: (Array[Byte], Int) => Option[J],
      inSubgroup: J => Boolean,
      zero: J
  )(addProduct: (J, J, BigInteger) => J): Option[J] =
    val pairSize = pointSize + ScalarSize
    val pairs = (0 until input.length / pairSize).map { i =>
      val offset = i * pairSize
      val scalar = new BigInteger(1, Arrays.copyOfRange(input, offset + pointSize, offset + pairSize))
      decode(input, offset).filter(inSubgroup).map(point => (point, scalar, isZero(input, offset, pointSize)))
    }
    Option.when(pairs.forall(_.isDefined)) {
      pairs.flatten.foldLeft(zero) { case (acc, (point, scalar, infinite)) =>
        if infinite || scalar.signum == 0 then acc else addProduct(acc, point, scalar)
      }
    }

  /** Whether the product of the pairings of the (G1, G2) pairs is 1. Every point must decode and be in its subgroup. A
    * pair holding a point at infinity contributes 1, and an input of only such pairs holds.
    */
  private def pairingCheck(input: Array[Byte]): Option[Boolean] =
    val pairSize = G1Size + G2Size
    val pairs = (0 until input.length / pairSize).map { i =>
      val offset = i * pairSize
      val infinite = isZero(input, offset, G1Size) || isZero(input, offset + G1Size, G2Size)
      for
        p <- decodeG1(input, offset)
        q <- decodeG2(input, offset + G1Size)
        if p.is_inf() || p.in_group()
        if q.is_inf() || q.in_group()
      yield (p, q, infinite)
    }
    Option.when(pairs.forall(_.isDefined)) {
      val loops = pairs.flatten.collect { case (p, q, false) => new PT(p.to_affine(), q.to_affine()) }
      loops.reduceOption((a, b) => a.mul(b)).forall(_.final_exp().is_one())
    }
