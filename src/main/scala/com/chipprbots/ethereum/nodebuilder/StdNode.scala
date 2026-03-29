package com.chipprbots.ethereum.nodebuilder

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.consensus.mining.StdMiningBuilder
import com.chipprbots.ethereum.console.Tui
import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.console.TuiUpdater
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.ServerActor
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.nodebuilder.tooling.PeriodicConsistencyCheck
import com.chipprbots.ethereum.nodebuilder.tooling.StorageConsistencyChecker
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ConfigValidator
import com.chipprbots.ethereum.utils.Hex

/** A standard node is everything Ethereum prescribes except the mining algorithm, which is plugged in dynamically.
  *
  * The design is historically related to the initial cake-pattern-based
  * [[com.chipprbots.ethereum.nodebuilder.Node Node]].
  *
  * @see
  *   [[com.chipprbots.ethereum.nodebuilder.Node Node]]
  */
abstract class BaseNode extends Node {

  private var tuiUpdater: Option[TuiUpdater] = None

  def start(): Unit = {
    ConfigValidator.validate()

    startMetricsClient()

    fixDatabase()

    checkUncleanShutdown()

    loadGenesisData()

    runDBConsistencyCheck()

    startPeerManager()

    startPortForwarding()

    startServer()

    startSyncController()

    startNodeStatusReporter()

    startMining()

    startDiscoveryManager()

    startJsonRpcHttpServer()

    startJsonRpcIpcServer()

    startJsonRpcWsServer()

    startPeriodicDBConsistencyCheck()

    startTuiUpdater()
  }

  private[this] def startMetricsClient(): Unit = {
    val rootConfig = com.typesafe.config.ConfigFactory.load()
    val fukuiiConfig = rootConfig.getConfig("fukuii")
    val metricsConfig = MetricsConfig(fukuiiConfig)
    Metrics.configure(metricsConfig) match {
      case Success(_) =>
        log.info("Metrics started")

        if (metricsConfig.enabled) {
          val snapSyncEnabled =
            Try(fukuiiConfig.getConfig("sync").getBoolean("do-snap-sync")).getOrElse(false)

          if (snapSyncEnabled) {
            // Ensure app_snapsync_* series exist even before SNAP sync starts.
            val _ = SNAPSyncMetrics
          }
        }
      case Failure(exception) => throw exception
    }
  }

  private[this] def loadGenesisData(): Unit =
    if (!Config.testmode) {
      genesisDataLoader.loadGenesisData()
    }

  private[this] def runDBConsistencyCheck(): Unit = {
    // Skip consistency check after SNAP sync — block headers 0..pivot don't exist yet.
    // SNAP sync only stores the pivot block header; earlier headers are downloaded
    // incrementally during regular sync's block-by-block import.
    if (storagesInstance.storages.appStateStorage.isSnapSyncDone()) {
      log.info("Skipping DB consistency check: SNAP sync stores only pivot block header, not full header chain")
      return
    }
    StorageConsistencyChecker.checkStorageConsistency(
      storagesInstance.storages.appStateStorage.getBestBlockNumber(),
      storagesInstance.storages.blockNumberMappingStorage,
      storagesInstance.storages.blockHeadersStorage,
      shutdown
    )(log)
  }

  private[this] def startPeerManager(): Unit = peerManager ! PeerManagerActor.StartConnecting

  private[this] def startServer(): Unit = server ! ServerActor.StartServer(networkConfig.Server.listenAddress)

  private[this] def startSyncController(): Unit = syncController ! SyncProtocol.Start

  private[this] def startNodeStatusReporter(): Unit = {
    system.actorOf(
      NodeStatusReporter.props(blockchainReader, syncController, peerManager, Config.Db.RocksDb.path),
      "node-status-reporter"
    )
  }

  private[this] def startMining(): Unit = mining.startProtocol(this)

  private[this] def startDiscoveryManager(): Unit = peerDiscoveryManager ! PeerDiscoveryManager.Start

  private[this] def startJsonRpcHttpServer(): Unit =
    maybeJsonRpcHttpServer match {
      case Right(jsonRpcServer) if jsonRpcConfig.httpServerConfig.enabled => jsonRpcServer.run()
      case Left(error) if jsonRpcConfig.httpServerConfig.enabled          => log.error(error)
      case _                                                              => // Nothing
    }

  private[this] def startJsonRpcIpcServer(): Unit =
    if (jsonRpcConfig.ipcServerConfig.enabled) jsonRpcIpcServer.run()

  private[this] def startJsonRpcWsServer(): Unit =
    maybeJsonRpcWsServer.foreach(_.run())

  def startPeriodicDBConsistencyCheck(): Unit =
    if (Config.Db.periodicConsistencyCheck)
      ActorSystem(
        PeriodicConsistencyCheck.start(
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.blockHeadersStorage,
          shutdown
        ),
        "PeriodicDBConsistencyCheck"
      )

  private[this] def startTuiUpdater(): Unit = {
    val tui = Tui.getInstance()
    if (tui.isEnabled) {
      log.info("Starting TUI updater")
      val updater = TuiUpdater(
        tui,
        TuiConfig.default,
        Some(peerManager),
        Some(syncController),
        Config.blockchains.network,
        shutdown
      )(using system)
      tuiUpdater = Some(updater)
      updater.start()
    }
  }

  override def shutdown: () => Unit = () => {
    def tryAndLogFailure(f: () => Any): Unit = Try(f()) match {
      case Failure(e) => log.warn("Error while shutting down...", e)
      case Success(_) =>
    }

    // H-017: Mark clean shutdown and record last safe block before closing DB
    tryAndLogFailure { () =>
      val appState = storagesInstance.storages.appStateStorage
      val bestBlock = appState.getBestBlockNumber()
      appState
        .putCleanShutdown(true)
        .and(appState.putLastSafeBlock(bestBlock))
        .commit()
      log.info("Clean shutdown marker written at block {}", bestBlock)
    }

    tryAndLogFailure(() => tuiUpdater.foreach(_.stop()))
    tryAndLogFailure(() => Tui.getInstance().shutdown())
    tryAndLogFailure(() => peerDiscoveryManager ! PeerDiscoveryManager.Stop)
    tryAndLogFailure(() => mining.stopProtocol())
    tryAndLogFailure(() =>
      Await.ready(
        system
          .terminate()
          .map(
            _ ->
              log.info("actor system finished")
          ),
        shutdownTimeoutDuration
      )
    )
    tryAndLogFailure(() => Await.ready(stopPortForwarding(), shutdownTimeoutDuration))
    if (jsonRpcConfig.ipcServerConfig.enabled) {
      tryAndLogFailure(() => jsonRpcIpcServer.close())
    }
    tryAndLogFailure(() => Metrics.get().close())
    tryAndLogFailure(() => storagesInstance.dataSource.close())
  }

  /** H-017: Check if last shutdown was unclean and rewind chain head if needed.
    * go-ethereum pattern: periodic safe-block markers + clean shutdown flag.
    */
  private[this] def checkUncleanShutdown(): Unit = {
    val appState = storagesInstance.storages.appStateStorage
    val wasClean = appState.isCleanShutdown()
    val lastSafe = appState.getLastSafeBlock()
    val bestBlock = appState.getBestBlockNumber()

    if (!wasClean && lastSafe > 0 && lastSafe < bestBlock) {
      log.warn(
        "Unclean shutdown detected! Best block {} may have inconsistent state. " +
          "Rewinding to last safe block {}.",
        bestBlock,
        lastSafe
      )
      appState.putBestBlockNumber(lastSafe).commit()
      log.info("Chain head rewound from {} to {}", bestBlock, lastSafe)
    } else if (!wasClean && bestBlock > 0) {
      log.warn("Unclean shutdown detected, but no safe block marker found. Proceeding with best block {}.", bestBlock)
    }

    // Clear the flag — it's set to true only during graceful shutdown
    appState.putCleanShutdown(false).commit()
  }

  def fixDatabase(): Unit = {
    val bestBlockInfo = storagesInstance.storages.appStateStorage.getBestBlockInfo()
    if (bestBlockInfo.hash == ByteString.empty && bestBlockInfo.number > 0) {
      log.warn("Fixing best block hash into database for block {}", bestBlockInfo.number)
      storagesInstance.storages.blockNumberMappingStorage.get(bestBlockInfo.number) match {
        case Some(hash) =>
          log.warn("Putting {} as the best block hash", Hex.toHexString(hash.toArray))
          storagesInstance.storages.appStateStorage.putBestBlockInfo(bestBlockInfo.copy(hash = hash)).commit()
        case None =>
          log.error("No block found for number {} when trying to fix database", bestBlockInfo.number)
      }

    }

  }
}

class StdNode extends BaseNode with StdMiningBuilder
