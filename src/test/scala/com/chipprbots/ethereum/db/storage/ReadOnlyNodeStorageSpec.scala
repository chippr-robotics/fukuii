package com.chipprbots.ethereum.db.storage

import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.FiniteDuration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.cache.Cache
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.cache.MapCache
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.NodeCacheConfig

class ReadOnlyNodeStorageSpec extends AnyFlatSpec with Matchers:

  "ReadOnlyNodeStorage" should "not update dataSource" taggedAs (UnitTest, DatabaseTest) in new TestSetup:
    val readOnlyNodeStorage = archiveStateStorage.getReadOnlyStorage
    readOnlyNodeStorage.updateNodesInStorage(Some(newLeaf), Nil)
    dataSource.storage.size shouldEqual 0

  it should "be able to persist to underlying storage when needed" taggedAs (UnitTest, DatabaseTest) in new TestSetup:
    val (nodeKey, _) = MptStorage.collapseNode(Some(newLeaf))._2.head
    val readOnlyNodeStorage = archiveStateStorage.getReadOnlyStorage

    readOnlyNodeStorage.updateNodesInStorage(Some(newLeaf), Nil)

    val previousSize = dataSource.storage.size
    readOnlyNodeStorage.get(nodeKey.toArray[Byte]) shouldEqual newLeaf

    previousSize shouldEqual 0

    readOnlyNodeStorage.persist()

    dataSource.storage.size shouldEqual 1

  it should "be able to persist to underlying storage when Genesis loading" in new TestSetup:
    val (nodeKey, _) = MptStorage.collapseNode(Some(newLeaf))._2.head
    val readOnlyNodeStorage = cachedStateStorage.getReadOnlyStorage

    readOnlyNodeStorage.updateNodesInStorage(Some(newLeaf), Nil)

    val previousSize = dataSource.storage.size
    readOnlyNodeStorage.get(nodeKey.toArray[Byte]) shouldEqual newLeaf

    previousSize shouldEqual 0

    readOnlyNodeStorage.persist()

    cachedStateStorage.forcePersist(GenesisDataLoad) shouldEqual true
    dataSource.storage.size shouldEqual 1

  it should "keep a node readable after a later update names it for removal (content-addressed, #1607)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new TestSetup:
    val keyA = ByteString("node-a").toArray[Byte]
    val keyB = ByteString("node-b").toArray[Byte]
    val readOnlyNodeStorage = ReadOnlyNodeStorage(new ArchiveNodeStorage(nodeStorage))

    readOnlyNodeStorage.update(Nil, Seq(ByteString(keyA) -> Array[Byte](1)))
    // root A -> root B: the replaced node A is listed for removal, but an earlier root may still reference it
    readOnlyNodeStorage.update(Seq(ByteString(keyA)), Seq(ByteString(keyB) -> Array[Byte](2)))

    readOnlyNodeStorage.get(ByteString(keyA)).map(_.toSeq) shouldEqual Some(Seq[Byte](1))
    readOnlyNodeStorage.get(ByteString(keyB)).map(_.toSeq) shouldEqual Some(Seq[Byte](2))
    dataSource.storage.size shouldEqual 0

  trait TestSetup:
    val newLeaf: LeafNode = LeafNode(ByteString(1), ByteString(1))
    val dataSource: EphemDataSource = EphemDataSource()
    val (archiveStateStorage, nodeStorage, cachedStorage) = StateStorage.createTestStateStorage(dataSource)

    object TestCacheConfig extends NodeCacheConfig:
      override val maxSize: Long = 100
      override val maxHoldTime: FiniteDuration = FiniteDuration(10, TimeUnit.MINUTES)
    val lruCache = new LruCache[NodeHash, HeapEntry](TestCacheConfig)
    val newNodeStorage = new NodeStorage(dataSource)
    val testCache: Cache[NodeHash, NodeEncoded] = MapCache.createTestCache[NodeHash, NodeEncoded](10)
    val newCachedNodeStorage = new CachedNodeStorage(newNodeStorage, testCache)

    val cachedStateStorage: StateStorage = StateStorage(InMemoryPruning(10), newNodeStorage, lruCache)
