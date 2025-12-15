package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** AccountRangeWorker fetches a single account range from a peer.
  *
  * Responsibilities:
  * - Request single account range from peer
  * - Handle response and validate proofs
  * - Delegate storage to coordinator's downloader
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
  * @param accountRangeDownloader
  *   Shared downloader for storage operations
  */
class AccountRangeWorker(
    coordinator: ActorRef,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    accountRangeDownloader: AccountRangeDownloader
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
    case FetchAccountRange(task, peer) =>
      log.debug(s"Fetching account range ${task.rangeString} from peer ${peer.id}")
      
      // Use the downloader to send the request and track it
      accountRangeDownloader.requestNextRange(peer) match {
        case Some(requestId) =>
          currentTask = Some((task, peer, requestId))
          context.become(working)
          
          // Set up timeout
          import context.dispatcher
          context.system.scheduler.scheduleOnce(30.seconds, self, RequestTimeout(requestId))

        case None =>
          log.warning("Failed to send account range request")
          coordinator ! TaskFailed(0, "Failed to send request")
          context.stop(self)
      }
  }

  def working: Receive = {
    case AccountRangeResponseMsg(response) =>
      currentTask match {
        case Some((task, peer, requestId)) if response.requestId == requestId =>
          log.debug(s"Received account range response for request $requestId")
          
          // Delegate to downloader for processing
          accountRangeDownloader.handleResponse(response) match {
            case Right(accountCount) =>
              log.info(s"Successfully processed $accountCount accounts")
              coordinator ! TaskComplete(requestId, Right(accountCount))
              
              // Return to idle state for potential reuse
              currentTask = None
              context.become(idle)

            case Left(error) =>
              log.warning(s"Failed to process account range: $error")
              coordinator ! TaskFailed(requestId, error)
              
              // Return to idle state
              currentTask = None
              context.become(idle)
          }

        case _ =>
          log.warning(s"Received response for wrong request ID: ${response.requestId}")
      }

    case RequestTimeout(requestId) =>
      currentTask match {
        case Some((task, peer, reqId)) if reqId == requestId =>
          log.warning(s"Request $requestId timed out")
          coordinator ! TaskFailed(requestId, "Request timeout")
          
          // Return to idle state
          currentTask = None
          context.become(idle)

        case _ =>
          log.debug(s"Timeout for old or unknown request $requestId")
      }

    case FetchAccountRange(task, peer) =>
      log.warning("Worker is busy, cannot accept new task")
      coordinator ! TaskFailed(0, "Worker busy")
  }
}

object AccountRangeWorker {
  def props(
      coordinator: ActorRef,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      accountRangeDownloader: AccountRangeDownloader
  ): Props =
    Props(
      new AccountRangeWorker(
        coordinator,
        networkPeerManager,
        requestTracker,
        accountRangeDownloader
      )
    )
}
