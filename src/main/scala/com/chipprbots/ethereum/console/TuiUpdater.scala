package com.chipprbots.ethereum.console

import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** Periodically updates the TUI with node status information.
  *
  * A background thread repeats [[tick]]: poll the status source, redraw, then wait up to `updateIntervalMs` for a key
  * press. The key wait IS the frame pacing — a key is handled as soon as it arrives, and with no input the screen still
  * refreshes every interval.
  *
  * @param statusSource
  *   polls the running node; may block briefly (it runs on the updater thread, never on an actor thread) and may throw
  * @param onQuit
  *   invoked once, on a fresh thread, when the user presses `q`. It must stop the node (e.g. `sys.exit`), and it may
  *   call [[stop]] — it does not run on the updater thread, so stopping cannot join itself.
  */
class TuiUpdater(
    tui: Tui,
    config: TuiConfig,
    statusSource: () => NodeStatusSnapshot,
    networkName: String,
    onQuit: () => Unit
) extends Logger:

  @volatile private var running = false
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
      t.join(config.shutdownTimeoutMs)
    }

  /** Whether the update loop is running. */
  def isRunning: Boolean = running

  /** One iteration of the update loop.
    *
    * @return
    *   true to keep looping
    */
  private[console] def tick(): Boolean =
    refreshStatus()
    tui.render()
    tui.checkInput(config.updateIntervalMs) match
      case Some(key) =>
        tui.handleCommand(key) match
          case TuiCommand.Continue => true
          case TuiCommand.Disable =>
            log.info("TUI disabled by user; node continues with standard logging")
            false
          case TuiCommand.Quit =>
            log.info("Quit requested via TUI")
            // Off-thread: onQuit typically exits the JVM, whose shutdown hooks stop this updater and join its thread.
            val quitter = new Thread(() => onQuit(), "TuiQuit")
            quitter.start()
            false
      case None => true

  private def updateLoop(): Unit =
    try
      while running && tui.isEnabled do
        val keepGoing =
          try tick()
          catch
            case _: InterruptedException => false
            case NonFatal(e) =>
              log.error(s"Error in TUI update loop: ${e.getMessage}", e)
              true
        if !keepGoing then running = false
    finally running = false

  private def refreshStatus(): Unit =
    try tui.updateStatus(statusSource())
    catch
      case NonFatal(e) =>
        log.debug(s"TUI status poll failed: ${e.getMessage}")
        tui.updateConnectionStatus(s"Error: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}")

object TuiUpdater:
  /** Create a TUI updater with default configuration. */
  def apply(
      tui: Tui,
      statusSource: () => NodeStatusSnapshot,
      networkName: String,
      onQuit: () => Unit
  ): TuiUpdater =
    new TuiUpdater(tui, TuiConfig.default, statusSource, networkName, onQuit)

  /** Create a TUI updater with custom configuration. */
  def apply(
      tui: Tui,
      config: TuiConfig,
      statusSource: () => NodeStatusSnapshot,
      networkName: String,
      onQuit: () => Unit
  ): TuiUpdater =
    new TuiUpdater(tui, config, statusSource, networkName, onQuit)
