package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.HeapWatchdogStart
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability

/** Shared controller fixture for the spec 016 characterization suites (T020).
  *
  * It drives the real `SNAPSyncController` with every seam from S0b (T012):
  *   - `ChildFactories`: [[RecordingChildFactories]]. No real child runs; each spawn is recorded on `spawns` with its
  *     full argument list, each child forwards what it receives to its inbox probe, and reports its stop on `stops`.
  *   - `heapWatchdogStart`: counts starts, and returns a handle whose stop is counted (no JMX listener is installed).
  *   - the intake budget: `budget`, injected, so tests can reserve credit and read it back.
  *
  * Time is `ManualTime` (use [[SnapControllerFixture.config]] for the test kit): no timer fires unless a test advances
  * the clock. The network peer manager is a stub that answers each peer poll with `peers`, then forwards the poll (and
  * every other message) to `npm`, so a test that has seen the poll on `npm` knows the reply is already in the
  * controller's mailbox. `awaitProcessed` is a mailbox barrier.
  *
  * The store is ephemeral (`EphemBlockchainTestSetup`). Existing `SNAP*Spec` fixtures are not migrated to this one
  * (spec 016 T020).
  *
  * Decision recorded for T020 (plan D1): `-Wsafe-init` is NOT turned on. scalac options apply per sbt module, not per
  * package, so it cannot be scoped to `snap`; on the whole root module it would report on code outside the split. The
  * trait-initialization hazard is covered instead by FR-016 (d) (`scripts/snap-split/verify.sh`, S0f) and by every
  * controller suite constructing the controller.
  */
class SnapControllerFixture(val testKit: ActorTestKit) extends EphemBlockchainTestSetup with TestSyncConfig:
  import SnapControllerFixture.*

  implicit override lazy val classicSystem: ActorSystem = testKit.system.classicSystem

  val manualTime: ManualTime = ManualTime()(using testKit.system)

  val appStateStorage: AppStateStorage = storagesInstance.storages.appStateStorage

  // ── Network peer manager stub ────────────────────────────────────────────────────────────────

  val peers: AtomicReference[Map[Peer, PeerInfo]] = new AtomicReference(Map.empty)
  val npm: TestProbe[NetworkPeerManagerActor.Command] = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
  val npmStub: ActorRef[NetworkPeerManagerActor.Command] =
    testKit.spawn(Behaviors.receiveMessage[NetworkPeerManagerActor.Command] {
      case poll @ GetHandshakedPeersCmd(replyTo) =>
        replyTo ! HandshakedPeers(peers.get())
        npm.ref ! poll
        Behaviors.same
      case other =>
        npm.ref ! other
        Behaviors.same
    })

  // ── Parent and children ──────────────────────────────────────────────────────────────────────

  val parent: TestProbe[SyncProtocol.SyncControllerReply] =
    testKit.createTestProbe[SyncProtocol.SyncControllerReply]()
  val spawns: TestProbe[ChildSpawn] = testKit.createTestProbe[ChildSpawn]()
  val stops: TestProbe[ChildStopped] = testKit.createTestProbe[ChildStopped]()
  val accountInbox: TestProbe[AccountRangeCoordinator.Command] =
    testKit.createTestProbe[AccountRangeCoordinator.Command]()
  val storageInbox: TestProbe[StorageRangeCoordinator.Command] =
    testKit.createTestProbe[StorageRangeCoordinator.Command]()
  val healingInbox: TestProbe[TrieNodeHealingCoordinator.Command] =
    testKit.createTestProbe[TrieNodeHealingCoordinator.Command]()
  val byteCodeInbox: TestProbe[ByteCodeCoordinator.Command] = testKit.createTestProbe[ByteCodeCoordinator.Command]()
  val chainInbox: TestProbe[ChainDownloader.Command] = testKit.createTestProbe[ChainDownloader.Command]()

  val factories = new RecordingChildFactories(
    spawns.ref,
    accountInbox = Some(accountInbox.ref),
    storageInbox = Some(storageInbox.ref),
    healingInbox = Some(healingInbox.ref),
    byteCodeInbox = Some(byteCodeInbox.ref),
    chainInbox = Some(chainInbox.ref),
    stopped = Some(stops.ref)
  )

  // ── #1501 seams ──────────────────────────────────────────────────────────────────────────────

  val budget = new SnapIntakeBudget(maxPendingStorageTasks = 1_000L, maxPendingByteCodeHashes = 1_000L)

  val heapWatchdogStarts = new AtomicInteger(0)
  val heapWatchdogStops = new AtomicInteger(0)
  val heapWatchdogStart: HeapWatchdogStart = (highFraction, lowFraction, _, onChange, _, _, _) =>
    heapWatchdogStarts.incrementAndGet()
    val watchdog = new SnapHeapWatchdog(new HeapPressureHysteresis(highFraction, lowFraction), () => None, onChange)
    // The executor never gets a task, so it never starts a thread; Handle.stop shuts it down.
    Some(
      new SnapHeapWatchdog.Handle(
        watchdog,
        Executors.newSingleThreadScheduledExecutor(),
        () => countWatchdogStop()
      )
    )

  private def countWatchdogStop(): Unit =
    heapWatchdogStops.incrementAndGet()
    ()

  // ── Controller ───────────────────────────────────────────────────────────────────────────────

  private val progressProbe = testKit.createTestProbe[SyncProgress]()
  private val statusProbe = testKit.createTestProbe[SyncProtocol.Status]()

  /** Spawn the controller and wait for `start()`'s immediate peer poll, so the current `peers` are in its mailbox. */
  def spawnController(
      config: SNAPSyncConfig,
      isPoS: Boolean = false,
      validatorFactory: MptStorage => StateValidator = new StateValidator(_)
  ): ActorRef[SNAPSyncController.Command] =
    given ExecutionContext = testKit.system.executionContext
    val snap = testKit.spawn(
      SNAPSyncController(
        blockchainReader,
        blockchainWriter,
        appStateStorage,
        storagesInstance.storages.stateStorage,
        storagesInstance.storages.evmCodeStorage,
        storagesInstance.storages.flatSlotStorage,
        npmStub,
        testKit.spawn(PeerEventBusActor.behavior()),
        syncConfig,
        config,
        testKit.system.classicSystem.scheduler,
        CacheBasedBlacklist.empty(100),
        parent.ref,
        validatorFactory = validatorFactory,
        isPoSChainOverride = Option.when(isPoS)(true),
        childFactories = factories,
        heapWatchdogStart = heapWatchdogStart,
        intakeBudgetOverride = Some(budget)
      )
    )
    awaitPoll()
    snap

  /** Mailbox barrier: every message this test sent to `snap` before it has been handled once the reply arrives. */
  def awaitProcessed(snap: ActorRef[SNAPSyncController.Command]): Unit =
    snap ! SNAPSyncController.GetProgress(progressProbe.ref)
    progressProbe.receiveMessage(10.seconds)
    ()

  def status(snap: ActorRef[SNAPSyncController.Command]): SyncProtocol.Status =
    snap ! SNAPSyncController.GetStatus(statusProbe.ref)
    statusProbe.receiveMessage(10.seconds)

  /** Wait until the stub has answered a peer poll (its reply is then in the controller's mailbox). */
  def awaitPoll(): Unit =
    npm.fishForMessage(10.seconds) {
      case _: GetHandshakedPeersCmd => FishingOutcomes.complete
      case _                        => FishingOutcomes.continueAndIgnore
    }
    ()

  /** Swap the peer set and poll; returns once the new set is in the controller's mailbox. */
  def pollWith(snap: ActorRef[SNAPSyncController.Command], newPeers: Map[Peer, PeerInfo]): Unit =
    peers.set(newPeers)
    snap ! SNAPSyncController.PollHandshakedPeers
    awaitPoll()

  // ── Fences: what a stub child received, in order ─────────────────────────────────────────────
  // A stub child forwards to its inbox probe asynchronously, so a controller barrier alone does not prove a child has
  // (or has not) received something. A fence does: `sendFence` makes the controller send `*PeerUnavailable(id)` to every
  // child it currently has (FlushPeerDisconnects), after everything it sent them before, and `*Before(id)` collects what
  // a child received up to that fence. The fence's only side effects are dropping an unknown peer id from the peer
  // list and arming the 3 s debounce timer (which then flushes nothing).

  private val fences = new AtomicInteger(0)

  def sendFence(snap: ActorRef[SNAPSyncController.Command]): String =
    val id = s"s0d-fence-${fences.incrementAndGet()}"
    snap ! SNAPSyncController.WrappedPeerDisconnected(PeerId(id))
    snap ! SNAPSyncController.FlushPeerDisconnects
    id

  private def beforeFence[M](probe: TestProbe[M])(isFence: M => Boolean): Seq[M] =
    val out = Seq.newBuilder[M]
    var reached = false
    while !reached do
      val msg = probe.receiveMessage(10.seconds)
      if isFence(msg) then reached = true else out += msg
    out.result()

  def accountBefore(id: String): Seq[AccountRangeCoordinator.Command] =
    beforeFence(accountInbox) {
      case AccountRangeCoordinator.PeerUnavailable(`id`) => true
      case _                                             => false
    }

  def storageBefore(id: String): Seq[StorageRangeCoordinator.Command] =
    beforeFence(storageInbox) {
      case StorageRangeCoordinator.StoragePeerUnavailable(`id`) => true
      case _                                                    => false
    }

  def byteCodeBefore(id: String): Seq[ByteCodeCoordinator.Command] =
    beforeFence(byteCodeInbox) {
      case ByteCodeCoordinator.ByteCodePeerUnavailable(`id`) => true
      case _                                                 => false
    }

  def healingBefore(id: String): Seq[TrieNodeHealingCoordinator.Command] =
    beforeFence(healingInbox) {
      case TrieNodeHealingCoordinator.HealingPeerUnavailable(`id`) => true
      case _                                                       => false
    }

  /** Fish the first message on `probe` for which `matches` holds, ignoring the others. */
  def fishFor[M](probe: TestProbe[M])(matches: M => Boolean): M =
    probe
      .fishForMessage(10.seconds)(m =>
        if matches(m) then FishingOutcomes.complete else FishingOutcomes.continueAndIgnore
      )
      .last

  /** Fish the next spawn of type `T`, ignoring other spawns. */
  def expectSpawn[T <: ChildSpawn](using ct: scala.reflect.ClassTag[T]): T =
    spawns
      .fishForMessage(10.seconds) {
        case s if ct.runtimeClass.isInstance(s) => FishingOutcomes.complete
        case _                                  => FishingOutcomes.continueAndIgnore
      }
      .last
      .asInstanceOf[T]

  /** Fish the next message of type `T` on `probe`, ignoring others. */
  def fish[M, T <: M](probe: TestProbe[M])(using ct: scala.reflect.ClassTag[T]): T =
    probe
      .fishForMessage(10.seconds) {
        case m if ct.runtimeClass.isInstance(m) => FishingOutcomes.complete
        case _                                  => FishingOutcomes.continueAndIgnore
      }
      .last
      .asInstanceOf[T]

  // ── Chain and store seeding ──────────────────────────────────────────────────────────────────

  def storeGenesis(): Unit =
    val genesis = Fixtures.Blocks.Genesis.header
    blockchainWriter.save(
      Block(genesis, BlockBody.empty),
      Nil,
      ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
      saveAsBestBlock = true
    )

  /** A header at `number` with `root` as its state root, stored so `getBlockHeaderByNumber` finds it. */
  def storeHeaderAt(number: BigInt, root: ByteString): BlockHeader =
    val header = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(number), stateRoot = TrieRoot(root))
    blockchainWriter.storeBlock(Block(header, BlockBody.empty)).commit()
    header

  /** A header at `number` with `root` as its state root, NOT stored. */
  def headerAt(number: BigInt, root: ByteString): BlockHeader =
    Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(number), stateRoot = TrieRoot(root))

  /** A prior run finished accounts, bytecode and storage at (pivot, root). */
  def seedAllComplete(pivot: BigInt, root: ByteString): Unit =
    appStateStorage
      .putSnapSyncAccountsComplete(true)
      .and(appStateStorage.putSnapSyncStorageComplete(true))
      .and(appStateStorage.putSnapSyncBytecodeComplete(true))
      .and(appStateStorage.putSnapSyncPivotBlock(pivot))
      .and(appStateStorage.putSnapSyncStateRoot(root))
      .commit()

  /** A prior run finished accounts and bytecode at (pivot, root); storage resumes from a one-entry task file. */
  def seedStorageResume(pivot: BigInt, root: ByteString): Unit =
    val taskFile = Files.createTempFile("s0d-storage-tasks", ".bin")
    taskFile.toFile.deleteOnExit()
    Files.write(taskFile, Array.fill[Byte](32)(0x01) ++ Array.fill[Byte](32)(0x02))
    appStateStorage
      .putSnapSyncAccountsComplete(true)
      .and(appStateStorage.putSnapSyncStorageComplete(false))
      .and(appStateStorage.putSnapSyncBytecodeComplete(true))
      .and(appStateStorage.putSnapSyncPivotBlock(pivot))
      .and(appStateStorage.putSnapSyncStateRoot(root))
      .and(appStateStorage.putSnapSyncStorageFilePath(taskFile.toString))
      .and(appStateStorage.putSnapSyncStorageFileCount(Some(1L)))
      .commit()

  /** Like [[seedStorageResume]], with bytecode also still to download from a one-entry codeHash file. */
  def seedStorageAndByteCodeResume(pivot: BigInt, root: ByteString): Unit =
    seedStorageResume(pivot, root)
    val codeFile = Files.createTempFile("s0d-code-hashes", ".bin")
    codeFile.toFile.deleteOnExit()
    Files.write(codeFile, Array.fill[Byte](32)(0x03))
    appStateStorage
      .putSnapSyncBytecodeComplete(false)
      .and(appStateStorage.putSnapSyncCodeHashesPath(codeFile.toString))
      .and(appStateStorage.putSnapSyncCodeHashesCount(Some(1L)))
      .commit()

  def peersAt(height: Int, snap: Boolean = true, id: String = "snap-peer", port: Int = 30303): Map[Peer, PeerInfo] =
    val status = RemoteStatus(
      Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(height),
      bestHash = ByteString(s"best-$height"),
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      supportsSnap = snap
    )
    val peer = Peer(
      PeerId(id),
      new InetSocketAddress("127.0.0.1", port),
      org.apache.pekko.testkit.TestProbe()(using classicSystem).ref.toTyped[PeerActor.Command],
      incomingConnection = false
    )
    Map(
      peer -> PeerInfo(
        remoteStatus = status,
        chainWeight = status.chainWeight,
        forkAccepted = true,
        maxBlockNumber = height,
        bestBlockHash = status.bestHash
      )
    )

  // ── Routes into each `syncing` phase ─────────────────────────────────────────────────────────

  /** `StateHealing`: accounts-complete recovery with every phase done; `startStateHealing` spawns the healing child. */
  def enterStateHealing(
      config: SNAPSyncConfig = SNAPSyncConfig(deferredMerkleization = false),
      pivot: BigInt = Pivot,
      root: ByteString = Root,
      isPoS: Boolean = false,
      validatorFactory: MptStorage => StateValidator = new StateValidator(_)
  ): ActorRef[SNAPSyncController.Command] =
    storeGenesis()
    storeHeaderAt(pivot, root)
    seedAllComplete(pivot, root)
    val snap = spawnController(config, isPoS = isPoS, validatorFactory = validatorFactory)
    snap ! SNAPSyncController.Start
    expectSpawn[ChildSpawn.Healing]
    snap

  /** `ByteCodeAndStorageSync`: accounts-complete recovery with storage still to download (storage child only). */
  def enterStorageResume(
      config: SNAPSyncConfig = SNAPSyncConfig(deferredMerkleization = false)
  ): ActorRef[SNAPSyncController.Command] =
    storeGenesis()
    storeHeaderAt(Pivot, Root)
    seedStorageResume(Pivot, Root)
    val snap = spawnController(config)
    snap ! SNAPSyncController.Start
    expectSpawn[ChildSpawn.StorageRange]
    awaitProcessed(snap)
    snap

  /** `ByteCodeAndStorageSync` with both the storage and the bytecode child (both still downloading). */
  def enterStorageAndByteCodeResume(
      config: SNAPSyncConfig = SNAPSyncConfig(deferredMerkleization = false)
  ): ActorRef[SNAPSyncController.Command] =
    storeGenesis()
    storeHeaderAt(Pivot, Root)
    seedStorageAndByteCodeResume(Pivot, Root)
    val snap = spawnController(config)
    snap ! SNAPSyncController.Start
    expectSpawn[ChildSpawn.StorageRange]
    awaitProcessed(snap)
    snap

  /** `AccountRangeSync` at the genesis pivot: one snap peer at a height below the pivot offset. Account, bytecode and
    * storage children are spawned; no ChainDownloader (pivot 0).
    */
  def enterGenesisAccountSync(config: SNAPSyncConfig = SNAPSyncConfig()): ActorRef[SNAPSyncController.Command] =
    storeGenesis()
    peers.set(peersAt(height = 10))
    val snap = spawnController(config)
    snap ! SNAPSyncController.Start
    expectSpawn[ChildSpawn.AccountRange]
    awaitProcessed(snap)
    snap

  /** `AccountRangeSync` at a network pivot (`height - offset`): `Start` asks the parent for the pivot header, the test
    * answers with `BootstrapComplete`, and the account phase starts with all three download children and the
    * ChainDownloader.
    */
  def enterAccountSync(
      config: SNAPSyncConfig = SNAPSyncConfig(),
      height: Int = 1_000,
      root: ByteString = Root
  ): AccountSyncEntry =
    storeGenesis()
    peers.set(peersAt(height))
    val snap = spawnController(config)
    snap ! SNAPSyncController.Start
    val pivot = BigInt(height) - config.pivotBlockOffset
    parent.fishForMessage(10.seconds) {
      case SNAPSyncController.StartRegularSyncBootstrap(`pivot`) => FishingOutcomes.complete
      case _                                                     => FishingOutcomes.continueAndIgnore
    }
    val header = storeHeaderAt(pivot, root)
    snap ! SNAPSyncController.BootstrapComplete(Some(header))
    val account = expectSpawn[ChildSpawn.AccountRange]
    awaitProcessed(snap)
    AccountSyncEntry(snap, header, account)

object SnapControllerFixture:

  /** The controller in `AccountRangeSync` at a network pivot: its pivot header and the account child's spawn. */
  final case class AccountSyncEntry(
      snap: ActorRef[SNAPSyncController.Command],
      header: BlockHeader,
      account: ChildSpawn.AccountRange
  )

  /** ManualTime over the test application.conf, which declares the dispatchers the controller uses. */
  val config: Config = ManualTime.config.withFallback(ConfigFactory.load())

  val Pivot: BigInt = BigInt(10_000)
  val Root: ByteString = root(0x11)

  def root(tag: Int): ByteString = ByteString(Array.fill(32)(tag.toByte))
