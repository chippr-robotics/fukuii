package com.chipprbots.ethereum.blockchain.sync.snap.controller

import java.nio.file.Path

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.snap.ChainDownloader
import com.chipprbots.ethereum.blockchain.sync.snap.ContractTaskFiles
import com.chipprbots.ethereum.blockchain.sync.snap.HeapWatchdogEscape
import com.chipprbots.ethereum.blockchain.sync.snap.HeapWatchdogEscapePolicy
import com.chipprbots.ethereum.blockchain.sync.snap.OldGenReading
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHeapWatchdog
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ByteCodePeerCooldownConfig
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.HealingFrontierStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Test seam (spec 016 T012, FR-026): how `SNAPSyncController` builds the behaviour of each child it spawns.
  *
  * Each method has the parameter list of the child's own `apply` — same names, order and defaults — and its body
  * forwards every argument, by name, to that `apply`. The controller's spawn sites call these methods with exactly the
  * argument lists they passed to the `apply`s before, so [[ChildFactories.production]] builds exactly the same
  * behaviours. Spawn names, dispatchers and supervision stay at the spawn sites.
  *
  * Tests override a method to record the full argument tuple (defaults included) and return a stub behaviour.
  * `ChildFactoriesSpec` checks that every default here equals the matching default of the child's `apply`.
  */
trait ChildFactories:

  def accountRangeCoordinator(
      stateRoot: ByteString,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      concurrency: Int,
      snapSyncController: TypedActorRef[SNAPSyncController.Command],
      resumeProgress: Map[ByteString, ByteString] = Map.empty,
      initialMaxInFlightPerPeer: Int = 5,
      initialResponseBytes: Int = 524288,
      minResponseBytes: Int = 102400,
      accountTrieEcOverride: Option[ExecutionContext] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorage: Option[PathNodeStorage] = None,
      taskFileDir: Option[Path] = None,
      carriedTaskFiles: Option[ContractTaskFiles] = None,
      progressGeneration: Long = 0L,
      storageDone: Option[SnapStorageDoneStorage] = None,
      intakeBudget: Option[SnapIntakeBudget] = None
  ): Behavior[AccountRangeCoordinator.Command] =
    AccountRangeCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager,
      requestTracker = requestTracker,
      mptStorage = mptStorage,
      concurrency = concurrency,
      snapSyncController = snapSyncController,
      resumeProgress = resumeProgress,
      initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
      initialResponseBytes = initialResponseBytes,
      minResponseBytes = minResponseBytes,
      accountTrieEcOverride = accountTrieEcOverride,
      storageScheme = storageScheme,
      pathNodeStorage = pathNodeStorage,
      taskFileDir = taskFileDir,
      carriedTaskFiles = carriedTaskFiles,
      progressGeneration = progressGeneration,
      storageDone = storageDone,
      intakeBudget = intakeBudget
    )

  def storageRangeCoordinator(
      stateRoot: ByteString,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: TypedActorRef[SNAPSyncController.Command],
      initialMaxInFlightPerPeer: Int = 5,
      initialResponseBytes: Int = 1048576,
      minResponseBytes: Int = 131072,
      deferredMerkleization: Boolean = true,
      flatBatchEntryThreshold: Int = 1000,
      flatBatchEcOverride: Option[ExecutionContext] = None,
      backpressureHighWatermark: Int = 100000,
      backpressureLowWatermark: Int = 50000,
      maxConcurrentStorageAccounts: Int = 256,
      snapProgressStorage: Option[SnapSyncProgressStorage] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorage: Option[PathNodeStorage] = None,
      recordStorageDone: Boolean = false,
      intakeBudget: Option[SnapIntakeBudget] = None
  ): Behavior[StorageRangeCoordinator.Command] =
    StorageRangeCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager,
      requestTracker = requestTracker,
      mptStorage = mptStorage,
      flatSlotStorage = flatSlotStorage,
      maxAccountsPerBatch = maxAccountsPerBatch,
      maxInFlightRequests = maxInFlightRequests,
      requestTimeout = requestTimeout,
      snapSyncController = snapSyncController,
      initialMaxInFlightPerPeer = initialMaxInFlightPerPeer,
      initialResponseBytes = initialResponseBytes,
      minResponseBytes = minResponseBytes,
      deferredMerkleization = deferredMerkleization,
      flatBatchEntryThreshold = flatBatchEntryThreshold,
      flatBatchEcOverride = flatBatchEcOverride,
      backpressureHighWatermark = backpressureHighWatermark,
      backpressureLowWatermark = backpressureLowWatermark,
      maxConcurrentStorageAccounts = maxConcurrentStorageAccounts,
      snapProgressStorage = snapProgressStorage,
      storageScheme = storageScheme,
      pathNodeStorage = pathNodeStorage,
      recordStorageDone = recordStorageDone,
      intakeBudget = intakeBudget
    )

  // TrieNodeHealingCoordinator.apply takes no intakeBudget (the #1501 budget gates storage and bytecode intake only).
  def trieNodeHealingCoordinator(
      stateRoot: ByteString,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: TypedActorRef[SNAPSyncController.Command],
      concurrency: Int = 16,
      visitedCap: Int = TrieNodeHealingCoordinator.DefaultVisitedCap,
      healingFrontierStorage: Option[HealingFrontierStorage] = None,
      healingWriterEcOverride: Option[ExecutionContext] = None,
      healingReaderEcOverride: Option[ExecutionContext] = None,
      traversalParallelism: Int = TrieNodeHealingCoordinator.DefaultBfsParallelism,
      healingMinParallelism: Int = TrieNodeHealingCoordinator.DefaultMinParallelism,
      healingReservedCores: Int = TrieNodeHealingCoordinator.DefaultReservedCores,
      bfsQueueStorageOpt: Option[BfsQueueStorage] = None,
      storageScheme: StorageScheme = StorageScheme.Hash,
      pathNodeStorageOpt: Option[PathNodeStorage] = None,
      frontierHighWater: Int = TrieNodeHealingCoordinator.DefaultFrontierHighWater,
      frontierLowWater: Int = TrieNodeHealingCoordinator.DefaultFrontierLowWater,
      frontierBackpressureMaxWaitMs: Long = TrieNodeHealingCoordinator.FrontierBackpressureMaxWaitMs,
      scopedHealVerification: Boolean = true,
      scopedHealMaxPaths: Int = TrieNodeHealingCoordinator.DefaultScopedHealMaxPaths,
      prunedHealVerification: Boolean = true,
      frontierPersistenceEnabled: Boolean = false,
      decoupledHealServeRoot: Boolean = false,
      decoupledHealMaxAttemptsNoRefresh: Int = TrieNodeHealingCoordinator.DefaultDecoupledHealMaxAttemptsNoRefresh,
      movingRootDeltaHeal: Boolean = false,
      evmCodeStorage: Option[EvmCodeStorage] = None,
      walkLocalOnly: Option[java.util.concurrent.atomic.AtomicBoolean] = None
  ): Behavior[TrieNodeHealingCoordinator.Command] =
    TrieNodeHealingCoordinator(
      stateRoot = stateRoot,
      networkPeerManager = networkPeerManager,
      requestTracker = requestTracker,
      mptStorage = mptStorage,
      batchSize = batchSize,
      snapSyncController = snapSyncController,
      concurrency = concurrency,
      visitedCap = visitedCap,
      healingFrontierStorage = healingFrontierStorage,
      healingWriterEcOverride = healingWriterEcOverride,
      healingReaderEcOverride = healingReaderEcOverride,
      traversalParallelism = traversalParallelism,
      healingMinParallelism = healingMinParallelism,
      healingReservedCores = healingReservedCores,
      bfsQueueStorageOpt = bfsQueueStorageOpt,
      storageScheme = storageScheme,
      pathNodeStorageOpt = pathNodeStorageOpt,
      frontierHighWater = frontierHighWater,
      frontierLowWater = frontierLowWater,
      frontierBackpressureMaxWaitMs = frontierBackpressureMaxWaitMs,
      scopedHealVerification = scopedHealVerification,
      scopedHealMaxPaths = scopedHealMaxPaths,
      prunedHealVerification = prunedHealVerification,
      frontierPersistenceEnabled = frontierPersistenceEnabled,
      decoupledHealServeRoot = decoupledHealServeRoot,
      decoupledHealMaxAttemptsNoRefresh = decoupledHealMaxAttemptsNoRefresh,
      movingRootDeltaHeal = movingRootDeltaHeal,
      evmCodeStorage = evmCodeStorage,
      walkLocalOnly = walkLocalOnly
    )

  def byteCodeCoordinator(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: TypedActorRef[SNAPSyncController.Command],
      cooldownConfig: ByteCodePeerCooldownConfig = ByteCodePeerCooldownConfig.default,
      backpressureHighWatermark: Int = 50000,
      backpressureLowWatermark: Int = 25000,
      intakeBudget: Option[SnapIntakeBudget] = None
  ): Behavior[ByteCodeCoordinator.Command] =
    ByteCodeCoordinator(
      evmCodeStorage = evmCodeStorage,
      networkPeerManager = networkPeerManager,
      requestTracker = requestTracker,
      batchSize = batchSize,
      snapSyncController = snapSyncController,
      cooldownConfig = cooldownConfig,
      backpressureHighWatermark = backpressureHighWatermark,
      backpressureLowWatermark = backpressureLowWatermark,
      intakeBudget = intakeBudget
    )

  // scalastyle:off parameter.number
  def chainDownloader(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      replyTo: TypedActorRef[ChainDownloader.Done.type],
      maxConcurrentRequests: Int = 4,
      requestTimeout: FiniteDuration = 10.seconds,
      snapServerPeerNodeIds: Set[ByteString] = Set.empty,
      blacklist: Blacklist = CacheBasedBlacklist.empty(1000),
      cursorScanCap: Long = 5000L,
      deferBodiesAndReceipts: Boolean = false,
      emptyHeaderBackoff: Option[FiniteDuration] = None,
      nowMs: () => Long = () => System.currentTimeMillis()
  ): Behavior[ChainDownloader.Command] =
    ChainDownloader(
      blockchainReader = blockchainReader,
      blockchainWriter = blockchainWriter,
      appStateStorage = appStateStorage,
      networkPeerManager = networkPeerManager,
      peerEventBus = peerEventBus,
      syncConfig = syncConfig,
      replyTo = replyTo,
      maxConcurrentRequests = maxConcurrentRequests,
      requestTimeout = requestTimeout,
      snapServerPeerNodeIds = snapServerPeerNodeIds,
      blacklist = blacklist,
      cursorScanCap = cursorScanCap,
      deferBodiesAndReceipts = deferBodiesAndReceipts,
      emptyHeaderBackoff = emptyHeaderBackoff,
      nowMs = nowMs
    )
  // scalastyle:on parameter.number

object ChildFactories:

  /** Today's construction: every method forwards to the child's own `apply`. */
  val production: ChildFactories = new ChildFactories {}

/** Test seam (spec 016 T012, FR-026) for starting the #1501 heap watchdog. Same parameters as
  * [[SnapHeapWatchdog.start]]; the controller calls it at the same place, with the same arguments, as before.
  */
trait HeapWatchdogStart:
  def apply(
      highFraction: Double,
      lowFraction: Double,
      pollInterval: FiniteDuration,
      onChange: (Boolean, OldGenReading) => Unit,
      queuesEmpty: () => Boolean,
      policy: HeapWatchdogEscapePolicy,
      onEscape: (HeapWatchdogEscape, OldGenReading) => Unit
  ): Option[SnapHeapWatchdog.Handle]

object HeapWatchdogStart:

  /** Today's start: [[SnapHeapWatchdog.start]] itself. */
  val production: HeapWatchdogStart =
    (highFraction, lowFraction, pollInterval, onChange, queuesEmpty, policy, onEscape) =>
      SnapHeapWatchdog.start(
        highFraction = highFraction,
        lowFraction = lowFraction,
        pollInterval = pollInterval,
        onChange = onChange,
        queuesEmpty = queuesEmpty,
        policy = policy,
        onEscape = onEscape
      )
