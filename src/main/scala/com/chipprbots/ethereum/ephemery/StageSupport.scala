package com.chipprbots.ethereum.ephemery

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.ephemery.ReleaseLayout.FileOps

/** The stage-and-rename helpers [[ReleaseStore]] uses: a stage sweep, the root's lock, an atomic symlink, a directory
  * copy and a tree delete.
  *
  * These are small DUPLICATES of the private helpers in [[ReleaseLayout]], on purpose: ReleaseLayout's crash recovery
  * (`.target` before `.old`, `.aside-done`, `.done`, `.stage-kept-*`) was reviewed and fixed in slice S2 and is left
  * exactly as it is. [[ReleaseStore]] never replaces an existing entry (it places one with a single rename only when
  * the target is absent), so it has no `.old` and needs no recovery; its sweep still never removes a stage holding
  * `.old` or a `.stage-kept-*`, so it cannot disturb a stage ReleaseLayout left in the same directory.
  *
  * File I/O only: no `Config`, no clock of its own, no network.
  */
private[ephemery] object StageSupport:

  // These three are ReleaseLayout's own, referenced and not copied, so the two writers cannot disagree on what a stage
  // is, how old one must be to be swept, or which are kept.

  /** A `.stage-*` directory older than this is the remains of a run that died, and is swept. */
  val StaleStage: java.time.Duration = ReleaseLayout.StaleStage

  /** A stage set aside by ReleaseLayout because it may hold the only old copy: never swept. */
  val KeptPrefix: String = ReleaseLayout.KeptPrefix

  def isStage(p: Path): Boolean = ReleaseLayout.isStage(p)

  private val TempLink = ".current-"

  /** An exclusive lock on `root/.lock` for the whole write; `None` when another writer holds it. */
  def acquire(root: Path): Option[(FileChannel, FileLock)] =
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

  /** Releases what [[acquire]] returned. */
  def release(lock: Option[(FileChannel, FileLock)]): Unit =
    lock.foreach { case (channel, l) =>
      Try(l.release())
      Try(channel.close())
    }

  /** Removes `.stage-*` directories left by runs that died, when older than an hour before `now`. Best effort. */
  def sweepStale(root: Path, now: java.time.Instant): Unit =
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
    // The temporary link of an interrupted `point`: a symlink, older than a stage may be.
    Try {
      val entries = Files.list(root)
      try
        entries.iterator().asScala.filter(p => p.getFileName.toString.startsWith(TempLink)).foreach { p =>
          if Files.isSymbolicLink(p) && Files
              .getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS)
              .toInstant
              .isBefore(now.minus(StaleStage))
          then Try(Files.deleteIfExists(p))
        }
      finally entries.close()
    }

  /** Writes `text` and forces it to disk before returning. */
  def writeDurable(file: Path, text: String): Unit =
    Files.createDirectories(file.getParent)
    val channel =
      FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    try
      channel.write(java.nio.ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      channel.force(true)
    finally channel.close()

  /** Forces a directory's entries to disk, where the file system lets a directory be opened; best effort elsewhere. */
  def syncDir(dir: Path): Unit =
    Try {
      val channel = FileChannel.open(dir, StandardOpenOption.READ)
      try channel.force(true)
      finally channel.close()
    }

  /** `link -> target`, replacing an existing symlink atomically (`ln -sfnT`). */
  def point(link: Path, target: String, ops: FileOps): Unit =
    val tmp = link.resolveSibling(s".current-${System.nanoTime()}")
    Files.createSymbolicLink(tmp, java.nio.file.Paths.get(target))
    try ops.move(tmp, link)
    catch
      case NonFatal(e) =>
        Try(Files.deleteIfExists(tmp))
        throw e

  /** Copies the directory `from` to `to`: directories and regular files. A symlink is skipped, and said so. */
  def copyMembers(from: Path, to: Path): Seq[String] =
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

  def deleteTree(path: Path): Unit =
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
