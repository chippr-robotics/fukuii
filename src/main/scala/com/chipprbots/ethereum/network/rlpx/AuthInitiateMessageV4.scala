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
    def toAuthInitiateMessageV4: AuthInitiateMessageV4 = {
      // EIP-8 auth messages are transported inside an ECIES envelope and may contain random trailing
      // padding bytes after the RLP payload. Our RLP decoder expects to consume the entire byte array,
      // so we must decode only the first RLP element and ignore any trailing bytes.
      //
      // This is distinct from the EIP-8 requirement to ignore *extra list elements* inside the RLP list
      // (handled below via `items.length >= 4`).
      val rlpItemEnd = com.chipprbots.ethereum.rlp.nextElementIndex(bytes, 0)
      val rlpItem = rawDecode(bytes.take(rlpItemEnd))

      rlpItem match {
      // EIP-8: Accept messages with additional list elements beyond the required 4
      // Per EIP-8 spec, implementations MUST ignore unknown trailing elements
      // This matches go-ethereum's approach where authMsgV4 has a `Rest []rlp.RawValue` field
      // with `rlp:"tail"` tag to capture and ignore extra fields for forward-compatibility.
      // See: https://github.com/ethereum/go-ethereum/blob/master/p2p/rlpx/rlpx.go#L388-397
      case list: RLPList if list.items.length >= 4 =>
        // Extract only the first 4 required fields, ignoring any trailing fields
        (list.items(0), list.items(1), list.items(2), list.items(3)) match {
          case (RLPValue(signatureBytesArr), RLPValue(publicKeyBytesArr), RLPValue(nonceArr), RLPValue(versionArr)) =>
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
}

case class AuthInitiateMessageV4(signature: ECDSASignature, publicKey: ECPoint, nonce: ByteString, version: Int)
