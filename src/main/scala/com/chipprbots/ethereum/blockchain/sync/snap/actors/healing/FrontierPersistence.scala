package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.ActiveRequest
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingEntry

/** The coordinator state `FrontierPersistence` reads and writes (spec 016 C-H1, research.md R14b). The core's behaviour
  * arms and the code C-H2…C-H5 will move touch every one of them too, or rule (d) keeps them in the core
  * (`pendingBackpressure`, the `AtomicInteger` the frontier walk's thread reads; `prunedEnabled` and `healingWriterEc`,
  * derived vals), so the Impl keeps them as fields. `flushing` is the one `var` the module writes; it also mutates
  * `rawNodeBuffer` and `pendingSubtreeRecords`.
  */
private[actors] trait FrontierPersistenceState:
  def pendingTasks: mutable.ArrayDeque[HealingEntry]
  def activeRequests: mutable.Map[BigInt, ActiveRequest]
  def pendingBackpressure: AtomicInteger
  def crossRootHealCount: Long
  def healAttempts: mutable.Map[ByteString, Int]
  def rawNodeBuffer: mutable.ArrayBuffer[(ByteString, Array[Byte])]
  var flushing: Boolean
  def pendingSubtreeRecords: mutable.Set[ByteString]
  def totalNodesHealed: Int
  def prunedEnabled: Boolean
  def healingWriterEc: ExecutionContext
