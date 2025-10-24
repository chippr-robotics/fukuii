package com.chipprbots.ethereum.utils

import com.chipprbots.ethereum.domain.UInt256

object BigIntExtensionMethods {
  implicit class BigIntAsUnsigned(val srcBigInteger: BigInt) extends AnyVal {
    def toUnsignedByteArray: Array[Byte] =
      ByteUtils.bigIntToUnsignedByteArray(srcBigInteger)

    def u256: UInt256 = UInt256(srcBigInteger)
  }
}
