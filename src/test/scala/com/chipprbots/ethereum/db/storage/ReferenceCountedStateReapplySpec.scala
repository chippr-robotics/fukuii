package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*

/** Re-applying a block whose state is already in the DB skews reference counts: applied twice, a trie that alternates
  * between two states (a system contract's per-block slots) ends with a LIVE node at refs == 0 on a death row once it
  * goes idle, and the prune deletes it `history` blocks later (devnet-8 block 320603, 0x0000bff4...:
  * MissingStorageNodeException). The hazard cases reproduce that on the raw storage and must keep failing as hazards.
  *
  * The import path no longer re-applies anything: a block's writes are staged and committed with the block, or
  * discarded (see [[StagedBlockState]]), and the executed prefix of a failed batch is adopted. The staged cases below
  * pin that on a real storage, byte for byte.
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

  it should "delete a LIVE node when an alternating batch is applied twice (devnet-8 320603 shape)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    var roots = continueLinear(Map(0 -> genesis()), 1, 5)
    roots = applyBatch(roots, 6, 8)
    roots = applyBatch(roots, 6, 8)
    roots = continueLinear(roots, 9)
    an[MerklePatriciaTrie.MissingNodeException] should be thrownBy readWholeState(roots(head))

  // ---- The fix: a block's writes are staged and committed with the block, or discarded. ----

  /** One block executed against a STAGED storage: three "transactions" (each persists), the second rewriting slot 0 so
    * one node is written twice within the block.
    */
  private def executeStaged(
      stateStorage: ReferenceCountedStateStorage,
      bn: Int,
      parentRoot: ByteString,
      txs: Int = 3
  ): (ByteString, StagedBlockState) =
    val staged = stateStorage.stageBlock(bn)
    var root = parentRoot
    (0 until txs).foreach { i =>
      val slot = if i == 1 then 0 else (bn % 7) + 1
      root = ByteString(
        MerklePatriciaTrie[ByteString, ByteString](root.toArray, staged.storage)
          .put(key(slot), value(s"b$bn-t$i", slot))
          .getRootHash
      )
    }
    (root, staged)

  it should "write, once committed, exactly what direct execution writes (dr/sck accumulate across transactions)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val direct = new Setup
    val staged = new Setup
    var directRoot = direct.genesis()
    var stagedRoot = staged.genesis()
    (1 to 6).foreach { bn =>
      // direct: the same three persists straight into the database
      val storage = direct.stateStorage.getBackingStorage(bn)
      (0 until 3).foreach { i =>
        val slot = if i == 1 then 0 else (bn % 7) + 1
        directRoot = ByteString(
          MerklePatriciaTrie[ByteString, ByteString](directRoot.toArray, storage)
            .put(key(slot), value(s"b$bn-t$i", slot))
            .getRootHash
        )
      }
      direct.stateStorage.onBlockSave(bn, bn - 1)(() => ())

      val (root, st) = executeStaged(staged.stateStorage, bn, stagedRoot)
      stagedRoot = root
      st.pending.foreach(_.commit())
      staged.stateStorage.onBlockSave(bn, bn - 1)(() => ())
    }
    stagedRoot shouldBe directRoot
    staged.dbContent shouldBe direct.dbContent
  }

  it should "leave the database untouched, and evict what was read back, when a staged block is discarded" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val root0 = genesis()
    val before = dbContent
    val (root1, st) = executeStaged(stateStorage, 1, root0)
    st.pending should not be empty
    dbContent shouldBe before // nothing reached the database
    // execution read the block's own nodes back through the decoded-node cache
    val cached = st.storage.asInstanceOf[SerializingMptStorage].cachedForExecution
    noException should be thrownBy MerklePatriciaTrie[ByteString, ByteString](root1.toArray, cached).get(key(0))
    st.discard()
    dbContent shouldBe before
    // the cache must not mask the absence: the discarded block's state is simply not there
    a[MerklePatriciaTrie.MissingNodeException] should be thrownBy readThroughCache(root1)
    noException should be thrownBy readThroughCache(root0)

  it should "skew nothing when a block fails halfway and is executed again (discard, then retry)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val reference = new Setup
    var refRoot = reference.genesis()
    var root = genesis()
    (1 to 8).foreach { bn =>
      val (r, st) = executeStaged(reference.stateStorage, bn, refRoot)
      refRoot = r
      st.pending.foreach(_.commit())
      reference.stateStorage.onBlockSave(bn, bn - 1)(() => ())

      if bn >= 4 && bn <= 6 then
        // a first attempt that dies after one transaction: its staged writes are dropped
        val (_, failed) = executeStaged(stateStorage, bn, root, txs = 1)
        failed.discard()
      val (r2, st2) = executeStaged(stateStorage, bn, root)
      root = r2
      st2.pending.foreach(_.commit())
      stateStorage.onBlockSave(bn, bn - 1)(() => ())
    }
    root shouldBe refRoot
    dbContent shouldBe reference.dbContent
