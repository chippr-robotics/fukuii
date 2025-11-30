package com.chipprbots.ethereum.console

import com.chipprbots.ethereum.utils.Logger

/** Enhanced console UI for monitoring Fukuii node status.
  *
  * @deprecated
  *   Use [[Tui]] instead. This class is maintained for backward compatibility and delegates to the new TUI module.
  *
  * Provides a grid-based terminal interface with:
  *   - Real-time peer connection status
  *   - Network information
  *   - Block sync progress
  *   - Keyboard commands
  */
class ConsoleUI extends Logger {

  import ConsoleUI._

  // Delegate to new TUI module
  private val tui: Tui = new Tui(TuiConfig.default)

  /** Initialize the console UI. */
  def initialize(): Unit =
    tui.initialize()

  /** Disable the console UI. */
  def disable(): Unit =
    tui.disable()

  /** Check if the console UI is enabled. */
  def isEnabled: Boolean = tui.isEnabled

  /** Update the peer count. */
  def updatePeerCount(count: Int, max: Int): Unit =
    tui.updatePeerCount(count, max)

  /** Update the block information. */
  def updateBlockInfo(current: Long, best: Long): Unit =
    tui.updateBlockInfo(current, best)

  /** Update the network name. */
  def updateNetwork(name: String): Unit =
    tui.updateNetwork(name)

  /** Update the sync status. */
  def updateSyncStatus(status: String): Unit =
    tui.updateSyncStatus(status)

  /** Update the connection status. */
  def updateConnectionStatus(status: String): Unit =
    tui.updateConnectionStatus(status)

  /** Render the console UI. */
  def render(): Unit =
    tui.render()

  /** Check for keyboard input (non-blocking). */
  def checkInput(): Option[Char] =
    tui.checkInput()

  /** Handle keyboard commands. Returns true if should continue, false if should quit. */
  def handleCommand(command: Char): Boolean =
    tui.handleCommand(command)

  /** Shutdown and cleanup the console UI. */
  def shutdown(): Unit =
    tui.shutdown()
}

object ConsoleUI {
  // Configuration constants (for backward compatibility)
  private[console] val UPDATE_INTERVAL_MS: Long = TuiConfig.DefaultUpdateIntervalMs
  private[console] val BANNER_DISPLAY_DURATION_MS: Long = TuiConfig.DefaultBannerDisplayDurationMs
  private[console] val SHUTDOWN_TIMEOUT_MS: Long = TuiConfig.DefaultShutdownTimeoutMs

  // Singleton instance
  private var instance: Option[ConsoleUI] = None

  /** Get or create the singleton instance.
    *
    * @deprecated
    *   Use [[Tui.getInstance()]] instead.
    */
  def getInstance(): ConsoleUI =
    instance match {
      case Some(ui) => ui
      case None =>
        val ui = new ConsoleUI()
        instance = Some(ui)
        ui
    }

  /** Reset the singleton instance (useful for testing). */
  def reset(): Unit = {
    instance.foreach(_.shutdown())
    instance = None
    Tui.reset()
  }
}
