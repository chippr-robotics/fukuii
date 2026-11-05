package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.PathToHashExporter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.mpt.NodesKeyValueStorage
import com.chipprbots.ethereum.testing.Tags.*

/** The Path publish writes the whole state through ONE hash-store instance, so whatever that instance retains per node
  * grows with the state. T063 (spec 022 Gate 2, Sepolia): `ReferenceCountNodeStorage` kept a net-delta entry per node
  * for the reorg undo record (#1471), about 100 B each, and the publish died of OutOfMemoryError at 37M of 161.5M
  * account nodes. These specs run the publish through the store `startPathPublish` takes (`getBackingStorage(pivot)` on
  * basic pruning) and look at what it keeps, and at what it wrote.
  */
class PathPublishRefCountSpec extends AnyFlatSpec with Matchers:

  private val Pivot = BigInt(12072545)

  /** `PathToHashExporter`'s chunk size: three whole chunks and a part of a fourth are published below. */
  private val Chunk = 10000
  private val AccountNodes = 3 * Chunk + 123
  private val StorageNodes = 2000

  /** A node of at least 32 bytes (so it is stored by hash), distinct per `i`. */
  private def rlpFor(i: Int): Array[Byte] = Array.fill[Byte](40)(0x01) ++ BigInt(i + 1).toByteArray

  private def nibbles(i: Int): Array[Byte] =
    Array(((i >> 12) & 0xf).toByte, ((i >> 8) & 0xf).toByte, ((i >> 4) & 0xf).toByte, (i & 0xf).toByte)

  private val account1 = ByteString(kec256(ByteString("account-1")))
  private val account2 = ByteString(kec256(ByteString("account-2")))

  /** A Path store of `AccountNodes` distinct account nodes and `StorageNodes` storage nodes, with two repeats: the node
    * at account path 0 is also at path 1, and storage node 0 of account 1 is also in account 2's trie (a storage root
    * shared by two accounts). Both repeats are one node by hash, referenced from two positions.
    */
  private def seededPathStorage(): PathNodeStorage =
    val pns = new PathNodeStorage(EphemDataSource())
    (0 until AccountNodes).foreach(i => pns.writeAccountNode(nibbles(i), rlpFor(i)))
    pns.writeAccountNode(nibbles(AccountNodes), rlpFor(0))
    (0 until StorageNodes).foreach(i => pns.writeStorageNode(account1, nibbles(i), rlpFor(AccountNodes + 1 + i)))
    pns.writeStorageNode(account2, nibbles(0), rlpFor(AccountNodes + 1))
    pns

  private val distinctNodes = AccountNodes + StorageNodes

  final private class HashStore(val source: EphemDataSource, val nodes: NodeStorage, val mpt: SerializingMptStorage):
    def backing: NodesKeyValueStorage = mpt.backing

  /** The store `startPathPublish` takes: `StateStorage.getBackingStorage(pivot)` on basic pruning (the production
    * default), built the way production builds it.
    */
  private def publishStore(): HashStore =
    val source = EphemDataSource()
    val (state, nodes, _) = StateStorage.createTestStateStorage(source, BasicPruning(64))
    new HashStore(source, nodes, state.getBackingStorage(Pivot).asInstanceOf[SerializingMptStorage])

  /** The store as it was before the fix: every instance tracked deltas. */
  private def trackingStore(): HashStore =
    val source = EphemDataSource()
    val nodes = new NodeStorage(source)
    new HashStore(
      source,
      nodes,
      new SerializingMptStorage(new ReferenceCountNodeStorage(nodes, Pivot, trackNetDeltas = true))
    )

  private def counted(store: HashStore): ReferenceCountNodeStorage =
    store.backing match
      case rc: ReferenceCountNodeStorage => rc
      case other                         => fail(s"the publish store is a ${other.getClass.getSimpleName}")

  private def references(nodes: NodeStorage, rlp: Array[Byte]): Int =
    encoding.storedNodeFromBytes(nodes.get(ByteString(kec256(rlp))).get).references

  /** What the data source holds, comparable. */
  private def contents(source: EphemDataSource): Map[Seq[Byte], Seq[Byte]] =
    source.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }

  "A Path publish through the store startPathPublish takes" should "retain no per-node delta entries" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val store = publishStore()
    val result = PathToHashExporter.publish(seededPathStorage(), store.mpt)

    result.accountNodes shouldBe (AccountNodes + 1).toLong
    result.storageNodes shouldBe (StorageNodes + 1).toLong
    // Before the fix this was one entry per distinct node (distinctNodes), held to the end of the publish.
    counted(store).trackedDeltaEntries should be <= Chunk
    counted(store).trackedDeltaEntries shouldBe 0
  }

  it should "write the same hash store as before: same keys, values, reference counts and snapshots" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val pns = seededPathStorage()
    val now = publishStore()
    PathToHashExporter.publish(pns, now.mpt)

    // Before the fix every instance tracked deltas, and a tracking instance is what that was: the same counting code
    // with the map filled.
    val before = trackingStore()
    PathToHashExporter.publish(pns, before.mpt)

    contents(now.source) shouldBe contents(before.source)
    // The two repeats are counted once per position, as block execution counts them.
    references(now.nodes, rlpFor(0)) shouldBe 2
    references(now.nodes, rlpFor(AccountNodes + 1)) shouldBe 2
    references(now.nodes, rlpFor(1)) shouldBe 1
    val everyNode = ((0 until AccountNodes) ++ (AccountNodes + 1 to AccountNodes + StorageNodes)).map(rlpFor)
    everyNode.foreach(rlp => now.nodes.get(ByteString(kec256(rlp))) should not be empty)
  }

  it should "be what a tracking instance was: one entry per distinct node, the leak the publish died of" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val store = trackingStore()
    PathToHashExporter.publish(seededPathStorage(), store.mpt)

    counted(store).trackedDeltaEntries shouldBe distinctNodes
  }

  it should "count a second publish of the same Path store again (a leak of references, never a lost node)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    // Pins what a restart after a crash mid-publish does to the nodes the first run had written: the count is
    // incremented per publish, so those nodes sit one reference high. A high count is a node that is never pruned; a
    // low one is a node pruned while live, so this is the safe side. Nothing makes the publish resumable or idempotent.
    val pns = seededPathStorage()
    val store = publishStore()
    PathToHashExporter.publish(pns, store.mpt)
    PathToHashExporter.publish(pns, store.mpt)

    references(store.nodes, rlpFor(1)) shouldBe 2
    references(store.nodes, rlpFor(0)) shouldBe 4
  }
