package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.MptStorageDecorator
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.testing.Tags.*

/** B-075: a node the store cannot read when a trie writes is thrown with its position in the trie, as a failed read is,
  * so that the importer's fetch of a replacement names where the node sits.
  */
class WriteFaultLocationSpec extends AnyFlatSpec with Matchers:

  private def big(fill: Int): Array[Byte] = Array.fill[Byte](40)(fill.toByte)
  private def key(nibbles: Int*): Array[Byte] = HexPrefix.nibblesToBytes(nibbles.map(_.toByte).toArray)

  /** A store that fails the replacement of one of the nodes an update names, and says which and where it sits. */
  final private class FailingWrites(
      val underlying: MptStorage,
      pick: Seq[(NibblePath, MptNode)] => (NibblePath, MptNode),
      thrownHash: Option[ByteString] = None
  ) extends MptStorageDecorator:
    var failedAt: Option[ByteString] = None
    var failedHash: Option[ByteString] = None
    protected def decorate(scoped: MptStorage): MptStorage = scoped
    override protected def update(newRoot: Option[MptNode], removals: MptStorageDecorator.Removals): Option[MptNode] =
      removals match
        case MptStorageDecorator.Removals.Positioned(replaced, _) if replaced.nonEmpty =>
          val (path, node) = pick(replaced)
          failedAt = Some(ByteString(path.toHexPrefix))
          failedHash = Some(ByteString(node.hash))
          throw new CorruptNodeException(thrownHash.getOrElse(ByteString(node.hash)), "wrapper does not unwrap")
        case _ => super.update(newRoot, removals)

  private def triePlus(
      pick: Seq[(NibblePath, MptNode)] => (NibblePath, MptNode),
      thrownHash: Option[ByteString] = None
  ): (FailingWrites, MerklePatriciaTrie[Array[Byte], Array[Byte]]) =
    val base = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    var trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](base)
    Seq(Seq(0, 0), Seq(0, 1), Seq(1, 0)).zipWithIndex.foreach { case (nibbles, i) =>
      trie = trie.put(key(nibbles*), big(i + 1))
    }
    val failing = new FailingWrites(base, pick, thrownHash)
    (failing, MerklePatriciaTrie[Array[Byte], Array[Byte]](trie.getRootHash, failing))

  "A trie write that meets a corrupt node" should "say where the node sits, for a put" taggedAs (UnitTest, MPTTest) in {
    val (failing, trie) = triePlus(_.last)

    val thrown = intercept[CorruptNodeException](trie.put(key(0, 2), big(9)))

    failing.failedAt shouldBe defined
    thrown.location shouldBe failing.failedAt
    ByteString(thrown.hash.toArray) shouldBe failing.failedHash.get
  }

  it should "say where the root sits when the root is the node" taggedAs (UnitTest, MPTTest) in {
    val (failing, trie) = triePlus(_.head)

    val thrown = intercept[MissingNodeException](trie.put(key(1, 1), big(9)))

    thrown.location shouldBe Some(ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false)))
    thrown.location shouldBe failing.failedAt
  }

  it should "say where the node sits, for a remove" taggedAs (UnitTest, MPTTest) in {
    val (failing, trie) = triePlus(_.last)

    val thrown = intercept[MissingNodeException](trie.remove(key(1, 0)))

    thrown.location shouldBe failing.failedAt
    thrown.location shouldBe defined
  }

  it should "leave a node the update does not replace as it was thrown" taggedAs (UnitTest, MPTTest) in {
    val (_, trie) = triePlus(_.last, Some(ByteString(Array.fill[Byte](32)(7))))
    val thrown = intercept[CorruptNodeException](trie.put(key(0, 2), big(9)))
    thrown.location shouldBe None
  }
