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
import com.chipprbots.ethereum.network.StaticNodesLoader
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

  private var tuiUpdater: Option[TuiUpdater] = None

  def start(): Unit = {
    // Phase 1: Essential initialization (must complete before anything else)
    startMetricsClient()
    fixDatabase()
    loadGenesisData()
    importChainData() // Must complete before APIs so queries return chain data

    // Phase 2: API servers (user-facing, ready as early as possible)
    startJsonRpcHttpServer()
    startJsonRpcWsServer()
    startJsonRpcIpcServer()
    startEngineApiServer()

    // Phase 3: P2P networking
    startPeerManager()
    loadStaticNodes()
    startPortForwarding()
    startServer()
    startDiscoveryManager()

    // Phase 5: Background work
    startSyncController()
    startMining()

    // Phase 6: Non-critical maintenance
    runDBConsistencyCheck()
    startPeriodicDBConsistencyCheck()
    startTuiUpdater()
  }

  private[this] def startMetricsClient(): Unit = {
    val metricsConfig = MetricsConfig(instanceConfig.config)
    Metrics.configure(metricsConfig, instanceConfig.instanceId) match {
      case Success(_) =>
        log.info("Metrics started")

        if (metricsConfig.enabled) {
          val snapSyncEnabled =
            Try(instanceConfig.config.getConfig("sync").getBoolean("do-snap-sync")).getOrElse(false)

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

  private[this] def importChainData(): Unit = {
    val chainFile = scala.util.Try(instanceConfig.config.getString("import-chain-file")).toOption
    chainFile.foreach { path =>
      log.info(s"Importing chain data from: $path")
      val (imported, skipped, failed) = chainImporter.importChainFile(path)
      log.info(s"Chain import: $imported imported, $skipped skipped, $failed failed")
    }
  }

  private[this] def runDBConsistencyCheck(): Unit = {
    // Skip consistency check after SNAP sync — block headers 0..pivot don't exist yet.
    // SNAP sync only stores the pivot block header; earlier headers are downloaded
    // incrementally during regular sync's block-by-block import.
    if (storagesInstance.storages.appStateStorage.isSnapSyncDone()) {
      log.info("Skipping DB consistency check: SNAP sync stores only pivot block header, not full header chain")
      return
    }
    // Skip consistency check in Engine API mode — optimistic imports store blocks
    // at the chain tip without the full header chain from genesis.
    if (engineApiConfig.enabled) {
      log.info("Skipping DB consistency check: Engine API mode uses optimistic block import")
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

  /** Load static peer nodes from ${datadir}/static-nodes.json and add each to the maintained-peers set.
    *
    * Besu reference: StaticNodesParser.fromPath() → DefaultP2PNetwork adds each to MaintainedPeers.
    * Static peers are maintained connections: the node will always attempt to reconnect on disconnect.
    */
  private[this] def loadStaticNodes(): Unit = {
    val datadir = instanceConfig.config.getString("datadir")
    val nodes   = StaticNodesLoader.load(datadir)
    if (nodes.nonEmpty) {
      log.info("Loading {} static peer(s) from {}/{}", nodes.size, datadir, StaticNodesLoader.FileName)
      nodes.foreach { uri =>
        peerManager ! PeerManagerActor.AddMaintainedPeer(uri)
        log.debug("Static peer added: {}", uri)
      }
    }
  }

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

  private[this] def startJsonRpcWsServer(): Unit =
    if (jsonRpcConfig.wsServerConfig.enabled) jsonRpcWsServer.run()

  private[this] def startJsonRpcIpcServer(): Unit =
    if (jsonRpcConfig.ipcServerConfig.enabled) jsonRpcIpcServer.run()

  private[this] def startEngineApiServer(): Unit =
    maybeEngineApiServer.foreach { server =>
      try {
        val binding = scala.concurrent.Await.result(
          server.start(),
          scala.concurrent.duration.Duration(10, "seconds")
        )
        log.info(s"Engine API server bound to ${binding.localAddress}")
      } catch {
        case ex: Exception =>
          log.error(s"Engine API server failed to start on ${engineApiConfig.interface}:${engineApiConfig.port}", ex)
      }
    }

  def startPeriodicDBConsistencyCheck(): Unit =
    if (Config.Db.periodicConsistencyCheck)
      ActorSystem(
        PeriodicConsistencyCheck.start(
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.blockHeadersStorage,
          shutdown
        ),
        s"PeriodicDBConsistencyCheck_${instanceConfig.instanceId}"
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

class StdNode(
    _instanceConfig: com.chipprbots.ethereum.utils.InstanceConfig = com.chipprbots.ethereum.utils.Config
) extends BaseNode
    with StdMiningBuilder {
  override lazy val instanceConfig: com.chipprbots.ethereum.utils.InstanceConfig = _instanceConfig
}
