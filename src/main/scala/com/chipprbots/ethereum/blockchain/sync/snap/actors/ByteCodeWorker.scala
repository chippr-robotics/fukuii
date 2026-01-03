package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.{Actor, ActorLogging, ActorRef, Props, Stash}

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
  with ActorLogging
  with Stash {

  import Messages._

  private var currentTask: Option[(ByteCodeTask, Peer, BigInt)] = None // (task, peer, requestId)

  override def receive: Receive = idle

  private def idle: Receive = {
    case ByteCodeWorkerFetchTask(task, peer, requestId, maxResponseSize) =>
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

      currentTask = Some((task, peer, requestId))
      context.become(working)
  }

  private def working: Receive = {
    case ByteCodesResponseMsg(response) =>
      currentTask match {
        case Some((_, _, requestId)) if response.requestId == requestId =>
          // IMPORTANT: mark the request complete so SNAPRequestTracker doesn't fire a timeout.
          requestTracker.completeRequest(requestId)
          log.debug(s"Received bytecodes response for request $requestId")
          coordinator ! ByteCodesResponseMsg(response)

          currentTask = None
          context.become(idle)
          unstashAll()

        case _ =>
          log.debug("Received response for wrong or old request")
      }

    case ByteCodeRequestTimeout(requestId) =>
      currentTask match {
        case Some((_, _, reqId)) if reqId == requestId =>
          // RequestTracker already removed this request when firing the callback; this is defensive.
          requestTracker.completeRequest(requestId)
          log.warning(s"Bytecode request $requestId timed out")
          coordinator ! ByteCodeTaskFailed(requestId, "Timeout")
          currentTask = None
          context.become(idle)
          unstashAll()
        case _ =>
      }

    case _: ByteCodeWorkerFetchTask =>
      // Important: never drop tasks. Coordinator may already have recorded this request as active.
      stash()
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
