package com.chipprbots.ethereum.console

import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** The TUI's frame loop (spec 027 FR-010, research §7.2).
  *
  * A background thread repeats [[tick]]: count a frame and take the latest snapshot, redraw, then wait for a key at the
  * frame's pace — `animationIntervalMs` (250 ms) while anything on screen moves, `updateIntervalMs` (1000 ms) otherwise
  * (Q1). The key wait IS the frame pacing: a key is handled as soon as it arrives, at either pace, and with no input
  * the screen still refreshes.
  *
  * Polling is not done here: [[TuiProbeThread]] publishes snapshots, and the loop only reads the latest one, so a slow
  * actor never delays a frame (FR-011).
  *
  * @param snapshots
  *   the latest published snapshot, if any; a read that never blocks
  * @param onQuit
  *   invoked once, on a fresh thread, when the user presses `q`. It must stop the node (e.g. `sys.exit`), and it may
  *   call [[stop]] — it does not run on the updater thread, so stopping cannot join itself. An exit should restore the
  *   terminal first: wrap it in [[TuiUpdater.restoreThenQuit]].
  */
class TuiUpdater(
    tui: Tui,
    config: TuiConfig,
    snapshots: () => Option[NodeStatusSnapshot],
    networkName: String,
    onQuit: () => Unit
) extends Logger:

  @volatile private var running = false

  /** Frames that failed in a row; the loop gives up at [[TuiUpdater.MaxFailedFrames]]. */
  private var failedFrames = 0
  private var updateThread: Option[Thread] = None

  /** Start the updater. */
  def start(): Unit = synchronized {
    if !tui.isEnabled then log.info("TUI is disabled, not starting updater")
    else if updateThread.isEmpty then
      log.info("Starting TUI updater")
      running = true
      tui.updateNetwork(networkName)
      val thread = new Thread(() => updateLoop(), "TuiUpdateThread")
      thread.setDaemon(true)
      updateThread = Some(thread)
      thread.start()
  }

  /** Stop the updater. Safe to call from any thread, including the updater's own. */
  def stop(): Unit =
    val thread = synchronized {
      running = false
      val t = updateThread
      updateThread = None
      t
    }
    thread.filterNot(_ eq Thread.currentThread()).foreach { t =>
      log.info("Stopping TUI updater")
      t.interrupt()
      // Once the TUI is down (the TuiRestore hook ran first) there is nothing to wait for, and a key read blocked in
      // native code ignores the interrupt: joining would only add the timeout to the node's shutdown.
      if tui.isEnabled then t.join(config.shutdownTimeoutMs)
    }

  /** Whether the update loop is running. */
  def isRunning: Boolean = running

  /** One frame: the counter moves on and the latest snapshot applies, the frame is drawn, then a key is awaited at the
    * pace the frame asks for.
    *
    * @return
    *   true to keep looping
    */
  private[console] def tick(): Boolean =
    tui.nextFrame(snapshots())
    val animated = tui.render()
    // Every key read is handled, in order, up to the first that ends the loop.
    tui.checkInput(config.paceMs(animated)).iterator.map(tui.handleKey).find(_ != TuiCommand.Continue) match
      case Some(TuiCommand.Disable) =>
        log.info("TUI disabled by user; node continues with standard logging")
        false
      case Some(TuiCommand.Quit) =>
        log.info("Quit requested via TUI")
        // Off-thread: onQuit typically exits the JVM, whose shutdown hooks stop this updater and join its thread.
        val quitter = new Thread(() => onQuit(), "TuiQuit")
        quitter.start()
        false
      case _ => true

  private def updateLoop(): Unit =
    // A throwable that is not NonFatal ends the loop; the finally names it. It is rethrown unchanged.
    var cause: Option[Throwable] = None
    try
      // An interrupt ends the loop: checkInput keeps it set, and every later key read would fail at once.
      while running && tui.isEnabled && !Thread.currentThread().isInterrupted do
        val keepGoing =
          try
            val more = tick()
            failedFrames = 0
            more
          catch
            case _: InterruptedException =>
              // Ends the loop without clearing `running`, so the finally tells stop()'s interrupt from any other.
              Thread.currentThread().interrupt()
              true
            case NonFatal(e) => frameFailed(e)
        if !keepGoing then running = false
    catch
      case e: Throwable =>
        cause = Some(e)
        throw e
    finally
      // stop(), q, d and the give-up all clear `running` first. Anything else that ends the loop (an interrupt from
      // elsewhere, a throwable that is not NonFatal) would leave the terminal in raw mode under a frozen frame.
      if running && tui.isEnabled then
        Thread.interrupted() // the teardown writes to the terminal; do not hand it an interrupted thread
        // A failed restore is logged, and neither skips `running = false` nor replaces the throwable that ended the loop.
        try tui.shutdown()
        catch
          case NonFatal(e) =>
            log.error("Error restoring the terminal after the TUI frame loop ended: {}", e.getMessage, e)
        log.warn(
          "TUI frame loop ended without being stopped ({}); the terminal and standard logging are restored",
          cause.fold("interrupted")(_.toString)
        )
      running = false

  /** A frame failed. A failure before the key wait is retried at once, so a repeated one is given up on: the terminal
    * and the console logs go back, and the node keeps running. The first is logged with its stack, the repeats at
    * DEBUG.
    *
    * @return
    *   whether to keep looping
    */
  private def frameFailed(e: Throwable): Boolean =
    failedFrames += 1
    if failedFrames == 1 then log.error("Error in TUI update loop: {}", e.getMessage, e)
    else log.debug("Error in TUI update loop again ({} in a row): {}", failedFrames, e.getMessage)
    if failedFrames < TuiUpdater.MaxFailedFrames then true
    else
      tui.shutdown()
      log.error(
        "TUI update loop failed {} frames in a row; the terminal and standard logging are restored",
        failedFrames
      )
      false

object TuiUpdater extends Logger:

  /** Frames that may fail in a row before the loop gives the terminal back. */
  val MaxFailedFrames: Int = 10

  /** The `q` action: restore the terminal, then `exit`.
    *
    * The restore runs here, before the exit, and is not left to a shutdown hook: JVM shutdown hooks run concurrently
    * and in no order, and JLine's own hook can close the terminal before [[Tui.shutdown]] gets to leave the alternate
    * screen. The later shutdown-hook call then finds the TUI already shut down and does nothing.
    */
  def restoreThenQuit(tui: Tui, exit: () => Unit): () => Unit = () =>
    try tui.shutdown()
    catch case NonFatal(e) => log.error(s"Error restoring the terminal before quitting: ${e.getMessage}", e)
    exit()

  /** Create a TUI updater with default configuration. */
  def apply(
      tui: Tui,
      snapshots: () => Option[NodeStatusSnapshot],
      networkName: String,
      onQuit: () => Unit
  ): TuiUpdater =
    new TuiUpdater(tui, TuiConfig.default, snapshots, networkName, onQuit)

  /** Create a TUI updater with custom configuration. */
  def apply(
      tui: Tui,
      config: TuiConfig,
      snapshots: () => Option[NodeStatusSnapshot],
      networkName: String,
      onQuit: () => Unit
  ): TuiUpdater =
    new TuiUpdater(tui, config, snapshots, networkName, onQuit)
