package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.typed.{ActorRef as TypedActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.FiniteDuration
import scala.reflect.TypeTest

import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.Command as PeerEventBusCommand
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.PeerDisconnected
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializable
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets

object PeerRequestHandler:

  // ---- Shared result types ----

  sealed trait Result:
    def requestId: Int
  final case class RequestFailed(requestId: Int, peer: Peer, reason: String) extends Result
  final case class ResponseReceived[T](requestId: Int, peer: Peer, response: T, timeTaken: Long) extends Result

  // ---- Typed API ----

  sealed trait Command

  final private case class MessageFromPeerCmd(msg: Message) extends Command
  final private case class PeerLeftCmd(peerId: com.chipprbots.ethereum.network.PeerId) extends Command
  private case object TimeoutCmd extends Command

  /** Typed factory: spawns one `PeerRequestHandler` per request. Replies to `replyTo` with `ResponseReceived` or
    * `RequestFailed` then stops. Callers pass an explicit `replyTo` because `context.parent` is unavailable in Pekko
    * Typed.
    */
  def behavior[RequestMsg <: Message, ResponseMsg <: Message](
      peer: Peer,
      responseTimeout: FiniteDuration,
      networkPeerManager: TypedActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: TypedActorRef[PeerEventBusCommand],
      requestMsg: RequestMsg,
      responseMsgCode: Int,
      replyTo: TypedActorRef[Result],
      requestId: Int
  )(using tt: TypeTest[Any, ResponseMsg], toSerializable: RequestMsg => MessageSerializable): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val startTime = System.currentTimeMillis()

        val expectedRequestId: Option[BigInt] = requestMsg match
          case hasId: ETHPackets.HasRequestId => Some(hasId.requestId)
          case _                              => None

        // Single adapter for all PeerEvent subtypes. Two registrations for the same type `T`
        // in Pekko's `internalMessageAdapter` overwrite each other (filterNot + prepend on
        // `_messageAdapters`), so separate msgAdapter/disconnectAdapter were both resolving to
        // the shared `messageAdapterRef` with only the last registration's function surviving.
        val peerEventAdapter: TypedActorRef[PeerEvent] =
          ctx.messageAdapter[PeerEvent] {
            case MessageFromPeer(m, _) => MessageFromPeerCmd(m)
            case PeerDisconnected(pid) => PeerLeftCmd(pid)
            case e                     => throw new MatchError(s"unexpected PeerEvent from bus: $e")
          }

        // Subscribe BEFORE sending. The response reaches the bus as a PublishCmd from PeerActor, a different sender
        // than this handler, so nothing orders it after our SubscribeCmd except the order of the sends below. With the
        // request sent first, a thread descheduled between the two sends lets a fast peer's reply (6 ms on loopback)
        // be published to a bus with no subscriber: it is dropped, and the request waits out the full responseTimeout.
        // Measured on hive "sync fukuii from fukuii": BlockBodies{requestId=55} decoded by PeerActor 6 ms after the
        // request, never delivered, PEER_REQUEST_TIMEOUT 90 s later; about 2 in 5 local runs stalled, 0 in 15 after.
        peerEventBus ! SubscribeCmd(
          PeerDisconnectedClassifier(PeerSelector.WithId(peer.id)),
          peerEventAdapter
        )
        peerEventBus ! SubscribeCmd(
          MessageClassifier(Set(responseMsgCode), PeerSelector.WithId(peer.id)),
          peerEventAdapter
        )
        networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(toSerializable(requestMsg), peer.id)
        timers.startSingleTimer("timeout", TimeoutCmd, responseTimeout)

        def timeTakenSoFar(): Long = System.currentTimeMillis() - startTime

        def cleanup(): Unit =
          timers.cancel("timeout")
          peerEventBus ! UnsubscribeAllCmd(peerEventAdapter)

        Behaviors.receiveMessage {
          case MessageFromPeerCmd(msg) =>
            msg match
              case responseMsg: ResponseMsg =>
                (expectedRequestId, responseMsg) match
                  case (Some(expected), hasId: ETHPackets.HasRequestId) if hasId.requestId != expected =>
                    ctx.log.debug(
                      "PEER_REQUEST_STALE: peer={}, expected requestId={}, got={} — ignoring",
                      peer.id,
                      expected,
                      hasId.requestId
                    )
                    Behaviors.same
                  case _ =>
                    val elapsed = timeTakenSoFar()
                    cleanup()
                    replyTo ! ResponseReceived(requestId, peer, responseMsg, elapsed)
                    Behaviors.stopped
              case _ =>
                Behaviors.same

          case TimeoutCmd =>
            val elapsed = timeTakenSoFar()
            ctx.log.warn(
              "PEER_REQUEST_TIMEOUT: peer={}, reqType={}, elapsed={}ms (timeout={}ms)",
              peer.id,
              requestMsg.getClass.getSimpleName,
              Long.box(elapsed),
              Long.box(responseTimeout.toMillis)
            )
            cleanup()
            replyTo ! RequestFailed(requestId, peer, "request timeout")
            Behaviors.stopped

          case PeerLeftCmd(peerId) if peerId == peer.id =>
            val elapsed = timeTakenSoFar()
            ctx.log.warn(
              "PEER_REQUEST_DISCONNECTED: peer={}, reqType={}, elapsed={}ms - connection closed before response",
              peer.id,
              requestMsg.getClass.getSimpleName,
              Long.box(elapsed)
            )
            cleanup()
            replyTo ! RequestFailed(requestId, peer, "connection closed")
            Behaviors.stopped

          case PeerLeftCmd(_) =>
            Behaviors.same
        }
      }
    }
