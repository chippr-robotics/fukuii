package com.chipprbots.ethereum.network.discovery

import scala.concurrent.duration._

import com.chipprbots.ethereum.utils.ConfigUtils
import com.chipprbots.ethereum.utils.Logger

case class AutoBlockConfig(
    enabled: Boolean,
    udpFailureThreshold: Int,
    udpFailureWindow: FiniteDuration,
    udpBlockDuration: FiniteDuration,
    hardFailureBlockDuration: FiniteDuration
)

case class DiscoveryConfig(
    discoveryEnabled: Boolean,
    host: Option[String],
    interface: String,
    port: Int,
    bootstrapNodes: Set[Node],
    reuseKnownNodes: Boolean,
    scanInterval: FiniteDuration,
    messageExpiration: FiniteDuration,
    maxClockDrift: FiniteDuration,
    requestTimeout: FiniteDuration,
    kademliaTimeout: FiniteDuration,
    kademliaBucketSize: Int,
    kademliaAlpha: Int,
    channelCapacity: Int,
    blockedIPs: Set[String],
    autoBlock: AutoBlockConfig
)

object DiscoveryConfig extends Logger {
  def apply(
      etcClientConfig: com.typesafe.config.Config,
      bootstrapNodes: Set[String],
      dnsDiscoveryDomains: Seq[String] = Seq.empty
  ): DiscoveryConfig = {
    val discoveryConfig = etcClientConfig.getConfig("network.discovery")

    // Load static nodes from datadir/static-nodes.json if it exists
    val datadir = etcClientConfig.getString("datadir")
    val staticNodes = StaticNodesLoader.loadFromDatadir(datadir)

    // Resolve DNS discovery domains (EIP-1459) to enode URLs
    val dnsNodes: Set[String] = if (dnsDiscoveryDomains.nonEmpty) {
      log.info(s"Resolving ${dnsDiscoveryDomains.size} DNS discovery domain(s): ${dnsDiscoveryDomains.mkString(", ")}")
      dnsDiscoveryDomains.flatMap { domain =>
        DnsDiscovery.resolveEnodes(domain)
      }.toSet
    } else {
      Set.empty
    }

    // Check if bootstrap nodes should be used (controlled by modifiers like 'enterprise')
    // Default to true if not specified to maintain backward compatibility
    val useBootstrapNodes =
      try
        System.getProperty("fukuii.network.discovery.use-bootstrap-nodes", "true").toBoolean
      catch {
        case _: Exception => true
      }

    // Combine nodes based on configuration
    val allBootstrapNodes = if (useBootstrapNodes) {
      // Public/default mode: merge bootstrap nodes, DNS-discovered nodes, and static nodes
      val combined = bootstrapNodes ++ dnsNodes ++ staticNodes
      val sources = Seq(
        if (bootstrapNodes.nonEmpty) Some(s"${bootstrapNodes.size} config") else None,
        if (dnsNodes.nonEmpty) Some(s"${dnsNodes.size} DNS") else None,
        if (staticNodes.nonEmpty) Some(s"${staticNodes.size} static") else None
      ).flatten
      if (sources.nonEmpty) {
        log.info(s"Bootstrap nodes: ${combined.size} total (${sources.mkString(", ")})")
      }
      combined
    } else {
      // Enterprise mode: use only static nodes, ignore bootstrap nodes and DNS
      if (staticNodes.nonEmpty) {
        log.info(s"Using ${staticNodes.size} static node(s) from static-nodes.json (bootstrap nodes ignored)")
      } else {
        log.warn("Bootstrap nodes disabled but no static-nodes.json found - node may not connect to any peers")
      }
      staticNodes
    }

    val autoBlockCfg = discoveryConfig.getConfig("auto-block")
    DiscoveryConfig(
      discoveryEnabled = discoveryConfig.getBoolean("discovery-enabled"),
      host = ConfigUtils.getOptionalValue(discoveryConfig, _.getString, "host"),
      interface = discoveryConfig.getString("interface"),
      port = discoveryConfig.getInt("port"),
      bootstrapNodes = NodeParser.parseNodes(allBootstrapNodes),
      reuseKnownNodes = discoveryConfig.getBoolean("reuse-known-nodes"),
      scanInterval = discoveryConfig.getDuration("scan-interval").toMillis.millis,
      messageExpiration = discoveryConfig.getDuration("message-expiration").toMillis.millis,
      maxClockDrift = discoveryConfig.getDuration("max-clock-drift").toMillis.millis,
      requestTimeout = discoveryConfig.getDuration("request-timeout").toMillis.millis,
      kademliaTimeout = discoveryConfig.getDuration("kademlia-timeout").toMillis.millis,
      kademliaBucketSize = discoveryConfig.getInt("kademlia-bucket-size"),
      kademliaAlpha = discoveryConfig.getInt("kademlia-alpha"),
      channelCapacity = discoveryConfig.getInt("channel-capacity"),
      blockedIPs =
        if (discoveryConfig.hasPath("blocked-ips")) discoveryConfig.getStringList("blocked-ips").asScala.toSet
        else Set.empty[String],
      autoBlock = AutoBlockConfig(
        enabled = autoBlockCfg.getBoolean("enabled"),
        udpFailureThreshold = autoBlockCfg.getInt("udp-failure-threshold"),
        udpFailureWindow = autoBlockCfg.getDuration("udp-failure-window").toMillis.millis,
        udpBlockDuration = autoBlockCfg.getDuration("udp-block-duration").toMillis.millis,
        hardFailureBlockDuration = autoBlockCfg.getDuration("hard-failure-block-duration").toMillis.millis
      )
    )
  }

}
