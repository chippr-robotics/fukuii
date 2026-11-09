package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.StashBuffer

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*

/** ByteCodeWorker fetches bytecodes from a peer.
  *
  * Simplified worker that just handles network communication. All business logic is in ByteCodeCoordinator.
  *
  * Pekko Typed leaf actor (Group W1). `coordinator` is a typed ref (§8k-A). `networkPeerManager` remains a Classic ref
  * via the typed→classic adapter. Busy-state back-pressure is provided by a `Behaviors.withStash` buffer (replacing the
  * Classic `Stash` mixin).
  */
object ByteCodeWorker:

  import ByteCodeCoordinator.*

  type Command = WorkerMessage

  private val StashCapacity = 100

  /** @param coordinator
    *   Parent coordinator (Typed)
    * @param networkPeerManager
    *   Network manager (Classic)
    * @param requestTracker
    *   Request tracker
    */
  def apply(
      coordinator: ActorRef[ByteCodeCoordinator.Command],
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker
  ): Behavior[Command] =
    Behaviors.withStash[Command](StashCapacity) { stash =>
      idle(coordinator, networkPeerManager, requestTracker, stash)
    }

  private def idle(
      coordinator: ActorRef[ByteCodeCoordinator.Command],
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      stash: StashBuffer[Command]
  ): Behavior[Command] =
    Behaviors.receive[Command] { (context, msg) =>
      msg match
        case ByteCodeWorkerFetchTask(task, peer, requestId, maxResponseSize) =>
          val request = GetByteCodes(
            requestId = requestId,
            hashes = task.codeHashes,
            responseBytes = maxResponseSize
          )

          // Track request with adaptive timeout from SNAPRequestTracker / PeerRateTracker.
          // Starts at ~12s for a fresh tracker; converges down as peers respond so slow peers
          // get pruned faster instead of holding in-flight slots for a full 30s.
          // The callback runs outside the actor context, so capture context.self for the self-send.
          val selfRef = context.self
          requestTracker.trackRequest(
            requestId,
            peer,
            SNAPRequestTracker.RequestType.GetByteCodes
          ) {
            selfRef ! ByteCodeRequestTimeout(requestId)
          }

          context.log.debug(
            s"Requesting ${task.codeHashes.size} bytecodes from peer ${peer.id} (request ID: $requestId)"
          )

          // Send request via NetworkPeerManager
          import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
          val messageSerializable: MessageSerializable = new GetByteCodesEnc(request)
          networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(messageSerializable, peer.id)

          working(coordinator, networkPeerManager, requestTracker, stash, (task, peer, requestId))

        case _ => Behaviors.same // responses/timeouts/releases while idle: nothing to do
    }

  private def working(
      coordinator: ActorRef[ByteCodeCoordinator.Command],
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      stash: StashBuffer[Command],
      currentTask: (ByteCodeTask, Peer, BigInt)
  ): Behavior[Command] =
    val (_, _, requestId) = currentTask
    def goIdle: Behavior[Command] =
      stash.unstashAll(idle(coordinator, networkPeerManager, requestTracker, stash))

    Behaviors.receive[Command] { (context, msg) =>
      msg match
        case ByteCodesResponseMsg(response) =>
          if response.requestId == requestId then
            // IMPORTANT: mark the request complete so SNAPRequestTracker doesn't fire a timeout.
            requestTracker.completeRequest(requestId, response.codes.size.max(1))
            context.log.debug(s"Received bytecodes response for request $requestId")
            coordinator ! ByteCodesResponseMsg(response)
            goIdle
          else
            context.log.debug("Received response for wrong or old request")
            Behaviors.same

        case ByteCodeRequestTimeout(reqId) =>
          if reqId == requestId then
            // RequestTracker already removed this request when firing the callback; this is defensive.
            requestTracker.completeRequest(requestId)
            context.log.warn(s"Bytecode request $requestId timed out")
            coordinator ! ByteCodeTaskFailed(requestId, "Timeout")
            goIdle
          else Behaviors.same

        case ByteCodeWorkerRelease(reqId) =>
          if reqId == requestId then
            requestTracker.completeRequest(requestId, 0)
            goIdle
          else
            context.log.debug(s"ByteCodeWorkerRelease for unknown request $reqId, ignoring")
            Behaviors.same

        case task: ByteCodeWorkerFetchTask =>
          // Important: never drop tasks. Coordinator may already have recorded this request as active.
          if !stash.isFull then stash.stash(task)
          Behaviors.same

        case _: FetchByteCodes => Behaviors.same // legacy message, not used by this worker
    }
