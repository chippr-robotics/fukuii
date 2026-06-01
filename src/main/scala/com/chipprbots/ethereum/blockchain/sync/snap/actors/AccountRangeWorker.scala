package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** AccountRangeWorker fetches a single account range from a peer.
  *
  * Responsibilities:
  *   - Request single account range from peer
  *   - Handle response and validate proofs
  *   - Report result to coordinator
  *
  * Lifecycle:
  *   1. Created by coordinator when needed 2. Fetches one task 3. Reports result 4. Can be reused for next task or
  *      stopped
  *
  * @param coordinator
  *   Parent coordinator actor
  * @param networkPeerManager
  *   Actor for network communication
  * @param requestTracker
  *   Tracker for requests
  */
class AccountRangeWorker(
    coordinator: ActorRef,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker
) extends Actor
    with ActorLogging {

  import Messages._

  // 4-tuple: (task, peer, requestId, expectedRoot)
  // expectedRoot is snapshotted from task.rootHash at FetchAccountRange receive time so that
  // a concurrent pivot refresh (task.rootHash mutation by the coordinator) cannot affect
  // the Merkle proof verification that runs when the response arrives.
  private var currentTask: Option[(AccountTask, Peer, BigInt, ByteString)] = None

  override def preStart(): Unit =
    log.debug(s"AccountRangeWorker ${self.path.name} started")

  override def postStop(): Unit =
    log.debug(s"AccountRangeWorker ${self.path.name} stopped")

  override def receive: Receive = idle

  def idle: Receive = {
    case _: WorkerRequestCancelled => // #1184: idempotent — already idle, nothing to clear
    case _: WorkerPeerDisconnected => // No current task, nothing to do
    case FetchAccountRange(task, peer, requestId, responseBytes) =>
      // Snapshot rootHash now — the coordinator may mutate task.rootHash on pivot refresh
      // while this response is in-flight. Using the snapshot makes proof verification
      // deterministic regardless of when the mutation occurs.
      val expectedRoot = task.rootHash
      log.debug(s"Fetching account range ${task.rangeString} from peer ${peer.id} (responseBytes=$responseBytes)")

      val request = GetAccountRange(
        requestId = requestId,
        rootHash = expectedRoot,
        startingHash = task.next,
        limitHash = task.last,
        responseBytes = responseBytes
      )

      // Track the request with adaptive timeout from SNAPRequestTracker / PeerRateTracker
      // (geth msgrate algorithm). Starts at ~12s for a fresh tracker, converges down as peers
      // respond — slow peers get pruned faster instead of holding in-flight slots for a full 30s.
      requestTracker.trackRequest(
        requestId,
        peer,
        SNAPRequestTracker.RequestType.GetAccountRange
      ) {
        self ! RequestTimeout(requestId)
      }

      // Send message to peer
      import com.chipprbots.ethereum.network.NetworkPeerManagerActor
      import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
      import com.chipprbots.ethereum.network.p2p.MessageSerializable
      val messageSerializable: MessageSerializable = new GetAccountRangeEnc(request)
      networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

      currentTask = Some((task, peer, requestId, expectedRoot))
      context.become(working)
  }

  def working: Receive = {
    case AccountRangeResponseMsg(response) =>
      currentTask match {
        case Some((task, _, reqId, expectedRoot)) if response.requestId == reqId =>
          log.debug(
            s"Received AccountRange: reqId=$reqId range=${task.rangeString} " +
              s"start=${task.next.take(4).toHex} limit=${task.last.take(4).toHex} " +
              s"accounts=${response.accounts.size} proofNodes=${response.proof.size}"
          )

          val accountCount = response.accounts.size

          // Validate basic response invariants (monotonic ordering, correct tracked type)
          // while the request is still pending in the tracker.
          val validated = requestTracker.validateAccountRange(response)

          // Complete the request in tracker (cancel timeout) regardless of validation outcome.
          // A proof-only empty range is still a served response, not a timeout/failure.
          val responseItemsForRate =
            if (accountCount > 0) accountCount
            else if (response.proof.nonEmpty) 1
            else 0
          requestTracker.completeRequest(reqId, responseItemsForRate)

          // Verify Merkle proof against the snapshotted pivot state root (expectedRoot),
          // not task.rootHash — the coordinator may have mutated it during a pivot refresh.
          val proofVerifier = MerkleProofVerifier(expectedRoot)
          val proofOk = validated.flatMap { validResponse =>
            val endHash = validResponse.accounts.lastOption.map(_._1).getOrElse(task.last)
            proofVerifier.verifyAccountRange(
              accounts = validResponse.accounts,
              proof = validResponse.proof,
              startHash = task.next,
              endHash = endHash
            )
          }

          proofOk match {
            case Left(error) =>
              val errorStr = error.toString
              // Root mismatch during a pivot transition is expected — the peer is serving the new
              // root while this worker was dispatched against the old one. Demote to debug since
              // TaskFailed is still sent and the coordinator re-queues normally.
              if (errorStr.contains("root mismatch") || errorStr.contains("Proof root"))
                log.debug(
                  s"AccountRange proof skipped (pivot transition) reqId=$reqId range=${task.rangeString}: $error"
                )
              else
                log.warning(s"AccountRange validation/proof failed for reqId=$reqId range=${task.rangeString}: $error")
              coordinator ! TaskFailed(reqId, error)

            case Right(_) =>
              log.debug(s"Successfully received $accountCount accounts")
              coordinator ! TaskComplete(reqId, Right((accountCount, response.accounts, response.proof)))
          }

          // Return to idle state for potential reuse
          currentTask = None
          context.become(idle)

        case _ =>
          log.warning(s"Received response for wrong request ID: ${response.requestId}")
      }

    case RequestTimeout(reqId) =>
      currentTask match {
        case Some((_, _, currentReqId, _)) if currentReqId == reqId =>
          log.warning(s"Request $reqId timed out")
          coordinator ! TaskFailed(reqId, "Request timeout")

          // Return to idle state
          currentTask = None
          context.become(idle)

        case _ =>
          log.debug(s"Timeout for old or unknown request $reqId")
      }

    case WorkerPeerDisconnected(peerId) =>
      currentTask match {
        case Some((_, peer, reqId, _)) if peer.id.value == peerId =>
          log.debug(s"Peer $peerId disconnected — re-queuing task immediately (reqId=$reqId)")
          requestTracker.completeRequest(reqId, 0)
          coordinator ! TaskFailed(reqId, "Peer disconnected")
          currentTask = None
          context.become(idle)
        case _ => // Different peer or no task; ignore
      }

    case WorkerRequestCancelled(reqId) =>
      // #1184: coordinator drained `activeTasks` and is owning the re-queue itself —
      // we just clear local state. Do NOT send TaskFailed (coordinator already re-queued).
      // Match existing tracker-ownership contract: worker owns its tracker entry.
      // Idempotent: SNAPRequestTracker.completeRequest is safe on already-removed ids.
      currentTask match {
        case Some((_, _, currentReqId, _)) if currentReqId == reqId =>
          log.debug(s"Worker request $reqId cancelled by coordinator — clearing state")
          requestTracker.completeRequest(reqId, 0)
          currentTask = None
          context.become(idle)
        case _ => // Different reqId or no current task; ignore
      }

    case FetchAccountRange(task, peer, _, _) =>
      log.warning("Worker is busy, cannot accept new task")
      coordinator ! TaskFailed(0, "Worker busy")
  }
}

object AccountRangeWorker {
  def props(
      coordinator: ActorRef,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker
  ): Props =
    Props(
      new AccountRangeWorker(
        coordinator,
        networkPeerManager,
        requestTracker
      )
    )
}
