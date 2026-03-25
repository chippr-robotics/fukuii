package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange

/** Probes peers that advertise snap/1 to verify they actually serve SNAP data.
  *
  * Besu pattern: send GetAccountRange(rootHash=peerHeadStateRoot, start=0x00, end=0x00) with 6s timeout.
  * Non-empty response = verified server. Empty/timeout = dishonest advertisement.
  */
object SnapServerChecker {

  /** Timeout for snap server probe responses (aligned with Besu's SNAP_SERVER_CHECKER_TIMEOUT) */
  val ProbeTimeoutMillis: Long = 6000L

  /** Minimum request ID for probe requests — uses high range to avoid collision with sync request IDs */
  private val probeRequestIdCounter = new AtomicLong(Long.MaxValue / 2)

  /** 32-byte zero hash used as start/end for probe */
  val ZeroHash: ByteString = ByteString(new Array[Byte](32))

  /** Active probes keyed by requestId → peerId */
  private val pendingProbes = new ConcurrentHashMap[BigInt, PeerId]()

  /** Peers that have already been probed (to avoid duplicate probes) */
  private val probedPeers = ConcurrentHashMap.newKeySet[PeerId]()

  /** Generate a unique request ID for probe messages */
  def nextProbeRequestId: BigInt = BigInt(probeRequestIdCounter.getAndIncrement())

  /** Check if a request ID belongs to a probe */
  def isProbeRequestId(requestId: BigInt): Boolean = pendingProbes.containsKey(requestId)

  /** Create a probe GetAccountRange message.
    *
    * Uses the stateRoot from the peer's best block header (fetched via GetBlockHeaders).
    * Note: this must be the header's stateRoot field, NOT the block hash (bestHash).
    * Start and end hash are both 0x00...00 (Besu's exact probe pattern).
    * responseBytes is set to 4096 — small enough to be fast, large enough for at least one account.
    */
  def createProbe(stateRoot: ByteString): (BigInt, GetAccountRange) = {
    val requestId = nextProbeRequestId
    val msg = GetAccountRange(
      requestId = requestId,
      rootHash = stateRoot,
      startingHash = ZeroHash,
      limitHash = ZeroHash,
      responseBytes = 4096
    )
    (requestId, msg)
  }

  /** Register a pending probe for a peer. Returns the requestId. */
  def registerProbe(requestId: BigInt, peerId: PeerId): Unit =
    pendingProbes.put(requestId, peerId)

  /** Complete a probe and return the peerId if this was a probe response.
    * Removes the probe from pending tracking.
    */
  def completeProbe(requestId: BigInt): Option[PeerId] =
    Option(pendingProbes.remove(requestId))

  /** Cancel a probe (on timeout). Returns the peerId if the probe was still pending. */
  def cancelProbe(requestId: BigInt): Option[PeerId] =
    Option(pendingProbes.remove(requestId))

  /** Evaluate whether a probe response indicates the peer is serving SNAP data.
    * Non-empty accounts OR proofs = verified server (aligned with Besu's check).
    */
  def isServingSnap(response: AccountRange): Boolean =
    response.accounts.nonEmpty || response.proof.nonEmpty

  /** Check if a peer has already been probed (to avoid duplicate probes on repeated BlockHeaders). */
  def hasBeenProbed(peerId: PeerId): Boolean = probedPeers.contains(peerId)

  /** Send a probe to a peer and register it for tracking. Returns None if already probed. */
  def sendProbe(peerRef: ActorRef, stateRoot: ByteString, peerId: PeerId): BigInt = {
    val (requestId, probeMsg) = createProbe(stateRoot)
    probedPeers.add(peerId)
    registerProbe(requestId, peerId)
    peerRef ! PeerActor.SendMessage(probeMsg)
    requestId
  }

  /** Number of currently pending probes */
  def pendingCount: Int = pendingProbes.size()

  /** Reset all state — for testing only */
  def reset(): Unit = {
    pendingProbes.clear()
    probedPeers.clear()
    probeRequestIdCounter.set(Long.MaxValue / 2)
  }
}
