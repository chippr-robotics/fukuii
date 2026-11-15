package com.chipprbots.ethereum.network

import scala.jdk.CollectionConverters.*

/** Thread-safe runtime IP blocklist.
  *
  * Initialized from config `blocked-ips` at startup. Can be modified at runtime via `admin_blockIP` / `admin_unblockIP`
  * RPC methods. Designed to be checked by discovery (DiscoveryService) and P2P (RLPxConnectionHandler) layers — wired
  * in feat/network-autoblocker.
  */
class BlockedIPRegistry(initialIPs: Set[String]):
  private val blocked = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  initialIPs.foreach(blocked.add)

  def isBlocked(ip: String): Boolean = blocked.contains(ip)
  def block(ip: String): Boolean = blocked.add(ip)
  def unblock(ip: String): Boolean = blocked.remove(ip)
  def all: Set[String] = blocked.asScala.toSet
  def size: Int = blocked.size()

object BlockedIPRegistry:

  /** The canonical text of an IP address literal (an IPv4 dotted quad, or an IPv6 address as `getHostAddress` writes
    * it, which is how the peer manager sees a remote address), or None for anything else, a host name included. The
    * list holds addresses in this form, and whoever asks it about an address asks in this form. Never resolves a name:
    * IPv4 is parsed by [[ExternalIPDetector.parseIpv4Literal]], IPv6 is checked by the URL parser before the bracketed
    * literal is handed to `InetAddress`.
    */
  def canonical(raw: String): Option[String] =
    val text = raw.trim
    if text.isEmpty then None
    else
      ExternalIPDetector
        .parseIpv4Literal(text)
        .map(_.getHostAddress)
        .orElse {
          val looksLikeIpv6 = text.contains(':') && text.forall(c => c.isLetterOrDigit || c == ':' || c == '.')
          if !looksLikeIpv6 then None
          else
            try
              // The URL parser checks the IPv6 syntax; the bracketed literal is then parsed by InetAddress without a lookup.
              Option(new java.net.URI(s"//[$text]").getHost).map(java.net.InetAddress.getByName(_).getHostAddress)
            catch case _: Exception => None
        }
