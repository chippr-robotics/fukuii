package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes

/** Bytecode recovery actor for Bug 20 hardening.
  *
  * Downloads the contract bytecodes the combined recovery scan (`CombinedRecoveryScanActor`) found missing from
  * evmCodeStorage (Bug 20: the phase handoff timeout), via the SNAP protocol using ByteCodeCoordinator. It has no scan
  * of its own: the legacy per-phase scan (`parallel-recovery-scan = false`) stopped at the first absent node and the
  * flag was then settled on that partial walk, and it was retired (B-217).
  *
  * Pekko Typed actor (`Behavior[Command]`, narrowed S5): SyncController sends both `ByteCodePeerAvailable` and
  * `ByteCodeSyncComplete` / `ProgressBytecodesDownloaded` to this actor. SyncController holds a Classic-visible ref via
  * the CAPSTONE co-existence bridge; all inbound messages are members of the sealed `Command` ADT, so `Behavior[Any]`
  * is no longer needed — all Typed machinery (named behavior functions, `Behaviors.withTimers`, `watchWith`) is active.
  *
  * Lifecycle:
  *   1. If none missing → mark recovery done, report to SyncController 2. If missing → download via
  *      ByteCodeCoordinator, then mark done
  *
  * Both layouts run it: what it writes is the code store, which both share (`ByteCodeResponseHandling`:
  * `EvmCodeStorage.put`), and the recovery flag and the excluded-set progress of the coordinator, which are app-state
  * and SNAP-progress rows. Nothing here writes a trie node (B-396).
  */
object BytecodeRecoveryActor:

  sealed trait Command
  private case class CheckAbandon(progressSeq: Long) extends Command
  private case object CoordinatorTerminated extends Command
  // SyncController forwards peer-available events here; BCA re-forwards to BCC
  case class ByteCodePeerAvailable(peer: Peer) extends Command
  // Adapter-mapped SSC replies — private[sync] so tests can inject them directly
  private[sync] case object ByteCodeDownloadComplete extends Command
  private[sync] case class ByteCodeDownloadProgress(n: Long) extends Command
  // Catch-all for unexpected SSC messages arriving via the adapter
  private case object DroppedBccMsg extends Command
  // SyncController relays SNAP peer responses here so ByteCodeCoordinator receives them
  // (no SNAPSyncController exists during recovery — SyncController acts as the routing relay).
  private[sync] case class ForwardByteCodesResponse(msg: ByteCodes) extends Command

  /** What this actor tells SyncController when it is over. */
  sealed trait SyncControllerMsg

  /** Sent to SyncController when recovery is complete (or skipped) */
  case object RecoveryComplete extends SyncControllerMsg

  /** Download the codes the combined scan found missing (`missing`). Used by `SyncController` once that scan reports.
    */
  def applyPreloaded(
      stateIngest: StateIngest,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[ByteString]
  ): Behavior[Command] =
    starting(stateIngest, networkPeerManager, syncController, snapSyncConfig, missing, coordinatorForTesting = None)

  /** Test entry point: exposes the coordinator-injection hook. */
  private[sync] def testApply(
      stateIngest: StateIngest,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[ByteString],
      coordinatorForTesting: Option[ActorRef] = None
  ): Behavior[Command] =
    starting(stateIngest, networkPeerManager, syncController, snapSyncConfig, missing, coordinatorForTesting)

  private def starting(
      stateIngest: StateIngest,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      syncController: TypedActorRef[SyncControllerMsg],
      snapSyncConfig: SNAPSyncConfig,
      missing: Seq[ByteString],
      coordinatorForTesting: Option[ActorRef]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      if missing.isEmpty then
        ctx.log.info("Bytecode recovery: all contract bytecodes present. Marking recovery complete.")
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
        stateIngest.appState.bytecodeRecoveryDone().commit()
        syncController ! RecoveryComplete
        Behaviors.stopped
      else
        ctx.log.warn(
          s"Bytecode recovery: found ${missing.size} missing bytecodes. Starting download..."
        )
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseDownloading)
        val bccAdapter: org.apache.pekko.actor.typed.ActorRef[snap.SNAPSyncController.Command] =
          ctx.messageAdapter {
            case snap.SNAPSyncController.ByteCodeSyncComplete           => ByteCodeDownloadComplete
            case snap.SNAPSyncController.ProgressBytecodesDownloaded(n) => ByteCodeDownloadProgress(n)
            case _                                                      => DroppedBccMsg
          }
        val coordinator: org.apache.pekko.actor.typed.ActorRef[snap.actors.ByteCodeCoordinator.Command] =
          coordinatorForTesting match
            case Some(testRef) => testRef.toTyped[snap.actors.ByteCodeCoordinator.Command]
            case None =>
              val requestTracker = new snap.SNAPRequestTracker()(using ctx.system.classicSystem.scheduler)
              ctx.spawn(
                Behaviors
                  .supervise(
                    snap.actors.ByteCodeCoordinator(
                      stateIngest = stateIngest,
                      networkPeerManager = networkPeerManager,
                      requestTracker = requestTracker,
                      batchSize = snap.ByteCodeTask.DEFAULT_BATCH_SIZE,
                      snapSyncController = bccAdapter
                    )
                  )
                  .onFailure[Throwable](
                    SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3)
                  ),
                "bytecode-recovery-coordinator",
                org.apache.pekko.actor.typed.DispatcherSelector.fromConfig("sync-dispatcher")
              )
        ctx.watchWith(coordinator, CoordinatorTerminated)
        coordinator ! snap.actors.ByteCodeCoordinator.StartByteCodeSync(missing)
        // The whole gap list is known up front. Without this the coordinator waits for more tasks that never come
        // ("accumulating") and never announces completion, so recovery only ended when the no-progress abandon timer
        // fired, however quickly the bytecodes had arrived.
        coordinator ! snap.actors.ByteCodeCoordinator.NoMoreByteCodeTasks
        downloading(ctx, coordinator, missing.size, syncController, stateIngest, snapSyncConfig)
    }

  private def downloading(
      ctx: ActorContext[Command],
      coordinator: org.apache.pekko.actor.typed.ActorRef[snap.actors.ByteCodeCoordinator.Command],
      expectedCount: Int,
      syncController: TypedActorRef[SyncControllerMsg],
      stateIngest: StateIngest,
      snapSyncConfig: SNAPSyncConfig
  ): Behavior[Command] =
    var progressSeq = 0L
    var downloadedCount = 0L
    var lastBytecodeRecoveryMilestone: Int = -1
    var lastRateNanos = System.nanoTime()
    var lastRateDownloaded = 0L
    val abandonAfter: FiniteDuration = snapSyncConfig.storageRecoveryAbandonTimeout

    Behaviors.withTimers { timers =>
      timers.startSingleTimer("abandon", CheckAbandon(0L), abandonAfter)

      def recordProgress(): Unit =
        progressSeq += 1
        timers.cancel("abandon")

      def finishRecovery(): Behavior[Command] =
        timers.cancel("abandon")
        RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
        stateIngest.appState.bytecodeRecoveryDone().commit()
        syncController ! RecoveryComplete
        Behaviors.stopped

      Behaviors.receiveMessage {
        case ByteCodePeerAvailable(peer) =>
          coordinator ! snap.actors.ByteCodeCoordinator.ByteCodePeerAvailable(peer)
          Behaviors.same

        case ByteCodeDownloadComplete =>
          ctx.log.info(
            s"[SNAP-PROGRESS] BYTECODE-RECOVERY 100% — $expectedCount / $expectedCount bytecodes recovered — COMPLETE"
          )
          RecoveryMetrics.setBytecodeDownloaded(expectedCount.toLong)
          RecoveryMetrics.setBytecodePhase(RecoveryMetrics.PhaseComplete)
          finishRecovery()

        case ByteCodeDownloadProgress(_) =>
          downloadedCount += 1
          RecoveryMetrics.setBytecodeDownloaded(downloadedCount)
          recordProgress()
          downloadedCount += 1
          val (newM, crossed) =
            ProgressMilestones.crossed(downloadedCount, expectedCount.toLong, lastBytecodeRecoveryMilestone)
          lastBytecodeRecoveryMilestone = newM
          crossed.foreach { m =>
            val elapsedSecs = (System.nanoTime() - lastRateNanos) / 1e9
            val rate = if elapsedSecs > 0 then ((downloadedCount - lastRateDownloaded) / elapsedSecs).toLong else 0L
            if m % 10 == 0 || m <= 5 || m >= 95 then
              lastRateNanos = System.nanoTime()
              lastRateDownloaded = downloadedCount
            ctx.log.info(
              s"[SNAP-PROGRESS] BYTECODE-RECOVERY $m% — $downloadedCount / $expectedCount bytecodes | $rate bytecodes/s"
            )
          }
          Behaviors.same

        case CheckAbandon(progressAtSchedule) =>
          if progressAtSchedule == progressSeq then
            ctx.log.warn(
              "Bytecode recovery abandoned: no download progress for {}s. " +
                "Regular sync will fetch missing bytecodes on-demand via GetTrieNodes.",
              abandonAfter.toSeconds
            )
            finishRecovery()
          else Behaviors.same

        case CoordinatorTerminated =>
          ctx.log.error(
            "ByteCodeCoordinator crashed unexpectedly. Marking bytecode recovery done to unblock sync."
          )
          finishRecovery()

        case DroppedBccMsg => Behaviors.same

        case ForwardByteCodesResponse(msg) =>
          coordinator ! snap.actors.ByteCodeCoordinator.ByteCodesResponseMsg(msg)
          Behaviors.same
      }
    }
