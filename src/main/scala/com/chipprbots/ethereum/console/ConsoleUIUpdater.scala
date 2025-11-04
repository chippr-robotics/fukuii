package com.chipprbots.ethereum.console

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.Logger

/** Periodically updates the console UI with node status information.
  *
  * This component queries various actors for status information and updates the console UI display.
  */
class ConsoleUIUpdater(
    consoleUI: ConsoleUI,
    peerManager: Option[ActorRef[PeerManagerActor.PeerManagementCommand]],
    syncController: Option[ActorRef[SyncProtocol.Command]],
    networkName: String
)(implicit system: ActorSystem[_])
    extends Logger {

  private var running = false
  private var updateThread: Option[Thread] = None

  /** Start the updater. */
  def start(): Unit = {
    if (!consoleUI.isEnabled) {
      log.info("Console UI is disabled, not starting updater")
      return
    }

    log.info("Starting Console UI updater")
    running = true

    // Update network name immediately
    consoleUI.updateNetwork(networkName)

    // Start update thread
    updateThread = Some(new Thread(() => updateLoop(), "console-ui-updater"))
    updateThread.foreach(_.start())
  }

  /** Stop the updater. */
  def stop(): Unit = {
    log.info("Stopping Console UI updater")
    running = false
    updateThread.foreach { thread =>
      thread.interrupt()
      thread.join(1000)
    }
    updateThread = None
  }

  /** Main update loop. */
  private def updateLoop(): Unit = {
    try {
      while (running && consoleUI.isEnabled) {
        try {
          // Update status information
          updateStatus()

          // Render the UI
          consoleUI.render()

          // Check for keyboard input
          consoleUI.checkInput() match {
            case Some(cmd) =>
              val shouldContinue = consoleUI.handleCommand(cmd)
              if (!shouldContinue) {
                log.info("Quit requested via console UI")
                // Shutdown the application
                IO(System.exit(0)).unsafeRunSync()
              }
            case None => // No input
          }

          // Sleep for a bit
          Thread.sleep(1000)
        } catch {
          case _: InterruptedException =>
            // Thread interrupted, exit loop
            running = false
          case e: Exception =>
            log.error(s"Error in console UI update loop: ${e.getMessage}", e)
            Thread.sleep(1000)
        }
      }
    } finally {
      consoleUI.shutdown()
    }
  }

  /** Update status information from various sources. */
  private def updateStatus(): Unit = {
    // In a real implementation, we would query actors for status
    // For now, we'll just update some placeholder values
    
    // Update connection status based on whether managers are defined
    if (peerManager.isDefined && syncController.isDefined) {
      consoleUI.updateConnectionStatus("Connected")
    } else {
      consoleUI.updateConnectionStatus("Initializing")
    }

    // Note: In a production implementation, we would need to:
    // 1. Query PeerManagerActor for peer count
    // 2. Query SyncController for sync status and block info
    // 3. Use Ask pattern or some other mechanism to get this information
    // 
    // For this initial implementation, we're setting up the structure.
    // The actual actor queries would be added in integration.
  }
}
