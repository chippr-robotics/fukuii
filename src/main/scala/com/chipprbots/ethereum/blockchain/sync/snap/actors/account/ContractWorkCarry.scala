package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.google.common.hash.BloomFilter

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTaskFile
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.CheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.ContractEntrySize
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.ReplayCarriedContracts
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie

/** The coordinator state `ContractWorkCarry` reads and writes (spec 016 C-A4, research.md R14b: 4, about 15 with the
  * task-file I/O values). The contract task files, their streams and counts, the codeHash dedup filter and the carried
  * prefix counts are concrete `val`s whose initializers open files or copy the previous coordinator's prefix, or `var`s
  * whose initializer is not a literal, so rule (d) keeps them in the core (coordinator-characterization.md §3.2); the
  * core's `onStart`, `onStop`, `sendProgressSnapshot` and the trie assembly's `finalizing` read them too.
  */
private[actors] trait ContractWorkCarryState:
  def carriedStorageCount: Long
  def carriedCodeHashesCount: Long
  def contractAccountsOut: BufferedOutputStream
  def contractStorageFile: Path
  def contractStorageFos: FileOutputStream
  def contractStorageOut: BufferedOutputStream
  def uniqueCodeHashesFile: Path
  def uniqueCodeHashesFos: FileOutputStream
  def uniqueCodeHashesOut: BufferedOutputStream
  def codeHashBloom: BloomFilter[ByteString]
  var contractAccountsCount: Long
  var contractStorageCount: Long
  var uniqueCodeHashesCount: Long

/** The contract-work carry (spec 016 C-A4, coordinator-characterization.md §3.2): identifying the contract accounts of
  * a response into the storage-task and codeHash task files (with the codeHash dedup) and dispatching their work
  * inline; the paced replay of a resumed coordinator's carried prefix, with the completion-marker skip and, on the Hash
  * scheme, the root-presence check (#1487/#1498/#1501); making the task files durable for a checkpoint; and reading
  * them back for the controller. The coordinator core keeps the behaviour arms that call it and the task files.
  */
private[actors] trait ContractWorkCarry extends ContractWorkCarryApi with ContractTaskFilesApi:
  self: ContractWorkCarryState & AccountRangeEnv & AccountIntakeGateApi =>

  // Replay of the carried prefix (entries [0, carried*Count) of this coordinator's own files). Account-range sync is
  // not complete until both offsets reach their counts, so NoMore{Storage,ByteCode}Tasks can never overtake them.
  private var replayStorageOffset: Long = 0L
  private var replayCodeHashesOffset: Long = 0L
  // Replay outcome, for the completion log line: carried storage tasks skipped because their completion marker exists,
  // and tasks actually sent downstream (the Hash-scheme root-presence check accounts for the rest).
  private var replaySkippedFinished: Long = 0L
  private var replayQueuedStorage: Long = 0L
  def replayDone: Boolean =
    replayStorageOffset >= carriedStorageCount && replayCodeHashesOffset >= carriedCodeHashesCount

  /** Flush and fsync the storage-task and codeHash files. IO errors propagate: a checkpoint that cannot make its task
    * files durable must not be persisted.
    */
  def syncTaskFiles(): Unit =
    contractStorageOut.flush()
    uniqueCodeHashesOut.flush()
    contractStorageFos.getFD.sync()
    uniqueCodeHashesFos.getFD.sync()

  /** Replay one chunk of the carried contract work (entries `[0, carried*Count)` of this coordinator's own task files)
    * to the storage/bytecode coordinators, exactly as `identifyContractAccounts` would have for freshly downloaded
    * accounts. Paced, and held while downstream back-pressure is engaged, so a large carried prefix cannot flood the
    * storage queue.
    *
    * Both schemes: a storage task with a completion marker (`storageDone`, written by StorageRangeCoordinator in the
    * same RocksDB batch as the account's last flat slots, after its trie nodes) is skipped — the previous run finished
    * it, so replaying it would only redo the work (Sepolia 2026-10-07: 9.35M carried entries re-queued ~818k finished
    * tasks). The resumed state is then exactly what the previous run held, which never retried a finished task either.
    *
    * Hash scheme only: a storage task whose storage root node is already present is skipped — StackTrie emits a trie's
    * root last, after every descendant, so a present root means that storage trie was completed (and the forced healing
    * walk re-verifies it anyway). Path scheme gets no such presence check: one node per path means a correct root node
    * does not prove its descendants were not overwritten later (and the path-scheme healing walk stops at a node whose
    * hash matches, so it would not notice), nor that the account's flat slots — flushed asynchronously — ever landed.
    */
  def replayCarriedChunk(): Unit =
    if !replayDone then
      // Spec 014: the intake gate is read synchronously. The watermark signal alone arrived minutes late on Sepolia
      // (it crosses two mailboxes), by which time this loop had pushed the whole 12M-entry prefix downstream.
      if downstreamBackpressureActive || intakeGateClosed then
        timers.startSingleTimer(
          ReplayCarriedContracts,
          ReplayCarriedContracts,
          AccountRangeCoordinator.ReplayPausedRetry
        )
      else
        val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
        val storageEnd = math.min(carriedStorageCount, replayStorageOffset + AccountRangeCoordinator.ReplayChunkEntries)
        val candidates = mutable.ArrayBuffer.empty[StorageTask]
        AccountRangeCoordinator.foreachEntry(contractStorageFile, ContractEntrySize, replayStorageOffset, storageEnd) {
          entry =>
            val storageRoot = ByteString(java.util.Arrays.copyOfRange(entry, 32, 64))
            if storageRoot != emptyRoot then
              candidates += StorageTask.createStorageTask(
                ByteString(java.util.Arrays.copyOfRange(entry, 0, 32)),
                storageRoot
              )
        }
        val unfinished =
          storageDone.fold(candidates.toSeq)(_.unfinished(candidates.toSeq)(t => (t.accountHash, t.storageRoot)))
        replaySkippedFinished += candidates.size - unfinished.size
        val storageTasks = storageScheme match
          case StorageScheme.Hash if unfinished.nonEmpty =>
            val roots = unfinished.map(_.storageRoot).distinct
            val present = roots
              .zip(mptStorage.multiGetNodes(roots.map(_.toArray)))
              .collect { case (root, Some(_)) => root }
              .toSet
            unfinished.filterNot(t => present.contains(t.storageRoot))
          case _ => unfinished
        replayQueuedStorage += storageTasks.size
        val codeEnd =
          math.min(carriedCodeHashesCount, replayCodeHashesOffset + AccountRangeCoordinator.ReplayChunkEntries)
        val codeHashes = mutable.ArrayBuffer.empty[ByteString]
        AccountRangeCoordinator.foreachEntry(
          uniqueCodeHashesFile,
          StorageTaskFile.CodeHashEntrySize,
          replayCodeHashesOffset,
          codeEnd
        )(entry => codeHashes += ByteString(entry))
        replayStorageOffset = storageEnd
        replayCodeHashesOffset = codeEnd
        if storageTasks.nonEmpty || codeHashes.nonEmpty then
          intakeBudget.foreach(_.reserve(storageTasks.size, codeHashes.size))
          snapSyncController ! SNAPSyncController.IncrementalContractData(
            codeHashes.toSeq,
            storageTasks,
            replayed = true
          )
        if replayDone then
          log.info(
            s"Replayed carried contract work: $carriedStorageCount storage-task entries " +
              s"($replayQueuedStorage queued, $replaySkippedFinished skipped as already finished" +
              (if storageDone.isEmpty then ", no completion markers" else "") +
              s"), $carriedCodeHashesCount codeHashes"
          )
          ctx.self ! CheckCompletion
        else
          timers.startSingleTimer(
            ReplayCarriedContracts,
            ReplayCarriedContracts,
            AccountRangeCoordinator.ReplayChunkInterval
          )

  /** Identify the contract work of a response: code for every account with code, storage for every account with a
    * storage root. They are independent. Initcode that writes storage and returns empty code leaves a leaf with no code
    * and a real storage root, and nothing else would ever fetch that storage (spec 022 T050a, H0).
    *
    * The storage file holds every account with code, its root empty or not (the replays and the sweep skip an empty
    * root), and every account with storage. The bytecode file holds accounts with code only, so the two counts differ.
    *
    * @param accounts
    *   Accounts to scan
    */
  def identifyContractAccounts(accounts: Seq[(ByteString, Account)]): Unit =
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val newCodeHashes = mutable.ArrayBuffer.empty[ByteString]
    val newStorageTasks = mutable.ArrayBuffer.empty[StorageTask]
    var codeBearing = 0
    var storageEntries = 0

    accounts.foreach { case (accountHash, account) =>
      val hasCode = account.codeHash != Account.EmptyCodeHash
      val hasRoot = account.storageRoot.value.nonEmpty && account.storageRoot.value != emptyRoot
      if hasCode then
        // Write 32-byte accountHash + 32-byte codeHash to bytecode file (crash recovery)
        contractAccountsOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
        contractAccountsOut.write(account.codeHash.value.toArray.padTo(32, 0.toByte), 0, 32)
        codeBearing += 1

        // Track unique codeHashes via Bloom filter + temp file. A false positive means this hash is never queued and
        // never written to the file, and nothing in SNAP catches that: a clean finalisation sets bytecodeRecoveryDone,
        // so the recovery scan is skipped, and the healing walk has never read code. So the filter is sized to make it
        // negligible (`newCodeHashBloom`, about 1e-9 at the load it is built for), which is not provably zero. What is
        // left is found at import, whose bulk code recovery is the backstop (spec 022 T050a, H3).
        if !codeHashBloom.mightContain(account.codeHash.value) then
          codeHashBloom.put(account.codeHash.value)
          uniqueCodeHashesOut.write(account.codeHash.value.toArray.padTo(32, 0.toByte), 0, 32)
          uniqueCodeHashesCount += 1
          newCodeHashes += account.codeHash.value

      if hasCode || hasRoot then
        // Write 32-byte accountHash + 32-byte storageRoot to storage file (crash recovery)
        contractStorageOut.write(accountHash.toArray.padTo(32, 0.toByte), 0, 32)
        contractStorageOut.write(account.storageRoot.toArray.padTo(32, 0.toByte), 0, 32)
        storageEntries += 1

      // Collect storage task for inline dispatch (skip accounts with empty storage)
      if hasRoot then newStorageTasks += StorageTask.createStorageTask(accountHash, account.storageRoot.value)
    }

    if codeBearing > 0 || storageEntries > 0 then
      contractAccountsCount += codeBearing
      contractStorageCount += storageEntries
      // Flush file streams (crash recovery path)
      contractAccountsOut.flush()
      contractStorageOut.flush()
      uniqueCodeHashesOut.flush()
      log.info(
        s"Identified $codeBearing accounts with code and $storageEntries storage-file entries " +
          s"(totals: $contractAccountsCount, $contractStorageCount; unique codeHashes: $uniqueCodeHashesCount)"
      )

    // Geth-aligned: dispatch contract data inline to controller → bytecode/storage coordinators.
    // This eliminates the 6+ minute gap between account completion and first storage/bytecode request.
    if newCodeHashes.nonEmpty || newStorageTasks.nonEmpty then
      intakeBudget.foreach(_.reserve(newStorageTasks.size, newCodeHashes.size))
      snapSyncController ! SNAPSyncController.IncrementalContractData(
        newCodeHashes.toSeq,
        newStorageTasks.toSeq
      )

  /** Read all contract account entries from a temporary file. Each entry is 64 bytes: 32-byte key + 32-byte value.
    * Flushes the output stream first to ensure all data is written.
    */
  def readContractFile(
      filePath: Path,
      out: BufferedOutputStream,
      count: Long
  ): Seq[(ByteString, ByteString)] =
    out.flush()
    if count == 0 then Seq.empty
    else
      val raf = new RandomAccessFile(filePath.toFile, "r")
      try
        val result = new mutable.ArrayBuffer[(ByteString, ByteString)](count.toInt)
        val buf = new Array[Byte](ContractEntrySize)
        var i = 0L
        while i < count do
          raf.readFully(buf)
          val key = ByteString(java.util.Arrays.copyOfRange(buf, 0, 32))
          val value = ByteString(java.util.Arrays.copyOfRange(buf, 32, 64))
          result += ((key, value))
          i += 1
        result.toSeq
      finally raf.close()

  /** Read unique codeHashes from the Bloom-filtered temp file. Each entry is 32 bytes. File size is ~64MB for ~2M
    * unique hashes (vs 4.7GB for 73.5M raw entries).
    */
  def readUniqueCodeHashes(): Seq[ByteString] =
    uniqueCodeHashesOut.flush()
    if uniqueCodeHashesCount == 0 then Seq.empty
    else
      val raf = new RandomAccessFile(uniqueCodeHashesFile.toFile, "r")
      try
        val result = new mutable.ArrayBuffer[ByteString](uniqueCodeHashesCount.toInt)
        val buf = new Array[Byte](32)
        var i = 0L
        while i < uniqueCodeHashesCount do
          raf.readFully(buf)
          result += ByteString(buf.clone())
          i += 1
        result.toSeq
      finally raf.close()
