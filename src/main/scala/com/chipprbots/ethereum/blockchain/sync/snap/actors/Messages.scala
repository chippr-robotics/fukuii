package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.blockchain.sync.snap._
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

/** Message protocols for SNAP sync actors */
object Messages {

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
  case class ContractStorageAccountsResponse(accounts: Seq[(ByteString, ByteString)]) extends AccountRangeCoordinatorMessage
  case object CheckCompletion extends AccountRangeCoordinatorMessage

  sealed trait AccountRangeWorkerMessage
  case class FetchAccountRange(task: AccountTask, peer: Peer, requestId: BigInt) extends AccountRangeWorkerMessage
  case class AccountRangeResponseMsg(response: AccountRange) extends AccountRangeWorkerMessage
  case class RequestTimeout(requestId: BigInt) extends AccountRangeWorkerMessage

  // ========================================
  // ByteCode Messages
  // ========================================

  sealed trait ByteCodeCoordinatorMessage

  case class StartByteCodeSync(contractAccounts: Seq[(ByteString, ByteString)]) extends ByteCodeCoordinatorMessage
  case class ByteCodePeerAvailable(peer: Peer) extends ByteCodeCoordinatorMessage
  case class ByteCodeTaskComplete(requestId: BigInt, result: Either[String, Int]) extends ByteCodeCoordinatorMessage
  case class ByteCodeTaskFailed(requestId: BigInt, reason: String) extends ByteCodeCoordinatorMessage
  case object ByteCodeGetProgress extends ByteCodeCoordinatorMessage
  case object ByteCodeCheckCompletion extends ByteCodeCoordinatorMessage

  sealed trait ByteCodeWorkerMessage
  case class FetchByteCodes(task: ByteCodeTask, peer: Peer) extends ByteCodeWorkerMessage
  case class ByteCodeWorkerFetchTask(task: ByteCodeTask, peer: Peer, requestId: BigInt, maxResponseSize: BigInt) extends ByteCodeWorkerMessage
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

  // ========================================
  // TrieNodeHealing Messages
  // ========================================

  sealed trait TrieNodeHealingCoordinatorMessage

  case class StartTrieNodeHealing(stateRoot: ByteString) extends TrieNodeHealingCoordinatorMessage
  case class QueueMissingNodes(nodes: Seq[ByteString]) extends TrieNodeHealingCoordinatorMessage
  case class HealingPeerAvailable(peer: Peer) extends TrieNodeHealingCoordinatorMessage
  case class HealingTaskComplete(requestId: BigInt, result: Either[String, Int]) extends TrieNodeHealingCoordinatorMessage
  case class HealingTaskFailed(requestId: BigInt, reason: String) extends TrieNodeHealingCoordinatorMessage
  case object HealingGetProgress extends TrieNodeHealingCoordinatorMessage
  case object HealingCheckCompletion extends TrieNodeHealingCoordinatorMessage

  sealed trait TrieNodeHealingWorkerMessage
  case class FetchTrieNodes(task: HealingTask, peer: Peer) extends TrieNodeHealingWorkerMessage
  case class TrieNodesResponseMsg(response: TrieNodes) extends TrieNodeHealingWorkerMessage
  case class HealingRequestTimeout(requestId: BigInt) extends TrieNodeHealingWorkerMessage
  case object HealingCheckIdle extends TrieNodeHealingWorkerMessage
}
