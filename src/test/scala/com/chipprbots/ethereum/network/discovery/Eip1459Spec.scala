package com.chipprbots.ethereum.network.discovery

import java.math.BigInteger
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtResult
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkOutcome
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.WalkSettings
import com.chipprbots.ethereum.network.discovery.DnsTreeFixture.Stub
import com.chipprbots.ethereum.network.discovery.Eip1459.DnsTree
import com.chipprbots.ethereum.testing.Tags.*

/** The EIP-1459 checks themselves ([[Eip1459]]), against vectors from outside this code base. The Mordor tree in
  * `src/test/resources/dns` is `all.mordor.etcdisco.net` as published on 2026-11-08, root and every entry as the
  * resolver returned it (the multi-string records re-joined as [[DnsDiscovery.concatenateTxtStrings]] does). Its root
  * was signed by the Ethereum Classic tree key, which is the one core-geth ships for it
  * (`params/bootnodes_classic.go:28`): a signature made by go-ethereum's tooling and checked here by this code, and its
  * 17 entries' labels by go-ethereum's hash rule.
  */
class Eip1459Spec extends AnyFlatSpec with Matchers:

  given IORuntime = IORuntime.global

  private val snapshotDomain = "all.mordor.etcdisco.net"
  private lazy val snapshot: Map[String, TxtResult] = DnsTreeFixture.snapshot("/dns/all.mordor.etcdisco.net.tsv")
  private lazy val snapshotTree: DnsTree = DnsTrustAnchors.resolve(snapshotDomain).toOption.get

  private def text(records: Map[String, TxtResult], name: String): String = records(name) match
    case TxtResult.Found(record) => record
    case other                   => fail(s"no record at $name: $other")

  private lazy val snapshotRoot: String = text(snapshot, snapshotDomain)

  private lazy val snapshotRecords: Seq[(String, String)] =
    snapshot.toSeq.collect { case (name, TxtResult.Found(record)) => name -> record }

  private def entriesOf(prefix: String): Seq[(String, String)] =
    snapshotRecords.filter(_._2.startsWith(prefix))

  /** `s` with the character at `index` changed to a different one. */
  private def changeChar(s: String, index: Int): String =
    s.substring(0, index) + (if s(index) == 'A' then 'B' else 'A') + s.substring(index + 1)

  behavior.of("base32 and the enrtree:// URL")

  it should "round-trip the keys go-ethereum and core-geth publish" taggedAs (UnitTest, NetworkTest) in {
    Seq(DnsTrustAnchors.EtcKey, DnsTrustAnchors.EthKey).foreach { key =>
      val bytes = Eip1459.base32Decode(key).getOrElse(fail(s"$key does not decode"))
      bytes.length shouldBe 33
      Set[Byte](2, 3) should contain(bytes(0))
      Eip1459.base32Encode(bytes) shouldBe key
    }
  }

  it should "round-trip arbitrary lengths, and refuse text that is not base32" taggedAs (UnitTest, NetworkTest) in {
    (0 to 40).foreach { n =>
      val bytes = Array.tabulate(n)(i => (i * 37 + n).toByte)
      Eip1459.base32Decode(Eip1459.base32Encode(bytes)).map(_.toSeq) shouldBe Some(bytes.toSeq)
    }
    Eip1459.base32Decode("abcdefgh") shouldBe None // lower case, as Go refuses it
    Eip1459.base32Decode("AB1DEFGH") shouldBe None // 1 is not in the alphabet
    Eip1459.base32Decode("A") shouldBe None // a length no byte count makes
    Eip1459.base32Decode("AAAAAAAA=") shouldBe None // padding
  }

  it should "parse the URL go-ethereum prints for a tree, and print it back" taggedAs (UnitTest, NetworkTest) in {
    val url = s"enrtree://${DnsTrustAnchors.EthKey}@all.sepolia.ethdisco.net"

    val tree = DnsTree.parse(url).toOption.get

    tree.domain shouldBe "all.sepolia.ethdisco.net"
    tree.publicKey.length shouldBe 33
    tree.url shouldBe url
  }

  it should "refuse a URL without a key, with a bad key, or without a domain" taggedAs (UnitTest, NetworkTest) in {
    val onCurve = DnsTrustAnchors.EtcKey
    val offCurve = Eip1459.base32Encode(Array(5.toByte) ++ Array.fill(32)(1.toByte)) // 0x05 is not a point prefix
    Seq(
      "all.classic.etcdisco.net",
      "http://all.classic.etcdisco.net",
      s"enrtree://$onCurve", // no @
      s"enrtree://${onCurve}@",
      "enrtree://NOTBASE32!@example.test",
      s"enrtree://${onCurve.dropRight(2)}@example.test", // a key of the wrong length
      s"enrtree://$offCurve@example.test"
    ).foreach(bad => withClue(bad)(DnsTree.parse(bad).isLeft shouldBe true))
  }

  behavior.of("DnsTrustAnchors")

  it should "give every shipped domain the key its published URL names" taggedAs (UnitTest, NetworkTest) in {
    def keyOf(domain: String): String =
      Eip1459.base32Encode(DnsTrustAnchors.resolve(domain).toOption.get.publicKey.toArray)

    keyOf("all.classic.etcdisco.net") shouldBe DnsTrustAnchors.EtcKey
    keyOf("all.classic.blockd.info") shouldBe DnsTrustAnchors.EtcKey
    keyOf("all.mordor.etcdisco.net") shouldBe DnsTrustAnchors.EtcKey
    keyOf("all.mordor.blockd.info") shouldBe DnsTrustAnchors.EtcKey
    keyOf("all.sepolia.ethdisco.net") shouldBe DnsTrustAnchors.EthKey
    keyOf("all.hoodi.ethdisco.net") shouldBe DnsTrustAnchors.EthKey
  }

  it should "refuse a bare domain it has no key for, and take a URL's own key" taggedAs (UnitTest, NetworkTest) in {
    DnsTrustAnchors.resolve("tree.test").isLeft shouldBe true
    val own = DnsTrustAnchors.resolve(s"enrtree://${DnsTrustAnchors.EthKey}@tree.test")
    own.map(_.domain) shouldBe Right("tree.test")
  }

  behavior.of("the root")

  it should "parse a real root and verify its signature against the published key" taggedAs (UnitTest, NetworkTest) in {
    val root = Eip1459.parseRoot(snapshotRoot).toOption.get

    root.seq shouldBe 1168L
    root.signature.length shouldBe 65
    Eip1459.verifyRoot(root, snapshotTree.publicKey) shouldBe true
  }

  it should "verify the recorded all.hoodi.ethdisco.net root against the shipped EF key" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    // TXT record of all.hoodi.ethdisco.net seen on 2026-11-12 (recorded vector, no network access in this test).
    val hoodiRoot =
      "enrtree-root:v1 e=PJFYMLOBJ3UM4YA7HMYZ67ORDI l=FDXN3SN67NA5DKA4J2GOK7BVQI seq=1794520894 sig=nIcQulprxRACi1eCrFyD0Oq26R2rXVe0dldhBjvvkdAL8lReQWRf4Qg6H2qJ2yRCE5Kp6yPzq3-qma7XVKbnrwE"
    val root = Eip1459.parseRoot(hoodiRoot).toOption.get
    val key = DnsTrustAnchors.resolve("all.hoodi.ethdisco.net").toOption.get.publicKey

    root.seq shouldBe 1794520894L
    Eip1459.verifyRoot(root, key) shouldBe true
  }

  it should "not verify a real root under another tree's key" taggedAs (UnitTest, NetworkTest) in {
    val root = Eip1459.parseRoot(snapshotRoot).toOption.get
    val efKey = DnsTrustAnchors.resolve("all.sepolia.ethdisco.net").toOption.get.publicKey

    Eip1459.verifyRoot(root, efKey) shouldBe false
  }

  it should "not verify a real root after any field is changed" taggedAs (UnitTest, NetworkTest) in {
    val root = Eip1459.parseRoot(snapshotRoot).toOption.get

    Eip1459.verifyRoot(root.copy(seq = root.seq + 1), snapshotTree.publicKey) shouldBe false
    Eip1459.verifyRoot(root.copy(eroot = root.lroot), snapshotTree.publicKey) shouldBe false
    Eip1459.verifyRoot(root.copy(lroot = root.eroot), snapshotTree.publicKey) shouldBe false
  }

  it should "not accept the same signature with its s flipped to the high half, as go-ethereum does not" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val root = Eip1459.parseRoot(snapshotRoot).toOption.get
    val sig = root.signature.toArray
    val s = new BigInteger(1, sig.slice(32, 64))
    val highS = crypto.curve.getN.subtract(s).toByteArray.dropWhile(_ == 0)
    val malleated = sig.take(32) ++ Array.fill(32 - highS.length)(0.toByte) ++ highS :+ (sig(64) ^ 1).toByte

    // mathematically a valid signature of the same text by the same key, but not the canonical one
    Eip1459.verifyRoot(root, snapshotTree.publicKey) shouldBe true
    Eip1459.verifyRoot(root.copy(signature = ByteString(malleated)), snapshotTree.publicKey) shouldBe false
  }

  it should "be parsed strictly: field order, child labels, sequence number and a 65-byte unpadded signature" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val root = Eip1459.parseRoot(snapshotRoot).toOption.get
    val sig = Base64.getUrlEncoder.withoutPadding.encodeToString(root.signature.toArray)
    val good = s"enrtree-root:v1 e=${root.eroot} l=${root.lroot} seq=1 sig=$sig"
    Eip1459.parseRoot(good).isRight shouldBe true

    Seq(
      s"enrtree-root:v1 l=${root.lroot} e=${root.eroot} seq=1 sig=$sig", // fields out of order
      s"enrtree-root:v1 e=${root.eroot} l=${root.lroot} seq=1", // no signature
      s"enrtree-root:v1 e=${root.eroot} l=${root.lroot} seq=x sig=$sig", // seq is not a number
      s"enrtree-root:v1 e=abc l=${root.lroot} seq=1 sig=$sig", // not a valid label
      s"enrtree-root:v1 e=${root.eroot} l=${root.lroot} seq=1 sig=$sig=", // padded
      s"enrtree-root:v1 e=${root.eroot} l=${root.lroot} seq=1 sig=${sig.dropRight(4)}", // a short signature
      s"enrtree-root:v2 e=${root.eroot} l=${root.lroot} seq=1 sig=$sig" // another version
    ).foreach(bad => withClue(bad)(Eip1459.parseRoot(bad).isLeft shouldBe true))
  }

  behavior.of("entry labels")

  it should "match every entry of the real tree: the name is the base32 of keccak256 truncated to 16 bytes" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val entries = snapshotRecords.filter(_._1 != snapshotDomain)

    entries.size shouldBe 17
    entries.foreach { case (name, record) =>
      val label = name.stripSuffix(s".$snapshotDomain")
      withClue(name) {
        Eip1459.entryLabel(record) shouldBe label
        Eip1459.labelMatches(label, record) shouldBe true
      }
    }
  }

  it should "match on a truncated label, and on nothing else" taggedAs (UnitTest, NetworkTest) in {
    val (name, record) = entriesOf(Eip1459.BranchPrefix).find(_._2.length > 100).get
    val label = name.stripSuffix(s".$snapshotDomain")
    val twelveBytes = label.take(20) // 20 base32 characters are 12 bytes (and 4 bits)

    Eip1459.labelMatches(twelveBytes, record) shouldBe true
    Eip1459.labelMatches(label.take(18), record) shouldBe false // 11 bytes is below the minimum
    Eip1459.labelMatches(label, record + " ") shouldBe false
    Eip1459.labelMatches(label.toLowerCase, record) shouldBe false
    Eip1459.labelMatches(changeChar(label, 3), record) shouldBe false
  }

  behavior.of("an ENR")

  it should "verify every real ENR of the tree, and fail the same ENR with a changed byte" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val enrs = entriesOf(Eip1459.EnrPrefix).map(_._2)

    enrs.size shouldBe 14
    enrs.foreach { enr =>
      withClue(enr)(Eip1459.verifyEnr(enr) shouldBe Right(()))
      val changed = changeChar(enr, enr.length / 2)
      withClue(changed)(Eip1459.verifyEnr(changed).isLeft shouldBe true)
    }
  }

  it should "verify the fixture's ENRs and fail the ways an ENR can be wrong" taggedAs (UnitTest, NetworkTest) in {
    Eip1459.verifyEnr(DnsTreeFixture.enr(7)) shouldBe Right(())
    Eip1459.verifyEnr(DnsTreeFixture.enrWithBadSignature(7)).isLeft shouldBe true
    Eip1459.verifyEnr(DnsTreeFixture.enrWithoutScheme(7)).isLeft shouldBe true
    Eip1459.verifyEnr("enr:").isLeft shouldBe true
    Eip1459.verifyEnr("enr:not base64!").isLeft shouldBe true
    Eip1459.verifyEnr("enr:AAAA").isLeft shouldBe true
  }

  behavior.of("the real Mordor tree")

  private def walkSnapshot(url: DnsTree, records: Map[String, TxtResult]) =
    val seen = ConcurrentHashMap.newKeySet[String]()
    val summary = DnsDiscovery
      .walkTree(url, new Stub(records), WalkSettings(60.seconds, 8), None, enode => IO(seen.add(enode)).void)
      .unsafeRunTimed(60.seconds)
      .getOrElse(fail("the walk did not return"))
    (summary, seen.asScala.toSet)

  it should "verify end to end: root signature, 17 entry hashes and 14 ENR signatures, nothing rejected" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (summary, delivered) = walkSnapshot(snapshotTree, snapshot)

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 0
    summary.lost shouldBe 0
    summary.resolved shouldBe 14
    summary.seq shouldBe Some(1168L)
    summary.queries shouldBe 18
    delivered.size shouldBe 14
  }

  it should "deliver nothing under the wrong key" taggedAs (UnitTest, NetworkTest) in {
    val efKey = DnsTrustAnchors.resolve("all.sepolia.ethdisco.net").toOption.get.publicKey

    val (summary, delivered) = walkSnapshot(DnsTree(snapshotDomain, efKey), snapshot)

    summary.outcome shouldBe a[WalkOutcome.Failed]
    summary.rejected shouldBe 1
    delivered shouldBe empty
  }

  it should "drop exactly the one ENR that was changed in transit" taggedAs (UnitTest, NetworkTest) in {
    val (name, record) = entriesOf(Eip1459.EnrPrefix).head
    val changed = changeChar(record, 40)

    val (summary, delivered) = walkSnapshot(snapshotTree, snapshot.updated(name, TxtResult.Found(changed)))

    summary.outcome shouldBe WalkOutcome.Completed
    summary.rejected shouldBe 1
    summary.resolved shouldBe 13
    delivered.size shouldBe 13
  }
