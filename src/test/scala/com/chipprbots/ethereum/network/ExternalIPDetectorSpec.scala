package com.chipprbots.ethereum.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

class ExternalIPDetectorSpec extends AnyFlatSpec with Matchers:

  private def v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
    InetAddress.getByAddress(Array(a.toByte, b.toByte, c.toByte, d.toByte))

  // ---------------------------------------------------------------------
  // isPublicIPv4
  // ---------------------------------------------------------------------

  private val blockedBoundaries: List[(String, InetAddress, InetAddress)] = List(
    ("0.0.0.0/8", v4(0, 0, 0, 0), v4(0, 255, 255, 255)),
    ("10.0.0.0/8", v4(10, 0, 0, 0), v4(10, 255, 255, 255)),
    ("100.64.0.0/10", v4(100, 64, 0, 0), v4(100, 127, 255, 255)),
    ("127.0.0.0/8", v4(127, 0, 0, 0), v4(127, 255, 255, 255)),
    ("169.254.0.0/16", v4(169, 254, 0, 0), v4(169, 254, 255, 255)),
    ("172.16.0.0/12", v4(172, 16, 0, 0), v4(172, 31, 255, 255)),
    ("192.0.0.0/24", v4(192, 0, 0, 0), v4(192, 0, 0, 255)),
    ("192.0.2.0/24", v4(192, 0, 2, 0), v4(192, 0, 2, 255)),
    ("192.168.0.0/16", v4(192, 168, 0, 0), v4(192, 168, 255, 255)),
    ("198.18.0.0/15", v4(198, 18, 0, 0), v4(198, 19, 255, 255)),
    ("198.51.100.0/24", v4(198, 51, 100, 0), v4(198, 51, 100, 255)),
    ("203.0.113.0/24", v4(203, 0, 113, 0), v4(203, 0, 113, 255)),
    ("224.0.0.0/4", v4(224, 0, 0, 0), v4(239, 255, 255, 255)),
    ("240.0.0.0/4", v4(240, 0, 0, 0), v4(255, 255, 255, 255))
  )

  "isPublicIPv4" should "reject the first and last address of every blocked range" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    blockedBoundaries.foreach { case (name, first, last) =>
      withClue(s"$name first address $first: ")(ExternalIPDetector.isPublicIPv4(first) shouldBe false)
      withClue(s"$name last address $last: ")(ExternalIPDetector.isPublicIPv4(last) shouldBe false)
    }
  }

  it should "accept addresses immediately outside each blocked range" taggedAs (UnitTest, NetworkTest) in {
    val neighbours = List(
      v4(9, 255, 255, 255),
      v4(11, 0, 0, 0),
      v4(100, 63, 255, 255),
      v4(100, 128, 0, 0),
      v4(172, 15, 255, 255),
      v4(172, 32, 0, 0),
      v4(8, 8, 8, 8),
      v4(1, 1, 1, 1)
    )
    neighbours.foreach(addr => withClue(s"$addr: ")(ExternalIPDetector.isPublicIPv4(addr) shouldBe true))
  }

  it should "reject an IPv6 address" taggedAs (UnitTest, NetworkTest) in {
    val ipv6 = InetAddress.getByAddress(Array.fill[Byte](16)(0)) // ::
    ExternalIPDetector.isPublicIPv4(ipv6) shouldBe false
  }

  // ---------------------------------------------------------------------
  // parseIpv4Literal
  // ---------------------------------------------------------------------

  "parseIpv4Literal" should "accept a plain dotted-decimal literal" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("8.8.8.8").map(_.getHostAddress) shouldBe Some("8.8.8.8")
  }

  it should "accept a literal with surrounding whitespace" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal(" 8.8.8.8\n").map(_.getHostAddress) shouldBe Some("8.8.8.8")
  }

  it should "reject a hostname" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("example.com") shouldBe None
  }

  it should "reject a three-octet literal" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("8.8.8") shouldBe None
  }

  it should "reject a five-octet literal" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("8.8.8.8.8") shouldBe None
  }

  it should "reject an octet greater than 255" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("256.0.0.1") shouldBe None
  }

  it should "reject a leading zero in an octet" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("01.2.3.4") shouldBe None
  }

  it should "reject trailing garbage after the literal" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("8.8.8.8 x") shouldBe None
  }

  it should "reject an IPv6 literal" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("::1") shouldBe None
  }

  it should "reject an empty string" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("") shouldBe None
  }

  it should "reject a 10 KB string without scanning past the first few bytes" taggedAs (UnitTest, NetworkTest) in {
    ExternalIPDetector.parseIpv4Literal("1" * 10240) shouldBe None
  }

  // ---------------------------------------------------------------------
  // STUN reply parser (parseXorMappedAddress) — pins current bounds-checking behaviour
  // ---------------------------------------------------------------------

  private def buildStunResponse(
      msgType: Short = 0x0101,
      txId: Array[Byte],
      xorIp: Int = 0,
      magicCookie: Int = 0x2112a442,
      attrLenOverride: Option[Int] = None,
      family: Byte = 0x01
  ): (Array[Byte], Int) =
    val attrValueLen = 8 // reserved(1)+family(1)+port(2)+addr(4) — always write a full value
    val declaredAttrLen = attrLenOverride.getOrElse(attrValueLen)
    // RFC 5389 §6: Message Length counts everything after the 20-byte header, i.e. the attribute's own
    // 4-byte type+length header PLUS its value — not just the value.
    val msgLen = 4 + attrValueLen
    val totalLen = 20 + msgLen
    val buf = new Array[Byte](totalLen)
    val bb = ByteBuffer.wrap(buf)
    bb.putShort(msgType) // type
    bb.putShort(msgLen.toShort) // declared message length (correct, unless the test wants a bad attrLen)
    bb.putInt(magicCookie)
    bb.put(txId)
    // XOR-MAPPED-ADDRESS attribute
    bb.putShort(0x0020.toShort) // attr type
    bb.putShort(declaredAttrLen.toShort) // declared attr length
    bb.put(0.toByte) // reserved
    bb.put(family) // family
    bb.putShort(0.toShort) // xor-port (unused in our parser)
    bb.putInt(xorIp ^ 0x2112a442) // xorAddr (XOR with the real magic cookie regardless of what's declared)
    (buf, totalLen)

  "parseXorMappedAddress" should "accept a well-formed response whose transaction ID matches" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val txId = Array.tabulate[Byte](12)(i => (i + 1).toByte)
    val (buf, len) = buildStunResponse(txId = txId, xorIp = 0x01020304) // 1.2.3.4
    ExternalIPDetector.parseXorMappedAddress(buf, len, txId).getHostAddress shouldBe "1.2.3.4"
  }

  it should "reject a response whose transaction ID does not match" taggedAs (UnitTest, NetworkTest) in {
    val txId = Array.tabulate[Byte](12)(i => (i + 1).toByte)
    val wrong = Array.tabulate[Byte](12)(i => (i + 7).toByte)
    val (buf, len) = buildStunResponse(txId = txId, xorIp = 0x01020304)
    (the[IllegalStateException] thrownBy {
      ExternalIPDetector.parseXorMappedAddress(buf, len, wrong)
    } should have).message("STUN response transaction ID mismatch — possible spoofing or server reuse")
  }

  it should "reject a wrong magic cookie" taggedAs (UnitTest, NetworkTest) in {
    val txId = new Array[Byte](12)
    val (buf, len) = buildStunResponse(txId = txId, magicCookie = 0xdeadbeef)
    (the[IllegalStateException] thrownBy {
      ExternalIPDetector.parseXorMappedAddress(buf, len, txId)
    } should have).message("STUN response: unexpected magic cookie")
  }

  it should "reject a response with the wrong message type" taggedAs (UnitTest, NetworkTest) in {
    val txId = new Array[Byte](12)
    val (buf, len) = buildStunResponse(msgType = 0x0100.toShort, txId = txId)
    intercept[IllegalStateException] {
      ExternalIPDetector.parseXorMappedAddress(buf, len, txId)
    }.getMessage should include("Binding Response")
  }

  it should "reject a truncated header" taggedAs (UnitTest, NetworkTest) in {
    val txId = new Array[Byte](12)
    val (buf, _) = buildStunResponse(txId = txId)
    (the[IllegalStateException] thrownBy {
      ExternalIPDetector.parseXorMappedAddress(buf, 10, txId)
    } should have).message("STUN response truncated: header requires 20 bytes")
  }

  it should "reject an attribute length that runs past the declared message end" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val txId = new Array[Byte](12)
    // Declare a message/attribute length far larger than what actually fits in the buffer.
    val (buf, len) = buildStunResponse(txId = txId, attrLenOverride = Some(200))
    intercept[IllegalStateException] {
      ExternalIPDetector.parseXorMappedAddress(buf, len, txId)
    }.getMessage should include("exceeds")
  }

  it should "reject an IPv6-family XOR-MAPPED-ADDRESS" taggedAs (UnitTest, NetworkTest) in {
    val txId = new Array[Byte](12)
    val (buf, len) = buildStunResponse(txId = txId, family = 0x02)
    (the[IllegalStateException] thrownBy {
      ExternalIPDetector.parseXorMappedAddress(buf, len, txId)
    } should have).message("STUN response contained no XOR-MAPPED-ADDRESS for IPv4")
  }

  // ---------------------------------------------------------------------
  // STUN transaction ID generation (unchanged behaviour, kept as a regression guard)
  // ---------------------------------------------------------------------

  "STUN transaction IDs" should "be unique across two requests" taggedAs (UnitTest, NetworkTest) in {
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

  // ---------------------------------------------------------------------
  // tryStun end-to-end against a fake STUN server on loopback (no real network)
  // ---------------------------------------------------------------------

  /** Starts a one-shot UDP server on 127.0.0.1 that replies to the first datagram it receives with a STUN response
    * echoing the caller's transaction ID (unless `txIdOverride` is supplied, to simulate a spoofed/mismatched reply)
    * and the given mapped IP. Returns the bound port.
    */
  private def withFakeStunServer(mappedIp: InetAddress, txIdOverride: Option[Array[Byte]] = None)(
      test: Int => Unit
  ): Unit =
    val serverSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val serverPort = serverSocket.getLocalPort
    val ipBytes = mappedIp.getAddress
    val xorIp =
      ((ipBytes(0) & 0xff) << 24) | ((ipBytes(1) & 0xff) << 16) | ((ipBytes(2) & 0xff) << 8) | (ipBytes(3) & 0xff)
    val serverThread = new Thread(() =>
      try
        val reqBuf = new Array[Byte](1024)
        val reqPacket = new DatagramPacket(reqBuf, reqBuf.length)
        serverSocket.receive(reqPacket)
        val clientTxId = java.util.Arrays.copyOfRange(reqBuf, 8, 20)
        val replyTxId = txIdOverride.getOrElse(clientTxId)
        val (respBuf, respLen) = buildStunResponse(txId = replyTxId, xorIp = xorIp)
        serverSocket.send(new DatagramPacket(respBuf, respLen, reqPacket.getAddress, reqPacket.getPort))
      catch case _: Throwable => ()
    )
    serverThread.setDaemon(true)
    try
      serverThread.start()
      test(serverPort)
    finally
      serverThread.join(2000)
      serverSocket.close()

  "tryStun" should "accept a mapped address that is publicly routable" taggedAs (UnitTest, NetworkTest) in {
    withFakeStunServer(v4(8, 8, 8, 8)) { port =>
      val result = ExternalIPDetector.tryStun(List("127.0.0.1" -> port))
      result.map(_.getHostAddress) shouldBe Some("8.8.8.8")
    }
  }

  it should "reject a mapped address in a private-use range" taggedAs (UnitTest, NetworkTest) in {
    withFakeStunServer(v4(10, 0, 0, 1)) { port =>
      ExternalIPDetector.tryStun(List("127.0.0.1" -> port)) shouldBe None
    }
  }

  it should "reject a mapped loopback address" taggedAs (UnitTest, NetworkTest) in {
    withFakeStunServer(v4(127, 0, 0, 1)) { port =>
      ExternalIPDetector.tryStun(List("127.0.0.1" -> port)) shouldBe None
    }
  }

  it should "reject a mapped multicast address" taggedAs (UnitTest, NetworkTest) in {
    withFakeStunServer(v4(224, 0, 0, 1)) { port =>
      ExternalIPDetector.tryStun(List("127.0.0.1" -> port)) shouldBe None
    }
  }

  it should "ignore a reply carrying the wrong transaction ID" taggedAs (UnitTest, NetworkTest) in {
    withFakeStunServer(v4(8, 8, 8, 8), txIdOverride = Some(Array.fill[Byte](12)(0x42))) { port =>
      ExternalIPDetector.tryStun(List("127.0.0.1" -> port)) shouldBe None
    }
  }

  // ---------------------------------------------------------------------
  // detect(mode, ...) — injected probe functions, no real network at all
  // ---------------------------------------------------------------------

  private class CountingProbe(result: Option[InetAddress]) extends (() => Option[InetAddress]):
    var callCount: Int = 0
    def apply(): Option[InetAddress] =
      callCount += 1
      result

  "detect" should "call nothing and return None in mode None" taggedAs (UnitTest, NetworkTest) in {
    val upnp = new CountingProbe(Some(v4(8, 8, 8, 8)))
    val stun = new CountingProbe(Some(v4(8, 8, 8, 8)))
    val http = new CountingProbe(Some(v4(8, 8, 8, 8)))
    val local = new CountingProbe(Some(v4(8, 8, 8, 8)))

    ExternalIPDetector.detect(DetectionMode.None, upnp, stun, http, local) shouldBe None
    upnp.callCount shouldBe 0
    stun.callCount shouldBe 0
    http.callCount shouldBe 0
    local.callCount shouldBe 0
  }

  it should "call only upnp and local-interface in mode Upnp, never stun or http" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val upnp = new CountingProbe(None)
    val stun = new CountingProbe(Some(v4(8, 8, 8, 8)))
    val http = new CountingProbe(Some(v4(8, 8, 8, 8)))
    val local = new CountingProbe(Some(v4(9, 9, 9, 9)))

    val result = ExternalIPDetector.detect(DetectionMode.Upnp, upnp, stun, http, local)
    result shouldBe Some(v4(9, 9, 9, 9))
    upnp.callCount shouldBe 1
    local.callCount shouldBe 1
    stun.callCount shouldBe 0
    http.callCount shouldBe 0
  }

  it should "call every step in order in mode Full, stopping at the first public result" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val upnp = new CountingProbe(None)
    val stun = new CountingProbe(Some(v4(1, 2, 3, 4)))
    val http = new CountingProbe(Some(v4(8, 8, 8, 8)))
    val local = new CountingProbe(Some(v4(9, 9, 9, 9)))

    val result = ExternalIPDetector.detect(DetectionMode.Full, upnp, stun, http, local)
    result shouldBe Some(v4(1, 2, 3, 4))
    upnp.callCount shouldBe 1
    stun.callCount shouldBe 1
    http.callCount shouldBe 0 // never reached — stun already produced a result
    local.callCount shouldBe 0
  }

  it should "fall through every step in mode Full when each returns None" taggedAs (UnitTest, NetworkTest) in {
    val upnp = new CountingProbe(None)
    val stun = new CountingProbe(None)
    val http = new CountingProbe(None)
    val local = new CountingProbe(None)

    ExternalIPDetector.detect(DetectionMode.Full, upnp, stun, http, local) shouldBe None
    upnp.callCount shouldBe 1
    stun.callCount shouldBe 1
    http.callCount shouldBe 1
    local.callCount shouldBe 1
  }
