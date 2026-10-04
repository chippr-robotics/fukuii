package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*

/** Re-applying a block whose state is already in the DB must leave reference counts exactly as a single application
  * would. `BlockImporter.resolvingMissingNode` retries the WHOLE batch after a missing-node fetch (the executed prefix
  * is not adopted), and a restart mid-batch does the same. Applied twice, a trie that alternates between two states (a
  * system contract's per-block slots) ends with a LIVE node at refs == 0 on a death row once it goes idle, and the
  * prune deletes it `history` blocks later (devnet-8 block 320603, 0x0000bff4...: MissingStorageNodeException).
  */
class ReferenceCountedStateReapplySpec extends AnyFlatSpec with Matchers:

  private val history = 4
  private val keyCount = 50
  private val idleAfter = 9
  private val head = 30

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String, i: Int): ByteString = ByteString(s"$tag-$i-padding-to-force-a-hashed-leaf-node")

  class Setup:
    val dataSource: EphemDataSource = EphemDataSource()
    val nodeStorage = new NodeStorage(dataSource)
    val stateStorage = new ReferenceCountedStateStorage(nodeStorage, history, 8L * 1024 * 1024)

    /** Block bn: slot 0 alternates by parity until `idleAfter`, then the contract is idle and its root is stable. */
    def execute(bn: Int, parentRoot: ByteString): ByteString =
      if bn > idleAfter then parentRoot
      else
        val storage = stateStorage.getBackingStorage(bn)
        val trie = MerklePatriciaTrie[ByteString, ByteString](parentRoot.toArray, storage)
          .put(key(0), value(if bn % 2 == 0 then "even" else "odd", 0))
        ByteString(trie.getRootHash)

    def genesis(): ByteString =
      val storage = stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value("g", i))
      }
      ByteString(trie.getRootHash)

    /** Reads through the decoded-node cache, as block execution does. */
    def readThroughCache(root: ByteString, storage: ReferenceCountedStateStorage = stateStorage): Unit =
      val cached = storage.getBackingStorage(0).asInstanceOf[SerializingMptStorage].cachedForExecution
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, cached)
      (0 until keyCount).foreach(i => trie.get(key(i)))

    def readWholeState(root: ByteString): Unit =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, stateStorage.getReadOnlyStorage)
      (0 until keyCount).foreach(i => trie.get(key(i)))

    def dbKeys: Set[Seq[Byte]] = dataSource.storage.keySet.map(_.array().toSeq).toSet

    /** Every stored entry INCLUDING its value, i.e. reference counts and last-used blocks, not just which keys exist.
      */
    def dbContent: Map[Seq[Byte], Seq[Byte]] =
      dataSource.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }.toMap

    /** Executes and saves `from..to` on top of `roots(from - 1)`, as one import batch (best advances only afterwards).
      */
    def applyBatch(roots: Map[Int, ByteString], from: Int, to: Int): Map[Int, ByteString] =
      (from to to).foldLeft(roots) { (acc, bn) =>
        val r = acc + (bn -> execute(bn, acc(bn - 1)))
        stateStorage.onBlockSave(bn, from - 1)(() => ())
        r
      }

    /** Adopts one block at a time (best = bn - 1) up to `head`. */
    def continueLinear(roots: Map[Int, ByteString], from: Int, to: Int = head): Map[Int, ByteString] =
      (from to to).foldLeft(roots) { (acc, bn) =>
        val r = acc + (bn -> execute(bn, acc(bn - 1)))
        stateStorage.onBlockSave(bn, bn - 1)(() => ())
        r
      }

  // Batches whose double application leaves skewed counts (a wide batch can happen to prune the skew away again).
  private val skewedBatches = Seq((5, 5), (6, 8), (7, 9))
  private val batches = skewedBatches :+ (4, 10)

  "ReferenceCountedStateStorage" should "keep the head state when every block is applied exactly once (control)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val roots = continueLinear(Map(0 -> genesis()), 1)
    noException should be thrownBy readWholeState(roots(head))

  /** Single application of everything: the reference every retry variant must equal. */
  private def referenceContent: Map[Seq[Byte], Seq[Byte]] =
    val reference = new Setup
    reference.continueLinear(Map(0 -> reference.genesis()), 1)
    reference.dbContent

  skewedBatches.foreach { case (from, to) =>
    it should s"skew the reference counts when batch $from-$to is applied twice (the hazard)" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Setup:
      var roots = continueLinear(Map(0 -> genesis()), 1, from - 1)
      roots = applyBatch(roots, from, to)
      roots = applyBatch(roots, from, to) // the retry, without undoing the first pass
      roots = continueLinear(roots, to + 1)
      // Visible whatever the parity of the idle state: the stored counts differ from a single application.
      dbContent should not be referenceContent
  }

  batches.foreach { case (from, to) =>
    it should s"keep exactly the single-application DB when batch $from-$to is rolled back (descending) before the retry" taggedAs (
      UnitTest,
      DatabaseTest
    ) in new Setup:
      var roots = continueLinear(Map(0 -> genesis()), 1, from - 1)
      roots = applyBatch(roots, from, to)
      (to to from by -1).foreach(bn => stateStorage.rollbackUnadoptedBlock(bn, from - 1))
      roots = applyBatch(roots, from, to)
      roots = continueLinear(roots, to + 1)
      noException should be thrownBy readWholeState(roots(head))
      dbContent shouldBe referenceContent
  }

  it should "delete a LIVE node when an alternating batch is applied twice (devnet-8 320603 shape)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    var roots = continueLinear(Map(0 -> genesis()), 1, 5)
    roots = applyBatch(roots, 6, 8)
    roots = applyBatch(roots, 6, 8)
    roots = continueLinear(roots, 9)
    an[MerklePatriciaTrie.MissingNodeException] should be thrownBy readWholeState(roots(head))

  it should "leave exactly the DB a single application leaves, after rollback and retry" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val reference = new Setup
    val refRoots = reference.continueLinear(Map(0 -> reference.genesis()), 1)
    var roots = continueLinear(Map(0 -> genesis()), 1, 5)
    roots = applyBatch(roots, 6, 8)
    (8 to 6 by -1).foreach(bn => stateStorage.rollbackUnadoptedBlock(bn, 5))
    roots = applyBatch(roots, 6, 8)
    roots = continueLinear(roots, 9)
    roots(head) shouldBe refRoots(head)
    dbContent shouldBe reference.dbContent

  it should "refuse to roll back a block at or below the best block, and ignore a never-applied number" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val roots = continueLinear(Map(0 -> genesis()), 1)
    val before = dbKeys
    stateStorage.rollbackUnadoptedBlock(head, head) // at best
    stateStorage.rollbackUnadoptedBlock(5, head) // below best
    stateStorage.rollbackUnadoptedBlock(head + 7, head) // above best, never applied: no-op
    dbKeys shouldBe before
    noException should be thrownBy readWholeState(roots(head))

  it should "repair a batch killed mid-way with the startup sweep, and be a no-op on a clean node" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    var roots = continueLinear(Map(0 -> genesis()), 1, 5)
    stateStorage.rollbackUnadoptedAbove(5, 512) shouldBe 0 // clean node
    roots = applyBatch(roots, 6, 8) // ... process killed here: best is still 5

    val restarted =
      new ReferenceCountedStateStorage(nodeStorage, history, 8L * 1024 * 1024) // fresh process, lastPruned = None
    restarted.rollbackUnadoptedAbove(5, 512) shouldBe 3
    restarted.rollbackUnadoptedAbove(5, 512) shouldBe 0 // idempotent

    roots = applyBatch(roots, 6, 8)
    roots = continueLinear(roots, 9)
    noException should be thrownBy readWholeState(roots(head))

  it should "bound the sweep to its window" taggedAs (UnitTest, DatabaseTest) in new Setup:
    var roots = continueLinear(Map(0 -> genesis()), 1, 5)
    roots = applyBatch(roots, 6, 8)
    stateStorage.rollbackUnadoptedAbove(5, 2) shouldBe 2 // window 2 covers blocks 6 and 7 only

  it should "make the sweep a no-op on archive and in-memory pruning modes" taggedAs (UnitTest, DatabaseTest) in {
    val (archive, _, _) = StateStorage.createTestStateStorage(EphemDataSource())
    archive.rollbackUnadoptedAbove(5, 512) shouldBe 0
    archive.rollbackUnadoptedBlock(10, 5) // must not throw
    val (inMemory, _, _) =
      StateStorage.createTestStateStorage(
        EphemDataSource(),
        com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning(10)
      )
    inMemory.rollbackUnadoptedAbove(5, 512) shouldBe 0
    inMemory.rollbackUnadoptedBlock(10, 5)
  }

  it should "evict a rolled-back node from the decoded-node cache, so the next read misses instead of being masked" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    var roots = Map(0 -> genesis())
    roots = applyBatch(roots, 1, 1) // block 1 creates new nodes (a new root)
    readThroughCache(roots(1)) // caches them
    stateStorage.rollbackUnadoptedBlock(1, 0)
    a[MerklePatriciaTrie.MissingNodeException] should be thrownBy readThroughCache(roots(1))
    noException should be thrownBy readThroughCache(roots(0)) // the parent state is untouched

  it should "evict the nodes the startup sweep deletes from the decoded-node cache" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    var roots = Map(0 -> genesis())
    roots = applyBatch(roots, 1, 2)
    readThroughCache(roots(2))
    stateStorage.rollbackUnadoptedAbove(0, 512) shouldBe 2
    a[MerklePatriciaTrie.MissingNodeException] should be thrownBy readThroughCache(roots(2))
