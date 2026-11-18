package com.chipprbots.ethereum.network

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config
import org.json4s.*
import org.json4s.native.JsonMethods.parse

import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger

/** Builds the [[NodeAllowlist]] from `fukuii.network.peer.node-allowlist`, the static nodes and the bootnodes, and
  * reloads its file (spec 030 FR-050, FR-058).
  *
  * The effective list is the union of `nodes`, the file, and the static nodes and bootnodes. A static node or bootnode
  * that is not on `nodes` or in the file at startup is a startup error (FR-053), so the union never widens the list the
  * operator wrote; after startup it keeps those entries listed, so a reload that drops one from the file cannot
  * recreate that contradiction.
  *
  * A reload compares the SHA-256 of the file's bytes (not its mtime, which a same-second edit leaves unchanged) with
  * the last one loaded. A file that does not parse keeps the last good list, and is counted once per distinct content
  * in `p2p_allowlist_reload_failures_total`.
  */
final class NodeAllowlistStore private (
    val allowlist: NodeAllowlist,
    file: Option[Path],
    configEntries: Seq[ParsedAllowlistEntry],
    staticEntries: Seq[ParsedAllowlistEntry],
    bootnodeEntries: Seq[ParsedAllowlistEntry],
    ownId: Option[ByteString],
    initialSha: Option[String]
) extends Logger:

  private val lock = new Object
  private var lastLoadedSha: Option[String] = initialSha
  private var lastFailedSha: Option[String] = None

  import NodeAllowlistStore.*

  /** Re-reads the file if its content changed since the last load. */
  def reload(): ReloadResult = lock.synchronized {
    file match
      case None => ReloadResult.Unchanged
      case Some(path) =>
        readFile(path, mustExist = true) match
          case Left(err) =>
            val failedSha = Try(sha256(Files.readAllBytes(path))).toOption.orElse(Some("unreadable"))
            if lastFailedSha != failedSha then
              lastFailedSha = failedSha
              NodeAllowlistMetrics.ReloadFailures.increment()
              log.error("Node allowlist reload failed: {}: {}; keeping the previous list", path, err)
            ReloadResult.Failed(err)
          case Right((_, sha)) if lastLoadedSha.contains(sha) => ReloadResult.Unchanged
          case Right((entries, sha)) =>
            lastLoadedSha = Some(sha)
            lastFailedSha = None
            val changed = allowlist.replace(merged(entries))
            if changed then log.info("Node allowlist reloaded from {}: {} node(s)", path, allowlist.snapshot.size)
            ReloadResult.Reloaded(changed)
  }

  private def merged(fileEntries: Seq[ParsedAllowlistEntry]): Map[ByteString, AllowlistEntry] =
    NodeAllowlist.merge(
      Seq(
        AllowlistSource.Config -> configEntries,
        AllowlistSource.File -> fileEntries,
        AllowlistSource.Static -> staticEntries,
        AllowlistSource.Bootnode -> bootnodeEntries
      ),
      ownId
    )

  /** Registers the reload with `scheduler`, every `interval`. Runs `reload()` on `ec`. */
  def schedule(interval: FiniteDuration, scheduler: Scheduler)(using ec: ExecutionContext): Cancellable =
    scheduler.scheduleWithFixedDelay(interval, interval) { () =>
      val _ = reload()
    }

object NodeAllowlistStore extends Logger:

  enum ReloadResult:
    case Unchanged
    case Reloaded(changed: Boolean)
    case Failed(reason: String)

  val DefaultReloadInterval: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.DurationInt(1).minute

  private def sha256(bytes: Array[Byte]): String =
    Hex.toHexString(MessageDigest.getInstance("SHA-256").digest(bytes))

  /** The entries of a JSON array of strings (the `static-nodes.json` format) and the SHA-256 of its bytes. A file that
    * does not exist is an empty list unless `mustExist`.
    */
  private[network] def readFile(
      path: Path,
      mustExist: Boolean
  ): Either[String, (Seq[ParsedAllowlistEntry], String)] =
    if !Files.exists(path) then
      if mustExist then Left("the file does not exist") else Right((Seq.empty, sha256(Array.emptyByteArray)))
    else
      Try(Files.readAllBytes(path)).toEither.left.map(e => s"cannot read: ${e.getMessage}").flatMap { bytes =>
        val text = new String(bytes, "UTF-8").trim
        val strings: Either[String, Seq[String]] =
          if text.isEmpty then Right(Seq.empty)
          else
            Try(parse(text)).toEither.left.map(e => s"invalid JSON: ${e.getMessage}").flatMap {
              case JArray(items) =>
                items.foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) {
                  case (acc, JString(s)) => acc.map(_ :+ s)
                  case (_, other)        => Left(s"an entry is not a string: ${other.toString.take(40)}")
                }
              case _ => Left("expected a JSON array of node ids or enode URIs")
            }
        strings.flatMap(NodeAllowlist.parseAll).map(entries => (entries, sha256(bytes)))
      }

  /** Whether `fukuii.network.peer.node-allowlist.enabled` is true. */
  def isEnabled(config: Config): Boolean =
    val path = "network.peer.node-allowlist.enabled"
    config.hasPath(path) && config.getBoolean(path)

  /** Builds the store from the `node-allowlist` block. `Right(None)` when it is disabled (the default).
    *
    * @param section
    *   `fukuii.network.peer.node-allowlist`; `None` when the block is absent
    * @param staticNodes
    *   `${datadir}/static-nodes.json`
    * @param bootnodes
    *   the chain's bootstrap nodes (`enode://` strings; ones that do not parse are not checked here, the discovery
    *   config reports them)
    * @param ownId
    *   this node's id, dropped from the list
    */
  def fromConfig(
      section: Option[Config],
      staticNodes: Seq[URI],
      bootnodes: Seq[String],
      ownId: Option[ByteString]
  ): Either[String, Option[NodeAllowlistStore]] =
    val enabled = section.exists(c => c.hasPath("enabled") && c.getBoolean("enabled"))
    if !enabled then Right(None)
    else
      val cfg = section.get
      val nodes = if cfg.hasPath("nodes") then cfg.getStringList("nodes").asScala.toSeq else Seq.empty
      val filePath: Option[Path] =
        if cfg.hasPath("file") && cfg.getString("file").trim.nonEmpty then Some(Paths.get(cfg.getString("file")))
        else None
      for
        configEntries <- NodeAllowlist.parseAll(nodes).left.map(e => s"node-allowlist.nodes: $e")
        loaded <- filePath match
          case Some(p) =>
            readFile(p, mustExist = false).left
              .map(e => s"node-allowlist.file $p: $e")
              .map { case (es, sha) => (es, Some(sha)) }
          case None => Right((Seq.empty[ParsedAllowlistEntry], None))
        (fileEntries, sha) = loaded
        staticEntries = staticNodes.flatMap(u => NodeAllowlist.parseEntry(u.toString).toOption)
        bootEntries = bootnodes.flatMap(b => NodeAllowlist.parseEntry(b).toOption)
        explicit = (configEntries ++ fileEntries).map(_.id).toSet
        _ <- (staticEntries.map("static node" -> _) ++ bootEntries.map("bootstrap node" -> _)).collectFirst {
          case (kind, e) if !ownId.contains(e.id) && !explicit.contains(e.id) =>
            s"$kind ${Hex.toHexString(e.id.take(8).toArray)} is not on fukuii.network.peer.node-allowlist"
        } match
          case Some(msg) => Left(msg)
          case None      => Right(())
      yield
        val store = new NodeAllowlistStore(
          NodeAllowlist.enabled(AllowlistSnapshot.empty),
          filePath,
          configEntries,
          staticEntries,
          bootEntries,
          ownId,
          sha
        )
        store.allowlist.replace(store.merged(fileEntries))
        Some(store)
