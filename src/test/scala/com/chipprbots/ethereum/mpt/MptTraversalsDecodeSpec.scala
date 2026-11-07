package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*

/** #1675: `parseMpt` reads a branch node's 17 items by index (`items(i)` for each child, then `items.last`). On the
  * `Queue` the RLP decoder used to build, that was O(i) per read on the hottest decode path in the node (every trie
  * read, the per-block tx/receipt root validators, healing and proofs). The decoded list is a `Vector`, and parsing
  * must keep it one: nothing between the decoder and `parseMpt` may copy it back into a linear collection.
  */
class MptTraversalsDecodeSpec extends AnyFlatSpec with Matchers:

  private val childHashes: IndexedSeq[Array[Byte]] =
    (0 until 16).map(i => if i % 3 == 0 then Array.fill[Byte](32)((i + 1).toByte) else Array.emptyByteArray)
  private val terminator: Array[Byte] = "terminator".getBytes
  private val branchBytes: Array[Byte] =
    rlp.encode(RLPList((childHashes.map(RLPValue(_)) :+ RLPValue(terminator))*))

  "MptTraversals.decodeNode" should "parse a branch node from an indexed (Vector) item list" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    MptTraversals.decodeNode(branchBytes) match
      case branch: BranchNode =>
        branch.parsedRlp match
          case Some(list: RLPList) => list.items shouldBe a[Vector[?]]
          case other               => fail(s"expected the parsed RLP list to be kept, got $other")
        (0 until 16).foreach { i =>
          branch.children(i) match
            case HashNode(hash) => hash shouldBe childHashes(i)
            case NullNode       => childHashes(i) shouldBe empty
            case other          => fail(s"unexpected child $other at $i")
        }
        branch.terminator shouldBe Some(ByteString(terminator))
      case other => fail(s"expected a branch node, got $other")
  }

  it should "re-encode a decoded branch node to exactly its original bytes" taggedAs (UnitTest, MPTTest) in {
    MptTraversals.encodeNode(MptTraversals.decodeNode(branchBytes)) shouldBe branchBytes
  }
