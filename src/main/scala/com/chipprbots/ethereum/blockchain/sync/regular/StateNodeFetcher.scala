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
import com.chipprbots.ethereum.network.p2p.messages.SNAP.ByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes.GetByteCodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes.GetTrieNodesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.ByteStringUtils
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
      case StateNodeFetcher.FetchStateNode(hash, sender, stateRoot, paths, _, isByteCode, fallbackRoot) =>
        // De-dup concurrent fetches for the same hash. BlockImporter's resolvingMissingNode
        // 30s ReceiveTimeout retries the same import, which re-fires FetchStateNode for the same
        // missing node. Without this guard, every retry spawns a parallel SNAP request and
        // overwrites the requester, multiplying load and poisoning the blacklist.
        requester match {
          case Some(existing) if existing.hash == hash =>
            log.debug(
              "FetchStateNode for in-flight hash {} (attempt {}) — updating replyTo only",
              ByteStringUtils.hash2string(hash),
              existing.attempts
            )
            requester = Some(existing.copy(replyTo = sender))
          case _ =>
            log.debug(
              "Start fetching {} {} (snap paths available: {}, fallback root available: {})",
              if (isByteCode) "bytecode" else "state node",
              ByteStringUtils.hash2string(hash),
              paths.isDefined,
              fallbackRoot.isDefined
            )
            requester = Some(
              StateNodeRequester(hash, sender, stateRoot, paths, attempts = 0, isByteCode, fallbackRoot)
            )
            requestStateNode(hash, stateRoot, paths, isByteCode)
        }
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

      // SNAP ByteCodes response
      case AdaptedMessage(peer, ByteCodes(_, codes)) if requester.isDefined =>
        log.info("Received SNAP ByteCodes response from peer {} with {} codes", peer, codes.size)
        handleByteCodesValues(peer, codes)

      case StateNodeFetcher.RetryStateNodeRequest =>
        // The peer request resolved to NoSuitablePeer / RequestFailed and FetchRequest piped this
        // fallback to us. Count it as a failed attempt and either schedule another fire or signal
        // exhaustion to BlockImporter (which has a 5-minute backoff handler for empty responses).
        requester.foreach(retryOrExhaust)
        Behaviors.same

      case StateNodeFetcher.FireRequest =>
        // Internal self-message used to fire a retry after BackoffInterval has elapsed.
        // Consumes from the in-place requester so de-dup stays consistent.
        requester.foreach { req =>
          log.debug(
            "Re-firing state node request for {} (attempt {})",
            ByteStringUtils.hash2string(req.hash),
            req.attempts
          )
          requestStateNode(req.hash, req.stateRoot, req.paths, req.isByteCode)
        }
        Behaviors.same

      case _ => Behaviors.unhandled
    }

  /** Increment attempt counter and either schedule another request or signal exhaustion to the
    * BlockImporter. Sending an empty FetchedStateNode triggers BlockImporter's 5-minute backoff
    * handler so the resolvingMissingNode → import-fail → re-fetch loop can't spin indefinitely.
    */
  private def retryOrExhaust(req: StateNodeRequester): Unit = {
    val nextAttempt = req.attempts + 1
    if (nextAttempt >= MaxStateNodeFetchRetries) {
      log.warn(
        "State node fetch for {} exhausted after {} attempts — signaling BlockImporter to back off",
        ByteStringUtils.hash2string(req.hash),
        req.attempts
      )
      req.replyTo ! FetchedStateNode(NodeData(Seq.empty))
      requester = None
    } else {
      requester = Some(req.copy(attempts = nextAttempt))
      context.scheduleOnce(BackoffInterval, context.self, StateNodeFetcher.FireRequest)
    }
  }

  private def handleNodeDataValues(peer: Peer, values: Seq[ByteString]): Behavior[StateNodeFetcherCommand] =
    requester
      .map { stateNodeRequester =>
        val validatedNode = values
          .asRight[BlacklistReason]
          .ensure(BlacklistReason.EmptyStateNodeResponse)(_.nonEmpty)
          .ensure(BlacklistReason.WrongStateNodeResponse)(nodes => stateNodeRequester.hash == kec256(nodes.head))

        validatedNode match {
          case Left(err) =>
            log.debug("State node validation failed with {}", err.description)
            peersClient ! BlacklistPeer(peer.id, err)
            retryOrExhaust(stateNodeRequester)
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
          // Empty TrieNodes from a snap peer almost always means "I don't have this root" —
          // typical when the parent stateRoot is older than the peer's ~128-block serve window.
          // Fall back to the recent canonical root we got from BlockFetcher: trie nodes are
          // content-addressed, so the same nibble path against a recent root still leads to the
          // same node provided the account's trie subtree hasn't been touched. Don't blacklist
          // here — the peer answered correctly that it doesn't have this stale root.
          maybeSwitchToFallbackRoot(stateNodeRequester) match {
            case Some(updated) =>
              log.warn(
                "SNAP TrieNodes empty for stale root, switching to recent canonical root {}",
                updated.stateRoot.map(r => ByteStringUtils.hash2string(r.take(4))).getOrElse("<none>")
              )
              requester = Some(updated)
              requestStateNode(updated.hash, updated.stateRoot, updated.paths, updated.isByteCode)
              Behaviors.same[StateNodeFetcherCommand]
            case None =>
              log.warn("SNAP TrieNodes response was empty, retrying")
              peersClient ! BlacklistPeer(peer.id, BlacklistReason.EmptyStateNodeResponse)
              retryOrExhaust(stateNodeRequester)
              Behaviors.same[StateNodeFetcherCommand]
          }
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
              // Wrong-hash response. If we haven't tried the fallback root yet, switch — the
              // account's subtree may have moved at the recent root. Otherwise treat as a normal
              // wrong-hash and blacklist.
              maybeSwitchToFallbackRoot(stateNodeRequester) match {
                case Some(updated) =>
                  log.warn(
                    "SNAP TrieNodes wrong-hash on parent root, switching to recent canonical root {}",
                    updated.stateRoot.map(r => ByteStringUtils.hash2string(r.take(4))).getOrElse("<none>")
                  )
                  requester = Some(updated)
                  requestStateNode(updated.hash, updated.stateRoot, updated.paths, updated.isByteCode)
                  Behaviors.same[StateNodeFetcherCommand]
                case None =>
                  log.warn("SNAP TrieNodes: got {} nodes but none matched target hash, retrying", nodes.size)
                  peersClient ! BlacklistPeer(peer.id, BlacklistReason.WrongStateNodeResponse)
                  retryOrExhaust(stateNodeRequester)
                  Behaviors.same[StateNodeFetcherCommand]
              }
          }
        }
      }
      .getOrElse(Behaviors.same)

  /** If the requester still has an unused fallback canonical root, swap it in as the primary
    * stateRoot and clear the fallback slot (so we only switch once). Returns None when no
    * fallback is available or it has already been consumed.
    */
  private def maybeSwitchToFallbackRoot(req: StateNodeRequester): Option[StateNodeRequester] =
    req.fallbackStateRoot
      .filter(fallback => !req.stateRoot.contains(fallback))
      .map { fallback =>
        req.copy(stateRoot = Some(fallback), fallbackStateRoot = None)
      }

  private def handleByteCodesValues(peer: Peer, codes: Seq[ByteString]): Behavior[StateNodeFetcherCommand] =
    requester
      .collect { stateNodeRequester =>
        if (codes.isEmpty) {
          // Per SNAP/1, an empty ByteCodes response means the server doesn't have any of the
          // requested codes — equivalent to a stateless response. Retry against another peer.
          log.warn("SNAP ByteCodes response was empty, retrying")
          peersClient ! BlacklistPeer(peer.id, BlacklistReason.EmptyStateNodeResponse)
          retryOrExhaust(stateNodeRequester)
          Behaviors.same[StateNodeFetcherCommand]
        } else {
          // Codes are returned in the same order as requested hashes; we only request one hash
          // per recovery, so verify the first code's keccak matches the target codeHash.
          val matchingCode = codes.find(c => kec256(c) == stateNodeRequester.hash)
          matchingCode match {
            case Some(code) =>
              log.info(
                "Successfully fetched missing bytecode via SNAP GetByteCodes ({} codes in response)",
                codes.size
              )
              stateNodeRequester.replyTo ! FetchedStateNode(NodeData(Seq(code)))
              requester = None
              Behaviors.same[StateNodeFetcherCommand]
            case None =>
              log.warn("SNAP ByteCodes: got {} codes but none matched target codeHash, retrying", codes.size)
              peersClient ! BlacklistPeer(peer.id, BlacklistReason.WrongStateNodeResponse)
              retryOrExhaust(stateNodeRequester)
              Behaviors.same[StateNodeFetcherCommand]
          }
        }
      }
      .getOrElse(Behaviors.same)

  /** Route the request to the right wire protocol:
    *   - bytecode → SNAP GetByteCodes (every snap-capable peer serves these; works on ETH68+)
    *   - trie node with paths → SNAP GetTrieNodes (preserved root + HP-encoded paths)
    *   - trie node without paths → legacy GetNodeData (ETH63-67 only; mostly dead on modern nets)
    */
  private def requestStateNode(
      hash: ByteString,
      stateRoot: Option[ByteString],
      paths: Option[Seq[Seq[ByteString]]],
      isByteCode: Boolean
  ): Unit =
    if (isByteCode) {
      sendGetByteCodes(hash)
    } else {
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

  /** Fetch a single contract bytecode by codeHash via SNAP GetByteCodes. Used when post-fast-sync
    * regular sync hits a "Block has invalid gas used" error and findMissingContractCode identifies
    * a missing bytecode. SNAP's GetByteCodes is served by every snap-capable peer regardless of
    * their ETH version, so this works even when the entire peer set is ETH68+ (no GetNodeData).
    */
  private def sendGetByteCodes(codeHash: ByteString): Unit = {
    log.info(
      "Requesting missing bytecode via SNAP GetByteCodes (codeHash={})",
      ByteStringUtils.hash2string(codeHash)
    )
    val request = GetByteCodes(
      requestId = ETH66.nextRequestId,
      hashes = Seq(codeHash),
      responseBytes = BigInt(512 * 1024)
    )
    val resp = makeRequest(
      Request(request, BestSnapPeer, (msg: GetByteCodes) => new GetByteCodesEnc(msg)),
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

  // Bounded retry budget: 10 attempts × 5s backoff ≈ 50s per missing node before signaling
  // exhaustion to BlockImporter. Without a bound, the resolvingMissingNode loop could spin forever
  // when peers keep returning empty TrieNodes (Bug 30 — peers outside the SNAP serve window
  // disconnect on every GetTrieNodes request).
  val MaxStateNodeFetchRetries: Int = 10
  val BackoffInterval: FiniteDuration = 5.seconds

  sealed trait StateNodeFetcherCommand
  final case class FetchStateNode(
      hash: ByteString,
      originalSender: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      paths: Option[Seq[Seq[ByteString]]] = None,
      networkHead: BigInt = BigInt(0),
      isByteCode: Boolean = false,
      // Recent canonical stateRoot from BlockFetcher's tracked headers. Used as a one-shot
      // fallback when the primary stateRoot is too old for any SNAP peer to serve.
      fallbackStateRoot: Option[ByteString] = None
  ) extends StateNodeFetcherCommand
  case object RetryStateNodeRequest extends StateNodeFetcherCommand
  case object FireRequest extends StateNodeFetcherCommand
  final private case class AdaptedMessage[T <: Message](peer: Peer, msg: T) extends StateNodeFetcherCommand

  final case class StateNodeRequester(
      hash: ByteString,
      replyTo: ClassicActorRef,
      stateRoot: Option[ByteString] = None,
      paths: Option[Seq[Seq[ByteString]]] = None,
      attempts: Int = 0,
      isByteCode: Boolean = false,
      // One-shot fallback root: cleared the moment we switch to it, so we only attempt the
      // root-swap once per recovery (no flip-flop between primary and fallback).
      fallbackStateRoot: Option[ByteString] = None
  )
}
