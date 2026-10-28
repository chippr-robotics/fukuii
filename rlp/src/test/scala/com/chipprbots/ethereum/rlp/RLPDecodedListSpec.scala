package com.chipprbots.ethereum.rlp

import scala.util.Try

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

/** #1675: the decoder builds every `RLPList.items` as a `Vector`. Callers index decoded lists (`items(i)` in header,
  * trie-node and transaction decoders) and walk them head/tail (`+:` extractors); on the immutable `Queue` the decoder
  * used to build, each `items(i)` was O(i), which made every indexed walk over a decoded list quadratic. Only the
  * collection type may change: the decoded structure and the re-encoded bytes must be exactly what they were.
  */
class RLPDecodedListSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  /** Byte strings around every length boundary the encoding switches on: empty, the single bytes that encode as
    * themselves (< 0x80) and those that do not, the 55/56-byte short/long boundary, and the 0xff/0x100 boundary of the
    * length-of-length.
    */
  private val valueGen: Gen[RLPValue] =
    val sizes = Gen.oneOf(
      Gen.const(0),
      Gen.const(1),
      Gen.choose(2, 54),
      Gen.oneOf(55, 56, 57),
      Gen.oneOf(255, 256, 257),
      Gen.choose(58, 1100)
    )
    for
      n <- sizes
      bytes <- Gen.containerOfN[Array, Byte](n, Gen.choose(Byte.MinValue, Byte.MaxValue))
    yield RLPValue(bytes)

  /** Arbitrary trees, depth-bounded well under [[RLP.MaxNestingDepth]], with lists both side of the 55-byte payload
    * boundary.
    */
  private def treeGen(depth: Int): Gen[RLPEncodeable] =
    if depth == 0 then valueGen
    else
      Gen.frequency(
        3 -> valueGen,
        2 -> Gen.choose(0, 8).flatMap(n => Gen.listOfN(n, treeGen(depth - 1)).map(items => RLPList(items*))),
        1 -> Gen.choose(20, 70).flatMap(n => Gen.listOfN(n, valueGen).map(items => RLPList(items*)))
      )

  /** Structural equality: `RLPValue` is a case class over `Array[Byte]`, so its `==` is reference equality. */
  private def sameStructure(a: RLPEncodeable, b: RLPEncodeable): Boolean =
    (a, b) match
      case (RLPValue(x), RLPValue(y)) => x.sameElements(y)
      case (RLPList(xs*), RLPList(ys*)) =>
        xs.length == ys.length && xs.iterator.zip(ys.iterator).forall { case (x, y) => sameStructure(x, y) }
      case _ => false

  /** Every list in a decoded tree, the root included. */
  private def lists(e: RLPEncodeable): Iterator[RLPList] =
    e match
      case l: RLPList => Iterator.single(l) ++ l.items.iterator.flatMap(lists)
      case _          => Iterator.empty

  "RLP.rawDecode" should "build every decoded list, at every nesting level, as a Vector" taggedAs (
    UnitTest,
    RLPTest
  ) in {
    forAll(treeGen(4)) { tree =>
      val decoded = RLP.rawDecode(RLP.encode(tree))
      lists(decoded).foreach(l => l.items shouldBe a[Vector[?]])
    }
  }

  it should "re-encode a decoded tree to exactly the bytes it was decoded from, with the same structure" taggedAs (
    UnitTest,
    RLPTest
  ) in {
    forAll(treeGen(4), minSuccessful(500)) { tree =>
      val bytes = RLP.encode(tree)
      val decoded = RLP.rawDecode(bytes)
      RLP.encode(decoded) shouldBe bytes
      sameStructure(decoded, tree) shouldBe true
    }
  }

  it should "decode a large flat list (a 46,000-item reply) as a Vector in its original order" taggedAs (
    UnitTest,
    RLPTest
  ) in {
    val items = (0 until 46000).map(i => RLPValue(BigInt(i).toByteArray))
    val bytes = RLP.encode(RLPList(items*))
    val decoded = RLP.rawDecode(bytes).asInstanceOf[RLPList]
    decoded.items shouldBe a[Vector[?]]
    decoded.items.length shouldBe 46000
    // Indexed reads at the far end, the access a Queue made O(i).
    decoded.items(45999).asInstanceOf[RLPValue].bytes shouldBe BigInt(45999).toByteArray
    RLP.encode(decoded) shouldBe bytes
  }

  it should "still reject a list whose last item overruns the list's declared length" taggedAs (
    UnitTest,
    RLPTest
  ) in {
    // 0xc2 declares a 2-byte payload, but its item 0x83 'a' 'b' 'c' is 4 bytes: the decoder steps past the list end.
    // The builder change must not turn that into an accepted (truncated or extended) list.
    val overrun = Array[Byte](0xc2.toByte, 0x83.toByte, 'a', 'b', 'c')
    Try(RLP.rawDecode(overrun)).isFailure shouldBe true
  }
