package com.chipprbots.ethereum.vm

import java.math.BigInteger

/** EIP-2537's field-to-curve maps, before cofactor clearing: simplified SWU onto a curve isogenous to BLS12-381's E1 or
  * E2, then the isogeny onto E1 or E2 (RFC 9380 sections 6.6.2 and 8.8, appendices E.2 and E.3, which EIP-2537's
  * `field_to_curve.md` takes its parameters from). [[BlstNative]] clears the cofactor on blst.
  *
  * blst has these maps (`blst_map_to_g1`, `blst_map_to_g2`) but its Java bindings do not expose them, and the Windows
  * build exports no C symbol, so they are written out here (ADR VM-008). Nothing here is secret, so nothing is
  * constant-time. Which square root a step computes does not matter: step 9 of the map fixes the sign of y to the sign
  * of u, so the output is unique.
  *
  * The isogeny constants were extracted from the RFC text by a script, not typed. A wrong one would put the output off
  * E1 or E2, which blst rejects when the point is decoded; the EIP-2537 differential then fails.
  */
private[vm] object Bls12381MapToCurve:

  /** The base field modulus p. p = 3 mod 4. */
  val P: BigInteger = h(
    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab"
  )

  private def h(hex: String): BigInteger = new BigInteger(hex, 16)

  private val Zero = BigInteger.ZERO
  private val One = BigInteger.ONE
  private val SqrtExponent = P.add(One).shiftRight(2) // (p + 1) / 4
  private val LegendreExponent = P.subtract(One).shiftRight(1) // (p - 1) / 2

  // ── Fp ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

  private def add(a: BigInteger, b: BigInteger): BigInteger = a.add(b).mod(P)
  private def sub(a: BigInteger, b: BigInteger): BigInteger = a.subtract(b).mod(P)
  private def mul(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(P)
  private def neg(a: BigInteger): BigInteger = P.subtract(a).mod(P)

  /** inv0 of RFC 9380: the inverse, and 0 for 0. */
  private def inv0(a: BigInteger): BigInteger = if a.signum == 0 then Zero else a.modInverse(P)

  /** Whether `a` is a square in Fp; 0 counts as one. */
  private def isSquare(a: BigInteger): Boolean = a.signum == 0 || a.modPow(LegendreExponent, P) == One

  /** A square root of a square `a`. */
  private def sqrt(a: BigInteger): BigInteger =
    val root = a.modPow(SqrtExponent, P)
    require(mul(root, root) == a, "sqrt of a non-square")
    root

  private def sgn0(a: BigInteger): Int = if a.testBit(0) then 1 else 0

  // ── Fp2 = Fp[i] / (i^2 + 1) ────────────────────────────────────────────────────────────────────────────────────────

  /** c0 + c1 * i, each coordinate reduced mod p. */
  final case class Fp2(c0: BigInteger, c1: BigInteger):
    def +(o: Fp2): Fp2 = Fp2(add(c0, o.c0), add(c1, o.c1))
    def -(o: Fp2): Fp2 = Fp2(sub(c0, o.c0), sub(c1, o.c1))
    def *(o: Fp2): Fp2 =
      Fp2(sub(c0.multiply(o.c0), c1.multiply(o.c1)), add(c0.multiply(o.c1), c1.multiply(o.c0)))
    def unary_- : Fp2 = Fp2(neg(c0), neg(c1))
    def isZero: Boolean = c0.signum == 0 && c1.signum == 0

    /** The norm c0^2 + c1^2, which is in Fp. */
    def norm: BigInteger = add(c0.multiply(c0), c1.multiply(c1))

    /** inv0: the inverse, and 0 for 0. */
    def inv0: Fp2 =
      if isZero then this
      else
        val n = norm.modInverse(P)
        Fp2(mul(c0, n), mul(neg(c1), n))

    /** a is a square in Fp2 exactly when its norm is a square in Fp (p = 3 mod 4). */
    def isSquare: Boolean = Bls12381MapToCurve.isSquare(norm)

    /** A square root of a square. */
    def sqrt: Fp2 =
      val root =
        if c1.signum == 0 then
          // An element of Fp is a square in Fp2: either it is one in Fp, or -c0 is and the root is sqrt(-c0) * i.
          if Bls12381MapToCurve.isSquare(c0) then Fp2(Bls12381MapToCurve.sqrt(c0), Zero)
          else Fp2(Zero, Bls12381MapToCurve.sqrt(neg(c0)))
        else
          // x0^2 - x1^2 = c0 and 2 x0 x1 = c1, so x0^2 = (c0 +- sqrt(norm)) / 2 for the sign that gives a square.
          val alpha = Bls12381MapToCurve.sqrt(norm)
          val half = BigInteger.TWO.modInverse(P)
          val plus = mul(add(c0, alpha), half)
          val delta = if Bls12381MapToCurve.isSquare(plus) then plus else mul(sub(c0, alpha), half)
          val x0 = Bls12381MapToCurve.sqrt(delta)
          Fp2(x0, mul(c1, mul(BigInteger.TWO, x0).modInverse(P)))
      require(root * root == this, "sqrt of a non-square")
      root

    /** sgn0 for m = 2 (RFC 9380 section 4.1). */
    def sgn0: Int = if c0.testBit(0) || (c0.signum == 0 && c1.testBit(0)) then 1 else 0

  object Fp2:
    val Zero: Fp2 = Fp2(BigInteger.ZERO, BigInteger.ZERO)
    val One: Fp2 = Fp2(BigInteger.ONE, BigInteger.ZERO)

  // ── G1: simplified SWU onto E1' and the 11-isogeny onto E1: y^2 = x^3 + 4 ───────────────────────────────────────────

  private val G1A = h("144698a3b8e9433d693a02c96d4982b0ea985383ee66a8d8e8981aefd881ac98936f8da0e0f97f5cf428082d584c1d")
  private val G1B = h(
    "12e2908d11688030018b12e8753eee3b2016c1f0f24f4070a0b9c14fcef35ef55a23215a316ceaa5d1cc48e98e172be0"
  )
  private val G1Z = BigInteger.valueOf(11)

  /** The affine point on E1 that `u` maps to before cofactor clearing; `None` for the point at infinity, which the
    * isogeny gives where its denominator vanishes.
    */
  private val G1MinusBOverA = mul(neg(G1B), G1A.modInverse(P)) // -B / A
  private val G1BOverZA = mul(G1B, mul(G1Z, G1A).modInverse(P)) // B / (Z A)

  /** sqrt(-Z^3). -Z is a square (Z and -1 are not), so -Z^3 = -Z * Z^2 is one. */
  private val G1SqrtMinusZCubed: BigInteger = sqrt(neg(mul(G1Z, mul(G1Z, G1Z))))

  def mapToG1Uncleared(u: BigInteger): Option[(BigInteger, BigInteger)] =
    require(u.signum >= 0 && u.compareTo(P) < 0, "u is not a reduced field element")
    // RFC 9380 section 6.6.2, steps 1-10.
    val u2 = mul(u, u)
    val zu2 = mul(G1Z, u2)
    val tv1 = inv0(add(mul(zu2, zu2), zu2)) // 1. inv0(Z^2 u^4 + Z u^2)
    val x1 =
      if tv1.signum == 0 then G1BOverZA // 3. B / (Z A)
      else mul(G1MinusBOverA, add(One, tv1)) // 2. (-B / A)(1 + tv1)
    def g(x: BigInteger) = add(mul(add(mul(x, x), G1A), x), G1B) // x^3 + A x + B
    val gx1 = g(x1)
    // 7-8, with one exponentiation (the idea of RFC 9380 appendix F.2.1.2): r = gx1^((p+1)/4) is sqrt(gx1) when gx1 is
    // a square, and sqrt(-gx1) when it is not. In that case g(x2) = Z^3 u^6 gx1, so sqrt(g(x2)) = u^3 sqrt(-Z^3) r.
    val r = gx1.modPow(SqrtExponent, P)
    val (x, y0) =
      if mul(r, r) == gx1 then (x1, r)
      else
        val x2 = mul(zu2, x1) // 5. Z u^2 x1
        val y2 = mul(mul(mul(u2, u), G1SqrtMinusZCubed), r)
        require(mul(y2, y2) == g(x2), "sqrt(g(x2)) is wrong")
        (x2, y2)
    val y = if sgn0(u) != sgn0(y0) then neg(y0) else y0 // 9.
    // RFC 9380 appendix E.2, with one inversion for both denominators.
    val xNum = horner(G1K1, x)
    val xDen = horner(G1K2 :+ One, x)
    val yNum = horner(G1K3, x)
    val yDen = horner(G1K4 :+ One, x)
    val den = mul(xDen, yDen)
    Option.when(den.signum != 0) {
      val inverse = den.modInverse(P)
      (mul(xNum, mul(yDen, inverse)), mul(y, mul(yNum, mul(xDen, inverse))))
    }

  /** k_0 + k_1 x + ... + k_n x^n, coefficients lowest first. */
  private def horner(k: Vector[BigInteger], x: BigInteger): BigInteger =
    k.foldRight(Zero)((c, acc) => add(mul(acc, x), c))

  // ── G2: simplified SWU onto E2' and the 3-isogeny onto E2: y^2 = x^3 + 4(1 + i) ────────────────────────────────────

  private val G2A = Fp2(Zero, BigInteger.valueOf(240)) // 240 i
  private val G2B = Fp2(BigInteger.valueOf(1012), BigInteger.valueOf(1012)) // 1012 (1 + i)
  private val G2Z = Fp2(neg(BigInteger.TWO), neg(One)) // -(2 + i)

  /** The affine point on E2 that `u` maps to before cofactor clearing; `None` for the point at infinity. */
  def mapToG2Uncleared(u: Fp2): Option[(Fp2, Fp2)] =
    require(
      Seq(u.c0, u.c1).forall(c => c.signum >= 0 && c.compareTo(P) < 0),
      "u is not a reduced field element"
    )
    val u2 = u * u
    val zu2 = G2Z * u2
    val tv1 = ((zu2 * zu2) + zu2).inv0
    val x1 =
      if tv1.isZero then G2B * (G2Z * G2A).inv0
      else -G2B * G2A.inv0 * (Fp2.One + tv1)
    def g(x: Fp2) = ((x * x) + G2A) * x + G2B
    val gx1 = g(x1)
    val (x, y0) =
      if gx1.isSquare then (x1, gx1.sqrt)
      else
        val x2 = zu2 * x1
        (x2, g(x2).sqrt)
    val y = if u.sgn0 != y0.sgn0 then -y0 else y0
    // RFC 9380 appendix E.3.
    val xNum = horner2(G2K1, x)
    val xDen = horner2(G2K2 :+ Fp2.One, x)
    val yNum = horner2(G2K3, x)
    val yDen = horner2(G2K4 :+ Fp2.One, x)
    val den = xDen * yDen
    Option.when(!den.isZero) {
      val inverse = den.inv0
      (xNum * (yDen * inverse), y * (yNum * (xDen * inverse)))
    }

  private def horner2(k: Vector[Fp2], x: Fp2): Fp2 = k.foldRight(Fp2.Zero)((c, acc) => acc * x + c)

  // ── Isogeny constants: RFC 9380 appendix E.2 (G1) and E.3 (G2), k_(i,j) at index j ──────────────────────────────

  private val G1K1: Vector[BigInteger] = Vector(
    h("11a05f2b1e833340b809101dd99815856b303e88a2d7005ff2627b56cdb4e2c85610c2d5f2e62d6eaeac1662734649b7"),
    h("17294ed3e943ab2f0588bab22147a81c7c17e75b2f6a8417f565e33c70d1e86b4838f2a6f318c356e834eef1b3cb83bb"),
    h("d54005db97678ec1d1048c5d10a9a1bce032473295983e56878e501ec68e25c958c3e3d2a09729fe0179f9dac9edcb0"),
    h("1778e7166fcc6db74e0609d307e55412d7f5e4656a8dbf25f1b33289f1b330835336e25ce3107193c5b388641d9b6861"),
    h("e99726a3199f4436642b4b3e4118e5499db995a1257fb3f086eeb65982fac18985a286f301e77c451154ce9ac8895d9"),
    h("1630c3250d7313ff01d1201bf7a74ab5db3cb17dd952799b9ed3ab9097e68f90a0870d2dcae73d19cd13c1c66f652983"),
    h("d6ed6553fe44d296a3726c38ae652bfb11586264f0f8ce19008e218f9c86b2a8da25128c1052ecaddd7f225a139ed84"),
    h("17b81e7701abdbe2e8743884d1117e53356de5ab275b4db1a682c62ef0f2753339b7c8f8c8f475af9ccb5618e3f0c88e"),
    h("80d3cf1f9a78fc47b90b33563be990dc43b756ce79f5574a2c596c928c5d1de4fa295f296b74e956d71986a8497e317"),
    h("169b1f8e1bcfa7c42e0c37515d138f22dd2ecb803a0c5c99676314baf4bb1b7fa3190b2edc0327797f241067be390c9e"),
    h("10321da079ce07e272d8ec09d2565b0dfa7dccdde6787f96d50af36003b14866f69b771f8c285decca67df3f1605fb7b"),
    h("6e08c248e260e70bd1e962381edee3d31d79d7e22c837bc23c0bf1bc24c6b68c24b1b80b64d391fa9c8ba2e8ba2d229")
  )
  private val G1K2: Vector[BigInteger] = Vector(
    h("8ca8d548cff19ae18b2e62f4bd3fa6f01d5ef4ba35b48ba9c9588617fc8ac62b558d681be343df8993cf9fa40d21b1c"),
    h("12561a5deb559c4348b4711298e536367041e8ca0cf0800c0126c2588c48bf5713daa8846cb026e9e5c8276ec82b3bff"),
    h("b2962fe57a3225e8137e629bff2991f6f89416f5a718cd1fca64e00b11aceacd6a3d0967c94fedcfcc239ba5cb83e19"),
    h("3425581a58ae2fec83aafef7c40eb545b08243f16b1655154cca8abc28d6fd04976d5243eecf5c4130de8938dc62cd8"),
    h("13a8e162022914a80a6f1d5f43e7a07dffdfc759a12062bb8d6b44e833b306da9bd29ba81f35781d539d395b3532a21e"),
    h("e7355f8e4e667b955390f7f0506c6e9395735e9ce9cad4d0a43bcef24b8982f7400d24bc4228f11c02df9a29f6304a5"),
    h("772caacf16936190f3e0c63e0596721570f5799af53a1894e2e073062aede9cea73b3538f0de06cec2574496ee84a3a"),
    h("14a7ac2a9d64a8b230b3f5b074cf01996e7f63c21bca68a81996e1cdf9822c580fa5b9489d11e2d311f7d99bbdcc5a5e"),
    h("a10ecf6ada54f825e920b3dafc7a3cce07f8d1d7161366b74100da67f39883503826692abba43704776ec3a79a1d641"),
    h("95fc13ab9e92ad4476d6e3eb3a56680f682b4ee96f7d03776df533978f31c1593174e4b4b7865002d6384d168ecdd0a")
  )
  private val G1K3: Vector[BigInteger] = Vector(
    h("90d97c81ba24ee0259d1f094980dcfa11ad138e48a869522b52af6c956543d3cd0c7aee9b3ba3c2be9845719707bb33"),
    h("134996a104ee5811d51036d776fb46831223e96c254f383d0f906343eb67ad34d6c56711962fa8bfe097e75a2e41c696"),
    h("cc786baa966e66f4a384c86a3b49942552e2d658a31ce2c344be4b91400da7d26d521628b00523b8dfe240c72de1f6"),
    h("1f86376e8981c217898751ad8746757d42aa7b90eeb791c09e4a3ec03251cf9de405aba9ec61deca6355c77b0e5f4cb"),
    h("8cc03fdefe0ff135caf4fe2a21529c4195536fbe3ce50b879833fd221351adc2ee7f8dc099040a841b6daecf2e8fedb"),
    h("16603fca40634b6a2211e11db8f0a6a074a7d0d4afadb7bd76505c3d3ad5544e203f6326c95a807299b23ab13633a5f0"),
    h("4ab0b9bcfac1bbcb2c977d027796b3ce75bb8ca2be184cb5231413c4d634f3747a87ac2460f415ec961f8855fe9d6f2"),
    h("987c8d5333ab86fde9926bd2ca6c674170a05bfe3bdd81ffd038da6c26c842642f64550fedfe935a15e4ca31870fb29"),
    h("9fc4018bd96684be88c9e221e4da1bb8f3abd16679dc26c1e8b6e6a1f20cabe69d65201c78607a360370e577bdba587"),
    h("e1bba7a1186bdb5223abde7ada14a23c42a0ca7915af6fe06985e7ed1e4d43b9b3f7055dd4eba6f2bafaaebca731c30"),
    h("19713e47937cd1be0dfd0b8f1d43fb93cd2fcbcb6caf493fd1183e416389e61031bf3a5cce3fbafce813711ad011c132"),
    h("18b46a908f36f6deb918c143fed2edcc523559b8aaf0c2462e6bfe7f911f643249d9cdf41b44d606ce07c8a4d0074d8e"),
    h("b182cac101b9399d155096004f53f447aa7b12a3426b08ec02710e807b4633f06c851c1919211f20d4c04f00b971ef8"),
    h("245a394ad1eca9b72fc00ae7be315dc757b3b080d4c158013e6632d3c40659cc6cf90ad1c232a6442d9d3f5db980133"),
    h("5c129645e44cf1102a159f748c4a3fc5e673d81d7e86568d9ab0f5d396a7ce46ba1049b6579afb7866b1e715475224b"),
    h("15e6be4e990f03ce4ea50b3b42df2eb5cb181d8f84965a3957add4fa95af01b2b665027efec01c7704b456be69c8b604")
  )
  private val G1K4: Vector[BigInteger] = Vector(
    h("16112c4c3a9c98b252181140fad0eae9601a6de578980be6eec3232b5be72e7a07f3688ef60c206d01479253b03663c1"),
    h("1962d75c2381201e1a0cbd6c43c348b885c84ff731c4d59ca4a10356f453e01f78a4260763529e3532f6102c2e49a03d"),
    h("58df3306640da276faaae7d6e8eb15778c4855551ae7f310c35a5dd279cd2eca6757cd636f96f891e2538b53dbf67f2"),
    h("16b7d288798e5395f20d23bf89edb4d1d115c5dbddbcd30e123da489e726af41727364f2c28297ada8d26d98445f5416"),
    h("be0e079545f43e4b00cc912f8228ddcc6d19c9f0f69bbb0542eda0fc9dec916a20b15dc0fd2ededda39142311a5001d"),
    h("8d9e5297186db2d9fb266eaac783182b70152c65550d881c5ecd87b6f0f5a6449f38db9dfa9cce202c6477faaf9b7ac"),
    h("166007c08a99db2fc3ba8734ace9824b5eecfdfa8d0cf8ef5dd365bc400a0051d5fa9c01a58b1fb93d1a1399126a775c"),
    h("16a3ef08be3ea7ea03bcddfabba6ff6ee5a4375efa1f4fd7feb34fd206357132b920f5b00801dee460ee415a15812ed9"),
    h("1866c8ed336c61231a1be54fd1d74cc4f9fb0ce4c6af5920abc5750c4bf39b4852cfe2f7bb9248836b233d9d55535d4a"),
    h("167a55cda70a6e1cea820597d94a84903216f763e13d87bb5308592e7ea7d4fbc7385ea3d529b35e346ef48bb8913f55"),
    h("4d2f259eea405bd48f010a01ad2911d9c6dd039bb61a6290e591b36e636a5c871a5c29f4f83060400f8b49cba8f6aa8"),
    h("accbb67481d033ff5852c1e48c50c477f94ff8aefce42d28c0f9a88cea7913516f968986f7ebbea9684b529e2561092"),
    h("ad6b9514c767fe3c3613144b45f1496543346d98adf02267d5ceef9a00d9b8693000763e3b90ac11e99b138573345cc"),
    h("2660400eb2e4f3b628bdd0d53cd76f2bf565b94e72927c1cb748df27942480e420517bd8714cc80d1fadc1326ed06f7"),
    h("e0fa1d816ddc03e6b24255e0d7819c171c40f65e273b853324efcd6356caa205ca2f570f13497804415473a1d634b8f")
  )
  private val G2K1: Vector[Fp2] = Vector(
    Fp2(
      h("5c759507e8e333ebb5b7a9a47d7ed8532c52d39fd3a042a88b58423c50ae15d5c2638e343d9c71c6238aaaaaaaa97d6"),
      h("5c759507e8e333ebb5b7a9a47d7ed8532c52d39fd3a042a88b58423c50ae15d5c2638e343d9c71c6238aaaaaaaa97d6")
    ),
    Fp2(h("0"), h("11560bf17baa99bc32126fced787c88f984f87adf7ae0c7f9a208c6b4f20a4181472aaa9cb8d555526a9ffffffffc71a")),
    Fp2(
      h("11560bf17baa99bc32126fced787c88f984f87adf7ae0c7f9a208c6b4f20a4181472aaa9cb8d555526a9ffffffffc71e"),
      h("8ab05f8bdd54cde190937e76bc3e447cc27c3d6fbd7063fcd104635a790520c0a395554e5c6aaaa9354ffffffffe38d")
    ),
    Fp2(h("171d6541fa38ccfaed6dea691f5fb614cb14b4e7f4e810aa22d6108f142b85757098e38d0f671c7188e2aaaaaaaa5ed1"), h("0"))
  )
  private val G2K2: Vector[Fp2] = Vector(
    Fp2(h("0"), h("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaa63")),
    Fp2(h("c"), h("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaa9f"))
  )
  private val G2K3: Vector[Fp2] = Vector(
    Fp2(
      h("1530477c7ab4113b59a4c18b076d11930f7da5d4a07f649bf54439d87d27e500fc8c25ebf8c92f6812cfc71c71c6d706"),
      h("1530477c7ab4113b59a4c18b076d11930f7da5d4a07f649bf54439d87d27e500fc8c25ebf8c92f6812cfc71c71c6d706")
    ),
    Fp2(h("0"), h("5c759507e8e333ebb5b7a9a47d7ed8532c52d39fd3a042a88b58423c50ae15d5c2638e343d9c71c6238aaaaaaaa97be")),
    Fp2(
      h("11560bf17baa99bc32126fced787c88f984f87adf7ae0c7f9a208c6b4f20a4181472aaa9cb8d555526a9ffffffffc71c"),
      h("8ab05f8bdd54cde190937e76bc3e447cc27c3d6fbd7063fcd104635a790520c0a395554e5c6aaaa9354ffffffffe38f")
    ),
    Fp2(h("124c9ad43b6cf79bfbf7043de3811ad0761b0f37a1e26286b0e977c69aa274524e79097a56dc4bd9e1b371c71c718b10"), h("0"))
  )
  private val G2K4: Vector[Fp2] = Vector(
    Fp2(
      h("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffa8fb"),
      h("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffa8fb")
    ),
    Fp2(h("0"), h("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffa9d3")),
    Fp2(h("12"), h("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaa99"))
  )
