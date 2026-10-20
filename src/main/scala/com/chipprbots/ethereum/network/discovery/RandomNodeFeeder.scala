package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.implicits.*

import scala.concurrent.duration.*

import com.chipprbots.ethereum.utils.Logger

/** The single producer of random peer candidates (task #52).
  *
  * Before this, every `GetRandomNodeInfoReq` compiled its own stream and ran its own full Kademlia lookup (alpha
  * fan-out, a bonding Ping to every neighbour, ENR fetches for the closest 16) and then kept one node. PeerManager asks
  * on every scan, on every disconnect and again on every rejected candidate, so with a low peer count and steady churn
  * the lookups ran concurrently and without bound — on Ephemery they kept ~2.6 cores busy signing our own requests and
  * recovering the keys of the replies.
  *
  * Now one fiber runs lookups back to back and offers the results to a bounded buffer that the requests drain, like
  * go-ethereum's `lookupIterator` feeding its dialer (p2p/discover/lookup.go): a lookup runs only when there is room in
  * the buffer, so at most one is in flight, and when lookups stop turning up nodes we have not seen recently the pause
  * between them doubles up to `maxDelay`.
  */
object RandomNodeFeeder extends Logger:

  /** Pause after a productive lookup — the spacing the old per-request `metered(2.seconds)` intended. */
  val MinDelay: FiniteDuration = 2.seconds

  /** Ceiling of the backoff when lookups keep returning only nodes we already know. */
  val MaxDelay: FiniteDuration = 64.seconds

  /** A node offered within this window does not count as new, so re-finding it does not reset the backoff. */
  val SeenTtl: FiniteDuration = 30.minutes

  /** Bound on the remembered node IDs; the oldest are forgotten first. */
  val MaxSeen: Int = 4096

  /** The pause before the next lookup: back to `min` after a lookup that found a node not seen recently, otherwise the
    * current pause doubled, capped at `max`.
    */
  def nextDelay(
      current: FiniteDuration,
      foundUnseen: Boolean,
      min: FiniteDuration = MinDelay,
      max: FiniteDuration = MaxDelay
  ): FiniteDuration =
    if foundUnseen then min
    else (current * 2).max(min).min(max)

  /** Drop entries older than `ttl` and keep at most `maxSize` of the most recent. */
  private[discovery] def pruneSeen(
      seen: Map[ByteString, Long],
      nowMillis: Long,
      ttl: FiniteDuration = SeenTtl,
      maxSize: Int = MaxSeen
  ): Map[ByteString, Long] =
    val live = seen.filter { case (_, at) => nowMillis - at < ttl.toMillis }
    if live.size <= maxSize then live
    else live.toSeq.sortBy { case (_, at) => -at }.take(maxSize).toMap

  /** Run lookups forever, offering every node found. `offer` should back-pressure (a bounded queue's `offer`) so that
    * no lookup starts while nobody consumes. A failed lookup counts as finding nothing new. `sleep` is injectable so
    * tests can observe the pauses without waiting for them.
    */
  def run(
      lookup: IO[Set[Node]],
      offer: Node => IO[Unit],
      minDelay: FiniteDuration = MinDelay,
      maxDelay: FiniteDuration = MaxDelay,
      sleep: FiniteDuration => IO[Unit] = IO.sleep
  ): IO[Unit] =
    // One round: look up, offer, and work out the next pause and the updated seen-set. The recursion below sits in
    // tail position of a flatMap so the endless loop does not accumulate continuations.
    def round(delay: FiniteDuration, seen: Map[ByteString, Long]): IO[(FiniteDuration, Map[ByteString, Long])] =
      for
        nodes <- lookup.handleErrorWith { ex =>
          IO(log.warn(s"Random-node lookup failed: ${ex.getMessage}")).as(Set.empty[Node])
        }
        now <- IO.realTime.map(_.toMillis)
        live = pruneSeen(seen, now)
        unseen = nodes.count(n => !live.contains(n.id))
        _ <- nodes.toList.traverse_(offer)
        next = nextDelay(delay, unseen > 0, minDelay, maxDelay)
        _ <- IO(
          log.debug(s"Random-node lookup found ${nodes.size} nodes ($unseen not seen recently); next lookup in $next")
        )
      yield (next, pruneSeen(live ++ nodes.iterator.map(_.id -> now), now))

    def loop(delay: FiniteDuration, seen: Map[ByteString, Long]): IO[Unit] =
      round(delay, seen).flatMap { case (next, nextSeen) => sleep(next) >> loop(next, nextSeen) }

    loop(minDelay, Map.empty)

  /** [[run]] that cannot die quietly. The loop is endless and already absorbs failed lookups, so any error reaching
    * here is a bug (or a failing `offer`); left alone it would end the fiber and the node would silently stop receiving
    * peer candidates. Log it loudly and start again after `minDelay`. Cancellation (service release) still stops it.
    */
  def supervised(
      lookup: IO[Set[Node]],
      offer: Node => IO[Unit],
      minDelay: FiniteDuration = MinDelay,
      maxDelay: FiniteDuration = MaxDelay,
      sleep: FiniteDuration => IO[Unit] = IO.sleep
  ): IO[Unit] =
    def restartAfter(reason: String): IO[Unit] =
      IO(log.error(s"Random-node feeder stopped unexpectedly ($reason); restarting in $minDelay")) >>
        sleep(minDelay) >> supervised(lookup, offer, minDelay, maxDelay, sleep)
    run(lookup, offer, minDelay, maxDelay, sleep).attempt.flatMap {
      case Left(ex) => restartAfter(s"failed with $ex")
      case Right(_) => restartAfter("finished")
    }
