package com.chipprbots.ethereum

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

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
  * Halt, not exit: shutdown hooks may wait on the stuck import. Skipping them is as safe as a `kill -9` at the same
  * point, and a block halted mid-execution is not stored. `persistState` runs after every transaction and every system
  * call, though, so writes can reach the database before the halt: on the modes that write a block's state straight
  * through (archive, `StagedBlockState.direct`, `BlockExecution.executeBlockNoValidation`) its trie nodes and code are
  * written as it runs. They are content-addressed and nothing refers to them until the block is stored. Under basic
  * pruning the block's state and code are staged and committed with the block in one batch (B-454), and a halted block
  * leaves none of them.
  *
  * Skipping the shutdown hooks also skips `TuiRestore`, which takes the terminal out of raw mode and the alternate
  * screen under `--tui`. So a halt first runs the [[beforeHalt]] hook (`Fukuii.main` sets it to the TUI's teardown),
  * bounded by [[BeforeHaltBound]], and then prints its message, on the restored screen.
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

  /** How long a halt waits for the [[beforeHalt]] hook. A terminal restore takes milliseconds; the bound keeps a hook
    * that blocks (the TUI's lock held by a thread that never releases it) from keeping a node up that must stop.
    */
  val BeforeHaltBound: FiniteDuration = 2.seconds

  private val beforeHaltHook = new AtomicReference[() => Unit](() => ())

  /** Sets what a halt runs before it prints its message and halts: `Fukuii.main` restores the terminal under `--tui`,
    * which the skipped `TuiRestore` shutdown hook would otherwise have done. The default does nothing.
    */
  def beforeHalt(hook: () => Unit): Unit = beforeHaltHook.set(hook)

  /** Runs the [[beforeHalt]] hook on a daemon thread and waits for it at most `bound`. A hook that throws is logged;
    * the halt goes ahead whatever the hook did.
    */
  private[ethereum] def runBeforeHalt(bound: FiniteDuration = BeforeHaltBound): Unit =
    val hook = beforeHaltHook.get()
    val runner = new Thread(
      () =>
        try hook()
        catch case NonFatal(e) => log.error(s"The before-halt hook failed: $e", e),
      "FatalErrors-beforeHalt"
    )
    runner.setDaemon(true)
    runner.start()
    runner.join(bound.toMillis)
    if runner.isAlive then log.error(s"The before-halt hook did not finish within $bound; halting without it")

  /** Applies [[decide]]. `halt` and `report` are parameters so that a test can run this without stopping its JVM. A
    * halt logs the error, runs the [[beforeHalt]] hook (the terminal restore), then prints the message and halts.
    */
  def handle(thread: Thread, error: Throwable, halt: Int => Unit, report: (Thread, Throwable) => Unit): Unit =
    decide(thread.getName, error) match
      case Action.Halt(message) =>
        log.error(message, error)
        runBeforeHalt()
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
