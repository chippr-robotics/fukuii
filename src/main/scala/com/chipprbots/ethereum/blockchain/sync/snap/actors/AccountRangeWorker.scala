package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** AccountRangeWorker fetches a single account range from a peer.
  *
  * Responsibilities:
  * - Request single account range from peer
  * - Handle response and validate proofs
  * - Report result to coordinator
  *
  * Lifecycle:
  * 1. Created by coordinator when needed
  * 2. Fetches one task
  * 3. Reports result
  * 4. Can be reused for next task or stopped
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

  private var currentTask: Option[(AccountTask, Peer, BigInt)] = None // (task, peer, requestId)

  override def preStart(): Unit = {
    log.debug(s"AccountRangeWorker ${self.path.name} started")
  }

  override def postStop(): Unit = {
    log.debug(s"AccountRangeWorker ${self.path.name} stopped")
  }

  override def receive: Receive = idle

  def idle: Receive = {
    case FetchAccountRange(task, peer, requestId) =>
      log.debug(s"Fetching account range ${task.rangeString} from peer ${peer.id}")

      // Send request directly via network peer manager
      // Create GetAccountRange message
      val request = GetAccountRange(
        requestId = requestId,
        rootHash = task.rootHash,
        startingHash = task.next,
        limitHash = task.last,
        responseBytes = BigInt(512 * 1024)
      )
      
      // Track the request with timeout
      import context.dispatcher
      requestTracker.trackRequest(
        requestId,
        peer,
        SNAPRequestTracker.RequestType.GetAccountRange,
        timeout = 30.seconds
      ) {
        self ! RequestTimeout(requestId)
      }
      
      // Send message to peer
      import com.chipprbots.ethereum.network.NetworkPeerManagerActor
      import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
      import com.chipprbots.ethereum.network.p2p.MessageSerializable
      val messageSerializable: MessageSerializable = new GetAccountRangeEnc(request)
      networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)
      
      currentTask = Some((task, peer, requestId))
      context.become(working)
  }

  def working: Receive = {
    case AccountRangeResponseMsg(response) =>
      currentTask match {
        case Some((task, peer, reqId)) if response.requestId == reqId =>
          log.info(
            s"Received AccountRange: reqId=$reqId range=${task.rangeString} " +
              s"start=${task.next.take(4).toHex} limit=${task.last.take(4).toHex} " +
              s"accounts=${response.accounts.size} proofNodes=${response.proof.size}"
          )
          
          // Process response - extract accounts and report to coordinator
          val accountCount = response.accounts.size
          log.info(s"Successfully received $accountCount accounts")
          
          // Report result to coordinator with accounts
          coordinator ! TaskComplete(reqId, Right((accountCount, response.accounts)))
          
          // Complete the request in tracker
          requestTracker.completeRequest(reqId)
          
          // Return to idle state for potential reuse
          currentTask = None
          context.become(idle)

        case _ =>
          log.warning(s"Received response for wrong request ID: ${response.requestId}")
      }

    case RequestTimeout(reqId) =>
      currentTask match {
        case Some((task, peer, currentReqId)) if currentReqId == reqId =>
          log.warning(s"Request $reqId timed out")
          coordinator ! TaskFailed(reqId, "Request timeout")
          
          // Return to idle state
          currentTask = None
          context.become(idle)

        case _ =>
          log.debug(s"Timeout for old or unknown request $reqId")
      }

    case FetchAccountRange(task, peer, _) =>
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
