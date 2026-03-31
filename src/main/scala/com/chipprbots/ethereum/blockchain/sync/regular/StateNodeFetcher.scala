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

  // Retry tracking for escape valve (Besu-inspired: MAX_RETRIES=4, we use configurable limits)
  private var snapRetryCount: Int = 0
  private var totalRetryCount: Int = 0
  private var emptyResponseCount: Long = 0

  override def onMessage(message: StateNodeFetcherCommand): Behavior[StateNodeFetcherCommand] =
    message match {
      case StateNodeFetcher.FetchStateNode(hash, sender, stateRoot, paths, _) =>
        val isRetry = requester.exists(_.hash == hash)
        if (!isRetry) {
          // New fetch request — reset retry counters
          snapRetryCount = 0
          totalRetryCount = 0
          emptyResponseCount = 0
        }
        log.debug("Start fetching state node (snap paths available: {}, retry: {})", paths.isDefined, isRetry)
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
        requester.foreach { req =>
          context.scheduleOnce(5.seconds, context.self,
            StateNodeFetcher.FetchStateNode(req.hash, req.replyTo, req.stateRoot, req.paths))
        }
        Behaviors.same
      case _ => Behaviors.unhandled
    }

  private def retryOrFallback(req: StateNodeRequester): Unit = {
    totalRetryCount += 1

    // Check if we've exhausted all retries — signal failure to supervisor
    if (totalRetryCount >= syncConfig.maxStateNodeTotalRetries) {
      val hashHex = req.hash.take(4).toArray.map("%02x".format(_)).mkString
      log.error(
        "State node fetch FAILED after {} total retries ({} snap, {} fallback) for hash={}. " +
          "Signaling failure to supervisor.",
        totalRetryCount, snapRetryCount, totalRetryCount - snapRetryCount, hashHex
      )
      req.replyTo ! BlockFetcher.StateNodeFetchFailed(req.hash)
      requester = None
      return
    }

    // After maxSnapRetries empty SNAP responses, fall back to GetNodeData (hash-only, no paths)
    // This handles the case where the SNAP root is stale — GetNodeData uses hash-based lookup
    // which works regardless of the current state root.
    if (snapRetryCount >= syncConfig.maxStateNodeSnapRetries && req.paths.isDefined) {
      val hashHex = req.hash.take(4).toArray.map("%02x".format(_)).mkString
      if (snapRetryCount == syncConfig.maxStateNodeSnapRetries) {
        log.warn(
          "SNAP GetTrieNodes returned empty {} times for hash={}. " +
            "Falling back to GetNodeData (hash-based lookup, no SNAP paths).",
          snapRetryCount, hashHex
        )
      }
      // Retry without paths — forces GetNodeData path in requestStateNode()
      context.scheduleOnce(5.seconds, context.self,
        StateNodeFetcher.FetchStateNode(req.hash, req.replyTo, req.stateRoot, paths = None))
    } else {
      // Normal retry with original parameters
      context.scheduleOnce(5.seconds, context.self,
        StateNodeFetcher.FetchStateNode(req.hash, req.replyTo, req.stateRoot, req.paths))
    }
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
            retryOrFallback(stateNodeRequester)
            Behaviors.same[StateNodeFetcherCommand]
          case Right(node) =>
            if (totalRetryCount > 0) {
              log.info("Successfully fetched state node after {} retries (via GetNodeData)", totalRetryCount)
            }
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
          emptyResponseCount += 1
          snapRetryCount += 1
          // Rate-limit log noise: log first, then every 100th occurrence
          if (emptyResponseCount == 1 || emptyResponseCount % 100 == 0) {
            log.warn(
              "SNAP TrieNodes response empty ({} total, snap retry {}/{}), root={}",
              emptyResponseCount, snapRetryCount, syncConfig.maxStateNodeSnapRetries,
              stateNodeRequester.stateRoot.map(_.take(4).toArray.map("%02x".format(_)).mkString).getOrElse("none")
            )
          }
          peersClient ! BlacklistPeer(peer.id, BlacklistReason.EmptyStateNodeResponse)
          retryOrFallback(stateNodeRequester)
          Behaviors.same[StateNodeFetcherCommand]
        } else {
          // Multi-depth request: scan all returned nodes for one matching the target hash.
          val matchingNode = nodes.find(n => kec256(n) == stateNodeRequester.hash)
          matchingNode match {
            case Some(nodeData) =>
              log.info("Successfully fetched missing state node via SNAP GetTrieNodes ({} nodes in response)", nodes.size)
              stateNodeRequester.replyTo ! FetchedStateNode(NodeData(Seq(nodeData)))
              requester = None
              Behaviors.same[StateNodeFetcherCommand]
            case None =>
              log.warn("SNAP TrieNodes: got {} nodes but none matched target hash, retrying", nodes.size)
              peersClient ! BlacklistPeer(peer.id, BlacklistReason.WrongStateNodeResponse)
              retryOrFallback(stateNodeRequester)
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
    log.info("Requesting missing state node via SNAP GetTrieNodes ({} path groups, root={})",
      pathGroups.size, root.take(4).toArray.map("%02x".format(_)).mkString)
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
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends StateNodeFetcherCommand

  final case class StateNodeRequester(
      hash: ByteString,
      replyTo: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      paths: Option[Seq[Seq[ByteString]]] = None
  )
}
