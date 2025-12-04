package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.effect.{Deferred, IO, Ref, Temporal}
import cats.implicits._
import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg}
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import com.chipprbots.scalanet.discovery.hash.{Hash, Keccak256}
import com.chipprbots.scalanet.peergroup.{Addressable, Channel, PeerGroup}
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import java.net.InetSocketAddress

/** Network layer for Discovery v5 protocol
  * 
  * Handles packet encoding/decoding, session management, and RPC operations
  */
trait DiscoveryNetwork[A] extends DiscoveryRPC[DiscoveryNetwork.Peer[A]] {
  
  /** Start handling incoming requests
    * 
    * @param handler The RPC handler to process incoming requests
    * @return A deferred that can be used to stop handling
    */
  def startHandling(handler: DiscoveryRPC[DiscoveryNetwork.Peer[A]]): IO[Deferred[IO, Unit]]
}

object DiscoveryNetwork {
  
  /** Peer identifier combining node ID and network address */
  case class Peer[A](id: Node.Id, address: A) {
    override def toString: String =
      s"Peer(id = ${id.value.toHex.take(16)}..., address = $address)"
      
    lazy val kademliaId: Hash = Node.kademliaId(id)
  }
  
  object Peer {
    implicit def addressable[A: Addressable]: Addressable[Peer[A]] = new Addressable[Peer[A]] {
      override def getAddress(a: Peer[A]): InetSocketAddress =
        Addressable[A].getAddress(a.address)
    }
  }
  
  /** Exception for packet processing errors */
  class PacketException(message: String) extends Exception(message) with NoStackTrace
  
  /** Create a Discovery v5 network instance
    * 
    * @param peerGroup The underlying UDP peer group
    * @param privateKey Local node's private key
    * @param localNode Local node information
    * @param toNodeAddress Function to convert network address to Node.Address
    * @param config Discovery configuration
    * @param sessionCache Session cache for managing peer sessions
    * @param sigalg Signature algorithm
    * @param temporal Temporal effect for timing operations
    * @return Discovery network instance
    */
  def apply[A](
    peerGroup: PeerGroup[A, Packet],
    privateKey: PrivateKey,
    localNode: Node,
    localEnrSeq: Long,  // Add ENR sequence number parameter
    toNodeAddress: A => Node.Address,
    config: DiscoveryConfig,
    sessionCache: Session.SessionCache
  )(implicit 
    sigalg: SigAlg,
    temporal: Temporal[IO],
    addressable: Addressable[A]
  ): IO[DiscoveryNetwork[A]] = IO {
    
    new DiscoveryNetwork[A] with LazyLogging {
      import DiscoveryRPC._
      import Payload._
      
      private val requestTimeout = config.requestTimeout
      private val handshakeTimeout = config.handshakeTimeout
      
      // Pending requests: requestId -> Deferred[Response]
      private val pendingRequests = Ref.unsafe[IO, Map[ByteVector, Deferred[IO, Payload]]](Map.empty)
      
      // Pending handshakes: nonce -> Deferred[HandshakeComplete]
      private val pendingHandshakes = Ref.unsafe[IO, Map[ByteVector, Deferred[IO, Session.ActiveSession]]](Map.empty)
      
      /** Send a PING and wait for PONG */
      override def ping(peer: Peer[A], localEnrSeq: Long): IO[Option[PingResult]] = {
        val requestId = Payload.randomRequestId
        val ping = Ping(requestId, localEnrSeq)
        
        for {
          deferred <- Deferred[IO, Payload]
          _ <- pendingRequests.update(_ + (requestId -> deferred))
          _ <- sendMessage(peer, ping)
          result <- temporal.timeout(deferred.get, requestTimeout).attempt
          _ <- pendingRequests.update(_ - requestId)
        } yield result.toOption.collect {
          case Pong(_, enrSeq, recipientIP, recipientPort) =>
            PingResult(enrSeq, recipientIP, recipientPort)
        }
      }
      
      /** Send FINDNODE and collect NODES responses */
      override def findNode(peer: Peer[A], distances: List[Int]): IO[Option[List[EthereumNodeRecord]]] = {
        val requestId = Payload.randomRequestId
        val findNode = FindNode(requestId, distances)
        
        for {
          deferred <- Deferred[IO, Payload]
          _ <- pendingRequests.update(_ + (requestId -> deferred))
          _ <- sendMessage(peer, findNode)
          result <- temporal.timeout(deferred.get, config.findNodeTimeout).attempt
          _ <- pendingRequests.update(_ - requestId)
        } yield result.toOption.collect {
          case Nodes(_, _, enrs) => enrs
        }
      }
      
      /** Send TALKREQ and wait for TALKRESP */
      override def talkRequest(
        peer: Peer[A], 
        protocol: ByteVector, 
        request: ByteVector
      ): IO[Option[ByteVector]] = {
        val requestId = Payload.randomRequestId
        val talkReq = TalkRequest(requestId, protocol, request)
        
        for {
          deferred <- Deferred[IO, Payload]
          _ <- pendingRequests.update(_ + (requestId -> deferred))
          _ <- sendMessage(peer, talkReq)
          result <- temporal.timeout(deferred.get, requestTimeout).attempt
          _ <- pendingRequests.update(_ - requestId)
        } yield result.toOption.collect {
          case TalkResponse(_, response) => response
        }
      }
      
      /** Register topic (requires full topic discovery implementation)
        * 
        * Topic discovery is an optional feature in Discovery v5 that allows
        * nodes to advertise interest in specific topics and discover other nodes
        * with matching interests.
        * 
        * Full implementation requires:
        * 1. Topic table to store topic registrations
        * 2. Ticket-based registration system to prevent spam
        * 3. Topic query routing and response aggregation
        * 4. Integration with main node module for RLP encoding of ENRs
        * 
        * This is currently implemented as a stub returning None since it's
        * an optional feature and requires significant additional infrastructure.
        * 
        * For production use, implement topic advertisement following:
        * https://github.com/ethereum/devp2p/blob/master/discv5/discv5-theory.md#topic-advertisement
        */
      override def regTopic(
        peer: Peer[A], 
        topic: ByteVector, 
        enr: EthereumNodeRecord, 
        ticket: ByteVector
      ): IO[Option[RegTopicResult]] = {
        logger.debug(s"Topic registration requested for topic ${topic.toHex.take(16)}... (not yet implemented)")
        // TODO: Implement topic table and ticket validation
        IO.pure(None)
      }
      
      /** Query topic (requires full topic discovery implementation)
        * 
        * Queries the network for nodes that have registered interest in a topic.
        * 
        * Full implementation requires:
        * 1. Topic routing table to find nodes advertising topics
        * 2. Query aggregation across multiple peers
        * 3. ENR validation and filtering
        * 4. Integration with main node module for RLP encoding
        * 
        * This is currently implemented as a stub returning None since it's
        * an optional feature and requires significant additional infrastructure.
        * 
        * For production use, implement topic queries following:
        * https://github.com/ethereum/devp2p/blob/master/discv5/discv5-theory.md#topic-query
        */
      override def topicQuery(peer: Peer[A], topic: ByteVector): IO[Option[List[EthereumNodeRecord]]] = {
        logger.debug(s"Topic query requested for topic ${topic.toHex.take(16)}... (not yet implemented)")
        // TODO: Implement topic query routing and aggregation
        IO.pure(None)
      }
      
      /** Send a message to a peer (handles encryption and session management) */
      private def sendMessage(peer: Peer[A], payload: Payload): IO[Unit] = {
        for {
          sessionOpt <- sessionCache.get(Session.nodeIdFromPublicKey(peer.id.value.bytes))
          _ <- sessionOpt match {
            case Some(session) =>
              // Have active session, encrypt and send
              sendEncryptedMessage(peer, payload, session)
            case None =>
              // No session, initiate handshake
              initiateHandshake(peer, payload)
          }
        } yield ()
      }
      
      /** Send encrypted message using active session
        * 
        * Implements the encrypted message flow:
        * 1. Encode payload using RLP/scodec codec
        * 2. Encrypt with AES-GCM using session keys  
        * 3. Create OrdinaryMessagePacket with encrypted data
        * 4. Send via UDP peer group
        */
      private def sendEncryptedMessage(
        peer: Peer[A], 
        payload: Payload, 
        session: Session.ActiveSession
      ): IO[Unit] = {
        import codecs.RLPCodecs.payloadCodec
        
        logger.debug(s"Sending encrypted ${payload.getClass.getSimpleName} to $peer")
        
        for {
          // 1. Encode payload
          encoded <- IO.fromEither(
            payloadCodec.encode(payload).toEither
              .left.map(err => new PacketException(s"Failed to encode payload: ${err.message}"))
          )
          
          // 2. Generate random nonce
          nonce = Packet.randomNonce
          
          // 3. Select encryption key based on session role
          encryptionKey = if (session.isInitiator) session.keys.initiatorKey else session.keys.recipientKey
          
          // 4. Build auth-data (source node ID)
          authData = session.localNodeId
          
          // 5. Encrypt payload
          ciphertext <- IO.fromTry(
            Session.encrypt(encryptionKey, nonce, encoded.bytes, authData)
          )
          
          // 6. Build OrdinaryMessagePacket
          packet = Packet.OrdinaryMessagePacket(
            nonce = nonce,
            authDataSize = authData.size.toInt,
            authData = authData,
            messageCipherText = ciphertext
          )
          
          // 7. Send via peer group (create channel and send)
          _ <- peerGroup.client(peer.address).use { channel =>
            channel.sendMessage(packet)
          }
          _ = logger.debug(s"Sent encrypted message to $peer")
        } yield ()
      }
      
      /** Initiate handshake with peer  
        *
        * Implements the handshake initiation flow:
        * 1. Send initial random packet to trigger WHOAREYOU
        * 2. Wait for WHOAREYOU response
        * 3. Perform ECDH key exchange
        * 4. Derive session keys
        * 5. Send HandshakeMessage
        * 6. Store session and retry original message
        */
      private def initiateHandshake(peer: Peer[A], payload: Payload): IO[Unit] = {
        logger.debug(s"Initiating handshake with $peer for ${payload.getClass.getSimpleName}")
        
        for {
          // 1. Create deferred for handshake completion
          handshakeDeferred <- Deferred[IO, Session.ActiveSession]
          
          // 2. Generate initial nonce that will be echoed by WHOAREYOU
          initialNonce = Packet.randomNonce
          
          // 3. Register pending handshake keyed by nonce for correlation
          _ <- pendingHandshakes.update(_ + (initialNonce -> handshakeDeferred))
          
          // 4. Send random packet (initial contact) to trigger WHOAREYOU
          randomAuthData = Packet.randomNonce // Random auth data
          initialPacket = Packet.OrdinaryMessagePacket(
            nonce = initialNonce,
            authDataSize = randomAuthData.size.toInt,
            authData = randomAuthData,
            messageCipherText = ByteVector.empty
          )
          
          _ <- peerGroup.client(peer.address).use { channel =>
            channel.sendMessage(initialPacket)
          }
          _ = logger.debug(s"Sent initial handshake packet to $peer with nonce ${initialNonce.toHex.take(16)}")
          
          // 5. Wait for handshake completion (WHOAREYOU will be processed by handleWhoAreYou)
          session <- temporal.timeout(handshakeDeferred.get, config.handshakeTimeout)
            .handleErrorWith { _ =>
              // Cleanup on timeout
              pendingHandshakes.update(_ - initialNonce) >>
              IO.raiseError(new PacketException(s"Handshake timeout with $peer"))
            }
          
          // 6. Cleanup
          _ <- pendingHandshakes.update(_ - initialNonce)
          
          // 7. Retry original message with encryption
          _ <- sendEncryptedMessage(peer, payload, session)
          _ = logger.debug(s"Handshake complete with $peer, retried original message")
        } yield ()
      }
      
      /** Handle WHOAREYOU challenge packet
        * 
        * Completes handshake by:
        * 1. Generating ephemeral key pair
        * 2. Performing ECDH
        * 3. Deriving session keys
        * 4. Sending HandshakeMessage
        * 5. Storing session in cache
        */
      private def handleWhoAreYou(peer: Peer[A], packet: Packet.WhoAreYouPacket): IO[Unit] = {
        import java.security.SecureRandom
        import org.bouncycastle.asn1.sec.SECNamedCurves
        
        logger.debug(s"Received WHOAREYOU from $peer with nonce ${packet.nonce.toHex.take(16)}")
        
        for {
          // 1. Look up pending handshake by nonce
          handshakeDeferredOpt <- pendingHandshakes.get.map(_.get(packet.nonce))
          
          _ <- handshakeDeferredOpt match {
            case None =>
              logger.warn(s"Received WHOAREYOU from $peer but no pending handshake found for nonce ${packet.nonce.toHex.take(16)}")
              IO.unit
              
            case Some(handshakeDeferred) =>
              for {
                // 2. Generate ephemeral key pair using proper EC cryptography
                ephemeralPrivateKey <- IO {
                  val random = new SecureRandom()
                  val privKeyBytes = Array.ofDim[Byte](32)
                  random.nextBytes(privKeyBytes)
                  ByteVector.view(privKeyBytes)
                }
                
                // 3. Derive ephemeral public key from private key using secp256k1
                ephemPubkey <- IO {
                  val curveParams = SECNamedCurves.getByName("secp256k1")
                  val privKeyBigInt = BigInt(1, ephemeralPrivateKey.toArray)
                  val pubKeyPoint = curveParams.getG.multiply(privKeyBigInt.bigInteger).normalize()
                  
                  // Get X and Y coordinates as 32-byte arrays
                  val x = pubKeyPoint.getAffineXCoord.toBigInteger.toByteArray
                  val y = pubKeyPoint.getAffineYCoord.toBigInteger.toByteArray
                  
                  // Ensure exactly 32 bytes each (pad or trim)
                  def normalize32(bytes: Array[Byte]): Array[Byte] = {
                    if (bytes.length < 32) {
                      Array.fill(32 - bytes.length)(0.toByte) ++ bytes
                    } else if (bytes.length > 32) {
                      bytes.takeRight(32)
                    } else {
                      bytes
                    }
                  }
                  
                  ByteVector.view(normalize32(x) ++ normalize32(y))
                }
                
                // 4. Get peer's public key (from peer ID)
                peerPublicKey = peer.id.value.bytes
                
                // 5. Perform ECDH
                sharedSecret = Session.performECDH(ephemeralPrivateKey, peerPublicKey)
                
                // 6. Derive session keys
                localNodeId = Session.nodeIdFromPublicKey(sigalg.toPublicKey(privateKey).value.bytes)
                remoteNodeId = Session.nodeIdFromPublicKey(peerPublicKey)
                idNonce = packet.whoAreYouData.idNonce
                keys = Session.deriveKeys(sharedSecret, localNodeId, remoteNodeId, idNonce)
                
                // 7. Create active session (we are the initiator)
                session = Session.ActiveSession(keys, localNodeId, remoteNodeId, isInitiator = true)
                
                // 8. Store in cache
                _ <- sessionCache.put(remoteNodeId, session)
                
                // 9. Create signature over id-nonce using the node's private key
                idSignature <- IO {
                  ByteVector.view(sigalg.sign(privateKey, idNonce.bits).value.toByteArray)
                }
                
                // 10. Build handshake auth data
                handshakeAuthData = Packet.HandshakeAuthData(
                  srcId = localNodeId,
                  sigSize = idSignature.size.toInt,
                  ephemPubkey = ephemPubkey,
                  idSignature = idSignature
                )
                
                // 11. Calculate auth data size
                authDataSize = 32 + 1 + 64 + idSignature.size.toInt
                
                // 12. Build handshake packet with empty message (handshake completion)
                handshakePacket = Packet.HandshakeMessagePacket(
                  nonce = Packet.randomNonce,
                  authDataSize = authDataSize,
                  handshakeAuthData = handshakeAuthData,
                  messageCipherText = ByteVector.empty
                )
                
                _ <- peerGroup.client(peer.address).use { channel =>
                  channel.sendMessage(handshakePacket)
                }
                _ = logger.debug(s"Sent handshake completion to $peer")
                
                // 13. Complete the specific pending handshake
                _ <- handshakeDeferred.complete(session).void
              } yield ()
          }
        } yield ()
      }
      
      /** Handle ordinary encrypted message packet */
      private def handleOrdinaryMessage(peer: Peer[A], packet: Packet.OrdinaryMessagePacket): IO[Unit] = {
        import codecs.RLPCodecs.payloadCodec
        
        logger.debug(s"Received ordinary message from $peer")
        
        for {
          // 1. Get session from cache
          remoteNodeId <- IO.pure(Session.nodeIdFromPublicKey(peer.id.value.bytes))
          sessionOpt <- sessionCache.get(remoteNodeId)
          
          // 2. If no session, send WHOAREYOU challenge
          _ <- sessionOpt match {
            case None =>
              logger.debug(s"No session for $peer, sending WHOAREYOU")
              sendWhoAreYou(peer, packet.nonce)
              
            case Some(session) =>
              // 3. Decrypt message using session role
              for {
                // Select decryption key based on session role (inverse of encryption)
                decryptionKey <- IO.pure(if (session.isInitiator) session.keys.initiatorKey else session.keys.recipientKey)
                
                plaintext <- IO.fromTry(
                  Session.decrypt(decryptionKey, packet.nonce, packet.messageCipherText, packet.authData)
                )
                
                // 4. Decode payload
                payload <- IO.fromEither(
                  payloadCodec.decode(plaintext.bits).toEither
                    .left.map(err => new PacketException(s"Failed to decode payload: ${err.message}"))
                    .map(_.value)
                )
                
                // 5. Handle payload
                _ <- handlePayload(peer, payload)
              } yield ()
          }
        } yield ()
      }
      
      /** Handle handshake message packet */
      private def handleHandshakeMessage(peer: Peer[A], packet: Packet.HandshakeMessagePacket): IO[Unit] = {
        logger.debug(s"Received handshake message from $peer")
        
        // Handshake messages are sent by the initiator in response to WHOAREYOU
        // We need to verify the signature and establish the session as recipient
        for {
          // 1. Extract source node ID from handshake auth data
          srcNodeId <- IO.pure(packet.handshakeAuthData.srcId)
          
          // 2. Verify signature over id-nonce (would need the original idNonce we sent)
          // For now, we'll accept it - in production, verify signature
          
          // 3. The ephemeral public key is in the packet
          ephemPubkey <- IO.pure(packet.handshakeAuthData.ephemPubkey)
          
          // 4. Perform ECDH with our node's private key
          sharedSecret <- IO.pure(Session.performECDH(privateKey.value.bytes, ephemPubkey))
          
          // 5. Derive session keys (would need the original idNonce we sent in WHOAREYOU)
          // For now, we'll skip session creation - in production, store idNonce and retrieve it
          _ = logger.debug(s"Handshake message received but session creation skipped (need idNonce tracking)")
        } yield ()
      }
      
      /** Send WHOAREYOU challenge packet */
      private def sendWhoAreYou(peer: Peer[A], triggeringNonce: ByteVector): IO[Unit] = {
        logger.debug(s"Sending WHOAREYOU to $peer echoing nonce ${triggeringNonce.toHex.take(16)}")
        
        for {
          // 1. Create WHOAREYOU data
          idNonce <- IO.pure(Session.randomIdNonce)
          enrSeq <- IO.pure(localEnrSeq)
          whoAreYouData <- IO.pure(Packet.WhoAreYouData(idNonce, enrSeq))
          
          // 2. Create WHOAREYOU packet - echo the nonce from the triggering packet
          whoAreYouPacket <- IO.pure(Packet.WhoAreYouPacket(
            nonce = triggeringNonce,  // Echo the original packet's nonce
            authDataSize = 24, // 16 bytes idNonce + 8 bytes enrSeq
            whoAreYouData = whoAreYouData
          ))
          
          // 3. Send
          _ <- peerGroup.client(peer.address).use { channel =>
            channel.sendMessage(whoAreYouPacket)
          }
        } yield ()
      }
      
      /** Handle decoded payload and route to appropriate handler */
      private def handlePayload(peer: Peer[A], payload: Payload): IO[Unit] = {
        payload match {
          case pong: Pong =>
            // Complete pending request
            pendingRequests.get.flatMap { requests =>
              requests.get(pong.requestId) match {
                case Some(deferred) => deferred.complete(pong).void
                case None => IO.unit
              }
            }
            
          case nodes: Nodes =>
            // Complete pending request
            pendingRequests.get.flatMap { requests =>
              requests.get(nodes.requestId) match {
                case Some(deferred) => deferred.complete(nodes).void
                case None => IO.unit
              }
            }
            
          case talkResp: TalkResponse =>
            // Complete pending request
            pendingRequests.get.flatMap { requests =>
              requests.get(talkResp.requestId) match {
                case Some(deferred) => deferred.complete(talkResp).void
                case None => IO.unit
              }
            }
            
          case ticket: Ticket =>
            // Complete pending request
            pendingRequests.get.flatMap { requests =>
              requests.get(ticket.requestId) match {
                case Some(deferred) => deferred.complete(ticket).void
                case None => IO.unit
              }
            }
            
          case regConf: RegConfirmation =>
            // Complete pending request
            pendingRequests.get.flatMap { requests =>
              requests.get(regConf.requestId) match {
                case Some(deferred) => deferred.complete(regConf).void
                case None => IO.unit
              }
            }
            
          case ping: Ping =>
            // Respond with PONG
            logger.debug(s"Received PING from $peer, sending PONG")
            val recipientAddr = Addressable[A].getAddress(peer.address)
            val pong = Pong(
              requestId = ping.requestId,
              enrSeq = localEnrSeq,
              recipientIP = ByteVector(recipientAddr.getAddress.getAddress),
              recipientPort = recipientAddr.getPort
            )
            sendMessage(peer, pong)
            
          case findNode: FindNode =>
            // Respond with NODES (empty for now - would lookup nodes at requested distances)
            logger.debug(s"Received FINDNODE from $peer for distances ${findNode.distances}")
            val enrsToReturn = List.empty[EthereumNodeRecord] // TODO: Replace with actual ENR lookup logic
            
            if (enrsToReturn.nonEmpty) {
              val nodes = Nodes(
                requestId = findNode.requestId,
                total = 1,
                enrs = enrsToReturn
              )
              sendMessage(peer, nodes)
            } else {
              // No nodes to return - send empty response with total=0 or don't respond
              logger.debug(s"No ENRs found for FINDNODE from $peer; sending empty NODES response")
              val nodes = Nodes(
                requestId = findNode.requestId,
                total = 1,
                enrs = List.empty
              )
              sendMessage(peer, nodes)
            }
            
          case talkReq: TalkRequest =>
            // Respond with empty TALKRESP
            logger.debug(s"Received TALKREQ from $peer")
            val talkResp = TalkResponse(
              requestId = talkReq.requestId,
              response = ByteVector.empty
            )
            sendMessage(peer, talkResp)
            
          case topicQuery: TopicQuery =>
            // Respond with NODES (topic queries not implemented)
            logger.debug(s"Received TOPICQUERY from $peer (not implemented)")
            IO.unit
            
          case regTopic: RegTopic =>
            // Respond with TICKET (topic registration not implemented)
            logger.debug(s"Received REGTOPIC from $peer (not implemented)")
            IO.unit
        }
      }
      
      /** Process incoming packet and dispatch to appropriate handler */
      private def processIncomingPacket(peer: Peer[A], packetBits: BitVector): IO[Unit] = {
        for {
          // 1. Decode packet
          decodeResult <- IO.fromEither(
            Packet.decodePacket(packetBits).toEither
              .left.map(err => new PacketException(s"Failed to decode packet: ${err.message}"))
          )
          
          packet = decodeResult.value
          
          // 2. Dispatch based on packet type
          _ <- packet match {
            case ord: Packet.OrdinaryMessagePacket =>
              handleOrdinaryMessage(peer, ord)
              
            case way: Packet.WhoAreYouPacket =>
              handleWhoAreYou(peer, way)
              
            case hs: Packet.HandshakeMessagePacket =>
              handleHandshakeMessage(peer, hs)
          }
        } yield ()
      }.handleErrorWith { error =>
        logger.error(s"Error processing packet from $peer: ${error.getMessage}", error)
        IO.unit
      }
      
      /** Start handling incoming packets */
      override def startHandling(handler: DiscoveryRPC[Peer[A]]): IO[Deferred[IO, Unit]] = {
        import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
        import com.chipprbots.scalanet.peergroup.Channel.MessageReceived
        
        for {
          cancelToken <- Deferred[IO, Unit]
          
          // Track channel fibers for cleanup
          channelFibers <- Ref[IO].of(List.empty[cats.effect.Fiber[IO, Throwable, Unit]])
          
          _ <- Stream.repeatEval(peerGroup.nextServerEvent)
            .interruptWhen(cancelToken.get.attempt)
            .collect {
              case Some(ChannelCreated(channel, release)) => (channel, release)
            }
            .evalMap { case (channel, release) =>
              // Handle this channel in the background
              val channelStream = Stream.repeatEval(channel.nextChannelEvent)
                .collect {
                  case Some(MessageReceived(packet: Packet)) => packet
                }
                .evalMap { packet =>
                  // Extract peer info from channel
                  val peerAddress = channel.from
                  
                  // Extract node ID from packet based on type
                  val peer = packet match {
                    case hs: Packet.HandshakeMessagePacket =>
                      // Extract node ID from handshake auth data
                      val nodeId: Node.Id = PublicKey(BitVector(hs.handshakeAuthData.srcId.toArray))
                      Peer(nodeId, peerAddress)
                      
                    case ord: Packet.OrdinaryMessagePacket =>
                      // Try to extract from auth data (should be srcId) or session cache
                      if (ord.authData.size == 32) {
                        val nodeId: Node.Id = PublicKey(BitVector(ord.authData.toArray ++ ord.authData.toArray))
                        Peer(nodeId, peerAddress)
                      } else {
                        // Fallback: use placeholder - session lookup will handle it
                        val placeholderNodeId: Node.Id = PublicKey(BitVector(Array.fill(64)(0.toByte)))
                        Peer(placeholderNodeId, peerAddress)
                      }
                      
                    case _: Packet.WhoAreYouPacket =>
                      // WHOAREYOU doesn't contain node ID, use placeholder
                      // The peer should be known from the session we're establishing
                      val placeholderNodeId: Node.Id = PublicKey(BitVector(Array.fill(64)(0.toByte)))
                      Peer(placeholderNodeId, peerAddress)
                  }
                  
                  // Process the packet
                  packet match {
                    case ord: Packet.OrdinaryMessagePacket =>
                      handleOrdinaryMessage(peer, ord)
                      
                    case way: Packet.WhoAreYouPacket =>
                      handleWhoAreYou(peer, way)
                      
                    case hs: Packet.HandshakeMessagePacket =>
                      handleHandshakeMessage(peer, hs)
                  }
                }
                .handleErrorWith { error =>
                  Stream.eval(IO(logger.error(s"Error in channel handler: ${error.getMessage}", error)))
                }
                .onFinalize(release)
                .compile
                .drain
              
              // Start the channel handler and track the fiber
              for {
                fiber <- channelStream.start
                _ <- channelFibers.update(fiber :: _)
              } yield ()
            }
            .onFinalize {
              // Cancel all channel fibers when the main stream is interrupted
              for {
                fibers <- channelFibers.get
                _ <- fibers.traverse_(_.cancel)
              } yield ()
            }
            .compile
            .drain
            .start
        } yield cancelToken
      }
    }
  }
}
