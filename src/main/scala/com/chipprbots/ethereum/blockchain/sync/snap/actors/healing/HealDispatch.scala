package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.network.Peer

/** The coordinator state `HealDispatch` reads and writes (spec 016 C-H4, research.md R14b). The core's behaviour arms
  * (`healDispatchArms`, the stagnation pulse, the pivot re-peg), the response processing C-H5 will move, or rule (d)
  * keeps them in the core, so the Impl keeps them as fields. The dispatch writes `pivotRefreshRequested` and
  * `pivotRefreshRequestedAt` (the stagnation escalation in `handleTimeout`), `lastHealedAtMs` (its re-fire guard),
  * `healRate` and `lastThrottleAdjustMs` (the throttle update), and mutates the task queue, its dedup set, the
  * in-flight map, the per-peer cooldown and `responseBytes` maps and the attempt counters; it reads the rest.
  * `lastThrottleAdjustMs` is touched only by the dispatch, but its initializer is not a literal, so rule (d) keeps it
  * in the core; `maxConcurrentRequests`, `rawFlushThreshold` and `healingStagnationTimeoutMs` are immutable vals rule
  * (d) keeps in the core.
  */
private[actors] trait HealDispatchState:
  def stateRoot: ByteString
  def serveRoot: ByteString
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def pendingHashSet: mutable.Set[ByteString]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def maxInFlightPerPeer: Int
  def maxConcurrentRequests: Int
  var pivotRefreshRequested: Boolean
  var pivotRefreshRequestedAt: Long
  def statelessPeers: mutable.Set[String]
  def knownAvailablePeers: mutable.Set[Peer]
  def peerCooldownUntilMs: mutable.Map[String, Long]
  def peerResponseBytesTarget: mutable.Map[String, BigInt]
  def healAttempts: mutable.Map[ByteString, Int]
  var healRate: Double
  var lastThrottleAdjustMs: Long
  def rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])]
  def rawFlushThreshold: Int
  var lastHealedAtMs: Long
  def healingStagnationTimeoutMs: Long
  def totalNodesHealed: Int
