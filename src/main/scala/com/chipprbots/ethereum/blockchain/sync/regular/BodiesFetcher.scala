package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.PeersClient
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
    val peersClient: ActorRef[PeersClient.Command],
    val syncConfig: SyncConfig,
    val supervisor: ActorRef[FetchCommand],
    context: ActorContext[BodiesFetcher.BodiesFetcherCommand]
) extends AbstractBehavior[BodiesFetcher.BodiesFetcherCommand](context)
    with FetchRequest[BodiesFetcher.BodiesFetcherCommand]:

  val log = context.log
  given scheduler: Scheduler = context.system.scheduler
  given runtime: IORuntime = IORuntime.global

  import BodiesFetcher.*
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
  // Workers that have not reported yet, with the slice each fetches (B-016). A worker stops itself after it reports, so
  // a worker that stops while it is here died on its way: its slice is handled as one that failed.
  private var workers: Map[ActorRef[BodiesSliceFetcher.SliceCommand], WorkerSlice] = Map.empty

  private def spawnWorker(gen: Long, slice: WorkerSlice): Unit =
    sliceIdCounter += 1
    val worker = context.spawn(
      BodiesSliceFetcher(peersClient, syncConfig, context.self, gen),
      s"bodies-slice-$sliceIdCounter"
    )
    workers += worker -> slice
    context.watchWith(worker, WorkerStopped(worker))
    worker ! BodiesSliceFetcher.FetchSlice(slice.hashes, slice.triedPeers, slice.retryCount)

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): Command = AdaptedMessage(peer, msg)

  override def onMessage(message: Command): Behavior[Command] =
    message match
      case FetchBodies(hashes, triedPeers, retryCount) =>
        log.debug(
          "Start fetching bodies for {} hashes (tried: {}, retry: {})",
          hashes.size,
          triedPeers.size,
          retryCount
        )
        if hashes.isEmpty then log.warn("FetchBodies called with empty hashes list")
        val concurrency = syncConfig.bodiesFetchConcurrency
        if concurrency <= 1 || hashes.size <= syncConfig.blockBodiesPerRequest then
          requestBodies(hashes, triedPeers, retryCount)
        else fanOutBodies(hashes, triedPeers, retryCount, concurrency)
        Behaviors.same

      case AdaptedMessage(peer, blockBodies: ETHPackets.BlockBodies) =>
        handleBodiesResponse(peer, blockBodies.bodies, protocolLabel = "ETH68")

      case BodiesFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount) =>
        log.debug("Retrying bodies request (tried: {}, retry: {})", triedPeers.size, retryCount)
        supervisor ! BlockFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount)
        Behaviors.same

      case SliceComplete(bodies, peer, gen, worker) =>
        workers -= worker
        if gen != currentBatchGen then
          log.debug("Discarding stale SliceComplete gen={} current={}", gen, currentBatchGen)
        else
          collectedBodies = collectedBodies ++ bodies
          if sliceRepresentativePeer.isEmpty then sliceRepresentativePeer = Some(peer)
          pendingSlices -= 1
          if bodies.nonEmpty && bodies.size < syncConfig.blockBodiesPerRequest then
            log.warn(
              "[RegularSync] slice truncated: got {} bodies from {} — server likely hit softResponseLimit",
              bodies.size,
              peer.id
            )
          if pendingSlices == 0 then
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
        Behaviors.same

      case WorkerStopped(worker) =>
        // The termination is a system message and is read before ordinary ones, so a worker that reported and stopped
        // is seen stopped before its report. One more hop behind the report: by then it is out of `workers` if it did.
        context.self ! WorkerStoppedConfirmed(worker)
        Behaviors.same

      case WorkerStoppedConfirmed(worker) =>
        workers.get(worker).foreach { slice =>
          workers -= worker
          log.error(
            "[RegularSync] bodies slice worker {} stopped before it reported (gen={}): its slice is handled as failed",
            worker.path.name,
            slice.gen
          )
          context.self ! SliceFailed(slice.hashes, None, slice.triedPeers, slice.retryCount, slice.gen, worker)
        }
        Behaviors.same

      case SliceFailed(hashes, failedPeerId, triedPeers, retryCount, gen, worker) =>
        workers -= worker
        if gen != currentBatchGen then log.debug("Discarding stale SliceFailed gen={} current={}", gen, currentBatchGen)
        else
          pendingSlices -= 1
          if retryCount < syncConfig.maxBodyFetchRetries then
            val updatedTried = triedPeers ++ failedPeerId.toSet
            spawnWorker(gen, WorkerSlice(hashes, updatedTried, retryCount + 1, gen))
            pendingSlices += 1
          else
            log.warn(
              "[RegularSync] slice exhausted {} retries, aborting batch gen={}",
              retryCount,
              gen
            )
            // Invalidate the batch so orphan SliceComplete/SliceFailed from remaining workers are ignored.
            currentBatchGen = -1L
            // the workers of the aborted batch are not waited for: their reports are stale
            workers = workers.filter(_._2.gen != gen)
            pendingSlices = 0
            collectedBodies = Seq.empty
            sliceRepresentativePeer = None
            supervisor ! BlockFetcher.RetryBodiesRequest(failedPeerId, triedPeers, retryCount)
        Behaviors.same

      case other =>
        log.warn("BodiesFetcher received unhandled message of type: {}", other.getClass.getSimpleName)
        Behaviors.unhandled

  private def handleBodiesResponse(
      peer: Peer,
      bodies: Seq[BlockBody],
      protocolLabel: String
  ): Behavior[Command] =
    if bodies.nonEmpty then
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
    else log.debug("[RegularSync] empty bodies response from {}", peer.id)
    // Always forward bodies to supervisor to ensure state is cleared
    supervisor ! BlockFetcher.ReceivedBodies(peer, bodies)
    Behaviors.same

  private def fanOutBodies(
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId],
      retryCount: Int,
      concurrency: Int
  ): Unit =
    currentBatchGen += 1
    val gen = currentBatchGen
    pendingSlices = 0
    collectedBodies = Seq.empty
    sliceRepresentativePeer = None
    val sliceSize = math.ceil(hashes.size.toDouble / concurrency).toInt
    val slices = hashes.grouped(sliceSize).toVector
    slices.foreach { slice =>
      spawnWorker(gen, WorkerSlice(slice, triedPeers, retryCount, gen))
      pendingSlices += 1
    }
    log.info(
      "[RegularSync] fan-out: {} slice workers × ~{} hashes each, total={}, gen={}",
      slices.size,
      sliceSize,
      hashes.size,
      gen
    )

  private def requestBodies(hashes: Seq[ByteString], triedPeers: Set[PeerId], retryCount: Int): Unit =
    log.debug("Requesting {} block bodies (excluding {} tried peers)", hashes.size, triedPeers.size)
    val msg = ETHPackets.GetBlockBodies(ETHPackets.nextRequestId, hashes)
    val peerSelector = if triedPeers.nonEmpty then ExcludingPeers(triedPeers) else BestPeer
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

object BodiesFetcher:

  def apply(
      peersClient: ActorRef[PeersClient.Command],
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
      batchGen: Long,
      worker: ActorRef[BodiesSliceFetcher.SliceCommand]
  ) extends BodiesFetcherCommand
  final private[regular] case class SliceFailed(
      hashes: Seq[ByteString],
      failedPeerId: Option[PeerId],
      triedPeers: Set[PeerId],
      retryCount: Int,
      batchGen: Long,
      worker: ActorRef[BodiesSliceFetcher.SliceCommand]
  ) extends BodiesFetcherCommand

  /** The slice a worker fetches: what is asked again if the worker dies (B-016). */
  final private[regular] case class WorkerSlice(
      hashes: Seq[ByteString],
      triedPeers: Set[PeerId],
      retryCount: Int,
      gen: Long
  )
  final private[regular] case class WorkerStopped(worker: ActorRef[BodiesSliceFetcher.SliceCommand])
      extends BodiesFetcherCommand
  final private[regular] case class WorkerStoppedConfirmed(worker: ActorRef[BodiesSliceFetcher.SliceCommand])
      extends BodiesFetcherCommand
