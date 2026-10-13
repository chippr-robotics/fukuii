package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ActiveByteCodeRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.WorkerRef
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId

/** The coordinator state `ByteCodeDispatch` reads and writes (spec 016 C-B1, research.md R14b: 9). The core's behaviour
  * arms touch every one of them too, so the Impl keeps them as fields.
  */
private[actors] trait ByteCodeDispatchState:
  def activeTasks: mutable.Map[BigInt, ActiveByteCodeRequest]
  def idleWorkers: mutable.LinkedHashSet[WorkerRef]
  def knownAvailablePeers: mutable.Set[Peer]
  def maxInFlightPerPeer: Int
  def peerCooldownUntilMillis: mutable.Map[PeerId, Long]
  def peerFailureCounts: mutable.Map[PeerId, Int]
  def peerResponseBytesTarget: mutable.Map[PeerId, BigInt]
  def pendingTasks: mutable.Queue[ByteCodeTask]
  def workers: mutable.ArrayBuffer[WorkerRef]

/** What `ByteCodeResponseHandling` calls in `ByteCodeDispatch`: the clock, the peer cooldowns, the per-peer response
  * budget and the worker pool's idle set.
  */
private[actors] trait ByteCodeDispatchApi:
  def nowMillis: Long
  def clearPeerFailures(peer: Peer): Unit
  def recordPeerCooldown(peer: Peer, base: FiniteDuration, reason: String): FiniteDuration
  def responseBytesTargetFor(peer: Peer): BigInt
  def adjustResponseBytesTargetOnSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit
  def adjustResponseBytesTargetOnFailure(peer: Peer, reason: String): Unit
  def markWorkerIdle(worker: WorkerRef): Unit

/** What `ByteCodeDispatch` calls in the coordinator core: the queue's pending work in codeHashes. */
private[actors] trait ByteCodeQueueApi:
  def pendingHashCount: Long
