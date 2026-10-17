package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry
import com.chipprbots.ethereum.network.Peer

/** The coordinator state `HealResponseProcessing` reads and writes (spec 016 C-H5, research.md R14b). The core's
  * behaviour arms (the lifecycle arms' reseeds and re-pegs, the completion gate, the stagnation pulse), the other
  * modules, or rule (d) keep them in the core, so the Impl keeps them as fields. The response processing mutates the
  * task queue, its dedup set, the Path-scheme alias map, the in-flight map, the raw-node buffer, the attempt counters,
  * the spec 003 scoped-verification accumulator (`healedPathsThisRound`, `healedPathsRoot`, `healedPathsOverflowed`),
  * the stateless set and its strikes and the staged spec 005 subtree records; it writes the progress counters
  * (`totalNodesHealed`, `crossRootHealCount`, `totalBytesReceived`, `completedTaskCount`), the pivot-refresh latch and
  * its timestamp, and `lastHealedAtMs`; it reads the walk and serve roots and `knownAvailablePeers` (and evicts from
  * it). `prunedEnabled` is an immutable val rule (d) keeps in the core.
  */
private[actors] trait HealResponseProcessingState:
  def stateRoot: ByteString
  def serveRoot: ByteString
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def pendingHashSet: mutable.Set[ByteString]
  def pathAliases: mutable.Map[ByteString, mutable.LinkedHashMap[Seq[ByteString], HealingEntry]]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])]
  var totalNodesHealed: Int
  var crossRootHealCount: Long
  def healAttempts: mutable.Map[ByteString, Int]
  def healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry]
  var healedPathsRoot: ByteString
  var healedPathsOverflowed: Boolean
  var totalBytesReceived: Long
  var completedTaskCount: Int
  def statelessPeers: mutable.Set[String]
  def emptyResponseStrikes: mutable.Map[String, Int]
  def knownAvailablePeers: mutable.Set[Peer]
  var pivotRefreshRequested: Boolean
  var pivotRefreshRequestedAt: Long
  var lastHealedAtMs: Long
  def prunedEnabled: Boolean
  def pendingSubtreeRecords: mutable.Set[ByteString]

/** What `reverifyCurrentRoot` calls in the core: `isComplete`, nothing pending and nothing in flight (C-H5's member,
  * still in the core).
  */
private[actors] trait HealCompletionApi:
  def isComplete: Boolean
