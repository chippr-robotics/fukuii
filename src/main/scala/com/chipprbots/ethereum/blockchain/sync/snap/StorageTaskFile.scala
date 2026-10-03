package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path

/** The persisted contract-storage task list that lets the SNAP storage phase resume after a restart.
  *
  * Each entry is 64 bytes: 32-byte account hash followed by 32-byte storage root. [[AccountRangeCoordinator]] writes
  * the file while accounts download; the controller reads it back on recovery. The file lives under the node datadir
  * (`<datadir>/snap/`), not `java.io.tmpdir`: a host reboot wipes /tmp, and a missing file used to be read as "nothing
  * left to download", which marked storage complete and pushed every contract's storage onto trie healing.
  *
  * When the file is missing, empty or truncated while storage is incomplete, recovery restarts the accounts phase. The
  * tasks are not rebuilt from the account trie: that trie is unhealed at this point (and Path-scheme nodes are keyed
  * differently), so a walk is unreliable.
  */
object StorageTaskFile:

  val EntrySize: Int = 64
  val CodeHashEntrySize: Int = 32
  val DirName: String = "snap"

  /** Directory for the task files under a node datadir. */
  def dirUnder(datadir: Path): Path = datadir.resolve(DirName)

  /** Create a fresh file for `prefix`/`suffix` in `dir`, creating `dir` if needed; the system temp dir when `None`. */
  def createFile(dir: Option[Path], prefix: String, suffix: String): Path =
    dir match
      case Some(d) =>
        Files.createDirectories(d)
        Files.createTempFile(d, prefix, suffix)
      case None => Files.createTempFile(prefix, suffix)

  /** A file is usable when it exists, is non-empty and holds a whole number of `entrySize`-byte entries (64 for storage
    * tasks, 32 for code hashes). I/O errors mean unusable.
    */
  def isUsable(path: Path, entrySize: Int = EntrySize): Boolean =
    try Files.isRegularFile(path) && Files.size(path) > 0 && Files.size(path) % entrySize == 0
    catch case _: java.io.IOException => false
