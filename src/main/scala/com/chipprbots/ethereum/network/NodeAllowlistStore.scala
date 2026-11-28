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

  /** The file runtime edits persist to, if one is configured. */
  def filePath: Option[Path] = file

  /** A runtime edit (spec 030 FR-056, FR-057; contracts/admin-allowlist.md), all-or-nothing, under the store's lock:
    *   1. the request is checked whole (1 to 256 entries, each parsing, none twice); 2. the file is re-read if its
    *      content changed since the last load, and a file that no longer parses refuses the edit; 3. the edit is
    *      applied to the file's entries, refusing an add of an entry the file already holds, and a remove of one it
    *      does not (an entry listed only by config, a static node or a bootnode cannot be removed at runtime); 4. the
    *      new file is written to a temporary file in the same directory, flushed to disk, and renamed over the file
    *      atomically, and the directory flushed; 5. only then is the SHA-256 of what was written recorded (so the next
    *      reload sees no change) and the in-memory list replaced, which disconnects a peer no longer listed (FR-058).
    *
    * Any failure leaves the file and the list as they were, and is the answer.
    *
    * @return
    *   the number of entries the edit changed
    */
  def edit(
      op: AllowlistEdit,
      raws: Seq[String],
      write: (Path, Array[Byte]) => Unit = atomicWrite
  ): Either[EditError, Int] =
    lock.synchronized {
      val result = for
        path <- file.toRight(EditError.Refused(NoFileMessage))
        _ <- Either.cond(
          raws.nonEmpty && raws.size <= MaxEdit,
          (),
          EditError.InvalidParams("expected 1 to 256 entries")
        )
        parsed <- raws.foldLeft[Either[EditError, Vector[ParsedAllowlistEntry]]](Right(Vector.empty)) { (acc, raw) =>
          acc.flatMap(v =>
            NodeAllowlist
              .parseEntry(raw)
              .left
              .map(_ => EditError.InvalidParams(s"invalid enode or node id: ${raw.trim.take(160)}"))
              .map(v :+ _)
          )
        }
        _ <- parsed.groupBy(_.id).collectFirst { case (id, es) if es.size > 1 => id } match
          case Some(id) => Left(EditError.InvalidParams(s"duplicate entry: ${prefix(id)}"))
          case None     => Right(())
        current <- readFile(path, mustExist = false).left.map(reason =>
          EditError.Refused(s"allowlist file $path does not parse ($reason); fix it before editing")
        )
        (onDisk, diskSha) = current
        _ = if !lastLoadedSha.contains(diskSha) then
          // an edit by hand since the last load: taken first, as a reload would
          lastLoadedSha = Some(diskSha)
          allowlist.replace(merged(onDisk))
        next <- apply(op, onDisk, parsed)
        bytes = render(next)
        _ <-
          try Right(write(path, bytes))
          catch
            case scala.util.control.NonFatal(e) =>
              Left(
                EditError.Refused(
                  s"could not write $path: ${Option(e.getMessage).getOrElse(e.toString)}; nothing changed"
                )
              )
      yield
        lastLoadedSha = Some(sha256(bytes))
        lastFailedSha = None
        allowlist.replace(merged(next))
        parsed.size
      NodeAllowlistMetrics.edit(op, result)
      result
    }

  private def apply(
      op: AllowlistEdit,
      onDisk: Seq[ParsedAllowlistEntry],
      request: Seq[ParsedAllowlistEntry]
  ): Either[EditError, Seq[ParsedAllowlistEntry]] =
    val inFile = onDisk.map(_.id).toSet
    op match
      case AllowlistEdit.Add =>
        request.find(e => inFile.contains(e.id)) match
          case Some(e) => Left(EditError.Refused(s"already on the allowlist: ${prefix(e.id)}"))
          case None    => Right(onDisk ++ request)
      case AllowlistEdit.Remove =>
        request.collectFirst {
          case e if !inFile.contains(e.id) =>
            allowlist.snapshot.entries.get(e.id) match
              case None => EditError.Refused(s"not on the allowlist: ${prefix(e.id)}")
              case Some(entry) =>
                val source = entry.sources.toSeq.sortBy(_.ordinal).headOption.map(sourceName).getOrElse("config")
                EditError.Refused(s"${prefix(e.id)} comes from $source and cannot be removed at runtime")
        } match
          case Some(error) => Left(error)
          case None =>
            val removing = request.map(_.id).toSet
            Right(onDisk.filterNot(e => removing.contains(e.id)))

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

/** What a runtime edit of the allowlist does. */
enum AllowlistEdit(val label: String):
  case Add extends AllowlistEdit("add")
  case Remove extends AllowlistEdit("remove")

/** Why a runtime edit was refused: a request that is malformed (JSON-RPC -32602), or one the allowlist refuses
  * (-32000).
  */
enum EditError(val message: String):
  case InvalidParams(override val message: String) extends EditError(message)
  case Refused(override val message: String) extends EditError(message)

object NodeAllowlistStore extends Logger:

  val MaxEdit = 256
  val DisabledMessage = "node allowlist is disabled (fukuii.network.peer.node-allowlist.enabled = false)"
  val NoFileMessage = "node allowlist has no file; runtime edits need fukuii.network.peer.node-allowlist.file"

  /** The first 8 bytes of a node id, in hex: how messages and log lines name a node. */
  def prefix(id: ByteString): String = Hex.toHexString(id.take(8).toArray)

  def sourceName(s: AllowlistSource): String = s match
    case AllowlistSource.Config   => "config"
    case AllowlistSource.File     => "file"
    case AllowlistSource.Static   => "static"
    case AllowlistSource.Bootnode => "bootnode"

  /** The file's text: a JSON array of strings, an entry's enode URI where it has one, else its node id. */
  def render(entries: Seq[ParsedAllowlistEntry]): Array[Byte] =
    val lines = entries.map(e => "  \"" + e.enode.map(_.toString).getOrElse(Hex.toHexString(e.id.toArray)) + "\"")
    (if lines.isEmpty then "[]\n" else lines.mkString("[\n", ",\n", "\n]\n")).getBytes("UTF-8")

  /** Writes `bytes` to `path` so that `path` always holds the old content or the new, whole: a temporary file in the
    * same directory, flushed to disk, renamed over `path` atomically, then the directory flushed so the rename is
    * durable. The temporary file is removed on any failure, and one a killed process left is removed by the next write.
    */
  def atomicWrite(path: Path, bytes: Array[Byte]): Unit =
    val absolute = path.toAbsolutePath
    val dir = absolute.getParent
    val stem = s".${absolute.getFileName}."
    // a temporary file of a write that was killed before its rename: never the file, so dropped
    Try {
      val stale = Files.list(dir)
      try
        stale.iterator.asScala
          .filter(p => p.getFileName.toString.startsWith(stem) && p.getFileName.toString.endsWith(".tmp"))
          .foreach(Files.deleteIfExists)
      finally stale.close()
    }
    val tmp = Files.createTempFile(dir, stem, ".tmp")
    try
      val channel = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)
      try
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
      finally channel.close()
      Files.move(
        tmp,
        absolute,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      // the rename is durable once the directory is: best effort where the platform cannot open a directory
      Try {
        val d = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)
        try d.force(true)
        finally d.close()
      }
      ()
    finally
      val _ = Files.deleteIfExists(tmp)

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
