package com.chipprbots.ethereum.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

import scala.util.Try

/** EIP-7951: P-256 (secp256r1) signature verification using JDK's java.security API.
  */
object Secp256r1 {

  private lazy val ecParams: ECParameterSpec = {
    val params = AlgorithmParameters.getInstance("EC")
    params.init(new ECGenParameterSpec("secp256r1"))
    params.getParameterSpec(classOf[ECParameterSpec])
  }

  /** Verify a P-256 ECDSA signature.
    *
    * @param hash
    *   32-byte message hash
    * @param r
    *   32-byte r component of signature
    * @param s
    *   32-byte s component of signature
    * @param x
    *   32-byte x coordinate of public key
    * @param y
    *   32-byte y coordinate of public key
    * @return
    *   true if the signature is valid
    */
  def verify(hash: Array[Byte], r: Array[Byte], s: Array[Byte], x: Array[Byte], y: Array[Byte]): Boolean =
    Try {
      val pubX = new BigInteger(1, x)
      val pubY = new BigInteger(1, y)
      val ecPoint = new ECPoint(pubX, pubY)
      val pubKeySpec = new ECPublicKeySpec(ecPoint, ecParams)
      val keyFactory = KeyFactory.getInstance("EC")
      val publicKey = keyFactory.generatePublic(pubKeySpec)

      // Convert r, s to DER-encoded signature for JCA
      val derSig = toDerSignature(new BigInteger(1, r), new BigInteger(1, s))

      val sig = Signature.getInstance("NONEwithECDSA")
      sig.initVerify(publicKey)
      sig.update(hash)
      sig.verify(derSig)
    }.getOrElse(false)

  /** Encode r, s as DER-encoded ECDSA signature */
  private def toDerSignature(r: BigInteger, s: BigInteger): Array[Byte] = {
    val rBytes = toUnsignedByteArray(r)
    val sBytes = toUnsignedByteArray(s)

    // DER encoding: 0x30 [total-length] 0x02 [r-length] [r] 0x02 [s-length] [s]
    val totalLength = 2 + rBytes.length + 2 + sBytes.length
    val der = new Array[Byte](2 + totalLength)
    var offset = 0
    der(offset) = 0x30; offset += 1
    der(offset) = totalLength.toByte; offset += 1
    der(offset) = 0x02; offset += 1
    der(offset) = rBytes.length.toByte; offset += 1
    System.arraycopy(rBytes, 0, der, offset, rBytes.length); offset += rBytes.length
    der(offset) = 0x02; offset += 1
    der(offset) = sBytes.length.toByte; offset += 1
    System.arraycopy(sBytes, 0, der, offset, sBytes.length)
    der
  }

  /** Convert BigInteger to minimal unsigned byte array (with leading 0 if high bit set) */
  private def toUnsignedByteArray(value: BigInteger): Array[Byte] = {
    val bytes = value.toByteArray
    if (bytes.length > 1 && bytes(0) == 0 && (bytes(1) & 0x80) == 0) {
      // Strip unnecessary leading zero
      bytes.drop(1)
    } else {
      bytes
    }
  }
}
