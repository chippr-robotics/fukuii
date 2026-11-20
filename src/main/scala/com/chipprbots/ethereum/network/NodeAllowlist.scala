package com.chipprbots.ethereum.network

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import scala.util.Try

import io.micrometer.core.instrument.Counter

import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.utils.Hex

/** Where an allowlist entry came from. An entry that is listed through more than one source stays listed until all of
  * them drop it (spec 030 data-model, "Removal rules").
  */
enum AllowlistSource:
  case Config, File, Static, Bootnode

/** One node the allowlist admits. `enode` is set when the entry carried an address (`enode://<id>@host:port`): the node
  * is then also a dial candidate (FR-058); a bare node id admits that node inbound only.
  */
final case class AllowlistEntry(id: ByteString, enode: Option[URI], sources: Set[AllowlistSource])

/** An immutable view of the allowlist. `generation` rises each time the set of listed ids or addresses changes. */
final case class AllowlistSnapshot(entries: Map[ByteString, AllowlistEntry], generation: Long):
  def contains(nodeId: ByteString): Boolean = entries.contains(nodeId)
  def size: Int = entries.size

  /** The entries that carry an address, as enode URIs. */
  def dialable: Seq[URI] = entries.values.flatMap(_.enode).toSeq

object AllowlistSnapshot:
  val empty: AllowlistSnapshot = AllowlistSnapshot(Map.empty, 0L)

/** What one allowlist line parsed to. */
final case class ParsedAllowlistEntry(id: ByteString, enode: Option[URI])

/** The P2P node-ID allowlist of a consortium node (spec 030 FR-050..FR-055, FR-058).
  *
  * Readers (the RLPx handshake, `PeerManagerActor.connectWith`, the admin methods) call [[contains]]; it is one read of
  * an `AtomicReference`, so the handshake path makes no actor hop. When disabled, everything is permitted.
  */
final class NodeAllowlist private (val enabled: Boolean, initial: AllowlistSnapshot):
  private val ref = new AtomicReference[AllowlistSnapshot](initial)
  private val listeners = new java.util.concurrent.CopyOnWriteArrayList[AllowlistSnapshot => Unit]()
  NodeAllowlistMetrics.setSize(if enabled then initial.size.toLong else 0L)

  def snapshot: AllowlistSnapshot = ref.get()
  def generation: Long = snapshot.generation

  /** Whether `nodeId` may connect or be dialled. Always true when the allowlist is off. */
  def permits(nodeId: ByteString): Boolean = !enabled || snapshot.contains(nodeId)

  /** The FR-052 refusal for dialling `uri`, or None when it is permitted. */
  def refusalFor(uri: URI): Option[String] =
    Option(uri.getUserInfo).map(_.toLowerCase).flatMap(h => Try(ByteString(Hex.decode(h))).toOption) match
      case Some(id) if permits(id) => None
      case Some(_)                 => Some(s"node ${uri.getUserInfo.take(16)}... is not on the node allowlist")
      case None => if enabled then Some("the enode has no node id, so it cannot be on the node allowlist") else None

  /** Calls `f` with the new snapshot after each change of the listed set (not for a no-op replace). */
  def onChange(f: AllowlistSnapshot => Unit): Unit = listeners.add(f)

  /** Replaces the entries; returns whether anything changed (ids, addresses or sources). A change raises the generation
    * and tells the listeners. Called by [[NodeAllowlistStore]] only.
    */
  private[network] def replace(entries: Map[ByteString, AllowlistEntry]): Boolean = synchronized {
    val cur = ref.get()
    if cur.entries == entries then false
    else
      val next = AllowlistSnapshot(entries, cur.generation + 1)
      ref.set(next)
      NodeAllowlistMetrics.setSize(next.size.toLong)
      listeners.forEach(l => l(next))
      true
  }

  /** Counts a refused connection, for `p2p_allowlist_rejections_total{direction}`. */
  def recordRejection(inbound: Boolean): Unit =
    NodeAllowlistMetrics.rejected(inbound)

object NodeAllowlist:
  /** Permits everything; the default, and what a test of an unrelated actor passes. */
  val Disabled: NodeAllowlist = new NodeAllowlist(false, AllowlistSnapshot.empty)

  private[network] def enabled(initial: AllowlistSnapshot): NodeAllowlist = new NodeAllowlist(true, initial)

  private val NodeIdHex = "(?i)(?:0x)?([0-9a-f]{128})".r

  /** One entry: 128 hex characters (optional `0x`, any case) or an `enode://<128 hex>@host:port` URI. */
  def parseEntry(raw: String): Either[String, ParsedAllowlistEntry] =
    raw.trim match
      case NodeIdHex(hex) => Right(ParsedAllowlistEntry(ByteString(Hex.decode(hex.toLowerCase)), None))
      case s if s.regionMatches(true, 0, "enode://", 0, 8) =>
        Try(new URI(s)).toEither.left
          .map(e => s"'${s.take(40)}' is not a valid enode URI: ${e.getMessage}")
          .flatMap { uri =>
            val id = Option(uri.getUserInfo).getOrElse("")
            if !NodeIdHex.pattern.matcher(id).matches() then Left(s"'${s.take(40)}' has no 128-hex node id")
            else if uri.getHost == null || uri.getPort < 1 || uri.getPort > 65535 then
              Left(s"'${s.take(40)}' has no host:port")
            else
              val q = Option(uri.getRawQuery).fold("")("?" + _)
              val normal = new URI(s"enode://${id.toLowerCase.stripPrefix("0x")}@${uri.getHost}:${uri.getPort}$q")
              Right(ParsedAllowlistEntry(ByteString(Hex.decode(id.toLowerCase.stripPrefix("0x"))), Some(normal)))
          }
      case other => Left(s"'${other.take(40)}' is neither a 128-hex node id nor an enode:// URI")

  /** Parses every line; all must parse (a bad entry is an error naming it). */
  def parseAll(raws: Seq[String]): Either[String, Seq[ParsedAllowlistEntry]] =
    raws.foldLeft[Either[String, Vector[ParsedAllowlistEntry]]](Right(Vector.empty)) { (acc, raw) =>
      acc.flatMap(v => parseEntry(raw).map(v :+ _))
    }

  /** Merges entries from several sources: sources are unioned; the first address seen (in the order given) is kept.
    * `ownId` is dropped (the node's own id on the list is ignored).
    */
  def merge(
      parts: Seq[(AllowlistSource, Seq[ParsedAllowlistEntry])],
      ownId: Option[ByteString]
  ): Map[ByteString, AllowlistEntry] =
    parts.foldLeft(Map.empty[ByteString, AllowlistEntry]) { case (acc, (source, entries)) =>
      entries.filterNot(e => ownId.contains(e.id)).foldLeft(acc) { (m, e) =>
        val merged = m.get(e.id) match
          case Some(cur) => cur.copy(enode = cur.enode.orElse(e.enode), sources = cur.sources + source)
          case None      => AllowlistEntry(e.id, e.enode, Set(source))
        m.updated(e.id, merged)
      }
    }

/** The allowlist's meters (spec 030 data-model). Each is looked up in the registry at its use, not held: the allowlist
  * is built and checked before the node configures its metrics (StdNode's start-up check), and a meter created then
  * would sit in the default throwaway registry and never be exported, which a three-node run on dev-node found. Looking
  * a meter up again returns the one already registered.
  */
object NodeAllowlistMetrics:
  private def registry = Metrics.get().registry

  def InboundRejections: Counter = registry.counter("p2p.allowlist.rejections", "direction", "inbound")
  def OutboundRejections: Counter = registry.counter("p2p.allowlist.rejections", "direction", "outbound")
  def ReloadFailures: Counter = registry.counter("p2p.allowlist.reload.failures")

  private val size = new AtomicLong(0)
  private val inboundWindow = new AtomicLong(0)
  private val outboundWindow = new AtomicLong(0)

  /** The number of listed nodes, for `p2p_allowlist_size`. */
  def setSize(n: Long): Unit =
    size.set(n)
    register()

  /** Registers every meter on the registry now in use, so each series is exported from the start (at 0). The node calls
    * it once metrics are configured; it is idempotent.
    */
  def register(): Unit =
    val _ = (registry.gauge("p2p.allowlist.size", size), InboundRejections, OutboundRejections, ReloadFailures)

  /** Counts a refused connection: `p2p_allowlist_rejections_total{direction}`, and the next [[summary]]. */
  def rejected(inbound: Boolean): Unit =
    if inbound then
      InboundRejections.increment()
      val _ = inboundWindow.incrementAndGet()
    else
      OutboundRejections.increment()
      val _ = outboundWindow.incrementAndGet()

  /** The refusals since the last call, as the INFO line of contracts/observability.md (FR-051); None when there were
    * none. Each rejection itself is logged at DEBUG only.
    */
  def summary(): Option[String] =
    val in = inboundWindow.getAndSet(0)
    val out = outboundWindow.getAndSet(0)
    Option.when(in + out > 0)(
      s"P2P node allowlist refused $in inbound and $out outbound connection(s) since the last summary"
    )

  /** `p2p_allowlist_edits_total{op, result}` (spec 030 data-model): `ok`, `refused` (the request or the allowlist
    * refused it) or `failed` (the file could not be written).
    */
  def edit(op: AllowlistEdit, result: Either[EditError, Int]): Unit =
    val outcome = result match
      case Right(_)                                           => "ok"
      case Left(e) if e.message.startsWith("could not write") => "failed"
      case Left(_)                                            => "refused"
    registry.counter("p2p.allowlist.edits", "op", op.label, "result", outcome).increment()
