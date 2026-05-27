package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.unsafe.IORuntime
import cats.implicits._
import scodec.bits.ByteVector

import com.chipprbots.scalanet.peergroup.CloseableQueue

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
import com.chipprbots.ethereum.network.discovery.codecs.V5RLPCodecs
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus
import com.chipprbots.scalanet.discovery.ethereum.v4.Packet
import com.chipprbots.scalanet.discovery.ethereum.v5
import com.chipprbots.scalanet.discovery.ethereum.{Node => ScNode}
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord.Content
import com.chipprbots.scalanet.peergroup.udp.V5DemuxResponder

trait DiscoveryServiceBuilder extends Logger {

  def discoveryServiceResource(
      discoveryConfig: DiscoveryConfig,
      tcpPort: Int,
      nodeStatusHolder: AtomicReference[NodeStatus],
      knownNodesStorage: KnownNodesStorage,
      forkIdTag: Option[ForkIdTag] = None
  )(implicit scheduler: IORuntime): Resource[IO, v4.DiscoveryService] = {

    implicit val sigalg = new Secp256k1SigAlg()
    val keyPair = nodeStatusHolder.get.key
    val (privateKeyBytes, _) = crypto.keyPairToByteArrays(keyPair)
    val privateKey = PrivateKey(BitVector(privateKeyBytes))

    implicit val packetCodec: Codec[Packet] = v4.Packet.packetCodec(allowDecodeOverMaxPacketSize = true)
    implicit val payloadCodec = RLPCodecs.payloadCodec
    implicit val enrContentCodec: Codec[Content] = RLPCodecs.codecFromRLPCodec(RLPCodecs.enrContentRLPCodec)

    // Warm up the discv4 packet pack/unpack path eagerly. The first invocation
    // pays ~100 ms in JIT/class-loading + Bouncy Castle ECDSA provider init +
    // scodec RLP codec materialisation. By driving a synthetic Ping through the
    // codec during startup we move that cost into the (un-timed) init window
    // instead of paying it on the first incoming Ping — which the hive devp2p
    // suite tests against a 300 ms reply deadline.
    locally {
      import com.chipprbots.scalanet.discovery.ethereum.v4.Payload
      import com.chipprbots.scalanet.discovery.ethereum.Node
      import java.net.InetAddress
      val dummyAddr = Node.Address(InetAddress.getLoopbackAddress, udpPort = 0, tcpPort = 0)
      val dummyPing: Payload =
        Payload.Ping(version = 4, from = dummyAddr, to = dummyAddr, expiration = 0L, enrSeq = None)
      // Pack uses the local private key; unpack recovers it from the signature.
      // Both code paths are exercised; if anything throws, we don't propagate
      // the error — discovery still works, we just lose the warmup benefit.
      try
        v4.Packet.pack(dummyPing, privateKey).toOption.foreach { packet =>
          val _ = v4.Packet.unpack(packet)
        }
      catch {
        case scala.util.control.NonFatal(ex) =>
          log.warn(s"discv4 codec warmup failed (non-fatal): ${ex.getMessage}")
      }
    }

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
      // Synchronous discv4 fast-path: replies to Ping with Pong on the netty
      // event-loop thread, bypassing the cats-effect IO scheduler. This is the
      // only path that meets hive's 300 ms `waitTime` deadline; the structural
      // fixes in PR #1090 reduced same-target lookup spam and the first-Ping
      // JIT cost, but the full async pipeline is still ~600 ms end-to-end
      // under load. The responder is opt-in via the SyncResponder Config.
      //
      // The async pipeline still runs after the sync reply for bonding /
      // kademlia bookkeeping; the only side-effect of the fast-path is that
      // a duplicate Pong may also leave the async pipeline. Both are valid;
      // the simulator (and any well-behaved peer) ignores duplicates.
      //
      // localEnrSeqRef is currently a static `None` — adding a wire-up to
      // the DiscoveryService.stateRef so the seq stays in sync with ENR
      // rotations is a follow-up. Most discv4 hive tests don't check seq.
      localEnrSeqRef = new AtomicReference[Option[Long]](None)
      // Shared dedup state between the sync responder and the async pipeline.
      // The sync path marks every Ping it answers; the async pipeline checks
      // before sending its own Pong so we don't emit duplicates on the wire
      // (hive's discv4 simulator counts and rejects duplicate Pongs).
      pingDedup = new v4.Discv4SyncResponder.PingDedup
      v4SyncResponder = v4.Discv4SyncResponder(
        privateKey = privateKey,
        expirationSeconds = discoveryConfig.messageExpiration.toSeconds,
        maxClockDriftSeconds = discoveryConfig.maxClockDrift.toSeconds,
        localEnrSeqRef = localEnrSeqRef,
        dedup = pingDedup
      )
      // Compose the v5 sync responder + demuxer alongside v4. The chain order
      // matters: v5 first (fast peek that claims v5-shaped packets), v4 second
      // (handles legacy discv4). Non-discv5/v4 bytes return Pass through both
      // and fall to the v4 codec's async decode (where they'll fail with a
      // DecodingError). The v5 caches here are shared with the (future-wired)
      // `v5.DiscoveryService` so populated bystander ENRs surface in
      // FINDNODE responses immediately.
      v5SessionCache = new v5.Session.SessionCache()
      v5ChallengeCache = new v5.Discv5SyncResponder.ChallengeCache()
      v5BystanderTable = new v5.Discv5SyncResponder.BystanderEnrTable()
      v5Queue <- Resource.eval(CloseableQueue.unbounded[(InetSocketAddress, ByteVector)])
      v5InitialEnr = EthereumNodeRecord
        .fromNode(
          ScNode(
            id = localNode.id,
            address = ScNode.Address(
              ip = localNode.address.ip,
              udpPort = localNode.address.udpPort,
              tcpPort = localNode.address.tcpPort
            )
          ),
          privateKey,
          seq = 1
        )
        .require
      v5EnrRef = new AtomicReference[EthereumNodeRecord](v5InitialEnr)
      // The sync responder needs a way to send unsolicited UDP packets back to
      // a peer (after a handshake completes, fukuii pings the peer to confirm
      // bonding — this is what hive's `FindnodeResults` waits for). We can't
      // pass the peer group's `sendRaw` directly because the responder is
      // constructed BEFORE the peer group. Instead, we hand the responder an
      // AtomicReference that the wiring in [[makeDiscoveryNetwork]] populates
      // once the peer group exists.
      v5OutboundSenderRef =
        new AtomicReference[Option[v5.Discv5SyncResponder.OutboundSender]](None)
      v5Responder = buildV5SyncResponder(
        privateKey,
        localNode,
        v5SessionCache,
        v5ChallengeCache,
        v5BystanderTable,
        v5EnrRef,
        v5OutboundSenderRef,
        v5Queue
      )
      chainedResponder = StaticUDPPeerGroup.chainResponders(v5Responder, v4SyncResponder)
      udpConfig = makeUdpConfig(discoveryConfig, host, chainedResponder)
      network <- makeDiscoveryNetwork(privateKey, localNode, v4Config, udpConfig, pingDedup, v5OutboundSenderRef)
      // Start the v5 active discovery service in the background alongside v4.
      // Uses the shared session/challenge/bystander caches so ENRs discovered
      // via v5 are immediately visible to the v5 sync responder.
      _ <- buildAndStartV5Service(
        privateKey,
        localNode,
        v5SessionCache,
        v5ChallengeCache,
        v5BystanderTable,
        v5EnrRef,
        v5Queue,
        v5OutboundSenderRef,
        discoveryConfig
      )
      service <- makeDiscoveryService(privateKey, localNode, v4Config, network, forkIdTag)
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
        knownPeers = knownPeers,
        subnetLimitPrefixLength = discoveryConfig.subnetLimitPrefixLength,
        subnetLimitForBucket = discoveryConfig.subnetLimitForBucket,
        subnetLimitForTable = discoveryConfig.subnetLimitForTable
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

  private def makeUdpConfig(
      discoveryConfig: DiscoveryConfig,
      host: InetAddress,
      syncResponder: StaticUDPPeerGroup.SyncResponder
  ): StaticUDPPeerGroup.Config =
    StaticUDPPeerGroup.Config(
      bindAddress = new InetSocketAddress(discoveryConfig.interface, discoveryConfig.port),
      processAddress = InetMultiAddress(new InetSocketAddress(host, discoveryConfig.port)),
      channelCapacity = discoveryConfig.channelCapacity,
      receiveBufferSizeBytes = v4.Packet.MaxPacketBitsSize / 8 * 2,
      syncResponder = syncResponder
    )

  private def setDiscoveryStatus(nodeStatusHolder: AtomicReference[NodeStatus], status: ServerStatus): IO[Unit] =
    IO(nodeStatusHolder.updateAndGet(_.copy(discoveryStatus = status)))

  private def makeDiscoveryNetwork(
      privateKey: PrivateKey,
      localNode: ENode,
      v4Config: v4.DiscoveryConfig,
      udpConfig: StaticUDPPeerGroup.Config,
      pingDedup: v4.Discv4SyncResponder.PingDedup,
      v5OutboundSenderRef: AtomicReference[Option[v5.Discv5SyncResponder.OutboundSender]]
  )(implicit
      payloadCodec: Codec[v4.Payload],
      packetCodec: Codec[v4.Packet],
      sigalg: SigAlg,
      runtime: IORuntime
  ): Resource[IO, v4.DiscoveryNetwork[InetMultiAddress]] =
    for {
      peerGroup <- StaticUDPPeerGroup[v4.Packet](udpConfig)
      // Now that the peer group exists, populate the outbound sender so the v5
      // sync responder can send post-handshake ping-backs through this UDP
      // channel. Fire-and-forget at the IO level — the netty `writeAndFlush`
      // is async and we don't await it on the netty event-loop thread.
      _ <- Resource.eval {
        IO {
          v5OutboundSenderRef.set(
            Some { (addr: java.net.InetSocketAddress, bytes: scodec.bits.ByteVector, delayMillis: Long) =>
              // Each call optionally schedules a delay before the UDP write
              // — only the post-handshake ping-back asks for one (so it
              // doesn't race the synchronous Pong); chunked NODES replies
              // pass 0 and are written immediately.
              val send = peerGroup.sendRaw(addr, bytes)
              val task =
                if (delayMillis > 0) IO.sleep(scala.concurrent.duration.FiniteDuration(delayMillis, "ms")) *> send
                else send
              task.unsafeRunAndForget()(runtime)
            }
          )
        }
      }
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
          config = v4Config,
          pingDedup = pingDedup
        )
      }
    } yield network

  /** Build the v5 synchronous responder. Wires:
    *   - [[v5.Discv5SyncResponder]] for inbound packet handling on the netty event-loop thread (sub-300ms hive
    *     deadline)
    *   - Shared [[v5.Session.SessionCache]] / [[v5.Discv5SyncResponder.ChallengeCache]] /
    *     [[v5.Discv5SyncResponder.BystanderEnrTable]] for cross-cutting state
    *   - A `Handler` whose `findNodes` reads from the bystander table — so as the async discovery service populates the
    *     table, the sync responder immediately starts returning real ENRs to FINDNODE requests
    *
    * The active discovery service ([[v5.DiscoveryService]]) is constructed separately by [[buildV5DiscoveryService]]
    * using the same caches.
    *
    * Returns the responder wrapped in [[V5DemuxResponder]] for the future v5 dispatch queue side-channel — currently
    * None (transparent forwarder) because the `DiscoveryNetwork.startHandling` consumer that would read from the queue
    * isn't started in this minimal build path.
    */
  private def buildV5SyncResponder(
      privateKey: PrivateKey,
      localNode: ENode,
      sessions: v5.Session.SessionCache,
      challenges: v5.Discv5SyncResponder.ChallengeCache,
      bystanders: v5.Discv5SyncResponder.BystanderEnrTable,
      enrRef: AtomicReference[EthereumNodeRecord],
      outboundSenderRef: AtomicReference[Option[v5.Discv5SyncResponder.OutboundSender]],
      v5Queue: CloseableQueue[(InetSocketAddress, ByteVector)]
  )(implicit
      sigalg: SigAlg,
      runtime: IORuntime
  ): StaticUDPPeerGroup.SyncResponder = {
    import V5RLPCodecs.codecFromRLPCodec
    implicit val v5PayloadCodec: Codec[v5.Payload] = V5RLPCodecs.payloadCodec
    implicit val v5EnrCodec: Codec[EthereumNodeRecord] = codecFromRLPCodec(V5RLPCodecs.enrRLPCodec)

    val localPubBytes = localNode.id.value.bytes
    val localNodeId = v5.Session.nodeIdFromPublicKey(localPubBytes)

    val handler = new v5.Discv5SyncResponder.Handler {
      def localEnr: EthereumNodeRecord = enrRef.get
      def localEnrSeq: Long = enrRef.get.content.seq
      def findNodes(distances: List[Int]): List[EthereumNodeRecord] = {
        // Pull every distance the peer asked about from the bystander table;
        // distance=0 yields the local ENR explicitly.
        val builder = List.newBuilder[EthereumNodeRecord]
        distances.foreach { d =>
          if (d == 0) builder += enrRef.get
          else builder ++= bystanders.atDistance(localNodeId, d)
        }
        builder.result()
      }
    }

    val responder = v5.Discv5SyncResponder(
      privateKey = privateKey,
      localNodeId = localNodeId,
      handler = handler,
      sessions = sessions,
      challenges = challenges,
      bystanders = bystanders,
      outboundSenderRef = outboundSenderRef
    )
    V5DemuxResponder(responder, queue = Some(v5Queue))
  }

  /** Start the discv5 active discovery service (bootstrap pings + periodic FINDNODE).
    *
    * The service runs its fibers inside a Resource so they are cancelled cleanly when the resource is released. Uses
    * the same session/challenge/bystander caches as the sync responder so discovered peers are immediately usable for
    * FINDNODE replies.
    *
    * Sends a no-op handler to `startHandling` — the sync responder already answers all inbound requests; the handler is
    * only for service-level bookkeeping that we don't need in the minimal activation path.
    */
  private def buildAndStartV5Service(
      privateKey: PrivateKey,
      localNode: ENode,
      sessions: v5.Session.SessionCache,
      challenges: v5.Discv5SyncResponder.ChallengeCache,
      bystanders: v5.Discv5SyncResponder.BystanderEnrTable,
      enrRef: AtomicReference[EthereumNodeRecord],
      v5Queue: CloseableQueue[(InetSocketAddress, ByteVector)],
      outboundSenderRef: AtomicReference[Option[v5.Discv5SyncResponder.OutboundSender]],
      discoveryConfig: DiscoveryConfig
  )(implicit sigalg: SigAlg, @annotation.unused _runtime: IORuntime): Resource[IO, Unit] = {
    implicit val v5PayloadCodec: scodec.Codec[v5.Payload] = V5RLPCodecs.payloadCodec
    implicit val v5EnrContentCodec: scodec.Codec[EthereumNodeRecord.Content] =
      RLPCodecs.codecFromRLPCodec(RLPCodecs.enrContentRLPCodec)
    implicit val temporal: Temporal[IO] = cats.effect.IO.asyncForIO

    val localPubBytes = localNode.id.value.bytes
    val localNodeId = v5.Session.nodeIdFromPublicKey(localPubBytes)

    // Bootstrap nodes: reuse the v4-style conversion (scalanet ENode = scalanet Node)
    val bootstrapNodes: Set[ScNode] = discoveryConfig.bootstrapNodes.map { n =>
      ScNode(
        id = PublicKey(BitVector(n.id.toArray[Byte])),
        address = ScNode.Address(ip = n.addr, udpPort = n.udpPort, tcpPort = n.tcpPort)
      )
    }

    // PeerGroupSender adapter: delegates to the outbound sender populated by makeDiscoveryNetwork
    val peerGroupSender = new v5.DiscoveryNetwork.PeerGroupSender[InetSocketAddress] {
      def sendRaw(addr: InetSocketAddress, bytes: ByteVector): IO[Unit] =
        IO(outboundSenderRef.get().foreach(_.apply(addr, bytes, 0L)))
    }

    for {
      v5Network <- Resource.eval {
        v5.DiscoveryNetwork[InetSocketAddress](
          peerGroup = peerGroupSender,
          privateKey = privateKey,
          publicKey = localNode.id,
          localNodeId = localNodeId,
          localEnrRef = enrRef,
          sessions = sessions,
          challenges = challenges,
          bystanders = bystanders,
          dispatchQueue = v5Queue,
          config = v5.DiscoveryConfig.default
        )
      }
      // Start inbound consumer fiber with a no-op handler (sync responder handles replies)
      _ <- Resource.eval {
        val noopHandler = new v5.DiscoveryRPC[v5.DiscoveryNetwork.Peer[InetSocketAddress]] {
          override def ping(peer: v5.DiscoveryNetwork.Peer[InetSocketAddress], localEnrSeq: Long) =
            IO.pure(None)
          override def findNode(peer: v5.DiscoveryNetwork.Peer[InetSocketAddress], distances: List[Int]) =
            IO.pure(None)
          override def talkRequest(
              peer: v5.DiscoveryNetwork.Peer[InetSocketAddress],
              protocol: ByteVector,
              message: ByteVector
          ) =
            IO.pure(None)
        }
        v5Network.startHandling(noopHandler).void
      }
      _ <- v5
        .DiscoveryService(
          privateKey = privateKey,
          publicKey = localNode.id,
          localNode = ScNode(
            id = localNode.id,
            address = ScNode.Address(
              ip = localNode.address.ip,
              udpPort = localNode.address.udpPort,
              tcpPort = localNode.address.tcpPort
            )
          ),
          network = v5Network,
          sessions = sessions,
          bystanders = bystanders,
          bootstrapNodes = bootstrapNodes,
          config = v5.DiscoveryConfig.default
        )
        .void
    } yield ()
  }

  private def makeDiscoveryService(
      privateKey: PrivateKey,
      localNode: ENode,
      v4Config: v4.DiscoveryConfig,
      network: v4.DiscoveryNetwork[InetMultiAddress],
      forkIdTag: Option[ForkIdTag]
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
      enrollInBackground = true,
      tags = forkIdTag.toList
    )
}
