package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.duration.*
import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** A node's stop as named steps run in order, each timed and logged (B-426).
  *
  * A Mordor node's SIGTERM stop logged only `TimeoutException 15000 ms` from the actor system's wait: nothing said
  * which step was slow, or what it waited for, and the steps after it went on with the actor system still running. Here
  * every step logs at INFO how long it took; a step past its budget is named at WARN and the stop goes on without it,
  * its thread left to finish (and to say so when it does); a step with no budget, the database's close, is waited for
  * to its end, with a WARN each interval it is still running. go-ethereum's stop does the same with the database: it
  * stops the writers, then gives the close all the time it needs.
  *
  * Each step runs on a thread of its own, so a step that blocks cannot hold up the steps after it, and only the budget
  * decides how long the stop waits for it.
  */
object ShutdownSequence extends Logger:

  /** How long the stop waits for a step. */
  enum Budget:
    /** Waited for up to `limit`; past it the stop goes on, and the step keeps running on its thread. */
    case Bounded(limit: FiniteDuration)

    /** Waited for to its end, however long it takes: never left half-way. A WARN each `noticeEvery` it still runs. */
    case Unbounded(noticeEvery: FiniteDuration)

  object Budget:
    /** [[Budget.Bounded]] for a finite `limit`; [[Budget.Unbounded]] for an infinite one (`shutdown-timeout = Inf`). */
    def upTo(limit: Duration, noticeEvery: FiniteDuration = DefaultNotice): Budget = limit match
      case finite: FiniteDuration => Bounded(finite)
      case _                      => Unbounded(noticeEvery)

  /** The interval of the WARN for a step that has no budget and is still running. */
  val DefaultNotice: FiniteDuration = 10.seconds

  /** One step of the stop.
    *
    * @param onOverrun
    *   run once, on the stop's thread, when the step passes its budget: what the step waits for, for the log
    */
  final case class Step(name: String, budget: Budget, run: () => Unit, onOverrun: () => Option[String] = () => None)

  enum Result:
    case Done(elapsed: FiniteDuration)
    case Failed(elapsed: FiniteDuration, error: Throwable)
    case Overran(limit: FiniteDuration)

  final case class Outcome(results: Seq[(String, Result)]):
    def resultOf(step: String): Option[Result] = results.collectFirst { case (`step`, result) => result }

    /** Whether `step` ran to its end without failing. */
    def completed(step: String): Boolean = resultOf(step).exists {
      case Result.Done(_) => true
      case _              => false
    }

    /** The steps that failed or passed their budget. */
    def troubled: Seq[String] = results.collect {
      case (name, Result.Failed(_, _)) => name
      case (name, Result.Overran(_))   => name
    }

  /** Where the stop's lines go: the node's log, or a test's list. */
  trait Report:
    def info(message: String): Unit
    def warn(message: String, error: Option[Throwable] = None): Unit

  object Report:
    val log: Report = new Report:
      def info(message: String): Unit = ShutdownSequence.log.info(message)
      def warn(message: String, error: Option[Throwable]): Unit = error match
        case Some(e) => ShutdownSequence.log.warn(message, e)
        case None    => ShutdownSequence.log.warn(message)

  /** The longest the bounded steps can hold the stop up, all together. Steps with no budget come on top. */
  def boundedTotal(steps: Seq[Step]): FiniteDuration =
    steps.foldLeft(Duration.Zero: FiniteDuration) { (sum, step) =>
      step.budget match
        case Budget.Bounded(limit) => sum + limit
        case Budget.Unbounded(_)   => sum
    }

  /** Runs `steps` in order and says how each went. Never throws: a step's failure is logged and the next step runs. */
  def run(steps: Seq[Step], report: Report = Report.log): Outcome =
    val unbounded = steps.collect { case Step(name, Budget.Unbounded(_), _, _) => name }
    report.info(
      s"[SHUTDOWN] Stopping the node: ${steps.size} steps; those with a budget take at most " +
        s"${boundedTotal(steps).toSeconds} s in all" +
        (if unbounded.isEmpty then "" else s", then ${unbounded.mkString(", ")}, waited for to the end")
    )
    val started = System.nanoTime()
    val results = steps.map(step => step.name -> runStep(step, report))
    val outcome = Outcome(results)
    val took = elapsedSince(started)
    if outcome.troubled.isEmpty then report.info(s"[SHUTDOWN] The node stopped in ${took.toMillis} ms")
    else
      report.warn(
        s"[SHUTDOWN] The node stopped in ${took.toMillis} ms; failed or past their budget: " +
          outcome.troubled.mkString(", ")
      )
    outcome

  private def runStep(step: Step, report: Report): Result =
    val started = System.nanoTime()
    val finished = new CompletableFuture[Unit]()
    val thread = new Thread(
      () =>
        try
          step.run()
          finished.complete(()): Unit
        catch case e: Throwable => finished.completeExceptionally(e): Unit, s"shutdown-${step.name.replace(' ', '-')}"
    )
    // A step left running past its budget must not keep the JVM alive.
    thread.setDaemon(true)
    thread.start()

    def outcome(): Result =
      val took = elapsedSince(started)
      try
        finished.get(): Unit
        report.info(s"[SHUTDOWN] ${step.name}: done in ${took.toMillis} ms")
        Result.Done(took)
      catch
        case e: java.util.concurrent.ExecutionException =>
          val cause = Option(e.getCause).getOrElse(e)
          report.warn(s"[SHUTDOWN] ${step.name}: failed after ${took.toMillis} ms", Some(cause))
          Result.Failed(took, cause)

    step.budget match
      case Budget.Bounded(limit) =>
        if waitFor(finished, limit) then outcome()
        else
          val waitingOn =
            try step.onOverrun()
            catch case NonFatal(e) => Some(s"(could not say what it waits for: $e)")
          report.warn(
            s"[SHUTDOWN] ${step.name}: still running after its budget of ${limit.toMillis} ms; the stop goes on " +
              "without it" + waitingOn.fold("")(w => s". It waits for:\n$w")
          )
          // Said when it does end, so the log shows by how much the budget was short.
          finished.whenComplete { (_, error) =>
            val took = elapsedSince(started).toMillis
            if error == null then report.info(s"[SHUTDOWN] ${step.name}: ended after all, ${took} ms after it began")
            else report.warn(s"[SHUTDOWN] ${step.name}: failed after all, ${took} ms after it began", Some(error))
          }: Unit
          Result.Overran(limit)
      case Budget.Unbounded(noticeEvery) =>
        while !waitFor(finished, noticeEvery) do
          report.warn(
            s"[SHUTDOWN] ${step.name}: still running after ${elapsedSince(started).toSeconds} s; waited for to its end"
          )
        outcome()

  /** Whether `finished` completed (either way) within `limit`. */
  private def waitFor(finished: CompletableFuture[Unit], limit: FiniteDuration): Boolean =
    try
      finished.get(limit.toNanos, TimeUnit.NANOSECONDS): Unit
      true
    catch
      case _: TimeoutException                        => false
      case _: java.util.concurrent.ExecutionException => true

  private def elapsedSince(startNanos: Long): FiniteDuration = (System.nanoTime() - startNanos).nanos

  /** The stacks of the threads whose name starts with `prefix` and that are busy: not a pool worker parked waiting for
    * work. For a step that passed its budget waiting for those threads (an actor system that does not terminate is
    * waiting for an actor whose message has not returned), so the log names the call that holds it up.
    */
  def busyThreadStacks(prefix: String, maxFrames: Int = 15): Option[String] =
    import scala.jdk.CollectionConverters.*
    val busy = Thread.getAllStackTraces.asScala.toSeq
      .filter { case (thread, stack) => thread.getName.startsWith(prefix) && !isIdleWorker(stack.toSeq) }
      .sortBy(_._1.getName)
    Option.when(busy.nonEmpty) {
      busy
        .map { case (thread, stack) =>
          val frames = stack.take(maxFrames).map(f => s"    at $f").mkString("\n")
          s"  \"${thread.getName}\" ${thread.getState}\n$frames"
        }
        .mkString("\n")
    }

  /** Whether a thread with this stack is a pool worker parked waiting for work: below the parking frames, its first
    * frame is the pool's own wait for a task.
    */
  private[nodebuilder] def isIdleWorker(stack: Seq[StackTraceElement]): Boolean =
    val parking =
      Set("jdk.internal.misc.Unsafe", "java.util.concurrent.locks.LockSupport", "java.lang.Object", "java.lang.Thread")
    stack.dropWhile(f => parking.contains(f.getClassName)).headOption match
      case None => true
      case Some(frame) =>
        frame.getClassName.startsWith("java.util.concurrent.ForkJoinPool") ||
        // Pekko's scheduler thread, sleeping until its next tick
        frame.getClassName.contains("LightArrayRevolverScheduler") ||
        (frame.getClassName.startsWith("java.util.concurrent.") && frame.getMethodName == "take") ||
        (frame.getClassName.startsWith("java.util.concurrent.") && frame.getMethodName == "poll") ||
        frame.getMethodName == "getTask"
