package com.chipprbots.ethereum.network.discovery

import scala.concurrent.duration._

import com.chipprbots.ethereum.utils.ConfigUtils

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

object DiscoveryConfig {
  def apply(etcClientConfig: com.typesafe.config.Config, bootstrapNodes: Set[String]): DiscoveryConfig = {
    val discoveryConfig = etcClientConfig.getConfig("network.discovery")
    val parsedBootstrapNodes = NodeParser.parseNodes(bootstrapNodes)
    println(s"DEBUG: DiscoveryConfig.apply - bootstrapNodes.size = ${bootstrapNodes.size}, parsedBootstrapNodes.size = ${parsedBootstrapNodes.size}")

    DiscoveryConfig(
      discoveryEnabled = discoveryConfig.getBoolean("discovery-enabled"),
      host = ConfigUtils.getOptionalValue(discoveryConfig, _.getString, "host"),
      interface = discoveryConfig.getString("interface"),
      port = discoveryConfig.getInt("port"),
      bootstrapNodes = parsedBootstrapNodes,
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
