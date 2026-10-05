package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.encoding.*
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*

/** The Engine API's `newPayload` and the regular-sync importer each executed AND committed the same block (Platåberget
  * v19: 25 blocks committed 2-4 times). Execution commits ABSOLUTE reference counts computed from a read-through, so
  * the second commit applies the block's changes again: a replaced node ends at -1 and a created one at 2, and a later
  * re-add of a -1 node lands on 0 = death row = pruned while live ("Missing account trie node ...").
  *
  * `StagedBlockState.commitWith` makes the commit idempotent per block hash. The "legacy" cases commit the old way
  * (`pendingWith(...).foreach(_.commit())`, no guard) and pin the hazard; the "guarded" cases must not show it.
  */
class BlockCommitIdempotencySpec extends AnyFlatSpec with Matchers:

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

    def genesis(): ByteString =
      val storage = stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value("g", i))
      }
      ByteString(trie.getRootHash)

    def hashOf(bn: Int): ByteString = kec256(ByteString(s"block-$bn"))

    /** Block `bn`: slot 0 alternates by parity until `idleAfter`, then the block changes nothing. */
    def stageBlock(bn: Int, parentRoot: ByteString): (ByteString, StagedBlockState) =
      val staged = stateStorage.stageBlock(bn)
      if bn > idleAfter then (parentRoot, staged)
      else
        val trie = MerklePatriciaTrie[ByteString, ByteString](parentRoot.toArray, staged.storage)
          .put(key(0), value(if bn % 2 == 0 then "even" else "odd", 0))
        (ByteString(trie.getRootHash), staged)

    def legacyCommit(bn: Int, staged: StagedBlockState): Unit =
      staged.pendingWith(bn, hashOf(bn)).foreach(_.commit())

    def guardedCommit(bn: Int, staged: StagedBlockState): Unit =
      staged.commitWith(bn, hashOf(bn), None)

    /** The second execution reads the state the first commit left (reference counts included) and computes ABSOLUTE
      * counts from it: with `interleaved = false` it ran after the first commit (the Platåberget shape: Engine
      * newPayload committed, the importer then executed the same block), with `true` both ran before either committed.
      */
    def commitTwice(
        bn: Int,
        parentRoot: ByteString,
        commit: (Int, StagedBlockState) => Unit,
        interleaved: Boolean = false
    ): ByteString =
      val (root, first) = stageBlock(bn, parentRoot)
      val second = if interleaved then Some(stageBlock(bn, parentRoot)._2) else None
      commit(bn, first)
      commit(bn, second.getOrElse(stageBlock(bn, parentRoot)._2))
      root

    def commitOnce(bn: Int, parentRoot: ByteString, commit: (Int, StagedBlockState) => Unit): ByteString =
      val (root, staged) = stageBlock(bn, parentRoot)
      commit(bn, staged)
      root

    def advance(bn: Int, root: ByteString, commit: (Int, StagedBlockState) => Unit): ByteString =
      val next = commitOnce(bn, root, commit)
      stateStorage.onBlockSave(bn, bn - 1)(() => ())
      next

    /** Reference counts of every stored trie node (32-byte keys that decode as a stored node). */
    def refCounts: Seq[Int] =
      dataSource.storage.collect {
        case (k, v) if k.array().length == 32 + Namespaces.NodeNamespace.length => storedNodeFromBytes(v).references
      }.toSeq

    def dbContent: Map[Seq[Byte], Seq[Byte]] =
      dataSource.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }.toMap

    def readWholeState(root: ByteString): Unit =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, stateStorage.getReadOnlyStorage)
      (0 until keyCount).foreach(i => trie.get(key(i)))

  "A block committed twice" should "apply its count changes twice without the guard (the hazard): replaced -1, created 2" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val root0 = genesis()
    commitTwice(1, root0, legacyCommit)
    refCounts.min shouldBe -1
    refCounts.max shouldBe 2

  it should "apply them once with the guard: replaced 0, created 1, identical to a single commit" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val reference = new Setup
    val refRoot0 = reference.genesis()
    reference.commitOnce(1, refRoot0, reference.guardedCommit)

    val root0 = genesis()
    commitTwice(1, root0, guardedCommit)
    refCounts.min shouldBe 0
    refCounts.max shouldBe 1
    dbContent shouldBe reference.dbContent

  it should "apply them once with the guard when both executions ran before either committed" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val reference = new Setup
    reference.commitOnce(1, reference.genesis(), reference.guardedCommit)

    commitTwice(1, genesis(), guardedCommit, interleaved = true)
    dbContent shouldBe reference.dbContent

  it should "keep the live node through the prune when the alternating blocks were committed twice (toggle-to-delete)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    def run(commit: Setup => (Int, StagedBlockState) => Unit): Setup =
      val s = new Setup
      var root = s.genesis()
      (1 to 5).foreach(bn => root = s.advance(bn, root, commit(s)))
      (6 to 8).foreach { bn =>
        root = s.commitTwice(bn, root, commit(s))
        s.stateStorage.onBlockSave(bn, bn - 1)(() => ())
      }
      (9 to head).foreach(bn => root = s.advance(bn, root, commit(s)))
      s.readWholeState(root) // throws if a live node was pruned
      s

    // the hazard, without the guard: a live node was pruned
    an[MerklePatriciaTrie.MissingNodeException] should be thrownBy run(s => s.legacyCommit)
    // with the guard the head state is whole, and equals a single application of every block
    val guarded = run(s => s.guardedCommit)
    val single = new Setup
    var root = single.genesis()
    (1 to head).foreach(bn => root = single.advance(bn, root, single.guardedCommit))
    guarded.dbContent shouldBe single.dbContent
  }

  it should "apply again after an undo (a reorg took it back, #1471): commit, undo, re-execute" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val root0 = genesis()
    val afterGenesis = dbContent
    commitOnce(1, root0, guardedCommit)
    val afterCommit = dbContent
    stateStorage.isBlockAppliedForTest(hashOf(1)) shouldBe true

    stateStorage.onBlocksAbandoned(Seq((BigInt(1), hashOf(1))))
    stateStorage.isBlockAppliedForTest(hashOf(1)) shouldBe false
    dbContent should not be afterCommit

    // re-executed (not redone): the guard must let it through, because the record says "undone"
    commitOnce(1, root0, guardedCommit)
    stateStorage.isBlockAppliedForTest(hashOf(1)) shouldBe true
    refCounts.min shouldBe 0
    refCounts.max shouldBe 1
    dbContent.keySet should contain allElementsOf afterGenesis.keySet

  extension (s: ReferenceCountedStateStorage)
    private def isBlockAppliedForTest(hash: ByteString): Boolean =
      s.stageBlock(1).isBlockApplied(hash)
