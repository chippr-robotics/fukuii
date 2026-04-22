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

import scala.concurrent.duration._
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
      case StateNodeFetcher.FetchStateNode(hash, sender, stateRoot, paths, _) =>
        log.debug("Start fetching state node (snap paths available: {})", paths.isDefined)
        requester = Some(StateNodeRequester(hash, sender, stateRoot, paths))
        requestStateNode(hash, stateRoot, paths)
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
        log.info("Received SNAP TrieNodes response from peer {} with {} nodes", peer, nodes.size)
        handleTrieNodesValues(peer, nodes)

      case StateNodeFetcher.RetryStateNodeRequest if requester.isDefined =>
        // Backoff before retrying to prevent flooding peers with requests.
        // Without this, each failure immediately spawns a new request while old PeerRequestHandler
        // actors are still alive waiting for timeout — creating unbounded request multiplication.
        // Use InternalRetry so we re-use the existing requester (preserving snapEmptyCount/paths).
        context.scheduleOnce(5.seconds, context.self, StateNodeFetcher.InternalRetry)
        Behaviors.same

      case StateNodeFetcher.InternalRetry if requester.isDefined =>
        // Re-issue the request using current requester state (snapEmptyCount and paths preserved).
        requester.foreach(req => requestStateNode(req.hash, req.stateRoot, req.paths))
        Behaviors.same

      case _ => Behaviors.unhandled
    }

  // Schedule a retry that preserves the current requester state (including snapEmptyCount and
  // any updated paths). Must NOT schedule FetchStateNode — that rebuilds requester from scratch.
  private def retryAfterBackoff(): Unit =
    context.scheduleOnce(5.seconds, context.self, StateNodeFetcher.InternalRetry)

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
            retryAfterBackoff()
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
          val newCount = stateNodeRequester.snapEmptyCount + 1
          if (newCount >= SnapFallbackThreshold) {
            log.warn(
              "SNAP GetTrieNodes returned empty {} consecutive times for node {}, switching to GetNodeData fallback",
              newCount,
              stateNodeRequester.hash.take(4).toArray.map("%02x".format(_)).mkString
            )
            // Clear paths so next request uses GetNodeData instead of SNAP.
            // GetNodeData fetches by hash from the raw KV store — a peer may have the node
            // even if they've pruned the specific state root context that SNAP needs.
            requester = Some(stateNodeRequester.copy(snapEmptyCount = 0, paths = None))
          } else {
            log.warn("SNAP TrieNodes response was empty ({}/{}), retrying", newCount, SnapFallbackThreshold)
            requester = Some(stateNodeRequester.copy(snapEmptyCount = newCount))
          }
          peersClient ! BlacklistPeer(peer.id, BlacklistReason.EmptyStateNodeResponse)
          retryAfterBackoff()
          Behaviors.same[StateNodeFetcherCommand]
        } else {
          // Multi-depth request: scan all returned nodes for one matching the target hash.
          val matchingNode = nodes.find(n => kec256(n) == stateNodeRequester.hash)
          matchingNode match {
            case Some(nodeData) =>
              log.info(
                "Successfully fetched missing state node via SNAP GetTrieNodes ({} nodes in response)",
                nodes.size
              )
              stateNodeRequester.replyTo ! FetchedStateNode(NodeData(Seq(nodeData)))
              requester = None
              Behaviors.same[StateNodeFetcherCommand]
            case None =>
              log.warn("SNAP TrieNodes: got {} nodes but none matched target hash, retrying", nodes.size)
              peersClient ! BlacklistPeer(peer.id, BlacklistReason.WrongStateNodeResponse)
              retryAfterBackoff()
              Behaviors.same[StateNodeFetcherCommand]
          }
        }
      }
      .getOrElse(Behaviors.same)

  /** Route the request to either SNAP GetTrieNodes (if paths available) or legacy GetNodeData. */
  private def requestStateNode(
      hash: ByteString,
      stateRoot: Option[ByteString],
      paths: Option[Seq[Seq[ByteString]]]
  ): Unit =
    (stateRoot, paths) match {
      case (Some(root), Some(pathGroups)) if pathGroups.nonEmpty =>
        // Use SNAP GetTrieNodes with the SAME root the paths were computed from.
        // The paths are HP-encoded nibble prefixes from a trie walk against this root.
        // Using a different root would make paths invalid — the trie structure differs.
        sendGetTrieNodes(root, pathGroups)

      case _ =>
        // Fallback to GetNodeData (pre-ETH68 peers only)
        log.debug("Requesting missing state node via GetNodeData (no SNAP paths available)")
        val resp = makeRequest(
          Request.create(GetNodeData(List(hash)), BestNodeDataPeer),
          StateNodeFetcher.RetryStateNodeRequest
        )
        context.pipeToSelf(resp.unsafeToFuture()) {
          case Success(res) => res
          case Failure(_)   => StateNodeFetcher.RetryStateNodeRequest
        }
    }

  private def sendGetTrieNodes(root: ByteString, pathGroups: Seq[Seq[ByteString]]): Unit = {
    log.info(
      "Requesting missing state node via SNAP GetTrieNodes ({} path groups, root={})",
      pathGroups.size,
      root.take(4).toArray.map("%02x".format(_)).mkString
    )
    val request = GetTrieNodes(
      requestId = ETH66.nextRequestId,
      rootHash = root,
      paths = pathGroups,
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
      paths: Option[Seq[Seq[ByteString]]] = None,
      networkHead: BigInt = BigInt(0)
  ) extends StateNodeFetcherCommand
  case object RetryStateNodeRequest extends StateNodeFetcherCommand
  // Internal retry: re-issues request from current requester state (preserves snapEmptyCount/paths).
  // Use this instead of scheduling FetchStateNode, which rebuilds requester from scratch.
  case object InternalRetry extends StateNodeFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends StateNodeFetcherCommand

  // After this many consecutive empty SNAP TrieNodes responses, fall back to GetNodeData.
  // GetNodeData fetches by hash regardless of state root — useful when SNAP peers have pruned
  // the specific historical state root but still hold the raw node bytes in their KV store.
  val SnapFallbackThreshold: Int = 5

  final case class StateNodeRequester(
      hash: ByteString,
      replyTo: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      paths: Option[Seq[Seq[ByteString]]] = None,
      snapEmptyCount: Int = 0
  )
}
