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
    // The Engine API (8551) and ETH JSON-RPC (8545) both bind here, each awaited, in the order chosen further
    // down. The history matters because this ordering was flipped twice, each flip fixing one hive simulator and
    // breaking another:
    //   * 8545 probed, 8551 bound after it: the sim's engine_newPayloadV3 hit 8551 before it had bound (2026-06-01);
    //   * 8551 bound first, 8545 fire-and-forget: the harness's first eth_getBlockByNumber raced the 8545 bind;
    //   * 8551 then 8545, both awaited: correct for every simulator that probes 8545, wrong for `ethereum/sync`,
    //     whose sink node is probed on 8551 (hive sync.go:97). hive/fukuii/Dockerfile's HIVE_CHECK_LIVE_PORT was
    //     believed to pin the probe to 8545; it does not — libhive reads the port from the simulator's per-client
    //     parameters only (internal/libhive/api.go), defaulting to 8545.
    //
    // No-op when the Engine API is disabled (e.g. ETC mainnet).
    //
    // Bind the p2p import path's invalid-chain channel first. It must be live before
    // startSyncController() below, or a consensus-invalid block imported in the first moments of
    // sync would be rejected with no way to tell the CL. Also a no-op when the Engine API is
    // disabled, and for the same reason: see EngineApiBuilder.bindInvalidChainReporter.
    bindInvalidChainReporter()
    // And the read side, for the same reason and with the same timing constraint: branch resolution must already know
    // to follow the CL's head before the first peer branch arrives. Also a no-op off a post-merge chain with a live
    // Engine API — see EngineApiBuilder.bindDesignatedHead.
    bindDesignatedHead()
    // Whichever port the supervisor probes for readiness must bind LAST, so that "that port accepts a connection"
    // implies the other one already does. hive decides per simulator which port it probes (libhive defaults to 8545;
    // the `ethereum/sync` sink node overrides it to 8551), so a fixed order is always wrong for one of them — see
    // StdNode.engineApiBindsLast. Both starts Await their binding, which is what makes the order a guarantee.
    if StdNode.engineApiBindsLast(readinessPort, engineApiConfig.port) then
      startJsonRpcHttpServer()
      startEngineApiServer()
    else
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
    networkConfig.Server.advertisedAddress.map(java.net.InetAddress.getByName)
  )

  /** The port an external supervisor probes to decide this node is ready, if one was configured
    * (`fukuii.network.readiness-port`; the hive adapter sets it from `HIVE_CHECK_LIVE_PORT`). `None` keeps the default
    * order, Engine API first and JSON-RPC last.
    */
  private lazy val readinessPort: Option[Int] =
    Try(instanceConfig.config.getInt("network.readiness-port")).toOption

  private def startSyncController(): Unit =
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

  private def startMining(): Unit = mining.startProtocol(this)

  private def startDiscoveryManager(): Unit = peerDiscoveryManagerTyped ! PeerDiscoveryManager.Start

  /** Starts the JSON-RPC HTTP server and BLOCKS until the socket is listening.
    *
    * `run()` only requests the bind -- it attaches a logging callback to the binding future and returns immediately --
    * so without this await the method returns while the port is still coming up. That made "node started" mean nothing
    * for 8545, and it is the whole of hive's `ethereum/sync` flakiness: startEngineApiServer() below awaits its own
    * binding, so 8551 is guaranteed listening on return while 8545 lands some unpredictable moment later. With
    * readiness gated on 8551 the harness declared the node ready at the earliest possible instant and its first
    * eth_getBlockByNumber raced the async 8545 bind -- "connection refused", instant fail, no retry, intermittent by
    * construction.
    *
    * Awaiting here makes 8545 genuinely the last port up, which is what hive/fukuii/Dockerfile's HIVE_CHECK_LIVE_PORT
    * now keys on: 8545 accepting connections implies 8551 already does.
    *
    * A bind failure is logged, not thrown -- a node that cannot serve RPC should still sync.
    */
  private def startJsonRpcHttpServer(): Unit =
    maybeJsonRpcHttpServer match
      case Right(jsonRpcServer) if jsonRpcConfig.httpServerConfig.enabled =>
        try
          val binding = scala.concurrent.Await.result(
            jsonRpcServer.run(),
            scala.concurrent.duration.Duration(10, "seconds")
          )
          log.info(s"JSON-RPC HTTP server bound to ${binding.localAddress}")
        catch
          case ex: Exception =>
            log.error(
              s"JSON-RPC HTTP server failed to start on ${jsonRpcConfig.httpServerConfig.interface}:" +
                s"${jsonRpcConfig.httpServerConfig.port}",
              ex
            )
      case Left(error) if jsonRpcConfig.httpServerConfig.enabled => log.error(error)
      case _                                                     => // Nothing
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

object StdNode:

  /** Should the Engine API bind after the ETH JSON-RPC server rather than before it?
    *
    * Only when the readiness probe targets the Engine API port. hive's `ethereum/sync` simulator probes 8551 on the
    * sink node, then calls eth_getBlockByNumber on 8545 about 200 ms later; with 8551 bound first that call lost the
    * race to the 8545 bind — `connection refused`, measured ~10 ms short on 14acbe402. Every other simulator probes
    * 8545 and then drives 8551 immediately, which needs the opposite order. Hence: the probed port binds last.
    */
  def engineApiBindsLast(readinessPort: Option[Int], engineApiPort: Int): Boolean =
    readinessPort.contains(engineApiPort)
