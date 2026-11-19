package com.chipprbots.ethereum.tls

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

import scala.util.control.NonFatal

/** Notices a change to any of a set of files by the SHA-256 of their content (spec 030 research D-13).
  *
  * Content, not modification times: an edit within the same second is seen, and so is the Kubernetes way of updating a
  * mounted secret, where `..data` is a symlink swapped to a new directory and the files keep their names and times. A
  * file that cannot be read counts as its own state ("missing"), so its return is a change too.
  */
final class FilePoller(val files: Seq[Path]):

  @volatile private var last: String = FilePoller.digest(files)

  /** Whether the files changed since the last call (or since this poller was made). Each change is reported once:
    * whatever the caller then does with it, the next call compares against what it saw now.
    */
  def changed(): Boolean = synchronized {
    val now = FilePoller.digest(files)
    val result = now != last
    last = now
    result
  }

object FilePoller:

  /** One SHA-256 over every file's name and content, in order. */
  def digest(files: Seq[Path]): String =
    val sha = MessageDigest.getInstance("SHA-256")
    files.foreach { file =>
      sha.update(file.toString.getBytes(StandardCharsets.UTF_8))
      sha.update(0.toByte)
      try sha.update(Files.readAllBytes(file))
      catch case NonFatal(_) => sha.update("missing".getBytes(StandardCharsets.UTF_8))
      sha.update(0.toByte)
    }
    sha.digest().map(b => f"${b & 0xff}%02x").mkString
