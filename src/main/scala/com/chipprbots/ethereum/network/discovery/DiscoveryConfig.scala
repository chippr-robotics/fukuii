package com.chipprbots.ethereum.network.discovery

import scala.concurrent.duration.*

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
    channelCapacity: Int,
    // Cap on live server-side UDP channels (one per remote address); 0 = unlimited (#1519).
    maxServerChannels: Int,
    // Size of the dedicated discovery compute pool, i.e. the most cores discovery may occupy (task #52).
    computeThreads: Int,
    // EIP-1459 trees to seed peers from. Only the names live here: PeerDiscoveryManager walks them in the background
    // once the node is up, so config load never waits on DNS (H9). Empty when bootstrap nodes are disabled.
    dnsDomains: Seq[String],
    // How that walk is scheduled (per-tree deadline, queries in flight).
    dnsWalk: DnsDiscovery.WalkSettings
)

object DiscoveryConfig extends Logger:
  val DefaultComputeThreads: Int = 1

  // Whether bootstrap nodes (and DNS seeding) are used at all; controlled by modifiers like 'enterprise'.
  // Default to true if not specified to maintain backward compatibility.
  private def bootstrapNodesEnabled: Boolean =
    try System.getProperty("fukuii.network.discovery.use-bootstrap-nodes", "true").toBoolean
    catch case _: Exception => true

  def apply(
      etcClientConfig: com.typesafe.config.Config,
      bootstrapNodes: Set[String],
      dnsDiscoveryDomains: Seq[String] = Seq.empty,
      useBootstrapNodes: Boolean = bootstrapNodesEnabled
  ): DiscoveryConfig =
    val discoveryConfig = etcClientConfig.getConfig("network.discovery")

    // Load static nodes from datadir/static-nodes.json if it exists.
    // MIGRATION: redirected from discovery.StaticNodesLoader (prefix-only check) to
    // network.StaticNodesLoader (full pubkey + port validation, matching Besu reference).
    // network.StaticNodesLoader.load() returns Seq[URI]; convert to Set[String] for NodeParser.
    val datadir = etcClientConfig.getString("datadir")
    val staticNodes: Set[String] =
      com.chipprbots.ethereum.network.StaticNodesLoader.load(datadir).map(_.toString).toSet

    // Combine nodes based on configuration
    val allBootstrapNodes = if useBootstrapNodes then
      // Public/default mode: merge bootstrap nodes and static nodes. DNS-discovered nodes join later, as the
      // background walk of `dnsDomains` finds them; nothing is resolved here.
      val combined = bootstrapNodes ++ staticNodes
      val sources = Seq(
        if bootstrapNodes.nonEmpty then Some(s"${bootstrapNodes.size} config") else None,
        if staticNodes.nonEmpty then Some(s"${staticNodes.size} static") else None
      ).flatten
      if sources.nonEmpty then log.info(s"Bootstrap nodes: ${combined.size} total (${sources.mkString(", ")})")
      if dnsDiscoveryDomains.nonEmpty then
        log.info(
          s"DNS discovery: ${dnsDiscoveryDomains.size} domain(s) will be resolved in the background: " +
            dnsDiscoveryDomains.mkString(", ")
        )
      combined
    else
      // Enterprise mode: use only static nodes, ignore bootstrap nodes and DNS
      if staticNodes.nonEmpty then
        log.info(s"Using ${staticNodes.size} static node(s) from static-nodes.json (bootstrap nodes ignored)")
      else log.warn("Bootstrap nodes disabled but no static-nodes.json found - node may not connect to any peers")
      staticNodes

    val dnsDomains: Seq[String] = if useBootstrapNodes then dnsDiscoveryDomains else Seq.empty
    val defaultWalk = DnsDiscovery.WalkSettings.Default

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
      maxServerChannels =
        if discoveryConfig.hasPath("max-server-channels") then discoveryConfig.getInt("max-server-channels") else 2048,
      computeThreads =
        if discoveryConfig.hasPath("compute-threads") then discoveryConfig.getInt("compute-threads")
        else DefaultComputeThreads,
      dnsDomains = dnsDomains,
      dnsWalk = DnsDiscovery.WalkSettings(
        treeDeadline =
          if discoveryConfig.hasPath("dns-tree-deadline") then
            discoveryConfig.getDuration("dns-tree-deadline").toMillis.millis
          else defaultWalk.treeDeadline,
        concurrency =
          if discoveryConfig.hasPath("dns-concurrency") then discoveryConfig.getInt("dns-concurrency")
          else defaultWalk.concurrency
      )
    )
