package com.chipprbots.ethereum.rlp

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.rlp.RLP._

object UInt256RLPImplicits {

  implicit class UInt256Enc(obj: UInt256) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable =
      RLPValue(if (obj.equals(UInt256.Zero)) Array.emptyByteArray else obj.bytes.dropWhile(_ == 0).toArray[Byte])
  }

  implicit class UInt256Dec(val bytes: ByteString) extends AnyVal {
    def toUInt256: UInt256 = UInt256RLPEncodableDec(rawDecode(bytes.toArray)).toUInt256
  }

  implicit class UInt256RLPEncodableDec(val rLPEncodeable: RLPEncodeable) extends AnyVal {
    def toUInt256: UInt256 = rLPEncodeable match {
      case RLPValue(b) => UInt256(b)
      case _           => throw RLPException("src is not an RLPValue")
    }
  }

}
