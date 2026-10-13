package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.SnapPeerHealth
import com.chipprbots.ethereum.blockchain.sync.snap.SnapTrie
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator.PendingResponse
import com.chipprbots.ethereum.network.Peer

/** The coordinator state `StoragePeerDispatch` reads and writes (spec 016 C-S1, research.md R14b: 23, plus
  * `peerHealth`). The core's behaviour arms, or the response processing, ordered apply and done markers that C-S2…S4
  * will move, touch every one of them too, so the Impl keeps them as fields.
  */
private[actors] trait StoragePeerDispatchState:
  def stateRoot: ByteString
  def maxInFlightPerPeer: Int
  def tasks: mutable.Queue[StorageTask]
  def pendingTaskKeys: mutable.Set[(ByteString, ByteString)]
  def activeTasks: mutable.Map[BigInt, (Peer, Seq[StorageTask], BigInt)]
  def completedTaskCount: Long
  var consecutiveTaskFailures: Int
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def peerHealth: SnapPeerHealth
  def statelessPeers: mutable.Set[String]
  def emptyResponseStrikes: mutable.Map[String, Int]
  var pivotRefreshRequested: Boolean
  var consecutiveUnproductiveRefreshes: Int
  var lastDispatchOrResponseMs: Long
  def postRefreshCooldownUntilMs: Long
  def peerBatchSize: mutable.Map[String, Int]
  def peerBatchSuccessStreak: mutable.Map[String, Int]
  def knownAvailablePeers: mutable.Set[Peer]
  def noMoreTasksExpected: Boolean
  def peerResponseBytesTarget: mutable.Map[String, BigInt]
  def pendingAccountTries: mutable.Map[ByteString, SnapTrie]
  def pendingResponses: mutable.Queue[PendingResponse]
  def pendingResponseBytes: Long
  def runningProcessingJobs: Int

/** What `StoragePeerDispatch` calls in the coordinator core: the task-completion fraction its periodic
  * `[STORAGE-STATE]` line reports.
  */
private[actors] trait StorageRangeCoreApi:
  def progress: Double
