package com.chipprbots.ethereum.network

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters._

import io.micrometer.core.instrument.Meter

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for [[PeerTelemetry]] — the per-peer Prometheus series that feed the network-explore dashboard.
  *
  * Verifies the labels are derived correctly from Peer/PeerInfo, that a peer's series is removed from the registry on
  * disconnect (bounded cardinality), and that re-registering the same peer doesn't leak meters.
  */
class PeerTelemetrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("PeerTelemetrySpec")
  // Any non-null ActorRef satisfies Peer; we never message it.
  private def aRef = system.deadLetters

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  private def registry = Metrics.get().registry

  private def peer(id: String, ip: String, port: Int, inbound: Boolean): Peer =
    Peer(PeerId(id), new InetSocketAddress(ip, port), aRef, incomingConnection = inbound)

  private def peerInfo(
      clientId: String,
      capability: Capability = Capability.ETH68,
      snap: Boolean = false,
      networkId: Long = 1L,
      maxBlock: BigInt = BigInt(0)
  ): PeerInfo = {
    val status = RemoteStatus(
      capability = capability,
      networkId = networkId,
      chainWeight = ChainWeight.zero,
      bestHash = ByteString.empty,
      genesisHash = ByteString.empty,
      supportsSnap = snap,
      capabilities = List(capability),
      latestBlock = None,
      remoteClientId = clientId
    )
    PeerInfo(status, ChainWeight.zero, forkAccepted = true, maxBlockNumber = maxBlock, bestBlockHash = ByteString.empty)
  }

  // Find the info meter whose `peer` tag matches, regardless of the metrics-prefix the active registry applies.
  private def findInfoMeter(peerId: String): Option[Meter] =
    registry.getMeters.asScala.find { m =>
      m.getId.getName.endsWith("network.peer.info") &&
      Option(m.getId.getTag("peer")).contains(peerId)
    }

  private def tagOf(m: Meter, key: String): String = m.getId.getTag(key)

  "PeerTelemetry" should "register a per-peer info series with labels derived from Peer/PeerInfo" taggedAs UnitTest in {
    val p = peer("telemetry-reg-1", "1.2.3.4", 30303, inbound = false)
    val info = peerInfo("CoreGeth/v1.12.20-stable-abc/linux-amd64/go1.21", Capability.ETH68, snap = true)

    PeerTelemetry.registerPeer(p, info)
    try {
      val meter = findInfoMeter("telemetry-reg-1")
      meter shouldBe defined
      val m = meter.get
      tagOf(m, "remote_address") shouldBe "1.2.3.4:30303"
      tagOf(m, "client") shouldBe "CoreGeth/v1.12.20-stable-abc/linux-amd64/go1.21"
      tagOf(m, "client_name") shouldBe "CoreGeth"
      tagOf(m, "capability") shouldBe "eth/68"
      tagOf(m, "network_id") shouldBe "1"
      tagOf(m, "direction") shouldBe "outbound"
      tagOf(m, "snap") shouldBe "true"
    } finally PeerTelemetry.deregisterPeer(p.id)
  }

  it should "label inbound peers and missing clientId distinctly" taggedAs UnitTest in {
    val p = peer("telemetry-reg-2", "10.0.0.9", 30304, inbound = true)
    PeerTelemetry.registerPeer(p, peerInfo(clientId = ""))
    try {
      val m = findInfoMeter("telemetry-reg-2").get
      tagOf(m, "direction") shouldBe "inbound"
      tagOf(m, "client") shouldBe "unknown"
      tagOf(m, "client_name") shouldBe "unknown"
    } finally PeerTelemetry.deregisterPeer(p.id)
  }

  it should "publish a best-block gauge carrying the advertised head" taggedAs UnitTest in {
    val p = peer("telemetry-reg-3", "1.2.3.5", 30303, inbound = false)
    PeerTelemetry.registerPeer(p, peerInfo("Geth/v1.14.0", maxBlock = BigInt(987654)))
    try {
      val block = registry.getMeters.asScala.find { m =>
        m.getId.getName.endsWith("network.peer.best_block") &&
        Option(m.getId.getTag("peer")).contains("telemetry-reg-3")
      }
      block shouldBe defined
      block.get.measure().iterator().next().getValue shouldBe 987654.0
    } finally PeerTelemetry.deregisterPeer(p.id)
  }

  it should "remove a peer's series from the registry on disconnect (bounded cardinality)" taggedAs UnitTest in {
    val p = peer("telemetry-del-1", "8.8.8.8", 30303, inbound = false)
    PeerTelemetry.registerPeer(p, peerInfo("besu/v24.1.0"))
    findInfoMeter("telemetry-del-1") shouldBe defined

    PeerTelemetry.deregisterPeer(p.id)
    findInfoMeter("telemetry-del-1") shouldBe empty
    registry.getMeters.asScala.exists { m =>
      m.getId.getName.endsWith("network.peer.best_block") &&
      Option(m.getId.getTag("peer")).contains("telemetry-del-1")
    } shouldBe false
  }

  it should "be idempotent when the same peer re-registers (no meter leak)" taggedAs UnitTest in {
    val before = PeerTelemetry.trackedPeerCount
    val p = peer("telemetry-idem-1", "9.9.9.9", 30303, inbound = false)
    PeerTelemetry.registerPeer(p, peerInfo("nethermind/v1.25.0"))
    PeerTelemetry.registerPeer(p, peerInfo("nethermind/v1.25.0"))
    try {
      PeerTelemetry.trackedPeerCount shouldBe (before + 1)
      registry.getMeters.asScala.count { m =>
        m.getId.getName.endsWith("network.peer.info") &&
        Option(m.getId.getTag("peer")).contains("telemetry-idem-1")
      } shouldBe 1
    } finally PeerTelemetry.deregisterPeer(p.id)
  }
}
