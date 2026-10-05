package com.chipprbots.ethereum.ledger

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.common.SimpleMap
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.CachingMptStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.byteStringSerializer
import com.chipprbots.ethereum.testing.Tags.*

/** The read caches of the block-import path: base-trie read memos ([[TrieReadMemo]]), the decoded-node cache and the
  * execution-side code cache. Each is memoisation of something immutable under its key; these specs pin where that
  * stops being true (a write, a new root, a pruned node, an absent entry).
  */
class StateReadCachesSpec extends AnyFlatSpec with Matchers:

  /** A SimpleMap that counts `get`s and can be told to fail a key once, standing in for a trie. */
  final class CountingMap(val data: Map[Int, Int], val reads: AtomicInteger, failOnce: Set[Int] = Set.empty)
      extends SimpleMap[Int, Int, CountingMap]:
    private val failed = scala.collection.mutable.Set.empty[Int]
    override def get(key: Int): Option[Int] =
      reads.incrementAndGet()
      if failOnce.contains(key) && failed.add(key) then throw new RuntimeException(s"missing node for $key")
      data.get(key)
    override def update(toRemove: Seq[Int], toUpsert: Seq[(Int, Int)]): CountingMap =
      new CountingMap((data -- toRemove) ++ toUpsert, reads, failOnce)

  private def memo(entries: Int = 1000) = new TrieReadMemo[Int, Int](new ReadMemoBudget(entries))

  "InMemorySimpleMapProxy read memo" should "read the base trie once per key, absent keys included" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val reads = new AtomicInteger
    val proxy = InMemorySimpleMapProxy.wrap[Int, Int, CountingMap](new CountingMap(Map(1 -> 10), reads), memo())
    (1 to 5).foreach(_ => proxy.get(1) shouldBe Some(10))
    (1 to 5).foreach(_ => proxy.get(2) shouldBe None)
    reads.get shouldBe 2
  }

  it should "let the uncommitted overlay win, and show the base again after rollback" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val reads = new AtomicInteger
    val base = InMemorySimpleMapProxy.wrap[Int, Int, CountingMap](new CountingMap(Map(1 -> 10), reads), memo())
    base.get(1) shouldBe Some(10) // memoised
    val written = base.put(1, 11)
    written.get(1) shouldBe Some(11)
    written.remove(1).get(1) shouldBe None
    written.rollback.get(1) shouldBe Some(10)
    base.get(1) shouldBe Some(10) // the original proxy is unaffected by its descendants
  }

  it should "forget everything on persist: a new trie is a new root" taggedAs (UnitTest, StateTest) in {
    val reads = new AtomicInteger
    val base = InMemorySimpleMapProxy.wrap[Int, Int, CountingMap](new CountingMap(Map(1 -> 10), reads), memo())
    base.get(1) shouldBe Some(10)
    base.get(3) shouldBe None
    val persisted = base.put(1, 11).put(3, 33).remove(2).persist()
    persisted.get(1) shouldBe Some(11)
    persisted.get(3) shouldBe Some(33)
    persisted.get(1) shouldBe Some(11)
    // the pre-persist answers (10, None) were not served for the new trie, and the old proxy still answers its own
    base.get(1) shouldBe Some(10)
    base.get(3) shouldBe None
  }

  it should "never memoise a read that failed" taggedAs (UnitTest, StateTest) in {
    val reads = new AtomicInteger
    val proxy = InMemorySimpleMapProxy.wrap[Int, Int, CountingMap](
      new CountingMap(Map(7 -> 70), reads, failOnce = Set(7)),
      memo()
    )
    an[RuntimeException] should be thrownBy proxy.get(7)
    proxy.get(7) shouldBe Some(70)
  }

  it should "stay correct, just uncached, once its entry budget is spent" taggedAs (UnitTest, StateTest) in {
    val reads = new AtomicInteger
    val proxy = InMemorySimpleMapProxy.wrap[Int, Int, CountingMap](
      new CountingMap(Map(1 -> 10, 2 -> 20), reads),
      new TrieReadMemo[Int, Int](new ReadMemoBudget(1))
    )
    proxy.get(1) shouldBe Some(10) // takes the only entry
    (1 to 3).foreach(_ => proxy.get(2) shouldBe Some(20)) // not memoised, still right
    reads.get shouldBe 4
    proxy.get(1) shouldBe Some(10)
    reads.get shouldBe 4
  }

  "WorldReadMemos" should "scope a storage memo to its trie root" taggedAs (UnitTest, StateTest) in {
    val memos = new WorldReadMemos(new ReadMemoBudget(100))
    val rootA = kec256(ByteString("a"))
    val rootB = kec256(ByteString("b"))
    (memos.storageFor(rootA) should be).theSameInstanceAs(memos.storageFor(rootA))
    memos.storageFor(rootA) should not be theSameInstanceAs(memos.storageFor(rootB))
  }

  // ---- decoded node cache --------------------------------------------------------------------------------------

  private val history = 4
  private val keyCount = 120
  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String, i: Int): ByteString = ByteString(s"$tag-$i")

  class Chain:
    val dataSource: EphemDataSource = EphemDataSource()
    val stateStorage = new ReferenceCountedStateStorage(new NodeStorage(dataSource), history, 8L * 1024 * 1024)
    def cachedStorage(bn: Int): MptStorage =
      stateStorage.getBackingStorage(bn).asInstanceOf[SerializingMptStorage].cachedForExecution

    def execute(bn: Int, parentRoot: Option[ByteString], touched: Seq[Int], tag: String): ByteString =
      val storage = stateStorage.getBackingStorage(bn)
      val start = parentRoot.fold(MerklePatriciaTrie[ByteString, ByteString](storage))(r =>
        MerklePatriciaTrie[ByteString, ByteString](r.toArray, storage)
      )
      ByteString(touched.foldLeft(start)((t, i) => t.put(key(i), value(tag, i))).getRootHash)

    def readAll(root: ByteString, storage: MptStorage): Unit =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, storage)
      (0 until keyCount).foreach(i => trie.get(key(i)))

  "DecodedNodeCache" should "serve a node from memory the second time and never swallow a missing node" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain:
    val root = execute(0, None, 0 until keyCount, "g")
    val reads = new AtomicInteger
    val counting = new MptStorage:
      private val real = stateStorage.getBackingStorage(0)
      override def get(nodeId: Array[Byte]): MptNode =
        reads.incrementAndGet(); real.get(nodeId)
      override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]) =
        real.updateNodesInStorage(newRoot, toRemove)
      override def persist(): Unit = real.persist()
    val cache = DecodedNodeCache.withOwnBudget(8L * 1024 * 1024)
    val cached = new CachingMptStorage(counting, cache)
    readAll(root, cached)
    val firstPass = reads.get
    firstPass should be > 0
    readAll(root, cached)
    reads.get shouldBe firstPass // the second pass is entirely from the cache

    val absent = kec256(ByteString("no such node"))
    a[MissingNodeException] should be thrownBy cached.get(absent.toArray)
    a[MissingNodeException] should be thrownBy cached.get(absent.toArray) // not remembered as anything

  it should "not outlive pruning: a node pruned from the database is a missing node, cache or not" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain:
    var roots = Map(0 -> execute(0, None, 0 until keyCount, "g"))
    // Populate the cache with every node of block 1's state while it is still whole.
    roots += 1 -> execute(1, Some(roots(0)), (0 until 60).toSeq, "c")
    stateStorage.onBlockSave(1, 0)(() => ())
    readAll(roots(1), cachedStorage(1))
    (2 to 14).foreach { bn =>
      roots += bn -> execute(bn, Some(roots(bn - 1)), (0 until 60).map(j => (bn * 13 + j * 7) % keyCount), s"c$bn")
      stateStorage.onBlockSave(bn, bn - 1)(() => ())
    }
    // Precondition: with the history window long gone, block 1's replaced nodes are really pruned from the database.
    a[MissingNodeException] should be thrownBy readAll(roots(1), stateStorage.getReadOnlyStorage)
    // The execution cache must say the same, not resurrect the nodes it decoded earlier.
    a[MissingNodeException] should be thrownBy readAll(roots(1), cachedStorage(15))
    // And the live head is unaffected, through the cache as well.
    noException should be thrownBy readAll(roots(14), cachedStorage(15))

  it should "be reached only through cachedForExecution: the plain storage reads what is on disk" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain:
    val root = execute(0, None, 0 until keyCount, "g")
    readAll(root, cachedStorage(0)) // fills the cache
    // Remove a node from the database behind the cache's back (a stand-in for anything that deletes nodes).
    val victim = dataSource.storage.keys.find(k => k.array().endsWith(root.toArray)).get
    dataSource.storage = dataSource.storage - victim
    // The plain handle (what healing and presence checks use) sees the hole exactly as before...
    a[MissingNodeException] should be thrownBy readAll(root, stateStorage.getBackingStorage(0))

  // ---- code cache ------------------------------------------------------------------------------------------------

  "EvmCodeStorage.getForExecution" should "never remember an absent code as absent" taggedAs (UnitTest, StateTest) in {
    val storage = new EvmCodeStorage(EphemDataSource())
    val code = ByteString(Array.fill(100)(0x5b.toByte))
    val hash = kec256(code)
    storage.getForExecution(hash) shouldBe None
    storage.getForExecution(hash) shouldBe None
    storage.put(hash, code).commit()
    storage.getForExecution(hash) shouldBe Some(code)
    storage.getForExecution(hash) shouldBe Some(code)
    storage.get(hash) shouldBe Some(code)
    // Deleting code through the storage is seen by the next execution read: a block that needs it must find it missing.
    storage.remove(hash).commit()
    storage.getForExecution(hash) shouldBe None
  }

  it should "not let one storage's cached code answer for another database that lacks it" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val withCode = new EvmCodeStorage(EphemDataSource())
    val without = new EvmCodeStorage(EphemDataSource())
    val code = ByteString(Array.fill(100)(0x5b.toByte))
    val hash = kec256(code)
    withCode.put(hash, code).commit()
    withCode.getForExecution(hash) shouldBe Some(code) // cached in the process-wide cache
    without.getForExecution(hash) shouldBe None // a different database: still missing
  }

  "JUMPDEST block memo" should "belong to one world, shared by its copies and by no other world" taggedAs (
    UnitTest,
    StateTest
  ) in {
    def world() = InMemoryWorldStateProxy(
      new EvmCodeStorage(EphemDataSource()),
      new ReferenceCountedStateStorage(new NodeStorage(EphemDataSource()), history, 1024L * 1024).getBackingStorage(0),
      (_: BigInt) => None,
      com.chipprbots.ethereum.domain.UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    val block1 = world()
    val block2 = world() // the next block, or an eth_call, builds its own world
    val copy = block1.saveCode(com.chipprbots.ethereum.domain.Address(1), ByteString(0x5b))
    block1.jumpDestMemo shouldBe defined
    (copy.jumpDestMemo.get should be).theSameInstanceAs(block1.jumpDestMemo.get)
    block2.jumpDestMemo.get should not be theSameInstanceAs(block1.jumpDestMemo.get)

    val code = ByteString(0x5b, 0x5b)
    block1.jumpDestMemo.get.getOrCompute(kec256(code), code)
    block1.jumpDestMemo.get.entries shouldBe 1
    block2.jumpDestMemo.get.entries shouldBe 0 // nothing leaked to the other world
  }

  "StateReadCacheConfig" should "never size a cache beyond its fraction of the max heap" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val heap = Runtime.getRuntime.maxMemory
    com.chipprbots.ethereum.utils.StateReadCacheConfig.codeCacheBytes should be <= (heap * 0.05).toLong
    com.chipprbots.ethereum.utils.StateReadCacheConfig.codeSizeCacheBytes should be <= (heap * 0.02).toLong
    com.chipprbots.ethereum.utils.StateReadCacheConfig.decodedNodeCacheBytes should be <= (heap * 0.04).toLong
    com.chipprbots.ethereum.utils.StateReadCacheConfig.jumpDestCacheBytes should be <= (heap * 0.01).toLong
    com.chipprbots.ethereum.utils.StateReadCacheConfig.jumpDestBlockMemoBytes should be <= (heap * 0.02).toLong
    com.chipprbots.ethereum.utils.StateReadCacheConfig.worldReadMemoEntries.toLong should be <= heap / 32768
  }

  private def sizing(conf: String, path: String, default: Long, fraction: Double, heap: Long): Long =
    com.chipprbots.ethereum.utils.StateReadCacheConfig.effectiveBytes(
      Some(com.typesafe.config.ConfigFactory.parseString(conf)),
      path,
      default,
      fraction,
      heap
    )

  "StateReadCacheConfig.effectiveBytes" should "cap an unset default at its heap fraction" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val heap = 1L << 30 // 1 GiB
    sizing("", "code-cache-bytes", 256L << 20, 0.05, heap) shouldBe (heap * 0.05).toLong
    sizing("other = 1", "code-cache-bytes", 256L << 20, 0.05, heap) shouldBe (heap * 0.05).toLong
    // a default already below the fraction is unchanged
    sizing("", "x", 1L << 20, 0.05, heap) shouldBe (1L << 20)
  }

  it should "honour an explicit value above the heap fraction" taggedAs (UnitTest, StateTest) in {
    val heap = 8L << 30 // 8 GiB; 5% = ~410 MB
    val want = 2560L << 20 // 2.5 GiB, 31% of heap
    sizing(s"code-cache-bytes = $want", "code-cache-bytes", 256L << 20, 0.05, heap) shouldBe want
    sizing("code-cache-bytes = 0", "code-cache-bytes", 256L << 20, 0.05, heap) shouldBe 0L
  }

  it should "clamp an explicit value to the safety ceiling of 50% of the heap" taggedAs (UnitTest, StateTest) in {
    val heap = 8L << 30
    sizing(s"code-cache-bytes = ${heap}", "code-cache-bytes", 256L << 20, 0.05, heap) shouldBe heap / 2
  }

  it should "ship the base config with the byte-size keys unset, so the fractions apply" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val shipped = com.typesafe.config.ConfigFactory.parseResources("conf/base/state-read-caches.conf")
    val sec = shipped.getConfig("state-read-caches")
    Seq(
      "code-cache-bytes",
      "code-size-cache-bytes",
      "decoded-node-cache-bytes",
      "jumpdest-cache-bytes",
      "jumpdest-block-memo-bytes"
    ).foreach(k => withClue(k)(sec.hasPath(k) shouldBe false))
  }

  "EvmCodeStorage.getForExecution" should "serve a cache hit without copying the code" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val storage = new EvmCodeStorage(EphemDataSource())
    val code = ByteString(Array.fill[Byte](1024)(0x5b))
    val hash = kec256(code)
    storage.put(hash, code).commit()
    val first = storage.getForExecution(hash).get // miss: fills the cache
    val second = storage.getForExecution(hash).get
    (second should be).theSameInstanceAs(first)
    second.toArrayUnsafe() shouldBe code.toArray
  }
