package com.chipprbots.ethereum.crypto

import org.apache.pekko.util.ByteString

object ECDSASignatureImplicits {

  import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
  import com.chipprbots.ethereum.rlp.RLPImplicits.given
  import com.chipprbots.ethereum.rlp._

  implicit val ecdsaSignatureDec: RLPDecoder[ECDSASignature] = new RLPDecoder[ECDSASignature] {
    override def decode(rlp: RLPEncodeable): ECDSASignature = rlp match {
      case RLPList(RLPValue(r), RLPValue(s), RLPValue(v)) if v.nonEmpty =>
        ECDSASignature(ByteString(r), ByteString(s), v.head)
      case RLPList(RLPValue(r), RLPValue(s), RLPValue(v)) if v.isEmpty =>
        // Empty v component represents yParity=0 in EIP-2930 transaction RLP encoding
        // In RLP, the integer 0 is encoded as an empty byte string (0x80)
        ECDSASignature(ByteString(r), ByteString(s), 0.toByte)
      case RLPList(items @ _*) =>
        throw new RuntimeException(
          s"Cannot decode ECDSASignature: expected 3 RLPValue items (r, s, v), got ${items.length} items"
        )
      case other =>
        throw new RuntimeException(
          s"Cannot decode ECDSASignature: expected RLPList, got ${other.getClass.getSimpleName}"
        )
    }
  }

  implicit class ECDSASignatureEnc(ecdsaSignature: ECDSASignature) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable =
      RLPList(ecdsaSignature.r, ecdsaSignature.s, ecdsaSignature.v)
  }

  implicit val ECDSASignatureOrdering: Ordering[ECDSASignature] = Ordering.by(sig => (sig.r, sig.s, sig.v))
}
