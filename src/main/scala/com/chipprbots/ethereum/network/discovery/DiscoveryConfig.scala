package com.chipprbots.ethereum.network.discovery

import scala.concurrent.duration._

import com.chipprbots.ethereum.utils.ConfigUtils
import com.chipprbots.ethereum.utils.Logger

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
    channelCapacity: Int
)

object DiscoveryConfig extends Logger {
  def apply(etcClientConfig: com.typesafe.config.Config, bootstrapNodes: Set[String]): DiscoveryConfig = {
    val discoveryConfig = etcClientConfig.getConfig("network.discovery")

    // Load static nodes from datadir/static-nodes.json if it exists
    val datadir = etcClientConfig.getString("datadir")
    val staticNodes = StaticNodesLoader.loadFromDatadir(datadir)

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
      // Public/default mode: merge bootstrap nodes and static nodes
      val combined = bootstrapNodes ++ staticNodes
      if (staticNodes.nonEmpty && bootstrapNodes.nonEmpty) {
        log.info(
          s"Merged ${staticNodes.size} static node(s) from static-nodes.json with ${bootstrapNodes.size} bootstrap node(s) from config"
        )
      } else if (staticNodes.nonEmpty) {
        log.info(s"Using ${staticNodes.size} static node(s) from static-nodes.json")
      } else if (bootstrapNodes.nonEmpty) {
        log.info(s"Using ${bootstrapNodes.size} bootstrap node(s) from config")
      }
      combined
    } else {
      // Enterprise mode: use only static nodes, ignore bootstrap nodes
      if (staticNodes.nonEmpty) {
        log.info(s"Using ${staticNodes.size} static node(s) from static-nodes.json (bootstrap nodes ignored)")
      } else {
        log.warn("Bootstrap nodes disabled but no static-nodes.json found - node may not connect to any peers")
      }
      staticNodes
    }

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
      channelCapacity = discoveryConfig.getInt("channel-capacity")
    )
  }

}
