package com.chipprbots.ethereum.rlp

import org.scalatest.funsuite.AnyFunSuite

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.UInt256RLPImplicits._

class UInt256RlpEncodingSpec extends AnyFunSuite {

  test("UInt256.Zero RLP encodes as empty (0x80)") {
    val encoded = encode(UInt256.Zero.toRLPEncodable)
    assert(encoded.sameElements(Array(0x80.toByte)))
  }

  test("UInt256.One RLP encodes as single byte (0x01)") {
    val encoded = encode(UInt256.One.toRLPEncodable)
    assert(encoded.sameElements(Array(0x01.toByte)))
  }

  test("CREATE address for nonce=0 matches Core-Geth") {
    val sender = Address("0xf7f04e1052c6a30f651b07bb8f6bedf4844137f5")
    val encoded = encode(RLPList(sender.bytes, UInt256.Zero.toRLPEncodable))
    val created = Address(ByteString(kec256(encoded)))

    assert(created == Address("0x2fae8af94cdc68452042ccfc2abf25a9120fdb2e"))
  }
}
