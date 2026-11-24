package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

import scala.util.Try
import scala.util.control.NonFatal

import org.json4s.*

import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse

/** One Engine API call as the consensus client saw it end.
  *
  * @param atMs
  *   when its answer was ready, in epoch milliseconds
  * @param method
  *   the method as it was called (`engine_newPayloadV4`), or [[EngineCallLog.OtherMethod]] for one this node has not
  * @param status
  *   what the consensus client was told: a payload status (`VALID`, `INVALID`, `SYNCING`, `ACCEPTED`) for `newPayload`
  *   and `forkchoiceUpdated`, `error <code>` for an error answer, and nothing for a method that answers neither
  * @param nanos
  *   from the request being read to its answer being ready
  * @param blockNumber
  *   the payload's block number, for `newPayload` (from the request) and `getPayload` (from the answer)
  */
final case class EngineCallRecord(
    atMs: Long,
    method: String,
    status: Option[String],
    nanos: Long,
    blockNumber: Option[Long]
)

/** What the consensus client said it is: the argument of its own `engine_getClientVersionV1` call, and how many methods
  * it listed in `engine_exchangeCapabilities`. Each text is the client's own, cut to [[EngineCallLog.MaxTextChars]] and
  * stripped of control characters before it is kept.
  */
final case class ClIdentity(code: String, name: String, version: String, commit: String)

/** Why the JWT check refused a request: a fixed set, so that nothing a request carries becomes a key. */
enum AuthFailure(val label: String):
  case Missing extends AuthFailure("no bearer token")
  case Format extends AuthFailure("not a JWT")
  case Signature extends AuthFailure("wrong signature (another secret)")
  case Algorithm extends AuthFailure("unsupported algorithm")
  case IssuedAtMissing extends AuthFailure("no iat claim")
  case Expired extends AuthFailure("iat too far from now (clocks apart)")
  case Malformed extends AuthFailure("malformed token")

object AuthFailure:

  /** The reason behind the JWT check's own message, by how the message starts (`JwtAuthenticator.validateToken`). */
  def of(message: String): AuthFailure =
    if message.startsWith("Missing bearer token") then Missing
    else if message.startsWith("Invalid JWT format") then Format
    else if message.startsWith("Invalid JWT signature encoding") then Malformed
    else if message.startsWith("Invalid JWT signature") then Signature
    else if message.startsWith("Unsupported JWT algorithm") then Algorithm
    else if message.startsWith("Missing iat claim") then IssuedAtMissing
    else if message.startsWith("JWT expired") then Expired
    else Malformed

/** A payload this node built for the consensus client. */
final case class PayloadBuild(atMs: Long, blockNumber: Long, transactions: Int)

/** The Engine API as it was used, kept in process for the `--tui` dashboard's Engine Synchronization Monitor (spec 029
  * FR-040): the last [[EngineCallLog.Capacity]] calls, who the consensus client says it is, why requests failed
  * authentication, and the payloads built.
  *
  * It observes and decides nothing (FR-042). Every method here is total: no lock, no log, no registry, nothing that
  * blocks and nothing that can throw; what a consensus client sends is read with `Try` and kept only cut and cleaned.
  * `EngineApiHttpServer` calls [[observe]] from a callback of its own beside the answer, never in front of it, so an
  * answer's bytes, status, order and time are what they are without this.
  *
  * A plain class with no registry behind it, so that making one can fail on nothing: the node makes its own when it
  * starts, and no request is the first to touch it.
  *
  * @param enabled
  *   false keeps nothing: what a test compares the Engine API's answers against
  */
final class EngineCallLog(clock: () => Long = () => System.currentTimeMillis(), enabled: Boolean = true):
  import EngineCallLog.*

  private val ring = new AtomicReferenceArray[EngineCallRecord](Capacity)
  private val written = new AtomicLong(0L)
  private val identity = new AtomicReference[ClIdentity](null)
  private val capabilities = new AtomicLong(-1L)
  private val authFailures = new AtomicLongArray(AuthFailure.values.length)
  private val builds = new AtomicLong(0L)
  private val failedBuilds = new AtomicLong(0L)
  private val lastBuild = new AtomicReference[PayloadBuild](null)

  /** One call that ended. `request` is the request as it was parsed, `answer` what it was answered with; an `eth_`,
    * `net_` or `web3_` call through the Engine port is the JSON-RPC server's to count, and is left out.
    */
  def observe(method: String, sent: JValue, answer: Try[JsonRpcResponse], startedNanos: Long): Unit =
    try
      if enabled && !isPassThrough(method) then
        // A request is looked into only where it is an object: on anything else (an element of a batch that is
        // itself a list) a field lookup would go through its elements.
        val request: JValue = sent match
          case o: JObject => o
          case _          => JNothing
        val name = if Methods.contains(method) then method else OtherMethod
        val response = answer.toOption
        val status = response.flatMap(statusOf(name, _)).orElse(Option.when(answer.isFailure)("failed"))
        val block = blockNumberOf(name, request, response)
        // The CL's identity and capability count are recorded before the call is counted (B-423): a reader that sees
        // the new total then sees them too. Counted first, a snapshot could show the call without them.
        if response.exists(_.error.isEmpty) then
          if name == "engine_getClientVersionV1" then identityOf(request).foreach(identity.set)
          else if name == "engine_exchangeCapabilities" then
            request \ "params" match
              // The method names it listed: a list of anything else is not a list of capabilities.
              case JArray(JArray(names) :: _) if names.forall(_.isInstanceOf[JString]) =>
                capabilities.set(names.size.toLong)
              case _ => ()
        add(EngineCallRecord(clock(), name, status, System.nanoTime() - startedNanos, block))
    catch case NonFatal(_) => ()

  /** A request that never became a call: a body that was not JSON, or not a request. */
  def observeUnreadable(startedNanos: Long): Unit =
    try
      if enabled then
        add(EngineCallRecord(clock(), OtherMethod, Some("unreadable request"), System.nanoTime() - startedNanos, None))
    catch case NonFatal(_) => ()

  private def add(record: EngineCallRecord): Unit =
    // Each writer takes its own place: two calls ending at once lose neither.
    val at = written.getAndIncrement()
    ring.set((at % Capacity).toInt, record)

  /** The JWT check refused a request. */
  def authFailed(reason: AuthFailure): Unit =
    try if enabled then authFailures.incrementAndGet(reason.ordinal)
    catch case NonFatal(_) => ()

  /** A payload was built for the consensus client, at its `forkchoiceUpdated`. A payload built again with the pool of
    * the moment when `getPayload` asks for it is not counted, and may hold other transactions than this says.
    */
  def payloadBuilt(blockNumber: Long, transactions: Int): Unit =
    try
      if enabled then
        builds.incrementAndGet()
        lastBuild.set(PayloadBuild(clock(), blockNumber, transactions))
    catch case NonFatal(_) => ()

  /** A payload the consensus client asked for could not be built. */
  def payloadBuildFailed(): Unit =
    try if enabled then failedBuilds.incrementAndGet()
    catch case NonFatal(_) => ()

  /** Everything kept, at this instant: the calls oldest first. A call that ends while this reads may be missing from it
    * or stand where an older one stood; none is ever half-written.
    */
  def snapshot: Snapshot =
    val total = written.get()
    val kept = Math.min(total, Capacity.toLong).toInt
    val calls = Vector.tabulate(kept)(i => ring.get(((total - kept + i) % Capacity).toInt)).filter(_ != null)
    Snapshot(
      calls = calls.sortBy(_.atMs),
      total = total,
      identity = Option(identity.get()),
      capabilities = Option(capabilities.get()).filter(_ >= 0).map(_.toInt),
      authFailures = AuthFailure.values.iterator.map(r => r -> authFailures.get(r.ordinal)).filter(_._2 > 0).toMap,
      payloadsBuilt = builds.get(),
      payloadBuildsFailed = failedBuilds.get(),
      lastBuild = Option(lastBuild.get())
    )

object EngineCallLog:

  /** The calls kept. */
  val Capacity: Int = 256

  /** The longest text of a consensus client's kept. */
  val MaxTextChars: Int = 64

  /** The name a call to a method this node does not have is kept under: the name sent is the caller's, and is not kept.
    */
  val OtherMethod: String = "(other)"

  /** The Engine API methods this node answers (`EngineApiController.handleRequest`), each kept under its own name and
    * version: a version is never derived from a fork or a clock.
    */
  val Methods: Set[String] =
    (
      (1 to 5).map(v => s"engine_newPayloadV$v") ++
        (1 to 4).map(v => s"engine_forkchoiceUpdatedV$v") ++
        (1 to 6).map(v => s"engine_getPayloadV$v") ++
        Seq("engine_getBlobsV1", "engine_getBlobsV2") ++
        (1 to 2).flatMap(v => Seq(s"engine_getPayloadBodiesByHashV$v", s"engine_getPayloadBodiesByRangeV$v")) ++
        Seq("engine_exchangeCapabilities", "engine_getClientVersionV1")
    ).toSet

  /** The node's own. Made when the node starts (`StdNode`), not by the first request. */
  val Node: EngineCallLog = new EngineCallLog

  /** @param total
    *   every call since the node started, the ones no longer kept too
    * @param capabilities
    *   how many methods the consensus client listed in its last `engine_exchangeCapabilities`
    */
  final case class Snapshot(
      calls: Vector[EngineCallRecord],
      total: Long,
      identity: Option[ClIdentity],
      capabilities: Option[Int],
      authFailures: Map[AuthFailure, Long],
      payloadsBuilt: Long,
      payloadBuildsFailed: Long,
      lastBuild: Option[PayloadBuild]
  )

  private[engine] def isPassThrough(method: String): Boolean =
    method.startsWith("eth_") || method.startsWith("net_") || method.startsWith("web3_")

  /** What the consensus client was told. The status is a field of the answer the node built, read where it is: a
    * `newPayload` answer is a payload status, a `forkchoiceUpdated` answer holds one. No other method's answer is
    * looked into (a `getBlobs` answer is a list as long as what was asked for).
    */
  private[engine] def statusOf(method: String, response: JsonRpcResponse): Option[String] =
    response.error match
      case Some(error) => Some(s"error ${error.code}")
      case None =>
        val status =
          if method.startsWith("engine_newPayload") then response.result.map(_ \ "status")
          else if method.startsWith("engine_forkchoiceUpdated") then response.result.map(_ \ "payloadStatus" \ "status")
          else None
        status.collect { case JString(s) => clean(s) }

  /** The payload's block number: `newPayload` names it in its request, `getPayload` in its answer (inside
    * `executionPayload` from V2). A fork choice names hashes only.
    */
  private[engine] def blockNumberOf(method: String, request: JValue, response: Option[JsonRpcResponse]): Option[Long] =
    val field =
      if method.startsWith("engine_newPayload") then
        request \ "params" match
          case JArray((payload: JObject) :: _) => payload \ "blockNumber"
          case _                               => JNothing
      else if method.startsWith("engine_getPayload") && !method.startsWith("engine_getPayloadBodies") then
        response.flatMap(_.result) match
          case Some(result: JObject) =>
            result \ "executionPayload" match
              case payload: JObject => payload \ "blockNumber"
              case _                => result \ "blockNumber"
          case _ => JNothing
      else JNothing
    field match
      case JString(hex) if hex.startsWith("0x") && hex.length <= 18 =>
        Try(java.lang.Long.parseLong(hex.drop(2), 16)).toOption
      case _ => None

  /** The consensus client's own version, the first of the list it sends: `{code, name, version, commit}`. */
  private[engine] def identityOf(request: JValue): Option[ClIdentity] =
    val first = request \ "params" match
      case JArray((one: JObject) :: _)              => Some(one)
      case JArray(JArray((one: JObject) :: _) :: _) => Some(one)
      case _                                        => None
    first.flatMap { o =>
      def text(key: String): String = o \ key match
        case JString(s) => clean(s)
        case _          => ""
      val id = ClIdentity(text("code"), text("name"), text("version"), text("commit"))
      Option.when(id.name.nonEmpty || id.code.nonEmpty)(id)
    }

  /** A text of the caller's, safe to keep and to draw: cut, and with no control character. */
  private[engine] def clean(text: String): String =
    // Cut by code point, so that no half of a pair is kept.
    val kept = new java.lang.StringBuilder
    text
      .codePoints()
      .limit(MaxTextChars.toLong)
      .filter(cp => !Character.isISOControl(cp))
      .forEach(cp => kept.appendCodePoint(cp))
    kept.toString
