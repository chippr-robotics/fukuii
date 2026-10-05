package com.chipprbots.ethereum.db.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.testing.Tags.*

/** Task #74: pruning deletes a node and then evicts it from the decoded-node cache; a reader that loaded the node
  * before the delete must not be able to insert it after the eviction. The interleaving is forced with latches, no
  * sleeps.
  */
class DecodedNodeCacheRaceSpec extends AnyFlatSpec with Matchers:

  private val hash = ByteString(Array.fill(32)(7.toByte))
  private val node: MptNode = LeafNode(ByteString(1), ByteString(2))

  /** A storage holding one node that can be "pruned"; `get` pauses after reading, before returning, when armed. */
  final private class PausingStorage extends MptStorage:
    @volatile var present = true
    val readDone = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    val pause = new AtomicBoolean(true)
    override def get(nodeId: Array[Byte]): MptNode =
      if !present then throw new MissingNodeException(ByteString(nodeId))
      val result = node
      if pause.compareAndSet(true, false) then
        readDone.countDown()
        proceed.await(10, TimeUnit.SECONDS) shouldBe true
      result
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] = None
    override def persist(): Unit = ()

  private def interleave(read: () => Unit, storage: PausingStorage, cache: DecodedNodeCache): Unit =
    val reader = new Thread(() => read())
    reader.start()
    storage.readDone.await(10, TimeUnit.SECONDS) shouldBe true // the reader holds the node, pre-delete
    storage.present = false // pruning deletes ...
    cache.evict(Seq(hash)) // ... and evicts
    storage.proceed.countDown() // the reader now inserts what it read
    reader.join(10000)
    reader.isAlive shouldBe false

  "DecodedNodeCache" should "not let an execution read that raced a prune re-insert the pruned node" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val cache = DecodedNodeCache.withOwnBudget(1024L * 1024)
    val storage = new PausingStorage
    val cached = new CachingMptStorage(storage, cache)
    interleave(() => cached.get(hash.toArray), storage, cache)
    cache.get(hash) shouldBe null
    // The ghost would have masked this: a later read must see the node as missing.
    a[MissingNodeException] should be thrownBy cached.get(hash.toArray)
  }

  it should "not let a prefetch read that raced a prune re-insert the pruned node" taggedAs (UnitTest, StateTest) in {
    val cache = DecodedNodeCache.withOwnBudget(1024L * 1024)
    val storage = new PausingStorage
    val prefetch = new PrefetchNodeReader(storage, Some(cache), 1024L * 1024)
    interleave(() => prefetch.get(hash.toArray), storage, cache)
    cache.get(hash) shouldBe null
  }

  it should "still cache a node read with no eviction in between" taggedAs (UnitTest, StateTest) in {
    val cache = DecodedNodeCache.withOwnBudget(1024L * 1024)
    val storage = new PausingStorage
    storage.pause.set(false)
    new CachingMptStorage(storage, cache).get(hash.toArray)
    cache.get(hash) should not be null
  }
