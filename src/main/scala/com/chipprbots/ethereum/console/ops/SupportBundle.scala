package com.chipprbots.ethereum.console.ops

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** `bundle` (spec 029 FR-054, FR-058, contracts/actions.md): a directory of text files under the data directory for
  * whoever is asked to look at this node: the build, the effective configuration with every secret hidden, the
  * dashboard's log lines, the alerts and the health checks, every view as text, the database's figures, a thread dump,
  * and the memory and garbage-collector figures.
  *
  * It holds no key, no keystore, no JWT secret, no state and no chain data. It does hold peer addresses and this node's
  * id, and says so before it is written. It is written for the node's owner alone (owner-only permissions where the
  * file system has them) and goes nowhere: nothing here sends anything.
  */
object SupportBundle:

  /** What goes in, each read when the bundle is made.
    *
    * @param build
    *   the build's name, version and commit, a line each
    * @param config
    *   the effective configuration's rows, already through [[Redaction]] ([[ConfigView.rows]])
    * @param logs
    *   the dashboard's log lines, oldest first
    * @param alerts
    *   the alerts on the alert row now, and each health check with its state and since when
    * @param views
    *   every view of the dashboard as text, by its name
    * @param database
    *   the database's figures as the Storage view has them
    */
  final case class Inputs(
      build: () => Seq[String],
      config: () => Seq[String],
      logs: () => Seq[String],
      alerts: () => Seq[String],
      views: () => Seq[(String, Seq[String])],
      database: () => Seq[String]
  )

  private val Stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

  /** A line of log with the credentials taken out of any URL in it: a log line is not a configuration value, and a URL
    * that was logged whole is the one way a secret known today could be in one.
    */
  private[ops] def scrubbed(line: String): String = Redaction.scrubbed(line)

  private def threads(): Seq[String] =
    ManagementFactory.getThreadMXBean
      .dumpAllThreads(false, false)
      .toSeq
      .sortBy(_.getThreadName)
      .flatMap { t =>
        s"\"${t.getThreadName}\" ${t.getThreadState}" +: t.getStackTrace.toSeq
          .take(40)
          .map(frame => s"    at $frame") :+ ""
      }

  private def memory(): Seq[String] =
    val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    val nonHeap = ManagementFactory.getMemoryMXBean.getNonHeapMemoryUsage
    Seq(
      s"heap used ${heap.getUsed} committed ${heap.getCommitted} max ${heap.getMax}",
      s"non-heap used ${nonHeap.getUsed} committed ${nonHeap.getCommitted}"
    ) ++ ManagementFactory.getMemoryPoolMXBeans.asScala.toSeq.map(p =>
      s"pool ${p.getName}: used ${p.getUsage.getUsed} max ${p.getUsage.getMax}"
    ) ++ ManagementFactory.getGarbageCollectorMXBeans.asScala.toSeq.map(g =>
      s"collector ${g.getName}: ${g.getCollectionCount} collections, ${g.getCollectionTime} ms"
    ) ++ Seq(
      s"threads ${ManagementFactory.getThreadMXBean.getThreadCount}, peak ${ManagementFactory.getThreadMXBean.getPeakThreadCount}",
      s"uptime ${ManagementFactory.getRuntimeMXBean.getUptime} ms",
      s"jvm ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}",
      s"os ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime.availableProcessors()} processors"
    )

  /** The files of a bundle, by name, as text. */
  def files(inputs: Inputs, at: Instant): Seq[(String, String)] =
    def text(lines: Seq[String]): String = lines.mkString("", "\n", "\n")
    Seq(
      "README.txt" -> text(
        Seq(
          s"fukuii support bundle, written ${at.toString} by the --tui dashboard of the node it describes.",
          "It holds no key, no keystore, no JWT secret, no state and no chain data.",
          "It holds peer addresses and this node's id.",
          "Every configuration value whose key holds a secret is hidden; a URL is written without its credentials."
        )
      ),
      "build.txt" -> text(inputs.build()),
      "config.txt" -> text(inputs.config()),
      "logs.txt" -> text(inputs.logs().map(scrubbed)),
      "alerts.txt" -> text(inputs.alerts()),
      "database.txt" -> text(inputs.database()),
      "threads.txt" -> text(threads()),
      "memory.txt" -> text(memory())
    ) ++ inputs.views().map((name, rows) => s"views/$name.txt" -> text(rows))

  /** The directory a bundle made at `at` goes to, under the data directory. */
  def directory(dataDir: Path, at: Instant): Path = dataDir.resolve("support").resolve(s"bundle-${Stamp.format(at)}")

  /** Writes `contents` under `dir`, for the owner alone where the file system has such permissions: each directory
    * and file is made with them, so nothing is readable by anyone else at any moment. Returns whether it has them: a
    * file system without them (or one that would not say) gets the files with its defaults, and the result says so.
    * A file that is already there is not written over: two bundles do not share a directory.
    */
  private[ops] def write(dir: Path, contents: Seq[(String, String)]): Boolean =
    val dirOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    val fileOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    var ownerOnly = true
    def directory(path: Path): Unit =
      if !Files.isDirectory(path) then
        Option(path.getParent).foreach(parent => if !Files.isDirectory(parent) then Files.createDirectories(parent))
        try
          val _ = Files.createDirectory(path, dirOnly)
        catch
          case _: UnsupportedOperationException =>
            ownerOnly = false
            val _ = Files.createDirectory(path)
    directory(dir.getParent)
    directory(dir)
    contents.foreach { (name, body) =>
      val file = dir.resolve(name)
      if file.getParent != dir then directory(file.getParent)
      try
        val _ = Files.createFile(file, fileOnly)
      catch
        case _: UnsupportedOperationException =>
          ownerOnly = false
          val _ = Files.createFile(file)
      val _ = Files.write(file, body.getBytes(StandardCharsets.UTF_8))
    }
    ownerOnly

  private def size(bytes: Int): String =
    if bytes >= 1024 * 1024 then f"${bytes / 1048576.0}%.1f MiB"
    else if bytes >= 1024 then s"${bytes / 1024} KiB"
    else s"$bytes B"

  /** The files a bundle holds, as its preview names them: the preview reads nothing of the node. */
  val Holds: String =
    "README.txt, build.txt, config.txt, logs.txt, alerts.txt, database.txt, threads.txt, memory.txt, " +
      "and each view of the dashboard as views/<name>.txt"

  def handler(inputs: Inputs, dataDir: () => Option[Path], now: () => Instant): ActionHandler =
    ActionHandler(
      _ =>
        dataDir() match
          case None => Preview(Vector.empty, Some("this node has no data directory to write under"))
          case Some(dir) =>
            Preview(
              Vector(
                s"Writes a directory of text files, ${dir.resolve("support")}/bundle-<the time it is written>:",
                s"$Holds.",
                "It holds peer addresses and this node's id. It holds no key, no JWT secret, no state and no chain data.",
                "Secrets in the configuration are hidden. The files are readable by this node's user only.",
                "It goes nowhere: sending it to anyone is up to you."
              )
            )
      ,
      (_, progress) =>
        dataDir().toRight("this node has no data directory to write under").flatMap { dir =>
          val at = now()
          val target = directory(dir, at)
          progress("reading the node")
          val contents = files(inputs, at)
          val bytes = contents.map(_._2.getBytes(StandardCharsets.UTF_8).length).sum
          progress(s"writing ${contents.size} files")
          Try(write(target, contents)).toEither.left
            .map {
              case _: java.nio.file.FileAlreadyExistsException =>
                s"a bundle was already written to $target this second: nothing was written over"
              case e => s"could not write $target: ${Option(e.getMessage).getOrElse(e.toString)}"
            }
            .map(ownerOnly =>
              s"${contents.size} files (${size(bytes)}) written to $target" +
                (if ownerOnly then ""
                 else "; this file system has no owner-only permissions: the files have its defaults")
            )
        }
    )
