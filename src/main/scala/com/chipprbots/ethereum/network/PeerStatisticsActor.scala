package com.chipprbots.ethereum.network

import java.time.Clock

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.network.PeerEventBusActor.*
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Codes

object PeerStatisticsActor:

  /** Behavior factory for the Typed PeerStatisticsActor.
    *
    * The Classic `var maybeStats` is replaced by state threaded through the recursive [[active]] behavior.
    * Subscriptions to the (still Classic) PeerEventBus are wired through a message adapter that lifts Classic
    * [[PeerEvent]] messages into the typed [[Command]] ADT ([[PeerMessageReceived]] / [[PeerGone]]); the adapter's
    * underlying Classic ref is what we hand to the bus via `Subscribe`.
    */
  def apply(peerEventBus: TypedActorRef[PeerEventBusActor.Command], slotDuration: FiniteDuration, slotCount: Int)(
      implicit clock: Clock
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      // Lift Classic PeerEventBus notifications into the typed Command ADT.
      val eventAdapter: TypedActorRef[PeerEvent] = ctx.messageAdapter[PeerEvent] {
        case PeerEvent.MessageFromPeer(msg, peerId) => PeerMessageReceived(msg, peerId)
        case PeerEvent.PeerDisconnected(peerId)     => PeerGone(peerId)
        case _                                      => PeerEventIgnored
      }

      // Subscribe to messages received from handshaked peers to maintain stats.
      peerEventBus ! SubscribeCmd(MessageSubscriptionClassifier, eventAdapter)
      // Removing peers is an optimisation to free space, but eventually the stats would be overwritten anyway.
      peerEventBus ! SubscribeCmd(
        SubscriptionClassifier.PeerDisconnectedClassifier(PeerSelector.AllPeers),
        eventAdapter
      )

      active(TimeSlotStats[PeerId, PeerStat](slotDuration, slotCount))
    }

  private def active(maybeStats: Option[TimeSlotStats[PeerId, PeerStat]])(implicit
      clock: Clock
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case PeerMessageReceived(msg, peerId) =>
        active(maybeStats.map(_.add(peerId, observe(msg))))

      case PeerGone(peerId) =>
        active(maybeStats.map(_.remove(peerId)))

      case GetStatsForAll(window, replyTo) =>
        val stats = maybeStats.map(_.getAll(Some(window))).getOrElse(Map.empty)
        replyTo ! StatsForAll(stats)
        Behaviors.same

      case GetStatsForPeer(window, peerId, replyTo) =>
        val stats = maybeStats.map(_.get(peerId, Some(window))).getOrElse(PeerStat.empty)
        replyTo ! StatsForPeer(peerId, stats)
        Behaviors.same

      case PeerEventIgnored =>
        Behaviors.same
    }

  private def observe(msg: Message)(implicit clock: Clock): PeerStat =
    val now = clock.millis
    PeerStat(
      responsesReceived = if ResponseCodes(msg.code) then 1 else 0,
      requestsReceived = if RequestCodes(msg.code) then 1 else 0,
      firstSeenTimeMillis = Some(now),
      lastSeenTimeMillis = Some(now)
    )

  /** Protocol for the Typed PeerStatisticsActor. */
  sealed trait Command

  /** Internal: a `PeerEvent.MessageFromPeer` lifted from the Classic PeerEventBus via the message adapter. */
  final private[network] case class PeerMessageReceived(msg: Message, peerId: PeerId) extends Command

  /** Internal: a `PeerEvent.PeerDisconnected` lifted from the Classic PeerEventBus via the message adapter. */
  final private[network] case class PeerGone(peerId: PeerId) extends Command

  /** Internal: any other (unexpected) PeerEvent the adapter receives; dropped. */
  private case object PeerEventIgnored extends Command

  final case class GetStatsForAll(window: FiniteDuration, replyTo: TypedActorRef[StatsForAll]) extends Command
  case class StatsForAll(stats: Map[PeerId, PeerStat])
  final case class GetStatsForPeer(window: FiniteDuration, peerId: PeerId, replyTo: TypedActorRef[StatsForPeer])
      extends Command
  case class StatsForPeer(peerId: PeerId, stat: PeerStat)

  val ResponseCodes: Set[Int] = Set(
    Codes.NewBlockCode,
    Codes.NewBlockHashesCode,
    Codes.SignedTransactionsCode,
    Codes.BlockHeadersCode,
    Codes.BlockBodiesCode,
    Codes.BlockHashesFromNumberCode,
    Codes.ReceiptsCode
  )

  val RequestCodes: Set[Int] = Set(
    Codes.GetBlockHeadersCode,
    Codes.GetBlockBodiesCode,
    Codes.GetReceiptsCode
  )

  val MessageSubscriptionClassifier: SubscriptionClassifier.MessageClassifier =
    SubscriptionClassifier.MessageClassifier(
      messageCodes = RequestCodes.union(ResponseCodes),
      peerSelector = PeerSelector.AllPeers
    )
