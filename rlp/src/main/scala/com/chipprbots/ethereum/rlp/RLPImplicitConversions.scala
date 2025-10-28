package com.chipprbots.ethereum.rlp

import akka.util.ByteString
import scala.language.implicitConversions

import com.chipprbots.ethereum.rlp.RLPImplicits._

object RLPImplicitConversions {

  implicit def toEncodeable[T](value: T)(implicit enc: RLPEncoder[T]): RLPEncodeable = enc.encode(value)

  def fromEncodeable[T](value: RLPEncodeable)(implicit dec: RLPDecoder[T]): T = dec.decode(value)

  implicit def fromOptionalEncodeable[T: RLPDecoder]: (Option[RLPEncodeable]) => Option[T] = _.map(fromEncodeable[T])

  implicit def toRlpList[T](values: Seq[T])(implicit enc: RLPEncoder[T]): RLPList =
    RLPList(values.map(v => toEncodeable[T](v)): _*)

  def fromRlpList[T](rlpList: RLPList)(implicit dec: RLPDecoder[T]): Seq[T] =
    rlpList.items.map(dec.decode)

  implicit def byteStringToEncodeable: (ByteString) => RLPEncodeable = toEncodeable[ByteString]

  // Scala 3 conversions using Conversion instances
  given byteConv: Conversion[RLPEncodeable, Byte] with {
    def apply(x: RLPEncodeable): Byte = fromEncodeable[Byte](x)
  }

  given shortConv: Conversion[RLPEncodeable, Short] with {
    def apply(x: RLPEncodeable): Short = fromEncodeable[Short](x)
  }

  given intConv: Conversion[RLPEncodeable, Int] with {
    def apply(x: RLPEncodeable): Int = fromEncodeable[Int](x)
  }

  given bigIntConv: Conversion[RLPEncodeable, BigInt] with {
    def apply(x: RLPEncodeable): BigInt = fromEncodeable[BigInt](x)
  }

  given byteStringConv: Conversion[RLPEncodeable, ByteString] with {
    def apply(x: RLPEncodeable): ByteString = fromEncodeable[ByteString](x)
  }

  given longConv: Conversion[RLPEncodeable, Long] with {
    def apply(x: RLPEncodeable): Long = fromEncodeable[Long](x)
  }

  given stringConv: Conversion[RLPEncodeable, String] with {
    def apply(x: RLPEncodeable): String = fromEncodeable[String](x)
  }

  given byteArrayConv: Conversion[RLPEncodeable, Array[Byte]] with {
    def apply(x: RLPEncodeable): Array[Byte] = fromEncodeable[Array[Byte]](x)
  }

}
