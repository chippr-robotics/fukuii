package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Hex

/** Spec 030 FR-050: the entries of the node allowlist, their sources, and the snapshot readers see. */
class NodeAllowlistSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with org.scalatest.EitherValues
    with org.scalatest.OptionValues:

  private val hexChar = Gen.oneOf("0123456789abcdef".toSeq)
  private val nodeIdHex: Gen[String] = Gen.listOfN(128, hexChar).map(_.mkString)

  "NodeAllowlist.parseEntry" should "accept 128 hex characters, any case, with or without 0x" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    forAll(nodeIdHex) { id =>
      val expected = ByteString(Hex.decode(id))
      NodeAllowlist.parseEntry(id) shouldBe Right(ParsedAllowlistEntry(expected, None))
      NodeAllowlist.parseEntry("0x" + id.toUpperCase) shouldBe Right(ParsedAllowlistEntry(expected, None))
    }
  }

  it should "accept an enode URI, keep its address and lower-case its id" taggedAs (UnitTest, NetworkTest) in {
    forAll(nodeIdHex) { id =>
      val parsed = NodeAllowlist.parseEntry(s"enode://${id.toUpperCase}@10.1.2.3:30303?discport=30301").value
      parsed.id shouldBe ByteString(Hex.decode(id))
      parsed.enode.value.getUserInfo shouldBe id
      parsed.enode.value.getHost shouldBe "10.1.2.3"
      parsed.enode.value.getPort shouldBe 30303
    }
  }

  it should "accept an IPv6 literal address" taggedAs (UnitTest, NetworkTest) in {
    val id = "ab" * 64
    NodeAllowlist.parseEntry(s"enode://$id@[::1]:30303").value.enode.value.getHost shouldBe "[::1]"
  }

  it should "refuse what is not a node id or an enode, naming the entry" taggedAs (UnitTest, NetworkTest) in {
    val id = "ab" * 64
    val bad = Seq(
      "",
      "nonsense",
      "ab" * 63, // 126 hex
      "zz" * 64, // not hex
      s"enode://${"ab" * 63}@10.0.0.1:30303", // short id
      s"enode://$id@10.0.0.1", // no port
      s"enode://$id@10.0.0.1:0", // port 0
      s"enode://$id@:30303", // no host
      s"http://$id@10.0.0.1:30303" // not an enode
    )
    bad.foreach(b => withClue(s"'$b': ")(NodeAllowlist.parseEntry(b).isLeft shouldBe true))
    NodeAllowlist.parseAll(Seq(id, "nonsense")).left.value should include("nonsense")
  }

  "NodeAllowlist.merge" should "union the sources of an id, keep the first address and drop the node's own id" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val a = "aa" * 64
    val b = "bb" * 64
    val own = "cc" * 64
    def p(s: String) = NodeAllowlist.parseEntry(s).value
    val merged = NodeAllowlist.merge(
      Seq(
        AllowlistSource.Config -> Seq(p(a), p(own)),
        AllowlistSource.File -> Seq(p(s"enode://$a@10.0.0.1:30303"), p(b)),
        AllowlistSource.Static -> Seq(p(s"enode://$a@10.0.0.9:30303"))
      ),
      Some(ByteString(Hex.decode(own)))
    )
    merged.keySet shouldBe Set(ByteString(Hex.decode(a)), ByteString(Hex.decode(b)))
    val ea = merged(ByteString(Hex.decode(a)))
    ea.sources shouldBe Set(AllowlistSource.Config, AllowlistSource.File, AllowlistSource.Static)
    ea.enode.value.getHost shouldBe "10.0.0.1"
    merged(ByteString(Hex.decode(b))).enode shouldBe None
  }

  "A disabled NodeAllowlist" should "permit every node and refuse no dial" taggedAs (UnitTest, NetworkTest) in {
    val any = ByteString(Hex.decode("ab" * 64))
    NodeAllowlist.Disabled.enabled shouldBe false
    NodeAllowlist.Disabled.permits(any) shouldBe true
    NodeAllowlist.Disabled.refusalFor(new java.net.URI(s"enode://${"ab" * 64}@10.0.0.1:30303")) shouldBe None
  }

  "An enabled NodeAllowlist" should "permit listed ids only and name an unlisted dial" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val listed = "aa" * 64
    val other = "bb" * 64
    val entries = NodeAllowlist.merge(
      Seq(AllowlistSource.Config -> Seq(NodeAllowlist.parseEntry(listed).value)),
      None
    )
    val list = NodeAllowlist.enabled(AllowlistSnapshot(entries, 1L))
    list.permits(ByteString(Hex.decode(listed))) shouldBe true
    list.permits(ByteString(Hex.decode(other))) shouldBe false
    list.refusalFor(new java.net.URI(s"enode://$listed@10.0.0.1:30303")) shouldBe None
    list.refusalFor(new java.net.URI(s"enode://$other@10.0.0.1:30303")).value should include(
      "not on the node allowlist"
    )
  }

  it should "raise the generation and tell listeners only when the set changes" taggedAs (UnitTest, NetworkTest) in {
    val a = NodeAllowlist.parseEntry("aa" * 64).value
    val b = NodeAllowlist.parseEntry("bb" * 64).value
    val list = NodeAllowlist.enabled(AllowlistSnapshot.empty)
    var seen = Vector.empty[Long]
    list.onChange(s => seen = seen :+ s.generation)

    val one = NodeAllowlist.merge(Seq(AllowlistSource.Config -> Seq(a)), None)
    list.replace(one) shouldBe true
    list.replace(one) shouldBe false // no-op: same entries
    list.replace(NodeAllowlist.merge(Seq(AllowlistSource.Config -> Seq(a, b)), None)) shouldBe true
    seen shouldBe Vector(1L, 2L)
    list.generation shouldBe 2L
    list.snapshot.size shouldBe 2
  }
