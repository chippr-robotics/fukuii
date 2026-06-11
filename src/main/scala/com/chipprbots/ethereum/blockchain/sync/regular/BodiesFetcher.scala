package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.ExcludingPeers
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.utils.Config.SyncConfig

class BodiesFetcher(
    val peersClient: ClassicActorRef,
    val syncConfig: SyncConfig,
    val supervisor: ActorRef[FetchCommand],
    context: ActorContext[BodiesFetcher.BodiesFetcherCommand]
) extends AbstractBehavior[BodiesFetcher.BodiesFetcherCommand](context)
    with FetchRequest[BodiesFetcher.BodiesFetcherCommand] {

  val log = context.log
  implicit val runtime: IORuntime = IORuntime.global

  import BodiesFetcher._
  private type Command = BodiesFetcher.BodiesFetcherCommand
  private var totalBodiesFetched: Long = 0L
  private val bodiesFetchStartMs: Long = System.currentTimeMillis()

  // Fan-out coordinator state. Only active when bodiesFetchConcurrency > 1.
  private var pendingSlices: Int = 0
  private var collectedBodies: Seq[BlockBody] = Seq.empty
  private var sliceRepresentativePeer: Option[Peer] = None
  // Monotonically increasing batch generation: stamps outgoing worker spawns and validates
  // incoming SliceComplete/SliceFailed to discard orphans from aborted batches.
  private var currentBatchGen: Long = 0L
  // Monotonic counter for unique worker actor names within this supervisor's child namespace.
  private var sliceIdCounter: Long = 0L

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): Command = AdaptedMessage(peer, msg)

  override def onMessage(message: Command): Behavior[Command] =
    message match {
      case FetchBodies(hashes, triedPeers, retryCount) =>
        log.debug(
          "Start fetching bodies for {} hashes (tried: {}, retry: {})",
          hashes.size,
          triedPeers.size,
          retryCount
        )
        if (hashes.isEmpty) {
          log.warn("FetchBodies called with empty hashes list")
        }
        val concurrency = syncConfig.bodiesFetchConcurrency
        if (concurrency <= 1 || hashes.size <= syncConfig.blockBodiesPerRequest) {
          requestBodies(hashes, triedPeers, retryCount)
        } else {
          fanOutBodies(hashes, triedPeers, retryCount, concurrency)
        }
        Behaviors.same

      case AdaptedMessage(peer, blockBodies: ETHPackets.BlockBodies) =>
        handleBodiesResponse(peer, blockBodies.bodies, protocolLabel = "ETH68")

      case BodiesFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount) =>
        log.debug("Retrying bodies request (tried: {}, retry: {})", triedPeers.size, retryCount)
        supervisor ! BlockFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount)
        Behaviors.same

      case SliceComplete(bodies, peer, gen) =>
        if (gen != currentBatchGen) {
          log.debug("Discarding stale SliceComplete gen={} current={}", gen, currentBatchGen)
        } else {
          collectedBodies = collectedBodies ++ bodies
          if (sliceRepresentativePeer.isEmpty) sliceRepresentativePeer = Some(peer)
          pendingSlices -= 1
          if (bodies.nonEmpty && bodies.size < syncConfig.blockBodiesPerRequest) {
            log.warn(
              "[RegularSync] slice truncated: got {} bodies from {} — server likely hit softResponseLimit",
              bodies.size,
              peer.id
            )
          }
          if (pendingSlices == 0) {
            val mergedCount = collectedBodies.size
            log.info(
              "[RegularSync] bodies={} merged from fan-out, peer={}",
              mergedCount,
              sliceRepresentativePeer.map(_.id).getOrElse("-")
            )
            totalBodiesFetched += mergedCount
            supervisor ! BlockFetcher.ReceivedBodies(sliceRepresentativePeer.get, collectedBodies)
            collectedBodies = Seq.empty
            sliceRepresentativePeer = None
          }
        }
        Behaviors.same

      case SliceFailed(hashes, failedPeerId, triedPeers, retryCount, gen) =>
        if (gen != currentBatchGen) {
          log.debug("Discarding stale SliceFailed gen={} current={}", gen, currentBatchGen)
        } else {
          pendingSlices -= 1
          if (retryCount < syncConfig.maxBodyFetchRetries) {
            val updatedTried = triedPeers ++ failedPeerId.toSet
            sliceIdCounter += 1
            val worker = context.spawn(
              BodiesSliceFetcher(peersClient, syncConfig, context.self, gen),
              s"bodies-slice-$sliceIdCounter"
            )
            worker ! BodiesSliceFetcher.FetchSlice(hashes, updatedTried, retryCount + 1)
            pendingSlices += 1
          } else {
            log.warn(
              "[RegularSync] slice exhausted {} retries, aborting batch gen={}",
              retryCount,
              gen
            )
            // Invalidate the batch so orphan SliceComplete/SliceFailed from remaining workers are ignored.
            currentBatchGen = -1L
            pendingSlices = 0
            collectedBodies = Seq.empty
            sliceRepresentativePeer = None
            supervisor ! BlockFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount)
          }
        }
        Behaviors.same

      case other =>
        log.warn("BodiesFetcher received unhandled message of type: {}", other.getClass.getSimpleName)
        Behaviors.unhandled
    }

  private def handleBodiesResponse(
      peer: Peer,
      bodies: Seq[BlockBody],
      protocolLabel: String
  ): Behavior[Command] = {
    if (bodies.nonEmpty) {
      totalBodiesFetched += bodies.size
      val rate = totalBodiesFetched * 1000L / (System.currentTimeMillis() - bodiesFetchStartMs).max(1)
      log.info(
        "[RegularSync] bodies={} from={} via={} total={} rate={}/s",
        bodies.size,
        peer.id,
        protocolLabel,
        totalBodiesFetched,
        rate
      )
    } else {
      log.debug("[RegularSync] empty bodies response from {}", peer.id)
    }
    // Always forward bodies to supervisor to ensure state is cleared
    supervisor ! BlockFetcher.ReceivedBodies(peer, bodies)
    Behaviors.same
  }

  private def fanOutBodies(
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId],
      retryCount: Int,
      concurrency: Int
  ): Unit = {
    currentBatchGen += 1
    val gen = currentBatchGen
    pendingSlices = 0
    collectedBodies = Seq.empty
    sliceRepresentativePeer = None
    val sliceSize = math.ceil(hashes.size.toDouble / concurrency).toInt
    val slices = hashes.grouped(sliceSize).toVector
    slices.foreach { slice =>
      sliceIdCounter += 1
      val worker = context.spawn(
        BodiesSliceFetcher(peersClient, syncConfig, context.self, gen),
        s"bodies-slice-$sliceIdCounter"
      )
      worker ! BodiesSliceFetcher.FetchSlice(slice, triedPeers, retryCount)
      pendingSlices += 1
    }
    log.info(
      "[RegularSync] fan-out: {} slice workers × ~{} hashes each, total={}, gen={}",
      slices.size,
      sliceSize,
      hashes.size,
      gen
    )
  }

  private def requestBodies(hashes: Seq[ByteString], triedPeers: Set[PeerId], retryCount: Int): Unit = {
    log.debug("Requesting {} block bodies (excluding {} tried peers)", hashes.size, triedPeers.size)
    val msg = ETHPackets.GetBlockBodies(ETHPackets.nextRequestId, hashes)
    val peerSelector = if (triedPeers.nonEmpty) ExcludingPeers(triedPeers) else BestPeer
    val fallback = BodiesFetcher.RetryBodiesRequest(failedPeerId = None, triedPeers, retryCount)
    val resp = makeRequest(Request.create(msg, peerSelector), fallback, triedPeers, retryCount)
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res: BodiesFetcher.RetryBodiesRequest) =>
        log.debug("Bodies request will be retried")
        res
      case Success(res) =>
        log.debug("Bodies request completed successfully")
        res
      case Failure(ex) =>
        log.warn("Bodies request failed with exception: {}", ex.getMessage)
        BodiesFetcher.RetryBodiesRequest(failedPeerId = None, triedPeers, retryCount)
    }
  }
}

object BodiesFetcher {

  def apply(
      peersClient: ClassicActorRef,
      syncConfig: SyncConfig,
      supervisor: ActorRef[FetchCommand]
  ): Behavior[BodiesFetcherCommand] =
    Behaviors.setup(context => new BodiesFetcher(peersClient, syncConfig, supervisor, context))

  sealed trait BodiesFetcherCommand
  final case class FetchBodies(
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId] = Set.empty,
      retryCount: Int = 0
  ) extends BodiesFetcherCommand
  final case class RetryBodiesRequest(
      failedPeerId: Option[PeerId],
      triedPeers: Set[PeerId],
      retryCount: Int
  ) extends BodiesFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends BodiesFetcherCommand
  // Sent by BodiesSliceFetcher workers back to the coordinator.
  final private[regular] case class SliceComplete(
      bodies: Seq[BlockBody],
      peer: Peer,
      batchGen: Long
  ) extends BodiesFetcherCommand
  final private[regular] case class SliceFailed(
      hashes: Seq[ByteString],
      failedPeerId: Option[PeerId],
      triedPeers: Set[PeerId],
      retryCount: Int,
      batchGen: Long
  ) extends BodiesFetcherCommand
}
