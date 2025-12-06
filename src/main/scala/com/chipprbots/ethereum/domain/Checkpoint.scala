package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.rlp._

case class Checkpoint(signatures: Seq[ECDSASignature])

object Checkpoint {

  import com.chipprbots.ethereum.crypto.ECDSASignatureImplicits._

  implicit val checkpointRLPEncoder: RLPEncoder[Checkpoint] = { checkpoint =>
    RLPList(checkpoint.signatures.map(_.toRLPEncodable): _*)
  }

  implicit val checkpointRLPDecoder: RLPDecoder[Checkpoint] = {
    case signatures: RLPList =>
      Checkpoint(
        signatures.items.map(ecdsaSignatureDec.decode).toList
      )
    case _ => throw new RuntimeException("Cannot decode Checkpoint")
  }

  def empty: Checkpoint = Checkpoint(Nil)
}
