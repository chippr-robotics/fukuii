package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** ByteCodeWorker fetches bytecodes from a peer.
  *
  * @param coordinator
  *   Parent coordinator
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  * @param bytecodeDownloader
  *   Shared downloader
  */
class ByteCodeWorker(
    coordinator: ActorRef,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker,
    bytecodeDownloader: ByteCodeDownloader
) extends Actor
    with ActorLogging {

  import Messages._

  private var currentRequestId: Option[BigInt] = None

  override def receive: Receive = idle

  def idle: Receive = {
    case FetchByteCodes(_, peer) =>
      bytecodeDownloader.requestNextBatch(peer) match {
        case Some(requestId) =>
          currentRequestId = Some(requestId)
          context.become(working)
          
          import context.dispatcher
          context.system.scheduler.scheduleOnce(30.seconds, self, ByteCodeRequestTimeout(requestId))

        case None =>
          log.debug("No more bytecodes to fetch")
          context.stop(self)
      }
  }

  def working: Receive = {
    case ByteCodesResponseMsg(response) =>
      currentRequestId match {
        case Some(requestId) if response.requestId == requestId =>
          bytecodeDownloader.handleResponse(response) match {
            case Right(count) =>
              coordinator ! ByteCodeTaskComplete(requestId, Right(count))
              currentRequestId = None
              context.become(idle)

            case Left(error) =>
              coordinator ! ByteCodeTaskFailed(requestId, error)
              currentRequestId = None
              context.become(idle)
          }
        case _ =>
          log.debug(s"Received response for wrong request")
      }

    case ByteCodeRequestTimeout(requestId) =>
      currentRequestId match {
        case Some(reqId) if reqId == requestId =>
          log.warning(s"Bytecode request $requestId timed out")
          coordinator ! ByteCodeTaskFailed(requestId, "Timeout")
          currentRequestId = None
          context.become(idle)
        case _ =>
      }
  }
}

object ByteCodeWorker {
  def props(
      coordinator: ActorRef,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker,
      bytecodeDownloader: ByteCodeDownloader
  ): Props =
    Props(new ByteCodeWorker(coordinator, networkPeerManager, requestTracker, bytecodeDownloader))
}
