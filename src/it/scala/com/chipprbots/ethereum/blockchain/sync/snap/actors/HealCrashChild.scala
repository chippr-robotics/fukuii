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
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
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
  * It heals a fixture trie with a real `TrieNodeHealingCoordinator` over a real RocksDB at `dbPath`, against an
  * in-process peer that serves the trie: `account` is `PathHealPresenceFixtures.accountTrieOld` (eight nodes: branches,
  * an extension, leaves), `storage` is `storageTrieOld` (an account leaf at the root over a storage trie, so a chain
  * commit spans both trie column families and the audit checks a storage root). When it reaches its kill point it
  * prints `KP:<point>` on stdout and stops there: the writing thread parks, or the peer leaves the request unanswered.
  * The parent then sends SIGKILL, reopens the database and audits it. Nothing here waits on time: every pause is a
  * latch or a blocking read that only the kill ends.
  *
  * Arguments: `dbPath`, `path|hash`, `basic|inmemory`, `account|storage`, and the kill point:
  *   - `held`: the root has arrived and is held (its children are requested; nothing below it has arrived);
  *   - `chain-before` / `chain-after`: inside the first commit write that carries two or more nodes (a node with the
  *     ancestors it completes), before or after the write is applied, i.e. before the coordinator releases the held
  *     entries;
  *   - `repeg-partial`: the pivot moves while the upper nodes are held (one subtree committed), and the kill lands in
  *     the first commit write after the re-peg that writes a node;
  *   - `repeg-full`: the old trie is healed completely, the pivot moves to the new build of the fixture, and the kill
  *     lands in the first commit write after the re-peg that writes a node;
  *   - `repeg-delete` (Path only): as `repeg-full`, but the kill lands in the first write after the re-peg that only
  *     deletes (the stale positions of the old root, found before anything new is written there);
  *   - `excluded-write` / `excluded-written`: the trie is healed, and the kill lands inside the fsynced write of an
  *     excluded-account entry, before or after it is applied;
  *   - `excluded-resolved` / `excluded-removed`: an account on disk with its storage abandoned and an entry that says
  *     so; the kill lands right after the first chain committed under it (the seed, or a walk, can have asked for it),
  *     or right after the resolver takes the entry out;
  *   - `code-recorded` / `code-committed` (the `contract` fixture): only bytecode blocks, 30 idle minutes are ticked
  *     by, and the kill lands right after the awaited hashes are recorded, or right after the chains waiting on them
  *     are written;
  *   - `excluded-delete` (Path only): as `excluded-written`, then a coordinator starts over the entry, and the kill
  *     lands right after it has deleted every account-trie position on the entry's key (T034).
  *
  * Commit writes are counted where healing makes them: Path, a write batch to the path-trie column families; Hash, a
  * `storeRawNodes` call (under in-memory pruning it reaches the node cache, which here holds two entries, so most of a
  * write is evicted to disk at once, one node at a time, as production's cache does when it is full). The fixtures also
  * include `twin`, two accounts with one storage trie (`HealRequestFixtures.twinStorageTrie`), so a chain commit and a
  * re-peg delete run over aliased positions (vault V4, slice 3).
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
    val Array(dbPath, schemeArg, pruningArg, fixture, point) = args: @unchecked
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

    val (oldTrie, newTrie) = HealCrashChild.fixture(fixture)
    val progress = new SnapSyncProgressStorage(dataSource)
    // `excluded-resolved`: an account was committed, its storage was abandoned and the entry says so: the resolver seeds
    // the storage root, and the first chain it commits is what the kill lands after.
    val resolving = point == "excluded-resolved" || point == "excluded-removed"
    if resolving then
      val onDisk = oldTrie.nodes.filterNot(n => Set("sRoot", "sLeaf1")(n.label))
      pathNodes match
        case Some(_) => PathHealPresenceFixtures.seedPath(new PathNodeStorage(rocks), onDisk)
        case None =>
          val backing = stateStorage.getBackingStorage(1)
          backing.storeRawNodes(onDisk.map(n => (n.hash, n.encoded.toArray)))
          backing.persist()
      progress.recordExcluded(
        Seq(ExcludedAccount(HealCrashChild.excludedSubject(fixture, oldTrie), ExcludedReason.StorageAbandoned))
      )
      gate.armExcludedDelete() // the next write to the set is the resolver's
    // `code-*`: the contract fixture's code is never served; the coordinator is told 30 idle minutes have passed.
    val codeHandOff = point.startsWith("code-")
    // `repeg-partial`: one node of the old trie is never served, so the nodes above it stay held.
    val withheld: Set[Seq[ByteString]] =
      if point == "repeg-partial" then HealCrashChild.withheldLabels(fixture).map(oldTrie(_).pathset).toSet
      else Set.empty
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
    // Two writer threads, as production's pool has several (the coordinator keeps one healed-node write at a time).
    val pool = Executors.newFixedThreadPool(2)
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
      movingRootDeltaHeal = true,
      evmCodeStorage = Option.when(codeHandOff)(new EvmCodeStorage(dataSource)),
      excludedStore = Option.when(resolving || codeHandOff)(progress)
    )(using testKit)
    coordinatorRef.set(coordinator)
    def peerAvailable(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("heal-crash-peer", testKit.createTestProbe[Any]().ref.toClassic)
      )

    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(oldTrie.root.hash)
    peerAvailable()

    if codeHandOff then
      val awaited = oldTrie.nodes.flatMap(HealCrashChild.contractCode).toSet
      var reported = Set.empty[ByteString]
      controller.fishForMessage(60.seconds) {
        case SNAPSyncController.HealedCodeHashes(hashes) =>
          reported ++= hashes
          if reported == awaited then FishingOutcomes.complete else FishingOutcomes.continueAndIgnore
        case _ => FishingOutcomes.continueAndIgnore
      }
      (1 to TrieNodeHealingCoordinator.MaxBlockedTicks).foreach(_ =>
        coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick
      )

    if point.startsWith("excluded") && !resolving then
      controller.fishForMessage(60.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
      // The excluded-write points land inside this call, on the writing thread: this one.
      progress.recordExcluded(
        Seq(ExcludedAccount(HealCrashChild.excludedSubject(fixture, oldTrie), ExcludedReason.StorageAbandoned))
      )
      gate.armExcludedDelete()
      HealingTrieFixtures.spawnCoordinator(
        stateRoot = oldTrie.root.hash,
        networkPeerManager = peerManager,
        requestTracker = new SNAPRequestTracker()(testKit.system.classicSystem.scheduler),
        mptStorage = mpt,
        batchSize = 16,
        snapSyncController = controller.ref,
        healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
        storageScheme = scheme,
        pathNodeStorageOpt = pathNodes,
        movingRootDeltaHeal = true,
        excludedStore = Some(progress)
      )(using testKit) ! TrieNodeHealingCoordinator.StartTrieNodeHealing(oldTrie.root.hash)

    if point.startsWith("repeg") then
      if point == "repeg-full" || point == "repeg-delete" then
        controller.fishForMessage(60.seconds) {
          case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
          case _                                       => FishingOutcomes.continueAndIgnore
        }
      else if !repegReady.await(60, TimeUnit.SECONDS) then
        throw new IllegalStateException("the withheld node was never requested alone")
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

  /** The in-memory pruning node cache as production wires it (evicted entries are written one by one), but holding only
    * two entries, so the harness's small tries are evicted to disk as production's cache is when full (vault V4). Below
    * 20 entries Guava keeps one segment, so the eviction order is plain least-recently-used and deterministic.
    */
  def nodeCache(nodeStorage: NodeStorage): LruCache[NodeHash, HeapEntry] =
    new LruCache[NodeHash, HeapEntry](
      new NodeCacheConfig:
        override val maxSize: Long = 2
        override val maxHoldTime: FiniteDuration = 60.minutes
      ,
      Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
    )

  /** The old and new builds of the fixture named `name`. */
  def fixture(name: String): (PathHealPresenceFixtures.Trie, PathHealPresenceFixtures.Trie) =
    name match
      case "account" => (PathHealPresenceFixtures.accountTrieOld(), PathHealPresenceFixtures.accountTrieNew())
      case "storage" => (PathHealPresenceFixtures.storageTrieOld(), PathHealPresenceFixtures.storageTrieNew())
      case "twin"    => (healing.HealRequestFixtures.twinStorageTrie(1), healing.HealRequestFixtures.twinStorageTrie(2))
      case "contract" => (contractTrie, contractTrie)
      case other      => throw new IllegalArgumentException(s"unknown fixture $other")

  private val contractRest: Seq[Array[Byte]] =
    Seq(5 -> 1, 3 -> 7).map { case (m, a) => (0 until 63).map(i => ((i * m + a) % 16).toByte).toArray }

  /** A root over two contract accounts whose bytecode no peer has. */
  private lazy val contractTrie: PathHealPresenceFixtures.Trie =
    def leaf(i: Int): com.chipprbots.ethereum.mpt.LeafNode =
      val account = com.chipprbots.ethereum.domain.Account(
        nonce = com.chipprbots.ethereum.domain.UInt256(i.toLong + 1),
        balance = com.chipprbots.ethereum.domain.UInt256(1L),
        codeHash = com.chipprbots.ethereum.domain.CodeHash(
          com.chipprbots.ethereum.crypto.kec256(ByteString(s"heal-crash/contract-code-$i"))
        )
      )
      com.chipprbots.ethereum.mpt.LeafNode(
        ByteString(contractRest(i)),
        ByteString(com.chipprbots.ethereum.domain.Account.accountSerializer.toBytes(account))
      )
    val (acct0, acct1) = (leaf(0), leaf(1))
    val root = healing.HealRequestFixtures.branch(0 -> acct0, 1 -> acct1)
    PathHealPresenceFixtures.Trie(
      Seq(
        PathHealPresenceFixtures.Placed("root", None, Array.empty[Byte], root),
        PathHealPresenceFixtures.Placed("acct0", None, Array[Byte](0), acct0),
        PathHealPresenceFixtures.Placed("acct1", None, Array[Byte](1), acct1)
      )
    )

  /** The code hash an account leaf of the contract fixture names. */
  def contractCode(n: PathHealPresenceFixtures.Placed): Option[ByteString] =
    n.node match
      case l: com.chipprbots.ethereum.mpt.LeafNode =>
        com.chipprbots.ethereum.domain.Account(l.value).toOption.map(_.codeHash.value)
      case _ => None

  /** The positions `repeg-partial` never serves, so the nodes above them stay held across the re-peg. Under `twin` one
    * node at both of its positions: a fetch of it may go out from either.
    */
  def withheldLabels(fixture: String): Seq[String] =
    fixture match
      case "storage" => Seq("sLeaf1")
      case "twin"    => Seq("s0B", "s1B")
      case _         => Seq("brX")

  /** The key of the entry the `excluded-*` points record: an account of the fixture. `account` has no real account, so
    * any key through `leafX1`'s position does.
    */
  def excludedSubject(fixture: String, trie: PathHealPresenceFixtures.Trie): ByteString =
    fixture match
      case "storage" => trie("sRoot").accountHash.get
      case "twin"    => trie("s0").accountHash.get
      case _ =>
        healing.HealRequestFixtures.packNibbles(Array[Byte](0, 5, 6, 1) ++ new Array[Byte](60))

/** Decides, at each commit write, whether this is the kill point; prints `KP:<point>` and parks the writing thread. */
final class KillGate(point: String):
  private val repegArmed = new AtomicBoolean(false)
  private val signalled = new AtomicBoolean(false)
  private val excludedDeleteArmed = new AtomicBoolean(false)

  def armRepeg(): Unit = repegArmed.set(true)
  def armExcludedDelete(): Unit = excludedDeleteArmed.set(true)

  /** A write to the app-state column family's excluded set; `apply` performs it. */
  def onExcludedWrite(apply: () => Unit): Unit =
    point match
      case "excluded-write" if !signalled.get() =>
        signal(point)
        park()
      case "excluded-written" | "code-recorded" if !signalled.get() =>
        apply()
        signal(point)
        park()
      case "excluded-removed" if excludedDeleteArmed.get() && !signalled.get() =>
        apply()
        signal(point)
        park()
      case _ => apply()

  /** A commit write of `nodes` trie nodes; `apply` performs it. */
  def onCommitWrite(nodes: Int)(apply: () => Unit): Unit =
    point match
      case "chain-before" if nodes >= 2 && !signalled.get() =>
        signal(point)
        park()
      case "chain-after" | "excluded-resolved" | "code-committed" if nodes >= 2 && !signalled.get() =>
        apply()
        signal(point)
        park()
      // vault V4 (e): the re-peg points count only writes that put a node; a write that only deletes is its own point.
      case "repeg-partial" | "repeg-full" if repegArmed.get() && nodes >= 1 && !signalled.get() =>
        apply()
        signal(point)
        park()
      case "repeg-delete" if repegArmed.get() && nodes == 0 && !signalled.get() =>
        apply()
        signal(point)
        park()
      case "excluded-delete" if excludedDeleteArmed.get() && nodes == 0 && !signalled.get() =>
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
  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
    val excludedSetWrite = dataSourceUpdates.exists {
      case DataSourceUpdateOptimized(ns, _, _) => ns == Namespaces.AppStateNamespace
      case _                                   => false
    }
    if excludedSetWrite then gate.onExcludedWrite(() => underlying.updateSync(dataSourceUpdates))
    else underlying.updateSync(dataSourceUpdates)
  def clear(): Unit = underlying.clear()
  def close(): Unit = underlying.close()
  def destroy(): Unit = underlying.destroy()
  def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = underlying.iterate()
  def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
    underlying.iterate(namespace)
