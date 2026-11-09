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

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeerWithMinBlockExcluding
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeerWithMinBlockExcluding
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BlacklistPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.ExcludingPeers
import com.chipprbots.ethereum.blockchain.sync.PeersClient.NoSuitablePeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.PeersClient.RequestFailed
import com.chipprbots.ethereum.blockchain.sync.snap.HeaderChainCheck
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Fetches and persists a single pivot header so SNAP can start without importing blocks.
  *
  * Three modes:
  *   - **By number** (`targetBlock`, `targetHash = None`): the standard pre-merge / non-CL path, used by SNAP's
  *     TD-driven pivot selection.
  *   - **By hash** (`targetBlock = 0`, `targetHash = Some(_)`): the CL-driven post-merge path (#1207). The CL has
  *     pushed a head hash via engine_forkchoiceUpdated; we fetch that hash directly via `GetBlockHeaders(Right(hash))`.
  *     The `Completed` reply uses `header.number` as the targetBlock so callers don't need to special-case the by-hash
  *     mode.
  *   - **Ancestry** ([[applyAncestry]]): the header `depth` blocks below a CL head hash, from one reverse request
  *     starting at that hash; the run must be the head's ancestry ([[ancestryPivot]]), so the header is the CL head's
  *     ancestor whichever peer served it. `Completed` carries its number.
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
  // Sent from the off-thread `peersClient ?` callback when no peer was eligible, so the decision (wait for a peer, or
  // give up after a rejection) and the wait-for-peer timer stay on the actor thread.
  private case object NoEligiblePeer extends Command
  // Sent from the off-thread callback when `headerCheck` rejected the header `peer` served.
  final private case class Rejected(peer: Option[Peer], header: BlockHeader, error: BlockHeaderError) extends Command
  // Sent from the off-thread callback when an ancestry run is not the head's ancestry (AncestryProblem.Mismatch).
  final private case class AncestryRejected(peer: Option[Peer], reason: String, metricReason: String) extends Command
  final private case class Retry(reason: String) extends Command
  final private case class Fetched(header: BlockHeader) extends Command

  // ----- Outgoing messages, delivered to the Classic `replyTo` parent (SyncController, Behavior[Command], CAPSTONE bridge) -----
  /** Sealed umbrella for the two reply types sent to `replyTo` (SyncController). Enables a narrow
    * `TypedActorRef[Reply]` adapter instead of `TypedActorRef[Any]`.
    */
  sealed trait Reply
  final case class Completed(targetBlock: BigInt, header: BlockHeader) extends Reply
  final case class Failed(reason: String) extends Reply

  /** The default `headerCheck`: every header that matches the target is accepted. */
  val AcceptAll: BlockHeader => Either[BlockHeaderError, BlockHeaderValid] = _ => Right(BlockHeaderValid)

  /** The `Failed` reason when no peer is left to ask after a header was rejected: the caller re-selects the target from
    * the peers that remain (the peer that served the rejected header is blacklisted).
    */
  val ExhaustedAfterRejection: String = "eligible peers exhausted after header rejection"

  /** The `Failed` reason in ancestry mode when every eligible peer was asked and none served the run (each was behind
    * the head, say): the caller asks again later, from the consensus layer's newest head.
    */
  val AncestryNotServed: String = "no peer served the head's ancestry"

  /** The most headers below a head an ancestry fetch asks for: go-ethereum's `MaxHeaderFetch` (192) per request. */
  val MaxAncestryDepth: Int = 192

  /** Why a reverse header run from a head hash does not give the header `depth` below it ([[ancestryPivot]]). */
  enum AncestryProblem:
    /** The run is the head's ancestry so far but shorter than asked: the peer does not hold it all (it is behind the
      * head, say). Not a fault of the peer.
      */
    case Incomplete(received: Int)

    /** The run is not the head's ancestry: the first header is not the head, a header is not its predecessor's parent
      * (hash and number), or there are more headers than asked for. `metricReason` labels `chain.header.rejected`.
      */
    case Mismatch(reason: String, metricReason: String)

  /** The header `depth` blocks below `headHash`, from `headers`, a reverse run that must be exactly `depth + 1`
    * headers: the first one is the header whose hash is `headHash`, and each one after it is the parent of the one
    * before it (its hash is that header's parent hash, its number one less). The header returned is then the head's
    * ancestor by hash, whoever served the run.
    */
  def ancestryPivot(headHash: ByteString, depth: Int, headers: Seq[BlockHeader]): Either[AncestryProblem, BlockHeader] =
    val run = headers.toIndexedSeq
    val expected = depth + 1
    if run.isEmpty then Left(AncestryProblem.Incomplete(0))
    else if run.head.hash.value != headHash then
      Left(AncestryProblem.Mismatch(s"the first header ${run.head.idTag} is not the head", "AncestryHead"))
    else
      val broken = (1 until run.size).find(i =>
        run(i).hash != run(i - 1).parentHash || run(i).number.value != run(i - 1).number.value - 1
      )
      broken match
        case Some(i) =>
          Left(
            AncestryProblem.Mismatch(
              s"header ${run(i).idTag} (position $i) is not the parent of ${run(i - 1).idTag}",
              HeaderChainCheck.ParentLinkReason
            )
          )
        case None if run.size < expected => Left(AncestryProblem.Incomplete(run.size))
        case None if run.size > expected =>
          Left(AncestryProblem.Mismatch(s"${run.size} headers for $expected asked", "AncestryLength"))
        case None => Right(run.last)

  /** Fetch by block number (the standard pre-merge / TD-driven SNAP path).
    *
    * @param headerCheck
    *   run on a header that matches the target before it is stored; a `Left` rejects it: the peer that served it is
    *   blacklisted (`BlockHeaderValidationFailed`) and the fetch is retried from another peer, consuming an attempt.
    *   When no eligible peer is left after a rejection, the bootstrap replies [[Failed]] ([[ExhaustedAfterRejection]])
    *   instead of waiting for a fresh peer.
    */
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
      preferSnapPeers: Boolean = false,
      headerCheck: BlockHeader => Either[BlockHeaderError, BlockHeaderValid] = AcceptAll
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
      preferSnapPeers,
      headerCheck
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
      preferSnapPeers,
      AcceptAll
    )

  /** Ancestry mode: the header `depth` blocks below the consensus layer's head `headHash` (post-merge chains). One
    * `GetBlockHeaders(Right(headHash), depth + 1, reverse)` per attempt; the run must be the head's ancestry
    * ([[ancestryPivot]]). A run that is not is not stored: its peer is blacklisted (`BlockHeaderValidationFailed`) and
    * another peer is asked. A peer that holds only part of the run is skipped without blame. When no eligible peer is
    * left the bootstrap replies [[Failed]] ([[ExhaustedAfterRejection]] after a rejected run, else
    * [[AncestryNotServed]]) instead of waiting: the caller asks again from the newest head.
    */
  def applyAncestry(
      peersClient: ActorRef[PeersClient.Command],
      blockchainWriter: BlockchainWriter,
      headHash: ByteString,
      depth: Int,
      replyTo: ActorRef[Reply],
      syncConfig: SyncConfig,
      maxAttempts: Int = 10,
      initialRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      waitForPeerDelay: FiniteDuration = 30.seconds
  ): Behavior[Command] =
    require(depth >= 0 && depth <= MaxAncestryDepth, s"ancestry depth must be in 0..$MaxAncestryDepth, was $depth")
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
      preferSnapPeers = false,
      headerCheck = AcceptAll,
      ancestryDepth = Some(depth)
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
      preferSnapPeers: Boolean,
      headerCheck: BlockHeader => Either[BlockHeaderError, BlockHeaderValid],
      ancestryDepth: Option[Int] = None
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
          preferSnapPeers,
          headerCheck,
          ancestryDepth
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
      preferSnapPeers: Boolean,
      headerCheck: BlockHeader => Either[BlockHeaderError, BlockHeaderValid],
      // Ancestry mode (applyAncestry): the header this many blocks below the head `targetHash`.
      ancestryDepth: Option[Int]
  ):

    // Safe from any thread (the `peersClient ?` callbacks run off the actor thread).
    private val asyncLog = LoggerFactory.getLogger(getClass)

    // The ask future combinators run off the actor thread.
    private given ec: ExecutionContext = ctx.executionContext
    // Typed AskPattern needs an implicit Scheduler.
    implicit private val scheduler: Scheduler = ctx.system.scheduler

    private val byHashMode: Boolean = targetHash.isDefined
    private def targetDesc: String = (targetHash, ancestryDepth) match
      case (Some(h), Some(depth)) =>
        s"$depth below head=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(h)}"
      case (Some(h), None) => s"hash=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(h)}"
      case (None, _)       => s"block=$targetBlock"

    private var attempt: Int = 0
    private val triedPeers: scala.collection.mutable.Set[PeerId] = scala.collection.mutable.Set.empty
    private var waitCount: Int = 0
    // Headers `headerCheck` rejected. Once one has been, an empty pool ends the bootstrap instead of waiting: the peers
    // that claimed the target's height may be exactly the ones that served a rejected header.
    private var rejections: Int = 0

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

      case Rejected(peerOpt, header, error) =>
        // Not stored: blacklist the peer that served it (the shared sync blacklist, via PeersClient) and retry; the peer
        // is already in `triedPeers`, so the next attempt asks another.
        rejections += 1
        val peerDesc = peerOpt.map(_.id.value).getOrElse("?")
        ctx.log.warn(
          s"${HeaderChainCheck.LogTag} pivot header ${header.idTag} from peer $peerDesc failed validation ($error); " +
            "blacklisting the peer and asking another"
        )
        SNAPSyncMetrics.incrementHeaderRejected(
          HeaderChainCheck.rejectionReason(error),
          HeaderChainCheck.Phase.PivotBootstrap
        )
        peerOpt.foreach(p => peersClient ! BlacklistPeer(p.id, BlacklistReason.BlockHeaderValidationFailed))
        ctx.self ! Retry(s"header ${header.idTag} from peer $peerDesc failed validation: $error")
        Behaviors.same

      case AncestryRejected(peerOpt, reason, metricReason) =>
        // Not the head's ancestry: nothing is stored; blacklist the peer (as for a header that fails validation) and
        // ask another (it is in `triedPeers`).
        rejections += 1
        val peerDesc = peerOpt.map(_.id.value).getOrElse("?")
        ctx.log.warn(
          s"${HeaderChainCheck.LogTag} pivot ancestry run ($targetDesc) from peer $peerDesc is not the head's ancestry " +
            s"($reason); blacklisting the peer and asking another"
        )
        SNAPSyncMetrics.incrementHeaderRejected(metricReason, HeaderChainCheck.Phase.PivotBootstrap)
        peerOpt.foreach(p => peersClient ! BlacklistPeer(p.id, BlacklistReason.BlockHeaderValidationFailed))
        ctx.self ! Retry(s"ancestry run from peer $peerDesc rejected: $reason")
        Behaviors.same

      case NoEligiblePeer if ancestryDepth.isDefined =>
        // Ancestry mode does not wait for a fresh peer: the head it walks back from ages, and the caller asks again from
        // the consensus layer's newest one.
        val reason = if rejections > 0 then ExhaustedAfterRejection else AncestryNotServed
        ctx.log.warn(
          s"Pivot ancestry bootstrap ($targetDesc): no eligible peer left (${triedPeers.size} tried, $rejections " +
            s"rejected run(s)); giving up: $reason"
        )
        replyTo ! Failed(reason)
        Behaviors.stopped

      case NoEligiblePeer if rejections > 0 =>
        ctx.log.warn(
          s"${HeaderChainCheck.LogTag} pivot header bootstrap for $targetDesc: no eligible peer left after " +
            s"$rejections rejected header(s) (${triedPeers.size} peer(s) tried); giving up so the target is chosen again"
        )
        replyTo ! Failed(ExhaustedAfterRejection)
        Behaviors.stopped

      case NoEligiblePeer =>
        // Pool empty or every known peer already tried. Model Besu's waitForPeer(!peersUsed.contains(p)): wait for a
        // fresh peer to connect.
        ctx.log.info(
          "Pivot header bootstrap for {}: no eligible peer available ({} tried). Waiting {} for a fresh peer.",
          targetDesc,
          triedPeers.size,
          waitForPeerDelay
        )
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
      // Build the GetBlockHeaders request — Right(hash) for by-hash mode, Left(number) for by-number. Ancestry mode: the
      // head and the `depth` headers below it, in reverse.
      val target: Either[BigInt, ByteString] = targetHash.toRight(targetBlock)
      val msg = ancestryDepth match
        case Some(depth) =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, target, maxHeaders = depth + 1, skip = 0, reverse = true)
        case None =>
          ETHPackets.GetBlockHeaders(ETHPackets.nextRequestId, target, maxHeaders = 1, skip = 0, reverse = false)

      // Peer selection:
      //   By-number with preferSnapPeers (standard SNAP pivot bootstrap): BestSnapPeerWithMinBlockExcluding
      //     combines Besu's two-stage pattern — PivotSelectorFromPeers pre-filters by estimatedChainHeight >= pivot,
      //     PivotBlockConfirmer excludes used peers — into a single selector. This prevents Besu ETH69 (synthetic
      //     TD ~61.66×10²¹ >> real ETC TD) from being selected when its reported maxBlockNumber is below targetBlock,
      //     and rotates through remaining SNAP-capable peers via the exclusion set.
      //   By-number without preferSnapPeers: BestPeerWithMinBlockExcluding rotates through the full pool.
      //   By-hash (preferSnapPeers=false in SyncController): BestPeer — CL-driven path.
      //   By-hash + preferSnapPeers: unreachable in production (targetBlock=0, no meaningful minBlock).
      //   Ancestry: any peer not yet asked (ExcludingPeers), so a peer whose run was rejected or short is not asked again.
      val selector =
        if ancestryDepth.isDefined then ExcludingPeers(triedPeers.toSet)
        else if preferSnapPeers && !byHashMode then BestSnapPeerWithMinBlockExcluding(targetBlock, triedPeers.toSet)
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
            (Some(peer), headers.headers, false)
          case PeersClient.Response(peer, _) =>
            (Some(peer), Seq.empty[BlockHeader], false)
          case NoSuitablePeer =>
            (None, Seq.empty[BlockHeader], false)
          case RequestFailed(peer, reason) =>
            asyncLog.warn("Pivot header request failed: {}", reason)
            (Some(peer), Seq.empty[BlockHeader], true)
        }
        .recover { case ex =>
          asyncLog.warn("Pivot header bootstrap ask failed (attempt {}/{}): {}", attempt, maxAttempts, ex.getMessage)
          (None, Seq.empty[BlockHeader], false)
        }
        .foreach { case (peerOpt, headers, isFailed) =>
          peerOpt.foreach(p => triedPeers += p.id)
          (targetHash, ancestryDepth) match
            case (Some(head), Some(depth)) if headers.nonEmpty => onAncestryRun(peerOpt, head, depth, headers)
            case _                                             => onHeader(peerOpt, headers.headOption, isFailed)
        }

    /** One header (by number or by hash), or none, from `peerOpt`. Runs on the ask callback's thread. */
    private def onHeader(peerOpt: Option[Peer], headerOpt: Option[BlockHeader], isFailed: Boolean): Unit =
      headerOpt match
        case Some(header) if matchesTarget(header) =>
          headerCheck(header) match
            case Right(_)    => ctx.self ! Fetched(header)
            case Left(error) => ctx.self ! Rejected(peerOpt, header, error)
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
          // NoSuitablePeer: decided on the actor thread (wait for a fresh peer, or give up after a rejection).
          ctx.self ! NoEligiblePeer

    /** Ancestry mode: a non-empty reverse run from `head`. Runs on the ask callback's thread. */
    private def onAncestryRun(peerOpt: Option[Peer], head: ByteString, depth: Int, headers: Seq[BlockHeader]): Unit =
      ancestryPivot(head, depth, headers) match
        case Right(pivot)                               => ctx.self ! Fetched(pivot)
        case Left(AncestryProblem.Incomplete(received)) =>
          // The peer holds only part of the run (it is behind the head): ask another, consuming no attempt.
          asyncLog.debug(
            "Pivot ancestry ({}): peer {} served {} of {} headers; asking another",
            targetDesc,
            peerOpt.map(_.id.value).getOrElse("?"),
            received,
            depth + 1
          )
          ctx.self ! WaitForPeer
        case Left(AncestryProblem.Mismatch(reason, metricReason)) =>
          ctx.self ! AncestryRejected(peerOpt, reason, metricReason)

    private def matchesTarget(header: BlockHeader): Boolean =
      targetHash match
        case Some(hash) => header.hash.value == hash
        case None       => header.number.value == targetBlock
