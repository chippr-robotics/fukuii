package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** ByteCodeWorker fetches bytecodes from a peer.
  *
  * Simplified worker that just handles network communication.
  * All business logic is in ByteCodeCoordinator.
  *
  * @param coordinator
  *   Parent coordinator
  * @param networkPeerManager
  *   Network manager
  * @param requestTracker
  *   Request tracker
  */
class ByteCodeWorker(
    coordinator: ActorRef,
    networkPeerManager: ActorRef,
    requestTracker: SNAPRequestTracker
) extends Actor
    with ActorLogging {

  import Messages._

  private var currentRequestId: Option[BigInt] = None

  override def receive: Receive = {
    case ByteCodeWorkerFetchTask(task, peer, requestId, maxResponseSize) =>
      currentRequestId = Some(requestId)
      
      val request = GetByteCodes(
        requestId = requestId,
        hashes = task.codeHashes,
        responseBytes = maxResponseSize
      )

      // Track request with timeout
      requestTracker.trackRequest(
        requestId,
        peer,
        SNAPRequestTracker.RequestType.GetByteCodes,
        timeout = 30.seconds
      ) {
        self ! ByteCodeRequestTimeout(requestId)
      }

      log.debug(s"Requesting ${task.codeHashes.size} bytecodes from peer ${peer.id} (request ID: $requestId)")

      // Send request via NetworkPeerManager
      import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
      val messageSerializable: MessageSerializable = new GetByteCodesEnc(request)
      networkPeerManager ! NetworkPeerManagerActor.SendMessage(messageSerializable, peer.id)

    case ByteCodesResponseMsg(response) =>
      currentRequestId match {
        case Some(requestId) if response.requestId == requestId =>
          log.debug(s"Received bytecodes response for request $requestId")
          coordinator ! ByteCodesResponseMsg(response)
          currentRequestId = None

        case _ =>
          log.debug(s"Received response for wrong or old request")
      }

    case ByteCodeRequestTimeout(requestId) =>
      currentRequestId match {
        case Some(reqId) if reqId == requestId =>
          log.warning(s"Bytecode request $requestId timed out")
          coordinator ! ByteCodeTaskFailed(requestId, "Timeout")
          currentRequestId = None
        case _ =>
      }
  }
}

object ByteCodeWorker {
  def props(
      coordinator: ActorRef,
      networkPeerManager: ActorRef,
      requestTracker: SNAPRequestTracker
  ): Props =
    Props(new ByteCodeWorker(coordinator, networkPeerManager, requestTracker))
}
