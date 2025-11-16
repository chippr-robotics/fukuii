package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.math.Ordering.Implicits._

object ByteStringUtils {
  def hash2string(hash: ByteString): String =
    Hex.toHexString(hash.toArray[Byte])

  // unsafe
  def string2hash(hash: String): ByteString =
    ByteString(Hex.decode(hash))

  /** Converts a Long to ByteString (big-endian, 8 bytes). */
  def longToByteString(l: Long): ByteString =
    ByteString(
      Array(
        ((l >> 56) & 0xff).toByte,
        ((l >> 48) & 0xff).toByte,
        ((l >> 40) & 0xff).toByte,
        ((l >> 32) & 0xff).toByte,
        ((l >> 24) & 0xff).toByte,
        ((l >> 16) & 0xff).toByte,
        ((l >> 8) & 0xff).toByte,
        (l & 0xff).toByte
      )
    )

  /** Converts a ByteString to Long (big-endian, expects 8 bytes). */
  def byteStringToLong(bs: ByteString): Long = {
    val bytes = bs.toArray
    require(bytes.length == 8, s"Expected 8 bytes for Long conversion, got ${bytes.length}")
    ((bytes(0) & 0xffL) << 56) |
      ((bytes(1) & 0xffL) << 48) |
      ((bytes(2) & 0xffL) << 40) |
      ((bytes(3) & 0xffL) << 32) |
      ((bytes(4) & 0xffL) << 24) |
      ((bytes(5) & 0xffL) << 16) |
      ((bytes(6) & 0xffL) << 8) |
      (bytes(7) & 0xffL)
  }

  implicit class Padding(val bs: ByteString) extends AnyVal {
    def padToByteString(length: Int, b: Byte): ByteString =
      if (length <= bs.length) bs
      else {
        val len = Math.max(bs.length, length)
        val result = new Array[Byte](len)
        bs.copyToArray(result, 0)
        var i = bs.length
        while (i < len) {
          result.update(i, b)
          i += 1
        }
        ByteString.fromArray(result)
      }
  }

  implicit class ByteStringOps(val bytes: ByteString) extends AnyVal {
    def toHex: String = Hex.toHexString(bytes.toArray[Byte])
  }

  sealed trait ByteStringElement {
    def len: Int
    def asByteArray: Array[Byte]
  }

  implicit class ByteStringSelfElement(val bs: ByteString) extends ByteStringElement {
    def len: Int = bs.length
    def asByteArray: Array[Byte] = bs.toArray
  }

  implicit class ByteStringArrayElement(val ar: Array[Byte]) extends ByteStringElement {
    def len: Int = ar.length
    def asByteArray: Array[Byte] = ar
  }

  implicit class ByteStringByteElement(val b: Byte) extends ByteStringElement {
    def len: Int = 1
    def asByteArray: Array[Byte] = Array(b)
  }

  implicit val byteStringOrdering: Ordering[ByteString] =
    Ordering.by[ByteString, Seq[Byte]](_.toSeq)

  def concatByteStrings(head: ByteStringElement, tail: ByteStringElement*): ByteString = {
    val it = Iterator.single(head) ++ tail.iterator
    concatByteStrings(it)
  }

  def concatByteStrings(elements: Iterator[ByteStringElement]): ByteString = {
    val builder = new mutable.ArrayBuilder.ofByte
    elements.foreach(el => builder ++= el.asByteArray)
    ByteString(builder.result())
  }

}
