package com.chipprbots.ethereum

import scala.annotation.tailrec

import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.NativeBackends
import com.chipprbots.ethereum.vm.NativeBackends.NativeBackendUnavailable

/** What the node does with an error it must not survive: a precompile's native backend that is missing at call time
  * (B-135).
  *
  * Throwing [[NativeBackendUnavailable]] does not stop the node by itself. Block import (`BlockImporter.importWith`)
  * and the JSON-RPC and Engine API servers run the VM inside Cats Effect on `IORuntime.global`. Cats Effect treats the
  * error as fatal: it shuts its runtimes down and rethrows on its compute thread. That thread has its own
  * uncaught-exception handler, which prints the stack trace to stderr, so the JVM default handler never sees the error.
  * The JVM stays up, the importer stays in its importing state, and the log says nothing (FatalErrorsSpec runs this in
  * a child JVM).
  *
  * So the node halts where the error is raised. [[installCallTimeHalt]] registers a [[NativeBackends.onUnavailable]]
  * hook, which logs the error and halts the JVM before the error is thrown, on whatever thread raises it.
  * [[installUncaughtHandler]] covers the threads that do use the JVM default handler. A thread that dies of a
  * NativeBackendUnavailable anywhere in its cause chain halts the JVM. A thread that dies of another
  * [[VirtualMachineError]] is logged at ERROR and is still reported to stderr as before.
  *
  * Halt, not exit: shutdown hooks may wait on the stuck import. Skipping them loses nothing, because a block's state is
  * committed in one batch after it executes, so a block halted mid-execution leaves nothing behind.
  */
object FatalErrors extends Logger:

  /** What happens to an error that reached the top of a thread. */
  enum Action:
    /** Log it at ERROR and to stderr, then halt the JVM with status 1. */
    case Halt(message: String)

    /** Log it at ERROR, then report it as the JVM would. */
    case Report(message: String)

    /** Report it as the JVM would. */
    case Default

  /** The action for `error`, which ended thread `threadName`. */
  def decide(threadName: String, error: Throwable): Action =
    unavailableIn(error) match
      case Some(unavailable) => Action.Halt(haltMessage(threadName, unavailable))
      case None =>
        error match
          case _: VirtualMachineError => Action.Report(s"Thread $threadName died of $error")
          case _                      => Action.Default

  /** The [[NativeBackendUnavailable]] in `error`'s cause chain, if there is one. */
  def unavailableIn(error: Throwable): Option[NativeBackendUnavailable] =
    @tailrec
    def loop(current: Throwable, seen: Set[Throwable]): Option[NativeBackendUnavailable] =
      current match
        case unavailable: NativeBackendUnavailable => Some(unavailable)
        case _ =>
          Option(current.getCause) match
            case Some(cause) if !seen.contains(cause) => loop(cause, seen + current)
            case _                                    => None
    loop(error, Set.empty)

  private def haltMessage(threadName: String, unavailable: NativeBackendUnavailable): String =
    val cause = Option(unavailable.getCause).fold("")(cause => s" ($cause)")
    s"${unavailable.backend.library} is unavailable at call time, on thread $threadName: ${unavailable.detail}$cause. " +
      s"It runs the ${unavailable.backend.precompiles}; without it this node computes different results from the " +
      "network. Halting."

  /** Applies [[decide]]. `halt` and `report` are parameters so that a test can run this without stopping its JVM. */
  def handle(thread: Thread, error: Throwable, halt: Int => Unit, report: (Thread, Throwable) => Unit): Unit =
    decide(thread.getName, error) match
      case Action.Halt(message) =>
        log.error(message, error)
        System.err.println(message)
        System.err.flush()
        halt(1)
      case Action.Report(message) =>
        log.error(message, error)
        report(thread, error)
      case Action.Default =>
        report(thread, error)

  /** Installs [[handle]] as the JVM default uncaught-exception handler. A default handler installed before this one
    * still receives every error that is reported. Without one, the error is printed the way the JVM prints it.
    */
  def installUncaughtHandler(halt: Int => Unit = code => Runtime.getRuntime.halt(code)): Unit =
    val report: (Thread, Throwable) => Unit = Option(Thread.getDefaultUncaughtExceptionHandler) match
      case Some(previous) => (thread, error) => previous.uncaughtException(thread, error)
      case None           => jvmReport
    Thread.setDefaultUncaughtExceptionHandler((thread, error) => handle(thread, error, halt, report))

  /** Halts the JVM at the point where a precompile finds its native backend missing (see the object doc). */
  def installCallTimeHalt(halt: Int => Unit = code => Runtime.getRuntime.halt(code)): Unit =
    NativeBackends.onUnavailable(unavailable => handle(Thread.currentThread(), unavailable, halt, (_, _) => ()))

  /** What `ThreadGroup.uncaughtException` prints when no default handler is installed. */
  private def jvmReport(thread: Thread, error: Throwable): Unit =
    System.err.print(s"Exception in thread \"${thread.getName}\" ")
    error.printStackTrace(System.err)
