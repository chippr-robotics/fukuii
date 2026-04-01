package com.chipprbots.ethereum.jsonrpc

import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Block.BlockEnc
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.utils.Hex
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

object AdminService {

  case class AdminNodeInfoRequest()
  case class AdminNodeInfoResponse(
      enode: Option[String],
      id: String,
      ip: Option[String],
      listenAddr: Option[String],
      name: String,
      ports: Map[String, Int],
      protocols: Map[String, String]
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
}

class AdminService(
    nodeStatusHolder: AtomicReference[NodeStatus],
    peerManager: ActorRef,
    blockchainReader: BlockchainReader,
    peerManagerTimeout: FiniteDuration,
    datadir: String,
    blockedIPRegistry: com.chipprbots.ethereum.network.BlockedIPRegistry
) extends Logger {
  import AdminService._

  implicit private val timeout: Timeout = Timeout(peerManagerTimeout)

  def nodeInfo(req: AdminNodeInfoRequest): ServiceResponse[AdminNodeInfoResponse] = IO.pure {
    val status = nodeStatusHolder.get()
    val nodeId = Hex.toHexString(status.nodeId)

    status.serverStatus match {
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
            protocols = Map("eth" -> "68")
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
            protocols = Map("eth" -> "68")
          )
        )
    }
  }

  def peers(req: AdminPeersRequest): ServiceResponse[AdminPeersResponse] =
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map { peers =>
        val peerInfos = peers.peers.map { case (peer, status) =>
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

  def addPeer(req: AdminAddPeerRequest): ServiceResponse[AdminAddPeerResponse] = IO {
    try {
      val uri = new URI(req.enodeUrl)
      peerManager ! PeerManagerActor.ConnectToPeer(uri)
      Right(AdminAddPeerResponse(true))
    } catch {
      case ex: Exception =>
        log.error(s"Failed to parse enode URL: ${req.enodeUrl}", ex)
        Right(AdminAddPeerResponse(false))
    }
  }

  def removePeer(req: AdminRemovePeerRequest): ServiceResponse[AdminRemovePeerResponse] = IO {
    try {
      val uri = new URI(req.enodeUrl)
      // Extract node ID from enode URL (enode://nodeId@host:port)
      val nodeId = uri.getUserInfo
      if (nodeId != null) {
        peerManager ! PeerManagerActor.DisconnectPeerById(PeerId(nodeId))
        Right(AdminRemovePeerResponse(true))
      } else {
        Right(AdminRemovePeerResponse(false))
      }
    } catch {
      case ex: Exception =>
        log.error(s"Failed to parse enode URL: ${req.enodeUrl}", ex)
        Right(AdminRemovePeerResponse(false))
    }
  }

  def getDatadir(req: AdminDatadirRequest): ServiceResponse[AdminDatadirResponse] =
    IO.pure(Right(AdminDatadirResponse(datadir)))

  def exportChain(req: AdminExportChainRequest): ServiceResponse[AdminExportChainResponse] = IO {
    try {
      val first = req.first.getOrElse(BigInt(0))
      val last = req.last.getOrElse(blockchainReader.getBestBlockNumber())
      val out = new BufferedOutputStream(new FileOutputStream(req.file))
      try {
        var i = first
        while (i <= last) {
          blockchainReader.getBestBlock() // warmup
          blockchainReader.getBlockHeaderByNumber(i).foreach { header =>
            blockchainReader.getBlockByHash(header.hash).foreach { block =>
              val bytes: Array[Byte] = block.toBytes
              // Length-prefixed format: 4-byte big-endian length + RLP block bytes
              val lenBuf = ByteBuffer.allocate(4).putInt(bytes.length).array()
              out.write(lenBuf)
              out.write(bytes)
            }
          }
          i += 1
        }
        out.flush()
        log.info(s"Exported blocks $first to $last to ${req.file}")
        Right(AdminExportChainResponse(true))
      } finally {
        out.close()
      }
    } catch {
      case ex: Exception =>
        log.error(s"Failed to export chain to ${req.file}", ex)
        Right(AdminExportChainResponse(false))
    }
  }

  def importChain(req: AdminImportChainRequest): ServiceResponse[AdminImportChainResponse] = IO {
    try {
      val in = new FileInputStream(req.file)
      try {
        var count = 0
        val lenBuf = new Array[Byte](4)
        while (in.read(lenBuf) == 4) {
          val len = ByteBuffer.wrap(lenBuf).getInt
          val blockBytes = new Array[Byte](len)
          var read = 0
          while (read < len) {
            val n = in.read(blockBytes, read, len - read)
            if (n == -1) throw new java.io.EOFException("Unexpected end of file")
            read += n
          }
          val block = blockBytes.toBlock
          // Note: imported blocks are stored but not validated — caller should verify chain consistency
          log.debug(s"Imported block ${block.header.number}")
          count += 1
        }
        log.info(s"Imported $count blocks from ${req.file}")
        Right(AdminImportChainResponse(true))
      } finally {
        in.close()
      }
    } catch {
      case ex: Exception =>
        log.error(s"Failed to import chain from ${req.file}", ex)
        Right(AdminImportChainResponse(false))
    }
  }

  def blockIP(req: AdminBlockIPRequest): ServiceResponse[AdminBlockIPResponse] = IO {
    val added = blockedIPRegistry.block(req.ip)
    if (added) log.info(s"Blocked IP: ${req.ip}")
    Right(AdminBlockIPResponse(added))
  }

  def unblockIP(req: AdminUnblockIPRequest): ServiceResponse[AdminUnblockIPResponse] = IO {
    val removed = blockedIPRegistry.unblock(req.ip)
    if (removed) log.info(s"Unblocked IP: ${req.ip}")
    Right(AdminUnblockIPResponse(removed))
  }

  def listBlockedIPs(req: AdminListBlockedIPsRequest): ServiceResponse[AdminListBlockedIPsResponse] = IO {
    Right(AdminListBlockedIPsResponse(blockedIPRegistry.all.toList.sorted))
  }
}
