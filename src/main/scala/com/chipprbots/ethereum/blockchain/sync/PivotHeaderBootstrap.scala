package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler

import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeerWithMinBlockExcluding
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeerWithMinBlockExcluding
import com.chipprbots.ethereum.blockchain.sync.PeersClient.NoSuitablePeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.PeersClient.RequestFailed
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Fetches and persists a single pivot header so SNAP can start without importing blocks.
  *
  * Two modes:
  *   - **By number** (`targetBlock`, `targetHash = None`): the standard pre-merge / non-CL path, used by SNAP's
  *     TD-driven pivot selection.
  *   - **By hash** (`targetBlock = 0`, `targetHash = Some(_)`): the CL-driven post-merge path (#1207). The CL has
  *     pushed a head hash via engine_forkchoiceUpdated; we fetch that hash directly via `GetBlockHeaders(Right(hash))`.
  *     The `Completed` reply uses `header.number` as the targetBlock so callers don't need to special-case the by-hash
  *     mode.
  *
  * Pekko Typed migration (Group ROOT): converted to a `Behavior[Command]` with a sealed inbound ADT. The two outgoing
  * messages [[Completed]] / [[Failed]] are delivered to a Classic `replyTo` ref — the CAPSTONE co-existence bridge on
  * `SyncController`'s `ctx.self` — which matches them as raw case classes in its bootstrap / recovery / healing states.
  * SyncController is itself now `Behavior[Command]`; the Classic ref is the co-existence bridge, not a sign of an
  * un-narrowed actor. `peersClient` is Typed (CAPSTONE Phase 2d); requests use the Typed `AskPattern`
  * (`peersClient.ask`), which supplies the `replyTo` directly. The ask callbacks run off the actor thread, so they send
  * self-Commands via `ctx.self` and log via a plain SLF4J `asyncLog`. Retry / wait-for-peer scheduling moves from the
  * injected `scheduler` to `Behaviors.withTimers`.
  */
object PivotHeaderBootstrap:

  sealed trait Command
  private case object Fetch extends Command
  private case object WaitForPeer extends Command
  // Sent from the off-thread `peersClient ?` callback to arm the wait-for-peer timer on the actor thread
  // (TimerScheduler is only safe to drive from the behavior).
  private case object ScheduleWaitForPeer extends Command
  final private case class Retry(reason: String) extends Command
  final private case class Fetched(header: BlockHeader) extends Command

  // ----- Outgoing messages, delivered to the Classic `replyTo` parent (SyncController, Behavior[Command], CAPSTONE bridge) -----
  /** Sealed umbrella for the two reply types sent to `replyTo` (SyncController). Enables a narrow
    * `TypedActorRef[Reply]` adapter instead of `TypedActorRef[Any]`.
    */
  sealed trait Reply
  final case class Completed(targetBlock: BigInt, header: BlockHeader) extends Reply
  final case class Failed(reason: String) extends Reply

  /** Fetch by block number (the standard pre-merge / TD-driven SNAP path). */
  def apply(
      peersClient: ActorRef[PeersClient.Command],
      blockchainWriter: BlockchainWriter,
      targetBlock: BigInt,
      replyTo: ActorRef[Reply],
      syncConfig: SyncConfig,
      maxAttempts: Int = 10,
      initialRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      waitForPeerDelay: FiniteDuration = 30.seconds,
      preferSnapPeers: Boolean = false
  ): Behavior[Command] =
    behavior(
      peersClient,
      blockchainWriter,
      targetBlock,
      targetHash = None,
      replyTo,
      syncConfig,
      maxAttempts,
      initialRetryDelay,
      maxRetryDelay,
      waitForPeerDelay,
      preferSnapPeers
    )

  /** Fetch by hash — the CL-driven post-merge path (#1207). Block number is unknown until the header arrives;
    * `Completed` carries the discovered `header.number`.
    */
  def applyByHash(
      peersClient: ActorRef[PeersClient.Command],
      blockchainWriter: BlockchainWriter,
      headHash: ByteString,
      replyTo: ActorRef[Reply],
      syncConfig: SyncConfig,
      maxAttempts: Int = 10,
      initialRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      waitForPeerDelay: FiniteDuration = 30.seconds,
      preferSnapPeers: Boolean = false
  ): Behavior[Command] =
    behavior(
      peersClient,
      blockchainWriter,
      targetBlock = BigInt(0),
      targetHash = Some(headHash),
      replyTo,
      syncConfig,
      maxAttempts,
      initialRetryDelay,
      maxRetryDelay,
      waitForPeerDelay,
      preferSnapPeers
    )

  private def behavior(
      peersClient: ActorRef[PeersClient.Command],
      blockchainWriter: BlockchainWriter,
      targetBlock: BigInt,
      targetHash: Option[ByteString],
      replyTo: ActorRef[Reply],
      @annotation.unused syncConfig: SyncConfig,
      maxAttempts: Int,
      initialRetryDelay: FiniteDuration,
      maxRetryDelay: FiniteDuration,
      waitForPeerDelay: FiniteDuration,
      preferSnapPeers: Boolean
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        ctx.self ! Fetch
        new Impl(
          ctx,
          timers,
          peersClient,
          blockchainWriter,
          targetBlock,
          targetHash,
          replyTo,
          maxAttempts,
          initialRetryDelay,
          maxRetryDelay,
          waitForPeerDelay,
          preferSnapPeers
        ).running()
      }
    }

  private class Impl(
      ctx: ActorContext[Command],
      timers: TimerScheduler[Command],
      peersClient: ActorRef[PeersClient.Command],
      blockchainWriter: BlockchainWriter,
      targetBlock: BigInt,
      targetHash: Option[ByteString],
      replyTo: ActorRef[Reply],
      maxAttempts: Int,
      initialRetryDelay: FiniteDuration,
      maxRetryDelay: FiniteDuration,
      waitForPeerDelay: FiniteDuration,
      preferSnapPeers: Boolean
  ):

    // Safe from any thread (the `peersClient ?` callbacks run off the actor thread).
    private val asyncLog = LoggerFactory.getLogger(getClass)

    // The ask future combinators run off the actor thread.
    private given ec: ExecutionContext = ctx.executionContext
    // Typed AskPattern needs an implicit Scheduler.
    implicit private val scheduler: Scheduler = ctx.system.scheduler

    private val byHashMode: Boolean = targetHash.isDefined
    private def targetDesc: String = targetHash match
      case Some(h) => s"hash=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(h)}"
      case None    => s"block=$targetBlock"

    private var attempt: Int = 0
    private val triedPeers: scala.collection.mutable.Set[PeerId] = scala.collection.mutable.Set.empty
    private var waitCount: Int = 0

    private def currentRetryDelay: FiniteDuration =
      // Exponential backoff: initialRetryDelay * 2^(attempt-1), capped at maxRetryDelay
      val backoffMs = math.min(
        initialRetryDelay.toMillis * (1L << math.min(attempt - 1, 20)),
        maxRetryDelay.toMillis
      )
      backoffMs.millis

    // Use a short, fixed timeout for single-header requests.
    // syncConfig.peerResponseTimeout (90s in cirith-ungol) is far too long for fetching 1 header.
    // A responsive peer returns a header in <5s; 15s is generous.
    implicit private val timeout: Timeout = Timeout(15.seconds)

    def running(): Behavior[Command] = Behaviors.receiveMessage {
      case Fetch =>
        attempt += 1
        if attempt > maxAttempts then
          replyTo ! Failed(s"exhausted attempts ($maxAttempts) fetching pivot header $targetDesc")
          Behaviors.stopped
        else
          fetchOnce()
          Behaviors.same

      case Fetched(header) =>
        try
          blockchainWriter.storeBlockHeader(header).commit()
          // For by-hash mode: report the actual block number we discovered.
          val resolvedNumber = if byHashMode then header.number.value else targetBlock
          ctx.log.info(
            s"[PIVOT] Bootstrap complete — block=${header.number} hash=${header.hashAsHexString.take(10)} " +
              s"parentHash=${header.parentHash.value.take(4).toArray.map("%02x".format(_)).mkString}"
          )
          replyTo ! Completed(resolvedNumber, header)
        catch
          case t: Throwable =>
            replyTo ! Failed(s"failed storing pivot header $targetDesc: ${t.getMessage}")
        Behaviors.stopped

      case Retry(reason) =>
        ctx.log.warn(
          "Pivot header bootstrap retry {}/{} for {} (reason: {})",
          attempt,
          maxAttempts,
          targetDesc,
          reason
        )
        val delay = currentRetryDelay
        ctx.log.info("Scheduling pivot header retry in {} (attempt {}/{})", delay, attempt, maxAttempts)
        timers.startSingleTimer(Fetch, delay)
        Behaviors.same

      case ScheduleWaitForPeer =>
        // Arm the wait-for-peer timer on the actor thread (requested by the off-thread fetch callback).
        timers.startSingleTimer(WaitForPeer, waitForPeerDelay)
        Behaviors.same

      case WaitForPeer =>
        // Models Besu's waitForPeer(!peersUsed.contains(p)) / go-ethereum's idle-loop peer wait.
        // Does NOT increment `attempt` — starvation waits don't consume the retry budget.
        waitCount += 1
        if waitCount % 4 == 0 then
          ctx.log.warn(
            "Pivot header bootstrap for {} has been waiting for a fresh peer for ~{}s ({} peer(s) tried so far)",
            targetDesc,
            waitCount * waitForPeerDelay.toSeconds,
            triedPeers.size
          )
        fetchOnce()
        Behaviors.same
    }

    private def fetchOnce(): Unit =
      // Build the GetBlockHeaders request — Right(hash) for by-hash mode, Left(number) for by-number.
      val target: Either[BigInt, ByteString] = targetHash.toRight(targetBlock)
      val msg = ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, target, maxHeaders = 1, skip = 0, reverse = false)

      // Peer selection:
      //   By-number with preferSnapPeers (standard SNAP pivot bootstrap): BestSnapPeerWithMinBlockExcluding
      //     combines Besu's two-stage pattern — PivotSelectorFromPeers pre-filters by estimatedChainHeight >= pivot,
      //     PivotBlockConfirmer excludes used peers — into a single selector. This prevents Besu ETH69 (synthetic
      //     TD ~61.66×10²¹ >> real ETC TD) from being selected when its reported maxBlockNumber is below targetBlock,
      //     and rotates through remaining SNAP-capable peers via the exclusion set.
      //   By-number without preferSnapPeers: BestPeerWithMinBlockExcluding rotates through the full pool.
      //   By-hash (preferSnapPeers=false in SyncController): BestPeer — CL-driven path.
      //   By-hash + preferSnapPeers: unreachable in production (targetBlock=0, no meaningful minBlock).
      val selector =
        if preferSnapPeers && !byHashMode then BestSnapPeerWithMinBlockExcluding(targetBlock, triedPeers.toSet)
        else if preferSnapPeers then BestSnapPeer
        else if byHashMode then BestPeer
        else BestPeerWithMinBlockExcluding(targetBlock, triedPeers.toSet)
      peersClient
        .ask[PeersClient.ResponseMessage](replyTo =>
          Request[ETHPackets.GetBlockHeaders](msg, selector, (m: ETHPackets.GetBlockHeaders) => m, replyTo)
        )
        .flatMap {
          case NoSuitablePeer if preferSnapPeers =>
            // No SNAP peer available — try any peer with the target as fallback
            asyncLog.debug("No SNAP-capable peer for pivot header, falling back")
            val fallbackMsg =
              ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, target, maxHeaders = 1, skip = 0, reverse = false)
            val fallbackSelector =
              if byHashMode then BestPeer else BestPeerWithMinBlockExcluding(targetBlock, triedPeers.toSet)
            peersClient.ask[PeersClient.ResponseMessage](replyTo =>
              Request[ETHPackets.GetBlockHeaders](
                fallbackMsg,
                fallbackSelector,
                (m: ETHPackets.GetBlockHeaders) => m,
                replyTo
              )
            )
          case other =>
            scala.concurrent.Future.successful(other)
        }
        .map {
          case PeersClient.Response(peer, headers: ETHPackets.BlockHeaders) =>
            (Some(peer), headers.headers.headOption, false)
          case PeersClient.Response(peer, _) =>
            (Some(peer), None, false)
          case NoSuitablePeer =>
            (None, None, false)
          case RequestFailed(peer, reason) =>
            asyncLog.warn("Pivot header request failed: {}", reason)
            (Some(peer), None, true)
        }
        .recover { case ex =>
          asyncLog.warn("Pivot header bootstrap ask failed (attempt {}/{}): {}", attempt, maxAttempts, ex.getMessage)
          (None, None, false)
        }
        .foreach { case (peerOpt, headerOpt, isFailed) =>
          peerOpt.foreach(p => triedPeers += p.id)
          headerOpt match
            case Some(header) if matchesTarget(header) =>
              ctx.self ! Fetched(header)
            case Some(header) =>
              ctx.self ! Retry(
                s"received header (number=${header.number}, hash=${com.chipprbots.ethereum.utils.ByteStringUtils
                    .hash2string(header.hash.value)}) doesn't match target $targetDesc"
              )
            case None if peerOpt.isDefined && isFailed =>
              // RequestFailed: peer explicitly rejected the request — consume an attempt and rotate.
              ctx.self ! Retry(s"peer ${peerOpt.get.id} request failed")
            case None if peerOpt.isDefined =>
              // Empty headers — almost always a connection drop (Besu's ~60s reconnect cycle).
              // Use WaitForPeer (no attempt consumed) so the retry budget isn't drained by
              // transient disconnects.
              ctx.self ! WaitForPeer
            case None =>
              // NoSuitablePeer — pool empty or all known peers already tried.
              // Model Besu's waitForPeer(!peersUsed.contains(p)): wait for a fresh peer to connect.
              asyncLog.info(
                "Pivot header bootstrap for {}: no eligible peer available ({} tried). " +
                  "Waiting {} for a fresh peer.",
                targetDesc,
                triedPeers.size,
                waitForPeerDelay
              )
              // Schedule the wait via a self-message; startSingleTimer is thread-safe to call here, but to
              // keep timer ownership on the actor thread we send a self-Command that arms the timer there.
              ctx.self ! ScheduleWaitForPeer
        }

    private def matchesTarget(header: BlockHeader): Boolean =
      targetHash match
        case Some(hash) => header.hash.value == hash
        case None       => header.number.value == targetBlock
