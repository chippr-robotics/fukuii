package com.chipprbots.scalanet.discovery.ethereum.v5

import scala.concurrent.duration._
import com.chipprbots.scalanet.discovery.ethereum.Node

/** Configuration for Discovery v5 protocol */
case class DiscoveryConfig(
  // Request timeout for individual RPC calls
  requestTimeout: FiniteDuration,
  
  // Timeout for collecting multiple NODES responses
  findNodeTimeout: FiniteDuration,
  
  // Maximum number of nodes to return in a single NODES message
  maxNodesPerMessage: Int,
  
  // Concurrency parameter for recursive lookups
  lookupParallelism: Int,
  
  // Kademlia bucket size (k-value)
  kademliaBucketSize: Int,
  
  // How often to refresh random buckets
  bucketRefreshInterval: FiniteDuration,
  
  // How often to perform random lookups for discovery
  discoveryInterval: FiniteDuration,
  
  // Session timeout - how long to keep inactive sessions
  sessionTimeout: FiniteDuration,
  
  // Maximum number of concurrent sessions
  maxSessions: Int,
  
  // Bootstrap nodes to connect to initially
  bootstrapNodes: Set[Node],
  
  // Enable topic-based discovery (optional feature)
  enableTopicDiscovery: Boolean,
  
  // Maximum topics to advertise
  maxAdvertisedTopics: Int,
  
  // Topic advertisement refresh interval
  topicRefreshInterval: FiniteDuration,
  
  // Limit the number of IPs from the same subnet (/24 for IPv4, /56 for IPv6)
  subnetLimitPrefixLength: Int,
  
  // Limit per bucket
  subnetLimitForBucket: Int,
  
  // Limit for entire table
  subnetLimitForTable: Int,
  
  // Maximum pending handshakes at once
  maxPendingHandshakes: Int,
  
  // Handshake timeout
  handshakeTimeout: FiniteDuration
)

object DiscoveryConfig {
  
  /** Default configuration based on the specification recommendations */
  val default: DiscoveryConfig = DiscoveryConfig(
    requestTimeout = 3.seconds,
    findNodeTimeout = 10.seconds,
    maxNodesPerMessage = 16,
    lookupParallelism = 3,
    kademliaBucketSize = 16,
    bucketRefreshInterval = 1.hour,
    discoveryInterval = 30.seconds,
    sessionTimeout = 12.hours,
    maxSessions = 1000,
    bootstrapNodes = Set.empty,
    enableTopicDiscovery = false,
    maxAdvertisedTopics = 10,
    topicRefreshInterval = 5.minutes,
    subnetLimitPrefixLength = 24,
    subnetLimitForBucket = 2,
    subnetLimitForTable = 10,
    maxPendingHandshakes = 100,
    handshakeTimeout = 5.seconds
  )
  
  /** Create a minimal config for testing */
  def minimal(bootstrapNodes: Set[Node] = Set.empty): DiscoveryConfig = 
    default.copy(
      discoveryInterval = 10.seconds,
      bucketRefreshInterval = 10.minutes,
      bootstrapNodes = bootstrapNodes
    )
}
