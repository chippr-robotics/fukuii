package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.ReferenceCountNodeStorage.RepairedNodeReferences
import com.chipprbots.ethereum.db.storage.ReferenceCountNodeStorage.StoredNode
import com.chipprbots.ethereum.db.storage.encoding.*
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingRootNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.rlp.RLPException
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.testing.Tags.*

/** The storage primitives the background audit stands on (spec 022 T053, decisions 1, 3, 7, 8): a no-fill batch that
  * one bad entry cannot fail, a repair write that re-checks under the commit lock, and what it keeps where.
  */
class BackgroundAuditStorageSpec extends AnyFlatSpec with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(5, Millis))

  private def nodeBytes(seed: Int): Array[Byte] =
    MptTraversals.encodeNode(
      LeafNode(ByteString(Array.fill[Byte](4)(seed.toByte)), ByteString(Array.fill[Byte](40)((seed + 1).toByte)))
    )

  private def hashOf(bytes: Array[Byte]): NodeHash = ByteString(kec256(bytes))

  private class Basic:
    val source: EphemDataSource = EphemDataSource()
    val nodes: NodeStorage = new NodeStorage(source)
    val state: ReferenceCountedStateStorage =
      new ReferenceCountedStateStorage(nodes, 64, 1024L * 1024, nodesMayBeDeleted = () => true)
    def stored(hash: NodeHash): StoredNode = storedNodeFromBytes(nodes.get(hash).get)
    def put(hash: NodeHash, bytes: Array[Byte], refs: Int): Unit =
      nodes.put(hash, storedNodeToBytes(StoredNode(ByteString(bytes), refs, 1)))

  "multiGetOptimizedNoFill" should "answer as multiGetOptimized does, in order, on a store with no block cache" taggedAs UnitTest in {
    val source = EphemDataSource()
    val store = new NodeStorage(source)
    val a = nodeBytes(1)
    store.put(hashOf(a), a)
    val keys = Seq(hashOf(a).toArray, hashOf(nodeBytes(2)).toArray)
    val answer = source.multiGetOptimizedNoFill(store.namespace, keys)
    answer.map(_.map(_.toSeq)) shouldBe source.multiGetOptimized(store.namespace, keys).map(_.map(_.toSeq))
    answer.map(_.isDefined) shouldBe Seq(true, false)
  }

  "rawNodes on basic pruning" should "unwrap a stored node, say absent, and fail only the entry that does not unwrap" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    val bad = hashOf(nodeBytes(2))
    b.put(hashOf(good), good, 3)
    b.nodes.put(bad, Array[Byte](1, 2, 3)) // not a [bytes, refs, lastUsed] list
    val missing = hashOf(nodeBytes(3))

    val reads = b.state.getReadOnlyStorage.rawNodes(Seq(hashOf(good), bad, missing))

    reads(0).asInstanceOf[RawNodeRead.Bytes].encoded.toSeq shouldBe good.toSeq
    reads(1) shouldBe RawNodeRead.Undecodable
    reads(2) shouldBe RawNodeRead.Absent
    withClue("what this replaces for the audit: one bad entry fails all of multiGetNodes: ") {
      an[Exception] should be thrownBy b.state.getReadOnlyStorage.multiGetNodes(Seq(hashOf(good).toArray, bad.toArray))
    }
  }

  it should "reach the same read through the backing store and the decoded-node cache's delegator" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), good, 3)
    val backing = b.state.getBackingStorage(0).asInstanceOf[SerializingMptStorage]
    backing.executionCache shouldBe defined
    Seq(backing, backing.cachedForExecution).foreach { store =>
      store.rawNodes(Seq(hashOf(good))).head.asInstanceOf[RawNodeRead.Bytes].encoded.toSeq shouldBe good.toSeq
    }
    backing.cachedForExecution.getClass shouldBe classOf[CachingMptStorage]
  }

  "rawNodes on archive pruning" should "hand back the bytes as they are stored" taggedAs UnitTest in {
    val source = EphemDataSource()
    val nodes = new NodeStorage(source)
    val state = new ArchiveStateStorage(nodes)
    val good = nodeBytes(1)
    nodes.put(hashOf(good), good)

    val reads = state.getReadOnlyStorage.rawNodes(Seq(hashOf(good), hashOf(nodeBytes(2))))

    reads(0).asInstanceOf[RawNodeRead.Bytes].encoded.toSeq shouldBe good.toSeq
    reads(1) shouldBe RawNodeRead.Absent
  }

  "repairNode on basic pruning" should "write an absent node with its references pinned, and evict nothing else" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.Written
    val stored = b.stored(hashOf(good))
    stored.nodeEncoded.toArray.toSeq shouldBe good.toSeq
    stored.references shouldBe RepairedNodeReferences
    stored.lastUsedByBlock shouldBe BigInt(77)
  }

  it should "leave a valid node and its count alone, whatever the count" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), good, 5)
    b.state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.AlreadyValid
    b.stored(hashOf(good)).references shouldBe 5
    b.stored(hashOf(good)).lastUsedByBlock shouldBe BigInt(1)
  }

  it should "keep the count of a node a late on-demand fetch writes again, where saveNode sets it back to one (#1692)" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), good, 2)
    // What the importer's on-demand fetch writes when the fetched node is already there: repairNode finds it valid.
    b.state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.AlreadyValid
    b.stored(hashOf(good)).references shouldBe 2

    // The blind upsert the importer used to make: one reference, whatever the node had. At two positions that is one
    // short, and the first block to drop one of them sends the node to a death row while the other still uses it.
    b.state.saveNode(hashOf(good), good, 77)
    withClue("this is why BlockImporter no longer calls it: ") {
      b.stored(hashOf(good)).references shouldBe 1
    }
  }

  it should "overwrite bytes that fail their hash, and never lower the count they had" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), nodeBytes(2), RepairedNodeReferences + 7)
    b.state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.Written
    b.stored(hashOf(good)).nodeEncoded.toArray.toSeq shouldBe good.toSeq
    b.stored(hashOf(good)).references shouldBe RepairedNodeReferences + 7
  }

  it should "overwrite an entry that does not unwrap" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.nodes.put(hashOf(good), Array[Byte](1, 2, 3))
    b.state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.Written
    b.stored(hashOf(good)).references shouldBe RepairedNodeReferences
  }

  it should "refuse bytes that do not hash to the key" taggedAs UnitTest in {
    val b = new Basic
    val wanted = hashOf(nodeBytes(1))
    b.state.repairNode(wanted, nodeBytes(2), 77) shouldBe RepairOutcome.Rejected
    b.nodes.get(wanted) shouldBe None
  }

  // decision 1: import can write the node between the walk's proof that it is absent and this write.
  it should "re-check under the commit lock: a node that arrives while it waits for the lock keeps its count" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    var outcome: Option[RepairOutcome] = None
    val repair = new Thread(() => outcome = Some(b.state.repairNode(hashOf(good), good, 77)))

    ReferenceCountedStateStorage.commitLock.synchronized {
      repair.start()
      eventually(repair.getState shouldBe Thread.State.BLOCKED) // it holds its proof and waits for the block commit
      b.put(hashOf(good), good, 3) // the commit it waits for wrote this node
    }
    repair.join(10000)

    outcome shouldBe Some(RepairOutcome.AlreadyValid)
    b.stored(hashOf(good)).references shouldBe 3
  }

  /** A bad node in the execution cache, as a read before H3 left one there: the store's read now refuses bytes that
    * fail their hash, so none can be read in, and the eviction is pinned on one put there directly.
    */
  private def cacheBadNode(backing: SerializingMptStorage, hash: ByteString, bytes: Array[Byte]): Unit =
    val cache = backing.executionCache.get
    cache.putIfCurrent(hash, MptStorage.decodeNode(bytes, hash.toArray), cache.generation)

  it should "evict the decoded node, so execution stops reading what it replaced" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    val backing = b.state.getBackingStorage(0).asInstanceOf[SerializingMptStorage]
    val execution = backing.cachedForExecution
    b.put(hashOf(good), nodeBytes(2), 1)
    cacheBadNode(backing, hashOf(good), nodeBytes(2))
    execution.get(hashOf(good).toArray).encode.toSeq shouldBe nodeBytes(2).toSeq // the bad node, cached

    b.state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.Written

    execution.get(hashOf(good).toArray).encode.toSeq shouldBe good.toSeq
  }

  it should "survive the increment, decrement and prune paths without reaching zero" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.state.repairNode(hashOf(good), good, 77)

    new ReferenceCountNodeStorage(b.nodes, 78).update(Nil, Seq(hashOf(good) -> good))
    b.stored(hashOf(good)).references shouldBe RepairedNodeReferences + 1
    val counted = new ReferenceCountNodeStorage(b.nodes, 79)
    counted.update(Seq(hashOf(good)), Nil)
    counted.update(Seq(hashOf(good)), Nil)
    b.stored(hashOf(good)).references shouldBe RepairedNodeReferences - 1
    ReferenceCountNodeStorage.prune(79, b.nodes, inMemory = false)
    b.nodes.get(hashOf(good)) shouldBe defined
  }

  "repairNode on archive pruning" should "be a plain conditional put" taggedAs UnitTest in {
    val source = EphemDataSource()
    val nodes = new NodeStorage(source)
    val state = new ArchiveStateStorage(nodes)
    val good = nodeBytes(1)
    state.canRepairNodes shouldBe true

    state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.Written
    nodes.get(hashOf(good)).get.toSeq shouldBe good.toSeq
    state.repairNode(hashOf(good), good, 77) shouldBe RepairOutcome.AlreadyValid

    val other = nodeBytes(3)
    nodes.put(hashOf(other), nodeBytes(4))
    state.repairNode(hashOf(other), other, 77) shouldBe RepairOutcome.Written
    nodes.get(hashOf(other)).get.toSeq shouldBe other.toSeq

    state.repairNode(hashOf(nodeBytes(5)), other, 77) shouldBe RepairOutcome.Rejected
    nodes.get(hashOf(nodeBytes(5))) shouldBe None
  }

  "repairNode under in-memory pruning" should "be unsupported: the audit is off there" taggedAs UnitTest in {
    val (state, _, _) = StateStorage.createTestStateStorage(EphemDataSource(), InMemoryPruning(10))
    state.canRepairNodes shouldBe false
    an[UnsupportedOperationException] should be thrownBy state.repairNode(hashOf(nodeBytes(1)), nodeBytes(1), 1)
  }

  "repairCode" should "write absent code and leave valid code alone" taggedAs UnitTest in {
    val code = new EvmCodeStorage(EphemDataSource())
    val bytes = ByteString(nodeBytes(1))
    val hash = hashOf(bytes.toArray)
    code.repairCode(hash, bytes) shouldBe RepairOutcome.Written
    code.get(hash) shouldBe Some(bytes)
    code.repairCode(hash, bytes) shouldBe RepairOutcome.AlreadyValid
  }

  it should "overwrite code that fails its hash, and evict it from the execution caches" taggedAs UnitTest in {
    val code = new EvmCodeStorage(EphemDataSource())
    val bytes = ByteString(nodeBytes(1))
    val hash = hashOf(bytes.toArray)
    val corrupt = ByteString(nodeBytes(2) ++ nodeBytes(2))
    code.put(hash, corrupt).commit()
    code.getForExecution(hash) shouldBe None // a read refuses it (H3), so it is put in the caches directly
    code.cacheForExecution(hash, corrupt)
    code.getForExecution(hash) shouldBe Some(corrupt) // the execution caches now remember it
    code.getSizeForExecution(hash) shouldBe Some(corrupt.length)

    code.repairCode(hash, bytes) shouldBe RepairOutcome.Written

    code.get(hash) shouldBe Some(bytes)
    code.getForExecution(hash) shouldBe Some(bytes)
    code.getSizeForExecution(hash) shouldBe Some(bytes.length)
  }

  it should "refuse bytes that do not hash to the key" taggedAs UnitTest in {
    val code = new EvmCodeStorage(EphemDataSource())
    val hash = hashOf(nodeBytes(1))
    code.repairCode(hash, ByteString(nodeBytes(2))) shouldBe RepairOutcome.Rejected
    code.get(hash) shouldBe None
  }

  "multiGetNoFill on code" should "give one result per hash, in order" taggedAs UnitTest in {
    val code = new EvmCodeStorage(EphemDataSource())
    val bytes = ByteString(nodeBytes(1))
    code.put(hashOf(bytes.toArray), bytes).commit()
    code.multiGetNoFill(Seq(hashOf(nodeBytes(2)), hashOf(bytes.toArray))) shouldBe Seq(None, Some(bytes))
  }

  "The audit's cursors" should "live in the progress namespace as raw bytes, and not break what reads that namespace" taggedAs UnitTest in {
    val store = new SnapSyncProgressStorage(EphemDataSource())
    store.readAuditCursors() shouldBe None
    store.writeAuditCursors(Array[Byte](0, 1, 2, -1))
    store.readAuditCursors().get.toSeq shouldBe Seq[Byte](0, 1, 2, -1)
    store.writeAccountCursors(ByteString(Array.fill[Byte](32)(9)), 7L, Map.empty)

    noException should be thrownBy store.clearAll() // it decoded every value in the namespace as JSON

    store.writeAuditCursors(Array[Byte](3))
    store.clearAuditCursors()
    store.readAuditCursors() shouldBe None
  }

  "isSnapAuditOwed" should "owe nothing on a node that never finished SNAP or a checkpoint" taggedAs UnitTest in {
    val app = new AppStateStorage(EphemDataSource())
    app.isSnapAuditOwed() shouldBe false
    app.putSnapSyncPivotBlock(5).commit() // SNAP started, not finished
    app.isSnapAuditOwed() shouldBe false
  }

  it should "owe one clean pass per pivot" taggedAs UnitTest in {
    val app = new AppStateStorage(EphemDataSource())
    app.putSnapSyncPivotBlock(5).and(app.snapSyncDone()).commit()
    app.isSnapAuditOwed() shouldBe true
    app.recoveryDone().and(app.putSnapAuditCleanAnchor(app.snapAuditAnchor())).commit()
    app.isSnapAuditOwed() shouldBe false
    app.putSnapSyncPivotBlock(6).commit() // a later SNAP cycle
    app.isSnapAuditOwed() shouldBe true
  }

  it should "owe one on a checkpoint-synced node, which has done flags and no pivot" taggedAs UnitTest in {
    val app = new AppStateStorage(EphemDataSource())
    app.snapSyncDone().commit()
    app.snapAuditAnchor() shouldBe AppStateStorage.AuditNoPivot
    app.isSnapAuditOwed() shouldBe true
    app.recoveryDone().and(app.putSnapAuditCleanAnchor(app.snapAuditAnchor())).commit()
    app.isSnapAuditOwed() shouldBe false
  }

  // decision 14: a flag only that arm reads, with the scan skipped for the audit. Nothing else would pay it.
  it should "owe a pass while a recovery flag is down, with the anchor matching" taggedAs UnitTest in {
    val app = new AppStateStorage(EphemDataSource())
    app.putSnapSyncPivotBlock(5).and(app.snapSyncDone()).commit()
    app.recoveryDone().and(app.putSnapAuditCleanAnchor(app.snapAuditAnchor())).commit()
    app.isSnapAuditOwed() shouldBe false
    app.clearBytecodeRecoveryDone().commit()
    app.isSnapAuditOwed() shouldBe true
    app.recoveryDone().commit()
    app.clearStorageRecoveryDone().commit()
    app.isSnapAuditOwed() shouldBe true
  }

  // ---- decision 15 ---------------------------------------------------------------------------------------------------

  private val reachedByRoot: () => Boolean = () => true

  "deleteBadNode on basic pruning" should "delete bytes that fail their hash, and an entry that does not unwrap" taggedAs UnitTest in {
    val b = new Basic
    val failing = hashOf(nodeBytes(1))
    b.put(failing, nodeBytes(2), 9)
    b.state.deleteBadNode(failing, reachedByRoot) shouldBe BadEntryDelete.Deleted
    b.nodes.get(failing) shouldBe None

    val garbled = hashOf(nodeBytes(3))
    b.nodes.put(garbled, Array[Byte](1, 2, 3))
    b.state.deleteBadNode(garbled, reachedByRoot) shouldBe BadEntryDelete.Deleted
    b.nodes.get(garbled) shouldBe None
  }

  it should "never delete a node that passes keccak, whatever it is asked, and whether or not it decodes" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), good, 5)
    b.state.deleteBadNode(hashOf(good), reachedByRoot) shouldBe BadEntryDelete.NoLongerBad
    b.stored(hashOf(good)).references shouldBe 5

    val notANode = Array[Byte](1, 2, 3)
    b.put(hashOf(notANode), notANode, 5)
    b.state.deleteBadNode(hashOf(notANode), reachedByRoot) shouldBe BadEntryDelete.NoLongerBad
    b.nodes.get(hashOf(notANode)) shouldBe defined
  }

  it should "have nothing to do for an absent node" taggedAs UnitTest in {
    val b = new Basic
    b.state.deleteBadNode(hashOf(nodeBytes(1)), reachedByRoot) shouldBe BadEntryDelete.NoLongerBad
  }

  it should "leave a bad entry the current root no longer reaches" taggedAs UnitTest in {
    val b = new Basic
    val failing = hashOf(nodeBytes(1))
    b.put(failing, nodeBytes(2), 9)
    b.state.deleteBadNode(failing, () => false) shouldBe BadEntryDelete.NotReached
    b.nodes.get(failing) shouldBe defined
  }

  it should "stop at the read under the lock: good bytes that arrive while it waits are kept" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), nodeBytes(2), 9)
    var outcome: Option[BadEntryDelete] = None
    val delete = new Thread(() => outcome = Some(b.state.deleteBadNode(hashOf(good), reachedByRoot)))

    ReferenceCountedStateStorage.commitLock.synchronized {
      delete.start()
      eventually(delete.getState shouldBe Thread.State.BLOCKED)
      b.put(hashOf(good), good, 3) // the commit it waits for wrote the good node
    }
    delete.join(10000)

    outcome shouldBe Some(BadEntryDelete.NoLongerBad)
    b.stored(hashOf(good)).references shouldBe 3
  }

  it should "ask whether the root still reaches it under the lock, so no commit moves the root part way" taggedAs UnitTest in {
    val b = new Basic
    val failing = hashOf(nodeBytes(1))
    b.put(failing, nodeBytes(2), 9)
    var heldTheLock = false
    b.state.deleteBadNode(
      failing,
      () =>
        heldTheLock = Thread.holdsLock(ReferenceCountedStateStorage.commitLock)
        true
    )
    heldTheLock shouldBe true
  }

  it should "evict the decoded node, so execution finds it absent and not bad" taggedAs UnitTest in {
    val b = new Basic
    val failing = hashOf(nodeBytes(1))
    val backing = b.state.getBackingStorage(0).asInstanceOf[SerializingMptStorage]
    val execution = backing.cachedForExecution
    b.put(failing, nodeBytes(2), 9)
    cacheBadNode(backing, failing, nodeBytes(2))
    execution.get(failing.toArray).encode.toSeq shouldBe nodeBytes(2).toSeq // the bad node, cached

    b.state.deleteBadNode(failing, reachedByRoot) shouldBe BadEntryDelete.Deleted

    a[MissingNodeException] should be thrownBy execution.get(failing.toArray)
  }

  "deleteBadNode on archive pruning" should "be conditional in the same way" taggedAs UnitTest in {
    val nodes = new NodeStorage(EphemDataSource())
    val state = new ArchiveStateStorage(nodes)
    val good = nodeBytes(1)
    nodes.put(hashOf(good), good)
    state.deleteBadNode(hashOf(good), reachedByRoot) shouldBe BadEntryDelete.NoLongerBad
    nodes.get(hashOf(good)) shouldBe defined

    val failing = hashOf(nodeBytes(3))
    nodes.put(failing, nodeBytes(4))
    state.deleteBadNode(failing, () => false) shouldBe BadEntryDelete.NotReached
    nodes.get(failing) shouldBe defined
    state.deleteBadNode(failing, reachedByRoot) shouldBe BadEntryDelete.Deleted
    nodes.get(failing) shouldBe None
  }

  "deleteBadNode under in-memory pruning" should "be unsupported, as repair is" taggedAs UnitTest in {
    val (state, _, _) = StateStorage.createTestStateStorage(EphemDataSource(), InMemoryPruning(10))
    an[UnsupportedOperationException] should be thrownBy state.deleteBadNode(hashOf(nodeBytes(1)), reachedByRoot)
  }

  "deleteBadCode" should "delete code that fails its hash and evict it, and keep code that passes" taggedAs UnitTest in {
    val code = new EvmCodeStorage(EphemDataSource())
    val bytes = ByteString(nodeBytes(1))
    val hash = hashOf(bytes.toArray)

    code.put(hash, bytes).commit()
    code.deleteBadCode(hash, reachedByRoot) shouldBe BadEntryDelete.NoLongerBad
    code.get(hash) shouldBe Some(bytes)

    val corrupt = ByteString(nodeBytes(2))
    code.put(hash, corrupt).commit()
    code.getForExecution(hash) // the cache now remembers the good bytes or the bad: either way it must forget them
    code.deleteBadCode(hash, () => false) shouldBe BadEntryDelete.NotReached
    code.get(hash) shouldBe Some(corrupt)

    code.deleteBadCode(hash, reachedByRoot) shouldBe BadEntryDelete.Deleted
    code.get(hash) shouldBe None
    code.getForExecution(hash) shouldBe None
    code.deleteBadCode(hash, reachedByRoot) shouldBe BadEntryDelete.NoLongerBad
  }

  "The audit's record of bad entries" should "keep its own key beside the cursors" taggedAs UnitTest in {
    val store = new SnapSyncProgressStorage(EphemDataSource())
    store.readAuditBadEntries() shouldBe None
    store.writeAuditBadEntries(Array[Byte](5))
    store.writeAuditCursors(Array[Byte](6))
    store.clearAuditCursors()
    store.readAuditBadEntries().get.toSeq shouldBe Seq[Byte](5)
    noException should be thrownBy store.clearAll()
  }

  // ---- the review round: what a clean anchor is worth ----------------------------------------------------------------

  private def anchored(pivot: Option[Int]): (AppStateStorage, SnapSyncProgressStorage) =
    val source = EphemDataSource()
    val app = new AppStateStorage(source)
    val progress = new SnapSyncProgressStorage(source)
    pivot.foreach(p => app.putSnapSyncPivotBlock(p).commit())
    app.snapSyncDone().commit()
    app.recoveryDone().and(app.putSnapAuditCleanAnchor(app.snapAuditAnchor())).commit()
    progress.writeAuditCursors(Array[Byte](1))
    progress.writeAuditBadEntries(Array[Byte](2))
    app.isSnapAuditOwed() shouldBe false
    (app, progress)

  private def owesAWholePass(app: AppStateStorage, progress: SnapSyncProgressStorage): Unit =
    app.getSnapAuditCleanAnchor() shouldBe None
    app.isSnapAuditOwed() shouldBe true
    withClue("and not the rest of a pass over the state this replaced: ") {
      progress.readAuditCursors() shouldBe None
      progress.readAuditBadEntries() shouldBe None
    }

  "The clean anchor" should "go with the done flag, so a re-SNAP on the same pivot owes a pass" taggedAs UnitTest in {
    val (app, progress) = anchored(Some(5))
    app.clearSnapSyncDone().commit()
    app.snapSyncDone().commit() // the same pivot again
    owesAWholePass(app, progress)
  }

  it should "go at a checkpoint import, whose anchor is the same every time" taggedAs UnitTest in {
    val (app, progress) = anchored(None)
    app.beginCheckpointImport(ByteString(Array.fill[Byte](32)(1)), 1L).commit()
    app.endCheckpointImport().commit()
    owesAWholePass(app, progress)
  }

  // ---- the review round: a read error is not a bad node ---------------------------------------------------------------

  final private class FailingDisk extends RuntimeException("the disk")

  /** A store with no raw read of its own, so it has the default, and whose `get` is `read`. */
  private def plainStore(read: NodeHash => Option[Array[Byte]]): NodesKeyValueStorage =
    new NodesKeyValueStorage:
      override def get(key: NodeHash): Option[Array[Byte]] = read(key)
      override def update(toRemove: Seq[NodeHash], toUpsert: Seq[(NodeHash, Array[Byte])]): NodesKeyValueStorage = this
      override def persist(): Unit = ()

  private def plainMpt(read: Array[Byte] => MptNode): MptStorage =
    new MptStorage:
      override def get(nodeId: Array[Byte]): MptNode = read(nodeId)
      override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] = newRoot
      override def persist(): Unit = ()

  private val anyHash: NodeHash = hashOf(nodeBytes(9))

  "NodesKeyValueStorage's default raw read" should "let a read error through: a disk error must not read as a bad node" taggedAs UnitTest in {
    a[FailingDisk] should be thrownBy plainStore(_ => throw new FailingDisk).multiGetRaw(Seq(anyHash))
  }

  it should "call bytes that do not decode undecodable, that entry alone" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), good, 1)
    b.nodes.put(anyHash, nodeBytes(2)) // a list of two: not the [bytes, refs, lastUsed] a stored node is
    val store = plainStore(new ReferenceCountNodeStorage(b.nodes, 0).get)
    val reads = store.multiGetRaw(Seq(anyHash, hashOf(good)))
    reads(0) shouldBe RawNodeRead.Undecodable
    reads(1).asInstanceOf[RawNodeRead.Bytes].encoded.toSeq shouldBe good.toSeq
  }

  "MptStorage's default raw read" should "let a read error through, and tell absent from bad" taggedAs UnitTest in {
    a[FailingDisk] should be thrownBy plainMpt(_ => throw new FailingDisk).rawNodes(Seq(anyHash))
    plainMpt(_ => throw new MissingRootNodeException(anyHash)).rawNodes(Seq(anyHash)) shouldBe Seq(RawNodeRead.Absent)
    plainMpt(_ => throw new MPTException("Invalid Node")).rawNodes(Seq(anyHash)) shouldBe Seq(RawNodeRead.Undecodable)
    plainMpt(_ => throw RLPException("not rlp")).rawNodes(Seq(anyHash)) shouldBe Seq(RawNodeRead.Undecodable)
  }

  // ---- the review round: the last look before a delete ---------------------------------------------------------------

  // `commitLock` covers block commits and prunes. `saveNode`, import's on-demand write, takes no lock, so a good node can
  // land in the middle of a delete.
  "A delete" should "stop if good bytes land while it asks whether the root needs them, on basic" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.put(hashOf(good), nodeBytes(2), 9)
    val outcome = b.state.deleteBadNode(
      hashOf(good),
      () =>
        b.state.saveNode(hashOf(good), good, 7)
        true
    )
    outcome shouldBe BadEntryDelete.NoLongerBad
    b.stored(hashOf(good)).nodeEncoded.toArray.toSeq shouldBe good.toSeq
  }

  it should "stop in the same way on archive" taggedAs UnitTest in {
    val nodes = new NodeStorage(EphemDataSource())
    val state = new ArchiveStateStorage(nodes)
    val good = nodeBytes(1)
    nodes.put(hashOf(good), nodeBytes(2))
    val outcome = state.deleteBadNode(
      hashOf(good),
      () =>
        state.saveNode(hashOf(good), good, 7)
        true
    )
    outcome shouldBe BadEntryDelete.NoLongerBad
    nodes.get(hashOf(good)).get.toSeq shouldBe good.toSeq
  }

  it should "stop in the same way for code" taggedAs UnitTest in {
    val code = new EvmCodeStorage(EphemDataSource())
    val bytes = ByteString(nodeBytes(1))
    val hash = hashOf(bytes.toArray)
    code.put(hash, ByteString(nodeBytes(2))).commit()
    val outcome = code.deleteBadCode(
      hash,
      () =>
        code.put(hash, bytes).commit()
        true
    )
    outcome shouldBe BadEntryDelete.NoLongerBad
    code.get(hash) shouldBe Some(bytes)
  }

  // ---- the review round: never a node that passes keccak --------------------------------------------------------------

  "An entry basic pruning cannot unwrap" should "stay if its raw bytes hash to the key: archive's format, say" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    b.nodes.put(hashOf(good), good)
    withClue("this is the read the audit gets: ") {
      b.state.getReadOnlyStorage.rawNodes(Seq(hashOf(good))) shouldBe Seq(RawNodeRead.Undecodable)
    }
    b.state.deleteBadNode(hashOf(good), reachedByRoot) shouldBe BadEntryDelete.PassesKeccakElsewhere
    b.nodes.get(hashOf(good)).get.toSeq shouldBe good.toSeq
  }

  it should "stay if the wrapper's counters are past reading, around bytes that hash to the key" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    val damaged = rlpEncode(RLPList(RLPValue(good), RLPList(), RLPValue(Array.empty[Byte])))
    b.nodes.put(hashOf(good), damaged)
    withClue("this is the read the audit gets: ") {
      b.state.getReadOnlyStorage.rawNodes(Seq(hashOf(good))) shouldBe Seq(RawNodeRead.Undecodable)
    }
    b.state.deleteBadNode(hashOf(good), reachedByRoot) shouldBe BadEntryDelete.PassesKeccakElsewhere
    b.nodes.get(hashOf(good)).get.toSeq shouldBe damaged.toSeq
  }

  "An entry archive pruning reads as failing its hash" should "stay if it unwraps to a node that passes: basic's format" taggedAs UnitTest in {
    val nodes = new NodeStorage(EphemDataSource())
    val state = new ArchiveStateStorage(nodes)
    val good = nodeBytes(1)
    val wrapped = storedNodeToBytes(StoredNode(ByteString(good), 3, 1))
    nodes.put(hashOf(good), wrapped)
    state.deleteBadNode(hashOf(good), reachedByRoot) shouldBe BadEntryDelete.PassesKeccakElsewhere
    nodes.get(hashOf(good)).get.toSeq shouldBe wrapped.toSeq
  }
