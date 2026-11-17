package com.chipprbots.ethereum.ephemery

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using
import scala.util.control.NonFatal

import com.chipprbots.ethereum.ephemery.EphemeryRelease.ValidRelease

/** Writes a validated release in the layout `scripts/ephemery/fetch-genesis.sh` has always written (FR-015):
  * {{{
  *   OUT/ephemery-N/testnet/             the release's members, verbatim (a Lighthouse --testnet-dir)
  *   OUT/ephemery-N/chains/              ephemery-chain.conf and ephemery-genesis.json (custom-chains-dir)
  *   OUT/ephemery-N/conf/ephemery.conf   the operator conf that points fukuii at them
  *   OUT/ephemery-N/release.env          the shell variables of the iteration
  *   OUT/ephemery-N/release.json         the metadata of contracts/release-json.md
  *   OUT/current -> ephemery-N           a symlink to the iteration just written
  *   OUT/ephemery-local.conf             operator overrides, created once and never overwritten
  * }}}
  * The iteration is staged next to its destination and moved into place, so a failure leaves nothing of it. An existing
  * iteration directory keeps its `data/` and has only the generated members replaced.
  *
  * File I/O only: no `Config`, no clock, no network.
  */
object ReleaseLayout:

  /** @param dest
    *   `OUT/ephemery-N`
    * @param env
    *   the `release.env` lines (what the command prints)
    * @param notes
    *   what was not copied, for the operator
    */
  final case class Written(dest: Path, env: String, notes: Seq[String])

  /** What the script said about the file it creates: the template of `ephemery-local.conf`. */
  private val LocalConfTemplate: String =
    """# Operator overrides for every Ephemery iteration (never overwritten by fetch-genesis.sh).
# Ports chosen not to collide with the other fukuii units / Lighthouse containers on this host
# (they use p2p 30303, RPC 8546, WS 8552, Engine API 8551, metrics 13798).
fukuii {
  network {
    server-address.port = 30313
    discovery.port = 30313
    rpc.http.port = 8556
    rpc.ws.port = 8557
    engine-api {
      enabled = true
      interface = "localhost"
      port = 8561
      # jwt-secret-path = "/path/to/ephemery/jwt.hex"
    }
  }
  metrics.port = 13799
}
"""

  /** The file operations the replacement of an iteration is made of, so a test can fail one between two others. */
  trait FileOps:
    def move(from: Path, to: Path): Unit
    def delete(tree: Path): Unit

    /** Writes a marker file (a seam for tests; a real write by default). */
    def write(file: Path, text: String): Unit = put(file, text)

  object FileOps:
    object Default extends FileOps:
      def move(from: Path, to: Path): Unit = Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
      def delete(tree: Path): Unit = deleteTree(tree)

  /** The generated members of an iteration directory; everything else in it (`data/`) is the node's. */
  private val Members = List("testnet", "chains", "conf", "release.env", "release.json")

  /** A `.stage-*` directory older than this is the remains of a run that died, and is swept. */
  private[ephemery] val StaleStage = java.time.Duration.ofHours(1)

  /** `path` with its existing prefix resolved through `toRealPath`, so a symlink cannot hide an overlap. */
  private def realOf(path: Path): Path =
    val abs = path.toAbsolutePath.normalize
    var existing = abs
    while existing != null && !Files.exists(existing) do existing = existing.getParent
    if existing == null then abs else existing.toRealPath().resolve(existing.relativize(abs).toString).normalize

  /** Writes `release` under `out`, copying the members of `source` into `testnet/`.
    *
    * An existing iteration is replaced all or nothing: its old members are moved aside inside the stage, the new ones
    * moved in, and on any failure the old ones are moved back; they are deleted only after success. Once the iteration
    * is in place, a failure writing `current` or `ephemery-local.conf` is a warning in `notes`, not a failure.
    */
  def write(
      release: ValidRelease,
      source: Path,
      out: Path,
      metadata: ReleaseMetadata,
      ops: FileOps = FileOps.Default
  ): Either[String, Written] =
    val root = out.toAbsolutePath.normalize
    val from = source.toAbsolutePath.normalize
    val dest = root.resolve(release.release)
    val current = root.resolve("current")
    // The copy of `from` into the stage precedes any delete, and a stage inside `from` would copy itself: refuse an
    // `out` that is, or lies under, `from`, by real path.
    if realOf(root).startsWith(realOf(from)) then Left(s"--out $root is inside --from-dir $from")
    else if Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current) then
      Left(s"$current exists and is not a symlink; move it away first")
    else
      var stage: Option[Path] = None
      var keepStage = false
      var lock: Option[(FileChannel, FileLock)] = None
      val cleanup = mutable.ArrayBuffer.empty[String]
      val result: Either[String, Written] =
        try
          Files.createDirectories(root)
          lock = acquire(root)
          if lock.isEmpty then throw new AlreadyWriting(s"another ephemery-fetch is writing to $root")
          val notes = mutable.ArrayBuffer.empty[String]
          refuseKept(root)
          notes ++= recoverCrashed(root, ops)
          sweepStale(root, metadata.producedAt)
          val staged = Files.createTempDirectory(root, ".stage-")
          stage = Some(staged)
          // createTempDirectory makes it 0700, and the iteration directory inherits that mode.
          Try(Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwxr-xr-x")))
          notes ++= copyMembers(from, staged.resolve("testnet"))
          val env = releaseEnv(release, root, dest)
          put(staged.resolve("chains").resolve(EphemeryRelease.ChainConfFileName), release.chainConf)
          put(staged.resolve("chains").resolve(EphemeryRelease.GenesisFileName), release.encodedGenesis)
          put(staged.resolve("conf").resolve("ephemery.conf"), operatorConf(release, root, dest))
          put(staged.resolve("release.env"), "# GENERATED by scripts/ephemery/fetch-genesis.sh\n" + env)
          put(staged.resolve("release.json"), metadata.toJson)
          if Files.exists(dest) then replace(staged, dest, ops)
          else ops.move(staged, dest)
          // The iteration is in place. What follows cannot undo it, so it only warns.
          Try(point(current, release.release, ops)).failed.foreach(e =>
            notes += s"WARNING: cannot point $current at ${release.release}: ${e.getMessage}"
          )
          Try(createOnce(root.resolve("ephemery-local.conf"), LocalConfTemplate)).failed
            .foreach(e => notes += s"WARNING: cannot create ephemery-local.conf: ${e.getMessage}")
          Right(Written(dest, env, notes.toSeq))
        catch
          case e: RestoreFailed =>
            keepStage = true
            Left(s"cannot write $dest: ${e.getMessage} (the previous copy is kept in ${stage.get})")
          case e: AlreadyWriting => Left(e.getMessage)
          case NonFatal(e)       => Left(s"cannot write $dest: ${e.getMessage}")
        finally
          // A failing cleanup must neither throw away the result nor keep the lock.
          try
            if !keepStage then
              stage.foreach(st =>
                Try(cleanStage(st, ops)).failed.foreach(e =>
                  cleanup += s"WARNING: could not remove the stage $st: ${e.getMessage}"
                )
              )
          finally
            lock.foreach { case (channel, l) =>
              Try(l.release())
              Try(channel.close())
            }
      result.map(w => w.copy(notes = w.notes ++ cleanup))

  /** The old members could not be put back after a failed replacement: they stay in the stage. */
  final private class RestoreFailed(msg: String, cause: Throwable) extends IOException(msg, cause)

  /** Moves the old members to `staged/.old`, the new ones into `dest`; on a failure puts the old ones back. */
  private def replace(staged: Path, dest: Path, ops: FileOps): Unit =
    // `.target` first, then `.old`: a stage that has `.old` always has `.target`, so a crash between the two leaves a
    // stage without `.old`, which nothing needs and the sweep removes.
    ops.write(staged.resolve(TargetMarker), dest.toString)
    val old = Files.createDirectories(staged.resolve(".old"))
    val movedAside = mutable.ArrayBuffer.empty[String]
    val movedIn = mutable.ArrayBuffer.empty[String]
    try
      Members.filter(m => Files.exists(dest.resolve(m), LinkOption.NOFOLLOW_LINKS)).foreach { m =>
        ops.move(dest.resolve(m), old.resolve(m))
        movedAside += m
      }
      // Every old member is aside: from here on the new copy is being moved in, and a rollback must remove what it added.
      ops.write(staged.resolve(AsideDoneMarker), "aside")
      Members.foreach { m =>
        ops.move(staged.resolve(m), dest.resolve(m))
        movedIn += m
      }
      // Done: a leftover stage that has this (a crash before the stage was deleted) must not be rolled back. A failed
      // write is a failed replace, which the rollback below undoes: `.old` is never deleted without `.done` present.
      ops.write(staged.resolve(DoneMarker), "done")
    catch
      case NonFatal(e) =>
        val restored = Try {
          // First: a failed `.done` write may have left a partial file, and a rollback interrupted after it would be taken
          // for a finished replace, whose `.old` recovery deletes.
          ops.delete(staged.resolve(DoneMarker))
          movedIn.foreach(m => ops.delete(dest.resolve(m)))
          // Before anything is moved back: a rerun after a partial restore must not take the members already restored
          // for ones the new copy added (see recoverCrashed).
          ops.delete(staged.resolve(AsideDoneMarker))
          movedAside.foreach(m => ops.move(old.resolve(m), dest.resolve(m)))
        }
        if restored.isFailure then
          // Keep the stage, which holds the old copy.
          throw new RestoreFailed(
            s"${e.getMessage}; and the old members could not be restored: ${restored.failed.get.getMessage}",
            e
          )
        throw e

  private val TargetMarker = ".target"
  private val DoneMarker = ".done"
  private val AsideDoneMarker = ".aside-done"

  final private class AlreadyWriting(msg: String) extends IOException(msg)

  /** Deletes a stage, `.old` FIRST while `.done` and `.target` still exist: a crash in the middle then leaves a stage
    * that recovery only deletes (`.done`), or one whose `.old` is partly gone and whose `.done` is still there. A stage
    * with `.old` and without `.done` and `.target` can therefore only be a replace that was still in progress, and
    * never a finished one whose cleanup was interrupted.
    */
  private def cleanStage(stage: Path, ops: FileOps): Unit =
    ops.delete(stage.resolve(".old"))
    ops.delete(stage)

  private[ephemery] val KeptPrefix = ".stage-kept-"

  private[ephemery] def isStage(p: Path): Boolean =
    val n = p.getFileName.toString
    n.startsWith(".stage-") && !n.startsWith(KeptPrefix)

  /** The refusal for a stage left by an interrupted write: what it holds, where it belongs, what to do. */
  private def refusal(kept: Path, renameFailed: Boolean = false): AlreadyWriting =
    val out = kept.getParent
    val iteration = Try(Paths.get(read(kept.resolve(TargetMarker)).trim).getFileName.toString).toOption
    val where = iteration.fold(s"an unknown iteration under $out")(n => s"$out/$n")
    val failed = if renameFailed then " (renaming it to .stage-kept-* failed, so it is still here)" else ""
    new AlreadyWriting(
      s"an earlier interrupted write left $kept$failed; its .old holds the previous members of $where; move them " +
        s"back into that directory, or delete $kept, then run again"
    )

  /** A stage kept by an earlier run that could not recover it: neither recovery nor the sweep touches it, and nothing
    * is written until an operator has looked at it.
    */
  private def refuseKept(root: Path): Unit =
    val kept = Try {
      val entries = Files.list(root)
      try entries.iterator().asScala.filter(_.getFileName.toString.startsWith(KeptPrefix)).toList
      finally entries.close()
    }.getOrElse(Nil)
    kept.headOption.foreach(k => throw refusal(k))

  /** An exclusive lock on `OUT/.lock` for the whole write; `None` when another writer holds it. */
  private def acquire(root: Path): Option[(FileChannel, FileLock)] =
    val channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    val locked =
      try Option(channel.tryLock())
      catch
        case _: OverlappingFileLockException => None // held by this JVM
        case NonFatal(e) =>
          Try(channel.close())
          throw e
    locked match
      case Some(l) => Some((channel, l))
      case None =>
        channel.close()
        None

  /** After a crash in the middle of a replace, a stage holds the old members in `.old` and names its iteration
    * directory in `.target`. Roll each old member back into that directory (replacing what the new copy put there),
    * then delete the stage. A stage marked `.done` only needs deleting. A stage that cannot be rolled back (no
    * `.target`, or a failure) is renamed `.stage-kept-*` and the run is refused ([[refuseKept]]): it may hold the only
    * old copy, and the sweep never removes a stage that has `.old`.
    */
  private def recoverCrashed(root: Path, ops: FileOps): Seq[String] =
    val stages = Try {
      val entries = Files.list(root)
      try entries.iterator().asScala.filter(isStage).toList
      finally entries.close()
    }.getOrElse(Nil)
    stages.filter(st => Files.isDirectory(st.resolve(".old"))).flatMap { st =>
      val outcome = Try {
        if Files.exists(st.resolve(DoneMarker)) then
          cleanStage(st, ops)
          s"WARNING: removed the finished leftover stage ${st.getFileName}"
        else
          // only the names this layout writes: a crafted `.old/data` must never delete the target's `data/`
          val members = Using.resource(Files.list(st.resolve(".old"))) { entries =>
            entries.iterator().asScala.map(_.getFileName.toString).filter(Members.contains).toList
          }
          val asideDone = Files.exists(st.resolve(AsideDoneMarker))
          if members.isEmpty && !asideDone then
            // Nothing had moved: the target is as it was, and there is nothing to put back.
            cleanStage(st, ops)
            s"WARNING: removed the leftover stage ${st.getFileName} of a write that had moved nothing"
          else
            val target = Paths.get(read(st.resolve(TargetMarker)).trim)
            if target.getParent != root || !target.getFileName.toString.matches("ephemery-[0-9]+") then
              throw new IOException(s"${st.getFileName} names a target outside $root: $target")
            // Every old member was aside, so the new copy was being moved in: what it added is not the old copy's.
            if asideDone then
              Members.filterNot(members.contains).foreach(m => ops.delete(target.resolve(m)))
              // Before the restore loop, so recovery is idempotent: a crash partway through the loop leaves `.old` with
              // only the members still to move back, and the next run (no marker) deletes nothing but their new copies.
              ops.delete(st.resolve(AsideDoneMarker))
            members.foreach { m =>
              ops.delete(target.resolve(m))
              ops.move(st.resolve(".old").resolve(m), target.resolve(m))
            }
            cleanStage(st, ops)
            s"WARNING: a previous run died while replacing ${target.getFileName}; restored ${members.sorted.mkString(", ")}"
      }
      outcome.failed.toOption.fold(Seq(outcome.get)) { _ =>
        // It may hold the only old copy, and a later run must not roll it over a newer one: set it aside under a name
        // nothing recovers or sweeps, and refuse to write until an operator has looked.
        val kept = st.resolveSibling(KeptPrefix + st.getFileName.toString.stripPrefix(".stage-"))
        throw Try(Files.move(st, kept, StandardCopyOption.ATOMIC_MOVE))
          .fold(_ => refusal(st, renameFailed = true), _ => refusal(kept))
      }
    }

  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** Removes `.stage-*` directories left by runs that died, when older than an hour. Best effort. */
  private def sweepStale(root: Path, now: java.time.Instant): Unit =
    Try {
      val entries = Files.list(root)
      try
        entries.iterator().asScala.filter(isStage).foreach { p =>
          // never a stage that still holds an old copy
          if !Files.exists(p.resolve(".old")) && Files
              .getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
              .toInstant
              .isBefore(now.minus(StaleStage))
          then Try(deleteTree(p))
        }
      finally entries.close()
    }

  /** The `release.env` variables (without the leading comment). */
  def releaseEnv(release: ValidRelease, out: Path, dest: Path): String =
    val lines = List(
      s"ITERATION=\"${release.iteration}\"",
      s"RELEASE=\"${release.release}\"",
      s"CHAIN_ID=\"${release.chainId}\"",
      s"GENESIS_TIMESTAMP=\"${release.genesisTimestamp}\"",
      s"RESET_TIME=\"${release.resetTime}\"",
      s"GENESIS_HASH=\"${release.params.publishedHash}\"",
      s"OUT_DIR=\"$out\"",
      s"TESTNET_DIR=\"${dest.resolve("testnet")}\"",
      s"CHAINS_DIR=\"${dest.resolve("chains")}\"",
      s"FUKUII_CONF_DIR=\"${dest.resolve("conf")}\""
    ) ++ Option.when(release.bootEnrs.nonEmpty)(s"BOOTNODE_ENR_LIST=\"${release.bootEnrs.mkString(",")}\"")
    lines.mkString("", "\n", "\n")

  private def operatorConf(release: ValidRelease, out: Path, dest: Path): String =
    s"""# GENERATED by scripts/ephemery/fetch-genesis.sh for ${release.release} — do not edit; put overrides in
# ${out.resolve("ephemery-local.conf")}. Launch: FUKUII_CONF_DIR=${dest.resolve("conf")} fukuii ephemery
include classpath("conf/ephemery.conf")

fukuii {
  blockchains.custom-chains-dir = "${dest.resolve("chains")}"
  # One datadir per iteration: a datadir holding another iteration's genesis is refused at startup.
  datadir = "${dest.resolve("data")}"
}

include file("${out.resolve("ephemery-local.conf")}")
"""

  /** Creates `file` with `text` unless it exists already (an operator's file is never overwritten). */
  private def createOnce(file: Path, text: String): Unit =
    try Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
    catch case _: FileAlreadyExistsException => ()

  private[ephemery] def put(file: Path, text: String): Unit =
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))

  /** `link -> target`, replacing an existing symlink atomically (`ln -sfnT`). */
  private def point(link: Path, target: String, ops: FileOps): Unit =
    val tmp = link.resolveSibling(s".current-${System.nanoTime()}")
    Files.createSymbolicLink(tmp, java.nio.file.Paths.get(target))
    try ops.move(tmp, link)
    catch
      case NonFatal(e) =>
        Try(Files.deleteIfExists(tmp))
        throw e

  /** Copies the directory `from` to `to`: directories and regular files. A symlink is skipped, and said so. */
  private def copyMembers(from: Path, to: Path): Seq[String] =
    val notes = mutable.ArrayBuffer.empty[String]
    Files.walkFileTree(
      from,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
          Files.createDirectories(to.resolve(from.relativize(dir).toString))
          FileVisitResult.CONTINUE
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
          if attrs.isRegularFile then
            Files.copy(file, to.resolve(from.relativize(file).toString), StandardCopyOption.COPY_ATTRIBUTES)
          else notes += s"not copied into testnet/ (not a regular file): ${from.relativize(file)}"
          FileVisitResult.CONTINUE
    )
    notes.toSeq

  private def deleteTree(path: Path): Unit =
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      Files.walkFileTree(
        path,
        new SimpleFileVisitor[Path]:
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            Files.delete(file)
            FileVisitResult.CONTINUE
          override def postVisitDirectory(dir: Path, e: IOException): FileVisitResult =
            Files.delete(dir)
            FileVisitResult.CONTINUE
      )
