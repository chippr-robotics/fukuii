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
import com.chipprbots.ethereum.mpt.MptTraversals
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
    val state: ReferenceCountedStateStorage = new ReferenceCountedStateStorage(nodes, 64, 1024L * 1024)
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

  it should "evict the decoded node, so execution stops reading what it replaced" taggedAs UnitTest in {
    val b = new Basic
    val good = nodeBytes(1)
    val execution = b.state.getBackingStorage(0).asInstanceOf[SerializingMptStorage].cachedForExecution
    b.put(hashOf(good), nodeBytes(2), 1)
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
    state.pruningHistoryBlocks shouldBe None

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
    app.putSnapAuditCleanAnchor(app.snapAuditAnchor()).commit()
    app.isSnapAuditOwed() shouldBe false
    app.putSnapSyncPivotBlock(6).commit() // a later SNAP cycle
    app.isSnapAuditOwed() shouldBe true
  }

  it should "owe one on a checkpoint-synced node, which has done flags and no pivot" taggedAs UnitTest in {
    val app = new AppStateStorage(EphemDataSource())
    app.snapSyncDone().commit()
    app.snapAuditAnchor() shouldBe AppStateStorage.AuditNoPivot
    app.isSnapAuditOwed() shouldBe true
    app.putSnapAuditCleanAnchor(app.snapAuditAnchor()).commit()
    app.isSnapAuditOwed() shouldBe false
  }
