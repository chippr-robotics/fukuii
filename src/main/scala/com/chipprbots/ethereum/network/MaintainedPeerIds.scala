package com.chipprbots.ethereum.network

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MaintainedPeersChanged
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MaintainedPeersClassifier

/** The node ids of the peers [[PeerManagerActor]] maintains (static-nodes.json and `admin_addPeer`, less
  * `admin_removePeer`), as the peer event bus last said them (B-453). Read from any thread: the snap-peer precondition
  * reads it at each count, so a peer the operator adds while the node waits for snap peers is counted as declared.
  *
  * Empty until the first change is published after the subscription; the files the precondition reads at the start
  * cover the static nodes until then.
  */
final class MaintainedPeerIds:
  private val ids = new AtomicReference(Set.empty[ByteString])

  def current: Set[ByteString] = ids.get

  private[network] def set(nodeIds: Set[ByteString]): Unit = ids.set(nodeIds)

object MaintainedPeerIds:

  /** Subscribes to [[MaintainedPeersChanged]] on `peerEventBus` and keeps `ids` to the last set published. The bus keys
    * node ids by lowercase hex; one that does not decode, or is empty, is left out.
    *
    * @param onSubscribed
    *   called once the subscription was sent (tests wait on it before they publish)
    */
  def behavior(
      ids: MaintainedPeerIds,
      peerEventBus: ActorRef[PeerEventBusActor.Command],
      onSubscribed: () => Unit = () => ()
  ): Behavior[PeerEvent] =
    Behaviors.setup { ctx =>
      peerEventBus ! SubscribeCmd(MaintainedPeersClassifier, ctx.self)
      onSubscribed()
      Behaviors.receiveMessage {
        case MaintainedPeersChanged(nodeIds) =>
          ids.set(nodeIds.flatMap(hex => Try(ByteString(Hex.decode(hex))).toOption.filter(_.nonEmpty)))
          Behaviors.same
        case _ => Behaviors.same
      }
    }
