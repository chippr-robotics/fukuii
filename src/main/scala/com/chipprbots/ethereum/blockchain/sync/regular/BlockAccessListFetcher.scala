package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestEth71PeerExcluding
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BlacklistPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.PeersClient.ResponseMessage
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** Fetches EIP-7928 block access lists from eth/71 peers (EIP-8159 `GetBlockAccessLists`) for a batch about to be
  * imported by regular sync, so that `BalPrefetcher` can warm state before execution during catch-up as it already does
  * on the Engine API path.
  *
  * A list is only ever a prefetch HINT. Nothing here can fail an import or delay it by more than [[requestTimeout]] per
  * round: no eth/71 peer, a timeout, an error or an unavailable entry all yield fewer (or no) lists, and execution
  * still builds and validates its own. Every list handed on hashes to the header's `blockAccessListHash` (EIP-8159
  * "Validation"); a peer that serves one that does not is blacklisted and its response is discarded.
  *
  * Nothing is requested for a block whose header carries no `blockAccessListHash`, so ETC and pre-Amsterdam ETH batches
  * send no message at all.
  */
class BlockAccessListFetcher(
    peersClient: ActorRef[PeersClient.Command],
    requestTimeout: FiniteDuration = BlockAccessListFetcher.RequestTimeout,
    now: () => Long = () => System.currentTimeMillis()
)(using scheduler: Scheduler):
  import BlockAccessListFetcher.*

  private val log: Logger = LoggerFactory.getLogger(classOf[BlockAccessListFetcher])

  // After a round that got nothing (no capable peer, timeout) skip fetching for a while, so a node whose peers do not
  // serve lists pays no per-batch delay.
  @volatile private var suppressedUntilMs: Long = 0L

  /** The validated lists for `blocks` that a peer served, by block hash. Never fails. */
  def fetch(blocks: Seq[Block]): IO[Map[ByteString, BlockAccessList]] =
    val wanted = blocks.filter(_.header.blockAccessListHash.isDefined).toList
    if wanted.isEmpty || now() < suppressedUntilMs then IO.pure(Map.empty)
    else
      rounds(wanted, Set.empty, Map.empty, MaxRounds).handleError { error =>
        log.debug("BAL_FETCH: failed, importing without lists: {}", error.getMessage)
        Map.empty
      }

  private def rounds(
      remaining: List[Block],
      triedPeers: Set[PeerId],
      collected: Map[ByteString, BlockAccessList],
      roundsLeft: Int
  ): IO[Map[ByteString, BlockAccessList]] =
    if remaining.isEmpty || roundsLeft == 0 then IO.pure(collected)
    else
      val requested = remaining.take(MaxHashesPerRequest)
      val hashes = requested.map(_.header.hash.value)
      val request = ETHPackets.GetBlockAccessLists(ETHPackets.nextRequestId, hashes)
      val builder = Request.create(request, BestEth71PeerExcluding(triedPeers))
      given Timeout = Timeout(requestTimeout)
      IO.fromFuture(IO(peersClient.ask[ResponseMessage](replyTo => builder(replyTo)))).attempt.flatMap {
        case Right(PeersClient.Response(peer, ETHPackets.BlockAccessLists(_, entries))) =>
          val (accepted, misbehaved) = accept(requested, entries)
          misbehaved.foreach { reason =>
            log.warn("BAL_FETCH: peer {} served an invalid list: {}", peer.id, reason)
            peersClient ! BlacklistPeer(peer.id, BlacklistReason.InvalidBlockAccessList(reason))
          }
          val got = collected ++ accepted
          // A short answer is the 2 MiB soft limit cutting the tail: ask again for what follows. An answer that
          // served nothing new, or came from a peer that misbehaved, ends the fetch.
          val consumed = if misbehaved.isDefined then 0 else entries.size.min(requested.size)
          if consumed == 0 then IO.pure(got)
          else rounds(remaining.drop(consumed), triedPeers, got, roundsLeft - 1)
        case Right(PeersClient.Response(_, other)) =>
          log.debug("BAL_FETCH: unexpected response {}", other.getClass.getSimpleName)
          IO.pure(collected)
        case Right(PeersClient.NoSuitablePeer) =>
          suppress("no eth/71 peer")
          IO.pure(collected)
        case Right(PeersClient.RequestFailed(peer, reason)) =>
          // Not a blacklisting offence: a peer that is slow at lists is still good for bodies.
          log.debug("BAL_FETCH: request to {} failed: {}", peer.id, reason)
          suppress("request failed")
          IO.pure(collected)
        case Left(error) =>
          log.debug("BAL_FETCH: no answer within {}: {}", requestTimeout, error.getMessage)
          suppress("timeout")
          IO.pure(collected)
      }

  private def suppress(why: String): Unit =
    suppressedUntilMs = now() + SuppressionMs
    log.debug("BAL_FETCH: pausing list requests for {} ms ({})", SuppressionMs, why)

  /** Matches `entries` to `requested` by position and keeps those that validate. Returns the accepted lists and, when
    * the peer misbehaved, why (the first offence ends processing of that response).
    */
  private def accept(
      requested: List[Block],
      entries: Seq[RLPEncodeable]
  ): (Map[ByteString, BlockAccessList], Option[String]) =
    if entries.size > requested.size then
      (Map.empty, Some(s"${entries.size} entries for ${requested.size} hashes requested"))
    else
      val pairs = requested.zip(entries)
      pairs.foldLeft((Map.empty[ByteString, BlockAccessList], Option.empty[String])) {
        case (acc @ (_, Some(_)), _) => acc
        case ((ok, None), (block, entry)) =>
          entry match
            case RLPValue(bytes) if bytes.isEmpty => (ok, None) // unavailable: "0x80", not an offence
            case RLPValue(_)                      => (ok, Some("entry is neither a list nor the empty string"))
            case list: RLPList =>
              val encoded = rlp.encode(list)
              val expected = block.header.blockAccessListHash.getOrElse(ByteString.empty)
              if ByteString(kec256(encoded)) != expected then
                (ok, Some(s"hash mismatch for block ${block.header.number.value}"))
              else
                BlockAccessList
                  .decode(ByteString(encoded))
                  .flatMap(bal =>
                    bal.validateFor(block.body.transactionList.size, block.header.gasLimit.value).map(_ => bal)
                  ) match
                  // The header vouches for these bytes, so a list that does not decode or fit is no peer's fault:
                  // drop it and let execution (which checks its own) decide.
                  case Left(why) =>
                    log.debug("BAL_FETCH: dropping list for block {}: {}", block.header.number.value, why)
                    (ok, None)
                  case Right(bal) => (ok + (block.header.hash.value -> bal), None)
            case _ => (ok, Some("unexpected RLP shape"))
      }

object BlockAccessListFetcher:
  /** Bound on one request: a hint is not worth waiting for. */
  val RequestTimeout: FiniteDuration = 3.seconds

  /** Hashes per request. geth serves at most 1,024 (`maxBALsServe`) and stops at a 2 MiB soft limit; fukuii's host
    * matches. A smaller ask keeps responses near the soft limit.
    */
  val MaxHashesPerRequest: Int = 128

  /** Round trips per batch: a response cut by the soft limit is followed up this many times. */
  val MaxRounds: Int = 4

  /** How long to stay quiet after a round that got no answer. */
  val SuppressionMs: Long = 30_000L
