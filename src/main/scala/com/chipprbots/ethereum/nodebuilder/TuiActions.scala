package com.chipprbots.ethereum.nodebuilder

import java.nio.file.Paths

import cats.effect.unsafe.IORuntime

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.console.AlertThresholds
import com.chipprbots.ethereum.console.BundleInputs
import com.chipprbots.ethereum.console.TuiState
import com.chipprbots.ethereum.console.ops.ActionHandler
import com.chipprbots.ethereum.console.ops.Actions
import com.chipprbots.ethereum.console.ops.ConfigView
import com.chipprbots.ethereum.console.ops.DatabaseActions
import com.chipprbots.ethereum.console.ops.EndpointProbe
import com.chipprbots.ethereum.console.ops.LogLevelAction
import com.chipprbots.ethereum.console.ops.MiningActions
import com.chipprbots.ethereum.console.ops.PeerActions
import com.chipprbots.ethereum.console.ops.ReportActions
import com.chipprbots.ethereum.console.ops.RpcServerActions
import com.chipprbots.ethereum.console.ops.SupportBundle
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.AdminService
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.ServiceResponse
import com.chipprbots.ethereum.jsonrpc.server.RpcExposureCheck
import com.chipprbots.ethereum.jsonrpc.server.http.ServerLifecycle
import com.chipprbots.ethereum.network.DetectionMode
import com.chipprbots.ethereum.network.ExternalIPDetector
import com.chipprbots.ethereum.network.IpSource
import com.chipprbots.ethereum.nodebuilder.tooling.StorageConsistencyChecker
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.utils.ServerStatus

/** The dashboard's operator actions, each tied to the node's own function that carries it out (spec 029 FR-052,
  * contracts/actions.md): where an admin method exists, the action runs the service function that method runs, in
  * process. Nothing here enables an RPC namespace or adds a method anyone can reach over the network.
  *
  * Every function handed over is called on a thread of the dashboard's action runner, never on its frame loop: each may
  * block for as long as the service does.
  */
object TuiActions:

  /** How long a service's answer is waited for: the admin functions ask the peer manager with their own timeout. */
  private val ServiceWait: FiniteDuration = 15.seconds

  private def said(error: JsonRpcError): String = error.message

  /** A service's answer, on this thread: a Left is why it gave none. */
  private def run[A](response: ServiceResponse[A])(using IORuntime): Either[String, A] =
    Try(response.unsafeRunTimed(ServiceWait)).toEither.left
      .map(e => Option(e.getMessage).getOrElse(e.toString))
      .flatMap(_.toRight(s"no answer within ${ServiceWait.toSeconds} s"))
      .flatMap(_.left.map(said))

  /** A service function that answers `success`: false is a refusal, said as `refused`. */
  private def done(ok: Boolean, refused: String): Either[String, Unit] = if ok then Right(()) else Left(refused)

  /** The actions of `node`, and why each one it does not offer is not offered. */
  def of(node: Node, configured: AlertThresholds)(using
      IORuntime
  ): ((() => TuiState) => Map[String, ActionHandler], Map[String, String]) =
    import AdminService.*
    val admin = node.adminService
    val limits = admin.peerLimits

    // ---- peers ----------------------------------------------------------------------------------------------------
    val peers = PeerActions.Node(
      limits = () =>
        run(admin.adminState()).map(state =>
          PeerActions.Limits(
            outgoing = state.effectiveMaxOutgoing,
            incoming = limits.fold(0)(_.maxIncoming),
            minOutgoing = limits.fold(0)(_.minOutgoing),
            trusted = state.trustedNodeIds,
            maintained = state.maintainedNodeIds
          )
        ),
      blocked = () => node.blockedIPRegistry.all,
      setMaxPeers = n =>
        run(admin.maxPeers(AdminMaxPeersRequest(n))).flatMap(r =>
          done(r.success, "the peer manager refused the limit")
        ),
      trust = enode =>
        run(admin.addTrustedPeer(AdminAddTrustedPeerRequest(enode)))
          .flatMap(r => done(r.success, "the peer manager refused the enode")),
      // The admin function takes an enode and uses its node id alone: the address in it is never dialled.
      untrust = id =>
        AdminService
          .parseEnode(s"enode://${id.stripPrefix("0x")}@127.0.0.1:30303")
          .flatMap(uri => run(admin.removeTrustedPeer(AdminRemoveTrustedPeerRequest(uri.toString))))
          .flatMap(r => done(r.success, "the peer manager did not answer")),
      add = enode =>
        run(admin.addPeer(AdminAddPeerRequest(enode))).flatMap(r =>
          done(r.success, "the peer manager refused the enode")
        ),
      drop = (id, enode) =>
        enode
          .toRight(s"no enode can be made of the peer $id")
          .flatMap(uri => run(admin.removePeer(AdminRemovePeerRequest(uri))))
          .flatMap(r => done(r.success, "it is no longer connected")),
      block = ip =>
        run(admin.blockIP(AdminBlockIPRequest(ip)))
          .flatMap(r => done(r.success, "it was already blocked; the peers at it were disconnected again"))
          .map(_ => AdminService.canonicalIp(ip).getOrElse(ip)),
      unblock =
        ip => run(admin.unblockIP(AdminUnblockIPRequest(ip))).flatMap(r => done(r.success, "it was not blocked"))
    )

    // ---- log levels -----------------------------------------------------------------------------------------------
    val logLevels = LogLevelAction.Node(
      level = name => AdminService.logLevel(name).map(l => LogLevelAction.Level(l.name, l.configured, l.effective)),
      near = prefix => AdminService.logLevels(prefix).map(_.name),
      change = (level, logger) =>
        run(
          admin.changeLogLevel(
            AdminChangeLogLevelRequest(level, Some(List(if logger == LogLevelAction.Root then "" else logger)))
          )
        ).map(_ => ()),
      changed = () =>
        AdminService.changedLogLevels.map(c => LogLevelAction.Changed(c.name, c.before, c.now, c.createdByChange)),
      reset =
        () => AdminService.resetLogLevels().map(c => LogLevelAction.Changed(c.name, c.before, c.now, c.createdByChange))
    )

    // ---- mining (a proof-of-work node only) -----------------------------------------------------------------------
    val proofOfWork = node.blockchainConfig.networkType == NetworkType.ETC &&
      (node.miningConfig.protocol == Protocol.PoW || node.miningConfig.protocol == Protocol.RestrictedPoW)
    val mining = Option.when(proofOfWork)(
      MiningActions.Node(
        enabledAtStart = node.miningConfig.miningEnabled,
        running = () => PoWMiningMetrics.minerRunning.value() == 1.0,
        stop = () =>
          run(node.ethMiningService.stopMiner(EthMiningService.StopMinerRequest()))
            .flatMap(r => done(r.success, "the miner refused to stop")),
        coinbase = () => node.coinbaseProvider.get().toString,
        setCoinbase = address =>
          run(node.ethMiningService.setEtherbase(EthMiningService.SetEtherbaseRequest(Address(address))))
            .flatMap(r => done(r.success, "the miner refused the address"))
      )
    )

    // ---- the HTTP and WebSocket servers, where they were enabled at the start ------------------------------------
    val http = node.jsonRpcConfig.httpServerConfig
    val ws = node.jsonRpcConfig.wsServerConfig
    def exposed(name: String, key: String, interface: String, apis: Seq[String]): Seq[String] =
      RpcExposureCheck.exposedNamespaces(RpcExposureCheck.Transport(name, key, true, interface, apis))
    def stopping(lifecycle: ServerLifecycle): Either[String, Unit] =
      Try(Await.result(lifecycle.stop(), lifecycle.hardDeadline + 5.seconds)).toEither.left
        .map(e => Option(e.getMessage).getOrElse(e.toString))
    def starting(lifecycle: ServerLifecycle): Either[String, Unit] =
      Try(Await.result(lifecycle.start(), 10.seconds)).toEither
        .map(_ => ())
        .left
        .map(e => Option(e.getMessage).getOrElse(e.toString))
    val servers = Map.empty[String, RpcServerActions.Server] ++
      // The server is asked for only where it was enabled: asking builds it.
      Option.when(http.enabled)(node.maybeJsonRpcHttpServer).flatMap(_.toOption).map { server =>
        "http" -> RpcServerActions.Server(
          label = "HTTP",
          address = s"${http.interface}:${http.port}",
          namespaces = node.jsonRpcConfig.httpApis,
          exposed = exposed("HTTP", "http", http.interface, node.jsonRpcConfig.httpApis),
          tls = server.isTls,
          extras = Seq("/health", "/readiness", "/healthcheck", "/buildinfo", "GraphQL where it is on"),
          openConnections = () => None,
          listening = () => server.lifecycle.isBound,
          stop = () => stopping(server.lifecycle),
          start = () => starting(server.lifecycle)
        )
      } ++
      // The WebSocket server is built by being asked for: it is asked for only where it was enabled.
      Option.when(ws.enabled) {
        val server = node.jsonRpcWsServer
        "ws" -> RpcServerActions.Server(
          label = "WebSocket",
          address = s"${ws.interface}:${ws.port}",
          namespaces = node.jsonRpcConfig.wsApis,
          exposed = exposed("WebSocket", "ws", ws.interface, node.jsonRpcConfig.wsApis),
          tls = server.isTls,
          extras = Nil,
          openConnections = () => Some(server.openConnections),
          listening = () => server.lifecycle.isBound,
          stop = () => stopping(server.lifecycle),
          start = () => starting(server.lifecycle)
        )
      }

    // A server that was enabled and could not be made (a TLS keystore that does not open): said as it is, not as a
    // transport that was off.
    val serversNotMade: Map[String, String] =
      Option
        .when(http.enabled)(node.maybeJsonRpcHttpServer)
        .flatMap(_.left.toOption)
        .map(why => "http" -> s"the HTTP server could not be made when the node started: $why")
        .toMap

    // ---- the database check ---------------------------------------------------------------------------------------
    val appState = node.storagesInstance.storages.appStateStorage
    val database = DatabaseActions.Node(
      notMeaningful =
        () => StorageConsistencyChecker.applicability(appState, node.engineApiConfig.enabled).map(_.reason),
      best = () => appState.getBestBlockNumber(),
      step = StorageConsistencyChecker.DefaultStep,
      scan = progress =>
        val found = StorageConsistencyChecker.scan(
          appState.getBestBlockNumber(),
          node.storagesInstance.storages.blockNumberMappingStorage,
          node.storagesInstance.storages.blockHeadersStorage,
          onProgress = progress
        )
        DatabaseActions.Result(
          found.best,
          found.step,
          found.checked,
          found.missingCount,
          found.firstMissing.map(m =>
            s"block ${m.number} (${m.kind match
                case StorageConsistencyChecker.MissingKind.NoHashForNumber => "no hash for its number"
                case StorageConsistencyChecker.MissingKind.NoHeaderForHash => "no header for its hash"
              })"
          ),
          found.cancelled
        )
    )

    // ---- the addresses --------------------------------------------------------------------------------------------
    val server = node.instanceConfig.Network.Server
    def detection: String =
      if server.advertisedAddress.isDefined then "configured (fukuii.network.server-address.advertised-address)"
      else
        server.externalIpDetectionMode match
          case DetectionMode.None => "off"
          case DetectionMode.Upnp => "automatic, by UPnP only, every 30 minutes"
          case DetectionMode.Full => "automatic, every 30 minutes"
    def advertised: Option[String] = node.nodeStatusHolder.get().serverStatus match
      case ServerStatus.Listening(address) => Some(s"${address.getHostString}:${address.getPort}")
      case ServerStatus.NotListening       => None
    def netInfo: ReportActions.NetInfo =
      val peersRead = run(admin.peers(AdminPeersRequest())).map(_.peers)
      val connected = peersRead.getOrElse(Nil)
      val found = node.networkAddressInfo.get
      val how = found.found.map { d =>
        val by = d.source match
          case IpSource.Upnp      => "by UPnP, from the gateway"
          case IpSource.Stun      => "by STUN, from an outside server"
          case IpSource.Https     => "by HTTPS, from an outside service"
          case IpSource.Interface => "from a local interface: nothing outside confirmed it"
        val ago = found.atMillis.fold("")(at => s", ${((System.currentTimeMillis() - at) / 60000).max(0)} min ago")
        s"${d.address.getHostAddress} $by$ago"
      }
      ReportActions.NetInfo(
        listening = s"${server.listenAddress.getHostString}:${server.listenAddress.getPort}",
        isListening = advertised.isDefined,
        advertised = advertised,
        detected = how.orElse(found.atMillis.map(_ => "the last detection found no address")),
        detection = detection,
        inbound = connected.count(_.inbound),
        connected = connected.size,
        peersUnread = peersRead.left.toOption
      )

    // ---- the endpoints the endpoint test calls --------------------------------------------------------------------
    def endpoints: Seq[EndpointProbe.Endpoint] =
      import EndpointProbe.Kind
      val ipc = node.jsonRpcConfig.ipcServerConfig
      Seq(
        Option.when(http.enabled)(
          EndpointProbe.Endpoint(if http.tlsEnabled then Kind.Https else Kind.Http, http.interface, http.port)
        ),
        Option.when(ws.enabled)(EndpointProbe.Endpoint(Kind.Ws, ws.interface, ws.port)),
        Option.when(ipc.enabled)(EndpointProbe.Endpoint(Kind.Ipc, "", 0, ipc.socketFile)),
        Option.when(node.engineApiConfig.enabled)(
          EndpointProbe.Endpoint(Kind.Engine, node.engineApiConfig.interface, node.engineApiConfig.port)
        )
      ).flatten

    def configRows: Vector[String] = ConfigView.rows(node.instanceConfig.config, "fukuii")
    val dataDir = Try(node.instanceConfig.config.getString("datadir")).toOption.filter(_.nonEmpty).map(Paths.get(_))

    /** What changes while the node runs, as it is now. */
    def tunables(state: TuiState): Seq[(String, String, String)] =
      val peerState = peers.limits()
      val changed = AdminService.changedLogLevels
      val thresholds = AlertThresholds.Keys.flatMap { key =>
        for
          now <- AlertThresholds.show(state.thresholds, key)
          atStart <- AlertThresholds.show(configured, key)
          if now != atStart
        yield s"$key $now (configured $atStart)"
      }
      Seq(
        (
          "log levels",
          AdminService.logLevel("ROOT").fold("root: not read")(l => s"root ${l.effective}") +
            (if changed.isEmpty then ", none changed"
             else s", ${changed.size} changed: ${changed.map(c => s"${c.name} ${c.now}").mkString(", ")}"),
          "loglevel <level> [logger], loglevel reset"
        ),
        (
          "outgoing peer limit",
          peerState.fold(why => s"not read: $why", l => s"${l.outgoing} (incoming ${l.incoming}, not changeable here)"),
          "maxpeers <n>"
        ),
        (
          "trusted peers",
          peerState.fold(
            _ => "not read",
            l => if l.trusted.isEmpty then "none" else l.trusted.map(PeerActions.brief).mkString(", ")
          ),
          "peer trust <id>, peer untrust <id>"
        ),
        (
          "kept peers",
          peerState.fold(_ => "not read", l => if l.maintained.isEmpty then "none" else s"${l.maintained.size}"),
          "peer add <enode>, peer drop <id>"
        ),
        (
          "blocked addresses",
          if peers.blocked().isEmpty then "none" else peers.blocked().toSeq.sorted.mkString(", "),
          "peer block <address>, peer unblock <address>"
        )
      ) ++ mining.toSeq.flatMap(m =>
        Seq(
          (
            "built-in miner",
            if !m.enabledAtStart then "off since the start"
            else if m.running() then "mining"
            else "stopped until the node restarts",
            "mining stop"
          ),
          ("coinbase", m.coinbase(), "coinbase <address>")
        )
      ) ++ servers.toSeq
        .sortBy(_._1)
        .map((key, s) =>
          (
            s"${s.label} server",
            if s.listening() then s"listening on ${s.address}" else s"stopped (${s.address})",
            s"rpc stop $key, rpc start $key"
          )
        ) ++ Seq(
        (
          "alert thresholds",
          if thresholds.isEmpty then "as configured" else thresholds.mkString(", "),
          "threshold <name> <value>, threshold reset"
        )
      )

    val unavailable =
      (if proofOfWork then Map.empty[String, String]
       else
         Seq(Actions.MiningStart, Actions.MiningStop, Actions.Coinbase)
           .map(_.name -> MiningActions.NotProofOfWork)
           .toMap
      ) ++
        Option.when(dataDir.isEmpty)(Actions.Bundle.name -> "this node has no data directory to write under")

    val handlers: (() => TuiState) => Map[String, ActionHandler] = state =>
      PeerActions.handlers(peers, state) ++
        Map(Actions.LogLevel.name -> LogLevelAction.handler(logLevels)) ++
        mining.fold(Map.empty[String, ActionHandler])(MiningActions.handlers) ++
        RpcServerActions.handlers(servers, serversNotMade) ++
        DatabaseActions.handlers(database) ++
        Map(
          Actions.Config.name -> ConfigView.handler(() => node.instanceConfig.config, "fukuii"),
          Actions.Tunables.name -> ReportActions.tunables(() => tunables(state())),
          Actions.Net.name -> ReportActions.net(() => netInfo),
          Actions.TestRpc.name -> EndpointProbe.handler(() => endpoints),
          // The operator asks for it: the whole detection runs, whatever the node's own is set to, and the preview
          // says so where the node's own is off.
          Actions.DetectIp.name -> ReportActions.detectIp(
            () => ExternalIPDetector.detect(),
            () => detection,
            () => advertised,
            () => server.advertisedAddress.isEmpty && server.externalIpDetectionMode == DetectionMode.None
          )
        ) ++
        dataDir.map(dir =>
          Actions.Bundle.name -> SupportBundle.handler(
            BundleInputs.of(
              state,
              Seq(
                s"${BuildInfo.name} ${BuildInfo.version}",
                s"commit ${BuildInfo.gitHeadCommit}",
                s"scala ${BuildInfo.scalaVersion}"
              ),
              () => configRows
            ),
            () => Some(dir),
            () => java.time.Instant.now()
          )
        )
    (handlers, unavailable)
