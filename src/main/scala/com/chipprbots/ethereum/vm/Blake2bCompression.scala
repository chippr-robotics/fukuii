package com.chipprbots.ethereum.vm

import java.util.Arrays.copyOfRange

// scalastyle:off magic.number
object Blake2bCompression:
  val MessageBytesLength = 213

  import org.bouncycastle.util.Pack

  private val IV: Array[Long] = Array(0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL,
    0xa54ff53a5f1d36f1L, 0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L)

  private val PRECOMPUTED: Array[Array[Byte]] = Array(
    Array[Byte](0, 2, 4, 6, 1, 3, 5, 7, 8, 10, 12, 14, 9, 11, 13, 15),
    Array[Byte](14, 4, 9, 13, 10, 8, 15, 6, 1, 0, 11, 5, 12, 2, 7, 3),
    Array[Byte](11, 12, 5, 15, 8, 0, 2, 13, 10, 3, 7, 9, 14, 6, 1, 4),
    Array[Byte](7, 3, 13, 11, 9, 1, 12, 14, 2, 5, 4, 15, 6, 10, 0, 8),
    Array[Byte](9, 5, 2, 10, 0, 7, 4, 15, 14, 11, 6, 3, 1, 12, 8, 13),
    Array[Byte](2, 6, 0, 8, 12, 10, 11, 3, 4, 7, 15, 1, 13, 5, 14, 9),
    Array[Byte](12, 1, 14, 4, 5, 15, 13, 10, 0, 6, 9, 8, 7, 3, 2, 11),
    Array[Byte](13, 7, 12, 3, 11, 14, 1, 9, 5, 15, 8, 2, 0, 4, 6, 10),
    Array[Byte](6, 14, 11, 0, 15, 9, 3, 8, 12, 13, 1, 10, 2, 7, 4, 5),
    Array[Byte](10, 8, 7, 1, 2, 4, 6, 5, 15, 9, 3, 13, 11, 14, 12, 0)
  )

  private def bytesToInt(bytes: Array[Byte]) = Pack.bigEndianToInt(bytes, 0)

  private def bytesToLong(bytes: Array[Byte]) = Pack.littleEndianToLong(bytes, 0)

  def isValidInput(input: Array[Byte]): Boolean =
    !(input.length != MessageBytesLength || (input(212) & 0xfe) != 0)

  def parseNumberOfRounds(input: Array[Byte]): Long =
    Integer.toUnsignedLong(bytesToInt(copyOfRange(input, 0, 4)))

  /** Parses input according to the rules defined in: https://eips.ethereum.org/EIPS/eip-152 The encoded inputs are
    * corresponding to the ones specified in the BLAKE2 RFC Section 3.2:
    *
    * rounds - the number of rounds - 32-bit unsigned big-endian word h - the state vector - 8 unsigned 64-bit
    * little-endian words m - the message block vector - 16 unsigned 64-bit little-endian words t_0, t_1 - offset
    * counters - 2 unsigned 64-bit little-endian words f - the final block indicator flag - 8-bit word
    *
    * @param input
    *   [4 bytes for rounds][64 bytes for h][128 bytes for m][8 bytes for t_0][8 bytes for t_1][1 byte for f]
    * @return
    *   all parsed inputs from input array: (rounds, h, m, t, f)
    */
  private def parseInput(input: Array[Byte]): (Long, Array[Long], Array[Long], Array[Long], Boolean) =
    val rounds = parseNumberOfRounds(input)
    val h = new Array[Long](8)
    val m = new Array[Long](16)
    val t = new Array[Long](2)

    var i = 0
    while i < h.length do
      val offset = 4 + i * 8
      h(i) = bytesToLong(copyOfRange(input, offset, offset + 8))
      i += 1

    var j = 0
    while j < 16 do
      val offset = 68 + j * 8
      m(j) = bytesToLong(copyOfRange(input, offset, offset + 8))
      j += 1

    t(0) = bytesToLong(copyOfRange(input, 196, 204))
    t(1) = bytesToLong(copyOfRange(input, 204, 212))
    val f = input(212) != 0
    (rounds, h, m, t, f)

  def blake2bCompress(input: Array[Byte]): Option[Array[Byte]] =
    if isValidInput(input) then
      val (rounds, h, m, t, f) = parseInput(input)
      compress(rounds, h, m, t, f)
      Some(convertToBytes(h))
    else None

  private def convertToBytes(h: Array[Long]): Array[Byte] =
    var i = 0
    val out = new Array[Byte](h.length * 8)
    while i < h.length do
      System.arraycopy(Pack.longToLittleEndian(h(i)), 0, out, i * 8, 8)
      i += 1
    out

  /** PRECOMPUTED flattened: round r uses message word `m(Sigma(16 * (r % 10) + k))`. */
  private val Sigma: Array[Int] = PRECOMPUTED.flatMap(_.map(_.toInt))

  /** BLAKE2b F (RFC 7693 section 3.2) with a caller-chosen round count (EIP-152).
    *
    * The working vector lives in sixteen locals and the message schedule advances by a counter instead of `j % 10`, the
    * shape of go-ethereum's `fGeneric`. It is the same computation as the array-and-`mix` version it replaces, step for
    * step: each block below is G(a, b, c, d, x, y) with the rotations 32, 24, 16, 63. Why: the round count is
    * caller-controlled up to 2^32 - 1 at one gas per round, so ethereum/tests `CALLBlake2f_MaxRounds` executes
    * 4,294,967,295 rounds in one block; at ~21 ns/round that took 88 s and did not fit hive's 120 s startup limit.
    */
  private def compress(rounds: Long, h: Array[Long], m: Array[Long], t: Array[Long], f: Boolean): Unit =
    var v0 = h(0); var v1 = h(1); var v2 = h(2); var v3 = h(3)
    var v4 = h(4); var v5 = h(5); var v6 = h(6); var v7 = h(7)
    var v8 = IV(0); var v9 = IV(1); var v10 = IV(2); var v11 = IV(3)
    var v12 = IV(4) ^ t(0); var v13 = IV(5) ^ t(1)
    var v14 = if f then IV(6) ^ 0xffffffffffffffffL else IV(6)
    var v15 = IV(7)

    var j = 0L
    var s = 0 // 16 * (j % 10)
    while j < rounds do
      // G(0, 4, 8, 12, m[s0], m[s4])
      v0 += m(Sigma(s + 0)) + v4
      v12 = java.lang.Long.rotateLeft(v12 ^ v0, -32)
      v8 += v12
      v4 = java.lang.Long.rotateLeft(v4 ^ v8, -24)
      v0 += m(Sigma(s + 4)) + v4
      v12 = java.lang.Long.rotateLeft(v12 ^ v0, -16)
      v8 += v12
      v4 = java.lang.Long.rotateLeft(v4 ^ v8, -63)
      // G(1, 5, 9, 13, m[s1], m[s5])
      v1 += m(Sigma(s + 1)) + v5
      v13 = java.lang.Long.rotateLeft(v13 ^ v1, -32)
      v9 += v13
      v5 = java.lang.Long.rotateLeft(v5 ^ v9, -24)
      v1 += m(Sigma(s + 5)) + v5
      v13 = java.lang.Long.rotateLeft(v13 ^ v1, -16)
      v9 += v13
      v5 = java.lang.Long.rotateLeft(v5 ^ v9, -63)
      // G(2, 6, 10, 14, m[s2], m[s6])
      v2 += m(Sigma(s + 2)) + v6
      v14 = java.lang.Long.rotateLeft(v14 ^ v2, -32)
      v10 += v14
      v6 = java.lang.Long.rotateLeft(v6 ^ v10, -24)
      v2 += m(Sigma(s + 6)) + v6
      v14 = java.lang.Long.rotateLeft(v14 ^ v2, -16)
      v10 += v14
      v6 = java.lang.Long.rotateLeft(v6 ^ v10, -63)
      // G(3, 7, 11, 15, m[s3], m[s7])
      v3 += m(Sigma(s + 3)) + v7
      v15 = java.lang.Long.rotateLeft(v15 ^ v3, -32)
      v11 += v15
      v7 = java.lang.Long.rotateLeft(v7 ^ v11, -24)
      v3 += m(Sigma(s + 7)) + v7
      v15 = java.lang.Long.rotateLeft(v15 ^ v3, -16)
      v11 += v15
      v7 = java.lang.Long.rotateLeft(v7 ^ v11, -63)
      // G(0, 5, 10, 15, m[s8], m[s12])
      v0 += m(Sigma(s + 8)) + v5
      v15 = java.lang.Long.rotateLeft(v15 ^ v0, -32)
      v10 += v15
      v5 = java.lang.Long.rotateLeft(v5 ^ v10, -24)
      v0 += m(Sigma(s + 12)) + v5
      v15 = java.lang.Long.rotateLeft(v15 ^ v0, -16)
      v10 += v15
      v5 = java.lang.Long.rotateLeft(v5 ^ v10, -63)
      // G(1, 6, 11, 12, m[s9], m[s13])
      v1 += m(Sigma(s + 9)) + v6
      v12 = java.lang.Long.rotateLeft(v12 ^ v1, -32)
      v11 += v12
      v6 = java.lang.Long.rotateLeft(v6 ^ v11, -24)
      v1 += m(Sigma(s + 13)) + v6
      v12 = java.lang.Long.rotateLeft(v12 ^ v1, -16)
      v11 += v12
      v6 = java.lang.Long.rotateLeft(v6 ^ v11, -63)
      // G(2, 7, 8, 13, m[s10], m[s14])
      v2 += m(Sigma(s + 10)) + v7
      v13 = java.lang.Long.rotateLeft(v13 ^ v2, -32)
      v8 += v13
      v7 = java.lang.Long.rotateLeft(v7 ^ v8, -24)
      v2 += m(Sigma(s + 14)) + v7
      v13 = java.lang.Long.rotateLeft(v13 ^ v2, -16)
      v8 += v13
      v7 = java.lang.Long.rotateLeft(v7 ^ v8, -63)
      // G(3, 4, 9, 14, m[s11], m[s15])
      v3 += m(Sigma(s + 11)) + v4
      v14 = java.lang.Long.rotateLeft(v14 ^ v3, -32)
      v9 += v14
      v4 = java.lang.Long.rotateLeft(v4 ^ v9, -24)
      v3 += m(Sigma(s + 15)) + v4
      v14 = java.lang.Long.rotateLeft(v14 ^ v3, -16)
      v9 += v14
      v4 = java.lang.Long.rotateLeft(v4 ^ v9, -63)
      s += 16
      if s == 160 then s = 0
      j += 1

    // update h:
    h(0) ^= v0 ^ v8
    h(1) ^= v1 ^ v9
    h(2) ^= v2 ^ v10
    h(3) ^= v3 ^ v11
    h(4) ^= v4 ^ v12
    h(5) ^= v5 ^ v13
    h(6) ^= v6 ^ v14
    h(7) ^= v7 ^ v15
