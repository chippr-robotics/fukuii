package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.InitialDirContext

import scala.collection.mutable
import scala.util.Try
import scala.util.Success
import scala.util.Failure

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.utils.Logger

/** EIP-1459: DNS-based peer discovery.
  *
  * Resolves Ethereum Node Records (ENR) from a DNS tree, converting them to enode:// URLs. This is the same mechanism
  * core-geth uses to find Mordor peers via `all.mordor.blockd.info`.
  *
  * DNS tree format:
  *   - Root: `enrtree-root:v1 e=<hash> l=<hash> seq=<n> sig=<base64>`
  *   - Branch: `enrtree-branch:<hash1>,<hash2>,...`
  *   - ENR leaf: `enr:<base64-encoded-ENR>`
  *   - Link leaf: `enrtree://<pubkey>@<domain>`
  */
object DnsDiscovery extends Logger {

  /** Maximum number of ENR records to resolve from a single DNS tree. Prevents runaway resolution if the tree is very
    * large.
    */
  private val MaxRecords = 500

  /** Maximum tree depth to prevent infinite loops from circular references. */
  private val MaxDepth = 16

  /** DNS lookup timeout in milliseconds. */
  private val DnsTimeoutMs = "5000"

  /** Resolve enode URLs from a DNS discovery domain.
    *
    * @param domain
    *   DNS domain hosting the ENR tree (e.g., "all.mordor.blockd.info")
    * @return
    *   set of enode:// URL strings, empty on any failure
    */
  def resolveEnodes(domain: String): Set[String] = {
    log.info("DNS_DISCOVERY: Resolving peers from DNS tree: {}", domain)

    try {
      val ctx = createDnsContext()
      try {
        val root = lookupTxt(ctx, domain)
        root match {
          case Some(rootRecord) if rootRecord.startsWith("enrtree-root:") =>
            val parsed = parseRoot(rootRecord)
            parsed match {
              case Some((enrRoot, _, _)) =>
                val visited = mutable.Set.empty[String]
                val enodes = mutable.Set.empty[String]
                resolveTree(ctx, domain, enrRoot, visited, enodes, depth = 0)
                log.info("DNS_DISCOVERY: Resolved {} enode(s) from {}", enodes.size, domain)
                enodes.toSet

              case None =>
                log.warn("DNS_DISCOVERY: Failed to parse root record from {}: {}", domain, rootRecord)
                Set.empty
            }

          case Some(record) =>
            log.warn("DNS_DISCOVERY: Unexpected root record from {}: {}", domain, record)
            Set.empty

          case None =>
            log.warn("DNS_DISCOVERY: No TXT record found for {}", domain)
            Set.empty
        }
      } finally ctx.close()
    } catch {
      case ex: Exception =>
        log.warn("DNS_DISCOVERY: Failed to resolve DNS tree from {}: {}", domain, ex.getMessage)
        Set.empty
    }
  }

  /** Parse root record: `enrtree-root:v1 e=<hash> l=<hash> seq=<n> sig=<sig>` */
  private def parseRoot(record: String): Option[(String, String, Long)] = {
    // Remove prefix
    val content = record.stripPrefix("enrtree-root:")
    val parts = content.split("\\s+").map(_.trim).filter(_.nonEmpty)

    var enrRoot: Option[String] = None
    var linkRoot: Option[String] = None
    var seq: Long = 0

    parts.foreach { part =>
      if (part.startsWith("e=")) enrRoot = Some(part.stripPrefix("e="))
      else if (part.startsWith("l=")) linkRoot = Some(part.stripPrefix("l="))
      else if (part.startsWith("seq=")) seq = Try(part.stripPrefix("seq=").toLong).getOrElse(0L)
    }

    enrRoot.map(e => (e, linkRoot.getOrElse(""), seq))
  }

  /** Recursively resolve a DNS tree node. */
  private def resolveTree(
      ctx: InitialDirContext,
      domain: String,
      hash: String,
      visited: mutable.Set[String],
      enodes: mutable.Set[String],
      depth: Int
  ): Unit = {
    if (depth > MaxDepth || enodes.size >= MaxRecords || visited.contains(hash)) return
    visited += hash

    val subdomain = s"$hash.$domain"
    lookupTxt(ctx, subdomain) match {
      case Some(record) if record.startsWith("enrtree-branch:") =>
        val children = record.stripPrefix("enrtree-branch:").split(",").map(_.trim).filter(_.nonEmpty)
        children.foreach { child =>
          resolveTree(ctx, domain, child, visited, enodes, depth + 1)
        }

      case Some(record) if record.startsWith("enr:") =>
        parseEnrToEnode(record) match {
          case Some(enode) => enodes += enode
          case None        => log.debug("DNS_DISCOVERY: Failed to parse ENR leaf at {}", subdomain)
        }

      case Some(record) if record.startsWith("enrtree://") =>
        // Link to another tree — skip for now (could recurse into other domains)
        log.debug("DNS_DISCOVERY: Skipping link record at {}: {}", subdomain, record)

      case Some(record) =>
        log.debug("DNS_DISCOVERY: Unknown record type at {}: {}", subdomain, record.take(50))

      case None =>
        log.debug("DNS_DISCOVERY: No TXT record at {}", subdomain)
    }
  }

  /** Parse an ENR record into an enode:// URL.
    *
    * ENR format (EIP-778): RLP([signature, seq, k1, v1, k2, v2, ...]) Base64 encoded (URL-safe, no padding) after
    * "enr:" prefix.
    */
  private[discovery] def parseEnrToEnode(enrRecord: String): Option[String] =
    try {
      val base64Data = enrRecord.stripPrefix("enr:")
      val bytes = decodeBase64Url(base64Data)
      if (bytes.isEmpty) return None

      val decoded = rawDecode(bytes)
      decoded match {
        case list: RLPList if list.items.size >= 4 =>
          // items: [signature, seq, key1, val1, key2, val2, ...]
          val kvPairs = list.items.drop(2) // skip signature and seq
          val attrs = parseKVPairs(kvPairs)

          val ipv4Opt = attrs.get("ip").flatMap(parseIp)
          val tcpv4Opt = attrs.get("tcp").map(parsePort)
          val udpv4Opt = attrs.get("udp").map(parsePort)
          val pubkeyOpt = attrs.get("secp256k1")

          // IPv6 fallback (EIP-778: ip6/tcp6/udp6 keys) — prefer IPv4 when both present
          val (ipOpt, tcpOpt, udpOpt) = ipv4Opt match {
            case Some(_) => (ipv4Opt, tcpv4Opt, udpv4Opt)
            case None =>
              (attrs.get("ip6").flatMap(parseIp), attrs.get("tcp6").map(parsePort), attrs.get("udp6").map(parsePort))
          }

          (ipOpt, tcpOpt, pubkeyOpt) match {
            case (Some(ip), Some(tcpPort), Some(compressedKey)) if tcpPort > 0 =>
              // Decompress secp256k1 public key (33 bytes compressed -> 64 bytes uncompressed, no prefix)
              val nodeId = decompressToNodeId(compressedKey)
              nodeId.map { id =>
                val udpPort = udpOpt.getOrElse(tcpPort)
                if (udpPort != tcpPort)
                  s"enode://$id@${formatIp(ip)}:$tcpPort?discport=$udpPort"
                else
                  s"enode://$id@${formatIp(ip)}:$tcpPort"
              }

            case _ =>
              None // Missing required fields
          }

        case _ => None
      }
    } catch {
      case ex: Exception =>
        log.debug("DNS_DISCOVERY: ENR parse error: {}", ex.getMessage)
        None
    }

  /** Parse RLP key-value pairs into a map. Keys are UTF-8 strings, values are raw byte arrays. */
  private def parseKVPairs(items: Seq[rlp.RLPEncodeable]): Map[String, Array[Byte]] = {
    val pairs = mutable.Map.empty[String, Array[Byte]]
    var i = 0
    while (i + 1 < items.size) {
      (items(i), items(i + 1)) match {
        case (RLPValue(keyBytes), RLPValue(valBytes)) =>
          val key = new String(keyBytes, "UTF-8")
          pairs(key) = valBytes
        case _ => // skip non-value pairs
      }
      i += 2
    }
    pairs.toMap
  }

  private def parseIp(bytes: Array[Byte]): Option[InetAddress] =
    if (bytes.length == 4 || bytes.length == 16)
      Try(InetAddress.getByAddress(bytes)).toOption
    else
      None

  private def parsePort(bytes: Array[Byte]): Int = {
    if (bytes.isEmpty) return 0
    // Big-endian integer
    bytes.foldLeft(0)((acc, b) => (acc << 8) | (b & 0xff))
  }

  private def formatIp(addr: InetAddress): String = {
    val host = addr.getHostAddress
    // IPv6 addresses need brackets
    if (host.contains(":")) s"[$host]" else host
  }

  /** Decompress a 33-byte compressed secp256k1 public key to a 64-char hex node ID. Node ID =
    * keccak256(uncompressed_pubkey_64_bytes) — wait, no. In devp2p, node ID IS the 64-byte uncompressed public key
    * (without prefix), hex-encoded.
    */
  private def decompressToNodeId(compressedKey: Array[Byte]): Option[String] =
    try {
      if (compressedKey.length != 33) return None
      val point = crypto.curve.getCurve.decodePoint(compressedKey)
      val key = new ECPublicKeyParameters(point, crypto.curve)
      // Uncompressed encoding is 65 bytes: 0x04 prefix + 64 bytes (x || y)
      // Node ID is the 64 bytes without prefix
      val uncompressed = key.getQ.getEncoded(false).drop(1) // drop 0x04 prefix
      if (uncompressed.length != 64) return None
      Some(Hex.toHexString(uncompressed))
    } catch {
      case _: Exception => None
    }

  /** URL-safe Base64 decode (RFC 4648 §5, no padding). */
  private def decodeBase64Url(input: String): Array[Byte] =
    try {
      // Add padding if needed
      val padded = input.length % 4 match {
        case 2 => input + "=="
        case 3 => input + "="
        case _ => input
      }
      // Convert URL-safe to standard Base64
      val standard = padded.replace('-', '+').replace('_', '/')
      java.util.Base64.getDecoder.decode(standard)
    } catch {
      case _: Exception => Array.empty
    }

  /** DNS TXT record lookup using JNDI (JDK built-in, no external dependency). */
  private def lookupTxt(ctx: InitialDirContext, domain: String): Option[String] =
    try {
      val attrs = ctx.getAttributes(domain, Array("TXT"))
      val txtAttr = attrs.get("TXT")
      if (txtAttr != null && txtAttr.size > 0) {
        // TXT records may be returned with quotes
        val value = txtAttr.get(0).toString.stripPrefix("\"").stripSuffix("\"")
        // DNS may split long TXT records into multiple strings — concatenate
        Some(value.replace("\" \"", ""))
      } else {
        None
      }
    } catch {
      case _: javax.naming.NameNotFoundException => None
      case ex: javax.naming.NamingException =>
        log.debug("DNS_DISCOVERY: DNS lookup failed for {}: {}", domain, ex.getMessage)
        None
    }

  /** Create a JNDI DNS context with reasonable timeouts. */
  private def createDnsContext(): InitialDirContext = {
    val env = new Hashtable[String, String]()
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
    env.put("com.sun.jndi.dns.timeout.initial", DnsTimeoutMs)
    env.put("com.sun.jndi.dns.timeout.retries", "2")
    new InitialDirContext(env)
  }
}
