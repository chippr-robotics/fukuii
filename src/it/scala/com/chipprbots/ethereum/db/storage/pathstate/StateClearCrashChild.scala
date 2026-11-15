package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.appstate.BlockInfo

/** The child of [[StateClearCrashItSpec]]: a real RocksDB datadir that holds a state on the unified layout, cleared for
  * a sync from scratch ([[PathStateStorage.clearForResync]]) and stopped at one point of the clear. It prints
  * `KP:<point>` and parks there, and its parent kills it.
  *
  * Arguments: the datadir, and the point:
  *   - `before-batch`: the clear's first synced batch has not been written;
  *   - `after-batch`: it has, and no range has been deleted;
  *   - `mid-ranges`: the rows and the records are deleted (`v`, `t`, `u`), what the sync kept is not yet;
  *   - `before-mark`: every range is deleted, and the mark is still set;
  *   - `after-mark`: the mark's removal has returned.
  *
  * A kill is not a loss of power: what was written reaches the operating system and stays. This tells the order of the
  * writes and what each leaves, not that a synced write is on the disk.
  */
object StateClearCrashChild:

  val GenesisHash: ByteString = ByteString(Array.fill[Byte](32)(1))
  val Held: BlockRef = BlockRef(ByteString(Array.fill[Byte](32)(7)), 5000, ByteString(Array.fill[Byte](32)(8)))
  val Escalation: StateEscalation =
    StateEscalation(1763236800000L, EscalationKind.Unreachable, ByteString(Array.fill[Byte](32)(9)), 5200)

  /** The column families the clear deletes by range, with a key this child puts in each. */
  val Families: Seq[(String, IndexedSeq[Byte])] = Seq(
    "v" -> Namespaces.StateChangesetNamespace,
    "t" -> Namespaces.StateTriePathNamespace,
    "u" -> Namespaces.StorageTriePathNamespace,
    "p" -> Namespaces.SnapSyncProgressNamespace
  )

  def main(args: Array[String]): Unit =
    try run(args(0), args(1))
    catch
      case t: Throwable =>
        System.out.println(s"ERR:$t")
        t.printStackTrace(System.out)
        System.out.flush()
        sys.exit(2)

  private def run(dbPath: String, point: String): Unit =
    val rocks = RocksDbDataSource(rocksConfig(dbPath), Namespaces.nsSeq)
    seed(rocks)
    val gate = new Gated(rocks, point)
    val state = new PathStateStorage(new PathNodeStorage(gate), new AppStateStorage(gate))
    state.clearForResync(GenesisHash, Escalation.atMillis, Some(Escalation))
    gate.signal("none")
    new CountDownLatch(1).await()

  /** A datadir that synced to `Held` with SNAP and has followed the chain: rows, records, the sync's own record. */
  private def seed(rocks: RocksDbDataSource): Unit =
    val rows: Seq[DataUpdate] = Families.map { (name, family) =>
      DataSourceUpdateOptimized(
        family,
        Nil,
        (1 to 20).map(i => (Array.fill[Byte](33)(i.toByte), s"a row of $name $i".getBytes))
      )
    }
    rocks.update(rows)
    new SnapSyncProgressStorage(rocks).recordExcluded(
      Seq(ExcludedAccount(ByteString(Array.fill[Byte](32)(3)), ExcludedReason.StorageRootMismatch))
    )
    val appState = new AppStateStorage(rocks)
    appState
      .markDatadirCreatedUnderInvariant()
      .and(appState.putStateLayout())
      .and(appState.putStateAnchor(Held))
      .and(
        appState.putStateOrigin(
          BlockRef(ByteString(Array.fill[Byte](32)(5)), 4000, ByteString(Array.fill[Byte](32)(6)))
        )
      )
      .and(appState.putBestBlockInfo(BlockInfo(Held.hash, Held.number)))
      .and(appState.snapSyncDone())
      .and(appState.putSnapSyncPivotBlock(4000))
      .and(appState.putSnapSyncAccountsComplete(true))
      .commitSync()

  private def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

  /** The store, with the clear's writes counted: its synced batches and its range deletes. At `point` it says so and
    * the writing thread parks.
    */
  final private class Gated(underlying: DataSource, point: String) extends DataSource:
    import DataSource.*
    private val synced = new AtomicInteger(0)
    private val ranges = new AtomicInteger(0)

    def signal(name: String): Unit =
      System.out.println(s"KP:$name")
      System.out.flush()

    private def stopAt(name: String): Unit =
      if point == name then
        signal(name)
        new CountDownLatch(1).await()

    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      synced.incrementAndGet() match
        case 1 =>
          stopAt("before-batch")
          underlying.updateSync(dataSourceUpdates)
          stopAt("after-batch")
        case _ =>
          stopAt("before-mark")
          underlying.updateSync(dataSourceUpdates)
          stopAt("after-mark")

    def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      underlying.deleteRange(namespace, fromKey, toKeyExclusive)
      if ranges.incrementAndGet() == 3 then stopAt("mid-ranges")

    def get(namespace: Namespace, key: Key): Option[Value] = underlying.get(namespace, key)
    def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
      underlying.getOptimized(namespace, key)
    override def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
      underlying.multiGetOptimized(namespace, keys)
    def scanRange(
        namespace: Namespace,
        fromKey: Array[Byte],
        toKeyExclusive: Array[Byte]
    ): Iterator[(Array[Byte], Array[Byte])] = underlying.scanRange(namespace, fromKey, toKeyExclusive)
    def update(dataSourceUpdates: Seq[DataUpdate]): Unit = underlying.update(dataSourceUpdates)
    def clear(): Unit = underlying.clear()
    def close(): Unit = underlying.close()
    def destroy(): Unit = underlying.destroy()
    def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = underlying.iterate()
    def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
      underlying.iterate(namespace)
