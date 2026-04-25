package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.blockchain.sync.PeersClient.{
  BestPeer,
  BestSnapPeer,
  ExcludingPeers,
  NoSuitablePeer,
  Request,
  RequestFailed
}
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Fetches and persists a single pivot header (by block number) so SNAP can start without importing blocks.
  *
  * This is intentionally minimal: we only need the pivot header's stateRoot and number->hash mapping.
  */
final class PivotHeaderBootstrap(
    peersClient: ActorRef,
    blockchainWriter: BlockchainWriter,
    targetBlock: BigInt,
    @annotation.unused _syncConfig: SyncConfig,
    scheduler: Scheduler,
    maxAttempts: Int,
    initialRetryDelay: FiniteDuration,
    maxRetryDelay: FiniteDuration,
    preferSnapPeers: Boolean
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging {

  import PivotHeaderBootstrap._

  private var attempt: Int = 0

  // Peers that disconnected mid-request during this bootstrap session. Excluded from selection
  // until all peers have failed (reset), preventing tight reconnect loops (e.g. besu cycling every ~15s).
  private val failedPeers: mutable.Set[PeerId] = mutable.Set.empty

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
        context.parent ! Failed(s"exhausted attempts ($maxAttempts) fetching pivot header $targetBlock")
        context.stop(self)
      } else {
        fetchOnce()
      }

    case Fetched(header) =>
      try {
        blockchainWriter.storeBlockHeader(header).commit()
        context.parent ! Completed(targetBlock, header)
      } catch {
        case t: Throwable =>
          context.parent ! Failed(s"failed storing pivot header $targetBlock: ${t.getMessage}")
      } finally context.stop(self)

    case Retry(reason) =>
      log.warning(
        "Pivot header bootstrap retry {}/{} for block {} (reason: {})",
        attempt,
        maxAttempts,
        targetBlock,
        reason
      )
      val delay = currentRetryDelay
      log.info("Scheduling pivot header retry in {} (attempt {}/{})", delay, attempt, maxAttempts)
      scheduler.scheduleOnce(delay, self, Fetch)(context.dispatcher)

    case RetryExcluding(failedPeer, reason) =>
      failedPeers += failedPeer.id
      log.warning(
        "Pivot header peer {} failed — excluded for this bootstrap session ({} peers excluded). Reason: {}",
        failedPeer.id.value.take(16),
        failedPeers.size,
        reason
      )
      self ! Retry(reason)
  }

  private def fetchOnce(): Unit = {
    val msg = ETH66.GetBlockHeaders(ETH66.nextRequestId, Left(targetBlock), maxHeaders = 1, skip = 0, reverse = false)

    // When peers have failed previously, exclude them. Otherwise prefer SNAP-capable peers
    // (they're more likely to have recent headers). Fall back to any peer if no SNAP available.
    val primarySelector =
      if (failedPeers.nonEmpty) ExcludingPeers(failedPeers.toSet)
      else if (preferSnapPeers) BestSnapPeer
      else BestPeer

    val req = Request[ETH66.GetBlockHeaders](msg, primarySelector, (m: ETH66.GetBlockHeaders) => m)

    (peersClient ? req)
      .flatMap {
        case NoSuitablePeer if failedPeers.nonEmpty =>
          // All non-excluded peers exhausted — reset exclusion list and retry with full pool
          log.info(
            "No peer available after excluding {} failed peers — resetting exclusion list",
            failedPeers.size
          )
          failedPeers.clear()
          val retryMsg =
            ETH66.GetBlockHeaders(ETH66.nextRequestId, Left(targetBlock), maxHeaders = 1, skip = 0, reverse = false)
          val retrySelector = if (preferSnapPeers) BestSnapPeer else BestPeer
          peersClient ? Request[ETH66.GetBlockHeaders](retryMsg, retrySelector, (m: ETH66.GetBlockHeaders) => m)
        case NoSuitablePeer if preferSnapPeers =>
          // No SNAP peer available — fall back to any peer
          log.debug("No SNAP-capable peer for pivot header, falling back to BestPeer")
          val fallbackMsg =
            ETH66.GetBlockHeaders(ETH66.nextRequestId, Left(targetBlock), maxHeaders = 1, skip = 0, reverse = false)
          peersClient ? Request[ETH66.GetBlockHeaders](fallbackMsg, BestPeer, (m: ETH66.GetBlockHeaders) => m)
        case other =>
          scala.concurrent.Future.successful(other)
      }
      .foreach {
        case PeersClient.Response(_, eth66: ETH66BlockHeaders) =>
          eth66.headers.headOption match {
            case Some(header) if header.number == targetBlock => self ! Fetched(header)
            case Some(header) => self ! Retry(s"received header number ${header.number} != target $targetBlock")
            case None         => self ! Retry("empty headers response")
          }
        case PeersClient.Response(_, eth62: ETH62.BlockHeaders) =>
          eth62.headers.headOption match {
            case Some(header) if header.number == targetBlock => self ! Fetched(header)
            case Some(header) => self ! Retry(s"received header number ${header.number} != target $targetBlock")
            case None         => self ! Retry("empty headers response")
          }
        case NoSuitablePeer =>
          self ! Retry("no suitable peer")
        case RequestFailed(failedPeer, reason) =>
          self ! RetryExcluding(failedPeer, s"request failed: $reason")
        case other =>
          log.debug("Unexpected pivot header response: {}", other)
          self ! Retry("unexpected response")
      }
  }
}

object PivotHeaderBootstrap {
  def props(
      peersClient: ActorRef,
      blockchainWriter: BlockchainWriter,
      targetBlock: BigInt,
      syncConfig: SyncConfig,
      scheduler: Scheduler,
      maxAttempts: Int = 10,
      initialRetryDelay: FiniteDuration = 1.second,
      maxRetryDelay: FiniteDuration = 10.seconds,
      preferSnapPeers: Boolean = false
  )(implicit ec: ExecutionContext): Props =
    Props(
      new PivotHeaderBootstrap(
        peersClient,
        blockchainWriter,
        targetBlock,
        syncConfig,
        scheduler,
        maxAttempts,
        initialRetryDelay,
        maxRetryDelay,
        preferSnapPeers
      )
    )

  private case object Fetch
  final private case class Retry(reason: String)
  final private case class RetryExcluding(peer: com.chipprbots.ethereum.network.Peer, reason: String)
  final private case class Fetched(header: BlockHeader)

  final case class Completed(targetBlock: BigInt, header: BlockHeader)
  final case class Failed(reason: String)
}
