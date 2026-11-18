package com.chipprbots.ethereum.testing

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Temporary directories a spec owns, and the removal of them (B-305, B-187).
  *
  * A spec makes its directories with [[create]], registers what must be closed before they go (a RocksDB data source)
  * with [[closeBeforeDelete]], and calls [[deleteAll]] from `afterAll`. A JVM shutdown hook does the same, so a spec
  * that is killed or forgets still leaves nothing behind once the test JVM exits. Each spec holds its own instance:
  * suites that run in parallel in one JVM never delete one another's directories.
  */
final class TempDirs:
  private val dirs = new ConcurrentLinkedQueue[Path]()
  private val closers = new ConcurrentLinkedQueue[() => Unit]()

  Runtime.getRuntime.addShutdownHook(new Thread(() => deleteAll(), "temp-dirs-cleanup"))

  /** A new temporary directory, removed by [[deleteAll]]. */
  def create(prefix: String): Path =
    val dir = Files.createTempDirectory(prefix)
    dirs.add(dir)
    dir

  /** Runs `close` once before the directories are deleted. */
  def closeBeforeDelete(close: () => Unit): Unit =
    val _ = closers.add(close)

  /** Closes what was registered, then deletes every directory made. Idempotent; a failure to delete one is skipped. */
  def deleteAll(): Unit =
    closers.asScala.toList.foreach(close =>
      try close()
      catch case NonFatal(_) => ()
    )
    closers.clear()
    dirs.asScala.toList.foreach(TempDirs.deleteRecursively)
    dirs.clear()

object TempDirs:
  /** Deletes `path` and everything under it. A path that is not there is nothing to do. */
  def deleteRecursively(path: Path): Unit =
    val file = path.toFile
    Option(file.listFiles()).foreach(_.foreach(child => deleteRecursively(child.toPath)))
    val _ = file.delete()
