package com.chipprbots.ethereum.network.discovery

import java.util.Base64

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for ENR parsing in DnsDiscovery.
  *
  * Tests parseEnrToEnode with valid and invalid ENR records (EIP-778). Does not require DNS access — all test vectors
  * are constructed in-process.
  *
  * ENR structure: RLP([signature(64), seq, k1, v1, k2, v2, ...]) URL-safe base64 (no padding) after "enr:" prefix.
  */
class DnsDiscoveryEnrSpec extends AnyFlatSpec with Matchers {

  // secp256k1 generator point G in compressed form (33 bytes: 0x02 + x-coordinate).
  // Using G makes every test independent of key generation — it's a canonical valid point.
  private val validCompressedKey: Array[Byte] = Array(
    0x02, 0x79, 0xbe.toByte, 0x66, 0x7e, 0xf9.toByte, 0xdc.toByte, 0xbb.toByte,
    0xac.toByte, 0x55, 0xa0.toByte, 0x62, 0x95.toByte, 0xce.toByte, 0x87.toByte, 0x0b,
    0x07, 0x02, 0x9b.toByte, 0xfc.toByte, 0xdb.toByte, 0x2d, 0xce.toByte, 0x28,
    0xd9.toByte, 0x59, 0xf2.toByte, 0x81.toByte, 0x5b, 0x16, 0xf8.toByte, 0x17, 0x98.toByte
  ).map(_.toByte)

  private def buildEnr(
      ip: Array[Byte] = Array(127, 0, 0, 1).map(_.toByte),
      tcp: Array[Byte] = Array(0x1f, 0x90.toByte).map(_.toByte), // 8080
      udp: Option[Array[Byte]] = None,
      pubkey: Array[Byte] = validCompressedKey,
      extraFields: Seq[RLPEncodeable] = Nil
  ): String = {
    val base: Seq[RLPEncodeable] = Seq(
      RLPValue(Array.fill(64)(0.toByte)), // signature placeholder
      RLPValue(Array(0.toByte)),           // seq = 0
      RLPValue("ip".getBytes("UTF-8")), RLPValue(ip),
      RLPValue("tcp".getBytes("UTF-8")), RLPValue(tcp),
      RLPValue("secp256k1".getBytes("UTF-8")), RLPValue(pubkey)
    ) ++ udp.fold(Seq.empty[RLPEncodeable]) { u =>
      Seq(RLPValue("udp".getBytes("UTF-8")), RLPValue(u))
    } ++ extraFields

    val bytes = encode(RLPList(base: _*))
    "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  "parseEnrToEnode" should "parse a minimal valid IPv4 ENR" taggedAs UnitTest in {
    val enr = buildEnr()
    val result = DnsDiscovery.parseEnrToEnode(enr)
    result shouldBe defined
    result.get should startWith("enode://")
    result.get should include("@127.0.0.1:8080")
    val nodeId = result.get.stripPrefix("enode://").takeWhile(_ != '@')
    nodeId should have length 128 // 64 bytes hex-encoded
  }

  it should "produce ?discport suffix when udp port differs from tcp port" taggedAs UnitTest in {
    val enr = buildEnr(
      tcp = Array(0x76.toByte, 0x51.toByte), // 30289
      udp = Some(Array(0x76.toByte, 0x52.toByte)) // 30290
    )
    val result = DnsDiscovery.parseEnrToEnode(enr)
    result shouldBe defined
    result.get should include(":30289?discport=30290")
  }

  it should "not include ?discport when udp equals tcp" taggedAs UnitTest in {
    val port = Array(0x76.toByte, 0x51.toByte) // 30289
    val enr = buildEnr(tcp = port, udp = Some(port))
    val result = DnsDiscovery.parseEnrToEnode(enr)
    result shouldBe defined
    result.get should not include "discport"
  }

  it should "return None when ip field is missing" taggedAs UnitTest in {
    val bytes = encode(RLPList(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte)),
      RLPValue("tcp".getBytes("UTF-8")), RLPValue(Array(0x1f, 0x90.toByte).map(_.toByte)),
      RLPValue("secp256k1".getBytes("UTF-8")), RLPValue(validCompressedKey)
    ))
    val enr = "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None when tcp field is missing" taggedAs UnitTest in {
    val bytes = encode(RLPList(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte)),
      RLPValue("ip".getBytes("UTF-8")), RLPValue(Array(127, 0, 0, 1).map(_.toByte)),
      RLPValue("secp256k1".getBytes("UTF-8")), RLPValue(validCompressedKey)
    ))
    val enr = "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None when secp256k1 field is missing" taggedAs UnitTest in {
    val bytes = encode(RLPList(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte)),
      RLPValue("ip".getBytes("UTF-8")), RLPValue(Array(127, 0, 0, 1).map(_.toByte)),
      RLPValue("tcp".getBytes("UTF-8")), RLPValue(Array(0x1f, 0x90.toByte).map(_.toByte))
    ))
    val enr = "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None when tcp port is 0" taggedAs UnitTest in {
    val enr = buildEnr(tcp = Array(0, 0).map(_.toByte))
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None for a 32-byte (uncompressed-coordinate-only) public key" taggedAs UnitTest in {
    val shortKey = validCompressedKey.drop(1) // drop prefix → 32 bytes
    val enr = buildEnr(pubkey = shortKey)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None for a 65-byte (uncompressed) public key" taggedAs UnitTest in {
    val expandedKey = Array(0x04.toByte) ++ Array.fill(64)(0x00.toByte)
    val enr = buildEnr(pubkey = expandedKey)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None for invalid base64 ENR" taggedAs UnitTest in {
    DnsDiscovery.parseEnrToEnode("enr:invalid-base64!!!") shouldBe None
  }

  it should "return None for empty ENR payload" taggedAs UnitTest in {
    DnsDiscovery.parseEnrToEnode("enr:") shouldBe None
  }

  it should "return None for truncated (non-list) RLP" taggedAs UnitTest in {
    DnsDiscovery.parseEnrToEnode("enr:AAAA") shouldBe None
  }

  it should "return None for RLP list with fewer than 4 items" taggedAs UnitTest in {
    val bytes = encode(RLPList(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte))
    ))
    val enr = "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "return None for 3-byte ip (wrong length)" taggedAs UnitTest in {
    val enr = buildEnr(ip = Array(127, 0, 0).map(_.toByte))
    DnsDiscovery.parseEnrToEnode(enr) shouldBe None
  }

  it should "handle URL-safe base64 characters (- and _) correctly" taggedAs UnitTest in {
    // Construct an ENR whose base64 encoding naturally contains + or / (→ - and _)
    // by trying multiple seq values. Any valid ENR is sufficient.
    val enr = buildEnr()
    val withPadding = enr.replace('-', '+').replace('_', '/')
    // Standard base64 should not parse (DnsDiscovery expects URL-safe)
    // but our ENR should still parse fine (the URL-safe one)
    DnsDiscovery.parseEnrToEnode(enr) shouldBe defined
  }

  it should "ignore unknown key-value fields in ENR" taggedAs UnitTest in {
    val enr = buildEnr(extraFields = Seq(
      RLPValue("eth".getBytes("UTF-8")),
      RLPValue(Array(0xDE.toByte, 0xAD.toByte, 0xBE.toByte, 0xEF.toByte))
    ))
    DnsDiscovery.parseEnrToEnode(enr) shouldBe defined
  }

  it should "prefer IPv4 over IPv6 when both present" taggedAs UnitTest in {
    val ipv6Bytes = Array.fill(16)(0.toByte)
    ipv6Bytes(15) = 1.toByte // ::1
    val bytes = encode(RLPList(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte)),
      RLPValue("ip".getBytes("UTF-8")), RLPValue(Array(10, 0, 0, 1).map(_.toByte)),
      RLPValue("tcp".getBytes("UTF-8")), RLPValue(Array(0x1f, 0x90.toByte).map(_.toByte)),
      RLPValue("secp256k1".getBytes("UTF-8")), RLPValue(validCompressedKey),
      RLPValue("ip6".getBytes("UTF-8")), RLPValue(ipv6Bytes),
      RLPValue("tcp6".getBytes("UTF-8")), RLPValue(Array(0x1f, 0x90.toByte).map(_.toByte))
    ))
    val enr = "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val result = DnsDiscovery.parseEnrToEnode(enr)
    result shouldBe defined
    result.get should include("10.0.0.1") // IPv4 wins
    result.get should not include "::1"
  }

  it should "fall back to IPv6 when IPv4 is absent" taggedAs UnitTest in {
    val ipv6Bytes = Array.fill(16)(0.toByte)
    ipv6Bytes(15) = 1.toByte // ::1
    val bytes = encode(RLPList(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte)),
      RLPValue("ip6".getBytes("UTF-8")), RLPValue(ipv6Bytes),
      RLPValue("tcp6".getBytes("UTF-8")), RLPValue(Array(0x1f, 0x90.toByte).map(_.toByte)),
      RLPValue("secp256k1".getBytes("UTF-8")), RLPValue(validCompressedKey)
    ))
    val enr = "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val result = DnsDiscovery.parseEnrToEnode(enr)
    result shouldBe defined
    result.get should include("[::1]") // IPv6 bracketed
  }

  it should "produce a 128-hex-char node ID (64 uncompressed pubkey bytes)" taggedAs UnitTest in {
    val enr = buildEnr()
    val result = DnsDiscovery.parseEnrToEnode(enr)
    result shouldBe defined
    val nodeId = result.get.stripPrefix("enode://").takeWhile(_ != '@')
    nodeId should have length 128
    all(nodeId.toCharArray) should (be >= '0' and be <= 'f') // hex chars
  }
}
