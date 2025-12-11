package com.chipprbots.ethereum.console

import org.apache.pekko.actor.ActorSystem

import com.chipprbots.ethereum.utils.Logger

/** Periodically updates the TUI with node status information.
  *
  * This component queries various actors for status information and updates the TUI display. It runs a background
  * thread that handles both the update loop and keyboard input.
  */
class TuiUpdater(
    tui: Tui,
    config: TuiConfig,
    peerManager: Option[Any],
    syncController: Option[Any],
    networkName: String,
    shutdownHook: () => Unit
)(using _system: ActorSystem)
    extends Logger:

  private var running = false
  private var updateThread: Option[Thread] = None

  /** Start the updater. */
  def start(): Unit =
    if !tui.isEnabled then
      log.info("TUI is disabled, not starting updater")
      return

    log.info("Starting TUI updater")
    running = true

    // Update network name immediately
    tui.updateNetwork(networkName)

    // Start update thread
    updateThread = Some(new Thread(() => updateLoop(), "TuiUpdateThread"))
    updateThread.foreach(_.start())

  /** Stop the updater. */
  def stop(): Unit =
    log.info("Stopping TUI updater")
    running = false
    updateThread.foreach { thread =>
      thread.interrupt()
      thread.join(config.shutdownTimeoutMs)
    }
    updateThread = None

  /** Main update loop. */
  private def updateLoop(): Unit =
    try
      while running && tui.isEnabled do
        try
          // Update status information
          updateStatus()

          // Render the UI
          tui.render()

          // Check for keyboard input
          tui.checkInput() match
            case Some(cmd) =>
              val shouldContinue = tui.handleCommand(cmd)
              if !shouldContinue then
                log.info("Quit requested via TUI")
                // Use the provided shutdown hook for graceful shutdown
                shutdownHook()
            case None => // No input
          // Sleep for a bit
          Thread.sleep(config.updateIntervalMs)
        catch
          case _: InterruptedException =>
            // Thread interrupted, exit loop
            running = false
          case e: Exception =>
            log.error(s"Error in TUI update loop: ${e.getMessage}", e)
            Thread.sleep(config.updateIntervalMs)
    finally {
      // Shutdown is handled in StdNode.shutdown() to avoid race conditions
    }

  /** Update status information from various sources. */
  private def updateStatus(): Unit =
    // Update connection status based on whether managers are defined
    if peerManager.isDefined && syncController.isDefined then tui.updateConnectionStatus("Connected")
    else tui.updateConnectionStatus("Initializing")

    // Note: In a production implementation, we would need to:
    // 1. Query PeerManagerActor for peer count
    // 2. Query SyncController for sync status and block info
    // 3. Use Ask pattern or some other mechanism to get this information
    //
    // For this initial implementation, we're setting up the structure.
    // The actual actor queries would be added in integration.

object TuiUpdater:
  /** Create a TUI updater with default configuration. */
  def apply(
      tui: Tui,
      peerManager: Option[Any],
      syncController: Option[Any],
      networkName: String,
      shutdownHook: () => Unit
  )(using system: ActorSystem): TuiUpdater =
    new TuiUpdater(tui, TuiConfig.default, peerManager, syncController, networkName, shutdownHook)

  /** Create a TUI updater with custom configuration. */
  def apply(
      tui: Tui,
      config: TuiConfig,
      peerManager: Option[Any],
      syncController: Option[Any],
      networkName: String,
      shutdownHook: () => Unit
  )(using system: ActorSystem): TuiUpdater =
    new TuiUpdater(tui, config, peerManager, syncController, networkName, shutdownHook)
