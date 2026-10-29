package com.chipprbots.ethereum.blockchain.sync.snap.actors

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import cats.effect.IO

import fs2.Stream

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.storage.CachedReferenceCountedStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.utils.NodeCacheConfig

/** The child JVM of the spec 022 kill-point harness (T021, `HealCrashSafetyItSpec`).
  *
  * It heals `PathHealPresenceFixtures.accountTrieOld` (eight nodes: branches, an extension, leaves) with a real
  * `TrieNodeHealingCoordinator` over a real RocksDB at `dbPath`, against an in-process peer that serves the trie. When
  * it reaches its kill point it prints `KP:<point>` on stdout and stops there: the writing thread parks, or the peer
  * leaves the request unanswered. The parent then sends SIGKILL, reopens the database and audits it. Nothing here waits
  * on time: every pause is a latch or a blocking read that only the kill ends.
  *
  * Arguments: `dbPath`, `path|hash`, `basic|inmemory`, and the kill point:
  *   - `held`: the root has arrived and is held (its children are requested; nothing below it has arrived);
  *   - `chain-before` / `chain-after`: inside the first commit write that carries two or more nodes (a node with the
  *     ancestors it completes), before or after the write is applied, i.e. before the coordinator releases the held
  *     entries;
  *   - `repeg-partial`: the pivot moves while the root and the extension are held (one subtree committed), and the kill
  *     lands in the first commit write after the re-peg;
  *   - `repeg-full`: the old trie is healed completely, the pivot moves to `accountTrieNew` (four nodes changed, the
  *     extension's subtree among them), and the kill lands in the first commit write after the re-peg.
  *
  * Commit writes are counted where healing makes them: Path, a write batch to the path-trie column families; Hash, a
  * `storeRawNodes` call (under in-memory pruning it reaches the node cache, not disk).
  */
object HealCrashChild:

  def main(args: Array[String]): Unit =
    try run(args)
    catch
      case t: Throwable =>
        System.out.println(s"ERR:$t")
        t.printStackTrace(System.out)
        System.out.flush()
        System.exit(2)

  private def run(args: Array[String]): Unit =
    val Array(dbPath, schemeArg, pruningArg, point) = args: @unchecked
    val scheme = if schemeArg == "path" then StorageScheme.Path else StorageScheme.Hash
    val pruning: PruningMode = if pruningArg == "basic" then BasicPruning(1000) else InMemoryPruning(1000)
    val gate = new KillGate(point)

    val rocks = RocksDbDataSource(rocksConfig(dbPath), Namespaces.nsSeq)
    val dataSource = new HookedDataSource(rocks, gate)
    val nodeStorage = new NodeStorage(dataSource)
    val stateStorage = StateStorage(pruning, nodeStorage, nodeCache(nodeStorage))
    val mpt: MptStorage =
      val backing = stateStorage.getBackingStorage(1)
      if scheme == StorageScheme.Hash then new HookedMptStorage(backing, gate) else backing
    val pathNodes = Option.when(scheme == StorageScheme.Path)(new PathNodeStorage(dataSource))

    val oldTrie = PathHealPresenceFixtures.accountTrieOld()
    val newTrie = PathHealPresenceFixtures.accountTrieNew()
    // `repeg-partial`: the old trie's brX is never served, so the root and the extension stay held.
    val withheld: Set[Seq[ByteString]] = if point == "repeg-partial" then Set(oldTrie("brX").pathset) else Set.empty
    val served = new AtomicReference[Map[Seq[ByteString], ByteString]](oldTrie.served -- withheld)
    val requests = new AtomicInteger(0)
    val repegReady = new CountDownLatch(1)
    val coordinatorRef = new AtomicReference[ActorRef[TrieNodeHealingCoordinator.Command]]()

    val testKit = ActorTestKit("heal-crash-child")
    val peerManager = testKit.spawn(Behaviors.receiveMessage[NetworkPeerManagerActor.Command] {
      case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
        message.underlyingMsg match
          case req: SNAP.GetTrieNodes =>
            val n = requests.incrementAndGet()
            if point == "held" && n == 2 then gate.signal("held") // the root's children: never answered
            else
              if withheld.nonEmpty && req.paths.forall(withheld.contains) then repegReady.countDown()
              val map = served.get()
              coordinatorRef.get() ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
                SNAP.TrieNodes(req.requestId, req.paths.flatMap(map.get))
              )
          case _ => ()
        Behaviors.same
      case _ => Behaviors.same
    })
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val pool = Executors.newSingleThreadExecutor()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = oldTrie.root.hash,
      networkPeerManager = peerManager,
      requestTracker = new SNAPRequestTracker()(testKit.system.classicSystem.scheduler),
      mptStorage = mpt,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = scheme,
      pathNodeStorageOpt = pathNodes,
      movingRootDeltaHeal = true
    )(using testKit)
    coordinatorRef.set(coordinator)
    def peerAvailable(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("heal-crash-peer", testKit.createTestProbe[Any]().ref.toClassic)
      )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(oldTrie.root.hash)
    peerAvailable()

    if point.startsWith("repeg") then
      if point == "repeg-full" then
        controller.fishForMessage(60.seconds) {
          case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
          case _                                       => FishingOutcomes.continueAndIgnore
        }
      else if !repegReady.await(60, TimeUnit.SECONDS) then throw new IllegalStateException("brX was never requested")
      gate.armRepeg()
      served.set(newTrie.served)
      coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash)
      peerAvailable()

    // Until the kill: a healing that ends without reaching the kill point says so, and still waits.
    controller.fishForMessage(60.seconds) {
      case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
      case _                                       => FishingOutcomes.continueAndIgnore
    }
    gate.signal("none")
    System.in.read()

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

  /** The in-memory pruning node cache as production wires it (evicted entries are written one by one). */
  def nodeCache(nodeStorage: NodeStorage): LruCache[NodeHash, HeapEntry] =
    new LruCache[NodeHash, HeapEntry](
      new NodeCacheConfig:
        override val maxSize: Long = 1_280_000
        override val maxHoldTime: FiniteDuration = 60.minutes
      ,
      Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
    )

/** Decides, at each commit write, whether this is the kill point; prints `KP:<point>` and parks the writing thread. */
final class KillGate(point: String):
  private val repegArmed = new AtomicBoolean(false)
  private val signalled = new AtomicBoolean(false)

  def armRepeg(): Unit = repegArmed.set(true)

  /** A commit write of `nodes` trie nodes; `apply` performs it. */
  def onCommitWrite(nodes: Int)(apply: () => Unit): Unit =
    point match
      case "chain-before" if nodes >= 2 && !signalled.get() =>
        signal(point)
        park()
      case "chain-after" if nodes >= 2 && !signalled.get() =>
        apply()
        signal(point)
        park()
      case "repeg-partial" | "repeg-full" if repegArmed.get() && !signalled.get() =>
        apply()
        signal(point)
        park()
      case _ => apply()

  def signal(name: String): Unit =
    if signalled.compareAndSet(false, true) || name == "none" then
      System.out.println(s"KP:$name")
      System.out.flush()

  private def park(): Unit = new CountDownLatch(1).await()

/** The Hash-scheme commit write: `storeRawNodes`, counted and gated. */
final class HookedMptStorage(underlying: MptStorage, gate: KillGate) extends MptStorage:
  def get(nodeId: Array[Byte]): MptNode = underlying.get(nodeId)
  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
    underlying.updateNodesInStorage(newRoot, toRemove)
  def persist(): Unit = underlying.persist()
  override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] = underlying.multiGetNodes(hashes)
  override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit =
    gate.onCommitWrite(nodes.size)(() => underlying.storeRawNodes(nodes))

/** A data source that passes everything through, gating the Path-scheme commit writes (batches that touch the path-trie
  * column families).
  */
final class HookedDataSource(underlying: DataSource, gate: KillGate) extends DataSource:
  import DataSource.*

  private val trieNamespaces = Set(Namespaces.StateTriePathNamespace, Namespaces.StorageTriePathNamespace)

  private def pathTrieUpserts(updates: Seq[DataUpdate]): Option[Int] =
    val touched = updates.collect {
      case DataSourceUpdateOptimized(ns, _, up) if trieNamespaces.contains(ns) => up.size
      case DataSourceUpdate(ns, _, up) if trieNamespaces.contains(ns)          => up.size
    }
    Option.when(touched.nonEmpty)(touched.sum)

  def get(namespace: Namespace, key: Key): Option[Value] = underlying.get(namespace, key)
  def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    underlying.getOptimized(namespace, key)
  override def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
    underlying.multiGetOptimized(namespace, keys)
  def scanRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]) =
    underlying.scanRange(namespace, fromKey, toKeyExclusive)
  def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
    underlying.deleteRange(namespace, fromKey, toKeyExclusive)
  def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
    pathTrieUpserts(dataSourceUpdates) match
      case Some(nodes) => gate.onCommitWrite(nodes)(() => underlying.update(dataSourceUpdates))
      case None        => underlying.update(dataSourceUpdates)
  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit = underlying.updateSync(dataSourceUpdates)
  def clear(): Unit = underlying.clear()
  def close(): Unit = underlying.close()
  def destroy(): Unit = underlying.destroy()
  def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = underlying.iterate()
  def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    underlying.iterate(namespace)
