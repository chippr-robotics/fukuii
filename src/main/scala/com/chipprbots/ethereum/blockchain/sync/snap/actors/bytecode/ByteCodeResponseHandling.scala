package com.chipprbots.ethereum.blockchain.sync.snap.actors.bytecode

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.sync.snap.ByteCodeTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator.ActiveByteCodeRequest

/** The coordinator state `ByteCodeResponseHandling` reads and writes (spec 016 C-B1, research.md R14b: 7). All but
  * `hashFailureCounts` are shared with the core's behaviour arms; `hashFailureCounts` is the module's alone, but it is
  * a concrete collection `val`, so rule (d) keeps it in the core.
  */
private[actors] trait ByteCodeResponseHandlingState:
  def activeTasks: mutable.Map[BigInt, ActiveByteCodeRequest]
  var bytecodesDownloaded: Long
  var bytesDownloaded: Long
  var completedTaskCount: Long
  var consecutiveTaskFailures: Int
  def hashFailureCounts: mutable.Map[ByteString, Int]
  def pendingTasks: mutable.Queue[ByteCodeTask]

/** What `ByteCodeResponseHandling` calls in the coordinator core: the completion check. */
private[actors] trait ByteCodeCompletionApi:
  def checkCompletion(): Unit
