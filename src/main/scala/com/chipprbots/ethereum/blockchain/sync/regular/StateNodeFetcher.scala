package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime
import cats.syntax.either._

import scala.util.Failure
import scala.util.Success

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient._
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchedStateNode
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.utils.Config.SyncConfig

class StateNodeFetcher(
    val peersClient: ClassicActorRef,
    val syncConfig: SyncConfig,
    val supervisor: ActorRef[FetchCommand],
    context: ActorContext[StateNodeFetcher.StateNodeFetcherCommand]
) extends AbstractBehavior[StateNodeFetcher.StateNodeFetcherCommand](context)
    with FetchRequest[StateNodeFetcher.StateNodeFetcherCommand] {

  val log = context.log
  implicit val runtime: IORuntime = IORuntime.global

  import StateNodeFetcher._

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): StateNodeFetcherCommand = AdaptedMessage(peer, msg)

  private var requester: Option[StateNodeRequester] = None

  override def onMessage(message: StateNodeFetcherCommand): Behavior[StateNodeFetcherCommand] =
    message match {
      case StateNodeFetcher.FetchStateNode(hash, sender) =>
        log.debug("Start fetching state node")
        requestStateNode(hash)
        requester = Some(StateNodeRequester(hash, sender))
        Behaviors.same
      case AdaptedMessage(peer, NodeData(values)) if requester.isDefined =>
        log.debug("Received state node response from peer {}", peer)

        requester
          .collect { stateNodeRequester =>
            val validatedNode = values
              .asRight[BlacklistReason]
              .ensure(BlacklistReason.EmptyStateNodeResponse)(_.nonEmpty)
              .ensure(BlacklistReason.WrongStateNodeResponse)(nodes => stateNodeRequester.hash == kec256(nodes.head))

            validatedNode match {
              case Left(err) =>
                log.debug("State node validation failed with {}", err.description)
                peersClient ! BlacklistPeer(peer.id, err)
                context.self ! StateNodeFetcher.FetchStateNode(stateNodeRequester.hash, stateNodeRequester.replyTo)
                Behaviors.same[StateNodeFetcherCommand]
              case Right(node) =>
                stateNodeRequester.replyTo ! FetchedStateNode(NodeData(node))
                requester = None
                Behaviors.same[StateNodeFetcherCommand]
            }
          }
          .getOrElse(Behaviors.same)

      case StateNodeFetcher.RetryStateNodeRequest if requester.isDefined =>
        log.debug("Something failed on a state node request, trying again")
        requester
          .collect(stateNodeRequester =>
            context.self ! StateNodeFetcher.FetchStateNode(stateNodeRequester.hash, stateNodeRequester.replyTo)
          )
        Behaviors.same
      case _ => Behaviors.unhandled
    }

  private def requestStateNode(hash: ByteString): Unit = {
    val resp = makeRequest(Request.create(GetNodeData(List(hash)), BestPeer), StateNodeFetcher.RetryStateNodeRequest)
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res) => res
      case Failure(_)   => StateNodeFetcher.RetryStateNodeRequest
    }
  }
}

object StateNodeFetcher {

  def apply(
      peersClient: ClassicActorRef,
      syncConfig: SyncConfig,
      supervisor: ActorRef[FetchCommand]
  ): Behavior[StateNodeFetcherCommand] =
    Behaviors.setup(context => new StateNodeFetcher(peersClient, syncConfig, supervisor, context))

  sealed trait StateNodeFetcherCommand
  final case class FetchStateNode(hash: ByteString, originalSender: ClassicActorRef) extends StateNodeFetcherCommand
  case object RetryStateNodeRequest extends StateNodeFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends StateNodeFetcherCommand

  final case class StateNodeRequester(hash: ByteString, replyTo: ClassicActorRef)
}
