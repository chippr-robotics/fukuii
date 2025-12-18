package com.chipprbots.ethereum.network.rlpx

import org.apache.pekko.util.ByteString

import org.bouncycastle.math.ec.ECPoint

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.rlp.RLPDecoder
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPEncoder
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

object AuthResponseMessageV4 {

  implicit val rlpEncDec: RLPEncoder[AuthResponseMessageV4] with RLPDecoder[AuthResponseMessageV4] =
    new RLPEncoder[AuthResponseMessageV4] with RLPDecoder[AuthResponseMessageV4] {
      override def encode(obj: AuthResponseMessageV4): RLPEncodeable = {
        import obj._
        // byte 0 of encoded ECC point indicates that it is uncompressed point, it is part of bouncycastle encoding
        RLPList(ephemeralPublicKey.getEncoded(false).drop(1), nonce.toArray[Byte], version)
      }

      override def decode(rlp: RLPEncodeable): AuthResponseMessageV4 = rlp match {
        // EIP-8: Accept messages with additional list elements beyond the required 3
        // Per EIP-8 spec, implementations MUST ignore unknown trailing elements
        // This matches go-ethereum's approach where authRespV4 has a `Rest []rlp.RawValue` field
        // with `rlp:"tail"` tag to capture and ignore extra fields for forward-compatibility.
        // See: https://github.com/ethereum/go-ethereum/blob/master/p2p/rlpx/rlpx.go#L400-408
        case list: RLPList if list.items.length >= 3 =>
          // Extract only the first 3 required fields, ignoring any trailing fields
          (list.items(0), list.items(1), list.items(2)) match {
            case (RLPValue(ephemeralPublicKeyBytesArr), RLPValue(nonceArr), RLPValue(versionArr)) =>
              val ephemeralPublicKey =
                curve.getCurve.decodePoint(ECDSASignature.UncompressedIndicator +: ephemeralPublicKeyBytesArr)
              val version = BigInt(versionArr).toInt
              AuthResponseMessageV4(ephemeralPublicKey, ByteString(nonceArr), version)
            case _ => throw new RuntimeException("Cannot decode auth response message: invalid field types")
          }
        case _ => throw new RuntimeException("Cannot decode auth response message: expected RLPList with at least 3 elements")
      }
    }
}

case class AuthResponseMessageV4(ephemeralPublicKey: ECPoint, nonce: ByteString, version: Int)
