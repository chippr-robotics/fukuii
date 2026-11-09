package com.chipprbots.ethereum.network

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge

import com.chipprbots.ethereum.metrics.MetricsContainer

case object NetworkMetrics extends MetricsContainer:

  final private val HandshakedIncomingPeersGauge =
    metrics.registry.gauge("network.peers.incoming.handshaked.gauge", new AtomicLong(0))
  final private val HandshakedOutgoingPeersGauge =
    metrics.registry.gauge("network.peers.outgoing.handshaked.gauge", new AtomicLong(0))

  final val ReceivedMessagesCounter: Counter = metrics.counter("network.messages.received.counter")

  final val SentMessagesCounter: Counter = metrics.counter("network.messages.sent.counter")

  final val DiscoveredPeersSize: AtomicLong =
    metrics.registry.gauge("network.discovery.foundPeers.gauge", new AtomicLong(0))

  final val BlacklistedPeersSize: AtomicLong =
    metrics.registry.gauge("network.peers.blacklisted.gauge", new AtomicLong(0))

  final val BlacklistedReasonsFastSyncGroup: Counter =
    metrics.registry.counter("network.peers.blacklisted.fastSyncGroup.counter")
  final val BlacklistedReasonsRegularSyncGroup: Counter =
    metrics.registry.counter("network.peers.blacklisted.regularSyncGroup.counter")
  final val BlacklistedReasonsP2PGroup: Counter = metrics.registry.counter("network.peers.blacklisted.p2pGroup.counter")

  final val PendingPeersSize: AtomicLong = metrics.registry.gauge("network.peers.pending.gauge", new AtomicLong(0))

  final val TriedPeersSize: AtomicLong =
    metrics.registry.gauge("network.tried.peers.gauge", new AtomicLong(0L))

  // ---- Peer scores (spec 027 slice 4a) ------------------------------------------------------------------------------
  //
  // The PeerScoringManager publishes a snapshot here (throttled to once a second of its clock, and on the peer
  // manager's 10 s tick). The aggregates are registered as gauges that read the snapshot when scraped, so they always
  // equal `getStatistics` of the scores that were published. The per-peer scores are in the snapshot, for the Peers
  // view to read, and not in the registry: a series per peer would be unbounded.

  private val peerScoreSnapshot = new AtomicReference[PeerScoreSnapshot](PeerScoreSnapshot.empty)

  private def scoreGauge(name: String)(value: ScoringStatistics => Double): Gauge =
    Gauge
      .builder(name, peerScoreSnapshot, (ref: AtomicReference[PeerScoreSnapshot]) => value(ref.get().statistics))
      .register(metrics.registry)

  /** Peers with a score. */
  final val ScoredPeersGauge: Gauge = scoreGauge("network.peers.scored.gauge")(_.totalPeers.toDouble)

  /** Mean score over the scored peers; 0 with none. */
  final val AverageScoreGauge: Gauge = scoreGauge("network.peers.score.average.gauge")(_.averageScore)

  /** Peers scoring 0.7 or more. */
  final val HighScoringPeersGauge: Gauge = scoreGauge("network.peers.score.high.gauge")(_.highScoringPeers.toDouble)

  /** Peers scoring from 0.4 up to 0.7. */
  final val MediumScoringPeersGauge: Gauge =
    scoreGauge("network.peers.score.medium.gauge")(_.mediumScoringPeers.toDouble)

  /** Peers scoring below 0.4. */
  final val LowScoringPeersGauge: Gauge = scoreGauge("network.peers.score.low.gauge")(_.lowScoringPeers.toDouble)

  /** Replace the published scores. Called by the PeerScoringManager. */
  def publishPeerScores(snapshot: PeerScoreSnapshot): Unit = peerScoreSnapshot.set(snapshot)

  /** The scores as last published; empty before the first publish. Safe to read from any thread. */
  def peerScores: PeerScoreSnapshot = peerScoreSnapshot.get()

  def registerAddHandshakedPeer(peer: Peer): Unit =
    if peer.incomingConnection then HandshakedIncomingPeersGauge.incrementAndGet()
    else HandshakedOutgoingPeersGauge.incrementAndGet()

  def registerRemoveHandshakedPeer(peer: Peer): Unit =
    if peer.incomingConnection then HandshakedIncomingPeersGauge.decrementAndGet()
    else HandshakedOutgoingPeersGauge.decrementAndGet()
