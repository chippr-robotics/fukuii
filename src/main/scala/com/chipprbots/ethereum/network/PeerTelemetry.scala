package com.chipprbots.ethereum.network

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter

import com.chipprbots.ethereum.metrics.MetricsContainer
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ProtocolFamily
import com.chipprbots.ethereum.utils.Logger

/** Per-peer Prometheus telemetry for network exploration / dashboards.
  *
  * For every handshaked peer we publish a labelled "info" gauge so an operator can explore exactly which nodes this
  * client is connected to (IP, software, protocol, direction, snap support, network) and where they sit (the geo
  * exporter joins on `remote_address`). A companion best-block gauge carries the peer's advertised head at handshake.
  *
  * Cardinality is bounded by the live peer count: each meter is keyed on the peer id and removed from the registry the
  * moment the peer disconnects, so churned series don't accumulate. Aggregate breakdowns (client diversity, protocol
  * mix, in/out split) are intentionally NOT precomputed here — the dashboard derives them with `count
  * by(client_name)(app_network_peer_info)` and friends, which keeps this module a single source of truth.
  *
  * Exposed on the node's `/metrics` endpoint (`fukuii.metrics.port`, e.g. :9095) as:
  *   - `app_network_peer_info{peer,remote_address,client,client_name,capability,network_id,direction,snap} = 1`
  *   - `app_network_peer_best_block{peer} = <advertised head at handshake>`
  *
  * All registry interaction is wrapped so a telemetry hiccup can never take down [[NetworkPeerManagerActor]].
  */
case object PeerTelemetry extends MetricsContainer with Logger {

  final private val InfoMetricName = "network.peer.info"
  final private val BestBlockMetricName = "network.peer.best_block"

  // PeerId -> registered Meter. Kept so we can remove the exact series on disconnect.
  // The NetworkPeerManagerActor drives register/deregister single-threaded, but other call
  // sites (tests) may touch these, so use concurrent maps defensively.
  final private val infoMeters = new ConcurrentHashMap[PeerId, Meter]()
  final private val bestBlockMeters = new ConcurrentHashMap[PeerId, Meter]()

  /** Publish (or refresh) the telemetry series for a freshly handshaked peer. Idempotent per peer. */
  def registerPeer(peer: Peer, peerInfo: PeerInfo): Unit =
    try {
      val status = peerInfo.remoteStatus
      val address = remoteAddressLabel(peer.remoteAddress)
      val client = normalizeClient(status.remoteClientId)
      val tags = Seq(
        "peer",
        peer.id.value,
        "remote_address",
        address,
        "client",
        client,
        "client_name",
        clientName(client),
        "capability",
        capabilityLabel(status.capability),
        "network_id",
        status.networkId.toString,
        "direction",
        if (peer.incomingConnection) "inbound" else "outbound",
        "snap",
        status.supportsSnap.toString
      )

      // Replace any stale series for this peer first (e.g. reconnect before a disconnect was processed).
      removeMeter(infoMeters, peer.id)
      val infoGauge = Gauge
        .builder(InfoMetricName, this, (_: Any) => 1.0)
        .tags(tags: _*)
        .strongReference(true)
        .register(metrics.registry)
      infoMeters.put(peer.id, infoGauge)

      removeMeter(bestBlockMeters, peer.id)
      val bestBlock = peerInfo.maxBlockNumber.toDouble
      val blockGauge = Gauge
        .builder(BestBlockMetricName, this, (_: Any) => bestBlock)
        .tags("peer", peer.id.value)
        .strongReference(true)
        .register(metrics.registry)
      bestBlockMeters.put(peer.id, blockGauge)

      log.debug(
        "PEER_TELEMETRY_ADD: peer={} addr={} client={} cap={} dir={} snap={} block={}",
        peer.id.value,
        address,
        client,
        capabilityLabel(status.capability),
        if (peer.incomingConnection) "inbound" else "outbound",
        status.supportsSnap,
        bestBlock.toLong
      )
    } catch {
      case t: Throwable =>
        log.warn(s"PEER_TELEMETRY_ADD failed for ${peer.id.value}: ${t.getMessage}")
    }

  /** Drop the telemetry series for a disconnected peer so its labels stop being scraped. */
  def deregisterPeer(peerId: PeerId): Unit =
    try {
      removeMeter(infoMeters, peerId)
      removeMeter(bestBlockMeters, peerId)
    } catch {
      case t: Throwable =>
        log.warn(s"PEER_TELEMETRY_REMOVE failed for ${peerId.value}: ${t.getMessage}")
    }

  /** Number of peers currently carrying an info series. Test/observability hook. */
  def trackedPeerCount: Int = infoMeters.size

  private def removeMeter(map: ConcurrentHashMap[PeerId, Meter], peerId: PeerId): Unit = {
    val existing = map.remove(peerId)
    if (existing != null) {
      val _ = metrics.registry.remove(existing)
    }
  }

  /** Numeric ip:port, avoiding any reverse-DNS so the geo exporter sees the raw address. */
  private def remoteAddressLabel(addr: InetSocketAddress): String = {
    val host = Option(addr.getAddress).map(_.getHostAddress).getOrElse(addr.getHostString)
    s"$host:${addr.getPort}"
  }

  /** "eth/68" style; falls back to the raw enum name for any non-ETH family. */
  private def capabilityLabel(cap: Capability): String = {
    val family = cap.name match {
      case ProtocolFamily.ETH  => "eth"
      case ProtocolFamily.SNAP => "snap"
    }
    s"$family/${cap.version.toInt}"
  }

  private def normalizeClient(raw: String): String =
    Option(raw).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")

  /** Software name only — first segment before the version, e.g. "CoreGeth/v1.12.20-..." -> "CoreGeth". */
  private def clientName(client: String): String =
    client.split('/').headOption.map(_.trim).filter(_.nonEmpty).getOrElse(client)
}
