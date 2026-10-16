package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.db.storage.BfsQueueStorage
import com.chipprbots.ethereum.mpt.MptNode

/** The coordinator state `FrontierRebuildBfs` reads and writes (spec 016 C-H2, research.md R14b). The core's behaviour
  * arms and the code C-H3…C-H5 will move touch them too, or rule (d) keeps them in the core, so the Impl keeps them as
  * fields. `verificationBFSRunning` is the one `var` the module writes (the walk launcher sets it on the actor thread;
  * the core's completion and failure arms clear it); `prunedSubtreeCount` is the `AtomicLong` the walk's sub-range
  * threads bump and the verification entry and completion read. The rest are immutable vals rule (d) keeps in the core:
  * `bfsQueue` (the walk's level queue, `stateIngest.bfsQueue` or an in-memory fallback, built once with the
  * coordinator), the two dispatchers, `prunedEnabled`, the emission batch size and the walk's caps.
  */
private[actors] trait FrontierRebuildBfsState:
  var verificationBFSRunning: Boolean
  def prunedSubtreeCount: AtomicLong
  def bfsQueue: BfsQueueStorage
  def healingWriterEc: ExecutionContext
  def healingReaderEc: ExecutionContext
  def prunedEnabled: Boolean
  def FrontierBatchSize: Int
  def HealingVisitedCap: Int
  def HealingTraversalParallelism: Int
  def HealingMinParallelism: Int
  def HealingReservedCores: Int

/** What the frontier-rebuild walk calls for a Path-scheme read: `readPathVerifiedNode`, the bytes at the entry's own
  * path, counted present only when they hash to the entry's hash. It stays in the core until C-H3 (`HealVerification`)
  * moves it.
  */
private[actors] trait PathVerifiedReadApi:
  def readPathVerifiedNode(hash: ByteString, pathset: Seq[ByteString]): Option[MptNode]
