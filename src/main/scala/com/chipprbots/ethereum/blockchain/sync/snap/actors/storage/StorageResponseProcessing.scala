package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PendingResponse
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StaleRootFailure
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.StorageTaskKey
import com.chipprbots.ethereum.network.Peer

/** The coordinator state `StorageResponseProcessing` reads and writes (spec 016 C-S4, research.md R14b). The core's
  * behaviour arms (pivot refresh, force-complete, peer loss, the processing result, progress), `onPostStop`, the peer
  * dispatch (C-S1) or the ordered apply (C-S2) touch every one of them too, or rule (d) keeps them in the core (the
  * immutable `processingPool` and `processingEc`), so the Impl keeps them as fields. The `var`s are the ones the module
  * writes.
  */
private[actors] trait StorageResponseProcessingState:
  def stateRoot: ByteString
  def tasks: mutable.Queue[StorageTask]
  def pendingTaskKeys: mutable.Set[(ByteString, ByteString)]
  def activeTasks: mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]
  def accountSubtaskCounters: mutable.Map[ByteString, (Int, Int)]
  var completedAccountCount: Long
  def statelessPeers: mutable.Set[String]
  var consecutiveUnproductiveRefreshes: Int
  var lastDispatchOrResponseMs: Long
  def emptyResponsesByTask: mutable.HashMap[StorageTaskKey, Int]
  def staleRootFailuresByAccount: mutable.Map[ByteString, Vector[StaleRootFailure]]
  def abandonedAccounts: mutable.Set[ByteString]
  var staleRootFailureEvents: Long
  def pendingAccountTries: mutable.Map[ByteString, SnapTrie]
  def pendingResponses: mutable.Queue[PendingResponse]
  var pendingResponseBytes: Long
  var runningProcessingJobs: Int
  var processingGeneration: Long
  var trieWriteNanos: Long
  def processingPool: Option[java.util.concurrent.ThreadPoolExecutor]
  def processingEc: ExecutionContext
