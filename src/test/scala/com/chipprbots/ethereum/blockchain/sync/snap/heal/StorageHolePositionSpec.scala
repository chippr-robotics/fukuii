package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** #1694: a storage hole met writing carries no position. Asking for the storage root instead drew an honest reply of
  * the wrong node, and the peer was blacklisted for it.
  */
class StorageHolePositionSpec extends AnyFlatSpec with Matchers:

  private val account = ByteString(kec256("account".getBytes))
  private def hash(tag: String) = ByteString(kec256(tag.getBytes))
  private def compact(nibbles: Int*) = ByteString(HexPrefix.encode(nibbles.map(_.toByte).toArray, isLeaf = false))

  private def branch(children: (Int, ByteString)*): MptNode =
    val at = children.toMap
    BranchNode(
      Array.tabulate[MptNode](BranchNode.numberOfChildren)(i =>
        at.get(i).fold[MptNode](NullNode)(h => HashNode(h.toArray))
      ),
      None
    )

  private val root = hash("root")
  private val left = hash("left")
  private val right = hash("right")
  private val hole = hash("hole")
  private val present: Map[ByteString, MptNode] = Map(
    root -> branch(3 -> left, 9 -> right),
    left -> LeafNode(ByteString(Array[Byte](1, 2)), ByteString("value")),
    right -> branch(5 -> hole)
  )

  "StorageHolePosition" should "find a hole by the reference to it, at its own position" taggedAs UnitTest in {
    StorageHolePosition.locate(account, root, hole, present.get) shouldBe Some(Seq(account, compact(9, 5)))
  }

  it should "give the empty path where the hole is the storage root" taggedAs UnitTest in {
    StorageHolePosition.locate(account, hole, hole, Map.empty[ByteString, MptNode].get) shouldBe Some(
      Seq(account, compact())
    )
  }

  it should "find nothing where nothing present refers to it" taggedAs UnitTest in {
    StorageHolePosition.locate(account, root, hash("elsewhere"), present.get) shouldBe None
  }

  it should "stop at its read limit" taggedAs UnitTest in {
    withClue("breadth first: the root, then `left`, and the reference to the hole is in `right`: ") {
      StorageHolePosition.locate(account, root, hole, present.get, maxReads = 2) shouldBe None
    }
    StorageHolePosition.locate(account, root, hole, present.get, maxReads = 3) shouldBe Some(
      Seq(account, compact(9, 5))
    )
  }

  "The pathset for a storage hole" should "be the exception's position where it has one" taggedAs UnitTest in {
    val location = compact(1, 2)
    StorageHolePosition.paths(
      Some(location),
      account,
      hole,
      fail("no walk is needed"),
      _ => fail("no read is needed")
    ) shouldBe
      Some(Seq(Seq(account, location)))
  }

  it should "be found by the walk where it has none" taggedAs UnitTest in {
    StorageHolePosition.paths(None, account, hole, Some(root), present.get) shouldBe Some(
      Seq(Seq(account, compact(9, 5)))
    )
  }

  it should "be none, not the storage root, where it cannot be found: that request only blames a peer" taggedAs UnitTest in {
    StorageHolePosition.paths(None, account, hash("elsewhere"), Some(root), present.get) shouldBe None
    StorageHolePosition.paths(None, account, hole, None, present.get) shouldBe None
  }
