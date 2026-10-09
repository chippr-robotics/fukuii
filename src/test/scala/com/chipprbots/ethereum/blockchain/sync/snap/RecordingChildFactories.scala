package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ByteCodePeerCooldownConfig
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.controller.ChildFactories
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

/** One child spawn as seen through the `ChildFactories` seam (spec 016 T012): the full argument list the controller
  * resolved for the child's `apply`, defaults included.
  */
sealed trait ChildSpawn

object ChildSpawn:
  final case class AccountRange(
      stateRoot: ByteString,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      concurrency: Int,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      resumeProgress: Map[ByteString, ByteString],
      initialMaxInFlightPerPeer: Int,
      initialResponseBytes: Int,
      minResponseBytes: Int,
      accountTrieEcOverride: Option[ExecutionContext],
      storageScheme: StorageScheme,
      pathNodeStorage: Option[PathNodeStorage],
      taskFileDir: Option[Path],
      carriedTaskFiles: Option[ContractTaskFiles],
      progressGeneration: Long,
      storageDone: Option[SnapStorageDoneStorage],
      intakeBudget: Option[SnapIntakeBudget]
  ) extends ChildSpawn

  final case class StorageRange(
      stateRoot: ByteString,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      initialMaxInFlightPerPeer: Int,
      initialResponseBytes: Int,
      minResponseBytes: Int,
      deferredMerkleization: Boolean,
      flatBatchEntryThreshold: Int,
      flatBatchEcOverride: Option[ExecutionContext],
      backpressureHighWatermark: Int,
      backpressureLowWatermark: Int,
      maxConcurrentStorageAccounts: Int,
      snapProgressStorage: Option[SnapSyncProgressStorage],
      storageScheme: StorageScheme,
      pathNodeStorage: Option[PathNodeStorage],
      recordStorageDone: Boolean,
      intakeBudget: Option[SnapIntakeBudget]
  ) extends ChildSpawn

  final case class Healing(
      stateRoot: ByteString,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      concurrency: Int,
      visitedCap: Int,
      healingFrontierStorage: Option[HealingFrontierStorage],
      healingWriterEcOverride: Option[ExecutionContext],
      healingReaderEcOverride: Option[ExecutionContext],
      traversalParallelism: Int,
      healingMinParallelism: Int,
      healingReservedCores: Int,
      bfsQueueStorageOpt: Option[BfsQueueStorage],
      storageScheme: StorageScheme,
      pathNodeStorageOpt: Option[PathNodeStorage],
      frontierHighWater: Int,
      frontierLowWater: Int,
      frontierBackpressureMaxWaitMs: Long,
      scopedHealVerification: Boolean,
      scopedHealMaxPaths: Int,
      prunedHealVerification: Boolean,
      frontierPersistenceEnabled: Boolean,
      decoupledHealServeRoot: Boolean,
      decoupledHealMaxAttemptsNoRefresh: Int,
      movingRootDeltaHeal: Boolean,
      evmCodeStorage: Option[EvmCodeStorage],
      walkLocalOnly: Option[AtomicBoolean]
  ) extends ChildSpawn

  final case class ByteCode(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      cooldownConfig: ByteCodePeerCooldownConfig,
      backpressureHighWatermark: Int,
      backpressureLowWatermark: Int,
      intakeBudget: Option[SnapIntakeBudget]
  ) extends ChildSpawn

  final case class Chain(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: ActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      replyTo: ActorRef[ChainDownloader.Done.type],
      maxConcurrentRequests: Int,
      requestTimeout: FiniteDuration,
      snapServerPeerNodeIds: Set[ByteString],
      blacklist: Blacklist,
      cursorScanCap: Long,
      deferBodiesAndReceipts: Boolean,
      emptyHeaderBackoff: Option[FiniteDuration],
      nowMs: () => Long
  ) extends ChildSpawn

/** A stub child built by [[RecordingChildFactories]] has stopped (`kind` is one of the `ChildStopped` constants). */
final case class ChildStopped(kind: String)

object ChildStopped:
  val Account = "account"
  val Storage = "storage"
  val Healing = "healing"
  val ByteCode = "bytecode"
  val Chain = "chain"

/** `ChildFactories` that build no real child. Each spawn is reported to `spawns` with its full argument list and
  * counted, and the spawned child is a stub that forwards every message it receives to the matching inbox (or ignores
  * it) and reports its own stop to `stopped`. The counters are bumped on the controller's thread before the factory
  * returns, so a test that reads them after a controller round trip (for example a `GetProgress` reply) sees every
  * spawn the controller made before it.
  */
final class RecordingChildFactories(
    spawns: ActorRef[ChildSpawn],
    accountInbox: Option[ActorRef[AccountRangeCoordinator.Command]] = None,
    storageInbox: Option[ActorRef[StorageRangeCoordinator.Command]] = None,
    healingInbox: Option[ActorRef[TrieNodeHealingCoordinator.Command]] = None,
    byteCodeInbox: Option[ActorRef[ByteCodeCoordinator.Command]] = None,
    chainInbox: Option[ActorRef[ChainDownloader.Command]] = None,
    stopped: Option[ActorRef[ChildStopped]] = None
) extends ChildFactories:

  val accountSpawns = new AtomicInteger(0)
  val storageSpawns = new AtomicInteger(0)
  val healingSpawns = new AtomicInteger(0)
  val byteCodeSpawns = new AtomicInteger(0)
  val chainSpawns = new AtomicInteger(0)

  private def stub[T](kind: String, inbox: Option[ActorRef[T]]): Behavior[T] =
    Behaviors
      .receiveMessage[T] { msg =>
        inbox.foreach(_ ! msg)
        Behaviors.same
      }
      .receiveSignal { case (_, PostStop) =>
        stopped.foreach(_ ! ChildStopped(kind))
        Behaviors.same
      }

  override def accountRangeCoordinator(
      stateRoot: ByteString,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      concurrency: Int,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      resumeProgress: Map[ByteString, ByteString],
      initialMaxInFlightPerPeer: Int,
      initialResponseBytes: Int,
      minResponseBytes: Int,
      accountTrieEcOverride: Option[ExecutionContext],
      storageScheme: StorageScheme,
      pathNodeStorage: Option[PathNodeStorage],
      taskFileDir: Option[Path],
      carriedTaskFiles: Option[ContractTaskFiles],
      progressGeneration: Long,
      storageDone: Option[SnapStorageDoneStorage],
      intakeBudget: Option[SnapIntakeBudget]
  ): Behavior[AccountRangeCoordinator.Command] =
    accountSpawns.incrementAndGet()
    spawns ! ChildSpawn.AccountRange(
      stateRoot,
      networkPeerManager,
      requestTracker,
      mptStorage,
      concurrency,
      snapSyncController,
      resumeProgress,
      initialMaxInFlightPerPeer,
      initialResponseBytes,
      minResponseBytes,
      accountTrieEcOverride,
      storageScheme,
      pathNodeStorage,
      taskFileDir,
      carriedTaskFiles,
      progressGeneration,
      storageDone,
      intakeBudget
    )
    stub(ChildStopped.Account, accountInbox)

  override def storageRangeCoordinator(
      stateRoot: ByteString,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      flatSlotStorage: FlatSlotStorage,
      maxAccountsPerBatch: Int,
      maxInFlightRequests: Int,
      requestTimeout: FiniteDuration,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      initialMaxInFlightPerPeer: Int,
      initialResponseBytes: Int,
      minResponseBytes: Int,
      deferredMerkleization: Boolean,
      flatBatchEntryThreshold: Int,
      flatBatchEcOverride: Option[ExecutionContext],
      backpressureHighWatermark: Int,
      backpressureLowWatermark: Int,
      maxConcurrentStorageAccounts: Int,
      snapProgressStorage: Option[SnapSyncProgressStorage],
      storageScheme: StorageScheme,
      pathNodeStorage: Option[PathNodeStorage],
      recordStorageDone: Boolean,
      intakeBudget: Option[SnapIntakeBudget]
  ): Behavior[StorageRangeCoordinator.Command] =
    storageSpawns.incrementAndGet()
    spawns ! ChildSpawn.StorageRange(
      stateRoot,
      networkPeerManager,
      requestTracker,
      mptStorage,
      flatSlotStorage,
      maxAccountsPerBatch,
      maxInFlightRequests,
      requestTimeout,
      snapSyncController,
      initialMaxInFlightPerPeer,
      initialResponseBytes,
      minResponseBytes,
      deferredMerkleization,
      flatBatchEntryThreshold,
      flatBatchEcOverride,
      backpressureHighWatermark,
      backpressureLowWatermark,
      maxConcurrentStorageAccounts,
      snapProgressStorage,
      storageScheme,
      pathNodeStorage,
      recordStorageDone,
      intakeBudget
    )
    stub(ChildStopped.Storage, storageInbox)

  override def trieNodeHealingCoordinator(
      stateRoot: ByteString,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      mptStorage: MptStorage,
      batchSize: Int,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      concurrency: Int,
      visitedCap: Int,
      healingFrontierStorage: Option[HealingFrontierStorage],
      healingWriterEcOverride: Option[ExecutionContext],
      healingReaderEcOverride: Option[ExecutionContext],
      traversalParallelism: Int,
      healingMinParallelism: Int,
      healingReservedCores: Int,
      bfsQueueStorageOpt: Option[BfsQueueStorage],
      storageScheme: StorageScheme,
      pathNodeStorageOpt: Option[PathNodeStorage],
      frontierHighWater: Int,
      frontierLowWater: Int,
      frontierBackpressureMaxWaitMs: Long,
      scopedHealVerification: Boolean,
      scopedHealMaxPaths: Int,
      prunedHealVerification: Boolean,
      frontierPersistenceEnabled: Boolean,
      decoupledHealServeRoot: Boolean,
      decoupledHealMaxAttemptsNoRefresh: Int,
      movingRootDeltaHeal: Boolean,
      evmCodeStorage: Option[EvmCodeStorage],
      walkLocalOnly: Option[AtomicBoolean]
  ): Behavior[TrieNodeHealingCoordinator.Command] =
    healingSpawns.incrementAndGet()
    spawns ! ChildSpawn.Healing(
      stateRoot,
      networkPeerManager,
      requestTracker,
      mptStorage,
      batchSize,
      snapSyncController,
      concurrency,
      visitedCap,
      healingFrontierStorage,
      healingWriterEcOverride,
      healingReaderEcOverride,
      traversalParallelism,
      healingMinParallelism,
      healingReservedCores,
      bfsQueueStorageOpt,
      storageScheme,
      pathNodeStorageOpt,
      frontierHighWater,
      frontierLowWater,
      frontierBackpressureMaxWaitMs,
      scopedHealVerification,
      scopedHealMaxPaths,
      prunedHealVerification,
      frontierPersistenceEnabled,
      decoupledHealServeRoot,
      decoupledHealMaxAttemptsNoRefresh,
      movingRootDeltaHeal,
      evmCodeStorage,
      walkLocalOnly
    )
    stub(ChildStopped.Healing, healingInbox)

  override def byteCodeCoordinator(
      evmCodeStorage: EvmCodeStorage,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      batchSize: Int,
      snapSyncController: ActorRef[SNAPSyncController.Command],
      cooldownConfig: ByteCodePeerCooldownConfig,
      backpressureHighWatermark: Int,
      backpressureLowWatermark: Int,
      intakeBudget: Option[SnapIntakeBudget]
  ): Behavior[ByteCodeCoordinator.Command] =
    byteCodeSpawns.incrementAndGet()
    spawns ! ChildSpawn.ByteCode(
      evmCodeStorage,
      networkPeerManager,
      requestTracker,
      batchSize,
      snapSyncController,
      cooldownConfig,
      backpressureHighWatermark,
      backpressureLowWatermark,
      intakeBudget
    )
    stub(ChildStopped.ByteCode, byteCodeInbox)

  override def chainDownloader(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: ActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      replyTo: ActorRef[ChainDownloader.Done.type],
      maxConcurrentRequests: Int,
      requestTimeout: FiniteDuration,
      snapServerPeerNodeIds: Set[ByteString],
      blacklist: Blacklist,
      cursorScanCap: Long,
      deferBodiesAndReceipts: Boolean,
      emptyHeaderBackoff: Option[FiniteDuration],
      nowMs: () => Long
  ): Behavior[ChainDownloader.Command] =
    chainSpawns.incrementAndGet()
    spawns ! ChildSpawn.Chain(
      blockchainReader,
      blockchainWriter,
      appStateStorage,
      networkPeerManager,
      peerEventBus,
      syncConfig,
      replyTo,
      maxConcurrentRequests,
      requestTimeout,
      snapServerPeerNodeIds,
      blacklist,
      cursorScanCap,
      deferBodiesAndReceipts,
      emptyHeaderBackoff,
      nowMs
    )
    stub(ChildStopped.Chain, chainInbox)
