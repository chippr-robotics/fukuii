package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Scheduler}
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.network.p2p.messages.ETH62
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.blockchain.sync.PeersClient.{BestPeer, NoSuitablePeer, Request, RequestFailed}
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Fetches and persists a single pivot header (by block number) so SNAP can start without importing blocks.
  *
  * This is intentionally minimal: we only need the pivot header's stateRoot and number->hash mapping.
  */
final class PivotHeaderBootstrap(
    peersClient: ActorRef,
    blockchainWriter: BlockchainWriter,
    targetBlock: BigInt,
    syncConfig: SyncConfig,
    scheduler: Scheduler,
    maxAttempts: Int,
    retryDelay: FiniteDuration
)(implicit ec: ExecutionContext)
    extends Actor
    with ActorLogging {

  import PivotHeaderBootstrap._

  private var attempt: Int = 0

  implicit private val timeout: Timeout = syncConfig.peerResponseTimeout + 2.seconds

  override def preStart(): Unit = {
    self ! Fetch
  }

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
        context.parent ! Completed(targetBlock)
      } catch {
        case t: Throwable =>
          context.parent ! Failed(s"failed storing pivot header $targetBlock: ${t.getMessage}")
      } finally {
        context.stop(self)
      }

    case Retry(reason) =>
      log.warning(
        "Pivot header bootstrap retry {}/{} for block {} (reason: {})",
        attempt,
        maxAttempts,
        targetBlock,
        reason
      )
      scheduler.scheduleOnce(retryDelay, self, Fetch)(context.dispatcher)
  }

  private def fetchOnce(): Unit = {
    val msg = ETH66.GetBlockHeaders(ETH66.nextRequestId, Left(targetBlock), maxHeaders = 1, skip = 0, reverse = false)

    // ETH66.GetBlockHeaders is already MessageSerializable, so the serializer is identity.
    val req = Request[ETH66.GetBlockHeaders](msg, BestPeer, (m: ETH66.GetBlockHeaders) => m)

    (peersClient ? req).map {
      case PeersClient.Response(_, eth66: ETH66BlockHeaders) =>
        eth66.headers.headOption
      case PeersClient.Response(_, eth62: ETH62.BlockHeaders) =>
        eth62.headers.headOption
      case NoSuitablePeer =>
        None
      case RequestFailed(_, reason) =>
        log.warning("Pivot header request failed: {}", reason)
        None
      case other =>
        log.debug("Unexpected pivot header response: {}", other)
        None
    }.foreach {
      case Some(header) if header.number == targetBlock =>
        self ! Fetched(header)
      case Some(header) =>
        self ! Retry(s"received header number ${header.number} != target $targetBlock")
      case None =>
        self ! Retry("no header returned")
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
      retryDelay: FiniteDuration = 1.second
  )(implicit ec: ExecutionContext): Props =
    Props(new PivotHeaderBootstrap(peersClient, blockchainWriter, targetBlock, syncConfig, scheduler, maxAttempts, retryDelay))

  private case object Fetch
  private final case class Retry(reason: String)
  private final case class Fetched(header: BlockHeader)

  final case class Completed(targetBlock: BigInt)
  final case class Failed(reason: String)
}
