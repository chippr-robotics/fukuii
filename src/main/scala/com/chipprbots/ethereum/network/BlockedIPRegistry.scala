package com.chipprbots.ethereum.network

import scala.jdk.CollectionConverters.*

/** The operator's IP block list, thread-safe.
  *
  * Starts from `network.peer.blocked-ips` ([[BlockedIPRegistry.fromConfig]]); `admin_blockIP` / `admin_unblockIP` and
  * the TUI's peer block change it until the node restarts. Read by the node-wide blacklist
  * (`OperatorBlockingBlacklist`: RLPx dial and accept) and by discovery (B-288: UDP packets from a blocked address are
  * dropped, and none are sent to one). Addresses are held in [[BlockedIPRegistry.canonical]] form.
  */
class BlockedIPRegistry(initialIPs: Set[String]):
  private val blocked = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  initialIPs.foreach(blocked.add)

  def isBlocked(ip: String): Boolean = blocked.contains(ip)

  /** Whether `address` is blocked, by its canonical text (`getHostAddress`, as [[BlockedIPRegistry.canonical]] gives
    * it). No name is resolved.
    */
  def isBlocked(address: java.net.InetAddress): Boolean = !blocked.isEmpty && blocked.contains(address.getHostAddress)
  def block(ip: String): Boolean = blocked.add(ip)
  def unblock(ip: String): Boolean = blocked.remove(ip)
  def all: Set[String] = blocked.asScala.toSet
  def size: Int = blocked.size()

object BlockedIPRegistry:

  /** The config key of the block list the node starts with: IP literals, IPv4 or IPv6. */
  val ConfigPath: String = "network.peer.blocked-ips"

  /** The block list `config` names at [[ConfigPath]], each entry canonical; empty when the key is absent. An entry that
    * is not an IP literal is an error naming it (the node refuses to start rather than run without a block it was told
    * to keep). Host names are refused, never resolved.
    */
  def fromConfig(config: com.typesafe.config.Config): Either[String, BlockedIPRegistry] =
    if !config.hasPath(ConfigPath) then Right(new BlockedIPRegistry(Set.empty))
    else
      val entries = config.getStringList(ConfigPath).asScala.toSeq
      val bad = entries.filter(canonical(_).isEmpty)
      if bad.nonEmpty then Left(s"$ConfigPath: not an IP address: ${bad.map(e => s"'$e'").mkString(", ")}")
      else Right(new BlockedIPRegistry(entries.flatMap(canonical).toSet))

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
