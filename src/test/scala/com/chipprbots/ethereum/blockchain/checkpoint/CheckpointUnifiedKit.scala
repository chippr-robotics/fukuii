package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportError
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportResult
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.NodeSink
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.Trust
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2Fixtures.ArchivePruningConfig
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.Op
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.SyncHandoff
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows

/** What the specs of the checkpoint import on the unified layout share (spec 018 C5c PR3): a datadir over a data source
  * that records every write and can die before one, and the rows it holds.
  */
object CheckpointUnifiedKit:

  /** The process died before this write reached the disk. */
  final class Killed(val atWrite: Int) extends RuntimeException(s"killed before write $atWrite")

  /** A write is an `update`, an `updateSync` or a `deleteRange`, counted in the order they are made. */
  final class Faulty extends EphemDataSource(Map.empty):
    val writes: mutable.ArrayBuffer[(String, Seq[Op])] = mutable.ArrayBuffer.empty
    @volatile var killBefore: Option[Int] = None

    private def gate(kind: String, ops: Seq[Op]): Unit =
      val index = writes.size
      writes += ((kind, ops))
      if killBefore.contains(index) then throw new Killed(index)

    override def update(updates: Seq[DataUpdate]): Unit =
      gate("plain", updates.flatMap(Op.of))
      super.update(updates)
    override def updateSync(updates: Seq[DataUpdate]): Unit =
      gate("sync", updates.flatMap(Op.of))
      super.update(updates)
    override def deleteRange(namespace: IndexedSeq[Byte], from: Array[Byte], to: Array[Byte]): Unit =
      gate("range", Seq(Op(namespace, delete = true, ByteString(from))))
      super.deleteRange(namespace, from, to)

  def keyOf(name: String): ByteString = ByteString(name.getBytes("UTF-8"))
  val AppNs: IndexedSeq[Byte] = Namespaces.AppStateNamespace
  val StateRows: IndexedSeq[Byte] = Namespaces.StateTriePathNamespace
  val StorageRows: IndexedSeq[Byte] = Namespaces.StorageTriePathNamespace

  /** A datadir of the unified layout over `source`: a restart is a new one over the same source. */
  final class Datadir(val source: Faulty, val genesis: BlockHeader, batch: Int = CheckpointImporter.NodeBatchSize):
    val component: EphemDataSourceComponent & ArchivePruningConfig & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with CheckpointImporterSpec.FixedDataSource(source)
        with ArchivePruningConfig
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = true
    val storages = component.storages
    val appState = storages.appStateStorage
    val state = storages.pathStateStorage.get
    val rows = new PathNodeStorage(source)
    val genesisRef: BlockRef = BlockRef(genesis.hash.value, 0, genesis.stateRoot.value)
    val handoff = new SyncHandoff(
      state,
      rows,
      appState,
      storages.blockHeadersStorage,
      storages.blockNumberMappingStorage,
      storages.chainWeightStorage,
      storages.blockBodiesStorage,
      storages.evmCodeStorage
    )
    val target = new CheckpointImporter.UnifiedTarget(state, rows, handoff, appState, () => genesisRef, () => 1L)

    /** The first state of a datadir, as a node commits it. */
    def commitGenesis(): Unit = state.commitGenesis(state.genesisLayer(), genesisRef, None)

    def importer(trust: Trust, retryRefused: Boolean = false, smallBatches: Boolean = false): CheckpointImporter =
      new CheckpointImporter(
        BlockchainWriter(storages),
        storages.stateStorage,
        storages.evmCodeStorage,
        appState,
        trust,
        unified = Some(target),
        sinkFor = Option.when(smallBatches)((_: BigInt) => new SmallBatches(rows, batch)),
        retryRefused = retryRefused
      )

    def importBytes(
        bytes: Array[Byte],
        trust: Trust,
        retryRefused: Boolean = false,
        smallBatches: Boolean = false
    ): Either[ImportError, ImportResult] =
      importer(trust, retryRefused, smallBatches).importFromStream(new java.io.ByteArrayInputStream(bytes))

    /** Every row of the position families, trie by trie, as the specs of the exporter give the positions. */
    def stored: Map[Option[ByteString], Rows] =
      val acc = mutable.ArrayBuffer.empty[(Option[ByteString], ByteString, ByteString)]
      rows.foreachAccountNodeChunk(1000)(_.foreach((k, v) => acc += ((None, ByteString(k), ByteString(v)))))
      rows.foreachStorageNodeChunk(1000)(
        _.foreach((k, v) => acc += ((Some(ByteString(k.take(32))), ByteString(k.drop(32)), ByteString(v))))
      )
      acc.groupBy(_._1).map((owner, rs) => owner -> rs.map(r => r._2 -> r._3).toMap)

    def rowCount: Int = stored.values.map(_.size).sum

  /** The row sink with batches of `size`, so that a small state is written in several. */
  final class SmallBatches(rows: PathNodeStorage, size: Int) extends NodeSink:
    private val buf = mutable.ArrayBuffer.empty[DataUpdate]
    def node(owner: Option[ByteString], path: Array[Byte], hash: ByteString, rlp: Array[Byte]): Unit =
      buf += owner.fold[DataUpdate](rows.accountNodeUpsert(path, rlp))(o => rows.storageNodeUpsert(o, path, rlp))
      if buf.size >= size then flush()
    def flush(): Unit =
      if buf.nonEmpty then
        rows.dataSource.update(buf.toSeq)
        buf.clear()

  /** Every key and value of a source, to show that a refused import wrote nothing. */
  def snapshot(source: EphemDataSource): Map[java.nio.ByteBuffer, Seq[Byte]] =
    source.storage.map((k, v) => k -> v.toSeq)
