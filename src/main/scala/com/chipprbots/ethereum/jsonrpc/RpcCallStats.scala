package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.metrics.LatencyHistogram

/** What the JSON-RPC servers were asked, by method and by transport, kept in process for the `--tui` dashboard's RPC
  * view (spec 029 FR-038, FR-039): the calls, the failures, the latencies of both, and when each method was last
  * called.
  *
  * It observes and decides nothing (FR-042). [[record]] and [[rateLimited]] are total: they take no lock, block on
  * nothing and cannot throw, so a call's answer, status and order are what they are without them. The Prometheus series
  * of `JsonRpcControllerMetrics` are untouched; this adds none.
  *
  * A plain class with no registry behind it: making one reads no configuration and registers nothing, so the node makes
  * its own at start-up and no request is the first to touch it.
  *
  * @param maxMethods
  *   the most method names kept apart; a call to a further name is counted under [[RpcCallStats.Other]]
  */
final class RpcCallStats(
    maxMethods: Int = RpcCallStats.MaxMethods,
    clock: () => Long = () => System.currentTimeMillis()
):
  import RpcCallStats.*

  final private class Counted:
    val calls = new LongAdder
    val errors = new LongAdder
    val latency = new LatencyHistogram
    val lastAt = new AtomicLong(0L)

    def add(nanos: Long, failed: Boolean, at: Long): Unit =
      calls.increment()
      if failed then errors.increment()
      latency.record(nanos)
      // The latest of the calls that ended, whichever thread notes its own last.
      lastAt.accumulateAndGet(at, (a, b) => Math.max(a, b))

  private val methods = new ConcurrentHashMap[String, Counted]()
  private val slots = new AtomicInteger(0)
  private val other = new Counted
  private val notFound = new Counted
  // The transports are few and known: made here, so a call never adds one.
  private val transports: Map[String, Counted] = Transports.map(_ -> new Counted).toMap
  private val refused = new LongAdder
  // 0: its server has not said; 1: bound and listening; 2: could not bind, or closed.
  private val bound: Map[String, AtomicInteger] = Transports.map(_ -> new AtomicInteger(0)).toMap

  /** One call that ended: answered, answered with an error, or failed with an exception.
    *
    * @param transport
    *   one of [[RpcCallStats.Transports]]; any other name counts under [[RpcCallStats.OtherTransport]]
    * @param method
    *   the method asked for
    * @param known
    *   whether the node has such a method. One it does not have is counted under [[RpcCallStats.NotFound]], whatever
    *   its name: a client that asks for names that do not exist takes none of the places of those that do
    * @param failed
    *   the answer was an error, or there was none
    */
  def record(transport: String, method: String, known: Boolean, nanos: Long, failed: Boolean): Unit =
    try
      val at = clock()
      (if known then slot(method) else notFound).add(nanos, failed, at)
      transports.getOrElse(transport, transports(OtherTransport)).add(nanos, failed, at)
    catch case NonFatal(_) => ()

  /** A transport's server bound its address, failed to, or closed: written where the server starts and stops, never on
    * a request's path.
    */
  def listening(transport: String, isListening: Boolean): Unit =
    try bound.get(transport).foreach(_.set(if isListening then 1 else 2))
    catch case NonFatal(_) => ()

  /** A request refused by the rate limit, before it reached any method. */
  def rateLimited(): Unit =
    try refused.increment()
    catch case NonFatal(_) => ()

  private def slot(method: String): Counted =
    // A name is a key here: bounded in length as in number.
    val name = if method.length > MaxNameChars then method.substring(0, MaxNameChars) else method
    val existing = methods.get(name)
    if existing != null then existing
    else if slots.get() >= maxMethods then other
    else if slots.incrementAndGet() > maxMethods then
      // Another thread took the last place between the check and the claim.
      slots.decrementAndGet()
      other
    else
      val fresh = new Counted
      val raced = methods.putIfAbsent(name, fresh)
      if raced == null then fresh
      else
        slots.decrementAndGet()
        raced

  private def of(name: String, c: Counted): Counts =
    Counts(name, c.calls.sum(), c.errors.sum(), c.latency.snapshot, c.lastAt.get)

  /** Everything counted, at this instant: the methods called, most called first, then those counted under
    * [[RpcCallStats.NotFound]] and [[RpcCallStats.Other]] when any were.
    */
  def snapshot: Snapshot =
    val named = methods.asScala.iterator.map((name, c) => of(name, c)).toVector.sortBy(m => (-m.calls, m.name))
    val rest = Vector(of(NotFound, notFound), of(Other, other)).filter(_.calls > 0)
    val listens = bound.flatMap((name, state) =>
      state.get match
        case 1 => Some(name -> true)
        case 2 => Some(name -> false)
        case _ => None
    )
    Snapshot(named ++ rest, Transports.map(t => of(t, transports(t))).toVector, refused.sum(), listens)

object RpcCallStats:

  /** The most method names kept apart, and the longest name kept whole. */
  val MaxMethods: Int = 256
  val MaxNameChars: Int = 64

  /** The names calls are counted under when not under their own: a method the node does not have, and a method past
    * [[MaxMethods]].
    */
  val NotFound: String = "(no such method)"
  val Other: String = "(other methods)"

  /** The transports: HTTP, WebSocket, IPC, the Engine API port's `eth_`, `net_` and `web3_` pass-through, and a
    * controller no transport was named for.
    */
  val Http: String = "http"
  val Ws: String = "ws"
  val Ipc: String = "ipc"
  val Engine: String = "engine"
  val OtherTransport: String = "other"
  val Transports: Seq[String] = Seq(Http, Ws, Ipc, Engine, OtherTransport)

  /** The node's own. Made when the node starts (`StdNode`), not by the first request. */
  val Node: RpcCallStats = new RpcCallStats

  /** A method's, or a transport's, calls.
    *
    * @param errors
    *   those answered with an error, or not answered for an exception
    * @param latency
    *   of every call, failed ones too
    * @param lastAtMillis
    *   when the last one ended, in epoch milliseconds; 0 with none
    */
  final case class Counts(
      name: String,
      calls: Long,
      errors: Long,
      latency: LatencyHistogram.Snapshot,
      lastAtMillis: Long
  )

  /** @param rateLimited
    *   requests the rate limit refused
    * @param listening
    *   by transport, whether its server is bound: absent for a server that has not been started
    */
  final case class Snapshot(
      methods: Vector[Counts],
      transports: Vector[Counts],
      rateLimited: Long,
      listening: Map[String, Boolean] = Map.empty
  )
