package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.crypto.ECDSASignature

/** Builds signatures whose public-key recovery lands on the secp256k1 point at infinity.
  *
  * Recovery computes Q = r^-1 (s*R - e*G). With R = G (r = Gx; Gy is even, so recovery id 27) and s = e mod n, s*R =
  * e*G and Q = O. The mirror R = -G (recovery id 28) with s = n - (e mod n) gives the same Q, so either one can be
  * chosen to keep s <= n/2 (the Homestead low-s rule). r and s are in range, so every syntactic check passes; only the
  * recovery itself can reject it — libsecp256k1 `secp256k1_ecdsa_sig_recover` returns 0 for Q = O.
  */
object PointAtInfinitySignature:
  val N: BigInt = BigInt("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
  val Gx: BigInt = BigInt("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)

  /** keccak256("")[12:] — the address fukuii derived from an empty (infinity) public key. */
  val InfinityAddress: Address = Address("0xdcc703c0e500b653ca82273b7bfad8045d85a470")

  /** Raw signature (v = 27/28) recovering to the point at infinity for message hash `hash`. */
  def rawFor(hash: Array[Byte]): ECDSASignature =
    val e = BigInt(1, hash).mod(N)
    require(e != 0)
    if e <= N / 2 then ECDSASignature(Gx, e, BigInt(ECDSASignature.negativePointSign))
    else ECDSASignature(Gx, N - e, BigInt(ECDSASignature.positivePointSign))

  /** Signs `tx` (legacy with optional EIP-155 chain id, or typed) with a point-at-infinity signature. */
  def sign(tx: Transaction, chainId: Option[BigInt]): SignedTransaction =
    val raw = rawFor(SignedTransaction.bytesToSign(tx, chainId))
    val parity = raw.v - BigInt(ECDSASignature.negativePointSign)
    val v = tx match
      case _: LegacyTransaction => chainId.fold(raw.v)(c => c * 2 + 35 + parity)
      case _                    => parity
    SignedTransaction(tx, raw.copy(v = v))
