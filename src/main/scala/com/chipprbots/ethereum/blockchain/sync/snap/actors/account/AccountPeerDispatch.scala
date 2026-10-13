package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.AccountTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.WorkerRef
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId

/** The coordinator state `AccountPeerDispatch` reads and writes (spec 016 C-A1, research.md R14b: 17). The core's
  * behaviour arms, or the account-task lifecycle that C-A2 will move, touch every one of them too, so the Impl keeps
  * them as fields.
  */
private[actors] trait AccountPeerDispatchState:
  def stateRoot: ByteString
  def maxInFlightPerPeer: Int
  def backpressureSources: mutable.Set[String]
  def statelessPeers: mutable.Set[PeerId]
  def snaplessPeers: mutable.Set[PeerId]
  def emptyResponseStrikes: mutable.Map[PeerId, Int]
  var pivotRefreshRequested: Boolean
  def pendingTasks: mutable.PriorityQueue[AccountTask]
  def activeTasks: mutable.Map[BigInt, (AccountTask, WorkerRef, Peer)]
  def workers: mutable.ArrayBuffer[WorkerRef]
  def idleWorkers: mutable.LinkedHashSet[WorkerRef]
  var lastDispatchOrResponseMs: Long
  def knownAvailablePeers: mutable.Set[Peer]
  def peerResponseBytesTarget: mutable.Map[String, BigInt]
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def lastDispatchTimeMs: mutable.Map[String, Long]
  var consecutiveUnproductiveRefreshes: Int

/** What `AccountPeerDispatch` calls outside itself: the redispatch of pending tasks to every eligible peer, which
  * re-engages dispatch when the last back-pressure source releases. The coordinator core provides it until C-A2 moves
  * it into `AccountTaskLifecycle`.
  */
private[actors] trait AccountTaskLifecycleApi:
  def tryRedispatchPendingTasks(): Unit
