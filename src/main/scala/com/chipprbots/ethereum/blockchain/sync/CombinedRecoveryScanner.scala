package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.RecoveryProgress
import com.chipprbots.ethereum.mpt.ShardEnumerator

/** The merged, cross-shard-deduped gap set a recovery scan produces.
  *
  * @param holes
  *   subtrees this session's walks could not read, the store holding no node at the top of each, so nothing under them
  *   was looked at. This session's only: `RecoveryProgress` does not record it, so the shards a resume skips count none
  *   (spec 022 T050a). It is for the log, and decides nothing.
  */
final case class RecoveryScanResult(
    missingBytecodes: Vector[ByteString],
    missingStorageTries: Vector[(ByteString, ByteString)],
    holes: Long
)

/** The cohesive recovery-scan unit: integrates the three building blocks into one driver.
  *
  *   - [[com.chipprbots.ethereum.mpt.ShardEnumerator]] (#1) partitions the state trie at `scanRoot` into disjoint,
  *     exhaustive shards.
  *   - [[CombinedRecoveryScan]] (#2) walks one shard in a single pass, checking BOTH bytecode and storage per account.
  *   - [[com.chipprbots.ethereum.db.storage.RecoveryProgress]] (#3) persists completed-shard + accumulated-gap state so
  *     a crash/OOM resumes from the last completed shard instead of re-walking the whole ~86M-account trie.
  *
  * Each not-yet-completed shard is walked (optionally on a bounded thread pool — the `~2–2.6×` speedup); as each shard
  * finishes, its gaps are merged with cross-shard dedup and the progress is persisted atomically (completion index +
  * gaps in one write). The driver honours the contract the resumability audit pinned:
  *   1. it scans `progress.remainingShards` only — never re-walks a completed shard (no double-count); 2. it dedups
  *      storage roots (and code hashes) ACROSS shards via driver-level seen-sets — the same root referenced by accounts
  *      in different shards is reported once; 3. it persists one shard's completion + gaps in a single atomic
  *      `putRecoveryProgress` commit; 4. on a complete-progress checkpoint (e.g. a crash during the later download
  *      phase) it skips the scan entirely and returns the persisted gaps.
  *
  * Read-only: it touches trie/EVM state only via `.get`, and writes only `AppStateStorage` progress. Concurrent shard
  * walks are safe because each shard gets its own `getBackingStorage` handle and the underlying node/EVM/Guava-cache
  * reads are thread-safe. Marking recovery done + clearing the checkpoint (via `appStateStorage.recoveryDone()`) is the
  * caller's job, AFTER the returned gaps have been downloaded.
  *
  * @param storageForShard
  *   yields a FRESH [[MptStorage]] handle per call (e.g. `() => stateStorage.getBackingStorage(pivotBlockNumber)`); one
  *   per shard so concurrent walks don't share a handle.
  * @param concurrency
  *   max shards walked at once (`<= 1` ⇒ sequential, deterministic). Default 1 — the parallel path is opt-in.
  * @param shardDepth
  *   branch levels to descend when partitioning (1 ⇒ up to 16 shards). Forms the resume tag via the resulting count.
  */
final class CombinedRecoveryScanner(
    scanRoot: ByteString,
    storageForShard: () => MptStorage,
    evmCodeStorage: EvmCodeStorage,
    appStateStorage: AppStateStorage,
    concurrency: Int = 1,
    shardDepth: Int = 1,
    // Test hooks. `onShardScanStart` fires when a shard's walk begins (records which shards a resumed run actually
    // re-scans); `onShardPersisted` fires right AFTER a shard's progress is committed (throw here to simulate a
    // crash-after-persist and assert resume); `onShardFailed` fires when a shard's walk has ended in a failure or a stop,
    // with the first failure already visible to the other walks. Production leaves all three as no-ops.
    onShardScanStart: Int => Unit = _ => (),
    onShardPersisted: Int => Unit = _ => (),
    onShardFailed: () => Unit = () => ()
):
  private val log = LoggerFactory.getLogger(getClass)

  /** Walk the not-yet-completed shards, persisting resumable progress as it goes, and return the merged gap set.
    *
    * The first shard to fail stops the rest (spec 022 T050a): a failed scan goes on to regular sync, and walks left
    * running would read and fsync for hours beside it, over a root regular sync is pruning. What is thrown is that
    * failure, whichever shard's future the wait happens to see first.
    */
  def run(): RecoveryScanResult =
    val shards = ShardEnumerator.enumShards(scanRoot, storageForShard(), shardDepth)
    val shardCount = shards.length

    // An empty state trie has nothing to recover; never persist a checkpoint for it (invariant: shardCount >= 1).
    if shardCount == 0 then return RecoveryScanResult(Vector.empty, Vector.empty, holes = 0L)

    // Resume: a tag-matched checkpoint seeds the accumulators + dedup sets; a mismatch/absence starts fresh.
    val resumed = appStateStorage.getRecoveryProgressFor(scanRoot, shardCount)
    val completed = mutable.BitSet() ++ resumed.map(_.completedShards).getOrElse(Set.empty)
    val accBytecodes = mutable.ArrayBuffer.from(resumed.map(_.missingBytecodes).getOrElse(Vector.empty))
    val accStorage = mutable.ArrayBuffer.from(resumed.map(_.missingStorageTries).getOrElse(Vector.empty))
    val seenCodeHashes = mutable.HashSet.from(accBytecodes)
    val seenStorageRoots = mutable.HashSet.from(accStorage.map(_._2))

    // Fast path: the checkpoint is already complete (e.g. the process died during the download phase). Skip the scan
    // and hand back the persisted gaps so download can resume without re-walking the trie.
    if completed.size >= shardCount then
      return RecoveryScanResult(accBytecodes.toVector, accStorage.toVector, holes = 0L)

    val remaining = (0 until shardCount).filterNot(completed)
    val lock = new Object
    var holes = 0L
    val firstFailure = new AtomicReference[Throwable](null)

    // Live progress gauges for the node-health dashboard. Counts accounts walked THIS session (resumed shards are not
    // re-walked); the missing count is seeded from the resumed checkpoint so it stays cumulative. Atomics because the
    // per-leaf callback fires from parallel shard walks.
    val accountsScanned = new java.util.concurrent.atomic.AtomicLong(0L)
    val contractsFound = new java.util.concurrent.atomic.AtomicLong(0L)
    val missingStorageCount = new java.util.concurrent.atomic.AtomicLong(accStorage.size.toLong)
    val onAccount: Boolean => Unit = isContract =>
      if firstFailure.get != null then throw CombinedRecoveryScanner.StoppedByAnotherShard
      val n = accountsScanned.incrementAndGet()
      if isContract then contractsFound.incrementAndGet()
      if n % 100000 == 0 then RecoveryMetrics.setStorageScanProgress(n, contractsFound.get(), missingStorageCount.get())

    // Merge one shard's gaps (cross-shard dedup) and atomically persist completion + accumulated gaps. Serialized so
    // the shared accumulators/dedup-sets and the single-key write are consistent under parallel walks.
    def mergeAndPersist(idx: Int, scan: CombinedRecoveryScan): Unit = lock.synchronized {
      scan.missingBytecodes.foreach(h => if seenCodeHashes.add(h) then accBytecodes += h)
      scan.missingStorageTries.foreach { case (acct, root) =>
        if seenStorageRoots.add(root) then accStorage += ((acct, root))
      }
      missingStorageCount.set(accStorage.size.toLong)
      holes += scan.holes
      completed += idx
      // commitSync (fsync) not commit: the per-shard checkpoint is the resumability anchor, so it must survive a hard
      // host crash (this box has hit a memory-pressure thrash-lock), not just a clean OOM exit — otherwise the OS
      // write-back window can lose the last shards' progress and force a full re-scan. ~16-256 fsyncs over a multi-hour
      // scan is negligible. (commitSync infra from PR #1305.)
      appStateStorage
        .putRecoveryProgress(
          RecoveryProgress(scanRoot, shardCount, completed.toSet, accBytecodes.toVector, accStorage.toVector)
        )
        .commitSync()
      // Each shard's end, so a scan of hours says how far it is and what it costs, and holes a later resume will not
      // count are still on record.
      log.info(
        s"[SNAP-RECOVERY] Scan: ${completed.size} of $shardCount shards done, ${accountsScanned.get()} accounts this " +
          s"session, ${accBytecodes.size} codes and ${accStorage.size} storage roots missing, $holes subtrees unreadable"
      )
      onShardPersisted(completed.size)
    }

    // A shard still queued after a failure begins and stops at its first leaf, so nothing needs to check before it does.
    def scanOne(idx: Int): Unit =
      try
        onShardScanStart(idx)
        val scan = new CombinedRecoveryScan(storageForShard(), evmCodeStorage, onAccount)
        scan.scanShard(shards(idx).root, shards(idx).pathPrefix)
        mergeAndPersist(idx, scan)
      catch
        case NonFatal(e) =>
          firstFailure.compareAndSet(null, e)
          onShardFailed()
          throw e

    if concurrency <= 1 then remaining.foreach(scanOne)
    else
      val pool = Executors.newFixedThreadPool(math.min(concurrency, remaining.size))
      given ec: ExecutionContext = ExecutionContext.fromExecutorService(pool)
      try Await.result(Future.sequence(remaining.map(idx => Future(scanOne(idx)))), Duration.Inf)
      catch case NonFatal(e) => throw Option(firstFailure.get).getOrElse(e)
      finally pool.shutdown()

    // Final progress publish so the dashboard reflects the completed totals.
    RecoveryMetrics.setStorageScanProgress(accountsScanned.get(), contractsFound.get(), missingStorageCount.get())
    RecoveryScanResult(accBytecodes.toVector, accStorage.toVector, holes)

object CombinedRecoveryScanner:

  /** A walk's signal to give up: another shard has failed, and that failure is the one reported. */
  private object StoppedByAnotherShard extends RuntimeException with NoStackTrace
