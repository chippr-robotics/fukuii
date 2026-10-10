package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Cancellable, Scheduler}
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.PeerRateTracker
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.utils.Logger

/** SNAP request tracker for managing pending requests and matching responses
  *
  * Tracks SNAP protocol requests by request ID, handles timeouts, and validates responses. Follows core-geth patterns
  * from eth/protocols/snap/sync.go for request tracking.
  *
  * Features:
  *   - Request ID generation and tracking
  *   - Adaptive timeout via PeerRateTracker (port of geth's msgrate)
  *   - Response validation and matching
  *   - Peer management for SNAP requests
  */
class SNAPRequestTracker(
    // Monotonic: a wall-clock (NTP) step must not look like a process stall. Only differences are ever used.
    nowMs: () => Long = () => System.nanoTime() / 1_000_000L,
    stallPolicy: SNAPRequestTracker.StallPolicy = SNAPRequestTracker.StallPolicy.Default
)(implicit scheduler: Scheduler)
    extends Logger:

  import SNAPRequestTracker.*

  /** Pending requests tracked by request ID */
  private val pendingRequests = mutable.Map[BigInt, PendingRequest]()

  /** Per-peer adaptive rate tracker (geth msgrate port) */
  val rateTracker: PeerRateTracker = new PeerRateTracker()

  // ── Prometheus wiring ────────────────────────────────────────────────────────────────────
  // The tracker sees every SNAP request (dispatch, completion, timeout) with its type, so the
  // per-phase request counters, download timers, and the timeout counter are emitted here in
  // one place instead of in each coordinator. Validation methods below emit the malformed-
  // response counter on structural violations (late responses are NOT counted as malformed).
  private def recordDispatchMetric(t: RequestType): Unit = t match
    case RequestType.GetAccountRange  => SNAPSyncMetrics.incrementAccountRangeRequests()
    case RequestType.GetStorageRanges => SNAPSyncMetrics.incrementStorageRangeRequests()
    case RequestType.GetByteCodes     => SNAPSyncMetrics.incrementBytecodeRequests()
    case RequestType.GetTrieNodes     => SNAPSyncMetrics.incrementHealingRequests()

  private def recordFailureMetric(t: RequestType): Unit = t match
    case RequestType.GetAccountRange  => SNAPSyncMetrics.incrementAccountRangeFailures()
    case RequestType.GetStorageRanges => SNAPSyncMetrics.incrementStorageRangeFailures()
    case RequestType.GetByteCodes     => SNAPSyncMetrics.incrementBytecodeFailures()
    case RequestType.GetTrieNodes     => SNAPSyncMetrics.incrementHealingFailures()

  private def recordDownloadTime(t: RequestType, elapsedMs: Long): Unit = t match
    case RequestType.GetAccountRange  => SNAPSyncMetrics.recordAccountRangeDownloadTime(elapsedMs)
    case RequestType.GetStorageRanges => SNAPSyncMetrics.recordStorageRangeDownloadTime(elapsedMs)
    case RequestType.GetByteCodes     => SNAPSyncMetrics.recordBytecodeDownloadTime(elapsedMs)
    case RequestType.GetTrieNodes     => SNAPSyncMetrics.recordStateHealingTime(elapsedMs)

  private def compareUnsignedLexicographically(a: ByteString, b: ByteString): Int =
    val minLen = math.min(a.length, b.length)
    var i = 0
    var result = 0
    while i < minLen && result == 0 do
      val av = java.lang.Byte.toUnsignedInt(a(i))
      val bv = java.lang.Byte.toUnsignedInt(b(i))
      result = av - bv
      i += 1
    if result != 0 then result else a.length - b.length

  /** Request ID counter */
  private var nextRequestId: BigInt = 1

  /** Generate next request ID
    *
    * @return
    *   unique request ID
    */
  def generateRequestId(): BigInt = synchronized {
    val id = nextRequestId
    nextRequestId += 1
    id
  }

  /** Track a pending request with adaptive timeout.
    *
    * Uses PeerRateTracker's adaptive timeout (geth msgrate algorithm) unless an explicit timeout is given. Timeout
    * formula: min(60s, 3 × medianRTT / confidence), starting at ~12s and converging as peers respond.
    *
    * @param requestId
    *   the request ID
    * @param peer
    *   the peer to which the request was sent
    * @param requestType
    *   the type of request
    * @param timeout
    *   explicit timeout override (None = use adaptive timeout from PeerRateTracker)
    * @param ownerDecidesTimeout
    *   when true the timer only calls `onTimeout`: the request stays pending, so a reply the owning actor processes
    *   first is still accepted, and the owner must call [[expireRequest]] when it handles the timeout. When false
    *   (default) the timer itself expires the request before calling `onTimeout`. See [[expireRequest]].
    * @param onTimeout
    *   callback when request times out
    * @return
    *   the tracked request
    */
  def trackRequest(
      requestId: BigInt,
      peer: Peer,
      requestType: RequestType,
      timeout: FiniteDuration = Duration.Zero, // Zero = use adaptive
      ownerDecidesTimeout: Boolean = false
  )(onTimeout: => Unit): PendingRequest = synchronized {
    val effectiveTimeout = if timeout == Duration.Zero then rateTracker.targetTimeout() else timeout
    recordDispatchMetric(requestType)
    val request = PendingRequest(
      requestId = requestId,
      peer = peer,
      requestType = requestType,
      timestamp = nowMs()
    )
    pendingRequests.put(
      requestId,
      request.copy(timeoutTask = Some(armTimeout(requestId, effectiveTimeout, onTimeout, ownerDecidesTimeout)))
    )
    request
  }

  /** Schedule the timeout for `requestId`. When the timer fires far later than its deadline the whole process was
    * paused (host swap storm, long safepoint): the peer's reply is sitting unread in the socket buffer, so declaring a
    * timeout now would blame the peer for our own stall, slash its rate capacity, cool it down and re-queue work whose
    * answer is about to arrive. Grant one grace period instead and restart the request clock so the stall is not
    * recorded as the peer's round-trip time. (plataberget soak 2026-10-05: during a ~78s stall, 30s storage timeouts
    * fired ~48s past their deadline and the "timed out" replies were processed 15ms afterwards.)
    */
  private def armTimeout(
      requestId: BigInt,
      delay: FiniteDuration,
      onTimeout: => Unit,
      ownerDecidesTimeout: Boolean,
      graceGranted: Boolean = false
  ): Cancellable =
    val scheduledAtMs = nowMs()
    scheduler.scheduleOnce(delay) {
      synchronized {
        pendingRequests.get(requestId).foreach { req =>
          val now = nowMs()
          val latenessMs = now - scheduledAtMs - delay.toMillis
          // Note: after a grace the request clock restarts at the stall's end, so a reply arriving in the grace window
          // is rated on its post-stall latency only (the peer's rate sample is not inflated by the stall).
          if !graceGranted && stallPolicy.isStall(latenessMs) then
            log.warn(
              s"SNAP request ${req.requestType} timer for request ID $requestId fired ${latenessMs}ms late — " +
                s"process stall, granting ${stallPolicy.grace.toMillis}ms grace instead of timing out peer ${req.peer.id}"
            )
            pendingRequests.put(
              requestId,
              req.copy(
                timestamp = now,
                timeoutTask = Some(armTimeout(requestId, stallPolicy.grace, onTimeout, ownerDecidesTimeout, true))
              )
            )
          else if ownerDecidesTimeout then
            // Leave the request pending: the owner's mailbox may already hold the reply (see expireRequest).
            log.debug(
              s"SNAP request ${req.requestType} timer for request ID $requestId fired after ${delay.toSeconds}s — " +
                s"owner decides the timeout"
            )
            onTimeout
          else
            expire(requestId, req, delay)
            onTimeout
        }
      }
    }

  /** Expire a request the owner has decided timed out: forget it, slash the peer's rate capacity and count the failure.
    * Returns `None` if the request was already completed or cancelled — then nothing is recorded, because the reply
    * won.
    *
    * Why the owner decides (requests tracked with `ownerDecidesTimeout = true`): the timer runs on the scheduler
    * thread, but the reply is delivered to the owning actor's mailbox. When that actor is busy — Sepolia 2026-10-09:
    * the storage coordinator spent 20-95 s inside single 128-account responses, 288 times in 7.7 h, while the rest of
    * the node kept running, so the late-timer stall grace above did not apply — a timer that expired the request itself
    * turned replies already waiting in the mailbox into "No pending request" discards (2,259 discarded replies against
    * 2,746 storage timeouts), and the timeout messages queued behind them then demoted every peer at once. With the
    * decision taken on the owner's thread, mailbox order is the arbiter: a reply enqueued before the timeout message
    * completes the request, and the timeout then finds nothing to expire. go-ethereum's snap syncer does the same: its
    * timer only schedules `revertStorageRequest` on the sync loop, and a response that reaches `OnStorage` first is
    * still matched against `storageReqs`.
    */
  def expireRequest(requestId: BigInt): Option[PendingRequest] = synchronized {
    pendingRequests.get(requestId).map { req =>
      req.timeoutTask.foreach(_.cancel())
      expire(requestId, req, Duration.Zero)
      req
    }
  }

  private def expire(requestId: BigInt, req: PendingRequest, delay: FiniteDuration): Unit =
    val elapsed = nowMs() - req.timestamp
    val timeoutLabel = if delay > Duration.Zero then s"timeout=${delay.toSeconds}s, " else ""
    log.warn(
      s"SNAP request ${req.requestType} timeout for request ID $requestId from peer ${req.peer.id} " +
        s"(${timeoutLabel}elapsed=${elapsed}ms)"
    )
    // Record timeout in rate tracker (items=0 slashes capacity to zero)
    val msgType = requestTypeToMsgType(req.requestType)
    rateTracker.update(req.peer.id.value, msgType, elapsed, items = 0)
    SNAPSyncMetrics.incrementRequestTimeout()
    recordFailureMetric(req.requestType)
    pendingRequests.remove(requestId)

  /** Check if a request is pending
    *
    * @param requestId
    *   the request ID
    * @return
    *   true if request is pending
    */
  def isPending(requestId: BigInt): Boolean = synchronized {
    pendingRequests.contains(requestId)
  }

  /** Get pending request
    *
    * @param requestId
    *   the request ID
    * @return
    *   the pending request if found
    */
  def getPendingRequest(requestId: BigInt): Option[PendingRequest] = synchronized {
    pendingRequests.get(requestId)
  }

  /** Complete a pending request and record response metrics in the rate tracker.
    *
    * @param requestId
    *   the request ID
    * @param responseItems
    *   number of items in the response (for rate tracker — default 1 if unknown)
    * @return
    *   the completed request if it was pending
    */
  def completeRequest(requestId: BigInt, responseItems: Int = 1): Option[PendingRequest] = synchronized {
    pendingRequests.remove(requestId).map { request =>
      // Cancel timeout
      request.timeoutTask.foreach(_.cancel())
      val elapsed = nowMs() - request.timestamp

      // Record measurement in rate tracker
      val msgType = requestTypeToMsgType(request.requestType)
      rateTracker.update(request.peer.id.value, msgType, elapsed, responseItems)
      recordDownloadTime(request.requestType, elapsed)

      log.debug(
        s"SNAP request ${request.requestType} completed for request ID $requestId " +
          s"(took ${elapsed}ms, items=$responseItems, timeout=${rateTracker.targetTimeout().toSeconds}s)"
      )
      request
    }
  }

  /** Forget a pending request and cancel its timeout WITHOUT recording any rate or failure metric. For a requester that
    * is abandoning or stopping, where the peer did nothing wrong (unlike [[completeRequest]], whose zero-item form
    * would slash the peer's capacity).
    */
  def cancelRequest(requestId: BigInt): Unit = synchronized {
    pendingRequests.remove(requestId).foreach(_.timeoutTask.foreach(_.cancel()))
  }

  /** Validate AccountRange response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateAccountRange(response: AccountRange): Either[String, AccountRange] =
    // Check if request is pending
    if !isPending(response.requestId) then Left(s"No pending request for ID ${response.requestId}")
    else
      val pending = getPendingRequest(response.requestId).get

      // Verify it's the expected type
      if pending.requestType != RequestType.GetAccountRange then
        SNAPSyncMetrics.incrementMalformedResponse()
        Left(s"Expected ${RequestType.GetAccountRange} but got response for ${pending.requestType}")
      else
        // Check accounts are monotonically increasing
        val violation = (1 until response.accounts.size).find { i =>
          val prevHash = response.accounts(i - 1)._1
          val currHash = response.accounts(i)._1
          compareUnsignedLexicographically(prevHash, currHash) >= 0
        }
        violation match
          case Some(i) =>
            SNAPSyncMetrics.incrementMalformedResponse()
            Left(s"Accounts not monotonically increasing at index $i")
          case None => Right(response)

  /** Validate StorageRanges response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateStorageRanges(response: StorageRanges): Either[String, StorageRanges] =
    if !isPending(response.requestId) then Left(s"No pending request for ID ${response.requestId}")
    else
      val pending = getPendingRequest(response.requestId).get
      if pending.requestType != RequestType.GetStorageRanges then
        SNAPSyncMetrics.incrementMalformedResponse()
        Left(s"Expected ${RequestType.GetStorageRanges} but got response for ${pending.requestType}")
      else
        // Validate storage slots are monotonically increasing within each account
        val violation = response.slots.zipWithIndex.collectFirst { case (accountSlots, accountIdx) =>
          (1 until accountSlots.size)
            .find { i =>
              val prevHash = accountSlots(i - 1)._1
              val currHash = accountSlots(i)._1
              compareUnsignedLexicographically(prevHash, currHash) >= 0
            }
            .map(i => (accountIdx, i))
        }.flatten
        violation match
          case Some((accountIdx, i)) =>
            SNAPSyncMetrics.incrementMalformedResponse()
            Left(s"Storage slots not monotonically increasing for account $accountIdx at index $i")
          case None => Right(response)

  /** Validate ByteCodes response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateByteCodes(response: ByteCodes): Either[String, ByteCodes] =
    if !isPending(response.requestId) then Left(s"No pending request for ID ${response.requestId}")
    else
      val pending = getPendingRequest(response.requestId).get
      if pending.requestType != RequestType.GetByteCodes then
        SNAPSyncMetrics.incrementMalformedResponse()
        Left(s"Expected ${RequestType.GetByteCodes} but got response for ${pending.requestType}")
      else Right(response)

  /** Validate TrieNodes response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateTrieNodes(response: TrieNodes): Either[String, TrieNodes] =
    if !isPending(response.requestId) then Left(s"No pending request for ID ${response.requestId}")
    else
      val pending = getPendingRequest(response.requestId).get
      if pending.requestType != RequestType.GetTrieNodes then
        SNAPSyncMetrics.incrementMalformedResponse()
        Left(s"Expected ${RequestType.GetTrieNodes} but got response for ${pending.requestType}")
      else Right(response)

  /** Get count of pending requests */
  def pendingCount: Int = synchronized {
    pendingRequests.size
  }

  /** Clear all pending requests */
  def clear(): Unit = synchronized {
    pendingRequests.values.foreach(_.timeoutTask.foreach(_.cancel()))
    pendingRequests.clear()
  }

  /** Map RequestType to PeerRateTracker message type ordinal */
  private def requestTypeToMsgType(rt: RequestType): Int = rt match
    case RequestType.GetAccountRange  => PeerRateTracker.MsgGetAccountRange
    case RequestType.GetStorageRanges => PeerRateTracker.MsgGetStorageRanges
    case RequestType.GetByteCodes     => PeerRateTracker.MsgGetByteCodes
    case RequestType.GetTrieNodes     => PeerRateTracker.MsgGetTrieNodes

object SNAPRequestTracker:

  /** When to treat a late timeout timer as a process stall rather than a slow peer.
    *
    * @param latenessThreshold
    *   a timer firing more than this after its deadline means the process (not the peer) was paused. Normal scheduler
    *   jitter is tens of milliseconds, so 5s is far above noise and far below the stalls seen in the field (78-155s).
    * @param grace
    *   extra time a request gets, once, after a detected stall before it times out for real.
    */
  final case class StallPolicy(latenessThreshold: FiniteDuration, grace: FiniteDuration):
    def isStall(latenessMs: Long): Boolean = latenessMs > latenessThreshold.toMillis

  object StallPolicy:
    val Default: StallPolicy = StallPolicy(latenessThreshold = 5.seconds, grace = 10.seconds)

  /** Pending SNAP request */
  case class PendingRequest(
      requestId: BigInt,
      peer: Peer,
      requestType: RequestType,
      timestamp: Long,
      timeoutTask: Option[Cancellable] = None
  )

  /** SNAP request types */
  sealed trait RequestType
  object RequestType:
    case object GetAccountRange extends RequestType
    case object GetStorageRanges extends RequestType
    case object GetByteCodes extends RequestType
    case object GetTrieNodes extends RequestType
