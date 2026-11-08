package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.util.Hashtable
import java.util.concurrent.atomic.AtomicInteger
import javax.naming.Context
import javax.naming.directory.InitialDirContext

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.SyncIO
import cats.effect.std.Semaphore
import cats.effect.unsafe.IORuntime
import cats.syntax.parallel.*

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Try

import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkIdValidationResult.Connect
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.utils.BlockchainConfig
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
object DnsDiscovery extends Logger:

  /** Maximum number of ENR records to resolve from a single DNS tree. Prevents runaway resolution if the tree is very
    * large.
    */
  private val MaxRecords = 500

  /** Maximum tree depth to prevent infinite loops from circular references. */
  private val MaxDepth = 16

  /** DNS lookup timeout in milliseconds. */
  private val DnsTimeoutMs = "5000"

  /** How a tree walk is scheduled. None of it changes what is accepted, only when and how fast.
    *
    * @param treeDeadline
    *   wall-clock budget for one tree. When it runs out the walk is cancelled, the enodes found so far stay delivered,
    *   and the tree is reported as timed out. A query still on the wire is interrupted, but JNDI does not abandon a
    *   datagram wait on interrupt: it returns when the attempt in progress times out and skips its retries, so a lost
    *   query holds the walk for at most one attempt timeout (5 s) past the deadline instead of its full 15 s (measured
    *   on JDK 25: 15.06 s uninterrupted, 5.04 s interrupted at 1 s).
    * @param concurrency
    *   most TXT queries in flight at once, per tree. This is also the DNS request rate limit: a walk can never ask the
    *   resolver for more than this many answers at a time.
    */
  final case class WalkSettings(treeDeadline: FiniteDuration, concurrency: Int):
    require(treeDeadline > Duration.Zero, s"DNS tree deadline must be positive, got $treeDeadline")
    require(concurrency > 0, s"DNS walk concurrency must be positive, got $concurrency")

  object WalkSettings:
    /** ETC mainnet's tree is about 300 queries. At 8 in flight that is seconds on a healthy resolver; the deadline only
      * matters when queries are being lost (a lost JNDI query costs 15 s), where 120 s still lets a 300-query walk
      * finish with several percent of them lost.
      */
    val Default: WalkSettings = WalkSettings(treeDeadline = 120.seconds, concurrency = 8)

  /** The answer to one TXT query. A lost query is not the same as a missing one: a missing name is a definite answer
    * (NXDOMAIN, no TXT), a lost query is the resolver failing to answer at all (timeout, SERVFAIL, socket error).
    */
  enum TxtResult:
    case Found(record: String)
    case NotFound
    case Lost(reason: String)

  /** One blocking TXT query. The walk runs these on the blocking pool, never on a compute or actor thread. A found
    * record is the whole entry: a TXT record stored as several strings (EIP-1459 splits any entry over 255 bytes) comes
    * back concatenated with nothing in between, as go-ethereum's `net.Resolver.LookupTXT` returns it.
    */
  trait TxtLookup:
    def query(name: String): TxtResult

  /** Undo JNDI's rendering of a multi-string TXT record, which is its strings joined by a single space.
    *
    * An EIP-1459 branch or ENR entry over 255 bytes is stored as several strings, and ETC and Mordor branches (13
    * children, 365 bytes) always are, so the space lands in the middle of a child hash: `...,YTGWSYWQNQLKUQTLMTPRORVK
    * 4Q,...` for `...,YTGWSYWQNQLKUQTLMTPRORVK4Q,...`. The walk then asked for a name that does not exist, the resolver
    * never answered, and the query cost its full 15 s timeout and dropped that child (measured on Mordor, where the
    * repaired hash resolves in 44 ms). Branch, ENR and link entries have no whitespace in their grammar, so removing it
    * restores the entry. The root has spaces between its fields and is returned as it came.
    */
  private[discovery] def concatenateTxtStrings(joined: String): String =
    if joined.startsWith("enrtree-root:") then joined else joined.filterNot(_.isWhitespace)

  /** DNS TXT record lookup using JNDI (JDK built-in, no external dependency).
    *
    * A fresh context per query: a JNDI context is not safe for concurrent use, and an interrupted query (the tree
    * deadline) must not leave a shared UDP socket closed under the next one.
    */
  object JndiTxtLookup extends TxtLookup:
    override def query(name: String): TxtResult =
      try
        val ctx = createDnsContext()
        try
          val attrs = ctx.getAttributes(name, Array("TXT"))
          val txtAttr = attrs.get("TXT")
          if txtAttr != null && txtAttr.size > 0 then
            // TXT records may be returned with quotes
            val value = txtAttr.get(0).toString.stripPrefix("\"").stripSuffix("\"")
            // DNS may split long TXT records into multiple strings — concatenate
            TxtResult.Found(concatenateTxtStrings(value.replace("\" \"", "")))
          else TxtResult.NotFound
        finally
          try ctx.close()
          catch case _: javax.naming.NamingException => () // the answer is already in hand
      catch
        case _: javax.naming.NameNotFoundException => TxtResult.NotFound
        case ex: javax.naming.NamingException =>
          log.debug("DNS_DISCOVERY: DNS lookup failed for {}: {}", name, ex.getMessage)
          TxtResult.Lost(Option(ex.getMessage).getOrElse(ex.getClass.getSimpleName))

  /** How a tree walk ended. */
  enum WalkOutcome:
    case Completed
    case TimedOut
    case Failed(reason: String)

  /** What a tree walk did, whatever way it ended. `queries` counts answered or lost queries, not ones cancelled in
    * flight by the deadline. A lost branch query drops its whole subtree, so `lost > 0` means the tree may be short.
    */
  final case class WalkSummary(
      domain: String,
      outcome: WalkOutcome,
      resolved: Int,
      skipped: Int,
      queries: Int,
      lost: Int,
      elapsed: FiniteDuration
  )

  /** Walk one EIP-1459 tree and hand each enode to `onEnode` as soon as its ENR leaf is parsed.
    *
    * This is the scheduling, not the trust: records are parsed and filtered exactly as before ([[parseRoot]],
    * [[parseEnrToEnode]], `forkIdFilter`), only the order queries are issued in and the time they may take are new.
    * Queries run on the blocking pool, up to `settings.concurrency` at once; sibling branches are walked in parallel.
    * `onEnode` may be called concurrently and more than once for the same tree, never twice for the same enode.
    *
    * Never fails and never blocks the caller: whatever goes wrong ends the walk with a [[WalkSummary]] (and a WARN),
    * after the enodes already delivered.
    *
    * @param forkIdFilter
    *   Optional EIP-2124 fork ID filter. When provided, ENR entries carrying an `eth` key with an incompatible fork ID
    *   are skipped (no outbound dial attempted). ENRs without an `eth` key are accepted optimistically — the
    *   wire-protocol STATUS handshake makes the authoritative check. Mirrors the logic in [[ForkIdTag]] used by discv4
    *   routing.
    */
  def walkTree(
      domain: String,
      lookup: TxtLookup,
      settings: WalkSettings,
      forkIdFilter: Option[EnrForkIdFilter],
      onEnode: String => IO[Unit]
  ): IO[WalkSummary] =
    for
      startedAt <- IO.monotonic
      permits <- Semaphore[IO](settings.concurrency.toLong)
      walk = new TreeWalk(domain, lookup, permits, forkIdFilter, onEnode)
      outcome <- walk.run
        .timeoutTo(settings.treeDeadline, IO.pure(WalkOutcome.TimedOut))
        .handleError(ex => WalkOutcome.Failed(s"unexpected error: $ex"))
      elapsed <- IO.monotonic.map(_ - startedAt)
      summary = walk.summary(outcome, elapsed)
      _ <- IO(logSummary(summary, settings))
    yield summary

  /** Walk every domain in parallel (they are independent trees), turning each enode found into a [[Node]] for
    * `onNodes`. Completes once every tree has ended; the summaries are in `domains` order.
    */
  def seed(
      domains: Seq[String],
      lookup: TxtLookup,
      settings: WalkSettings,
      forkIdFilter: Option[EnrForkIdFilter],
      onNodes: Set[Node] => IO[Unit]
  ): IO[List[WalkSummary]] =
    domains.toList.parTraverse { domain =>
      walkTree(
        domain,
        lookup,
        settings,
        forkIdFilter,
        enode =>
          IO(NodeParser.parseNodes(Set(enode))).flatMap(nodes => if nodes.isEmpty then IO.unit else onNodes(nodes))
      )
    }

  /** Blocking convenience over [[walkTree]] for tests and tools: the enode URLs of one tree, empty on any failure.
    * Never call this from node start-up, a tree walk takes as long as DNS takes.
    */
  def resolveEnodes(
      domain: String,
      forkIdFilter: Option[EnrForkIdFilter] = None,
      lookup: TxtLookup = JndiTxtLookup,
      settings: WalkSettings = WalkSettings.Default
  ): Set[String] =
    val found = mutable.Set.empty[String]
    val _ = walkTree(domain, lookup, settings, forkIdFilter, enode => IO(found.synchronized(found += enode)).void)
      .unsafeRunSync()(using IORuntime.global)
    found.synchronized(found.toSet)

  private def logSummary(summary: WalkSummary, settings: WalkSettings): Unit =
    val WalkSummary(domain, outcome, resolved, skipped, queries, lost, elapsed) = summary
    val skippedNote = if skipped > 0 then s", skipped $skipped on fork-ID mismatch" else ""
    val counts = s"resolved $resolved enode(s)$skippedNote, lost $lost of $queries queries, ${elapsed.toMillis} ms"
    outcome match
      case WalkOutcome.Completed if lost == 0 =>
        log.info(
          s"DNS_DISCOVERY: Resolved $resolved enode(s) from $domain$skippedNote ($queries queries, ${elapsed.toMillis} ms)"
        )
      case WalkOutcome.Completed =>
        log.warn(s"DNS_DISCOVERY: Tree $domain is incomplete, a lost branch query drops its subtree: $counts")
      case WalkOutcome.TimedOut =>
        log.warn(s"DNS_DISCOVERY: Tree $domain hit its ${settings.treeDeadline} deadline and was abandoned: $counts")
      case WalkOutcome.Failed(reason) =>
        log.warn(s"DNS_DISCOVERY: Failed to resolve DNS tree from $domain ($reason): $counts")

  /** One tree walk's state. The sets are touched by many fibers at once, so every access goes through `synchronized`.
    */
  final private class TreeWalk(
      domain: String,
      lookup: TxtLookup,
      permits: Semaphore[IO],
      forkIdFilter: Option[EnrForkIdFilter],
      onEnode: String => IO[Unit]
  ):
    private val visited = mutable.Set.empty[String]
    private val enodes = mutable.Set.empty[String]
    private val skipped = mutable.Set.empty[String] // tracking for log only
    private val queries = new AtomicInteger(0)
    private val lost = new AtomicInteger(0)

    def run: IO[WalkOutcome] =
      IO(log.info("DNS_DISCOVERY: Resolving peers from DNS tree: {}", domain)) *> query(domain).flatMap {
        case TxtResult.Found(rootRecord) if rootRecord.startsWith("enrtree-root:") =>
          parseRoot(rootRecord) match
            case Some((enrRoot, _, _)) => walk(enrRoot, depth = 0).as(WalkOutcome.Completed)
            case None                  => IO.pure(WalkOutcome.Failed(s"cannot parse root record: $rootRecord"))
        case TxtResult.Found(record) => IO.pure(WalkOutcome.Failed(s"unexpected root record: $record"))
        case TxtResult.NotFound      => IO.pure(WalkOutcome.Failed("no TXT record found"))
        case TxtResult.Lost(reason)  => IO.pure(WalkOutcome.Failed(s"root query lost: $reason"))
      }

    def summary(outcome: WalkOutcome, elapsed: FiniteDuration): WalkSummary =
      synchronized(WalkSummary(domain, outcome, enodes.size, skipped.size, queries.get, lost.get, elapsed))

    /** One query, holding a permit only while it is on the wire, so a branch never holds one while it waits for its
      * children (that would deadlock a small `concurrency`).
      */
    private def query(name: String): IO[TxtResult] =
      permits.permit
        .surround(IO.interruptible(lookup.query(name)))
        .handleError(ex => TxtResult.Lost(ex.toString))
        .flatTap { result =>
          IO {
            queries.incrementAndGet()
            if result.isInstanceOf[TxtResult.Lost] then lost.incrementAndGet()
          }
        }

    /** True the first time `hash` is seen, so a hash reachable twice (or in a cycle) is walked once. */
    private def firstVisit(hash: String): Boolean = synchronized(visited.add(hash))

    /** True if `enode` is new and the tree's record cap still has room. */
    private def accept(enode: String): Boolean = synchronized(enodes.size < MaxRecords && enodes.add(enode))

    private def atCap: Boolean = synchronized(enodes.size >= MaxRecords)

    private def skip(subdomain: String): Unit = synchronized { val _ = skipped.add(subdomain) }

    /** Resolve one tree node, siblings in parallel. */
    private def walk(hash: String, depth: Int): IO[Unit] =
      if depth <= MaxDepth && !atCap && firstVisit(hash) then
        val subdomain = s"$hash.$domain"
        query(subdomain).flatMap {
          case TxtResult.Found(record) if record.startsWith("enrtree-branch:") =>
            val children = record.stripPrefix("enrtree-branch:").split(",").map(_.trim).filter(_.nonEmpty)
            children.toList.parTraverse_(child => walk(child, depth + 1))

          case TxtResult.Found(record) if record.startsWith("enr:") =>
            parseEnrToEnode(record, forkIdFilter) match
              case Right(enode) =>
                // A sink that fails must not take the rest of the tree down with it.
                if accept(enode) then
                  onEnode(enode).handleErrorWith(ex =>
                    IO(log.warn("DNS_DISCOVERY: Could not hand over {}: {}", enode, ex.toString))
                  )
                else IO.unit
              case Left(reason) if reason.startsWith("fork-id mismatch") => IO(skip(subdomain))
              case Left(_) => IO(log.debug("DNS_DISCOVERY: Failed to parse ENR leaf at {}", subdomain))

          case TxtResult.Found(record) if record.startsWith("enrtree://") =>
            // Link to another tree — skip for now (could recurse into other domains)
            IO(log.debug("DNS_DISCOVERY: Skipping link record at {}: {}", subdomain, record))

          case TxtResult.Found(record) =>
            IO(log.debug("DNS_DISCOVERY: Unknown record type at {}: {}", subdomain, record.take(50)))

          case TxtResult.NotFound =>
            IO(log.debug("DNS_DISCOVERY: No TXT record at {}", subdomain))

          case TxtResult.Lost(reason) =>
            IO(log.debug("DNS_DISCOVERY: DNS query lost at {}: {}", subdomain, reason))
        }
      else IO.unit

  /** Parse root record: `enrtree-root:v1 e=<hash> l=<hash> seq=<n> sig=<sig>` */
  private def parseRoot(record: String): Option[(String, String, Long)] =
    // Remove prefix
    val content = record.stripPrefix("enrtree-root:")
    val parts = content.split("\\s+").map(_.trim).filter(_.nonEmpty)

    var enrRoot: Option[String] = None
    var linkRoot: Option[String] = None
    var seq: Long = 0

    parts.foreach { part =>
      if part.startsWith("e=") then enrRoot = Some(part.stripPrefix("e="))
      else if part.startsWith("l=") then linkRoot = Some(part.stripPrefix("l="))
      else if part.startsWith("seq=") then seq = Try(part.stripPrefix("seq=").toLong).getOrElse(0L)
    }

    enrRoot.map(e => (e, linkRoot.getOrElse(""), seq))

  /** Parse an ENR record into an enode:// URL.
    *
    * ENR format (EIP-778): RLP([signature, seq, k1, v1, k2, v2, ...]) Base64 encoded (URL-safe, no padding) after
    * "enr:" prefix.
    *
    * @param forkIdFilter
    *   When provided, the ENR's `eth` key (EIP-2124) is validated against the local fork ID. Returns `Left("fork-id
    *   mismatch: ...")` to signal a clean reject (not a parse failure) so callers can log it differently.
    */
  private[discovery] def parseEnrToEnode(
      enrRecord: String,
      forkIdFilter: Option[EnrForkIdFilter] = None
  ): Either[String, String] =
    try
      val base64Data = enrRecord.stripPrefix("enr:")
      val bytes = decodeBase64Url(base64Data)
      if bytes.isEmpty then Left("empty ENR payload")
      else
        val decoded = rawDecode(bytes)
        decoded match
          case list: RLPList if list.items.size >= 4 =>
            // items: [signature, seq, key1, val1, key2, val2, ...]
            val kvPairs = list.items.drop(2) // skip signature and seq
            val attrs = parseKVPairs(kvPairs)

            // Fork-ID filter — reject incompatible ENRs before they ever become dial candidates.
            // ENRs without an `eth` key are accepted optimistically (handshake STATUS will decide).
            val rejectedForkId = forkIdFilter match
              case Some(filter) =>
                attrs
                  .get("eth")
                  .flatMap { ethBytes =>
                    try Some(ForkIdTag.decodeEthEntry(ethBytes))
                    catch case _: Exception => None
                  }
                  .filter(id => !filter.accepts(id))
              case None => None

            rejectedForkId match
              case Some(id) => Left(s"fork-id mismatch: $id")
              case None =>
                val ipv4Opt = attrs.get("ip").flatMap(parseIp)
                val tcpv4Opt = attrs.get("tcp").map(parsePort)
                val udpv4Opt = attrs.get("udp").map(parsePort)
                val pubkeyOpt = attrs.get("secp256k1")

                // IPv6 fallback (EIP-778: ip6/tcp6/udp6 keys) — prefer IPv4 when both present
                val (ipOpt, tcpOpt, udpOpt) = ipv4Opt match
                  case Some(_) => (ipv4Opt, tcpv4Opt, udpv4Opt)
                  case None =>
                    (
                      attrs.get("ip6").flatMap(parseIp),
                      attrs.get("tcp6").map(parsePort),
                      attrs.get("udp6").map(parsePort)
                    )

                (ipOpt, tcpOpt, pubkeyOpt) match
                  case (Some(ip), Some(tcpPort), Some(compressedKey)) if tcpPort > 0 =>
                    // Decompress secp256k1 public key (33 bytes compressed -> 64 bytes uncompressed, no prefix)
                    decompressToNodeId(compressedKey)
                      .map { id =>
                        val udpPort = udpOpt.getOrElse(tcpPort)
                        if udpPort != tcpPort then s"enode://$id@${formatIp(ip)}:$tcpPort?discport=$udpPort"
                        else s"enode://$id@${formatIp(ip)}:$tcpPort"
                      }
                      .toRight("invalid secp256k1 pubkey")

                  case _ =>
                    Left("missing ip/tcp/secp256k1 in ENR")

          case _ => Left("ENR not an RLP list with >=4 items")
    catch
      case ex: Exception =>
        Left(s"ENR parse error: ${ex.getMessage}")

  /** Parse RLP key-value pairs into a map. Keys are UTF-8 strings, values are raw byte arrays. */
  private def parseKVPairs(items: Seq[rlp.RLPEncodeable]): Map[String, Array[Byte]] =
    val pairs = mutable.Map.empty[String, Array[Byte]]
    var i = 0
    while i + 1 < items.size do
      (items(i), items(i + 1)) match
        case (RLPValue(keyBytes), RLPValue(valBytes)) =>
          val key = new String(keyBytes, "UTF-8")
          pairs(key) = valBytes
        case _ => // skip non-value pairs
      i += 2
    pairs.toMap

  private def parseIp(bytes: Array[Byte]): Option[InetAddress] =
    if bytes.length == 4 || bytes.length == 16 then Try(InetAddress.getByAddress(bytes)).toOption
    else None

  private def parsePort(bytes: Array[Byte]): Int =
    if bytes.isEmpty then 0
    else
      // Big-endian integer
      bytes.foldLeft(0)((acc, b) => (acc << 8) | (b & 0xff))

  private def formatIp(addr: InetAddress): String =
    val host = addr.getHostAddress
    // IPv6 addresses need brackets
    if host.contains(":") then s"[$host]" else host

  /** Decompress a 33-byte compressed secp256k1 public key to a 64-char hex node ID. Node ID =
    * keccak256(uncompressed_pubkey_64_bytes) — wait, no. In devp2p, node ID IS the 64-byte uncompressed public key
    * (without prefix), hex-encoded.
    */
  private def decompressToNodeId(compressedKey: Array[Byte]): Option[String] =
    try
      if compressedKey.length != 33 then None
      else
        val point = crypto.curve.getCurve.decodePoint(compressedKey)
        val key = new ECPublicKeyParameters(point, crypto.curve)
        // Uncompressed encoding is 65 bytes: 0x04 prefix + 64 bytes (x || y)
        // Node ID is the 64 bytes without prefix
        val uncompressed = key.getQ.getEncoded(false).drop(1) // drop 0x04 prefix
        if uncompressed.length != 64 then None
        else Some(Hex.toHexString(uncompressed))
    catch case _: Exception => None

  /** URL-safe Base64 decode (RFC 4648 §5, no padding). */
  private def decodeBase64Url(input: String): Array[Byte] =
    try
      // Add padding if needed
      val padded = input.length % 4 match
        case 2 => input + "=="
        case 3 => input + "="
        case _ => input
      // Convert URL-safe to standard Base64
      val standard = padded.replace('-', '+').replace('_', '/')
      java.util.Base64.getDecoder.decode(standard)
    catch case _: Exception => Array.empty

  /** ENR fork-ID filter used during DNS discovery to skip nodes on incompatible chains. Mirrors the logic in
    * [[ForkIdTag]] used by discv4 routing-table filtering, but applies at DNS-resolution time so the rejected ENRs
    * never become dial candidates in the first place.
    *
    * Without this, sepolia's `all.sepolia.ethdisco.net` (or equivalent) tree could include mis-tagged entries, and on
    * shared infrastructure we observed peers from BSC (networkId 56), ETH mainnet (1), Core Chain (1116), etc. burning
    * our outbound dial slots before the wire-protocol STATUS check could reject them. See PR #1249.
    */
  class EnrForkIdFilter(
      genesisHash: () => ByteString,
      genesisTimestamp: () => Long,
      blockchainConfig: BlockchainConfig,
      currentBestBlock: () => BigInt,
      currentBestBlockTimestamp: () => Long
  ):
    def accepts(remoteForkId: ForkId): Boolean =
      import ForkIdValidator.syncIoLogger
      ForkIdValidator
        .validatePeer[SyncIO](genesisHash(), genesisTimestamp(), blockchainConfig)(
          currentBestBlock(),
          currentBestBlockTimestamp(),
          remoteForkId
        )
        .unsafeRunSync() match
        case Connect => true
        case _       => false

  /** Create a JNDI DNS context with reasonable timeouts. */
  private def createDnsContext(): InitialDirContext =
    val env = new Hashtable[String, String]()
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
    env.put("com.sun.jndi.dns.timeout.initial", DnsTimeoutMs)
    env.put("com.sun.jndi.dns.timeout.retries", "2")
    new InitialDirContext(env)
