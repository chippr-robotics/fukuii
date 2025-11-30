package com.chipprbots.ethereum.console

import org.apache.pekko.actor.ActorSystem

import com.chipprbots.ethereum.utils.Logger

/** Periodically updates the console UI with node status information.
  *
  * @deprecated
  *   Use [[TuiUpdater]] instead. This class is maintained for backward compatibility and delegates to the new TUI
  *   module.
  *
  * This component queries various actors for status information and updates the console UI display.
  */
class ConsoleUIUpdater(
    consoleUI: ConsoleUI,
    peerManager: Option[Any],
    syncController: Option[Any],
    networkName: String,
    shutdownHook: () => Unit
)(implicit _system: ActorSystem)
    extends Logger {

  // Delegate to new TuiUpdater
  private val tuiUpdater: TuiUpdater = new TuiUpdater(
    Tui.getInstance(),
    TuiConfig.default,
    peerManager,
    syncController,
    networkName,
    shutdownHook
  )(using _system)

  /** Start the updater. */
  def start(): Unit =
    tuiUpdater.start()

  /** Stop the updater. */
  def stop(): Unit =
    tuiUpdater.stop()
}
