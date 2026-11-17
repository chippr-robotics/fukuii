package com.chipprbots.ethereum.console.ops

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** An executor that runs what it is given at once, on the caller's thread: an [[ActionRunner]] built on it has done
  * everything it was asked by the time the call returns, so a spec reads what it published with no waiting.
  */
final private[ops] class SameThread extends AbstractExecutorService:
  def execute(task: Runnable): Unit = task.run()
  def shutdown(): Unit = ()
  def shutdownNow(): java.util.List[Runnable] = java.util.List.of()
  def isShutdown: Boolean = false
  def isTerminated: Boolean = false
  def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
