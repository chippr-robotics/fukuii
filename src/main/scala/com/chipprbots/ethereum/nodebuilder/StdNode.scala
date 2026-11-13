package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.StoredBodyCheck
import com.chipprbots.ethereum.blockchain.sync.StoredBodyRepair
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.consensus.mining.StdMiningBuilder
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.console.ChainFacts
import com.chipprbots.ethereum.console.ForkChoiceHashes
import com.chipprbots.ethereum.console.NodeSettings
import com.chipprbots.ethereum.console.NodeStatusSnapshot
import com.chipprbots.ethereum.console.Tui
import com.chipprbots.ethereum.console.TuiProbeThread
import com.chipprbots.ethereum.console.TuiStatusProbe
import com.chipprbots.ethereum.console.TuiUpdater
import com.chipprbots.ethereum.db.dataSource.RocksDbCacheMetrics
import com.chipprbots.ethereum.domain.BestMappingRepair
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.jsonrpc.server.RpcExposureCheck
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.ServerActor
import com.chipprbots.ethereum.network.StaticNodesLoader
import com.chipprbots.ethereum.network.discovery.PeerDiscoveryManager
import com.chipprbots.ethereum.nodebuilder.tooling.PeriodicConsistencyCheck
import com.chipprbots.ethereum.nodebuilder.tooling.StorageConsistencyChecker
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.NetworkType

/** A standard node is everything Ethereum prescribes except the mining algorithm, which is plugged in dynamically.
  *
  * The design is historically related to the initial cake-pattern-based
  * [[com.chipprbots.ethereum.nodebuilder.Node Node]].
  *
  * @see
  *   [[com.chipprbots.ethereum.nodebuilder.Node Node]]
  */
abstract class BaseNode extends Node:

  // Set on the thread that starts the node, read by the shutdown hook's.
  @volatile private var tuiUpdater: Option[TuiUpdater] = None
  @volatile private var tuiProbe: Option[TuiProbeThread] = None

  /** The TUI's latest status snapshot: [[TuiProbeThread]] publishes it, the frame loop reads it (spec 027 FR-011). */
  private val tuiSnapshots = new AtomicReference[Option[NodeStatusSnapshot]](None)

  // Secondary ActorSystem hosting the periodic DB consistency check (see startPeriodicDBConsistencyCheck).
  // Retained so shutdown() can terminate it — otherwise its dispatcher threads outlive node death.
  private var periodicConsistencyCheckSystem: Option[ActorSystem[?]] = None

  def start(): Unit =
    // Phase -1 (B-246): the Engine API's JWT secret is resolved before anything starts. A refused file is one clear
    // line and exit 1 here, not a stack trace from phase 3 with P2P and discovery already up.
    StdNode.engineApiRefusal(engineApiJwtAuthenticator).foreach(refuseToStart)

    // Phase 0: the TUI's frames, so its splash and "starting node" move while the node starts. The probe that feeds it
    // starts in phase 6, once the actors it asks exist.
    startTuiFrames()

    // Phase 1: Essential initialization (must complete before anything else)
    startMetricsClient()
    fixDatabase()
    repairBestBlockMapping() // before any server binds, so no newPayload/FCU races the read-then-write
    loadGenesisData()
    repairStoredBodies() // after genesis is loaded, before the sync controller reads the backfill cursors
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
    warnOnExposedNamespaces()
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
      )(using log)

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

  /** The port an external supervisor probes to decide this node is ready, if one was configured
    * (`fukuii.network.readiness-port`; the hive adapter sets it from `HIVE_CHECK_LIVE_PORT`). `None` keeps the default
    * order, Engine API first and JSON-RPC last.
    */
  private lazy val readinessPort: Option[Int] =
    Try(instanceConfig.config.getInt("network.readiness-port")).toOption

  private def startSyncController(): Unit =
    if instanceConfig.statePathLive then
      // Spec 018 Track C, C3b: on the unified state layout a block is committed in place or not at all until the
      // overlay (C4a). Network sync brings side branches and reorganisations, which need the state of blocks that
      // are not the head. So a node on the in-development guard imports chain files and serves what it holds.
      log.error(
        "[STATE-PATH-LIVE] fukuii.state.path-live is on: this node does NOT sync from the network. The unified " +
          "state layout holds the state of one block until its overlay lands (spec 018 C4), so it cannot follow a " +
          "side branch or a reorganisation. It takes blocks from a chain file (the `import` command, " +
          "admin_importChain) and answers from the state it holds."
      )
    else syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

  private def startMining(): Unit =
    // A node that produces blocks takes transactions from peers: core-geth's StartMining calls
    // `enableSyncedFeatures()` for the same reason, or a miner held behind the gate would mine empty blocks.
    if mining.config.generic.miningEnabled then
      txGossipGate.markSynced("block production is enabled")
      // The DAG gauges are written from the mining thread (EthashDAGManager), inside loops whose failure paths
      // delete and regenerate the DAG file. Initialise the metrics object here, after startMetricsClient and before
      // that thread exists, so that a failed initialiser surfaces at start-up and not on it (spec 027 slice 5, M2).
      val _ = PoWMiningMetrics
    mining.startProtocol(this)

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
  /** Logs a warning for each HTTP or WebSocket endpoint that serves personal, debug, admin, qa or miner while bound to
    * an address that is not loopback. IPC is not checked: its socket is protected by file permissions.
    */
  private def warnOnExposedNamespaces(): Unit =
    val http = jsonRpcConfig.httpServerConfig
    val ws = jsonRpcConfig.wsServerConfig
    RpcExposureCheck
      .warnings(
        Seq(
          RpcExposureCheck.Transport("HTTP", "http", http.enabled, http.interface, jsonRpcConfig.httpApis),
          RpcExposureCheck.Transport("WebSocket", "ws", ws.enabled, ws.interface, jsonRpcConfig.wsApis)
        )
      )
      .foreach(warning => log.warn(warning))

  private def startJsonRpcWsServer(): Unit =
    if jsonRpcConfig.wsServerConfig.enabled then jsonRpcWsServer.run()

  private def startJsonRpcIpcServer(): Unit =
    if jsonRpcConfig.ipcServerConfig.enabled then jsonRpcIpcServer.run()

  /** One ERROR line and exit 1: the node does not start half-way. */
  private def refuseToStart(message: String): Unit =
    log.error(message)
    sys.exit(1)

  /** With the Engine API enabled the node needs a consensus client to follow a post-merge chain, so a port it cannot
    * bind is fatal: a node that logs it and runs on would look healthy and never sync.
    */
  private def startEngineApiServer(): Unit =
    maybeEngineApiServer.foreach { server =>
      StdNode.awaitEngineApiBind(server.start(), engineApiConfig.interface, engineApiConfig.port, 10.seconds) match
        case Right(binding) => log.info(s"Engine API server bound to ${binding.localAddress}")
        case Left(message)  => refuseToStart(message)
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

  /** The TUI's frame loop, before anything else starts: the splash is a view of it (spec 027 FR-013). */
  private def startTuiFrames(): Unit =
    // Tui.current, never getInstance(): a node started without --tui has no TUI, and asking must not make one (B-045).
    Tui.current.filter(_.isEnabled).foreach { tui =>
      // `q` restores the terminal, then exits the JVM; the shutdown hook (ShutdownHooks) runs this node's shutdown(),
      // which stops the updater. The restore cannot wait for the TuiRestore hook: JLine's hook may close the terminal
      // first, leaving the user's shell in the alternate screen with the cursor hidden.
      val quit = TuiUpdater.restoreThenQuit(tui, () => sys.exit(0))
      // The settings Fukuii.main loaded (`fukuii.tui` plus the environment) when it built the TUI.
      val updater = TuiUpdater(tui, tui.config, () => tuiSnapshots.get, Config.blockchains.network, quit)
      tuiUpdater = Some(updater)
      updater.start()
    }

  /** The TUI's status poll, on its own thread, once the actors it asks exist (spec 027 FR-011). */
  private def startTuiUpdater(): Unit =
    Tui.current.filter(_.isEnabled).foreach { tui =>
      log.info("Starting TUI status probe")
      val settings = tuiNodeSettings()
      tui.updateNodeSettings(settings)
      // Display only: the fork configuration is read, never changed (spec 027 FR-017).
      tui.updateChain(ChainFacts.from(blockchainConfig, BuildInfo.version))
      // Spec 027 slice 5. The consensus-layer panel exists on a post-merge chain with a live Engine API, the gate that
      // binds the designated head (`bindDesignatedHead`): an ETC node, even one with an Engine API for `testing_*`, has
      // none and no engine series. The mining panel exists on ETC. Each reading is a thunk the probe thread calls: the
      // fork choice through one read of the manager's state (the manager itself is not handed to the TUI, whose
      // listener slot is the SNAP pivot's feed), the safe and finalized numbers by header lookup, and the coinbase
      // from the same provider `eth_coinbase` and `eth_getWork` answer from.
      val consensusLayerPanel = engineApiConfig.enabled && blockchainConfig.terminalTotalDifficulty.isDefined
      val local = TuiStatusProbe.LocalSources.node(
        dataDir = Option(settings.dataDir).filter(_.nonEmpty).map(new java.io.File(_)),
        // Only ETH's fork timeline stands on a timestamp; ETC's stands on the block number, so no header is read.
        headTimestamp =
          if blockchainConfig.networkType == NetworkType.ETH then
            () => blockchainReader.getBestBlockHeader.map(_.unixTimestamp.toLong)
          else () => None,
        cl =
          if consensusLayerPanel then
            TuiStatusProbe.LocalSources.clReader(
              forkChoice = () =>
                forkChoiceManager.getState
                  .map(s => ForkChoiceHashes(s.headBlockHash, s.safeBlockHash, s.finalizedBlockHash)),
              headerNumber = hash => blockchainReader.getBlockHeaderByHash(BlockHash(hash)).map(_.number.toLong)
            )
          else () => None,
        mining =
          if blockchainConfig.networkType == NetworkType.ETC then
            TuiStatusProbe.LocalSources.miningReader(coinbase = () => coinbaseProvider.get().toString)
          else () => None
      )
      val probe = new TuiStatusProbe(
        peerManager,
        syncController,
        () => blockchainReader.getBestBlockNumber,
        peerConfiguration.maxOutgoingPeers + peerConfiguration.maxIncomingPeers,
        () => SNAPSyncMetrics.latestSample,
        local = local,
        // Spec 027 slice 4: each peer's client id and best block, and the txpool; asked in the same bounded wait.
        networkPeerManager = Some(networkPeerManager),
        txPool = Some(pendingTransactionsManagerTyped)
      )(using system.scheduler)
      // Polling ends with the TUI: after `d`, a lost terminal or a failed key read, nothing reads the snapshots.
      val thread = new TuiProbeThread(probe, tui.config.statusPollIntervalMs, tuiSnapshots, () => tui.isEnabled)
      tuiProbe = Some(thread)
      thread.start()
    }

  private def tuiNodeSettings(): NodeSettings =
    val c = instanceConfig.config
    def opt[A](f: => A): Option[A] = Try(f).toOption
    NodeSettings(
      dataDir = opt(c.getString("datadir")).getOrElse(""),
      network = Config.blockchains.network,
      syncMode = if syncConfig.doSnapSync then "SNAP" else "Full",
      pruningMode = storagesInstance.pruningMode.toString,
      maxPeers = peerConfiguration.maxOutgoingPeers + peerConfiguration.maxIncomingPeers,
      rpcEnabled = opt(c.getBoolean("network.rpc.http.enabled")).getOrElse(false),
      rpcPort = opt(c.getInt("network.rpc.http.port")).getOrElse(0),
      miningEnabled = opt(c.getBoolean("mining.mining-enabled")).getOrElse(false),
      wsEnabled = opt(c.getBoolean("network.rpc.ws.enabled")).getOrElse(false),
      wsPort = opt(c.getInt("network.rpc.ws.port")).getOrElse(0),
      ipcEnabled = opt(c.getBoolean("network.rpc.ipc.enabled")).getOrElse(false),
      engineEnabled = opt(c.getBoolean("network.engine-api.enabled")).getOrElse(false),
      enginePort = opt(c.getInt("network.engine-api.port")).getOrElse(0)
    )

  override def shutdown: () => Unit = () =>
    def tryAndLogFailure(f: () => Any): Unit = Try(f()) match // Any: accepts any thunk — return value discarded
      case Failure(e) => log.warn("Error while shutting down...", e)
      case Success(_) =>

    tryAndLogFailure(() => tuiProbe.foreach(_.stop()))
    tryAndLogFailure(() => tuiUpdater.foreach(_.stop()))
    tryAndLogFailure(() => Tui.current.foreach(_.shutdown()))
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
    // Discovery's own runtime goes once the Stop sent above has released the service (UDP socket, feeder), and before
    // the actor system that hosts PeerDiscoveryManager is terminated (task #52).
    tryAndLogFailure(() => shutdownDiscoveryIoRuntime(10.seconds))
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

  /** Restores the number->hash entry at the best block's height if an earlier version rewrote the best block's header
    * (SNAP startup) and left the index naming the forged copy. See [[BlockchainWriter.repairBestBlockNumberMapping]].
    */
  def repairBestBlockMapping(): Unit =
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) match
      case BestMappingRepair.Consistent => ()
      case BestMappingRepair.Restored(number, bestHash, wasMappedTo, removedOrphan) =>
        log.warn(
          "Repaired canonical index: block {} was mapped to {} (not the best block); restored mapping to best block {}. " +
            "Forged header removed: {}. Cause: an earlier version rewrote the best block's header after SNAP.",
          number,
          wasMappedTo.toHexString,
          bestHash.toHexString,
          removedOrphan
        )
      case BestMappingRepair.Refused(number, bestHash, mappedTo, headerPresent, bodyPresent, reason) =>
        log.error(
          "Canonical index inconsistent at block {}: mapped to {} but best-block info names {} " +
            "(best header present={}, body present={}). Not repairing: {}.",
          number,
          Hex.toHexString(mappedTo.toArray),
          bestHash.toHexString,
          headerPresent,
          bodyPresent,
          reason
        )

  /** CQ-020-07: re-derive or refetch the SNAP pivot's and genesis's bodies when an earlier version stored them without
    * their content (see [[StoredBodyRepair]]).
    */
  def repairStoredBodies(): Unit =
    StoredBodyRepair
      .run(blockchainReader, blockchainWriter, storagesInstance.storages.appStateStorage)
      .foreach { o =>
        o.action match
          case StoredBodyCheck.Action.Write(_, replacing) =>
            log.warn(
              "Repaired {} body: block {} {} rewritten with the empty body its header commits to (was: {})",
              o.label,
              o.number,
              o.hash.toHexString,
              replacing.getOrElse("absent")
            )
          case StoredBodyCheck.Action.Remove(reason) =>
            log.warn(
              "Removed {} body of block {} {}: it contradicts its header ({}). Refetch from peers armed: {}",
              o.label,
              o.number,
              o.hash.toHexString,
              reason,
              o.refetchArmed
            )
          case other =>
            log.warn(
              "{} body of block {} {} is missing although the backfill claimed it ({}). Refetch from peers armed: {}",
              o.label,
              o.number,
              o.hash.toHexString,
              other,
              o.refetchArmed
            )
      }

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

  /** The message of a refused Engine API secret, if the secret is refused (B-246). `thunk` forces it. */
  def engineApiRefusal(thunk: => Any): Option[String] =
    try
      val _ = thunk
      None
    catch
      case e @ (_: IllegalStateException | _: java.io.IOException | _: IllegalArgumentException) =>
        Some(e.getMessage)

  /** Waits for the Engine API bind. A failure, or no answer in `timeout`, is a message naming the address and the key
    * to change, for the caller to log before it exits.
    */
  def awaitEngineApiBind[A](
      bind: scala.concurrent.Future[A],
      interface: String,
      port: Int,
      timeout: scala.concurrent.duration.FiniteDuration
  ): Either[String, A] =
    try Right(scala.concurrent.Await.result(bind, timeout))
    catch
      case scala.util.control.NonFatal(e) =>
        Left(
          s"Refusing to start: the Engine API cannot bind $interface:$port (${e.getClass.getSimpleName}: ${e.getMessage}). " +
            "The consensus client could never reach this node, which would not sync. Free the port, or set " +
            "network.engine-api.port (each instance on one host needs its own)."
        )

  /** Should the Engine API bind after the ETH JSON-RPC server rather than before it?
    *
    * Only when the readiness probe targets the Engine API port. hive's `ethereum/sync` simulator probes 8551 on the
    * sink node, then calls eth_getBlockByNumber on 8545 about 200 ms later; with 8551 bound first that call lost the
    * race to the 8545 bind — `connection refused`, measured ~10 ms short on 14acbe402. Every other simulator probes
    * 8545 and then drives 8551 immediately, which needs the opposite order. Hence: the probed port binds last.
    */
  def engineApiBindsLast(readinessPort: Option[Int], engineApiPort: Int): Boolean =
    readinessPort.contains(engineApiPort)
