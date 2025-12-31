package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime

import com.chipprbots.scalanet.discovery.crypto.PrivateKey
import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.v4
import com.chipprbots.scalanet.discovery.ethereum.{Node => ENode}
import com.chipprbots.scalanet.peergroup.ExternalAddressResolver
import com.chipprbots.scalanet.peergroup.InetMultiAddress
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
import scodec.Codec
import scodec.bits.BitVector

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.KnownNodesStorage
import com.chipprbots.ethereum.network.discovery.codecs.RLPCodecs
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus
import com.chipprbots.scalanet.discovery.ethereum.v4.Packet
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord.Content

trait DiscoveryServiceBuilder {

  def discoveryServiceResource(
      discoveryConfig: DiscoveryConfig,
      tcpPort: Int,
      nodeStatusHolder: AtomicReference[NodeStatus],
      knownNodesStorage: KnownNodesStorage
  )(implicit scheduler: IORuntime): Resource[IO, v4.DiscoveryService] = {

    implicit val sigalg = new Secp256k1SigAlg()
    val keyPair = nodeStatusHolder.get.key
    val (privateKeyBytes, _) = crypto.keyPairToByteArrays(keyPair)
    val privateKey = PrivateKey(BitVector(privateKeyBytes))

    implicit val packetCodec: Codec[Packet] = v4.Packet.packetCodec(allowDecodeOverMaxPacketSize = true)
    implicit val payloadCodec = RLPCodecs.payloadCodec
    implicit val enrContentCodec: Codec[Content] = RLPCodecs.codecFromRLPCodec(RLPCodecs.enrContentRLPCodec)

    val resource = for {
      host <- Resource.eval {
        getExternalAddress(discoveryConfig)
      }
      localNode = ENode(
        id = sigalg.toPublicKey(privateKey),
        address = ENode.Address(
          ip = host,
          udpPort = discoveryConfig.port,
          tcpPort = tcpPort
        )
      )
      v4Config <- Resource.eval {
        makeDiscoveryConfig(discoveryConfig, knownNodesStorage)
      }
      udpConfig = makeUdpConfig(discoveryConfig, host)
      network <- makeDiscoveryNetwork(privateKey, localNode, v4Config, udpConfig)
      service <- makeDiscoveryService(privateKey, localNode, v4Config, network)
      _ <- Resource.eval {
        setDiscoveryStatus(nodeStatusHolder, ServerStatus.Listening(udpConfig.bindAddress))
      }
    } yield service

    resource
      .onFinalize {
        setDiscoveryStatus(nodeStatusHolder, ServerStatus.NotListening)
      }
  }

  private def makeDiscoveryConfig(
      discoveryConfig: DiscoveryConfig,
      knownNodesStorage: KnownNodesStorage
  ): IO[v4.DiscoveryConfig] =
    for {
      reusedKnownNodes <-
        if (discoveryConfig.reuseKnownNodes)
          IO(knownNodesStorage.getKnownNodes().map(Node.fromUri))
        else
          IO.pure(Set.empty[Node])
      // Discovery is going to enroll with all the bootstrap nodes passed to it.
      // Since we're running the enrollment in the background, it won't hold up
      // anything even if we have to enroll with hundreds of previously known nodes.
      knownPeers = (discoveryConfig.bootstrapNodes ++ reusedKnownNodes).map { node =>
        ENode(
          id = PublicKey(BitVector(node.id.toArray[Byte])),
          address = ENode.Address(
            ip = node.addr,
            udpPort = node.udpPort,
            tcpPort = node.tcpPort
          )
        )
      }
      config = v4.DiscoveryConfig.default.copy(
        messageExpiration = discoveryConfig.messageExpiration,
        maxClockDrift = discoveryConfig.maxClockDrift,
        discoveryPeriod = discoveryConfig.scanInterval,
        requestTimeout = discoveryConfig.requestTimeout,
        kademliaTimeout = discoveryConfig.kademliaTimeout,
        kademliaBucketSize = discoveryConfig.kademliaBucketSize,
        kademliaAlpha = discoveryConfig.kademliaAlpha,
        knownPeers = knownPeers
      )
    } yield config

  private def getExternalAddress(discoveryConfig: DiscoveryConfig): IO[InetAddress] =
    discoveryConfig.host match {
      case Some(host) =>
        IO(InetAddress.getByName(host))

      case None =>
        ExternalAddressResolver.default.resolve.flatMap {
          case Some(address) =>
            IO.pure(address)
          case None =>
            // In some environments (Docker, CI, NAT without UPnP), external address resolution can fail.
            // Discovery can still function for outbound lookups if we bind to a local interface address.
            val fallbackHostOpt = Option(discoveryConfig.interface)
              .map(_.trim)
              .filter(h => h.nonEmpty && h != "0.0.0.0" && h != "::")

            fallbackHostOpt match {
              case Some(host) =>
                IO(InetAddress.getByName(host)).flatTap { addr =>
                  IO(
                    log.warn(
                      s"Failed to resolve external discovery address; falling back to configured interface '$host' ($addr). " +
                        "For best results set fukuii.network.discovery.host explicitly."
                    )
                  )
                }
              case None =>
                IO(InetAddress.getLocalHost).flatTap { addr =>
                  IO(
                    log.warn(
                      s"Failed to resolve external discovery address; falling back to InetAddress.getLocalHost ($addr). " +
                        "For best results set fukuii.network.discovery.host explicitly (e.g. container DNS name or public IP)."
                    )
                  )
                }
            }
        }
    }

  private def makeUdpConfig(discoveryConfig: DiscoveryConfig, host: InetAddress): StaticUDPPeerGroup.Config =
    StaticUDPPeerGroup.Config(
      bindAddress = new InetSocketAddress(discoveryConfig.interface, discoveryConfig.port),
      processAddress = InetMultiAddress(new InetSocketAddress(host, discoveryConfig.port)),
      channelCapacity = discoveryConfig.channelCapacity,
      receiveBufferSizeBytes = v4.Packet.MaxPacketBitsSize / 8 * 2
    )

  private def setDiscoveryStatus(nodeStatusHolder: AtomicReference[NodeStatus], status: ServerStatus): IO[Unit] =
    IO(nodeStatusHolder.updateAndGet(_.copy(discoveryStatus = status)))

  private def makeDiscoveryNetwork(
      privateKey: PrivateKey,
      localNode: ENode,
      v4Config: v4.DiscoveryConfig,
      udpConfig: StaticUDPPeerGroup.Config
  )(implicit
      payloadCodec: Codec[v4.Payload],
      packetCodec: Codec[v4.Packet],
      sigalg: SigAlg
  ): Resource[IO, v4.DiscoveryNetwork[InetMultiAddress]] =
    for {
      peerGroup <- StaticUDPPeerGroup[v4.Packet](udpConfig)
      network <- Resource.eval {
        v4.DiscoveryNetwork[InetMultiAddress](
          peerGroup = peerGroup,
          privateKey = privateKey,
          localNodeAddress = localNode.address,
          toNodeAddress = (address: InetMultiAddress) =>
            ENode.Address(
              ip = address.inetSocketAddress.getAddress,
              udpPort = address.inetSocketAddress.getPort,
              tcpPort = 0
            ),
          config = v4Config
        )
      }
    } yield network

  private def makeDiscoveryService(
      privateKey: PrivateKey,
      localNode: ENode,
      v4Config: v4.DiscoveryConfig,
      network: v4.DiscoveryNetwork[InetMultiAddress]
  )(implicit sigalg: SigAlg, enrContentCodec: Codec[EthereumNodeRecord.Content]): Resource[IO, v4.DiscoveryService] =
    v4.DiscoveryService[InetMultiAddress](
      privateKey = privateKey,
      node = localNode,
      config = v4Config,
      network = network,
      toAddress = (address: ENode.Address) => InetMultiAddress(new InetSocketAddress(address.ip, address.udpPort)),
      // On a network with many bootstrap nodes the enrollment and the initial self-lookup can take considerable
      // amount of time. We can do the enrollment in the background, which means the service is available from the
      // start, and the nodes can be contacted and gradually as they are discovered during the iterative lookup,
      // rather than at the end of the enrollment. Fukuii will also contact its previously persisted peers,
      // from that perspective it doesn't care whether enrollment is over or not.
      enrollInBackground = true
    )
}
