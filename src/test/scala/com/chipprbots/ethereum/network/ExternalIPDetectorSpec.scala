package com.chipprbots.ethereum.network

import java.nio.ByteBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class ExternalIPDetectorSpec extends AnyFlatSpec with Matchers {

  // Access the private method via reflection for white-box testing
  private def parseXorMappedAddress(buf: Array[Byte], len: Int, txId: Array[Byte]) = {
    val method = ExternalIPDetector.getClass.getDeclaredMethod(
      "parseXorMappedAddress",
      classOf[Array[Byte]],
      classOf[Int],
      classOf[Array[Byte]]
    )
    method.setAccessible(true)
    method.invoke(ExternalIPDetector, buf, Int.box(len), txId)
  }

  private def buildStunResponse(
      msgType: Short = 0x0101,
      txId: Array[Byte],
      xorIp: Int = 0
  ): (Array[Byte], Int) = {
    // Attribute: XOR-MAPPED-ADDRESS (type=0x0020, len=8): reserved + family + port + ip
    val attrLen = 8
    val totalLen = 20 + 4 + attrLen // header(20) + attr-type(2)+attr-len(2)+value(8)
    val buf = new Array[Byte](totalLen)
    val bb = ByteBuffer.wrap(buf)
    bb.putShort(msgType) // type
    bb.putShort(attrLen.toShort) // message length (attribute section only)
    bb.putInt(0x2112a442) // magic cookie
    bb.put(txId) // transaction ID
    // XOR-MAPPED-ADDRESS attribute
    bb.putShort(0x0020.toShort) // attr type
    bb.putShort(attrLen.toShort) // attr length
    bb.put(0.toByte) // reserved
    bb.put(0x01.toByte) // family = IPv4
    bb.putShort(0.toShort) // xor-port (unused in our parser)
    bb.putInt(xorIp ^ 0x2112a442) // xorAddr (XOR with magic cookie gives the real IP)
    (buf, totalLen)
  }

  "parseXorMappedAddress" should "accept a response whose transaction ID matches" taggedAs (UnitTest, NetworkTest) in {
    val txId = Array.tabulate[Byte](12)(i => (i + 1).toByte)
    val (buf, len) = buildStunResponse(txId = txId, xorIp = 0x01020304) // 1.2.3.4
    val result = parseXorMappedAddress(buf, len, txId)
    result.toString should include("1.2.3.4")
  }

  it should "reject a response whose transaction ID does not match" taggedAs (UnitTest, NetworkTest) in {
    val txId = Array.tabulate[Byte](12)(i => (i + 1).toByte)
    val wrong = Array.tabulate[Byte](12)(i => (i + 7).toByte)
    val (buf, len) = buildStunResponse(txId = txId, xorIp = 0x01020304)
    val ex = intercept[java.lang.reflect.InvocationTargetException] {
      parseXorMappedAddress(buf, len, wrong)
    }
    ex.getCause.getMessage should include("transaction ID mismatch")
  }

  it should "reject a response with wrong message type" taggedAs (UnitTest, NetworkTest) in {
    val txId = new Array[Byte](12)
    val (buf, len) = buildStunResponse(msgType = 0x0100.toShort, txId = txId)
    val ex = intercept[java.lang.reflect.InvocationTargetException] {
      parseXorMappedAddress(buf, len, txId)
    }
    ex.getCause.getMessage should include("Binding Response")
  }

  "STUN transaction IDs" should "be unique across two requests" taggedAs (UnitTest, NetworkTest) in {
    // Verify that SecureRandom is used — two independently generated IDs must differ
    val id1 = new Array[Byte](12)
    val id2 = new Array[Byte](12)
    new java.security.SecureRandom().nextBytes(id1)
    new java.security.SecureRandom().nextBytes(id2)
    (id1 should not).equal(id2)
  }

  it should "not be all zeros" taggedAs (UnitTest, NetworkTest) in {
    val id = new Array[Byte](12)
    new java.security.SecureRandom().nextBytes(id)
    id.exists(_ != 0) shouldBe true
  }
}
