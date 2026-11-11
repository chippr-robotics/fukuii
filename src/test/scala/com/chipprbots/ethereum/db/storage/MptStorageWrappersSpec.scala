package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2a (T041): the storages that wrap another one sit on [[MptStorageDecorator]], so what each adds
  * (a cache, a budget) applies to a read that names a position as it does to a read by hash, and to the view of one
  * trie (`forOwner`) as it does to the whole storage.
  */
class MptStorageWrappersSpec extends AnyFlatSpec with Matchers:

  /** Stored nodes as a storage returns them: decoded, with their encoding. */
  private def stored(fill: Int): MptNode =
    val node = LeafNode(ByteString(1, 2), ByteString(Array.fill[Byte](40)(fill.toByte)))
    MptTraversals.decodeNode(node.encode).withCachedHash(node.hash).withCachedRlpEncoded(node.encode)

  private val nodeA: MptNode = stored(1)
  private val nodeB: MptNode = stored(2)
  private val path: NibblePath = NibblePath(0xa)
  private val owner: Option[ByteString] = Some(ByteString(Array.fill[Byte](32)(7)))

  /** A storage that keeps tries apart and counts what reaches it. Every view shares the counts. */
  private class Counting(nodes: Map[ByteString, MptNode], val calls: mutable.ArrayBuffer[String], scope: String)
      extends MptStorage:
    def this(nodes: MptNode*) =
      this(nodes.map(n => ByteString(n.hash) -> n).toMap, mutable.ArrayBuffer.empty[String], "all")
    private def find(nodeId: Array[Byte]): MptNode = nodes(ByteString(nodeId))
    override def get(nodeId: Array[Byte]): MptNode =
      calls += s"$scope: get"; find(nodeId)
    override def getAt(at: NibblePath, nodeId: Array[Byte]): MptNode =
      calls += s"$scope: getAt(${at.show})"
      find(nodeId)
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      calls += s"$scope: updateNodesInStorage"
      newRoot
    override def updateNodesAt(
        newRoot: Option[MptNode],
        replaced: Seq[(NibblePath, MptNode)],
        absorbed: Seq[(NibblePath, MptNode)]
    ): Option[MptNode] =
      calls += s"$scope: updateNodesAt"
      newRoot
    override def persist(): Unit = calls += s"$scope: persist"
    override def forOwner(forWhom: Option[ByteString]): MptStorage =
      new Counting(nodes, calls, forWhom.fold("accounts")(_ => "one storage trie"))
    override def resetOwner(forWhom: ByteString): Unit = calls += s"$scope: resetOwner"
    override def storeRawNodes(raw: Seq[(ByteString, Array[Byte])]): Unit = calls += s"$scope: storeRawNodes"

  "CachingMptStorage" should "answer a positioned read from its cache, as it does a read by hash" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val wrapped = new Counting(nodeA)
    val caching = new CachingMptStorage(wrapped, DecodedNodeCache.withOwnBudget(1 << 20, strongValues = true))

    caching.getAt(path, nodeA.hash) shouldBe nodeA
    caching.getAt(path, nodeA.hash) shouldBe nodeA
    caching.get(nodeA.hash) shouldBe nodeA
    // the first read went to the wrapped storage, with its position; the other two were hits
    wrapped.calls.toSeq shouldBe Seq("all: getAt(a)")
  }

  it should "keep its cache in front of one trie's view" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Counting(nodeA, nodeB)
    val caching = new CachingMptStorage(wrapped, DecodedNodeCache.withOwnBudget(1 << 20, strongValues = true))

    val storageTrie = caching.forOwner(owner)
    storageTrie shouldBe a[CachingMptStorage]
    storageTrie.getAt(path, nodeA.hash) shouldBe nodeA
    // the same node asked for through another view: content is addressed by hash, so it is a hit
    caching.forOwner(None).getAt(path, nodeA.hash) shouldBe nodeA
    caching.forOwner(None).getAt(path, nodeB.hash) shouldBe nodeB
    wrapped.calls.toSeq shouldBe Seq("one storage trie: getAt(a)", "accounts: getAt(a)")
  }

  it should "pass updates, with their positions, and resets straight through" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Counting(nodeA)
    val caching = new CachingMptStorage(wrapped, DecodedNodeCache.withOwnBudget(1 << 20))

    caching.updateNodesAt(Some(nodeA), Seq(path -> nodeA), Nil) shouldBe Some(nodeA)
    caching.updateNodesInStorage(Some(nodeA), Seq(nodeA)) shouldBe Some(nodeA)
    caching.resetOwner(owner.get)
    caching.persist()
    wrapped.calls.toSeq shouldBe Seq(
      "all: updateNodesAt",
      "all: updateNodesInStorage",
      "all: resetOwner",
      "all: persist"
    )
  }

  // Beacon, C2a review: a prefetch reader for one trie must not start with a budget of its own, or each storage trie
  // of a block would warm the cache with a whole budget and the prefetch would never see itself exhausted.
  "PrefetchNodeReader" should "spend one budget across the views of every trie" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Counting(nodeA, nodeB)
    val cache = DecodedNodeCache.withOwnBudget(1 << 20, strongValues = true)
    val loaded = mutable.ArrayBuffer.empty[ByteString]
    // room for one node, by the cache's own weights
    val reader = new PrefetchNodeReader(wrapped, Some(cache), DecodedNodeCache.weigh(nodeA), loaded += _)

    reader.exhausted shouldBe false
    val accounts = reader.forOwner(None)
    val storageTrie = reader.forOwner(owner)
    accounts shouldBe a[PrefetchNodeReader]

    accounts.getAt(path, nodeA.hash) shouldBe nodeA
    reader.exhausted shouldBe true
    accounts.asInstanceOf[PrefetchNodeReader].exhausted shouldBe true
    storageTrie.asInstanceOf[PrefetchNodeReader].exhausted shouldBe true

    // read past the budget: still answered, counted, and not put in the cache
    storageTrie.getAt(path, nodeB.hash) shouldBe nodeB
    reader.nodesLoaded shouldBe 2
    loaded.toSeq shouldBe Seq(ByteString(nodeA.hash))
    cache.get(ByteString(nodeB.hash)) shouldBe null
    cache.get(ByteString(nodeA.hash)) shouldBe nodeA
    wrapped.calls.toSeq shouldBe Seq("accounts: getAt(a)", "one storage trie: getAt(a)")
  }

  it should "answer a positioned read from the cache without reading or counting" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Counting(nodeA)
    val cache = DecodedNodeCache.withOwnBudget(1 << 20, strongValues = true)
    val reader = new PrefetchNodeReader(wrapped, Some(cache), 1 << 20)

    reader.getAt(path, nodeA.hash) shouldBe nodeA
    reader.getAt(path, nodeA.hash) shouldBe nodeA
    reader.get(nodeA.hash) shouldBe nodeA
    reader.nodesLoaded shouldBe 1
    wrapped.calls.toSeq shouldBe Seq("all: getAt(a)")
  }

  it should "stay read-only, through every member that writes" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Counting(nodeA)
    val reader = new PrefetchNodeReader(wrapped, None, 1 << 20)

    an[UnsupportedOperationException] should be thrownBy reader.updateNodesInStorage(Some(nodeA), Nil)
    an[UnsupportedOperationException] should be thrownBy reader.updateNodesAt(Some(nodeA), Nil, Nil)
    an[UnsupportedOperationException] should be thrownBy reader.resetOwner(owner.get)
    an[UnsupportedOperationException] should be thrownBy reader.storeRawNodes(Nil)
    an[UnsupportedOperationException] should be thrownBy reader.forOwner(owner).updateNodesAt(Some(nodeA), Nil, Nil)
    reader.persist()
    wrapped.calls.toSeq shouldBe empty
  }
