package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** Message protocols for SNAP sync actors */
object Messages {

  // ========================================
  // Global Coordinator Control Messages
  // ========================================

  /** Dynamically adjust per-peer concurrency budget for a coordinator. Sent by SNAPSyncController at phase transitions
    * to implement global per-peer request budgeting (Geth-aligned: total 5 requests per peer across all coordinators).
    */
  case class UpdateMaxInFlightPerPeer(newLimit: Int)

  // ========================================
  // AccountRange Messages
  // ========================================

  sealed trait AccountRangeCoordinatorMessage

  case class StartAccountRangeSync(stateRoot: ByteString) extends AccountRangeCoordinatorMessage
  case class PeerAvailable(peer: Peer) extends AccountRangeCoordinatorMessage
  case class TaskComplete(
      requestId: BigInt,
      result: Either[String, (Int, Seq[(ByteString, com.chipprbots.ethereum.domain.Account)], Seq[ByteString])]
  ) extends AccountRangeCoordinatorMessage
  case class TaskFailed(requestId: BigInt, reason: String) extends AccountRangeCoordinatorMessage
  case object GetProgress extends AccountRangeCoordinatorMessage
  case object GetContractAccounts extends AccountRangeCoordinatorMessage
  case class ContractAccountsResponse(accounts: Seq[(ByteString, ByteString)]) extends AccountRangeCoordinatorMessage
  case object GetContractStorageAccounts extends AccountRangeCoordinatorMessage
  case class ContractStorageAccountsResponse(accounts: Seq[(ByteString, ByteString)])
      extends AccountRangeCoordinatorMessage

  /** Request unique codeHashes collected during account download (Bloom-filtered, file-backed). Returns ~2M entries
    * (~64MB) instead of 73.5M raw entries (4.7GB). Bug 20 fix.
    */
  case object GetUniqueCodeHashes extends AccountRangeCoordinatorMessage
  case class UniqueCodeHashesResponse(codeHashes: Seq[ByteString])

  /** Request storage file metadata for async streaming. Returns instantly (no file read). */
  case object GetStorageFileInfo extends AccountRangeCoordinatorMessage
  case class StorageFileInfoResponse(filePath: java.nio.file.Path, count: Long)
  case object CheckCompletion extends AccountRangeCoordinatorMessage

  /** Sent by AccountRangeCoordinator to SNAPSyncController with progress for ALL ranges. Maps range `last` hash →
    * current `next` position. Used to resume partial ranges across SNAP sync restarts (core-geth parity: preserves
    * task.Next across pivot changes).
    */
  case class AccountRangeProgress(progress: Map[ByteString, ByteString]) extends AccountRangeCoordinatorMessage

  /** Sent by SNAPSyncController when a fresher pivot has been selected. Coordinator updates pending tasks with the new
    * root and resumes downloading.
    */
  case class PivotRefreshed(newStateRoot: ByteString) extends AccountRangeCoordinatorMessage

  // Internal message for chunked account storage (avoids blocking the actor for minutes)
  private[actors] case class StoreAccountChunk(
      task: AccountTask,
      remaining: Seq[(ByteString, com.chipprbots.ethereum.domain.Account)],
      totalCount: Int,
      storedSoFar: Int,
      isTaskRangeComplete: Boolean
  ) extends AccountRangeCoordinatorMessage

  sealed trait AccountRangeWorkerMessage
  case class FetchAccountRange(
      task: AccountTask,
      peer: Peer,
      requestId: BigInt,
      responseBytes: BigInt = BigInt(512 * 1024)
  ) extends AccountRangeWorkerMessage
  case class AccountRangeResponseMsg(response: AccountRange) extends AccountRangeWorkerMessage
  case class RequestTimeout(requestId: BigInt) extends AccountRangeWorkerMessage

  // ========================================
  // ByteCode Messages
  // ========================================

  sealed trait ByteCodeCoordinatorMessage

  case class StartByteCodeSync(codeHashes: Seq[ByteString]) extends ByteCodeCoordinatorMessage

  /** Incrementally add bytecode download tasks (geth-aligned: inline dispatch from account responses). Coordinator
    * deduplicates and batches these into ByteCodeTasks.
    */
  case class AddByteCodeTasks(codeHashes: Seq[ByteString]) extends ByteCodeCoordinatorMessage

  /** Signal that no more bytecode tasks will arrive (all accounts downloaded). Coordinator may now report completion
    * when pending + active tasks drain.
    */
  case object NoMoreByteCodeTasks extends ByteCodeCoordinatorMessage

  /** Sent by SNAPSyncController when a fresher pivot has been selected. Bytecodes are content-addressed (hash-keyed) so
    * pivot changes don't invalidate them, but the coordinator should clear stale peer tracking.
    */
  case object ByteCodePivotRefreshed extends ByteCodeCoordinatorMessage

  case class ByteCodePeerAvailable(peer: Peer) extends ByteCodeCoordinatorMessage
  case class ByteCodeTaskComplete(requestId: BigInt, result: Either[String, Int]) extends ByteCodeCoordinatorMessage
  case class ByteCodeTaskFailed(requestId: BigInt, reason: String) extends ByteCodeCoordinatorMessage
  case object ByteCodeGetProgress extends ByteCodeCoordinatorMessage
  case object ByteCodeCheckCompletion extends ByteCodeCoordinatorMessage

  sealed trait ByteCodeWorkerMessage
  case class FetchByteCodes(task: ByteCodeTask, peer: Peer) extends ByteCodeWorkerMessage
  case class ByteCodeWorkerFetchTask(task: ByteCodeTask, peer: Peer, requestId: BigInt, maxResponseSize: BigInt)
      extends ByteCodeWorkerMessage
  case class ByteCodesResponseMsg(response: ByteCodes) extends ByteCodeWorkerMessage
  case class ByteCodeRequestTimeout(requestId: BigInt) extends ByteCodeWorkerMessage
  case class ByteCodeProgress(progress: Double, bytecodesDownloaded: Long, bytesDownloaded: Long)

  // ========================================
  // StorageRange Messages
  // ========================================

  sealed trait StorageRangeCoordinatorMessage

  case class StartStorageRangeSync(stateRoot: ByteString) extends StorageRangeCoordinatorMessage
  case class AddStorageTasks(tasks: Seq[StorageTask]) extends StorageRangeCoordinatorMessage
  case class AddStorageTask(task: StorageTask) extends StorageRangeCoordinatorMessage
  case class StoragePeerAvailable(peer: Peer) extends StorageRangeCoordinatorMessage
  case class StorageTaskComplete(requestId: BigInt, result: Either[String, Int]) extends StorageRangeCoordinatorMessage
  case class StorageTaskFailed(requestId: BigInt, reason: String) extends StorageRangeCoordinatorMessage
  case object StorageGetProgress extends StorageRangeCoordinatorMessage
  case object StorageCheckCompletion extends StorageRangeCoordinatorMessage

  sealed trait StorageRangeWorkerMessage
  case class FetchStorageRanges(task: StorageTask, peer: Peer) extends StorageRangeWorkerMessage
  case class StorageRangesResponseMsg(response: StorageRanges) extends StorageRangeWorkerMessage
  case class StorageRequestTimeout(requestId: BigInt) extends StorageRangeWorkerMessage
  case object StorageCheckIdle extends StorageRangeWorkerMessage

  /** Sent by SNAPSyncController when a fresher pivot has been selected during storage sync. Coordinator updates state
    * root and clears per-peer adaptive state.
    */
  case class StoragePivotRefreshed(newStateRoot: ByteString) extends StorageRangeCoordinatorMessage

  /** Signal that no more storage tasks will arrive (all accounts downloaded). Coordinator may now report completion
    * when pending + active tasks drain.
    */
  case object NoMoreStorageTasks extends StorageRangeCoordinatorMessage

  /** Sent by SNAPSyncController when storage sync has stagnated and should promote to healing. Coordinator flushes
    * deferred writes and reports StorageRangeSyncComplete.
    */
  case object ForceCompleteStorage extends StorageRangeCoordinatorMessage

  /** Two-phase storage: async trie construction completed for a batch of accounts. Background thread built tries from
    * buffered raw slots and flushed to storage. `forStateRoot` tags the pivot state root this construction was
    * initiated under, so stale completions (after pivot refresh) can be detected and ignored.
    */
  private[actors] case class TrieConstructionComplete(
      accountHashes: Seq[ByteString],
      totalSlots: Long,
      elapsedMs: Long,
      forStateRoot: ByteString
  ) extends StorageRangeCoordinatorMessage

  /** Two-phase storage: async trie construction failed for a batch of accounts. */
  private[actors] case class TrieConstructionFailed(
      accountHashes: Seq[ByteString],
      error: String,
      forStateRoot: ByteString
  ) extends StorageRangeCoordinatorMessage

  // ========================================
  // TrieNodeHealing Messages
  // ========================================

  sealed trait TrieNodeHealingCoordinatorMessage

  case class StartTrieNodeHealing(stateRoot: ByteString) extends TrieNodeHealingCoordinatorMessage

  /** Queue missing trie nodes for healing. Each entry is (pathset, hash) where pathset is the GetTrieNodes path
    * encoding:
    *   - Account trie node: Seq(compact_path)
    *   - Storage trie node: Seq(account_hash, compact_storage_path)
    */
  case class QueueMissingNodes(nodes: Seq[(Seq[ByteString], ByteString)]) extends TrieNodeHealingCoordinatorMessage
  case class HealingPeerAvailable(peer: Peer) extends TrieNodeHealingCoordinatorMessage
  case class HealingTaskComplete(requestId: BigInt, result: Either[String, Int])
      extends TrieNodeHealingCoordinatorMessage
  case class HealingTaskFailed(requestId: BigInt, reason: String) extends TrieNodeHealingCoordinatorMessage
  case object HealingGetProgress extends TrieNodeHealingCoordinatorMessage
  case object HealingCheckCompletion extends TrieNodeHealingCoordinatorMessage

  /** Sent by SNAPSyncController when a fresher pivot has been selected during healing. Coordinator updates state root,
    * clears pending tasks and stateless tracking. A new trie walk will re-populate tasks for the new root.
    */
  case class HealingPivotRefreshed(newStateRoot: ByteString) extends TrieNodeHealingCoordinatorMessage

  /** Sent by SNAPSyncController when the current pivot has advanced beyond the SNAP serve window (peers have pruned it).
    * Coordinator stops immediately, reporting current healed count, so controller can restart healing with a fresh pivot.
    */
  case object HealingForceComplete extends TrieNodeHealingCoordinatorMessage

  /** Sent by TrieNodeHealingCoordinator to SNAPSyncController when trie healing has stagnated (no progress for
    * MaxConsecutiveStagnations × stagnation-check-interval). Controller should restart healing with a fresh pivot.
    */
  case class HealingStagnated(healed: Long, pending: Long)

  sealed trait TrieNodeHealingWorkerMessage
  case class FetchTrieNodes(task: HealingTask, peer: Peer) extends TrieNodeHealingWorkerMessage
  case class TrieNodesResponseMsg(response: TrieNodes) extends TrieNodeHealingWorkerMessage
  case class HealingRequestTimeout(requestId: BigInt) extends TrieNodeHealingWorkerMessage
  case object HealingCheckIdle extends TrieNodeHealingWorkerMessage
}
