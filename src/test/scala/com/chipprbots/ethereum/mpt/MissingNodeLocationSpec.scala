package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.MptStorageDecorator
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.Unplaced
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.testing.Tags.*

/** Where a missing node says it is (spec 018 C5b): the trie's traversal puts the position of the node on a fault,
  * except on one that was thrown [[Unplaced]], by a view with layers over the rows, which has no row to name.
  */
class MissingNodeLocationSpec extends AnyFlatSpec with Matchers:

  private val hash = ByteString(Array.fill[Byte](32)(9))
  private val root = Array.fill[Byte](32)(1)

  private def storageThrowing(thrown: => MissingNodeException): MptStorage =
    new MptStorageDecorator:
      override protected def underlying: MptStorage = null
      override protected def decorate(scoped: MptStorage): MptStorage = this
      override protected def read(nodeId: Array[Byte], path: NibblePath | Null): MptNode = throw thrown

  private def faultOf(storage: MptStorage): MissingNodeException =
    val trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](root, storage)
    intercept[MissingNodeException](trie.get(Array[Byte](1, 2)))

  "A traversal" should "give a missing node the position it was met at" taggedAs UnitTest in {
    faultOf(storageThrowing(new MissingNodeException(hash))).location shouldBe defined
  }

  it should "leave a missing node that is Unplaced with no position, and keep it Unplaced" taggedAs UnitTest in {
    val fault = faultOf(storageThrowing(new MissingNodeException(hash) with Unplaced))
    fault.location shouldBe None
    fault shouldBe a[Unplaced]
  }

  "located" should "leave an Unplaced fault as it is, and give any other its position" taggedAs UnitTest in {
    val at = ByteString(0x00)
    val unplaced = new MissingNodeException(hash) with Unplaced
    (MerklePatriciaTrie.located(unplaced, at) should be).theSameInstanceAs(unplaced)
    MerklePatriciaTrie.located(new MissingNodeException(hash), at).location shouldBe Some(at)
  }
