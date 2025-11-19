package com.chipprbots.ethereum.nodebuilder

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.mining.StdMiningBuilder
import com.chipprbots.ethereum.console.ConsoleUI
import com.chipprbots.ethereum.console.ConsoleUIUpdater
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.ServerActor
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.nodebuilder.tooling.PeriodicConsistencyCheck
import com.chipprbots.ethereum.nodebuilder.tooling.StorageConsistencyChecker
import com.chipprbots.ethereum.utils.Config
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

  private var consoleUIUpdater: Option[ConsoleUIUpdater] = None

  def start(): Unit = {
    startMetricsClient()

    fixDatabase()

    loadGenesisData()

    runDBConsistencyCheck()

    startPeerManager()

    startPortForwarding()

    startServer()

    startSyncController()

    startMining()

    startDiscoveryManager()

    startJsonRpcHttpServer()

    startJsonRpcIpcServer()

    startPeriodicDBConsistencyCheck()

    startConsoleUIUpdater()
  }

  private[this] def startMetricsClient(): Unit = {
    val rootConfig = com.typesafe.config.ConfigFactory.load()
    val fukuiiConfig = rootConfig.getConfig("fukuii")
    val metricsConfig = MetricsConfig(fukuiiConfig)
    Metrics.configure(metricsConfig) match {
      case Success(_) =>
        log.info("Metrics started")
      case Failure(exception) => throw exception
    }
  }

  private[this] def loadGenesisData(): Unit =
    if (!Config.testmode) {
      genesisDataLoader.loadGenesisData()
      bootstrapCheckpointLoader.loadBootstrapCheckpoints()
    }

  private[this] def runDBConsistencyCheck(): Unit =
    StorageConsistencyChecker.checkStorageConsistency(
      storagesInstance.storages.appStateStorage.getBestBlockNumber(),
      storagesInstance.storages.blockNumberMappingStorage,
      storagesInstance.storages.blockHeadersStorage,
      shutdown
    )(log)

  private[this] def startPeerManager(): Unit = peerManager ! PeerManagerActor.StartConnecting

  private[this] def startServer(): Unit = server ! ServerActor.StartServer(networkConfig.Server.listenAddress)

  private[this] def startSyncController(): Unit = syncController ! SyncProtocol.Start

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

  private[this] def startConsoleUIUpdater(): Unit = {
    val consoleUI = ConsoleUI.getInstance()
    if (consoleUI.isEnabled) {
      log.info("Starting Console UI updater")
      val updater = new ConsoleUIUpdater(
        consoleUI,
        Some(peerManager),
        Some(syncController),
        Config.blockchains.network,
        shutdown
      )(system)
      consoleUIUpdater = Some(updater)
      updater.start()
    }
  }

  override def shutdown: () => Unit = () => {
    def tryAndLogFailure(f: () => Any): Unit = Try(f()) match {
      case Failure(e) => log.warning("Error while shutting down...", e)
      case Success(_) =>
    }

    tryAndLogFailure(() => consoleUIUpdater.foreach(_.stop()))
    tryAndLogFailure(() => ConsoleUI.getInstance().shutdown())
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

  def fixDatabase(): Unit = {
    // FIXME this is a temporary solution to avoid an incompatibility due to the introduction of the best block hash
    // We can remove this fix when we release an incompatible version.
    val bestBlockInfo = storagesInstance.storages.appStateStorage.getBestBlockInfo()
    if (bestBlockInfo.hash == ByteString.empty && bestBlockInfo.number > 0) {
      log.warning("Fixing best block hash into database for block {}", bestBlockInfo.number)
      storagesInstance.storages.blockNumberMappingStorage.get(bestBlockInfo.number) match {
        case Some(hash) =>
          log.warning("Putting {} as the best block hash", Hex.toHexString(hash.toArray))
          storagesInstance.storages.appStateStorage.putBestBlockInfo(bestBlockInfo.copy(hash = hash)).commit()
        case None =>
          log.error("No block found for number {} when trying to fix database", bestBlockInfo.number)
      }

    }

  }
}

class StdNode extends BaseNode with StdMiningBuilder
