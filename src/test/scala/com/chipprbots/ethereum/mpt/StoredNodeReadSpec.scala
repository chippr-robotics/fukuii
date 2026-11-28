package com.chipprbots.ethereum.mpt

import java.util.Arrays

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.MptStorageDecorator
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.testing.Tags.*

/** A read takes the node the storage decoded as it is (B-204): it does not encode and decode it again, it knows it by
  * the hash it was asked for, and a store that answers with a hash reference is a corrupt entry, not an endless walk.
  */
class StoredNodeReadSpec extends AnyFlatSpec with Matchers:

  private type Trie = MerklePatriciaTrie[Array[Byte], Array[Byte]]

  private def key(i: Int): Array[Byte] = kec256(Array[Byte]((i >> 8).toByte, i.toByte))
  private def value(i: Int): Array[Byte] = Array.tabulate(70)(j => (i + j).toByte)
  private val Size = 300

  /** A hash-keyed store holding a trie of [[Size]] keys, and that trie's root. */
  private def stored(): (MptStorage, Array[Byte]) =
    val storage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    val trie = (0 until Size).foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](storage): Trie) { (t, i) =>
      t.put(key(i), value(i))
    }
    (storage, trie.getRootHash)

  /** `storage`, with each node it hands over passed through `answer` and recorded. */
  final private class Answering(protected val underlying: MptStorage, answer: (Array[Byte], MptNode) => MptNode)
      extends MptStorageDecorator:
    val handedOver: mutable.ArrayBuffer[MptNode] = mutable.ArrayBuffer.empty
    override protected def decorate(scoped: MptStorage): MptStorage = new Answering(scoped, answer)
    override protected def read(nodeId: Array[Byte], path: NibblePath | Null): MptNode =
      val node = answer(nodeId, super.read(nodeId, path))
      handedOver += node
      node

  "A read" should "hand over the node the storage decoded, the same instance, and find every value" taggedAs UnitTest in {
    val (storage, root) = stored()
    val answering = new Answering(storage, (_, node) => node)
    val trie: Trie = MerklePatriciaTrie(root, answering)
    (0 until Size).foreach(i => trie.get(key(i)).map(_.toSeq) shouldBe Some(value(i).toSeq))
    val proof = trie.getProof(key(7)).get
    proof should not be empty
    // Every node of the proof is one the storage handed over, not a copy decoded again from its encoding.
    proof.foreach(node => answering.handedOver.exists(_ eq node) shouldBe true)
  }

  it should "give a proof whose nodes are the stored bytes, each hashing to the hash it is known by" taggedAs UnitTest in {
    val (storage, root) = stored()
    val trie: Trie = MerklePatriciaTrie(root, storage)
    val proof = trie.getProof(key(11)).get
    Arrays.equals(proof.head.hash, root) shouldBe true
    proof.foreach { node =>
      Arrays.equals(kec256(node.encode), node.hash) shouldBe true
      Arrays.equals(storage.get(node.hash).encode, node.encode) shouldBe true
    }
  }

  it should "know a node by the hash it was asked for when the storage did not cache one" taggedAs UnitTest in {
    val (storage, root) = stored()
    // A storage that decodes the node's bytes and caches neither hash nor encoding.
    val bare = new Answering(storage, (_, node) => MptTraversals.decodeNode(node.encode))
    val trie: Trie = MerklePatriciaTrie(root, bare)
    val proof = trie.getProof(key(3)).get
    proof.head.cachedHash.map(_.toSeq) shouldBe Some(root.toSeq)
    proof.foreach(node => node.cachedHash shouldBe defined)
    trie.get(key(3)).map(_.toSeq) shouldBe Some(value(3).toSeq)
  }

  it should "refuse a hash reference the store answers with, as a corrupt node at its position" taggedAs UnitTest in {
    val (storage, root) = stored()
    val referring = new Answering(storage, (nodeId, _) => HashNode(nodeId))
    val trie: Trie = MerklePatriciaTrie(root, referring)
    val fault = intercept[CorruptNodeException](trie.get(key(1)))
    fault.hash shouldBe ByteString(root)
    fault.location shouldBe defined
  }
