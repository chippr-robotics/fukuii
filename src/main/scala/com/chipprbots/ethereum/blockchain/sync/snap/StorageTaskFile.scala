package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

import scala.collection.mutable
import scala.util.Success

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.MptVisitors.PathTrackingLeafWalkVisitor

/** The persisted contract-storage task list that lets the SNAP storage phase resume after a restart.
  *
  * Each entry is 64 bytes: 32-byte account hash followed by 32-byte storage root. [[AccountRangeCoordinator]] writes
  * the file while accounts download; the controller reads it back on recovery. The file lives under the node datadir
  * (`<datadir>/snap/`), not `java.io.tmpdir`: a host reboot wipes /tmp, and a missing file used to be read as "nothing
  * left to download", which marked storage complete and pushed every contract's storage onto trie healing.
  *
  * When the file is absent or damaged, [[rederive]] rebuilds it by walking the finished account trie: the same accounts
  * and storage roots the coordinator saw when it first wrote the file.
  */
object StorageTaskFile:

  val EntrySize: Int = 64
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

  /** A file is usable when it exists and holds a whole number of 64-byte entries. */
  def isUsable(path: Path): Boolean =
    Files.isRegularFile(path) && Files.size(path) % EntrySize == 0

  /** Walk the account trie at `stateRoot`, write a `(accountHash, storageRoot)` entry to `out` for every account with a
    * non-empty storage root, and hand the tasks to `emit` in batches. Throws if the trie cannot be read. Returns the
    * number of tasks.
    */
  def rederive(
      storage: MptStorage,
      stateRoot: ByteString,
      out: Path,
      emit: Seq[StorageTask] => Unit,
      batchSize: Int = 10000
  ): Long =
    val emptyRoot = Account.EmptyStorageRootHash
    val batch = mutable.ArrayBuffer.empty[StorageTask]
    var total = 0L
    val os = new BufferedOutputStream(new FileOutputStream(out.toFile), 65536)
    try
      val onLeaf: (ByteString, LeafNode) => Unit = (accountHash, leaf) =>
        Account(leaf.value) match
          case Success(account) if account.storageRoot != emptyRoot && account.storageRoot.value.nonEmpty =>
            os.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
            os.write(account.storageRoot.value.toArray.padTo(32, 0.toByte), 0, 32)
            batch += StorageTask.createStorageTask(accountHash, account.storageRoot.value)
            total += 1
            if batch.size >= batchSize then
              emit(batch.toSeq)
              batch.clear()
          case _ => ()
      MptTraversals.dispatch(
        HashNode(stateRoot.toArray),
        new PathTrackingLeafWalkVisitor(storage, ByteString.empty, onLeaf)
      )
      if batch.nonEmpty then emit(batch.toSeq)
      os.flush()
    finally os.close()
    total
