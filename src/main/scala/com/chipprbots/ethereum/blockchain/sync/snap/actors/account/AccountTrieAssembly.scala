package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.io.BufferedOutputStream
import java.nio.file.Path

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.ProgressMilestones
import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapHashTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapPathTrie
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetCodeHashesFileInfo
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetContractAccounts
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetContractStorageAccounts
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetProgress
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetStorageFileInfo
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.AccountGetUniqueCodeHashes
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.CheckCompletion
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.CodeHashesFileInfoResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.ContractAccountsResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.ContractStorageAccountsResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.PeerAvailable
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.PivotRefreshed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.StorageFileInfoResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.StoreAccountChunk
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.TrieFlushComplete
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.TrieFlushFailed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.UniqueCodeHashesResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeStats
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer

/** The coordinator state `AccountTrieAssembly` reads and writes (spec 016 C-A3, research.md R14b: 12, plus
  * `trieFlushGeneration`, `startTime` and the six task-file handles). The core's behaviour arms, the other modules or
  * the contract carry that C-A4 will move touch every one of them too, or rule (d) keeps them in the core (concrete
  * `val`s, or a `var` whose initializer is not a literal), so the Impl keeps them as fields. The `finalizing` arms read
  * the task files and their counts to answer the controller's file queries during finalisation.
  */
private[actors] trait AccountTrieAssemblyState:
  def stateRoot: ByteString
  def pendingTasks: mutable.PriorityQueue[AccountTask]
  def activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]
  def completedTasks: mutable.ArrayBuffer[AccountTask]
  def workers: mutable.ArrayBuffer[WorkerRef]
  var accountsDownloaded: Long
  def startTime: Long
  def consumedKeyspace: BigInt
  def storingTasks: mutable.Map[ByteString, AccountTask]
  def taskStackTries: mutable.Map[ByteString, SnapTrie]
  def trieFlushGeneration: Long
  def contractAccountsFile: Path
  def contractAccountsOut: BufferedOutputStream
  def contractAccountsCount: Long
  def contractStorageFile: Path
  def contractStorageOut: BufferedOutputStream
  def contractStorageCount: Long
  def uniqueCodeHashesFile: Path
  def uniqueCodeHashesOut: BufferedOutputStream
  def uniqueCodeHashesCount: Long

/** What `AccountTrieAssembly` calls in the coordinator core: the progress stats a `finalizing` progress query answers
  * with, and the teardown the `finalizing` behaviour's `PostStop` runs.
  */
private[actors] trait AccountRangeCoreApi:
  def calculateProgress(): AccountRangeStats
  def onStop(): Unit

/** What `AccountTrieAssembly` calls in the contract carry: reading the contract and unique-codeHash task files for the
  * controller's queries during finalisation. The coordinator core provides it until C-A4 moves it into
  * `ContractWorkCarry`.
  */
private[actors] trait ContractTaskFilesApi:
  def readContractFile(filePath: Path, out: BufferedOutputStream, count: Long): Seq[(ByteString, ByteString)]
  def readUniqueCodeHashes(): Seq[ByteString]

/** The account-trie assembly (spec 016 C-A3, coordinator-characterization.md §3.2): inserting a stored response's
  * accounts chunk by chunk into its range's StackTrie (yielding to the mailbox between chunks), the per-range StackTrie
  * itself (Hash scheme: nodes flush to the pivot's trie store; Path scheme: path-keyed account nodes, with the
  * left-boundary skip on a resumed range), the range-complete commit, the off-actor trie finalisation and the
  * `finalizing` behaviour that waits for it. The coordinator core keeps the behaviour arms that call it.
  */
private[actors] trait AccountTrieAssembly:
  self: AccountTrieAssemblyState & AccountRangeEnv & AccountActivePeersApi & AccountTaskLifecycleApi &
    AccountProgressApi & AccountRangeCoreApi & ContractTaskFilesApi =>

  private var lastProgressLogAt: Long = 0 // accounts count at last periodic log
  private lazy val ProgressLogInterval: Long = 100_000 // log every 100K accounts
  private var lastFlatMilestonePct: Int = -1
  private lazy val totalKeyspace: BigInt = BigInt(2).pow(256)

  /** Behavior during async trie finalization. The StackTrie finalize is running on `account-trie-dispatcher`.
    * Package-private so tests can drive the finalisation phase directly.
    */
  private[actors] def finalizing(): Behavior[Command] = Behaviors
    .receiveMessage[Command] {
      // Stale-generation drop. A completion arriving for a generation that's been bumped
      // since spawn (e.g., the actor restarted finalisation) is silently ignored — data
      // is on disk either way, and the in-flight Future can't be cancelled.
      case TrieFlushComplete(gen, _) if gen != trieFlushGeneration =>
        log.debug(s"Dropping stale TrieFlushComplete (gen=$gen, current=$trieFlushGeneration)")
        Behaviors.same

      case TrieFlushComplete(_, Right(finalizedRoot)) =>
        log.info(
          "State trie finalized successfully with root {}",
          finalizedRoot.take(8).toArray.map("%02x".format(_)).mkString
        )
        snapSyncController ! SNAPSyncController.AccountTrieFinalized(finalizedRoot)
        snapSyncController ! SNAPSyncController.ProgressAccountsTrieFinalized
        Behaviors.stopped

      case TrieFlushComplete(_, Left(error)) =>
        log.error(s"Failed to finalize trie: $error")
        snapSyncController ! SNAPSyncController.AccountTrieFinalizationFailed(error)
        Behaviors.stopped

      // Stale-generation drop for the failure path too — a failure for a superseded generation
      // is ignored, mirroring the TrieFlushComplete stale-drop above.
      case TrieFlushFailed(gen, _) if gen != trieFlushGeneration =>
        log.debug(s"Dropping stale TrieFlushFailed (gen=$gen, current=$trieFlushGeneration)")
        Behaviors.same

      case TrieFlushFailed(_, error) =>
        log.error(s"Trie finalization failed with exception: $error")
        snapSyncController ! SNAPSyncController.AccountTrieFinalizationFailed(error)
        Behaviors.stopped

      case _: PeerAvailable =>
        // Ignore — no more tasks to dispatch during finalization
        Behaviors.same

      case _: PivotRefreshed =>
        log.info("Ignoring PivotRefreshed during trie finalization")
        Behaviors.same

      case AccountGetProgress(replyTo) =>
        replyTo ! calculateProgress()
        Behaviors.same

      case AccountGetContractAccounts(replyTo) =>
        replyTo ! ContractAccountsResponse(
          readContractFile(contractAccountsFile, contractAccountsOut, contractAccountsCount)
        )
        Behaviors.same

      case AccountGetContractStorageAccounts(replyTo) =>
        replyTo ! ContractStorageAccountsResponse(
          readContractFile(contractStorageFile, contractStorageOut, contractStorageCount)
        )
        Behaviors.same

      case AccountGetUniqueCodeHashes(replyTo) =>
        replyTo ! UniqueCodeHashesResponse(readUniqueCodeHashes())
        Behaviors.same

      case AccountGetStorageFileInfo(replyTo) =>
        contractStorageOut.flush()
        replyTo ! StorageFileInfoResponse(contractStorageFile, contractStorageCount)
        Behaviors.same

      case AccountGetCodeHashesFileInfo(replyTo) =>
        uniqueCodeHashesOut.flush()
        replyTo ! CodeHashesFileInfoResponse(uniqueCodeHashesFile, uniqueCodeHashesCount)
        Behaviors.same

      case CheckCompletion =>
        // Already finalizing, ignore
        Behaviors.same

      case _ =>
        // Ignore all other commands during finalisation (no tasks to dispatch). Also covers the
        // non-sealed Command trait exhaustiveness gap.
        Behaviors.same
    }
    .receiveSignal { case (_, org.apache.pekko.actor.typed.PostStop) =>
      // The coordinator stops from this behavior after trie finalisation (`Behaviors.stopped`).
      // Run the same teardown as the primary behavior's PostStop.
      onStop()
      Behaviors.same
    }

  // How many accounts to insert per chunk before yielding to the actor mailbox.
  // SnapHashTrie inserts are O(depth) memory + O(1) amortised compute (~2-20ms per 2000 accounts).
  private lazy val storeChunkSize = 2000

  /** Handle a chunk of account storage, inserting a batch into the per-task SnapHashTrie and yielding back to the actor
    * mailbox between chunks. Nodes batch-flush to RocksDB inside SnapHashTrie at the 8 MiB threshold.
    */
  def handleStoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ): Unit =
    val (chunk, rest) = remaining.splitAt(storeChunkSize)

    try
      // Route accounts to this task's per-range StackTrie. Inserts are O(depth) memory + O(1)
      // amortised compute; emitted nodes batch-flush to RocksDB inside SnapHashTrie at the 8 MiB
      // threshold, so we never accumulate a multi-GiB in-memory pivot trie.
      val trie = getOrCreateTaskStackTrie(task)
      chunk.foreach { case (accountHash, account) =>
        trie.update(accountHash.toArray, Account.accountSerializer.toBytes(account))
      }

      val newStored = storedSoFar + chunk.size
      // Report incremental progress so the stagnation watchdog sees activity
      accountsDownloaded += chunk.size
      snapSyncController ! SNAPSyncController.ProgressAccountsSynced(chunk.size.toLong)

      // Periodic progress log (every 100K accounts) to show download rate without per-chunk noise
      if accountsDownloaded - lastProgressLogAt >= ProgressLogInterval then
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val rate = if elapsed > 0 then (accountsDownloaded / elapsed).toLong else 0L
        val pct = (consumedKeyspace * 10000 / totalKeyspace).toDouble / 100.0
        log.info(
          s"Account download progress: $accountsDownloaded accounts (${"%.1f".format(pct)}% keyspace) " +
            s"(${completedTasks.size}/$concurrency ranges done, " +
            s"${pendingTasks.size} pending, ${activeTasks.size} active, " +
            s"${workers.size} workers/${activePeerCount} peers, " +
            s"${rate} accounts/sec)"
        )
        com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics.setAccountActivePeers(activePeerCount)
        lastProgressLogAt = accountsDownloaded
        val pctInt = pct.toInt
        val (newM, crossed) = ProgressMilestones.crossed(pctInt.toLong, 100L, lastFlatMilestonePct)
        lastFlatMilestonePct = newM
        crossed.foreach { m =>
          log.info(
            s"[SNAP-PROGRESS] ACCOUNT-RANGE $m% keyspace covered | $accountsDownloaded accounts | $rate accts/s"
          )
        }

      if rest.nonEmpty then
        log.debug(s"Stored chunk: $newStored/$totalCount accounts (${rest.size} remaining)")
        // Yield to actor mailbox - other messages (PeerAvailable, responses) process before next chunk
        ctx.self ! StoreAccountChunk(task, rest, totalCount, newStored, isTaskRangeComplete)
      else
        // Mark task done / re-enqueue BEFORE potentially spawning async flush — so the
        // task tracking is up to date by the time we re-enter `receive` after flushing.
        storingTasks.remove(task.last)
        if isTaskRangeComplete then
          completedTasks += task.copy(done = true)
          // On the StackTrie path, the task's per-range StackTrie has accumulated all of
          // this range's accounts; commit it to finalise the right boundary and flush the
          // remaining pending batch to RocksDB. The fragment root is logged for diagnostics
          // — it does NOT match the pivot's claimed root (each task produces only a
          // partial trie); healing reconciles fragments against the pivot root.
          taskStackTries.remove(task.last).foreach { trie =>
            val fragmentRoot = trie.commit()
            log.info(
              s"Account range COMPLETE: ${task.rangeString} " +
                s"(${completedTasks.size}/$concurrency ranges done, $accountsDownloaded accounts total, " +
                s"fragment root ${fragmentRoot.take(4).toArray.map("%02x".format(_)).mkString})"
            )
          }
          // Durable checkpoint: a completed range is the most valuable progress to keep.
          sendProgressSnapshot(durableRequested = true)
        else
          // Need more requests for the same interval; re-queue with updated `next`. Re-tag with the CURRENT root: a
          // pivot refresh while this response was being stored re-tagged pendingTasks only, not this task.
          pendingTasks.enqueue(task.copy(rootHash = stateRoot))
          // Trigger immediate redispatch so the re-queued task is picked up without
          // waiting up to 1s for the next PeerAvailable message (BUG-DISPATCH-001).
          tryRedispatchPendingTasks()
          // In-process snapshot of the advanced cursor (the durable checkpoint is CheckpointTick's job — it also
          // flushes the trie batches and fsyncs the task files, which is too heavy per response).
          sendProgressSnapshot()

        // Each task's SnapHashTrie batches its emissions and flushes to RocksDB at the 8 MiB
        // threshold (or on task-complete commit). No global flush required.
        log.debug(s"Stored all $totalCount accounts via StackTrie ($accountsDownloaded total)")
        ctx.self ! CheckCompletion
    catch
      case e: Exception =>
        log.error(s"Failed to store account chunk: ${e.getMessage}", e)
        // Re-queue task for retry
        storingTasks.remove(task.last)
        pendingTasks.enqueue(task.copy(pending = false, done = false))

  /** Get-or-create the per-task [[SnapTrie]] for the StackTrie path. Each task gets its own streaming trie keyed by
    * `task.last` (the end-of-range boundary, unique per task).
    *
    * HashScheme (default): nodes flush to `mptStorage` via `storeRawNodes`, routing through `FastSyncNodeStorage` with
    * pivot-block-number tagging for pruning. PathScheme: nodes are written path-keyed to `PathNodeStorage`, with
    * left-boundary skip when `resumeProgress` has a cursor for this task.
    */
  private def getOrCreateTaskStackTrie(task: AccountTask): SnapTrie =
    taskStackTries.getOrElseUpdate(
      task.last,
      storageScheme match
        case StorageScheme.Hash =>
          new SnapHashTrie(batch => mptStorage.storeRawNodes(batch))
        case StorageScheme.Path =>
          val pns = stateIngest.pathNodes.getOrElse(
            throw new IllegalStateException("PathScheme requires pathNodeStorage to be set")
          )
          val skipLeft = resumeProgress.contains(task.last)
          new SnapPathTrie(
            owner = ByteString.empty,
            skipLeftBoundary = skipLeft,
            writePath = (path, _, blob) => pns.writeAccountNode(path, blob),
            deleteExact = path => pns.deleteAccountNode(path)
          )
    )

  /** Finalize the trie and ensure all nodes including the root are persisted to storage.
    *
    * @return
    *   Either error message or success
    */
  def finalizeTrie(): Either[String, ByteString] =
    // Runs inside a Future on `account-trie-dispatcher` — uses `futureLog` (plain SLF4J), NOT
    // the thread-confined `ctx.log`.
    try
      futureLog.info("Finalizing state trie...")
      // StackTrie path: each task's SnapHashTrie was committed on task-complete in
      // `handleStoreAccountChunk`, flushing its right boundary + remaining batch to
      // RocksDB. Defensively commit any stragglers (should be empty unless a task
      // finished after its `isTaskRangeComplete` branch was missed).
      if taskStackTries.nonEmpty then
        futureLog.warn(s"Finalising ${taskStackTries.size} uncommitted task StackTries (unexpected)")
        taskStackTries.values.foreach { trie =>
          val _ = trie.commit()
        }
        taskStackTries.clear()
      // Use the pivot's claimed root as the "finalized root". With per-task fragments
      // there is no single computed root; healing reconciles the on-disk trie against
      // `stateRoot` regardless.
      futureLog.info(
        s"State trie finalization complete (StackTrie path, 16 fragments). " +
          s"Reported root: ${stateRoot.take(8).toArray.map("%02x".format(_)).mkString}..."
      )
      Right(stateRoot)
    catch
      case e: Exception =>
        futureLog.error(s"Failed to finalize trie: ${e.getMessage}", e)
        Left(s"Trie finalization error: ${e.getMessage}")
