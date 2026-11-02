package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.FrontierRebuilt
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCodeFilter
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditFrame
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditHead
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditHole
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditPosition
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditReader
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState.*
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditWalk
import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** The audit has its own expansion (spec 022 decision 13), so the nibble arithmetic is forked. This is what holds the
  * two together: with each node of a trie taken away in turn, the walk `FrontierRebuildBfs` runs and the audit's name
  * the same path and the same hash, branch, extension, account leaf, storage root and storage trie alike.
  */
class BackgroundAuditExpansionSpec extends AnyFlatSpec with Matchers:

  private val slotLeaf: LeafNode = storageLeaf(1)
  private val otherSlotLeaf: LeafNode = storageLeaf(2)
  private val storageBranch = branch(9 -> ByteString(slotLeaf.hash))
  private val storageExtension = extension(Seq(8), ByteString(storageBranch.hash))
  private val storageRoot = branch(7 -> ByteString(storageExtension.hash), 10 -> ByteString(otherSlotLeaf.hash))

  // Account keys are 64 nibbles: 0, 1, 2, then the slot and the leaf's own key.
  private val leafWithStorage: LeafNode =
    LeafNode(nibbles(60, 6), ByteString(accountBytes(Some(ByteString(storageRoot.hash)))))
  private val plainLeaf: LeafNode = LeafNode(nibbles(60, 7), ByteString(accountBytes()))
  private val accountBranch = branch(3 -> ByteString(leafWithStorage.hash), 4 -> ByteString(plainLeaf.hash))
  private val accountExtension = extension(Seq(1, 2), ByteString(accountBranch.hash))
  private val nearLeaf: LeafNode = LeafNode(nibbles(63, 5), ByteString(accountBytes()))
  private val root = branch(0 -> ByteString(accountExtension.hash), 5 -> ByteString(nearLeaf.hash))

  private val everyNode: Seq[MptNode] = Seq(
    root,
    accountExtension,
    accountBranch,
    leafWithStorage,
    plainLeaf,
    nearLeaf,
    storageRoot,
    storageExtension,
    storageBranch,
    slotLeaf,
    otherSlotLeaf
  )

  private def storeWithout(victim: Option[MptNode]): TestMptStorage =
    val store = new TestMptStorage
    everyNode.filterNot(victim.contains).foreach(store.putNode)
    store

  private val emptyPath: ByteString = ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))

  /** What the frontier walk reports missing: pathset and hash. */
  private def frontierWalk(store: TestMptStorage): Seq[(Seq[ByteString], ByteString)] =
    val s = new StubFrontierRebuildState(
      new CountingMptStorage(store),
      new InMemoryBfsQueueStorage(),
      StorageScheme.Hash,
      1000
    ) with FrontierRebuildBfs
    s.startFrontierBFS(ByteString(root.hash), emptyPath, isStor = false, (_: Long, _: Long) => ())
    s.selfInbox
      .receiveAll()
      .collect { case FrontierRebuilt(entries, _) => entries }
      .flatten
      .map(e => (e.pathset, e.hash))

  /** What the audit reports missing: the pathset it would ask for, and the hash. */
  private def auditWalk(store: TestMptStorage): Seq[(Seq[ByteString], ByteString)] =
    val head = AuditHead(1, ByteString(root.hash), 0)
    val reader = new AuditReader:
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = store.rawNodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = hashes.map(_ => None)
      def head(): Option[AuditHead] = Some(AuditHead(1, ByteString(root.hash), 0))
    var stack = Vector(AuditFrame(AuditPosition.AccountRoot, Some(ByteString(root.hash))))
    var holes = Vector.empty[AuditHole]
    while stack.nonEmpty do
      val step = AuditWalk.walkBatch(stack, head, reader, new AuditCodeFilter(10))
      holes ++= step.holes
      stack = step.pushes
    holes.map(h => (h.position.pathset, h.hash))

  "The audit's expansion" should "find nothing where the frontier walk finds nothing" taggedAs UnitTest in {
    frontierWalk(storeWithout(None)) shouldBe empty
    auditWalk(storeWithout(None)) shouldBe empty
  }

  it should "name the same path and hash as the frontier walk, for every node of the trie but the root" taggedAs UnitTest in {
    everyNode.filterNot(_ == root).foreach { victim =>
      withClue(s"with ${victim.getClass.getSimpleName} ${ByteString(victim.hash)} taken away: ") {
        val frontier = frontierWalk(storeWithout(Some(victim)))
        frontier should have size 1
        auditWalk(storeWithout(Some(victim))) shouldBe frontier
      }
    }
  }

  it should "reach every shape this trie has, so the comparison above covers each arm" taggedAs UnitTest in {
    val pathsets = everyNode.filterNot(_ == root).map(v => frontierWalk(storeWithout(Some(v))).head._1)
    withClue("an account-trie node and a storage-trie node: ") {
      pathsets.map(_.size).toSet shouldBe Set(1, 2)
    }
    withClue("a storage root is asked for at the account hash and the empty path: ") {
      pathsets should contain(Seq(accountHash6, emptyPath))
    }
    withClue("across an extension: ") {
      pathsets should contain(Seq(ByteString(HexPrefix.encode(Array[Byte](0, 1, 2), isLeaf = false))))
    }
  }

  private def accountHash6: ByteString =
    ByteString(HexPrefix.nibblesToBytes(Array[Byte](0, 1, 2, 3) ++ nibbles(60, 6).toArray))
