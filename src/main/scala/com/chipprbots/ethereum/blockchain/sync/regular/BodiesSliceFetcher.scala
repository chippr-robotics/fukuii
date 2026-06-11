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
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Ephemeral typed actor that fetches one slice of block bodies and reports back to its coordinator.
  *
  * Lifecycle: spawned by [[BodiesFetcher]] with a single [[FetchSlice]] message → makes one network request via
  * [[FetchRequest.makeRequest]] → sends [[BodiesFetcher.SliceComplete]] or [[BodiesFetcher.SliceFailed]] to coordinator
  * → stops. Never retries internally; retry policy lives in the coordinator.
  *
  * @param batchGen
  *   Batch generation counter stamped on all outgoing messages so the coordinator can discard stale responses from
  *   prior (aborted) batches.
  */
class BodiesSliceFetcher(
    val peersClient: ClassicActorRef,
    val syncConfig: SyncConfig,
    coordinator: ActorRef[BodiesFetcher.BodiesFetcherCommand],
    batchGen: Long,
    context: ActorContext[BodiesSliceFetcher.SliceCommand]
) extends AbstractBehavior[BodiesSliceFetcher.SliceCommand](context)
    with FetchRequest[BodiesSliceFetcher.SliceCommand] {

  import BodiesSliceFetcher._

  val log = context.log
  implicit val runtime: IORuntime = IORuntime.global

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): SliceCommand = AdaptedMessage(peer, msg)

  override def onMessage(message: SliceCommand): Behavior[SliceCommand] =
    message match {
      case FetchSlice(hashes, triedPeers, retryCount) =>
        requestSlice(hashes, triedPeers, retryCount)
        Behaviors.same

      case AdaptedMessage(peer, blockBodies: ETHPackets.BlockBodies) =>
        log.debug(
          "[RegularSync][slice] received {} bodies from {} gen={}",
          blockBodies.bodies.size,
          peer.id,
          batchGen
        )
        coordinator ! BodiesFetcher.SliceComplete(blockBodies.bodies, peer, batchGen)
        Behaviors.stopped

      case RetrySliceRequest(failedPeerId, hashes, triedPeers, retryCount) =>
        coordinator ! BodiesFetcher.SliceFailed(hashes, failedPeerId, triedPeers, retryCount, batchGen)
        Behaviors.stopped

      case other =>
        log.warn("[RegularSync][slice] unhandled message: {}", other.getClass.getSimpleName)
        Behaviors.unhandled
    }

  private def requestSlice(hashes: Seq[ByteString], triedPeers: Set[PeerId], retryCount: Int): Unit = {
    val msg = ETHPackets.GetBlockBodies(ETHPackets.nextRequestId, hashes)
    val peerSelector = if (triedPeers.nonEmpty) ExcludingPeers(triedPeers) else BestPeer
    val fallback: SliceCommand = RetrySliceRequest(None, hashes, triedPeers, retryCount)
    val resp = makeRequest(Request.create(msg, peerSelector), fallback, triedPeers, retryCount)
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res) => res
      case Failure(ex) =>
        log.warn("[RegularSync][slice] request threw: {}", ex.getMessage)
        RetrySliceRequest(None, hashes, triedPeers, retryCount)
    }
  }
}

object BodiesSliceFetcher {

  def apply(
      peersClient: ClassicActorRef,
      syncConfig: SyncConfig,
      coordinator: ActorRef[BodiesFetcher.BodiesFetcherCommand],
      batchGen: Long
  ): Behavior[SliceCommand] =
    Behaviors.setup(ctx => new BodiesSliceFetcher(peersClient, syncConfig, coordinator, batchGen, ctx))

  sealed trait SliceCommand
  final case class FetchSlice(
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId] = Set.empty,
      retryCount: Int = 0
  ) extends SliceCommand
  final private[regular] case class RetrySliceRequest(
      failedPeerId: Option[PeerId],
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId],
      retryCount: Int
  ) extends SliceCommand
  final private[regular] case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends SliceCommand
}
