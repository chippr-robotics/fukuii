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
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{NodeData => ETH66NodeData}
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.rlp.RLPValue
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
      case StateNodeFetcher.FetchStateNode(hash, sender, stateRoot, pathset) =>
        log.debug("Start fetching state node (snap pathset available: {})", pathset.isDefined)
        requester = Some(StateNodeRequester(hash, sender, stateRoot, pathset))
        requestStateNode(hash, stateRoot, pathset)
        Behaviors.same

      // ETH63/64/65 NodeData response (no requestId)
      case AdaptedMessage(peer, NodeData(values)) if requester.isDefined =>
        log.debug("Received ETH63 state node response from peer {}", peer)
        handleNodeDataValues(peer, values)

      // ETH66/67 NodeData response (with requestId)
      case AdaptedMessage(peer, ETH66NodeData(_, rlpValues)) if requester.isDefined =>
        log.debug("Received ETH66 state node response from peer {}", peer)
        val values = rlpValues.items.collect { case RLPValue(bytes) => ByteString(bytes) }
        handleNodeDataValues(peer, values)

      // SNAP TrieNodes response
      case AdaptedMessage(peer, TrieNodes(_, nodes)) if requester.isDefined =>
        log.debug("Received SNAP TrieNodes response from peer {} with {} nodes", peer, nodes.size)
        handleTrieNodesValues(peer, nodes)

      case StateNodeFetcher.RetryStateNodeRequest if requester.isDefined =>
        log.debug("Something failed on a state node request, trying again")
        requester.foreach { req =>
          context.self ! StateNodeFetcher.FetchStateNode(req.hash, req.replyTo, req.stateRoot, req.pathset)
        }
        Behaviors.same
      case _ => Behaviors.unhandled
    }

  private def handleNodeDataValues(peer: Peer, values: Seq[ByteString]): Behavior[StateNodeFetcherCommand] =
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
            context.self ! StateNodeFetcher.FetchStateNode(
              stateNodeRequester.hash, stateNodeRequester.replyTo,
              stateNodeRequester.stateRoot, stateNodeRequester.pathset
            )
            Behaviors.same[StateNodeFetcherCommand]
          case Right(node) =>
            stateNodeRequester.replyTo ! FetchedStateNode(NodeData(node))
            requester = None
            Behaviors.same[StateNodeFetcherCommand]
        }
      }
      .getOrElse(Behaviors.same)

  private def handleTrieNodesValues(peer: Peer, nodes: Seq[ByteString]): Behavior[StateNodeFetcherCommand] =
    requester
      .collect { stateNodeRequester =>
        if (nodes.isEmpty) {
          log.debug("SNAP TrieNodes response was empty, retrying")
          peersClient ! BlacklistPeer(peer.id, BlacklistReason.EmptyStateNodeResponse)
          context.self ! StateNodeFetcher.FetchStateNode(
            stateNodeRequester.hash, stateNodeRequester.replyTo,
            stateNodeRequester.stateRoot, stateNodeRequester.pathset
          )
          Behaviors.same[StateNodeFetcherCommand]
        } else {
          val nodeData = nodes.head
          val nodeHash = kec256(nodeData)
          if (nodeHash == stateNodeRequester.hash) {
            log.info("Successfully fetched missing state node via SNAP GetTrieNodes")
            stateNodeRequester.replyTo ! FetchedStateNode(NodeData(Seq(nodeData)))
            requester = None
            Behaviors.same[StateNodeFetcherCommand]
          } else {
            log.warn("SNAP TrieNodes hash mismatch: expected {}, got {}", stateNodeRequester.hash, nodeHash)
            peersClient ! BlacklistPeer(peer.id, BlacklistReason.WrongStateNodeResponse)
            context.self ! StateNodeFetcher.FetchStateNode(
              stateNodeRequester.hash, stateNodeRequester.replyTo,
              stateNodeRequester.stateRoot, stateNodeRequester.pathset
            )
            Behaviors.same[StateNodeFetcherCommand]
          }
        }
      }
      .getOrElse(Behaviors.same)

  private def requestStateNode(
      hash: ByteString,
      stateRoot: Option[ByteString],
      pathset: Option[Seq[ByteString]]
  ): Unit =
    (stateRoot, pathset) match {
      case (Some(root), Some(paths)) =>
        // Use SNAP GetTrieNodes — works with ETH68 peers
        log.info("Requesting missing state node via SNAP GetTrieNodes (pathset size: {})", paths.size)
        val request = GetTrieNodes(
          requestId = ETH66.nextRequestId,
          rootHash = root,
          paths = Seq(paths),
          responseBytes = BigInt(512 * 1024)
        )
        val resp = makeRequest(
          Request(request, BestSnapPeer, (msg: GetTrieNodes) => new GetTrieNodesEnc(msg)),
          StateNodeFetcher.RetryStateNodeRequest
        )
        context.pipeToSelf(resp.unsafeToFuture()) {
          case Success(res) => res
          case Failure(_)   => StateNodeFetcher.RetryStateNodeRequest
        }

      case _ =>
        // Fallback to GetNodeData (pre-ETH68 peers only)
        log.debug("Requesting missing state node via GetNodeData (no SNAP pathset available)")
        val resp = makeRequest(
          Request.create(GetNodeData(List(hash)), BestNodeDataPeer),
          StateNodeFetcher.RetryStateNodeRequest
        )
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
  final case class FetchStateNode(
      hash: ByteString,
      originalSender: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      pathset: Option[Seq[ByteString]] = None
  ) extends StateNodeFetcherCommand
  case object RetryStateNodeRequest extends StateNodeFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends StateNodeFetcherCommand

  final case class StateNodeRequester(
      hash: ByteString,
      replyTo: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      pathset: Option[Seq[ByteString]] = None
  )
}
