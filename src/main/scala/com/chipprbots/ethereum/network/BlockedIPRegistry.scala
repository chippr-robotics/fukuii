package com.chipprbots.ethereum.network

import scala.jdk.CollectionConverters._

/** Thread-safe runtime IP blocklist.
  *
  * Initialized from config `blocked-ips` at startup. Can be modified at runtime via `admin_blockIP` / `admin_unblockIP`
  * RPC methods. Checked by discovery (DiscoveryService) and P2P (RLPxConnectionHandler) layers.
  */
class BlockedIPRegistry(initialIPs: Set[String]) {
  private val blocked = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  initialIPs.foreach(blocked.add)

  def isBlocked(ip: String): Boolean = blocked.contains(ip)
  def block(ip: String): Boolean = blocked.add(ip)
  def unblock(ip: String): Boolean = blocked.remove(ip)
  def all: Set[String] = blocked.asScala.toSet
  def size: Int = blocked.size()
}
