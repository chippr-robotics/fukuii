package com.chipprbots.ethereum.rlp

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.rlp.RLP._
import com.chipprbots.ethereum.utils.ByteUtils

import RLPCodec.Ops

object RLPImplicits {

  given byteEncDec: (RLPEncoder[Byte] & RLPDecoder[Byte]) = new RLPEncoder[Byte] with RLPDecoder[Byte] {
    override def encode(obj: Byte): RLPValue = RLPValue(byteToByteArray(obj))

    override def decode(rlp: RLPEncodeable): Byte = rlp match {
      case RLPValue(bytes) =>
        val len = bytes.length

        if (len == 0) 0: Byte
        else if (len == 1) (bytes(0) & 0xff).toByte
        else throw RLPException("src doesn't represent a byte", rlp)

      case _ => throw RLPException("src is not an RLPValue", rlp)
    }
  }

  given shortEncDec: (RLPEncoder[Short] & RLPDecoder[Short]) = new RLPEncoder[Short] with RLPDecoder[Short] {
    override def encode(obj: Short): RLPValue = RLPValue(shortToBigEndianMinLength(obj))

    override def decode(rlp: RLPEncodeable): Short = rlp match {
      case RLPValue(bytes) =>
        val len = bytes.length

        if (len == 0) 0: Short
        else if (len == 1) (bytes(0) & 0xff).toShort
        else if (len == 2) (((bytes(0) & 0xff) << 8) + (bytes(1) & 0xff)).toShort
        else throw RLPException("src doesn't represent a short", rlp)

      case _ => throw RLPException("src is not an RLPValue", rlp)
    }
  }

  given intEncDec: (RLPEncoder[Int] & RLPDecoder[Int]) = new RLPEncoder[Int] with RLPDecoder[Int] {
    override def encode(obj: Int): RLPValue = RLPValue(intToBigEndianMinLength(obj))

    override def decode(rlp: RLPEncodeable): Int = rlp match {
      case RLPValue(bytes) => bigEndianMinLengthToInt(bytes)
      case _               => throw RLPException("src is not an RLPValue", rlp)
    }
  }

  // Used for decoding and encoding positive (or 0) BigInts
  given bigIntEncDec: (RLPEncoder[BigInt] & RLPDecoder[BigInt]) = new RLPEncoder[BigInt]
    with RLPDecoder[BigInt] {

    override def encode(obj: BigInt): RLPValue = RLPValue(
      if (obj.equals(BigInt(0))) byteToByteArray(0: Byte) else ByteUtils.bigIntToUnsignedByteArray(obj)
    )

    override def decode(rlp: RLPEncodeable): BigInt = rlp match {
      case RLPValue(bytes) =>
        bytes.foldLeft[BigInt](BigInt(0))((rec, byte) => (rec << (8: Int)) + BigInt(byte & 0xff))
      case _ => throw RLPException("src is not an RLPValue", rlp)
    }
  }

  // Used for decoding and encoding positive (or 0) longs
  given longEncDec: (RLPEncoder[Long] & RLPDecoder[Long]) = new RLPEncoder[Long] with RLPDecoder[Long] {
    override def encode(obj: Long): RLPEncodeable = bigIntEncDec.encode(BigInt(obj))

    override def decode(rlp: RLPEncodeable): Long = rlp match {
      case RLPValue(bytes) if bytes.length <= 8 => bigIntEncDec.decode(rlp).toLong
      case RLPValue(bytes) => throw RLPException(s"expected max 8 bytes for Long; got ${bytes.length}", rlp)
      case _               => throw RLPException(s"src is not an RLPValue", rlp)
    }
  }

  given stringEncDec: (RLPEncoder[String] & RLPDecoder[String]) = new RLPEncoder[String]
    with RLPDecoder[String] {
    override def encode(obj: String): RLPValue = RLPValue(obj.getBytes)

    override def decode(rlp: RLPEncodeable): String = rlp match {
      case RLPValue(bytes) => new String(bytes)
      case _               => throw RLPException("src is not an RLPValue", rlp)
    }
  }

  given byteArrayEncDec: (RLPEncoder[Array[Byte]] & RLPDecoder[Array[Byte]]) = new RLPEncoder[Array[Byte]]
    with RLPDecoder[Array[Byte]] {

    override def encode(obj: Array[Byte]): RLPValue = RLPValue(obj)

    override def decode(rlp: RLPEncodeable): Array[Byte] = rlp match {
      case RLPValue(bytes) => bytes
      case _               => throw RLPException("src is not an RLPValue", rlp)
    }
  }

  given byteStringEncDec: (RLPEncoder[ByteString] & RLPDecoder[ByteString]) = new RLPEncoder[ByteString]
    with RLPDecoder[ByteString] {
    override def encode(obj: ByteString): RLPEncodeable = byteArrayEncDec.encode(obj.toArray[Byte])

    override def decode(rlp: RLPEncodeable): ByteString = ByteString(byteArrayEncDec.decode(rlp))
  }

  given seqEncDec[T](using enc: RLPEncoder[T], dec: RLPDecoder[T]): (RLPEncoder[Seq[T]] & RLPDecoder[Seq[T]]) =
    new RLPEncoder[Seq[T]] with RLPDecoder[Seq[T]] {
      override def encode(obj: Seq[T]): RLPEncodeable = RLPList(obj.map(enc.encode): _*)

      override def decode(rlp: RLPEncodeable): Seq[T] = rlp match {
        case l: RLPList => l.items.map(dec.decode)
        case _          => throw RLPException("src is not a Seq", rlp)
      }
    }

  given listEncDec[T: RLPEncoder: RLPDecoder]: RLPCodec[List[T]] =
    seqEncDec[T].xmap(_.toList, _.toSeq)

  given optionEnc[T](using enc: RLPEncoder[T]): RLPEncoder[Option[T]] = {
    case None        => RLPList()
    case Some(value) => RLPList(enc.encode(value))
  }

  given optionDec[T](using dec: RLPDecoder[T]): RLPDecoder[Option[T]] = {
    case RLPList(value) => Some(dec.decode(value))
    case RLPList()      => None
    case rlp            => throw RLPException(s"${rlp} should be a list with 1 or 0 elements", rlp)
  }

  given booleanEncDec: (RLPEncoder[Boolean] & RLPDecoder[Boolean]) = new RLPEncoder[Boolean]
    with RLPDecoder[Boolean] {
    override def encode(obj: Boolean): RLPEncodeable = {
      val intRepresentation: Int = if (obj) 1 else 0
      intEncDec.encode(intRepresentation)
    }

    override def decode(rlp: RLPEncodeable): Boolean = {
      val intRepresentation = intEncDec.decode(rlp)

      if (intRepresentation == 1) true
      else if (intRepresentation == 0) false
      else throw RLPException(s"$rlp should be 1 or 0", rlp)
    }
  }

  given tuple2Codec[A: RLPCodec, B: RLPCodec]: RLPCodec[(A, B)] =
    RLPCodec.instance[(A, B)](
      { case (a, b) =>
        RLPList(RLPEncoder.encode(a), RLPEncoder.encode(b))
      },
      { case RLPList(a, b, _*) =>
        (RLPDecoder.decode[A](a), RLPDecoder.decode[B](b))
      }
    )

  given tuple3Codec[A: RLPCodec, B: RLPCodec, C: RLPCodec]: RLPCodec[(A, B, C)] =
    RLPCodec.instance[(A, B, C)](
      { case (a, b, c) =>
        RLPList(RLPEncoder.encode(a), RLPEncoder.encode(b), RLPEncoder.encode(c))
      },
      { case RLPList(a, b, c, _*) =>
        (RLPDecoder.decode[A](a), RLPDecoder.decode[B](b), RLPDecoder.decode[C](c))
      }
    )

  given tuple4Codec[A: RLPCodec, B: RLPCodec, C: RLPCodec, D: RLPCodec]: RLPCodec[(A, B, C, D)] =
    RLPCodec.instance[(A, B, C, D)](
      { case (a, b, c, d) =>
        RLPList(RLPEncoder.encode(a), RLPEncoder.encode(b), RLPEncoder.encode(c), RLPEncoder.encode(d))
      },
      { case RLPList(a, b, c, d, _*) =>
        (RLPDecoder.decode[A](a), RLPDecoder.decode[B](b), RLPDecoder.decode[C](c), RLPDecoder.decode[D](d))
      }
    )

  given tuple5Codec[A: RLPCodec, B: RLPCodec, C: RLPCodec, D: RLPCodec, E: RLPCodec]: RLPCodec[(A, B, C, D, E)] =
    RLPCodec.instance[(A, B, C, D, E)](
      { case (a, b, c, d, e) =>
        RLPList(
          RLPEncoder.encode(a),
          RLPEncoder.encode(b),
          RLPEncoder.encode(c),
          RLPEncoder.encode(d),
          RLPEncoder.encode(e)
        )
      },
      { case RLPList(a, b, c, d, e, _*) =>
        (
          RLPDecoder.decode[A](a),
          RLPDecoder.decode[B](b),
          RLPDecoder.decode[C](c),
          RLPDecoder.decode[D](d),
          RLPDecoder.decode[E](e)
        )
      }
    )

}
