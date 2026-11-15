package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.appstate.BlockInfo

/** The child of [[HandoffCrashItSpec]]: a real RocksDB datadir at the end of a sync on the unified layout, handed off
  * ([[PathStateStorage.commitHandoff]]) and stopped at one point. It prints `KP:<point>` and parks there, and its
  * parent kills it.
  *
  * Arguments: the datadir, and the point:
  *   - `before-commit`: the rows are written, as a sync writes them, with no sync of the log; the hand-off's batch has
  *     not been written;
  *   - `after-commit`: the batch's synced write has returned.
  *
  * A kill is not a loss of power: what was written reaches the operating system and stays. This tells what each side of
  * the one write leaves, not that a synced write is on the disk.
  */
object HandoffCrashChild:

  /** The state the sync wrote: the same in the child and in its parent, which looks it up again. */
  def synced(): PathStateTestKit.HashedState = new PathStateTestKit.HashedState

  def pivotOf(state: PathStateTestKit.HashedState): BlockRef =
    BlockRef(ByteString(Array.fill[Byte](32)(7)), 5000, state.root)

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
    val hashed = synced()
    // the datadir's stamp is its genesis commit's, long before; the rows are the sync's, written with no sync
    new AppStateStorage(rocks).putStateLayout().commitSync()
    PathStateTestKit.writeState(new PathNodeStorage(rocks), hashed.positions())
    val gate = new Gated(rocks, point)
    val appState = new AppStateStorage(gate)
    val state = new PathStateStorage(new PathNodeStorage(gate), appState)
    val pivot = pivotOf(hashed)
    state.commitHandoff(pivot, Some(appState.putBestBlockInfo(BlockInfo(pivot.hash, pivot.number))))
    gate.signal("none")
    new CountDownLatch(1).await()

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

  /** The store, stopped on either side of its first synced write: the hand-off's batch. */
  final private class Gated(underlying: DataSource, point: String) extends DataSource:
    import DataSource.*
    private val synced = new AtomicInteger(0)

    def signal(name: String): Unit =
      System.out.println(s"KP:$name")
      System.out.flush()

    private def stopAt(name: String): Unit =
      if point == name then
        signal(name)
        new CountDownLatch(1).await()

    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      if synced.incrementAndGet() == 1 then
        stopAt("before-commit")
        underlying.updateSync(dataSourceUpdates)
        stopAt("after-commit")
      else underlying.updateSync(dataSourceUpdates)

    def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      underlying.deleteRange(namespace, fromKey, toKeyExclusive)
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
