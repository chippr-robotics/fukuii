package com.chipprbots.ethereum.vm

import java.nio.charset.StandardCharsets

import org.apache.pekko.util.ByteString

import scala.util.Random

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*

/** What the corpus knows about a case's correct outcome, independently of every backend. */
enum Eip2537Expectation:
  /** No independent oracle: the backends are only compared with each other. */
  case Unknown

  /** A published vector, or the spec text, fixes the output bytes. */
  case Output(bytes: ByteString)

  /** The spec text makes this input an error. */
  case Failure

  /** The spec text makes this input succeed; the bytes are not computed here. */
  case AnySuccess

/** One input for one op. `source` is the vector file, `edge` or `fuzz`. */
final case class Eip2537Case(
    op: Eip2537Op,
    source: String,
    name: String,
    input: ByteString,
    expectation: Eip2537Expectation
):
  def label: String = s"$source:$name"

  def group: String = if source == "edge" || source == "fuzz" then source else "vector"

/** The B-153 differential corpus: published EIP-2537 and go-ethereum vectors, generated edge cases for every encoding
  * rule in EIP-2537, and a seeded fuzz. Everything is deterministic; the fuzz uses one fixed seed.
  *
  * Encoding rules exercised (EIP-2537 "Fine points and encoding of base elements"): 64-byte big-endian Fp words whose
  * top 16 bytes must be zero and whose value must be < p; 128/256 zero bytes are the point at infinity; the on-curve
  * check everywhere; the subgroup check for MSM and pairing only, never for ADD; scalars are 32 bytes and NOT reduced
  * or range-checked; variable-length ops reject empty input; every op rejects a wrong length.
  */
object Eip2537Corpus:

  // ---- BLS12-381 constants (EIP-2537 "Curve parameters") --------------------------------------------------------

  val P: BigInt = BigInt(
    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab",
    16
  )
  val R: BigInt = BigInt("73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16)
  val Max256: BigInt = (BigInt(1) << 256) - 1

  val FuzzSeed: Long = 0xb153L
  private val LargeKSeed: Long = 0xb1530000L
  private val WordBytes = 64

  // ---- encoding helpers ------------------------------------------------------------------------------------------

  def unhex(s: String): ByteString = ByteString(Hex.decode(s.stripPrefix("0x")))

  def hex(bs: ByteString): String = Hex.toHexString(bs.toArray)

  def zeros(n: Int): ByteString = ByteString(new Array[Byte](n))

  def isZero(bs: ByteString): Boolean = bs.forall(_ == 0)

  private def word(v: BigInt, size: Int): ByteString =
    require(v.signum >= 0, s"negative word $v")
    val raw = v.toByteArray.dropWhile(_ == 0)
    require(raw.length <= size, s"$v does not fit in $size bytes")
    zeros(size - raw.length) ++ ByteString(raw)

  /** A 64-byte Fp word. Values >= p (or >= 2^384) are allowed on purpose: they are the non-canonical cases. */
  def fp(v: BigInt): ByteString = word(v, WordBytes)

  def scalar(v: BigInt): ByteString = word(v, 32)

  def g1(x: BigInt, y: BigInt): ByteString = fp(x) ++ fp(y)

  def g2(x0: BigInt, x1: BigInt, y0: BigInt, y1: BigInt): ByteString = fp(x0) ++ fp(x1) ++ fp(y0) ++ fp(y1)

  private def g1Hex(x: String, y: String): ByteString = g1(BigInt(x, 16), BigInt(y, 16))

  private def g2Hex(x0: String, x1: String, y0: String, y1: String): ByteString =
    g2(BigInt(x0, 16), BigInt(x1, 16), BigInt(y0, 16), BigInt(y1, 16))

  /** The 64-byte word starting at byte `offset`, as an integer. */
  def wordAt(bs: ByteString, offset: Int): BigInt = BigInt(1, bs.slice(offset, offset + WordBytes).toArray)

  def withWordAt(bs: ByteString, offset: Int, v: BigInt): ByteString =
    bs.take(offset) ++ fp(v) ++ bs.drop(offset + WordBytes)

  def withByte(bs: ByteString, offset: Int, f: Int => Int): ByteString =
    val a = bs.toArray
    a(offset) = f(a(offset) & 0xff).toByte
    ByteString(a)

  private def concat(parts: Iterable[ByteString]): ByteString = parts.foldLeft(ByteString.empty)(_ ++ _)

  def negG1(pt: ByteString): ByteString =
    if isZero(pt) then pt else withWordAt(pt, 64, (P - wordAt(pt, 64)).mod(P))

  def negG2(pt: ByteString): ByteString =
    if isZero(pt) then pt
    else withWordAt(withWordAt(pt, 128, (P - wordAt(pt, 128)).mod(P)), 192, (P - wordAt(pt, 192)).mod(P))

  /** y^2 = x^3 + 4 over Fp. Infinity (all zero) is not on the curve by this test; callers skip it. */
  def onCurveG1(pt: ByteString): Boolean =
    val x = wordAt(pt, 0)
    val y = wordAt(pt, 64)
    x < P && y < P && (y * y - x * x * x - 4).mod(P).signum == 0

  /** y^2 = x^3 + 4(1 + u) over Fp2 = Fp[u]/(u^2 + 1) (EIP-2537: nr2 = p - 1, B = 4, twist coefficient v + 1). */
  def onCurveG2(pt: ByteString): Boolean =
    def mul(a: (BigInt, BigInt), b: (BigInt, BigInt)): (BigInt, BigInt) =
      ((a._1 * b._1 - a._2 * b._2).mod(P), (a._1 * b._2 + a._2 * b._1).mod(P))
    val x = (wordAt(pt, 0), wordAt(pt, 64))
    val y = (wordAt(pt, 128), wordAt(pt, 192))
    val yy = mul(y, y)
    val xxx = mul(mul(x, x), x)
    Seq(x._1, x._2, y._1, y._2).forall(_ < P) && yy == ((xxx._1 + 4).mod(P), (xxx._2 + 4).mod(P))

  // ---- points ----------------------------------------------------------------------------------------------------

  val G1Inf: ByteString = zeros(128)
  val G2Inf: ByteString = zeros(256)

  /** EIP-2537 H1. */
  val G1: ByteString = g1Hex(
    "17f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb",
    "08b3f481e3aaa0f1a09e30ed741d8ae4fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1"
  )

  /** The vectors' `p1`, in the subgroup (`bls_g1msm_(1*p1=p1)` succeeds). */
  val P1: ByteString = g1Hex(
    "112b98340eee2777cc3c14163dea3ec97977ac3dc5c70da32e6e87578f44912e902ccef9efe28d4a78b8999dfbca9426",
    "186b28d92356c4dfec4b5201ad099dbdede3781f8998ddf929b4cd7756192185ca7b8f4ef7088f813270ac3d48868a21"
  )

  /** 2 * H1, from `bls_g1add_(g1+g1=2*g1)`. */
  val G1Double: ByteString = g1Hex(
    "0572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62ae28f75bb8f1c7c42c39a8c5529bf0f4e",
    "166a9d8cabc673a322fda673779d8e3822ba3ecb8670e461f73bb9021d5fd76a4c56d9d4cd16bd1bba86881979749d28"
  )

  /** On the curve, not in the r-torsion subgroup: from `bls_pairing_e(G1_not_in_correct_subgroup,0)`. */
  val G1NotInSubgroup: ByteString = g1Hex(
    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "193fb7cedb32b2c3adc06ec11a96bc0d661869316f5e4a577a9f7c179593987beb4fb2ee424dbb2f5dd891e228b46c4a"
  )

  /** (0, 2): on y^2 = x^3 + 4, and every point with x = 0 has order 3, so it is outside the prime-order subgroup. It
    * also has x = 0, which catches an implementation that tests only x for the point at infinity.
    */
  val G1Order3: ByteString = g1(0, 2)

  /** p1's x with H1's y: not on the curve (`bls_pairing_e(G1_not_on_curve,0)`). */
  val G1NotOnCurve: ByteString = g1Hex(
    "112b98340eee2777cc3c14163dea3ec97977ac3dc5c70da32e6e87578f44912e902ccef9efe28d4a78b8999dfbca9426",
    "08b3f481e3aaa0f1a09e30ed741d8ae4fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1"
  )

  /** EIP-2537 H2. */
  val G2: ByteString = g2Hex(
    "024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8",
    "13e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e",
    "0ce5d527727d6e118cc9cdc6da2e351aadfd9baa8cbdd3a76d429a695160d12c923ac9cc3baca289e193548608b82801",
    "0606c4a02ea734cc32acd2b02bc28b99cb3e287e85a763af267492ab572e99ab3f370d275cec1da1aaa9075ff05f79be"
  )

  /** The vectors' `p2`, in the subgroup (`bls_g2msm_(1*p2=p2)` succeeds). */
  val P2: ByteString = g2Hex(
    "103121a2ceaae586d240843a398967325f8eb5a93e8fea99b62b9f88d8556c80dd726a4b30e84a36eeabaf3592937f27",
    "086b990f3da2aeac0a36143b7d7c824428215140db1bb859338764cb58458f081d92664f9053b50b3fbd2e4723121b68",
    "0f9e7ba9a86a8f7624aa2b42dcc8772e1af4ae115685e60abc2c9b90242167acef3d0be4050bf935eed7c3b6fc7ba77e",
    "0d22c3652d0dc6f0fc9316e14268477c2049ef772e852108d269d9c38dba1d4802e8dae479818184c08f9a569d878451"
  )

  /** 2 * H2, from `bls_g2add_(g2+g2=2*g2)`. */
  val G2Double: ByteString = g2Hex(
    "1638533957d540a9d2370f17cc7ed5863bc0b995b8825e0ee1ea1e1e4d00dbae81f14b0bf3611b78c952aacab827a053",
    "0a4edef9c1ed7f729f520e47730a124fd70662a904ba1074728114d1031e1572c6c886f6b57ec72a6178288c47c33577",
    "0468fb440d82b0630aeb8dca2b5256789a66da69bf91009cbfe6bd221e47aa8ae88dece9764bf3bd999d95d71e4c9899",
    "0f6d4552fa65dd2638b361543f887136a43253d9c66c411697003f7a13c308f5422e1aa0a59c8967acdefd8b6e36ccf3"
  )

  /** On the twist, not in the subgroup: from `bls_pairing_e(0,G2_not_in_correct_subgroup)`. */
  val G2NotInSubgroup: ByteString = g2Hex(
    "197bfd0342bbc8bee2beced2f173e1a87be576379b343e93232d6cef98d84b1d696e5612ff283ce2cfdccb2cfb65fa0c",
    "184e811f55e6f9d84d77d2f79102fd7ea7422f4759df5bf7f6331d550245e3f1bcf6a30e3b29110d85e0ca16f9f6ae7a",
    "0f10e1eb3c1e53d2ad9cf2d398b2dc22c5842fab0a74b174f691a7e914975da3564d835cd7d2982815b8ac57f507348f",
    "0767d1c453890f1b9110fda82f5815c27281aba3f026ee868e4176a0654feea41a96575e0c4d58a14dbfbcc05b5010b1"
  )

  /** p2's x.c0 with H2's other coordinates: not on the twist (`bls_pairing_e(0,G2_not_on_curve)`). */
  val G2NotOnCurve: ByteString = g2Hex(
    "103121a2ceaae586d240843a398967325f8eb5a93e8fea99b62b9f88d8556c80dd726a4b30e84a36eeabaf3592937f27",
    "13e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e",
    "0ce5d527727d6e118cc9cdc6da2e351aadfd9baa8cbdd3a76d429a695160d12c923ac9cc3baca289e193548608b82801",
    "0606c4a02ea734cc32acd2b02bc28b99cb3e287e85a763af267492ab572e99ab3f370d275cec1da1aaa9075ff05f79be"
  )

  /** The scalars the task names, plus 2, 2r and 2^255. EIP-2537: a scalar "is not required to be less than or equal to
    * main subgroup order q".
    */
  val EdgeScalars: Vector[(String, BigInt)] = Vector(
    "0" -> BigInt(0),
    "1" -> BigInt(1),
    "2" -> BigInt(2),
    "r-1" -> (R - 1),
    "r" -> R,
    "r+1" -> (R + 1),
    "2r" -> (R * 2),
    "2^255" -> (BigInt(1) << 255),
    "2^256-1" -> Max256
  )

  /** p - 1 is canonical; p, p + 1 and 2^512 - 1 (all 64 bytes 0xff, so the padding is non-zero too) are not. */
  val FpBoundaries: Vector[(String, BigInt)] = Vector(
    "p-1" -> (P - 1),
    "p" -> P,
    "p+1" -> (P + 1),
    "2^512-1" -> ((BigInt(1) << 512) - 1)
  )

  /** ZCash-style flag bits (compressed 0x80, infinity 0x40, sign 0x20) in the top byte of a 48-byte element. Any of
    * them makes the element >= 2^381 > p, so EIP-2537 must reject it; an implementation that strips them must not.
    */
  val FlagMasks: Vector[Int] = Vector(0x20, 0x40, 0x80, 0xe0)

  // ---- published vectors -----------------------------------------------------------------------------------------

  /** Vendored from ethereum/EIPs `assets/eip-2537` (CC0) and go-ethereum `core/vm/testdata/precompiles` (LGPL-3.0); see
    * `src/test/resources/eip2537/README.md`. The pre-final G1MUL/G2MUL vectors are MSMs at k = 1, which is how geth's
    * own `contracts_test.go` runs them.
    */
  val VectorFiles: Vector[(String, Eip2537Op)] = Vector(
    "eip/add_G1_bls.json" -> Eip2537Op.G1Add,
    "eip/fail-add_G1_bls.json" -> Eip2537Op.G1Add,
    "eip/add_G2_bls.json" -> Eip2537Op.G2Add,
    "eip/fail-add_G2_bls.json" -> Eip2537Op.G2Add,
    "eip/mul_G1_bls.json" -> Eip2537Op.G1Msm,
    "eip/fail-mul_G1_bls.json" -> Eip2537Op.G1Msm,
    "eip/msm_G1_bls.json" -> Eip2537Op.G1Msm,
    "eip/fail-msm_G1_bls.json" -> Eip2537Op.G1Msm,
    "eip/mul_G2_bls.json" -> Eip2537Op.G2Msm,
    "eip/fail-mul_G2_bls.json" -> Eip2537Op.G2Msm,
    "eip/msm_G2_bls.json" -> Eip2537Op.G2Msm,
    "eip/fail-msm_G2_bls.json" -> Eip2537Op.G2Msm,
    "eip/pairing_check_bls.json" -> Eip2537Op.Pairing,
    "eip/fail-pairing_check_bls.json" -> Eip2537Op.Pairing,
    "eip/map_fp_to_G1_bls.json" -> Eip2537Op.MapFpToG1,
    "eip/fail-map_fp_to_G1_bls.json" -> Eip2537Op.MapFpToG1,
    "eip/map_fp2_to_G2_bls.json" -> Eip2537Op.MapFp2ToG2,
    "eip/fail-map_fp2_to_G2_bls.json" -> Eip2537Op.MapFp2ToG2,
    "geth/blsG1Add.json" -> Eip2537Op.G1Add,
    "geth/fail-blsG1Add.json" -> Eip2537Op.G1Add,
    "geth/blsG2Add.json" -> Eip2537Op.G2Add,
    "geth/fail-blsG2Add.json" -> Eip2537Op.G2Add,
    "geth/blsG1Mul.json" -> Eip2537Op.G1Msm,
    "geth/fail-blsG1Mul.json" -> Eip2537Op.G1Msm,
    "geth/blsG1MultiExp.json" -> Eip2537Op.G1Msm,
    "geth/fail-blsG1MultiExp.json" -> Eip2537Op.G1Msm,
    "geth/blsG2Mul.json" -> Eip2537Op.G2Msm,
    "geth/fail-blsG2Mul.json" -> Eip2537Op.G2Msm,
    "geth/blsG2MultiExp.json" -> Eip2537Op.G2Msm,
    "geth/fail-blsG2MultiExp.json" -> Eip2537Op.G2Msm,
    "geth/blsPairing.json" -> Eip2537Op.Pairing,
    "geth/fail-blsPairing.json" -> Eip2537Op.Pairing,
    "geth/blsMapG1.json" -> Eip2537Op.MapFpToG1,
    "geth/fail-blsMapG1.json" -> Eip2537Op.MapFpToG1,
    "geth/blsMapG2.json" -> Eip2537Op.MapFp2ToG2,
    "geth/fail-blsMapG2.json" -> Eip2537Op.MapFp2ToG2
  )

  private def readResource(path: String): String =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(path))
      .getOrElse(throw new IllegalStateException(s"missing test resource $path"))
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  private def loadVectorFile(file: String, op: Eip2537Op): Vector[Eip2537Case] =
    parse(readResource(s"eip2537/$file")) match
      case JArray(items) =>
        items.toVector.map {
          case JObject(fields) =>
            val byKey = fields.toMap
            def text(key: String): Option[String] = byKey.get(key).collect { case JString(s) => s }
            val name = text("Name").getOrElse(throw new IllegalStateException(s"$file: a vector has no Name"))
            val input = unhex(text("Input").getOrElse(throw new IllegalStateException(s"$file/$name: no Input")))
            val expectation = text("Expected") match
              case Some(outputHex)                         => Eip2537Expectation.Output(unhex(outputHex))
              case None if byKey.contains("ExpectedError") => Eip2537Expectation.Failure
              case None => throw new IllegalStateException(s"$file/$name: neither Expected nor ExpectedError")
            Eip2537Case(op, file, name, input, expectation)
          case other => throw new IllegalStateException(s"$file: a vector is not an object: $other")
        }
      case _ => throw new IllegalStateException(s"$file: the top level is not an array")

  lazy val vectors: Vector[Eip2537Case] = VectorFiles.flatMap((file, op) => loadVectorFile(file, op))

  // ---- generated edge cases --------------------------------------------------------------------------------------

  private val Fail: Eip2537Expectation = Eip2537Expectation.Failure
  private val AnyOk: Eip2537Expectation = Eip2537Expectation.AnySuccess
  private val NoOracle: Eip2537Expectation = Eip2537Expectation.Unknown
  private def out(bs: ByteString): Eip2537Expectation = Eip2537Expectation.Output(bs)

  private val PairingTrue: Eip2537Expectation = out(scalar(1))
  private val PairingFalse: Eip2537Expectation = out(zeros(32))

  private def edge(op: Eip2537Op, name: String, input: ByteString, expectation: Eip2537Expectation): Eip2537Case =
    Eip2537Case(op, "edge", name, input, expectation)

  private def named(op: Eip2537Op, entries: Seq[(String, ByteString, Eip2537Expectation)]): Vector[Eip2537Case] =
    entries.toVector.map((name, input, expectation) => edge(op, name, input, expectation))

  /** A 0x01 at each of the 16 padding bytes of each listed 64-byte word ("must be checked"). */
  private def paddingCases(op: Eip2537Op, base: ByteString, words: Seq[Int], tag: String): Vector[Eip2537Case] =
    for
      w <- words.toVector
      i <- (0 until 16).toVector
    yield edge(op, s"$tag padding byte $i of the word at $w = 0x01", withByte(base, w + i, _ => 0x01), Fail)

  /** p, p + 1 and 2^512 - 1 in each listed word must fail; p - 1 is canonical and gets `canonical`. */
  private def boundaryCases(
      op: Eip2537Op,
      base: ByteString,
      words: Seq[Int],
      tag: String,
      canonical: Eip2537Expectation
  ): Vector[Eip2537Case] =
    for
      w <- words.toVector
      (label, v) <- FpBoundaries
    yield edge(op, s"$tag word at $w = $label", withWordAt(base, w, v), if v < P then canonical else Fail)

  /** A valid coordinate plus p: the same residue, but not canonical. A reducing decoder accepts it; the spec rejects.
    */
  private def plusPCases(op: Eip2537Op, base: ByteString, words: Seq[Int], tag: String): Vector[Eip2537Case] =
    words.toVector.map(w => edge(op, s"$tag word at $w + p", withWordAt(base, w, wordAt(base, w) + P), Fail))

  /** Each ZCash flag in the top byte of the 48-byte element, i.e. byte 16 of the 64-byte word. */
  private def flagCases(op: Eip2537Op, base: ByteString, words: Seq[Int], tag: String): Vector[Eip2537Case] =
    for
      w <- words.toVector
      m <- FlagMasks
    yield edge(op, f"$tag flag 0x$m%02x in the top byte of the word at $w", withByte(base, w + 16, _ | m), Fail)

  /** Empty, truncated and over-long inputs, cut from or zero-extended past `material`. */
  private def lengthCases(op: Eip2537Op, material: ByteString, lengths: Seq[Int]): Vector[Eip2537Case] =
    lengths.toVector.map { n =>
      val bytes = if n <= material.length then material.take(n) else material ++ zeros(n - material.length)
      edge(op, s"input length $n", bytes, Fail)
    }

  /** The ZCash infinity encodings 0x40.. and 0xc0..: a flag on zero, so x = 2^382 (or more) > p. */
  private def zcashInfinity(inf: ByteString, flag: Int): ByteString = withByte(inf, 16, _ => flag)

  private def g1Add(a: ByteString, b: ByteString): ByteString = a ++ b

  private def g1AddEdges: Vector[Eip2537Case] =
    val op = Eip2537Op.G1Add
    val base = g1Add(G1, P1)
    named(
      op,
      Seq(
        ("G+O", g1Add(G1, G1Inf), out(G1)),
        ("O+G", g1Add(G1Inf, G1), out(G1)),
        ("O+O", g1Add(G1Inf, G1Inf), out(G1Inf)),
        ("G+G", g1Add(G1, G1), out(G1Double)),
        ("G+(-G)", g1Add(G1, negG1(G1)), out(G1Inf)),
        ("P1+G", g1Add(P1, G1), AnyOk),
        ("notInSubgroup+G: ADD has no subgroup check", g1Add(G1NotInSubgroup, G1), AnyOk),
        ("notInSubgroup+notInSubgroup", g1Add(G1NotInSubgroup, G1NotInSubgroup), AnyOk),
        ("notInSubgroup+O", g1Add(G1NotInSubgroup, G1Inf), out(G1NotInSubgroup)),
        ("order3+order3 = -order3", g1Add(G1Order3, G1Order3), out(negG1(G1Order3))),
        ("order3+(-order3) = O", g1Add(G1Order3, negG1(G1Order3)), out(G1Inf)),
        ("order3+G", g1Add(G1Order3, G1), AnyOk),
        ("notOnCurve+G", g1Add(G1NotOnCurve, G1), Fail),
        ("G+notOnCurve", g1Add(G1, G1NotOnCurve), Fail),
        ("(0,1)+O: (0,1) is off the curve", g1Add(g1(0, 1), G1Inf), Fail),
        ("(p,0)+G: infinity mod p, not canonical", g1Add(g1(P, 0), G1), Fail),
        ("(0,p)+G", g1Add(g1(0, P), G1), Fail),
        ("(p,p)+O", g1Add(g1(P, P), G1Inf), Fail),
        ("0x40-infinity+G", g1Add(zcashInfinity(G1Inf, 0x40), G1), Fail),
        ("G+0xc0-infinity", g1Add(G1, zcashInfinity(G1Inf, 0xc0)), Fail)
      )
    ) ++
      paddingCases(op, base, Seq(0, 64, 128, 192), "G+P1") ++
      paddingCases(op, g1Add(G1Inf, G1Inf), Seq(0, 128), "O+O") ++
      boundaryCases(op, base, Seq(0, 64, 128), "G+P1", NoOracle) ++
      plusPCases(op, base, Seq(0, 64, 128, 192), "G+P1") ++
      flagCases(op, base, Seq(0, 64), "G+P1") ++
      lengthCases(op, base ++ base, Seq(0, 1, 128, 255, 257, 288, 384, 512))

  private def g2AddEdges: Vector[Eip2537Case] =
    val op = Eip2537Op.G2Add
    val base = G2 ++ P2
    named(
      op,
      Seq(
        ("G2+O", G2 ++ G2Inf, out(G2)),
        ("O+G2", G2Inf ++ G2, out(G2)),
        ("O+O", G2Inf ++ G2Inf, out(G2Inf)),
        ("G2+G2", G2 ++ G2, out(G2Double)),
        ("G2+(-G2)", G2 ++ negG2(G2), out(G2Inf)),
        ("P2+G2", P2 ++ G2, AnyOk),
        ("notInSubgroup+G2: ADD has no subgroup check", G2NotInSubgroup ++ G2, AnyOk),
        ("notInSubgroup+notInSubgroup", G2NotInSubgroup ++ G2NotInSubgroup, AnyOk),
        ("notInSubgroup+O", G2NotInSubgroup ++ G2Inf, out(G2NotInSubgroup)),
        ("notInSubgroup+(-notInSubgroup) = O", G2NotInSubgroup ++ negG2(G2NotInSubgroup), out(G2Inf)),
        ("notOnCurve+G2", G2NotOnCurve ++ G2, Fail),
        ("G2+notOnCurve", G2 ++ G2NotOnCurve, Fail),
        ("(p,0,0,0)+G2: infinity mod p, not canonical", g2(P, 0, 0, 0) ++ G2, Fail),
        ("(p,p,p,p)+O", g2(P, P, P, P) ++ G2Inf, Fail),
        ("0x40-infinity+G2", zcashInfinity(G2Inf, 0x40) ++ G2, Fail),
        ("G2+0xc0-infinity", G2 ++ zcashInfinity(G2Inf, 0xc0), Fail)
      )
    ) ++
      paddingCases(op, base, (0 until 8).map(_ * 64), "G2+P2") ++
      paddingCases(op, G2Inf ++ G2Inf, Seq(0, 256), "O+O") ++
      boundaryCases(op, base, Seq(0, 64, 128, 192), "G2+P2", NoOracle) ++
      plusPCases(op, base, Seq(0, 64, 128, 192, 256), "G2+P2") ++
      flagCases(op, base, Seq(0, 64, 128), "G2+P2") ++
      lengthCases(op, base ++ base, Seq(0, 1, 256, 511, 513, 544, 768, 1024))

  /** k = 1 results the spec fixes for a subgroup point: s = 0 mod r gives O, 1 gives the point, r - 1 its negation. */
  private def k1Expectation(
      pt: ByteString,
      s: BigInt,
      inf: ByteString,
      neg: ByteString => ByteString
  ): Eip2537Expectation =
    val m = s.mod(R)
    if isZero(pt) || m.signum == 0 then out(inf)
    else if m == BigInt(1) then out(pt)
    else if m == R - 1 then out(neg(pt))
    else AnyOk

  private def msm(pairs: (ByteString, BigInt)*): ByteString = concat(pairs.map((pt, s) => pt ++ scalar(s)))

  private def g1MsmEdges: Vector[Eip2537Case] =
    val op = Eip2537Op.G1Msm
    val one = BigInt(1)
    val zero = BigInt(0)
    val scalarTable =
      for
        (pn, pt) <- Vector("G" -> G1, "P1" -> P1, "-G" -> negG1(G1), "O" -> G1Inf)
        (sn, s) <- EdgeScalars
      yield edge(op, s"[($pn,$sn)]", msm(pt -> s), k1Expectation(pt, s, G1Inf, negG1))
    val repeats = (1 to 8).toVector.map { k =>
      val expectation = if k == 1 then out(G1) else if k == 2 then out(G1Double) else AnyOk
      edge(op, s"[(G,1)] x $k", msm(Seq.fill(k)(G1 -> one)*), expectation)
    }
    val atoms = Vector(G1, P1, negG1(G1), G1Double, negG1(P1), G1Inf)
    val largeK = Vector(16, 31, 32, 33, 64, 128, 256).map { k =>
      val rnd = new Random(LargeKSeed + k)
      edge(op, s"k=$k, seeded scalars", msm((0 until k).map(i => atoms(i % atoms.size) -> BigInt(256, rnd))*), NoOracle)
    }
    val lastOnly = msm((Seq.fill(127)(G1 -> zero) :+ (P1 -> one))*)
    named(
      op,
      Seq(
        ("[(G,r-1),(O,1)]", msm(G1 -> (R - 1), G1Inf -> one), out(negG1(G1))),
        ("[(O,1),(G,r-1)]", msm(G1Inf -> one, G1 -> (R - 1)), out(negG1(G1))),
        ("[(G,r-1),(G,0)]", msm(G1 -> (R - 1), G1 -> zero), out(negG1(G1))),
        ("[(G,r-1),(O,0),(O,0)]", msm(G1 -> (R - 1), G1Inf -> zero, G1Inf -> zero), out(negG1(G1))),
        ("[(P1,2^256-1),(O,1)]", msm(P1 -> Max256, G1Inf -> one), AnyOk),
        ("[(P1,2^256-1),(P1,0)]", msm(P1 -> Max256, P1 -> zero), AnyOk),
        ("[(P1,2^256-1),(-P1,2^256-1)] = O", msm(P1 -> Max256, negG1(P1) -> Max256), out(G1Inf)),
        ("[(G,r-1),(G,1)] = O", msm(G1 -> (R - 1), G1 -> one), out(G1Inf)),
        ("[(G,2),(-G,1),(-G,1)] = O", msm(G1 -> BigInt(2), negG1(G1) -> one, negG1(G1) -> one), out(G1Inf)),
        ("[(G,1),(G,r+1)] = 2G", msm(G1 -> one, G1 -> (R + 1)), out(G1Double)),
        ("[(G,0),(P1,0)] = O", msm(G1 -> zero, P1 -> zero), out(G1Inf)),
        ("[(G,0),(P1,0),(G,0),(P1,0)] = O", msm(G1 -> zero, P1 -> zero, G1 -> zero, P1 -> zero), out(G1Inf)),
        ("[(O,0) x 3] = O", msm(G1Inf -> zero, G1Inf -> zero, G1Inf -> zero), out(G1Inf)),
        ("[(G,r),(P1,2r)] = O", msm(G1 -> R, P1 -> (R * 2)), out(G1Inf)),
        ("k=128, every scalar 0 but the last (P1,1)", lastOnly, out(P1)),
        ("[(notInSubgroup,1)]", msm(G1NotInSubgroup -> one), Fail),
        ("[(notInSubgroup,0)]: points are checked before a zero scalar is skipped", msm(G1NotInSubgroup -> zero), Fail),
        ("[(notInSubgroup,r)]", msm(G1NotInSubgroup -> R), Fail),
        ("[(G,1),(notInSubgroup,0)]", msm(G1 -> one, G1NotInSubgroup -> zero), Fail),
        ("[(notInSubgroup,0),(G,1)]", msm(G1NotInSubgroup -> zero, G1 -> one), Fail),
        ("[(O,0),(notInSubgroup,0),(O,0)]", msm(G1Inf -> zero, G1NotInSubgroup -> zero, G1Inf -> zero), Fail),
        ("[(order3,1)]", msm(G1Order3 -> one), Fail),
        ("[(order3,3)]: 3 * order3 = O, still not in the subgroup", msm(G1Order3 -> BigInt(3)), Fail),
        ("[(order3,0)]", msm(G1Order3 -> zero), Fail),
        ("[(notOnCurve,0)]", msm(G1NotOnCurve -> zero), Fail),
        ("[(G,0),(notOnCurve,1)]", msm(G1 -> zero, G1NotOnCurve -> one), Fail),
        ("[((p,0),0)]: infinity mod p, not canonical", msm(g1(P, 0) -> zero), Fail),
        ("[((p,0),1)]", msm(g1(P, 0) -> one), Fail),
        ("[((0,p),1)]", msm(g1(0, P) -> one), Fail),
        ("[(0x40-infinity,0)]", msm(zcashInfinity(G1Inf, 0x40) -> zero), Fail),
        ("[(0xc0-infinity,1)]", msm(zcashInfinity(G1Inf, 0xc0) -> one), Fail)
      )
    ) ++ scalarTable ++ repeats ++ largeK ++
      paddingCases(op, msm(G1 -> BigInt(5)), Seq(0, 64), "[(G,5)]") ++
      paddingCases(op, msm(G1 -> one, P1 -> one), Seq(160, 224), "[(G,1),(P1,1)]") ++
      paddingCases(op, msm(G1Inf -> zero), Seq(0), "[(O,0)]") ++
      boundaryCases(op, msm(G1 -> one), Seq(0, 64), "[(G,1)]", NoOracle) ++
      plusPCases(op, msm(G1 -> one), Seq(0, 64), "[(G,1)]") ++
      plusPCases(op, msm(G1 -> one, P1 -> one), Seq(160, 224), "[(G,1),(P1,1)]") ++
      flagCases(op, msm(G1 -> one), Seq(0, 64), "[(G,1)]") ++
      lengthCases(
        op,
        msm(G1 -> one, P1 -> BigInt(2), G1 -> BigInt(3)),
        Seq(0, 1, 32, 128, 159, 161, 192, 319, 321, 400)
      )

  private def g2MsmEdges: Vector[Eip2537Case] =
    val op = Eip2537Op.G2Msm
    val one = BigInt(1)
    val zero = BigInt(0)
    val scalarTable =
      for
        (pn, pt) <- Vector("G2" -> G2, "P2" -> P2, "-G2" -> negG2(G2), "O" -> G2Inf)
        (sn, s) <- EdgeScalars
      yield edge(op, s"[($pn,$sn)]", msm(pt -> s), k1Expectation(pt, s, G2Inf, negG2))
    val repeats = (1 to 6).toVector.map { k =>
      val expectation = if k == 1 then out(G2) else if k == 2 then out(G2Double) else AnyOk
      edge(op, s"[(G2,1)] x $k", msm(Seq.fill(k)(G2 -> one)*), expectation)
    }
    val atoms = Vector(G2, P2, negG2(G2), G2Double, negG2(P2), G2Inf)
    val largeK = Vector(16, 32, 33, 64, 128).map { k =>
      val rnd = new Random(LargeKSeed + 1000 + k)
      edge(op, s"k=$k, seeded scalars", msm((0 until k).map(i => atoms(i % atoms.size) -> BigInt(256, rnd))*), NoOracle)
    }
    named(
      op,
      Seq(
        ("[(G2,r-1),(O,1)]", msm(G2 -> (R - 1), G2Inf -> one), out(negG2(G2))),
        ("[(O,1),(G2,r-1)]", msm(G2Inf -> one, G2 -> (R - 1)), out(negG2(G2))),
        ("[(G2,r-1),(G2,0)]", msm(G2 -> (R - 1), G2 -> zero), out(negG2(G2))),
        ("[(P2,2^256-1),(-P2,2^256-1)] = O", msm(P2 -> Max256, negG2(P2) -> Max256), out(G2Inf)),
        ("[(G2,r-1),(G2,1)] = O", msm(G2 -> (R - 1), G2 -> one), out(G2Inf)),
        ("[(G2,1),(G2,r+1)] = 2G2", msm(G2 -> one, G2 -> (R + 1)), out(G2Double)),
        ("[(G2,0),(P2,0)] = O", msm(G2 -> zero, P2 -> zero), out(G2Inf)),
        ("[(O,0) x 3] = O", msm(G2Inf -> zero, G2Inf -> zero, G2Inf -> zero), out(G2Inf)),
        ("[(G2,r),(P2,2r)] = O", msm(G2 -> R, P2 -> (R * 2)), out(G2Inf)),
        ("[(notInSubgroup,1)]", msm(G2NotInSubgroup -> one), Fail),
        ("[(notInSubgroup,0)]: points are checked before a zero scalar is skipped", msm(G2NotInSubgroup -> zero), Fail),
        ("[(notInSubgroup,r)]", msm(G2NotInSubgroup -> R), Fail),
        ("[(G2,1),(notInSubgroup,0)]", msm(G2 -> one, G2NotInSubgroup -> zero), Fail),
        ("[(O,0),(notInSubgroup,0),(O,0)]", msm(G2Inf -> zero, G2NotInSubgroup -> zero, G2Inf -> zero), Fail),
        ("[(notOnCurve,0)]", msm(G2NotOnCurve -> zero), Fail),
        ("[(G2,0),(notOnCurve,1)]", msm(G2 -> zero, G2NotOnCurve -> one), Fail),
        ("[((p,0,0,0),0)]: infinity mod p, not canonical", msm(g2(P, 0, 0, 0) -> zero), Fail),
        ("[((0,0,0,p),1)]", msm(g2(0, 0, 0, P) -> one), Fail),
        ("[(0x40-infinity,0)]", msm(zcashInfinity(G2Inf, 0x40) -> zero), Fail),
        ("[(0xc0-infinity,1)]", msm(zcashInfinity(G2Inf, 0xc0) -> one), Fail)
      )
    ) ++ scalarTable ++ repeats ++ largeK ++
      paddingCases(op, msm(G2 -> BigInt(5)), Seq(0, 64, 128, 192), "[(G2,5)]") ++
      paddingCases(op, msm(G2 -> one, P2 -> one), Seq(288, 352, 416, 480), "[(G2,1),(P2,1)]") ++
      paddingCases(op, msm(G2Inf -> zero), Seq(0), "[(O,0)]") ++
      boundaryCases(op, msm(G2 -> one), Seq(0, 64, 128, 192), "[(G2,1)]", NoOracle) ++
      plusPCases(op, msm(G2 -> one), Seq(0, 64, 128, 192), "[(G2,1)]") ++
      flagCases(op, msm(G2 -> one), Seq(0, 64, 128), "[(G2,1)]") ++
      lengthCases(
        op,
        msm(G2 -> one, P2 -> BigInt(2), G2 -> BigInt(3)),
        Seq(0, 1, 32, 256, 287, 289, 320, 575, 577, 700)
      )

  private def pairs(ps: (ByteString, ByteString)*): ByteString = concat(ps.map((a, b) => a ++ b))

  private def pairingEdges: Vector[Eip2537Case] =
    val op = Eip2537Op.Pairing
    val negG = negG1(G1)
    val base = pairs(G1 -> G2)
    named(
      op,
      Seq(
        ("e(G,G2)", base, PairingFalse),
        ("e(G,G2)e(-G,G2)", pairs(G1 -> G2, negG -> G2), PairingTrue),
        ("e(G,G2)e(G,-G2)", pairs(G1 -> G2, G1 -> negG2(G2)), PairingTrue),
        ("e(G,-G2)e(-G,G2)", pairs(G1 -> negG2(G2), negG -> G2), PairingFalse),
        ("e(P1,G2)e(-P1,G2)", pairs(P1 -> G2, negG1(P1) -> G2), PairingTrue),
        ("e(G,P2)e(-G,P2)", pairs(G1 -> P2, negG -> P2), PairingTrue),
        ("e(G,G2)^2 e(-G,G2)^2", pairs(G1 -> G2, G1 -> G2, negG -> G2, negG -> G2), PairingTrue),
        ("e(G,2G2)e(-G,G2)e(-G,G2)", pairs(G1 -> G2Double, negG -> G2, negG -> G2), PairingTrue),
        ("e(2G,G2)e(-G,2G2)", pairs(G1Double -> G2, negG -> G2Double), PairingTrue),
        ("e(2G,G2)e(-G,G2)", pairs(G1Double -> G2, negG -> G2), PairingFalse),
        ("e(O,G2)", pairs(G1Inf -> G2), PairingTrue),
        ("e(G,O)", pairs(G1 -> G2Inf), PairingTrue),
        ("e(O,O)", pairs(G1Inf -> G2Inf), PairingTrue),
        ("e(O,O)e(O,O)", pairs(G1Inf -> G2Inf, G1Inf -> G2Inf), PairingTrue),
        ("e(O,G2)e(G,G2)e(-G,G2)", pairs(G1Inf -> G2, G1 -> G2, negG -> G2), PairingTrue),
        ("e(G,G2)e(O,O)e(-G,G2)", pairs(G1 -> G2, G1Inf -> G2Inf, negG -> G2), PairingTrue),
        ("e(G,G2)e(-G,G2)e(G,O)", pairs(G1 -> G2, negG -> G2, G1 -> G2Inf), PairingTrue),
        ("e(O,G2)e(G,G2)", pairs(G1Inf -> G2, G1 -> G2), PairingFalse),
        ("e(G,O)e(G,G2)", pairs(G1 -> G2Inf, G1 -> G2), PairingFalse),
        ("e(O,P2)e(P1,O)", pairs(G1Inf -> P2, P1 -> G2Inf), PairingTrue),
        ("e(notInSubgroup,G2)", pairs(G1NotInSubgroup -> G2), Fail),
        (
          "e(notInSubgroup,O): points are checked before an infinity pair is skipped",
          pairs(G1NotInSubgroup -> G2Inf),
          Fail
        ),
        ("e(O,notInSubgroup)", pairs(G1Inf -> G2NotInSubgroup), Fail),
        ("e(G,notInSubgroup)", pairs(G1 -> G2NotInSubgroup), Fail),
        ("e(order3,G2)", pairs(G1Order3 -> G2), Fail),
        ("e(order3,O)", pairs(G1Order3 -> G2Inf), Fail),
        ("e(G,G2)e(-G,G2)e(O,notInSubgroup)", pairs(G1 -> G2, negG -> G2, G1Inf -> G2NotInSubgroup), Fail),
        ("e(notInSubgroup,O)e(G,G2)", pairs(G1NotInSubgroup -> G2Inf, G1 -> G2), Fail),
        ("e(notOnCurve,O)", pairs(G1NotOnCurve -> G2Inf), Fail),
        ("e(O,notOnCurve)", pairs(G1Inf -> G2NotOnCurve), Fail),
        ("e(G,G2)e(notOnCurve,G2)", pairs(G1 -> G2, G1NotOnCurve -> G2), Fail),
        ("e((p,0),G2): infinity mod p, not canonical", pairs(g1(P, 0) -> G2), Fail),
        ("e((p,0),O)", pairs(g1(P, 0) -> G2Inf), Fail),
        ("e(G,(p,0,0,0))", pairs(G1 -> g2(P, 0, 0, 0)), Fail),
        ("e(0x40-infinity,G2)", pairs(zcashInfinity(G1Inf, 0x40) -> G2), Fail),
        ("e(G,0xc0-infinity)", pairs(G1 -> zcashInfinity(G2Inf, 0xc0)), Fail)
      )
    ) ++
      paddingCases(op, base, (0 until 6).map(_ * 64), "e(G,G2)") ++
      paddingCases(op, pairs(G1Inf -> G2Inf), Seq(0, 128), "e(O,O)") ++
      boundaryCases(op, base, Seq(0, 64, 128, 320), "e(G,G2)", NoOracle) ++
      plusPCases(op, base, Seq(0, 64, 128, 192, 256, 320), "e(G,G2)") ++
      flagCases(op, base, Seq(0, 128), "e(G,G2)") ++
      lengthCases(op, pairs(G1 -> G2, negG -> G2, G1 -> G2), Seq(0, 1, 128, 256, 383, 385, 512, 767, 769, 896))

  private def mapG1Edges: Vector[Eip2537Case] =
    val op = Eip2537Op.MapFpToG1
    val canonical = Vector(
      "0: the SSWU exceptional case" -> BigInt(0),
      "1" -> BigInt(1),
      "2" -> BigInt(2),
      "p-2" -> (P - 2),
      "(p-1)/2" -> ((P - 1) / 2),
      "(p+1)/2" -> ((P + 1) / 2),
      "2^380" -> (BigInt(1) << 380)
    )
    val nonCanonical = Vector(
      "2p" -> (P * 2),
      "2^381" -> (BigInt(1) << 381),
      "2^384-1: zero padding, 48 bytes of 0xff" -> ((BigInt(1) << 384) - 1)
    )
    canonical.map((n, v) => edge(op, s"u = $n", fp(v), AnyOk)) ++
      nonCanonical.map((n, v) => edge(op, s"u = $n", fp(v), Fail)) ++
      boundaryCases(op, fp(1), Seq(0), "u", AnyOk) ++
      paddingCases(op, fp(1), Seq(0), "u=1") ++
      paddingCases(op, fp(P - 1), Seq(0), "u=p-1") ++
      flagCases(op, fp(1), Seq(0), "u=1") ++
      flagCases(op, fp(0), Seq(0), "u=0") ++
      lengthCases(op, fp(1) ++ fp(2), Seq(0, 1, 32, 48, 63, 65, 96, 128))

  private def mapG2Edges: Vector[Eip2537Case] =
    val op = Eip2537Op.MapFp2ToG2
    val values = Vector("0" -> BigInt(0), "1" -> BigInt(1), "p-1" -> (P - 1), "(p-1)/2" -> ((P - 1) / 2))
    val grid =
      for
        (n0, c0) <- values
        (n1, c1) <- values
      yield edge(op, s"u = ($n0, $n1)", fp(c0) ++ fp(c1), AnyOk)
    grid ++
      boundaryCases(op, fp(0) ++ fp(0), Seq(0, 64), "u=(0,0)", AnyOk) ++
      plusPCases(op, fp(1) ++ fp(1), Seq(0, 64), "u=(1,1)") ++
      paddingCases(op, fp(1) ++ fp(1), Seq(0, 64), "u=(1,1)") ++
      flagCases(op, fp(1) ++ fp(1), Seq(0, 64), "u=(1,1)") ++
      lengthCases(op, fp(1) ++ fp(1) ++ fp(2), Seq(0, 1, 64, 127, 129, 192, 256))

  lazy val edges: Vector[Eip2537Case] =
    g1AddEdges ++ g2AddEdges ++ g1MsmEdges ++ g2MsmEdges ++ pairingEdges ++ mapG1Edges ++ mapG2Edges

  // ---- seeded fuzz -----------------------------------------------------------------------------------------------

  /** Modest counts: the whole differential stays at a few seconds. */
  def fuzzCount(op: Eip2537Op): Int = op match
    case Eip2537Op.G1Add      => 300
    case Eip2537Op.G1Msm      => 200
    case Eip2537Op.G2Add      => 300
    case Eip2537Op.G2Msm      => 120
    case Eip2537Op.Pairing    => 100
    case Eip2537Op.MapFpToG1  => 300
    case Eip2537Op.MapFp2ToG2 => 200

  /** Subgroup points with nothing in common with the hand-picked ones: the outputs of the successful published MSM and
    * map vectors (an MSM over subgroup points, or a cofactor-cleared map, lands in the subgroup).
    */
  private def pool(seed: Vector[ByteString], from: Set[Eip2537Op]): Vector[ByteString] =
    val outputs = vectors.collect {
      case Eip2537Case(op, _, _, _, Eip2537Expectation.Output(bs)) if from.contains(op) && !isZero(bs) => bs
    }
    (seed ++ outputs).distinct.take(64)

  private def pick(rnd: Random, from: Vector[ByteString]): ByteString = from(rnd.nextInt(from.size))

  private def randomFp(rnd: Random): BigInt = BigInt(384, rnd).mod(P)

  private def g1Atom(rnd: Random, valid: Vector[ByteString]): ByteString =
    val roll = rnd.nextInt(100)
    if roll < 70 then pick(rnd, valid)
    else if roll < 78 then negG1(pick(rnd, valid))
    else if roll < 85 then G1Inf
    else if roll < 90 then G1NotInSubgroup
    else if roll < 93 then G1Order3
    else if roll < 96 then G1NotOnCurve
    else g1(randomFp(rnd), randomFp(rnd))

  private def g2Atom(rnd: Random, valid: Vector[ByteString]): ByteString =
    val roll = rnd.nextInt(100)
    if roll < 72 then pick(rnd, valid)
    else if roll < 80 then negG2(pick(rnd, valid))
    else if roll < 87 then G2Inf
    else if roll < 93 then G2NotInSubgroup
    else if roll < 97 then G2NotOnCurve
    else g2(randomFp(rnd), randomFp(rnd), randomFp(rnd), randomFp(rnd))

  private def fuzzScalar(rnd: Random): BigInt = rnd.nextInt(10) match
    case 0 => BigInt(0)
    case 1 => BigInt(1 + rnd.nextInt(16))
    case 2 => R - 1 - BigInt(rnd.nextInt(4))
    case 3 => R + BigInt(rnd.nextInt(4))
    case 4 => Max256 - BigInt(rnd.nextInt(4))
    case _ => BigInt(256, rnd)

  /** Mostly canonical; p + small and 381 random bits (above p about a third of the time) are not. */
  private def fuzzFp(rnd: Random): BigInt = rnd.nextInt(10) match
    case 0 => BigInt(0)
    case 1 => BigInt(1 + rnd.nextInt(16))
    case 2 => P - 1 - BigInt(rnd.nextInt(4))
    case 3 => P + BigInt(rnd.nextInt(4))
    case 4 => BigInt(381, rnd)
    case _ => randomFp(rnd)

  /** Four times in ten a pairing that should hold, e(a,b)e(-a,b), shuffled with infinity pairs; else random pairs. */
  private def fuzzPairing(rnd: Random, g1s: Vector[ByteString], g2s: Vector[ByteString]): ByteString =
    val chosen =
      if rnd.nextInt(10) < 4 then
        val balanced = (0 until 1 + rnd.nextInt(2)).toVector.flatMap { _ =>
          val a = pick(rnd, g1s)
          val b = pick(rnd, g2s)
          Vector(a ++ b, negG1(a) ++ b)
        }
        val infinities = (0 until rnd.nextInt(3)).toVector.map { _ =>
          rnd.nextInt(3) match
            case 0 => G1Inf ++ pick(rnd, g2s)
            case 1 => pick(rnd, g1s) ++ G2Inf
            case _ => G1Inf ++ G2Inf
        }
        rnd.shuffle(balanced ++ infinities)
      else (0 until 1 + rnd.nextInt(3)).toVector.map(_ => g1Atom(rnd, g1s) ++ g2Atom(rnd, g2s))
    concat(chosen)

  /** The byte offsets of every padding byte in an input of `length` bytes for `op`. */
  private def paddingOffsets(op: Eip2537Op, length: Int): Vector[Int] =
    val wordStarts = op match
      case Eip2537Op.G1Msm => (0 until length / 160).toVector.flatMap(i => Vector(i * 160, i * 160 + 64))
      case Eip2537Op.G2Msm => (0 until length / 288).toVector.flatMap(i => (0 until 4).map(j => i * 288 + j * 64))
      case _               => (0 until length / 64).toVector.map(_ * 64)
    wordStarts.flatMap(w => (0 until 16).map(w + _)).filter(_ < length)

  /** One or two of: a bit flip, a random byte, a non-zero padding byte, or (rarely) a byte or three cut or added. */
  private def mutate(op: Eip2537Op, input: ByteString, rnd: Random): ByteString =
    (0 until 1 + rnd.nextInt(2)).foldLeft(input) { (bs, _) =>
      if bs.isEmpty then bs
      else
        rnd.nextInt(12) match
          case 0 => bs.dropRight(1 + rnd.nextInt(3))
          case 1 => bs ++ ByteString(Array.fill(1 + rnd.nextInt(3))(rnd.nextInt(256).toByte))
          case 2 | 3 | 4 =>
            val pads = paddingOffsets(op, bs.length)
            if pads.isEmpty then bs else withByte(bs, pads(rnd.nextInt(pads.size)), _ => 1 + rnd.nextInt(255))
          case 5 | 6 | 7 => withByte(bs, rnd.nextInt(bs.length), b => b ^ (1 << rnd.nextInt(8)))
          case _         => withByte(bs, rnd.nextInt(bs.length), _ => rnd.nextInt(256))
    }

  private def fuzzInput(op: Eip2537Op, rnd: Random, g1s: Vector[ByteString], g2s: Vector[ByteString]): ByteString =
    val base = op match
      case Eip2537Op.G1Add => g1Atom(rnd, g1s) ++ g1Atom(rnd, g1s)
      case Eip2537Op.G2Add => g2Atom(rnd, g2s) ++ g2Atom(rnd, g2s)
      case Eip2537Op.G1Msm =>
        concat((0 until 1 + rnd.nextInt(6)).map(_ => g1Atom(rnd, g1s) ++ scalar(fuzzScalar(rnd))))
      case Eip2537Op.G2Msm =>
        concat((0 until 1 + rnd.nextInt(4)).map(_ => g2Atom(rnd, g2s) ++ scalar(fuzzScalar(rnd))))
      case Eip2537Op.Pairing    => fuzzPairing(rnd, g1s, g2s)
      case Eip2537Op.MapFpToG1  => fp(fuzzFp(rnd))
      case Eip2537Op.MapFp2ToG2 => fp(fuzzFp(rnd)) ++ fp(fuzzFp(rnd))
    if rnd.nextBoolean() then base else mutate(op, base, rnd)

  /** The seeded fuzz: one `Random(seed)` drawn in a fixed op order, so the same seed gives the same inputs. */
  def fuzz(seed: Long): Vector[Eip2537Case] =
    val rnd = new Random(seed)
    val g1s = pool(Vector(G1, P1, G1Double), Set(Eip2537Op.G1Msm, Eip2537Op.MapFpToG1))
    val g2s = pool(Vector(G2, P2, G2Double), Set(Eip2537Op.G2Msm, Eip2537Op.MapFp2ToG2))
    Eip2537Op.values.toVector.flatMap { op =>
      (0 until fuzzCount(op)).toVector.map(i =>
        Eip2537Case(op, "fuzz", s"seed=0x${seed.toHexString} #$i", fuzzInput(op, rnd, g1s, g2s), NoOracle)
      )
    }

  lazy val all: Vector[Eip2537Case] = vectors ++ edges ++ fuzz(FuzzSeed)
