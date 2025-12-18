package com.chipprbots.ethereum.network.rlpx

import org.apache.pekko.util.ByteString

import org.bouncycastle.math.ec.ECPoint

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.rlp._

object AuthInitiateMessageV4 extends AuthInitiateEcdsaCodec {

  implicit class AuthInitiateMessageV4Enc(obj: AuthInitiateMessageV4) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = {
      import obj._
      // byte 0 of encoded ECC point indicates that it is uncompressed point, it is part of bouncycastle encoding
      RLPList(
        RLPValue(encodeECDSA(signature).toArray),
        RLPValue(publicKey.getEncoded(false).drop(1)),
        RLPValue(nonce.toArray),
        RLPValue(Array(version.toByte))
      )
    }
  }

  implicit class AuthInitiateMessageV4Dec(val bytes: Array[Byte]) extends AnyVal {
    def toAuthInitiateMessageV4: AuthInitiateMessageV4 = rawDecode(bytes) match {
      // EIP-8: Accept messages with additional list elements beyond the required 4
      // Per EIP-8 spec, implementations MUST ignore unknown trailing elements
      case list: RLPList if list.items.length >= 4 =>
        list.items.take(4).toList match {
          case RLPValue(signatureBytesArr) :: RLPValue(publicKeyBytesArr) :: RLPValue(nonceArr) :: RLPValue(versionArr) :: Nil =>
            val signature = decodeECDSA(signatureBytesArr)
            val publicKey =
              curve.getCurve.decodePoint(ECDSASignature.UncompressedIndicator +: publicKeyBytesArr)
            val version = BigInt(versionArr).toInt
            AuthInitiateMessageV4(signature, publicKey, ByteString(nonceArr), version)
          case _ => throw new RuntimeException("Cannot decode auth initiate message: invalid field types")
        }
      case _ => throw new RuntimeException("Cannot decode auth initiate message: expected RLPList with at least 4 elements")
    }
  }
}

case class AuthInitiateMessageV4(signature: ECDSASignature, publicKey: ECPoint, nonce: ByteString, version: Int)
