package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor._
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.math.Ordered.orderingToOrdered

import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.utils.Logger

/** SNAP request tracker for managing pending requests and matching responses
  *
  * Tracks SNAP protocol requests by request ID, handles timeouts, and validates responses. Follows core-geth patterns
  * from eth/protocols/snap/sync.go for request tracking.
  *
  * Features:
  *   - Request ID generation and tracking
  *   - Timeout handling for pending requests
  *   - Response validation and matching
  *   - Peer management for SNAP requests
  */
class SNAPRequestTracker(implicit scheduler: Scheduler) extends Logger {

  import SNAPRequestTracker._

  /** Pending requests tracked by request ID */
  private val pendingRequests = mutable.Map[BigInt, PendingRequest]()

  private def compareUnsignedLexicographically(a: ByteString, b: ByteString): Int = {
    val minLen = math.min(a.length, b.length)
    var i = 0
    while (i < minLen) {
      val av = java.lang.Byte.toUnsignedInt(a(i))
      val bv = java.lang.Byte.toUnsignedInt(b(i))
      if (av != bv) return av - bv
      i += 1
    }
    a.length - b.length
  }

  /** Request ID counter */
  private var nextRequestId: BigInt = 1

  /** Default timeout for SNAP requests */
  private val defaultTimeout: FiniteDuration = 30.seconds

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

  /** Track a pending request
    *
    * @param requestId
    *   the request ID
    * @param peer
    *   the peer to which the request was sent
    * @param requestType
    *   the type of request
    * @param timeout
    *   timeout duration (default 30 seconds)
    * @param onTimeout
    *   callback when request times out
    * @return
    *   the tracked request
    */
  def trackRequest(
      requestId: BigInt,
      peer: Peer,
      requestType: RequestType,
      timeout: FiniteDuration = defaultTimeout
  )(onTimeout: => Unit): PendingRequest = synchronized {
    val request = PendingRequest(
      requestId = requestId,
      peer = peer,
      requestType = requestType,
      timestamp = System.currentTimeMillis()
    )

    // Schedule timeout
    val timeoutTask = scheduler.scheduleOnce(timeout) {
      synchronized {
        pendingRequests.get(requestId).foreach { req =>
          log.warn(s"SNAP request ${req.requestType} timeout for request ID $requestId from peer ${peer.id}")
          pendingRequests.remove(requestId)
          onTimeout
        }
      }
    }

    pendingRequests.put(requestId, request.copy(timeoutTask = Some(timeoutTask)))
    request
  }

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

  /** Complete a pending request
    *
    * @param requestId
    *   the request ID
    * @return
    *   the completed request if it was pending
    */
  def completeRequest(requestId: BigInt): Option[PendingRequest] = synchronized {
    pendingRequests.remove(requestId).map { request =>
      // Cancel timeout
      request.timeoutTask.foreach(_.cancel())
      log.debug(
        s"SNAP request ${request.requestType} completed for request ID $requestId (took ${System.currentTimeMillis() - request.timestamp}ms)"
      )
      request
    }
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
    if (!isPending(response.requestId)) {
      Left(s"No pending request for ID ${response.requestId}")
    } else {
      val pending = getPendingRequest(response.requestId).get

      // Verify it's the expected type
      if (pending.requestType != RequestType.GetAccountRange) {
        Left(s"Expected ${RequestType.GetAccountRange} but got response for ${pending.requestType}")
      } else {
        // Check accounts are monotonically increasing
        val violation = (1 until response.accounts.size).find { i =>
          val prevHash = response.accounts(i - 1)._1
          val currHash = response.accounts(i)._1
          compareUnsignedLexicographically(prevHash, currHash) >= 0
        }
        violation match {
          case Some(i) => Left(s"Accounts not monotonically increasing at index $i")
          case None    => Right(response)
        }
      }
    }

  /** Validate StorageRanges response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateStorageRanges(response: StorageRanges): Either[String, StorageRanges] =
    if (!isPending(response.requestId)) {
      Left(s"No pending request for ID ${response.requestId}")
    } else {
      val pending = getPendingRequest(response.requestId).get
      if (pending.requestType != RequestType.GetStorageRanges) {
        Left(s"Expected ${RequestType.GetStorageRanges} but got response for ${pending.requestType}")
      } else {
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
        violation match {
          case Some((accountIdx, i)) =>
            Left(s"Storage slots not monotonically increasing for account $accountIdx at index $i")
          case None => Right(response)
        }
      }
    }

  /** Validate ByteCodes response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateByteCodes(response: ByteCodes): Either[String, ByteCodes] = {
    if (!isPending(response.requestId)) {
      return Left(s"No pending request for ID ${response.requestId}")
    }

    val pending = getPendingRequest(response.requestId).get
    if (pending.requestType != RequestType.GetByteCodes) {
      return Left(s"Expected ${RequestType.GetByteCodes} but got response for ${pending.requestType}")
    }

    // TODO: Validate bytecode hashes match requested hashes
    Right(response)
  }

  /** Validate TrieNodes response
    *
    * @param response
    *   the response to validate
    * @return
    *   validation result
    */
  def validateTrieNodes(response: TrieNodes): Either[String, TrieNodes] = {
    if (!isPending(response.requestId)) {
      return Left(s"No pending request for ID ${response.requestId}")
    }

    val pending = getPendingRequest(response.requestId).get
    if (pending.requestType != RequestType.GetTrieNodes) {
      return Left(s"Expected ${RequestType.GetTrieNodes} but got response for ${pending.requestType}")
    }

    Right(response)
  }

  /** Get count of pending requests */
  def pendingCount: Int = synchronized {
    pendingRequests.size
  }

  /** Clear all pending requests */
  def clear(): Unit = synchronized {
    pendingRequests.values.foreach(_.timeoutTask.foreach(_.cancel()))
    pendingRequests.clear()
  }
}

object SNAPRequestTracker {

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
  object RequestType {
    case object GetAccountRange extends RequestType
    case object GetStorageRanges extends RequestType
    case object GetByteCodes extends RequestType
    case object GetTrieNodes extends RequestType
  }
}
