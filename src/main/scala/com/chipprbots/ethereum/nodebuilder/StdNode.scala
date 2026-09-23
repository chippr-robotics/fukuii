package com.chipprbots.ethereum.nodebuilder

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.consensus.mining.StdMiningBuilder
import com.chipprbots.ethereum.console.Tui
import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.console.TuiUpdater
import com.chipprbots.ethereum.db.dataSource.RocksDbCacheMetrics
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
abstract class BaseNode extends Node:

  private var tuiUpdater: Option[TuiUpdater] = None

  // Secondary ActorSystem hosting the periodic DB consistency check (see startPeriodicDBConsistencyCheck).
  // Retained so shutdown() can terminate it — otherwise its dispatcher threads outlive node death.
  private var periodicConsistencyCheckSystem: Option[ActorSystem[?]] = None

  def start(): Unit =
    // Phase 1: Essential initialization (must complete before anything else)
    startMetricsClient()
    fixDatabase()
    loadGenesisData()
    importChainData() // Must complete before APIs so queries return chain data

    // Phase 2: P2P networking — bind discovery + TCP server BEFORE the user-facing
    // APIs come up. Hive's client readiness check is on the RPC port (8545); if we
    // bring up RPC first, hive starts probing UDP 30303 (discovery) before our
    // discovery service has bound. Race is small but real and shows up as
    // i/o-timeout failures in the discv4 hive suite.
    startServer()
    loadStaticNodes()
    startPortForwarding()
    startPeerManager()
    startDiscoveryManager()

    // Phase 3: API servers (user-facing, ready as early as possible).
    // Bind the Engine API (8551) BEFORE the ETH JSON-RPC (8545). Hive's client-readiness
    // check (and a real CL) probes the ETH RPC, then immediately drives sync via the Engine
    // API. The Engine API runs on an isolated ActorSystem and `startEngineApiServer()` Await-
    // blocks until 8551 is actually bound — so binding it first guarantees 8551 is listening
    // by the time 8545 (the readiness signal) comes up. With the old order, hive declared the
    // node ready on 8545 and the sim's engine_newPayloadV3 hit 8551 before it had bound →
    // "connection refused" → instant sync failure (hive ethereum/sync "sync fukuii from
    // go-ethereum", 2026-06-01). No-op when the Engine API is disabled (e.g. ETC mainnet).
    startEngineApiServer()
    startJsonRpcHttpServer()
    startJsonRpcWsServer()
    startJsonRpcIpcServer()

    // Phase 5: Background work
    startSyncController()
    startMining()

    // Phase 6: Non-critical maintenance
    runDBConsistencyCheck()
    startPeriodicDBConsistencyCheck()
    startTuiUpdater()

  private def startMetricsClient(): Unit =
    val metricsConfig = MetricsConfig(instanceConfig.config)
    Metrics.configure(metricsConfig, instanceConfig.instanceId) match
      case Success(_) =>
        log.info("Metrics started")

        if metricsConfig.enabled then
          val snapSyncEnabled =
            Try(instanceConfig.config.getConfig("sync").getBoolean("do-snap-sync")).getOrElse(false)

          if snapSyncEnabled then
            // Ensure app_snapsync_* series exist even before SNAP sync starts.
            val _ = SNAPSyncMetrics

          // Register the RocksDB block-cache hit/miss poll gauges against the live state DataSource
          // (spec 002 US2 / FR-005). No-op unless the DataSource is a RocksDbDataSource; the gauges
          // read 0.0 until db.rocksdb.enable-statistics = true.
          RocksDbCacheMetrics.register(storagesInstance.dataSource)
      case Failure(exception) => throw exception

  private def loadGenesisData(): Unit =
    if !Config.testmode then genesisDataLoader.loadGenesisData()

  private def importChainData(): Unit =
    val chainFile = scala.util.Try(instanceConfig.config.getString("import-chain-file")).toOption
    chainFile.foreach { path =>
      log.info(s"Importing chain data from: $path")
      val (imported, skipped, failed) = chainImporter.importChainFile(path)
      log.info(s"Chain import: $imported imported, $skipped skipped, $failed failed")
    }

  private def runDBConsistencyCheck(): Unit =
    val appState = storagesInstance.storages.appStateStorage
    // Skip consistency check after SNAP sync — block headers 0..pivot don't exist yet.
    // SNAP sync only stores the pivot block header; earlier headers are downloaded
    // incrementally during regular sync's block-by-block import.
    if appState.isSnapSyncDone() then
      log.info("Skipping DB consistency check: SNAP sync stores only pivot block header, not full header chain")
      // Bug 28: Skip when SNAP is mid-sync. AppStateStorage.bestBlock holds the pivot number
      // whose header we have, but the block body was never persisted and the 0..pivot chain is
      // incomplete. The consistency checker would see "best block hash not in block storage",
      // log "Database seems to be in inconsistent state", and call shutdown — turning a recoverable
      // mid-SNAP restart into an unrecoverable wipe-and-resync.
    else if appState.isSnapSyncInProgress() then
      log.info("Skipping DB consistency check: SNAP sync in progress (pivot header only, no full chain yet)")
      // Skip consistency check in Engine API mode — optimistic imports store blocks
      // at the chain tip without the full header chain from genesis.
    else if engineApiConfig.enabled then
      log.info("Skipping DB consistency check: Engine API mode uses optimistic block import")
    else
      StorageConsistencyChecker.checkStorageConsistency(
        appState.getBestBlockNumber(),
        storagesInstance.storages.blockNumberMappingStorage,
        storagesInstance.storages.blockHeadersStorage,
        shutdown
      )(log)

  private def startPeerManager(): Unit = peerManager ! PeerManagerActor.StartConnectingCmd

  /** Load static peer nodes from ${datadir}/static-nodes.json and add each to the maintained-peers set.
    *
    * Besu reference: StaticNodesParser.fromPath() → DefaultP2PNetwork adds each to MaintainedPeers. Static peers are
    * maintained connections: the node will always attempt to reconnect on disconnect.
    */
  private def loadStaticNodes(): Unit =
    val datadir = instanceConfig.config.getString("datadir")
    val nodes = StaticNodesLoader.load(datadir)
    if nodes.nonEmpty then
      log.info("Loading {} static peer(s) from {}/{}", nodes.size, datadir, StaticNodesLoader.FileName)
      nodes.foreach { uri =>
        peerManager ! PeerManagerActor.AddMaintainedPeerCmd(uri, system.deadLetters)
        log.debug("Static peer added: {}", uri)
      }

  private def startServer(): Unit = server ! ServerActor.StartServer(
    networkConfig.Server.listenAddress,
    networkConfig.Server.advertisedAddress.map(java.net.InetAddress.getByName),
    networkConfig.Server.externalIpDetectionMode
  )

  private def startSyncController(): Unit =
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

  private def startMining(): Unit = mining.startProtocol(this)

  private def startDiscoveryManager(): Unit = peerDiscoveryManagerTyped ! PeerDiscoveryManager.Start

  private def startJsonRpcHttpServer(): Unit =
    maybeJsonRpcHttpServer match
      case Right(jsonRpcServer) if jsonRpcConfig.httpServerConfig.enabled => jsonRpcServer.run()
      case Left(error) if jsonRpcConfig.httpServerConfig.enabled          => log.error(error)
      case _                                                              => // Nothing
  private def startJsonRpcWsServer(): Unit =
    if jsonRpcConfig.wsServerConfig.enabled then jsonRpcWsServer.run()

  private def startJsonRpcIpcServer(): Unit =
    if jsonRpcConfig.ipcServerConfig.enabled then jsonRpcIpcServer.run()

  private def startEngineApiServer(): Unit =
    maybeEngineApiServer.foreach { server =>
      try
        val binding = scala.concurrent.Await.result(
          server.start(),
          scala.concurrent.duration.Duration(10, "seconds")
        )
        log.info(s"Engine API server bound to ${binding.localAddress}")
      catch
        case ex: Exception =>
          log.error(s"Engine API server failed to start on ${engineApiConfig.interface}:${engineApiConfig.port}", ex)
    }

  def startPeriodicDBConsistencyCheck(): Unit =
    if Config.Db.periodicConsistencyCheck then
      periodicConsistencyCheckSystem = Some(
        ActorSystem(
          Behaviors
            .supervise(
              PeriodicConsistencyCheck.start(
                storagesInstance.storages.appStateStorage,
                storagesInstance.storages.blockNumberMappingStorage,
                storagesInstance.storages.blockHeadersStorage,
                shutdown,
                engineApiConfig.enabled
              )
            )
            .onFailure[Throwable](SupervisorStrategy.restart.withLimit(3, 1.minute)),
          s"PeriodicDBConsistencyCheck_${instanceConfig.instanceId}"
        )
      )

  private def startTuiUpdater(): Unit =
    val tui = Tui.getInstance()
    if tui.isEnabled then
      log.info("Starting TUI updater")
      val updater = TuiUpdater(
        tui,
        TuiConfig.default,
        Some(peerManager),
        Some(syncController),
        Config.blockchains.network,
        shutdown
      )(using system.classicSystem)
      tuiUpdater = Some(updater)
      updater.start()

  override def shutdown: () => Unit = () =>
    def tryAndLogFailure(f: () => Any): Unit = Try(f()) match // Any: accepts any thunk — return value discarded
      case Failure(e) => log.warn("Error while shutting down...", e)
      case Success(_) =>

    tryAndLogFailure(() => tuiUpdater.foreach(_.stop()))
    tryAndLogFailure(() => Tui.getInstance().shutdown())
    tryAndLogFailure(() => peerDiscoveryManagerTyped ! PeerDiscoveryManager.Stop)
    tryAndLogFailure(() => mining.stopProtocol())
    // Stop the Engine API server first: it owns its own Http() binding (port 8551) on a dedicated
    // ActorSystem + IORuntime. Terminate them before the main ActorSystem so the port is released
    // and its dispatcher/compute pools don't outlive node death.
    tryAndLogFailure(() =>
      maybeEngineApiServer.foreach { engineServer =>
        shutdownTimeoutDuration match
          case fd: scala.concurrent.duration.FiniteDuration => engineServer.stopSync(fd)
          case _                                            => engineServer.stopSync()
      }
    )
    // Terminate the secondary ActorSystem hosting the periodic DB consistency check.
    tryAndLogFailure(() =>
      periodicConsistencyCheckSystem.foreach { s =>
        s.terminate()
        Await.ready(s.whenTerminated, shutdownTimeoutDuration)
      }
    )
    tryAndLogFailure(() =>
      Await.ready(
        system.classicSystem
          .terminate()
          .map(
            _ ->
              log.info("actor system finished")
          ),
        shutdownTimeoutDuration
      )
    )
    tryAndLogFailure(() => Await.ready(stopPortForwarding(), shutdownTimeoutDuration))
    if jsonRpcConfig.ipcServerConfig.enabled then tryAndLogFailure(() => jsonRpcIpcServer.close())
    tryAndLogFailure(() => Metrics.get().close())
    tryAndLogFailure(() => storagesInstance.dataSource.close())

  def fixDatabase(): Unit =
    val bestBlockInfo = storagesInstance.storages.appStateStorage.getBestBlockInfo()
    if bestBlockInfo.hash == ByteString.empty && bestBlockInfo.number > 0 then
      log.warn("Fixing best block hash into database for block {}", bestBlockInfo.number)
      storagesInstance.storages.blockNumberMappingStorage.get(bestBlockInfo.number) match
        case Some(hash) =>
          log.warn("Putting {} as the best block hash", Hex.toHexString(hash.toArray))
          storagesInstance.storages.appStateStorage.putBestBlockInfo(bestBlockInfo.copy(hash = hash)).commit()
        case None =>
          log.error("No block found for number {} when trying to fix database", bestBlockInfo.number)

class StdNode(
    _instanceConfig: com.chipprbots.ethereum.utils.InstanceConfig = com.chipprbots.ethereum.utils.Config
) extends BaseNode
    with StdMiningBuilder:
  override lazy val instanceConfig: com.chipprbots.ethereum.utils.InstanceConfig = _instanceConfig
