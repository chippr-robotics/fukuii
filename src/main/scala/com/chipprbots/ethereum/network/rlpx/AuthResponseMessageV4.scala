package com.chipprbots.ethereum.network.rlpx

import akka.util.ByteString

import org.bouncycastle.math.ec.ECPoint

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.rlp.RLPDecoder
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPEncoder
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPList

object AuthResponseMessageV4 {

  implicit val rlpEncDec: RLPEncoder[AuthResponseMessageV4] with RLPDecoder[AuthResponseMessageV4] =
    new RLPEncoder[AuthResponseMessageV4] with RLPDecoder[AuthResponseMessageV4] {
      override def encode(obj: AuthResponseMessageV4): RLPEncodeable = {
        import obj._
        // byte 0 of encoded ECC point indicates that it is uncompressed point, it is part of bouncycastle encoding
        RLPList(ephemeralPublicKey.getEncoded(false).drop(1), nonce.toArray[Byte], version)
      }

      override def decode(rlp: RLPEncodeable): AuthResponseMessageV4 = rlp match {
        case RLPList(ephemeralPublicKeyBytes, nonce, version, _*) =>
          val ephemeralPublicKey =
            curve.getCurve.decodePoint(ECDSASignature.UncompressedIndicator +: (ephemeralPublicKeyBytes: Array[Byte]))
          AuthResponseMessageV4(ephemeralPublicKey, ByteString(nonce: Array[Byte]), version)
        case _ => throw new RuntimeException("Cannot decode auth response message")
      }
    }
}

case class AuthResponseMessageV4(ephemeralPublicKey: ECPoint, nonce: ByteString, version: Int)
