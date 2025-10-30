package com.chipprbots.ethereum.crypto

import org.apache.pekko.util.ByteString

object ECDSASignatureImplicits {

  import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
  import com.chipprbots.ethereum.rlp.RLPImplicits._
  import com.chipprbots.ethereum.rlp._

  implicit val ecdsaSignatureDec: RLPDecoder[ECDSASignature] = new RLPDecoder[ECDSASignature] {
    override def decode(rlp: RLPEncodeable): ECDSASignature = rlp match {
      case RLPList(r, s, v) => ECDSASignature(r: ByteString, s: ByteString, v)
      case _                => throw new RuntimeException("Cannot decode ECDSASignature")
    }
  }

  implicit class ECDSASignatureEnc(ecdsaSignature: ECDSASignature) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable =
      RLPList(ecdsaSignature.r, ecdsaSignature.s, ecdsaSignature.v)
  }

  implicit val ECDSASignatureOrdering: Ordering[ECDSASignature] = Ordering.by(sig => (sig.r, sig.s, sig.v))
}
