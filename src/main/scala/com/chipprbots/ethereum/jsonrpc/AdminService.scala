package com.chipprbots.ethereum.jsonrpc

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.data.ChainExporter
import com.chipprbots.ethereum.blockchain.data.ChainImporter
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.branch.BestBranch
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

object AdminService:

  /** Besu alignment: protocols.eth sub-object in admin_nodeInfo. Mirrors Besu's EthProtocolStatus: difficulty (hex),
    * genesis hash (0x-prefixed hex), head hash (0x-prefixed hex), network ID.
    */
  case class EthProtocolInfo(
      difficulty: String,
      genesis: String,
      head: String,
      network: Long
  )

  case class AdminNodeInfoRequest()
  case class AdminNodeInfoResponse(
      enode: Option[String],
      id: String,
      ip: Option[String],
      listenAddr: Option[String],
      name: String,
      ports: Map[String, Int],
      protocols: Map[String, EthProtocolInfo],
      activeFork: String
  )

  case class AdminPeersRequest()
  case class AdminPeersResponse(peers: Seq[AdminPeerInfo])

  case class AdminPeerInfo(
      id: String,
      name: String,
      remoteAddress: String,
      inbound: Boolean
  )

  case class AdminAddPeerRequest(enodeUrl: String)
  case class AdminAddPeerResponse(success: Boolean)

  case class AdminRemovePeerRequest(enodeUrl: String)
  case class AdminRemovePeerResponse(success: Boolean)

  /** Besu: AdminChangeLogLevel — params[0]=level (OFF/ERROR/WARN/INFO/DEBUG/TRACE/ALL), params[1]=optional String[]
    * filters (logger names). If no filters: set root logger. Sets each named logger's level via Logback API.
    */
  case class AdminChangeLogLevelRequest(level: String, logFilters: Option[List[String]])
  case class AdminChangeLogLevelResponse()

  case class AdminDatadirRequest()
  case class AdminDatadirResponse(datadir: String)

  case class AdminExportChainRequest(file: String, first: Option[BigInt], last: Option[BigInt])
  case class AdminExportChainResponse(success: Boolean)

  case class AdminImportChainRequest(file: String)
  case class AdminImportChainResponse(success: Boolean)

  case class AdminBlockIPRequest(ip: String)
  case class AdminBlockIPResponse(success: Boolean)

  case class AdminUnblockIPRequest(ip: String)
  case class AdminUnblockIPResponse(success: Boolean)

  case class AdminListBlockedIPsRequest()
  case class AdminListBlockedIPsResponse(ips: List[String])

  /** core-geth: admin_addTrustedPeer / admin_removeTrustedPeer — always returns true on success. core-geth reference:
    * node/api.go AddTrustedPeer / RemoveTrustedPeer
    */
  case class AdminAddTrustedPeerRequest(enodeUrl: String)
  case class AdminAddTrustedPeerResponse(success: Boolean)

  case class AdminRemoveTrustedPeerRequest(enodeUrl: String)
  case class AdminRemoveTrustedPeerResponse(success: Boolean)

  /** core-geth: admin_maxPeers — sets max connected peers at runtime, returns true on success. core-geth reference:
    * eth/api_admin.go MaxPeers
    */
  case class AdminMaxPeersRequest(maxPeers: Int)
  case class AdminMaxPeersResponse(success: Boolean)

  private val ValidLogLevels = Set("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL")

class AdminService(
    nodeStatusHolder: AtomicReference[NodeStatus],
    peerManager: typed.ActorRef[PeerManagerActor.Command],
    blockchainReader: BlockchainReader,
    blockchainConfig: BlockchainConfig,
    peerManagerTimeout: FiniteDuration,
    datadir: String,
    blockedIPRegistry: BlockedIPRegistry,
    chainImporter: ChainImporter,
    // The node's sync status, read before admin_importChain: SyncController's GetStatus.
    syncStatus: () => IO[SyncProtocol.Status]
)(implicit scheduler: typed.Scheduler)
    extends Logger:
  import AdminService.*

  implicit private val timeout: Timeout = Timeout(peerManagerTimeout)

  /** Returns the canonical ECIP-1066 fork name active at the given block number. Chain-agnostic: uses ForkBlockNumbers
    * from the loaded blockchainConfig, so it produces the correct name for both ETC mainnet and Mordor testnet without
    * conditional logic. Forks set to Long.MaxValue (not yet activated, e.g. Olympia TBD) are excluded by the <=
    * blockNumber filter. Mirrors Besu's protocolSchedule.getByBlockHeader().getHardforkId() intent.
    */
  private def activeForkName(blockNumber: BigInt, forks: ForkBlockNumbers): String =
    List(
      forks.olympiaBlockNumber -> "Olympia",
      forks.spiralBlockNumber -> "Spiral",
      forks.mystiqueBlockNumber -> "Mystique",
      forks.magnetoBlockNumber -> "Magneto",
      forks.ecip1099BlockNumber -> "Thanos",
      forks.phoenixBlockNumber -> "Phoenix",
      forks.aghartaBlockNumber -> "Agharta",
      forks.atlantisBlockNumber -> "Atlantis",
      forks.difficultyBombRemovalBlockNumber -> "Defuse Difficulty Bomb",
      forks.difficultyBombContinueBlockNumber -> "Gotham",
      forks.difficultyBombPauseBlockNumber -> "Die Hard",
      forks.eip150BlockNumber -> "Gas Reprice",
      forks.homesteadBlockNumber -> "Homestead",
      forks.frontierBlockNumber -> "Frontier"
    ).filter(_._1 <= blockNumber)
      .maxByOption(_._1)
      .map(_._2)
      .getOrElse("Frontier")

  /** Besu AdminNodeInfo: enode string, id (unprefixed hex), ip, listenAddr, name, ports, protocols.eth, activeFork.
    * protocols.eth mirrors Besu's EthProtocolStatus: difficulty (hex), genesis, head, networkId. activeFork mirrors
    * Besu's protocolSchedule.getByBlockHeader().getHardforkId(). Remaining divergences: no NatService (NAT IP), no ENR
    * (discovery layer injection pending).
    */
  def nodeInfo(@unused req: AdminNodeInfoRequest): ServiceResponse[AdminNodeInfoResponse] = IO.pure {
    val status = nodeStatusHolder.get()
    val nodeId = Hex.toHexString(status.nodeId)

    val genesisHash = "0x" + Hex.toHexString(blockchainReader.genesisHeader.hash.toArray)
    val (headHash, headNumber) = blockchainReader.getBestBranch match
      case BestBranch(h, n) => (h, n)
      case _                => (org.apache.pekko.util.ByteString.empty, BigInt(0))
    val headHex = "0x" + Hex.toHexString(headHash.toArray)
    val totalDiff = blockchainReader
      .getChainWeightByHash(com.chipprbots.ethereum.domain.BlockHash(headHash))
      .map(w => "0x" + w.totalDifficulty.value.toString(16))
      .getOrElse("0x0")
    val ethInfo = EthProtocolInfo(
      difficulty = totalDiff,
      genesis = genesisHash,
      head = headHex,
      network = blockchainConfig.networkId
    )
    val fork = activeForkName(headNumber, blockchainConfig.forkBlockNumbers)

    status.serverStatus match
      case ServerStatus.Listening(address) =>
        val host = Option(address.getAddress)
          .map(com.chipprbots.ethereum.network.getHostName)
          .getOrElse(address.getHostString)
        val port = address.getPort
        val listenAddr = s"$host:$port"
        val enode = s"enode://$nodeId@$listenAddr"
        Right(
          AdminNodeInfoResponse(
            enode = Some(enode),
            id = nodeId,
            ip = Some(host),
            listenAddr = Some(listenAddr),
            name = "fukuii/v0.1",
            ports = Map("listener" -> port, "discovery" -> port),
            protocols = Map("eth" -> ethInfo),
            activeFork = fork
          )
        )
      case _ =>
        Right(
          AdminNodeInfoResponse(
            enode = None,
            id = nodeId,
            ip = None,
            listenAddr = None,
            name = "fukuii/v0.1",
            ports = Map.empty,
            protocols = Map("eth" -> ethInfo),
            activeFork = fork
          )
        )
  }

  /** Besu AdminPeers: streams ethPeers.streamAllPeers() → PeerResult.fromEthPeer. Divergence: Fukuii asks
    * PeerManagerActor.GetPeers instead (actor-based P2P, no EthPeers).
    */
  def peers(@unused req: AdminPeersRequest): ServiceResponse[AdminPeersResponse] =
    peerManager
      .askForTyped[PeerManagerActor.Peers](PeerManagerActor.GetPeersCmd(_))
      .map { peersResult =>
        val peerInfos = peersResult.peers.map { case (peer, _) =>
          AdminPeerInfo(
            id = peer.nodeId.map(bs => Hex.toHexString(bs.toArray)).getOrElse(peer.id.value),
            name = s"fukuii-peer/${peer.remoteAddress}",
            remoteAddress = peer.remoteAddress.toString,
            inbound = peer.incomingConnection
          )
        }.toSeq
        Right(AdminPeersResponse(peerInfos))
      }
      .handleError { ex =>
        log.error("Failed to get peers for admin_peers", ex)
        Right(AdminPeersResponse(Seq.empty))
      }

  /** Besu AdminAddPeer: parses enode → DefaultPeer → peerNetwork.addMaintainedConnectionPeer(peer) → boolean. Returns
    * wasAdded=true if the peer was new to the maintained set (false for duplicate calls — aligned with Besu).
    * PeerManagerActor tracks the maintained set and schedules reconnects on disconnect.
    */
  def addPeer(req: AdminAddPeerRequest): ServiceResponse[AdminAddPeerResponse] =
    try
      val uri = new URI(req.enodeUrl)
      peerManager
        .askForTyped[PeerManagerActor.AddMaintainedPeerResponse](ref => PeerManagerActor.AddMaintainedPeerCmd(uri, ref))
        .map(r => Right(AdminAddPeerResponse(r.wasAdded)))
        .handleError { ex =>
          log.error(s"Failed to add peer: ${req.enodeUrl}", ex)
          Right(AdminAddPeerResponse(false))
        }
    catch
      case ex: Exception =>
        log.error(s"Failed to parse enode URL: ${req.enodeUrl}", ex)
        IO.pure(Right(AdminAddPeerResponse(false)))

  /** Besu AdminRemovePeer: peerNetwork.removeMaintainedConnectionPeer(peer) → boolean (wasRemoved). Removes from the
    * maintained set (prevents auto-reconnect) AND disconnects the live connection if present. Returns true if a live
    * peer was found and disconnected; false if the peer was only in the maintained set.
    */
  def removePeer(req: AdminRemovePeerRequest): ServiceResponse[AdminRemovePeerResponse] =
    peerManager
      .askForTyped[PeerManagerActor.Peers](PeerManagerActor.GetPeersCmd(_))
      .map { peersResult =>
        try
          val uri = new URI(req.enodeUrl)
          val targetNodeId = Option(uri.getUserInfo).map(_.toLowerCase)
          targetNodeId match
            case None           => Right(AdminRemovePeerResponse(false))
            case Some(targetId) =>
              // Always remove from maintained set to prevent auto-reconnect
              peerManager ! PeerManagerActor.RemoveMaintainedPeerCmd(targetId)
              val matchingPeer = peersResult.peers.keys.find { peer =>
                peer.nodeId.exists(nid => Hex.toHexString(nid.toArray).toLowerCase == targetId)
              }
              matchingPeer match
                case Some(peer) =>
                  peerManager ! PeerManagerActor.DisconnectPeerFireAndForgetCmd(peer.id)
                  Right(AdminRemovePeerResponse(true))
                case None =>
                  Right(AdminRemovePeerResponse(false))
        catch
          case ex: Exception =>
            log.error(s"Failed to parse enode URL: ${req.enodeUrl}", ex)
            Right(AdminRemovePeerResponse(false))
      }
      .handleError { ex =>
        log.error(s"Failed to remove peer: ${req.enodeUrl}", ex)
        Right(AdminRemovePeerResponse(false))
      }

  /** Besu AdminChangeLogLevel: validates level ∈ {OFF,ERROR,WARN,INFO,DEBUG,TRACE,ALL}. If filters present: set each
    * named logger's level. If no filters: set root logger. Besu uses Log4j2 (org.apache.logging.log4j.core via
    * LogConfigurator). Fukuii uses Logback (ch.qos.logback.classic). Different backends for each project's ecosystem;
    * runtime behaviour (dynamic per-logger level changes via SLF4J) is equivalent.
    */
  def changeLogLevel(req: AdminChangeLogLevelRequest): ServiceResponse[AdminChangeLogLevelResponse] = IO {
    if !ValidLogLevels.contains(req.level) then Left(JsonRpcError.InvalidParams(s"Invalid log level: ${req.level}"))
    else
      try
        val ctx =
          LoggerFactory.getILoggerFactory
            .asInstanceOf[LoggerContext] // interop: SLF4J returns ILoggerFactory; Logback impl is always LoggerContext
        val level = Level.toLevel(req.level)
        val logFilters = req.logFilters.getOrElse(List(""))
        logFilters.foreach { filter =>
          val loggerName = if filter.isEmpty then org.slf4j.Logger.ROOT_LOGGER_NAME else filter
          val logger = ctx.getLogger(loggerName)
          log.debug(s"Setting $loggerName logging level to ${req.level}")
          logger.setLevel(level)
        }
        Right(AdminChangeLogLevelResponse())
      catch
        case ex: Exception =>
          log.error(s"Failed to change log level to ${req.level}", ex)
          Left(JsonRpcError.InternalError)
  }

  def getDatadir(@unused req: AdminDatadirRequest): ServiceResponse[AdminDatadirResponse] =
    IO.pure(Right(AdminDatadirResponse(datadir)))

  /** go-ethereum `admin_exportChain(file, first?, last?)` (eth/api_admin.go): writes the canonical blocks `first..last`
    * (default: genesis to head) to `file` as a [[ChainFile]] — bare concatenated RLP blocks, gzipped when the name ends
    * in `.gz`. Refuses to overwrite an existing file, and fails with the first missing block number rather than writing
    * a file with a gap. The file appears only once complete.
    */
  def exportChain(req: AdminExportChainRequest): ServiceResponse[AdminExportChainResponse] = IO.blocking {
    if req.first.isEmpty && req.last.isDefined then
      Left(JsonRpcError.InvalidParams("last cannot be specified without first"))
    else
      val first = req.first.getOrElse(BigInt(0))
      val last = req.last.getOrElse(blockchainReader.getBestBlockNumber)
      ChainExporter
        .exportChain(blockchainReader, Paths.get(req.file), first, last)
        .left
        .map(JsonRpcError.LogicError(_))
        .map(_ => AdminExportChainResponse(true))
  }

  /** go-ethereum `admin_importChain(file)`: imports a [[ChainFile]] through the node's own block import path (full
    * validation and execution, atomic per-block commit), skipping blocks already present and stopping with an error at
    * the first block that is invalid or does not continue the local chain.
    *
    * Like geth, which holds its chain insert lock for each batch, every block is imported under
    * [[com.chipprbots.ethereum.ledger.ChainInsertLock]], so sync and the Engine API cannot execute or commit a block in
    * between. Refused while the node is syncing (SNAP or catching up): the file must continue the local head, and sync
    * is moving it. Synchronous, as in geth: a long import outlives the HTTP request timeout
    * (`pekko.http.server.request-timeout`), the import carries on, and its outcome is logged. The offline `import`
    * command is the better route for large files.
    */
  def importChain(req: AdminImportChainRequest): ServiceResponse[AdminImportChainResponse] =
    syncStatus().attempt.flatMap {
      case Right(SyncProtocol.Status.Syncing(_, blocks, _)) =>
        IO.pure(
          Left(
            JsonRpcError.LogicError(
              s"admin_importChain refused: the node is syncing (block ${blocks.current} of ${blocks.target}); " +
                "import once sync is done, or stop the node and use the offline `import` command"
            )
          )
        )
      case Left(e) =>
        IO.pure(
          Left(JsonRpcError.LogicError(s"admin_importChain refused: could not read the sync status (${e.getMessage})"))
        )
      case Right(_) =>
        IO.blocking {
          chainImporter
            .importChainFileStrict(req.file)(using blockchainConfig)
            .left
            .map(failure => JsonRpcError.LogicError(failure.describe))
            .map(_ => AdminImportChainResponse(true))
        }
    }

  def blockIP(req: AdminBlockIPRequest): ServiceResponse[AdminBlockIPResponse] = IO {
    val added = blockedIPRegistry.block(req.ip)
    if added then log.info(s"Blocked IP: ${req.ip}")
    Right(AdminBlockIPResponse(added))
  }

  def unblockIP(req: AdminUnblockIPRequest): ServiceResponse[AdminUnblockIPResponse] = IO {
    val removed = blockedIPRegistry.unblock(req.ip)
    if removed then log.info(s"Unblocked IP: ${req.ip}")
    Right(AdminUnblockIPResponse(removed))
  }

  def listBlockedIPs(@unused req: AdminListBlockedIPsRequest): ServiceResponse[AdminListBlockedIPsResponse] = IO {
    Right(AdminListBlockedIPsResponse(blockedIPRegistry.all.toList.sorted))
  }

  /** core-geth admin_addTrustedPeer: adds peer to trusted set (bypasses max-peer limit). Always returns true — mirrors
    * core-geth node/api.go AddTrustedPeer returning (true, nil).
    */
  def addTrustedPeer(req: AdminAddTrustedPeerRequest): ServiceResponse[AdminAddTrustedPeerResponse] =
    try
      val uri = new URI(req.enodeUrl)
      peerManager
        .askForTyped[PeerManagerActor.AddTrustedPeerResponse](ref => PeerManagerActor.AddTrustedPeerCmd(uri, ref))
        .map(r => Right(AdminAddTrustedPeerResponse(r.success)))
        .handleError { ex =>
          log.error(s"Failed to add trusted peer: ${req.enodeUrl}", ex)
          Right(AdminAddTrustedPeerResponse(false))
        }
    catch
      case ex: Exception =>
        log.error(s"Failed to parse enode URL: ${req.enodeUrl}", ex)
        IO.pure(Right(AdminAddTrustedPeerResponse(false)))

  /** core-geth admin_removeTrustedPeer: removes from trusted set; does NOT disconnect. Always returns true — mirrors
    * core-geth node/api.go RemoveTrustedPeer returning (true, nil).
    */
  def removeTrustedPeer(req: AdminRemoveTrustedPeerRequest): ServiceResponse[AdminRemoveTrustedPeerResponse] =
    try
      val uri = new URI(req.enodeUrl)
      val targetNodeId = Option(uri.getUserInfo).map(_.toLowerCase).getOrElse("")
      peerManager
        .askForTyped[PeerManagerActor.RemoveTrustedPeerResponse](ref =>
          PeerManagerActor.RemoveTrustedPeerCmd(targetNodeId, ref)
        )
        .map(r => Right(AdminRemoveTrustedPeerResponse(r.success)))
        .handleError { ex =>
          log.error(s"Failed to remove trusted peer: ${req.enodeUrl}", ex)
          Right(AdminRemoveTrustedPeerResponse(false))
        }
    catch
      case ex: Exception =>
        log.error(s"Failed to parse enode URL: ${req.enodeUrl}", ex)
        IO.pure(Right(AdminRemoveTrustedPeerResponse(false)))

  /** core-geth admin_maxPeers: sets max connected peers at runtime. core-geth reference: eth/api_admin.go MaxPeers —
    * sets handler.maxPeers + p2pServer.MaxPeers.
    */
  def maxPeers(req: AdminMaxPeersRequest): ServiceResponse[AdminMaxPeersResponse] =
    peerManager
      .askForTyped[PeerManagerActor.SetMaxPeersResponse](ref => PeerManagerActor.SetMaxPeersCmd(req.maxPeers, ref))
      .map(r => Right(AdminMaxPeersResponse(r.success)))
      .handleError { ex =>
        log.error(s"Failed to set max peers to ${req.maxPeers}", ex)
        Right(AdminMaxPeersResponse(false))
      }
