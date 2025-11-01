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
import com.chipprbots.ethereum.utils.Config.NodeCacheConfig

class ReadOnlyNodeStorageSpec extends AnyFlatSpec with Matchers {

  "ReadOnlyNodeStorage" should "not update dataSource" in new TestSetup {
    val readOnlyNodeStorage = archiveStateStorage.getReadOnlyStorage
    readOnlyNodeStorage.updateNodesInStorage(Some(newLeaf), Nil)
    dataSource.storage.size shouldEqual 0
  }

  it should "be able to persist to underlying storage when needed" in new TestSetup {
    val (nodeKey, _) = MptStorage.collapseNode(Some(newLeaf))._2.head
    val readOnlyNodeStorage = archiveStateStorage.getReadOnlyStorage

    readOnlyNodeStorage.updateNodesInStorage(Some(newLeaf), Nil)

    val previousSize = dataSource.storage.size
    readOnlyNodeStorage.get(nodeKey.toArray[Byte]) shouldEqual newLeaf

    previousSize shouldEqual 0

    readOnlyNodeStorage.persist()

    dataSource.storage.size shouldEqual 1
  }

  it should "be able to persist to underlying storage when Genesis loading" in new TestSetup {
    val (nodeKey, _) = MptStorage.collapseNode(Some(newLeaf))._2.head
    val readOnlyNodeStorage = cachedStateStorage.getReadOnlyStorage

    readOnlyNodeStorage.updateNodesInStorage(Some(newLeaf), Nil)

    val previousSize = dataSource.storage.size
    readOnlyNodeStorage.get(nodeKey.toArray[Byte]) shouldEqual newLeaf

    previousSize shouldEqual 0

    readOnlyNodeStorage.persist()

    cachedStateStorage.forcePersist(GenesisDataLoad) shouldEqual true
    dataSource.storage.size shouldEqual 1
  }

  trait TestSetup {
    val newLeaf: LeafNode = LeafNode(ByteString(1), ByteString(1))
    val dataSource: EphemDataSource = EphemDataSource()
    val (archiveStateStorage, nodeStorage, cachedStorage) = StateStorage.createTestStateStorage(dataSource)

    object TestCacheConfig extends NodeCacheConfig {
      override val maxSize: Long = 100
      override val maxHoldTime: FiniteDuration = FiniteDuration(10, TimeUnit.MINUTES)
    }
    val lruCache = new LruCache[NodeHash, HeapEntry](TestCacheConfig)
    val newNodeStorage = new NodeStorage(dataSource)
    val testCache: Cache[NodeHash, NodeEncoded] = MapCache.createTestCache[NodeHash, NodeEncoded](10)
    val newCachedNodeStorage = new CachedNodeStorage(newNodeStorage, testCache)

    val cachedStateStorage: StateStorage = StateStorage(InMemoryPruning(10), newNodeStorage, lruCache)
  }
}
