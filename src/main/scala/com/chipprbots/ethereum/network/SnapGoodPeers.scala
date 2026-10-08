package com.chipprbots.ethereum.network

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.slf4j.LoggerFactory

/** Configuration for [[SnapGoodPeers]] (`network.snap-good-peers`, spec 012). */
final case class SnapGoodPeersConfig(
    enabled: Boolean,
    maxEntries: Int,
    halfLife: FiniteDuration,
    maxFailedDials: Int,
    maxAge: FiniteDuration,
    redialInterval: FiniteDuration,
    file: Path
)

object SnapGoodPeersConfig:
  def apply(config: com.typesafe.config.Config, datadir: String): SnapGoodPeersConfig =
    val c = config.getConfig("network.snap-good-peers")
    SnapGoodPeersConfig(
      enabled = c.getBoolean("enabled"),
      maxEntries = c.getInt("max-entries"),
      halfLife = c.getDuration("half-life").toMillis.millis,
      maxFailedDials = c.getInt("max-failed-dials"),
      maxAge = c.getDuration("max-age").toMillis.millis,
      redialInterval = c.getDuration("redial-interval").toMillis.millis,
      file = java.nio.file.Paths.get(datadir).resolve(c.getString("file"))
    )

/** A small, bounded, persisted list of peers that recently served us SNAP data (spec 012).
  *
  * Why: after a restart the three geth peers that carried ~80% of storage traffic never reconnected on their own;
  * re-adding one by hand raised storage throughput ~10x. This remembers such peers (by decayed served-response score)
  * and the owner re-dials them first at startup. Unlike maintained peers they are not permanent: an entry is dropped
  * after `maxFailedDials` consecutive dial attempts without a handshake, or when it has not served for `maxAge`.
  *
  * Only outbound connections are recorded: for an inbound connection the remote TCP port is ephemeral, so the enode URI
  * would be undialable.
  *
  * File format (text, versioned): first line `fukuii-snap-good-peers v1`, then one tab-separated line per peer:
  * `nodeIdHex host port score lastServedMs failedDials`. A missing, unreadable or wrong-version file loads as empty;
  * malformed lines are skipped. Writes are atomic (temp file + rename).
  *
  * Not thread-safe: owned by the PeerManagerActor.
  */
final class SnapGoodPeers(val config: SnapGoodPeersConfig):
  import SnapGoodPeers.*

  require(config.maxEntries >= 1, s"maxEntries must be >= 1, got ${config.maxEntries}")
  require(config.halfLife > Duration.Zero, s"halfLife must be positive, got ${config.halfLife}")

  private val entries = mutable.LinkedHashMap.empty[String, Entry]

  def size: Int = entries.size

  def contains(nodeIdHex: String): Boolean = entries.contains(nodeIdHex)

  def failedDials(nodeIdHex: String): Option[Int] = entries.get(nodeIdHex).map(_.failedDials)

  def decayedScore(nodeIdHex: String, nowMs: Long): Option[Double] =
    entries.get(nodeIdHex).map(decayed(_, nowMs))

  private def decayed(e: Entry, nowMs: Long): Double =
    val elapsed = (nowMs - e.lastServedMs).max(0L).toDouble
    e.score * math.pow(0.5, elapsed / config.halfLife.toMillis.toDouble)

  /** Record that a peer served `weight` worth of SNAP data. Resets its failed-dial count. */
  def recordServed(nodeIdHex: String, host: String, port: Int, weight: Double, nowMs: Long): Unit =
    val prior = entries.get(nodeIdHex).map(decayed(_, nowMs)).getOrElse(0.0)
    entries.remove(nodeIdHex)
    entries.update(nodeIdHex, Entry(host, port, prior + weight, nowMs, 0))
    while entries.size > config.maxEntries do
      val lowest = entries.minBy { case (_, e) => decayed(e, nowMs) }._1
      entries.remove(lowest)

  /** A handshake with this peer completed (either direction). Returns true if state changed. */
  def markConnected(nodeIdHex: String): Boolean =
    entries.get(nodeIdHex) match
      case Some(e) if e.failedDials != 0 =>
        entries.update(nodeIdHex, e.copy(failedDials = 0))
        true
      case _ => false

  /** Count a dial attempt. The entry is dropped once it exceeds `maxFailedDials` without a handshake in between. */
  def recordDialAttempt(nodeIdHex: String): Unit =
    entries.get(nodeIdHex).foreach { e =>
      val attempts = e.failedDials + 1
      if attempts > config.maxFailedDials then entries.remove(nodeIdHex)
      else entries.update(nodeIdHex, e.copy(failedDials = attempts))
    }

  /** Drop entries that have not served within `maxAge`. */
  def prune(nowMs: Long): Int =
    val stale = entries.collect { case (id, e) if isStale(e, nowMs) => id }.toList
    stale.foreach(entries.remove)
    stale.size

  private def isStale(e: Entry, nowMs: Long): Boolean = nowMs - e.lastServedMs > config.maxAge.toMillis

  /** Best peers first (highest decayed score), stale ones excluded. */
  def candidates(nowMs: Long): Seq[(String, URI)] =
    entries.iterator
      .filterNot { case (_, e) => isStale(e, nowMs) }
      .toSeq
      .sortBy { case (_, e) => -decayed(e, nowMs) }
      .flatMap { case (id, e) => toUri(id, e).map(id -> _) }

  private def toUri(id: String, e: Entry): Option[URI] =
    val host = if e.host.contains(':') && !e.host.startsWith("[") then s"[${e.host}]" else e.host
    Try(new URI(s"enode://$id@$host:${e.port}")).toOption

  def render: String =
    val sb = new StringBuilder(Header).append('\n')
    entries.foreach { case (id, e) =>
      sb.append(s"$id\t${e.host}\t${e.port}\t${e.score}\t${e.lastServedMs}\t${e.failedDials}\n")
    }
    sb.toString

  /** Best-effort load; never throws. Replaces current contents. */
  def load(nowMs: Long): Unit =
    entries.clear()
    val path = config.file
    if Files.isRegularFile(path) then
      Try(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList).toEither match
        case Left(ex) => log.warn("SNAP_GOOD_PEERS: cannot read {}: {}", path, ex.getMessage)
        case Right(lines) =>
          if lines.headOption.contains(Header) then
            lines.tail.flatMap(parseLine).foreach { case (id, e) => entries.update(id, e) }
            while entries.size > config.maxEntries do
              entries.remove(entries.minBy { case (_, e) => decayed(e, nowMs) }._1)
          else log.warn("SNAP_GOOD_PEERS: ignoring {}: unrecognised header/version", path)

  /** Best-effort atomic write; a failure is logged, never thrown (the list is an optimisation, not state). */
  def save(): Unit =
    val path = config.file
    val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
    Try {
      Option(path.getParent).foreach(Files.createDirectories(_))
      Files.write(tmp, render.getBytes(StandardCharsets.UTF_8))
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.failed.foreach(ex => log.warn("SNAP_GOOD_PEERS: cannot write {}: {}", path, ex.getMessage))

object SnapGoodPeers:
  val Header = "fukuii-snap-good-peers v1"

  private val log = LoggerFactory.getLogger(classOf[SnapGoodPeers])

  final private case class Entry(host: String, port: Int, score: Double, lastServedMs: Long, failedDials: Int)

  private val NodeIdHex = "[0-9a-f]{128}".r

  private def parseLine(line: String): Option[(String, Entry)] =
    line.split('\t') match
      case Array(id, host, port, score, last, failed) if NodeIdHex.matches(id) && host.nonEmpty =>
        Try(Entry(host, port.toInt, score.toDouble, last.toLong, failed.toInt)).toOption
          .filter(e => e.port > 0 && e.port < 65536 && e.score.isFinite && e.score >= 0 && e.failedDials >= 0)
          .map(id -> _)
      case _ => None
