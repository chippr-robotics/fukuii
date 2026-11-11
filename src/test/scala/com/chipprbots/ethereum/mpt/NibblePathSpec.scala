package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T018): [[NibblePath]], the position of a node in a trie. */
class NibblePathSpec extends AnyFlatSpec with Matchers:

  private def hp(path: NibblePath): String = Hex.toHexString(path.toHexPrefix)

  "NibblePath" should "hold one nibble per byte" taggedAs (UnitTest, MPTTest) in {
    val path = NibblePath(0x0, 0xa, 0xf)
    path.length shouldBe 3
    path.isEmpty shouldBe false
    (0 until 3).map(path(_)) shouldBe Seq[Byte](0x0, 0xa, 0xf)
    path.toNibbles shouldBe Array[Byte](0x0, 0xa, 0xf)
    path.show shouldBe "0af"

    NibblePath.Empty.length shouldBe 0
    NibblePath.Empty.isEmpty shouldBe true
    NibblePath.Empty.show shouldBe ""
  }

  it should "refuse a byte that is not a nibble" taggedAs (UnitTest, MPTTest) in {
    an[IllegalArgumentException] should be thrownBy NibblePath.fromNibbles(Array[Byte](0x1, 0x10))
    an[IllegalArgumentException] should be thrownBy NibblePath.fromNibbles(Array[Byte](-1))
    an[IllegalArgumentException] should be thrownBy (NibblePath.Empty :+ 16)
    an[IllegalArgumentException] should be thrownBy (NibblePath.Empty :+ -1)
    an[IllegalArgumentException] should be thrownBy (NibblePath.Empty ++ ByteString(0x3, 0x20))
  }

  // The store keys of spec 018 data-model 2 and 4.3: HexPrefix.encode(nibbles, isLeaf = false).
  it should "encode to the store key of data-model 2" taggedAs (UnitTest, MPTTest) in {
    hp(NibblePath.Empty) shouldBe "00"
    hp(NibblePath(0x0)) shouldBe "10"
    hp(NibblePath(0xa)) shouldBe "1a"
    hp(NibblePath(0xa, 0xb)) shouldBe "00ab"
    hp(NibblePath(0xa, 0xb, 0xc)) shouldBe "1abc"

    val sixtyFour = NibblePath.fromNibbles(Array.tabulate(64)(i => (i % 16).toByte))
    hp(sixtyFour) shouldBe "00" + "0123456789abcdef" * 4
    sixtyFour.toHexPrefix.length shouldBe 33

    val sixtyThree = NibblePath.fromNibbles(Array.tabulate(63)(i => ((i + 1) % 16).toByte))
    hp(sixtyThree) shouldBe "1" + ("0123456789abcdef" * 4).drop(1)
    sixtyThree.toHexPrefix.length shouldBe 32

    NibblePath(0xa, 0xb).key shouldBe ByteString(Hex.decode("00ab"))
  }

  it should "come back from its store key" taggedAs (UnitTest, MPTTest) in {
    val paths = Seq(
      NibblePath.Empty,
      NibblePath(0x0),
      NibblePath(0xa),
      NibblePath(0xa, 0xb),
      NibblePath(0xa, 0xb, 0xc),
      NibblePath.fromNibbles(Array.tabulate(63)(i => (i % 16).toByte)),
      NibblePath.fromNibbles(Array.tabulate(64)(i => (i % 16).toByte))
    )
    paths.foreach { path =>
      withClue(s"[${path.show}]") {
        NibblePath.fromHexPrefix(path.toHexPrefix).sameAs(path) shouldBe true
      }
    }
  }

  it should "refuse a key that is not a position" taggedAs (UnitTest, MPTTest) in {
    // 0x20 and 0x3a carry the leaf flag: a leaf's key, never a position
    an[IllegalArgumentException] should be thrownBy NibblePath.fromHexPrefix(Hex.decode("20ab"))
    an[IllegalArgumentException] should be thrownBy NibblePath.fromHexPrefix(Hex.decode("3a"))
    // an even-length key pads its first byte with a zero nibble
    an[IllegalArgumentException] should be thrownBy NibblePath.fromHexPrefix(Hex.decode("0aab"))
    an[IllegalArgumentException] should be thrownBy NibblePath.fromHexPrefix(Hex.decode("40"))
    an[IllegalArgumentException] should be thrownBy NibblePath.fromHexPrefix(Array.emptyByteArray)
  }

  it should "append a branch child and an extension's nibbles" taggedAs (UnitTest, MPTTest) in {
    val branch = NibblePath(0x1, 0x2)
    (branch :+ 0xf).show shouldBe "12f"
    (branch ++ ByteString(0x3, 0x4)).show shouldBe "1234"
    (branch ++ NibblePath(0x5)).show shouldBe "125"
    (branch ++ ByteString.empty).sameAs(branch) shouldBe true
    (NibblePath.Empty ++ branch).sameAs(branch) shouldBe true
    (NibblePath.Empty :+ 0x0).show shouldBe "0"
  }

  it should "never change once made" taggedAs (UnitTest, MPTTest) in {
    val source = Array[Byte](0x1, 0x2)
    val path = NibblePath.fromNibbles(source)
    source(0) = 0xf
    path.show shouldBe "12"

    val copy = path.toNibbles
    copy(1) = 0xf
    path.show shouldBe "12"

    val child = path :+ 0x3
    path.show shouldBe "12"
    child.show shouldBe "123"
  }

  it should "know the positions above it" taggedAs (UnitTest, MPTTest) in {
    val path = NibblePath(0x1, 0x2, 0x3)
    path.startsWith(NibblePath.Empty) shouldBe true
    path.startsWith(NibblePath(0x1)) shouldBe true
    path.startsWith(NibblePath(0x1, 0x2, 0x3)) shouldBe true
    path.startsWith(NibblePath(0x1, 0x3)) shouldBe false
    path.startsWith(NibblePath(0x1, 0x2, 0x3, 0x4)) shouldBe false
  }

  it should "compare by content with sameAs, and by content as a key" taggedAs (UnitTest, MPTTest) in {
    val one = NibblePath(0x1, 0x2)
    val other = NibblePath(0x1, 0x2)
    one.sameAs(other) shouldBe true
    one.sameAs(NibblePath(0x1)) shouldBe false
    one.key shouldBe other.key
    Map(one.key -> "node").get(other.key) shouldBe Some("node")
  }

  it should "sort in trie order, a position before the positions below it" taggedAs (UnitTest, MPTTest) in {
    val paths = Seq(
      NibblePath(0xa, 0xb),
      NibblePath(0xb),
      NibblePath.Empty,
      NibblePath(0xa),
      NibblePath(0xa, 0x0),
      NibblePath(0x0, 0xf, 0xf)
    )
    paths.sorted.map(_.show) shouldBe Seq("", "0ff", "a", "a0", "ab", "b")
  }

  // Store keys are not prefix-ordered (data-model 2): the children of [a] are 00ax, nowhere near 1a.
  it should "sort in store order by its key bytes, which is not trie order" taggedAs (UnitTest, MPTTest) in {
    val paths = Seq(NibblePath(0xa), NibblePath(0xa, 0xb), NibblePath.Empty, NibblePath(0xf), NibblePath(0xa, 0xb, 0xc))
    val sorted = paths.sorted(using NibblePath.storeOrdering)
    sorted.map(hp) shouldBe Seq("00", "00ab", "1a", "1abc", "1f")
    sorted.map(_.show) shouldBe Seq("", "ab", "a", "abc", "f")
  }
