package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.rlp.{RLPList, RLPValue, rawDecode}

class SNAPMessagesSpec extends AnyWordSpec with Matchers {

  "SNAP/1" should {

    "encode GetAccountRange requestId canonically (no leading zeros)" in {
      val msg = SNAP.GetAccountRange(
        requestId = BigInt(128),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        startingHash = ByteString(Array.fill(32)(0x22.toByte)),
        limitHash = ByteString(Array.fill(32)(0x33.toByte)),
        responseBytes = BigInt(1024)
      )

      import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
      val encoded = msg.toBytes

      rawDecode(encoded) match {
        case RLPList(RLPValue(requestIdBytes), _*) =>
          requestIdBytes shouldBe Array(0x80.toByte)
        case _ => fail("Expected RLPList with request-id as first element")
      }
    }

    "encode GetAccountRange requestId=0 as empty bytes per RLP spec" in {
      val msg = SNAP.GetAccountRange(
        requestId = BigInt(0),
        rootHash = ByteString(Array.fill(32)(0x11.toByte)),
        startingHash = ByteString(Array.fill(32)(0x22.toByte)),
        limitHash = ByteString(Array.fill(32)(0x33.toByte)),
        responseBytes = BigInt(1024)
      )

      import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
      val encoded = msg.toBytes

      rawDecode(encoded) match {
        case RLPList(RLPValue(requestIdBytes), _*) =>
          requestIdBytes shouldBe empty
        case _ => fail("Expected RLPList with request-id as first element")
      }
    }
  }
}
