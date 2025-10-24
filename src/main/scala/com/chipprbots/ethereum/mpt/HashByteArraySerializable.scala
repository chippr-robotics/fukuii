package com.chipprbots.ethereum.mpt

import com.chipprbots.ethereum.crypto.kec256

case class HashByteArraySerializable[T](tSerializer: ByteArrayEncoder[T]) extends ByteArrayEncoder[T] {
  override def toBytes(input: T): Array[Byte] = kec256(tSerializer.toBytes(input))
}
