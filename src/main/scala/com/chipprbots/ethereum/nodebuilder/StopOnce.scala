package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Try

/** A node's stop, run once, whose outcome every caller can wait for (B-426).
  *
  * The first call runs the stop on its own thread. A call made while it runs waits for it to end, then sees how it
  * ended: the SIGTERM handler that comes second, after a failed start or an error began the stop, would otherwise exit
  * while the database's close is still under way, and cut it. Two calls never wait on each other:
  *   - a call made on the thread that runs the stop (the stop re-entered) returns at once, as it cannot wait for
  *     itself;
  *   - [[runOrLeave]] does not wait: the JVM's shutdown hook uses it, so that the exit after a failed start, whose stop
  *     overran its bound, is not held up by that stop (B-274).
  */
final private[nodebuilder] class StopOnce[A](stop: () => A):
  private val started = new AtomicBoolean(false)
  private val ended = new CountDownLatch(1)
  @volatile private var stopping: Option[Thread] = None
  @volatile private var result: Option[Try[A]] = None

  /** Runs the stop if no call has, or waits for the one that did. How it ended: `None` while it has not (a call on the
    * stop's own thread), or when it ended on a fatal error. The call that runs the stop throws what the stop threw, as
    * a direct call of it would; a call that waited gets it as the `Failure`.
    *
    * An interrupt does not end the wait (B-452): its caller exits next, and exiting while the stop still closes the
    * database would cut the close. The wait goes on to the stop's end, which its own bounds limit, and the interrupt is
    * set again when it returns.
    */
  def run(): Option[Try[A]] = runThen(waitForIt = true)

  /** Runs the stop if no call has; otherwise returns at once, without waiting for it. */
  def runOrLeave(): Unit = runThen(waitForIt = false): Unit

  /** How the stop ended, once it has: its result, or the error it threw. */
  def outcome: Option[Try[A]] = result

  private def runThen(waitForIt: Boolean): Option[Try[A]] =
    if started.compareAndSet(false, true) then
      stopping = Some(Thread.currentThread())
      try result = Some(Try(stop()))
      finally ended.countDown()
      result.foreach(_.get)
      result
    else if !waitForIt || stopping.contains(Thread.currentThread()) then result
    else
      awaitEnd()
      result

  /** Waits for the stop to end, through any interrupt, which is set again afterwards. */
  private def awaitEnd(): Unit =
    var interrupted = false
    var done = false
    while !done do
      try
        ended.await()
        done = true
      catch case _: InterruptedException => interrupted = true
    if interrupted then Thread.currentThread().interrupt()
