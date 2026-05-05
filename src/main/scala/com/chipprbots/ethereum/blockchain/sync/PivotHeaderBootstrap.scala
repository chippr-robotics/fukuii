package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.{ByteString, Timeout}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.blockchain.sync.PeersClient.{
  BestPeer,
  BestPeerWithMinBlockExcluding,
  BestSnapPeer,
  NoSuitablePeer,
  Request,
  RequestFailed
}
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Fetches and persists a single pivot header so SNAP can start without importing blocks.
  *
  * Two modes:
  *   - **By number** (`targetBlock`, `targetHash = None`): the standard pre-merge / non-CL path. Used by both fast-sync
  *     pivot bootstrap and SNAP's TD-driven pivot selection.
  *   - **By hash** (`targetBlock = 0`, `targetHash = Some(_)`): the CL-driven post-merge path (#1207). The CL has
  *     pushed a head hash via engine_forkchoiceUpdated; we fetch that hash directly via `GetBlockHeaders(Right(hash))`.
  *     The `Completed` reply uses `header.number` as the targetBlock so callers don't need to special-case the by-hash
  *     mode.
  */
final class PivotHeaderBootstrap(
    peersClient: ActorRef,
    blockchainWriter: BlockchainWriter,
    targetBlock: BigInt,
    targetHash: Option[ByteString],
    @annotation.unused _syncConfig: SyncConfig,
    scheduler: Scheduler,
    maxAttempts: Int,
    initialRetryDelay: FiniteDuration,
    maxRetryDelay: FiniteDuration,
    waitForPeerDelay: FiniteDuration,
    preferSnapPeers: Boolean
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging {

  import PivotHeaderBootstrap._

  private val byHashMode: Boolean = targetHash.isDefined
  private def targetDesc: String = targetHash match {
    case Some(h) => s"hash=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(h)}"
    case None    => s"block=$targetBlock"
  }

  private var attempt: Int = 0
  private val triedPeers: scala.collection.mutable.Set[PeerId] = scala.collection.mutable.Set.empty
  private var waitCount: Int = 0

  private def currentRetryDelay: FiniteDuration = {
    // Exponential backoff: initialRetryDelay * 2^(attempt-1), capped at maxRetryDelay
    val backoffMs = math.min(
      initialRetryDelay.toMillis * (1L << math.min(attempt - 1, 20)),
      maxRetryDelay.toMillis
    )
    backoffMs.millis
  }

  // Use a short, fixed timeout for single-header requests.
  // syncConfig.peerResponseTimeout (90s in cirith-ungol) is far too long for fetching 1 header.
  // A responsive peer returns a header in <5s; 15s is generous.
  implicit private val timeout: Timeout = Timeout(15.seconds)

  override def preStart(): Unit =
    self ! Fetch

  override def receive: Receive = {
    case Fetch =>
      attempt += 1
      if (attempt > maxAttempts) {
        context.parent ! Failed(s"exhausted attempts ($maxAttempts) fetching pivot header $targetDesc")
        context.stop(self)
      } else {
        fetchOnce()
      }

    case Fetched(header) =>
      try {
        blockchainWriter.storeBlockHeader(header).commit()
        // For by-hash mode: report the actual block number we discovered.
        val resolvedNumber = if (byHashMode) header.number else targetBlock
        context.parent ! Completed(resolvedNumber, header)
      } catch {
        case t: Throwable =>
          context.parent ! Failed(s"failed storing pivot header $targetDesc: ${t.getMessage}")
      } finally context.stop(self)

    case Retry(reason) =>
      log.warning(
        "Pivot header bootstrap retry {}/{} for {} (reason: {})",
        attempt,
        maxAttempts,
        targetDesc,
        reason
      )
      val delay = currentRetryDelay
      log.info("Scheduling pivot header retry in {} (attempt {}/{})", delay, attempt, maxAttempts)
      scheduler.scheduleOnce(delay, self, Fetch)(context.dispatcher, self)

    case WaitForPeer =>
      // Models Besu's waitForPeer(!peersUsed.contains(p)) / go-ethereum's idle-loop peer wait.
      // Does NOT increment `attempt` — starvation waits don't consume the retry budget.
      waitCount += 1
      if (waitCount % 4 == 0) {
        log.warning(
          "Pivot header bootstrap for {} has been waiting for a fresh peer for ~{}s ({} peer(s) tried so far)",
          targetDesc,
          waitCount * waitForPeerDelay.toSeconds,
          triedPeers.size
        )
      }
      fetchOnce()
  }

  private def fetchOnce(): Unit = {
    // Build the GetBlockHeaders request — Right(hash) for by-hash mode, Left(number) for by-number.
    val target: Either[BigInt, ByteString] = targetHash.toRight(targetBlock)
    val msg = ETH66.GetBlockHeaders(ETH66.nextRequestId, target, maxHeaders = 1, skip = 0, reverse = false)

    // Peer selection:
    //   By-number (standard ETC mainnet path): BestPeerWithMinBlockExcluding rotates through the pool,
    //     skipping peers already tried this bootstrap run (Besu peersUsed / go-ethereum idle-pool pattern).
    //   By-hash / preferSnapPeers: unchanged — CL-driven and SNAP paths don't need exclusion.
    val selector =
      if (preferSnapPeers) BestSnapPeer
      else if (byHashMode) BestPeer
      else BestPeerWithMinBlockExcluding(targetBlock, triedPeers.toSet)
    val req = Request[ETH66.GetBlockHeaders](msg, selector, (m: ETH66.GetBlockHeaders) => m)

    (peersClient ? req)
      .flatMap {
        case NoSuitablePeer if preferSnapPeers =>
          // No SNAP peer available — try any peer with the target as fallback
          log.debug("No SNAP-capable peer for pivot header, falling back")
          val fallbackMsg =
            ETH66.GetBlockHeaders(ETH66.nextRequestId, target, maxHeaders = 1, skip = 0, reverse = false)
          val fallbackSelector =
            if (byHashMode) BestPeer else BestPeerWithMinBlockExcluding(targetBlock, triedPeers.toSet)
          val fallbackReq =
            Request[ETH66.GetBlockHeaders](fallbackMsg, fallbackSelector, (m: ETH66.GetBlockHeaders) => m)
          peersClient ? fallbackReq
        case other =>
          scala.concurrent.Future.successful(other)
      }
      .map {
        case PeersClient.Response(peer, eth66: ETH66BlockHeaders) =>
          (Some(peer), eth66.headers.headOption)
        case PeersClient.Response(peer, eth62: ETH62.BlockHeaders) =>
          (Some(peer), eth62.headers.headOption)
        case NoSuitablePeer =>
          (None, None)
        case RequestFailed(peer, reason) =>
          log.warning("Pivot header request failed: {}", reason)
          (Some(peer), None)
        case other =>
          log.debug("Unexpected pivot header response: {}", other)
          (None, None)
      }
      .recover { case ex =>
        log.warning("Pivot header bootstrap ask failed (attempt {}/{}): {}", attempt, maxAttempts, ex.getMessage)
        (None, None)
      }
      .foreach { case (peerOpt, headerOpt) =>
        peerOpt.foreach(p => triedPeers += p.id)
        headerOpt match {
          case Some(header) if matchesTarget(header) =>
            self ! Fetched(header)
          case Some(header) =>
            self ! Retry(
              s"received header (number=${header.number}, hash=${com.chipprbots.ethereum.utils.ByteStringUtils
                  .hash2string(header.hash)}) doesn't match target $targetDesc"
            )
          case None if peerOpt.isDefined =>
            // A peer was selected and tried but returned no useful header
            self ! Retry("no header returned from peer")
          case None =>
            // NoSuitablePeer — pool empty or all known peers already tried.
            // Model Besu's waitForPeer(!peersUsed.contains(p)): wait for a fresh peer to connect.
            log.info(
              "Pivot header bootstrap for {}: no eligible peer available ({} tried). " +
                "Waiting {} for a fresh peer.",
              targetDesc,
              triedPeers.size,
              waitForPeerDelay
            )
            scheduler.scheduleOnce(waitForPeerDelay, self, WaitForPeer)(context.dispatcher, self)
        }
      }
  }

  private def matchesTarget(header: BlockHeader): Boolean =
    targetHash match {
      case Some(hash) => header.hash == hash
      case None       => header.number == targetBlock
    }
}

object PivotHeaderBootstrap {

  /** Fetch by block number (the standard pre-merge / fast-sync / TD-driven SNAP path). */
  def props(
      peersClient: ActorRef,
      blockchainWriter: BlockchainWriter,
      targetBlock: BigInt,
      syncConfig: SyncConfig,
      scheduler: Scheduler,
      maxAttempts: Int = 10,
      initialRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      waitForPeerDelay: FiniteDuration = 30.seconds,
      preferSnapPeers: Boolean = false
  )(implicit ec: ExecutionContext): Props =
    Props(
      new PivotHeaderBootstrap(
        peersClient,
        blockchainWriter,
        targetBlock,
        targetHash = None,
        syncConfig,
        scheduler,
        maxAttempts,
        initialRetryDelay,
        maxRetryDelay,
        waitForPeerDelay,
        preferSnapPeers
      )
    )

  /** Fetch by hash — the CL-driven post-merge path (#1207). Block number is unknown until the header arrives;
    * `Completed` carries the discovered `header.number`.
    */
  def propsByHash(
      peersClient: ActorRef,
      blockchainWriter: BlockchainWriter,
      headHash: ByteString,
      syncConfig: SyncConfig,
      scheduler: Scheduler,
      maxAttempts: Int = 10,
      initialRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      waitForPeerDelay: FiniteDuration = 30.seconds,
      preferSnapPeers: Boolean = false
  )(implicit ec: ExecutionContext): Props =
    Props(
      new PivotHeaderBootstrap(
        peersClient,
        blockchainWriter,
        targetBlock = BigInt(0),
        targetHash = Some(headHash),
        syncConfig,
        scheduler,
        maxAttempts,
        initialRetryDelay,
        maxRetryDelay,
        waitForPeerDelay,
        preferSnapPeers
      )
    )

  private case object Fetch
  private case object WaitForPeer
  final private case class Retry(reason: String)
  final private case class Fetched(header: BlockHeader)

  final case class Completed(targetBlock: BigInt, header: BlockHeader)
  final case class Failed(reason: String)
}
