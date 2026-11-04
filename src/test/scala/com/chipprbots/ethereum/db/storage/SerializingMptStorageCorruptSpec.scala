package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceException
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.ReferenceCountNodeStorage.StoredNode
import com.chipprbots.ethereum.db.storage.encoding.*
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.*

/** A stored entry that is not the node its key names reads as a missing node, never as a decode error (spec 022 T062).
  *
  * The three classes are the ones the T062 run injected on Mordor: 0x01 (no RLP list, "Invalid Node"), 0xc1 (a list
  * header with nothing behind it, an out-of-bounds read) and 0x80 (decodes, to an empty value, and served a balance of
  * 0 over RPC with no error). Each is tried raw (archive) and in basic pruning's reference-count wrapper.
  */
class SerializingMptStorageCorruptSpec extends AnyFlatSpec with Matchers:

  private val leaf: Array[Byte] =
    MptTraversals.encodeNode(LeafNode(ByteString(Array.fill[Byte](4)(3)), ByteString(Array.fill[Byte](40)(4))))
  private val leafHash: NodeHash = ByteString(kec256(leaf))

  private val classes: Seq[(String, Array[Byte])] =
    Seq("invalid-node" -> Array(0x01.toByte), "out-of-bounds" -> Array(0xc1.toByte), "null-node" -> Array(0x80.toByte))

  /** Raw bytes under their key, as archive pruning keeps them. */
  private class Archive:
    val nodes: NodeStorage = new NodeStorage(EphemDataSource())
    val store: SerializingMptStorage = StateStorage.mptStorageFromNodeStorage(nodes)

  /** Basic pruning: each node in a `[bytes, references, lastUsedBy]` wrapper. */
  private class Basic:
    val nodes: NodeStorage = new NodeStorage(EphemDataSource())
    val state: ReferenceCountedStateStorage = new ReferenceCountedStateStorage(nodes, 64, 1024L * 1024)
    def wrap(bytes: Array[Byte]): Array[Byte] = storedNodeToBytes(StoredNode(ByteString(bytes), 1, 1))

  private def corruptAt(store: MptStorage, hash: NodeHash): CorruptNodeException =
    val thrown = intercept[CorruptNodeException](store.get(hash.toArray))
    thrown.hash shouldBe hash
    thrown

  "SerializingMptStorage.get" should "read each corrupt class stored raw under a node's key as a corrupt node" taggedAs (
    UnitTest,
    StateTest
  ) in {
    classes.foreach { case (name, bytes) =>
      withClue(s"$name: ") {
        val arc = new Archive
        arc.nodes.put(leafHash, bytes)
        corruptAt(arc.store, leafHash) shouldBe a[MissingNodeException]
      }
    }
  }

  it should "read each corrupt class inside basic pruning's wrapper as a corrupt node, through every store" taggedAs (
    UnitTest,
    StateTest
  ) in {
    classes.foreach { case (name, bytes) =>
      withClue(s"$name: ") {
        val b = new Basic
        b.nodes.put(leafHash, b.wrap(bytes))
        val backing = b.state.getBackingStorage(0).asInstanceOf[SerializingMptStorage]
        Seq(backing, backing.cachedForExecution, b.state.getReadOnlyStorage, b.state.stageBlock(1).storage)
          .foreach(corruptAt(_, leafHash))
        withClue("the decoded-node cache keeps nothing from it: ") {
          backing.executionCache.get.get(leafHash) shouldBe null
        }
      }
    }
  }

  it should "read a wrapper that does not unwrap as a corrupt node" taggedAs (UnitTest, StateTest) in {
    val b = new Basic
    b.nodes.put(leafHash, Array[Byte](1, 2, 3)) // not a [bytes, refs, lastUsed] list
    corruptAt(b.state.getBackingStorage(0), leafHash).detail should not be empty
  }

  it should "still decode the empty trie's root, the one bare value stored under its own hash" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val arc = new Archive
    arc.nodes.put(emptyRoot, MerklePatriciaTrie.EmptyEncoded)
    arc.store.get(emptyRoot.toArray) shouldBe NullNode
    val b = new Basic
    b.nodes.put(emptyRoot, b.wrap(MerklePatriciaTrie.EmptyEncoded))
    b.state.getBackingStorage(0).get(emptyRoot.toArray) shouldBe NullNode
  }

  it should "decode a good node, and say an absent one is missing, not corrupt" taggedAs (UnitTest, StateTest) in {
    val arc = new Archive
    arc.nodes.put(leafHash, leaf)
    arc.store.get(leafHash.toArray).hash.toSeq shouldBe leafHash.toSeq
    val absent = ByteString(kec256(Array[Byte](7, 7, 7)))
    val missing = intercept[MissingNodeException](arc.store.get(absent.toArray))
    missing shouldBe a[MissingRootNodeException]
    missing.isInstanceOf[CorruptNodeException] shouldBe false
  }

  it should "let a failing database through as it is: a disk error is never called corrupt" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val failures: Seq[RuntimeException] = Seq(
      RocksDbDataSourceException("read failed", new RuntimeException("io")),
      RocksDbDataSourceClosedException("closed"),
      new MissingNodeException(leafHash)
    )
    failures.foreach { failure =>
      val failing = new NodesKeyValueStorage:
        override def get(key: NodeHash): Option[NodeEncoded] = throw failure
        override def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, NodeEncoded)]): NodesKeyValueStorage =
          this
        override def persist(): Unit = ()
      withClue(s"${failure.getClass.getSimpleName}: ") {
        val thrown = intercept[RuntimeException](new SerializingMptStorage(failing).get(leafHash.toArray))
        (thrown should be).theSameInstanceAs(failure)
      }
    }
  }

  it should "keep the corrupt type through the trie's walk, with the position it was met at" taggedAs (
    UnitTest,
    StateTest
  ) in {
    import MerklePatriciaTrie.defaultByteArraySerializable
    val arc = new Archive
    arc.nodes.put(leafHash, Array(0x01.toByte))
    val trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](leafHash.toArray, arc.store)
    val thrown = intercept[CorruptNodeException](trie.get(Array[Byte](1, 2)))
    thrown.hash shouldBe leafHash
    thrown.location shouldBe Some(ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false)))
  }

  it should "log a corrupt hash once, and forget the least recently met past its bound" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val first = ByteString(kec256("logged once".getBytes))
    SerializingMptStorage.firstReport(first) shouldBe true
    SerializingMptStorage.firstReport(first) shouldBe false
    (0 until SerializingMptStorage.MaxLoggedCorrupt).foreach(i =>
      SerializingMptStorage.firstReport(ByteString(kec256(s"other $i".getBytes)))
    )
    withClue("bounded, so the first one is forgotten and would log again: ") {
      SerializingMptStorage.firstReport(first) shouldBe true
    }
  }

  "PrefetchNodeReader" should "fail a corrupt node's read as a hint, caching and counting nothing" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val arc = new Archive
    arc.nodes.put(leafHash, Array(0x80.toByte))
    val cache = DecodedNodeCache.withOwnBudget(1024L * 1024)
    val reader = new PrefetchNodeReader(arc.store, Some(cache), 1024L * 1024)
    corruptAt(reader, leafHash)
    cache.entries shouldBe 0
    reader.nodesLoaded shouldBe 0L
  }
