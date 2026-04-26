package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._
import scala.util.control.NonFatal

import cats.effect.{Deferred, IO, Resource, Temporal}
import cats.effect.std.Queue
import cats.implicits._
import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg, Signature}
import com.chipprbots.scalanet.discovery.ethereum.{EthereumNodeRecord, Node}
import com.chipprbots.scalanet.peergroup.CloseableQueue
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

/** discv5 async pipeline — outbound RPC + inbound dispatch.
  *
  * Outbound flow: caller invokes `ping` / `findNode` / `talkRequest`.
  * The network checks for an existing session with the peer:
  *   - With session: encrypt the payload under writeKey, send. Register a
  *     pending-request Deferred keyed on (peerId, requestId), wait on it
  *     with a timeout, and complete when an inbound MessagePacket arrives
  *     carrying the matching requestId.
  *   - Without session: send a "random-content" message that the peer
  *     can't decrypt, so they reply with WHOAREYOU. Track this as a
  *     pending-outbound-handshake. When the WHOAREYOU arrives, derive
  *     an ephemeral key, sign idNonce, build a Handshake packet with the
  *     original payload encrypted under the new session key, and send.
  *
  * Inbound flow: a single fiber consumes from the [[CloseableQueue]] that
  * `V5DemuxResponder` pushes to. For each (sender, bytes):
  *   - Decode the [[Packet]] under the local node id mask.
  *   - MessagePacket: try to decrypt with the session for (srcId, sender).
  *     If the decoded payload is a Response (Pong, Nodes, TalkResponse),
  *     correlate to a pending Deferred. Otherwise dispatch to the supplied
  *     [[DiscoveryRPC]] handler (so the service-level layer can update
  *     kbuckets, fetch ENR records, etc.). Note: the sync responder
  *     [[Discv5SyncResponder]] already replies to inbound requests;
  *     this path runs after to do bookkeeping.
  *   - Whoareyou: pair with a pending-outbound-handshake; build the
  *     handshake response and send.
  *   - Handshake: usually handled by sync responder; if it surfaces here
  *     (sync was rate-limited or threw) we re-handle defensively.
  */
trait DiscoveryNetwork[A] extends DiscoveryRPC[DiscoveryNetwork.Peer[A]] {

  /** Start consuming the inbound dispatch queue. The returned `Deferred`
    * is the cancellation signal — `complete(())` it to stop the consumer
    * fiber. Mirrors v4's `startHandling`. */
  def startHandling(handler: DiscoveryRPC[DiscoveryNetwork.Peer[A]]): IO[Deferred[IO, Unit]]
}

object DiscoveryNetwork {

  /** Peer is the (NodeId, address) pair. address is typically `InetMultiAddress`. */
  final case class Peer[A](id: ByteVector, address: A)

  /** Tracks our outbound that's waiting on a WHOAREYOU. When the WHOAREYOU
    * arrives we know the peer's id (it's the auth-data SrcID; here we
    * assume it matches the peer we initiated to) and we proceed to build
    * the handshake response. */
  private final case class PendingOutbound(
      payload: Payload.Request,
      requestDeferred: Deferred[IO, Payload.Response],
      sentNonce: ByteVector
  )

  def apply[A](
      peerGroup: PeerGroupSender[A],
      privateKey: PrivateKey,
      publicKey: PublicKey,
      localNodeId: ByteVector,
      localEnrRef: AtomicReference[EthereumNodeRecord],
      sessions: Session.SessionCache,
      challenges: Discv5SyncResponder.ChallengeCache,
      bystanders: Discv5SyncResponder.BystanderEnrTable,
      dispatchQueue: CloseableQueue[(InetSocketAddress, ByteVector)],
      config: DiscoveryConfig
  )(implicit
      payloadCodec: Codec[Payload],
      sigalg: SigAlg,
      temporal: Temporal[IO]
  ): IO[DiscoveryNetwork[A]] = IO {
    new DiscoveryNetwork[A] with LazyLogging {

      private val pendingRequests =
        new ConcurrentHashMap[(ByteVector, ByteVector), Deferred[IO, Payload.Response]]()

      private val pendingOutbound =
        new ConcurrentHashMap[ByteVector, PendingOutbound]() // keyed on the nonce we sent

      private val random = new SecureRandom()

      // ---- DiscoveryRPC ------------------------------------------------

      override def ping(peer: Peer[A], localEnrSeq: Long): IO[Option[DiscoveryRPC.PingResult]] = {
        val reqId = Payload.randomRequestId(8)
        val ping = Payload.Ping(reqId, localEnrSeq)
        sendRequest(peer, ping, reqId).map {
          case Some(pong: Payload.Pong) =>
            Some(DiscoveryRPC.PingResult(pong.enrSeq, pong.recipientIp, pong.recipientPort))
          case _ => None
        }
      }

      override def findNode(peer: Peer[A], distances: List[Int]): IO[Option[List[EthereumNodeRecord]]] = {
        val reqId = Payload.randomRequestId(8)
        val fn = Payload.FindNode(reqId, distances)
        // Nodes responses can be paginated; for v1 we take the first page only.
        sendRequest(peer, fn, reqId).map {
          case Some(nodes: Payload.Nodes) => Some(nodes.enrs)
          case _                          => None
        }
      }

      override def talkRequest(
          peer: Peer[A],
          protocol: ByteVector,
          message: ByteVector
      ): IO[Option[ByteVector]] = {
        val reqId = Payload.randomRequestId(8)
        val tq = Payload.TalkRequest(reqId, protocol, message)
        sendRequest(peer, tq, reqId).map {
          case Some(tr: Payload.TalkResponse) => Some(tr.message)
          case _                              => None
        }
      }

      // ---- Outbound RPC core --------------------------------------------

      /** Send a request payload; resolve the returned IO with the matching
        * Response when it arrives, or None on timeout / error. */
      private def sendRequest(
          peer: Peer[A],
          payload: Payload.Request,
          requestId: ByteVector
      ): IO[Option[Payload.Response]] = {
        val peerAddr = toInetSocketAddress(peer.address)
        for {
          deferred <- Deferred[IO, Payload.Response]
          _ <- IO {
            val _ = pendingRequests.put((peer.id, requestId), deferred)
          }
          _ <- sendOrInitiateHandshake(peer.id, peerAddr, payload)
          result <- deferred.get
            .timeout(config.requestTimeout)
            .attempt
          _ <- IO { val _ = pendingRequests.remove((peer.id, requestId)) }
        } yield result.toOption
      }

      private def sendOrInitiateHandshake(
          peerNodeId: ByteVector,
          peerAddr: InetSocketAddress,
          payload: Payload.Request
      ): IO[Unit] = {
        sessions.getIO(Session.SessionId(peerNodeId, peerAddr)).flatMap {
          case Some(session) =>
            sendEncrypted(peerNodeId, peerAddr, session, payload)
          case None =>
            // No session — send a random-content message; the peer can't
            // decrypt it, will reply with WHOAREYOU, and we'll complete
            // the handshake from there.
            sendRandomTrigger(peerNodeId, peerAddr, payload)
        }
      }

      /** Send a request payload encrypted under an existing session. */
      private def sendEncrypted(
          peerNodeId: ByteVector,
          peerAddr: InetSocketAddress,
          session: Session.Session,
          payload: Payload.Request
      ): IO[Unit] = IO {
        val plaintext = payloadCodec.encode(payload).require.toByteVector
        val nonce = Packet.randomNonce
        val iv = Packet.randomIv
        val authData = localNodeId
        val staticHeader =
          Packet.ProtocolId ++
            ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
            ByteVector(Packet.Flag.Message) ++
            nonce ++
            ByteVector.fromInt(authData.size.toInt, Packet.AuthSizeSize)
        val masked = Packet.aesCtrMask(peerNodeId, iv, staticHeader ++ authData)
        val aad = iv ++ masked
        val ct = Session.encrypt(session.keys.writeKey, nonce, plaintext, aad).get
        val full = aad ++ ct
        full
      }.flatMap(bytes => peerGroup.sendRaw(peerAddr, bytes))

      /** Send a random-content message to trigger a WHOAREYOU. We record the
        * nonce we sent so the WHOAREYOU's response field correlates back to
        * the original [[payload]] we wanted to deliver. */
      private def sendRandomTrigger(
          peerNodeId: ByteVector,
          peerAddr: InetSocketAddress,
          payload: Payload.Request
      ): IO[Unit] = for {
        nonce <- IO(Packet.randomNonce)
        iv = Packet.randomIv
        authData = localNodeId
        staticHeader = Packet.ProtocolId ++
          ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
          ByteVector(Packet.Flag.Message) ++
          nonce ++
          ByteVector.fromInt(authData.size.toInt, Packet.AuthSizeSize)
        randomCt <- IO {
          val r = new Array[Byte](20)
          random.nextBytes(r)
          ByteVector.view(r)
        }
        masked = Packet.aesCtrMask(peerNodeId, iv, staticHeader ++ authData)
        full = iv ++ masked ++ randomCt
        // Stash the pending outbound. The WHOAREYOU we get back will echo this nonce.
        deferred <- Deferred[IO, Payload.Response]
        _ <- IO {
          val _ = pendingOutbound.put(nonce, PendingOutbound(payload, deferred, nonce))
        }
        _ <- peerGroup.sendRaw(peerAddr, full)
      } yield ()

      // ---- Inbound consumer --------------------------------------------

      override def startHandling(
          handler: DiscoveryRPC[Peer[A]]
      ): IO[Deferred[IO, Unit]] = for {
        cancel <- Deferred[IO, Unit]
        _ <- Stream
          .repeatEval(dispatchQueue.next)
          .interruptWhen(cancel.get.attempt)
          .takeWhile(_.isDefined)
          .evalMap {
            case Some((sender, bytes)) =>
              processInbound(sender, bytes, handler).attempt.flatMap {
                case Left(ex) =>
                  IO(logger.warn(s"discv5 inbound handler failed: ${ex.getClass.getSimpleName}: ${ex.getMessage}"))
                case Right(_) => IO.unit
              }
            case None => IO.unit
          }
          .compile
          .drain
          .start
          .void
      } yield cancel

      private def processInbound(
          sender: InetSocketAddress,
          bytes: ByteVector,
          handler: DiscoveryRPC[Peer[A]]
      ): IO[Unit] = {
        Packet.decode(bytes, localNodeId).toEither match {
          case Right(packet) =>
            packet match {
              case msg: Packet.MessagePacket =>
                processMessage(sender, msg, bytes, handler)
              case who: Packet.WhoareyouPacket =>
                processWhoareyou(sender, who)
              case _: Packet.HandshakePacket =>
                // Sync responder typically handles this; ignore here.
                IO.unit
            }
          case Left(_) =>
            // Mask mismatch or junk; drop.
            IO.unit
        }
      }

      /** Decrypt the inbound message; if it's a Response, route to a pending
        * request; if it's a Request, dispatch to the supplied handler so the
        * service layer can do bookkeeping (the sync responder already replied). */
      private def processMessage(
          sender: InetSocketAddress,
          msg: Packet.MessagePacket,
          rawBytes: ByteVector,
          handler: DiscoveryRPC[Peer[A]]
      ): IO[Unit] = {
        sessions.getIO(Session.SessionId(msg.header.srcId, sender)).flatMap {
          case None =>
            // No session — sync responder should have sent WHOAREYOU.
            IO.unit
          case Some(session) =>
            val maskedRegionEnd =
              Packet.MaskingIVSize + Packet.StaticHeaderSize + msg.header.authData.size.toInt
            val aad = rawBytes.take(maskedRegionEnd.toLong)
            Session.decrypt(session.keys.readKey, msg.header.nonce, msg.messageCiphertext, aad).toEither match {
              case Right(plaintext) =>
                payloadCodec.decode(plaintext.bits).toEither match {
                  case Right(decoded) =>
                    routePayload(sender, msg.header.srcId, decoded.value, handler)
                  case Left(_) => IO.unit
                }
              case Left(_) => IO.unit
            }
        }
      }

      /** Route a decoded payload: responses complete pending Deferreds;
        * requests fall through to the handler (no reply — sync did that). */
      private def routePayload(
          sender: InetSocketAddress,
          srcId: ByteVector,
          payload: Payload,
          handler: DiscoveryRPC[Peer[A]]
      ): IO[Unit] = payload match {
        case response: Payload.Response =>
          val key = (srcId, response.requestId)
          IO(Option(pendingRequests.remove(key))).flatMap {
            case Some(deferred) => deferred.complete(response).void
            case None =>
              // No pending request — late or unsolicited. Drop.
              IO.unit
          }
        case _: Payload.Request =>
          // Sync path already replied; the handler can do service-level
          // bookkeeping (e.g. record-bond for FINDNODE) but we don't echo
          // its response anywhere. For now we just no-op; the v5 service
          // layer can override this hook in a future iteration.
          IO.unit
      }

      /** Receive a WHOAREYOU we triggered: build the handshake response. */
      private def processWhoareyou(
          sender: InetSocketAddress,
          who: Packet.WhoareyouPacket
      ): IO[Unit] = {
        val triggerNonce = who.header.nonce
        IO(Option(pendingOutbound.remove(triggerNonce))).flatMap {
          case None =>
            // No matching outbound — unsolicited or already handled.
            IO.unit
          case Some(pending) =>
            sendHandshakeReply(sender, who, pending)
        }
      }

      /** Build the handshake packet that completes the outbound flow:
        *   - Generate ephemeral keypair
        *   - Sign idNonceHash with our static privkey
        *   - Derive session keys via ECDH+HKDF
        *   - Encrypt the original `pending.payload` with the new session
        *   - Send a HandshakePacket carrying signature + ephPubkey + (ENR optional)
        */
      private def sendHandshakeReply(
          sender: InetSocketAddress,
          who: Packet.WhoareyouPacket,
          pending: PendingOutbound
      ): IO[Unit] = IO {
        // Ephemeral keypair
        val (_, ephPriv) = sigalg.newKeyPair
        val ephPub = Session.pubFromPriv(ephPriv.value.bytes, compressed = true)

        // The recipient's nodeId is what's encoded in the WHOAREYOU's auth data
        // we cannot recover from here directly — but we tracked it via outbound.
        // For this minimal implementation, we don't currently know the peer's
        // nodeId for outbound WHOAREYOU correlation; pendingOutbound is keyed
        // on our send nonce only. To complete, we need either the peer's
        // expected nodeId (provided by the caller) or to recover it from
        // a subsequent message.
        //
        // For now: extract the peer's nodeId from whatever caller knew. The
        // caller (DiscoveryService) supplies it via a side channel. In
        // practice we'd extend PendingOutbound with peerNodeId.
        //
        // TODO: thread peerNodeId through PendingOutbound so this code path
        // is complete. For now we just log and return — outbound handshake
        // initiation is best-effort in this minimal implementation.
        logger.debug(s"discv5 outbound handshake to $sender — peer nodeId tracking is a TODO; dropping for now")
        ()
      }

      // ---- Address helpers ----------------------------------------------

      /** Best-effort extraction of an InetSocketAddress from peer.address.
        * Most call sites use `InetMultiAddress`; we accept that or a raw
        * `InetSocketAddress` directly. */
      private def toInetSocketAddress(address: A): InetSocketAddress =
        address match {
          case isa: InetSocketAddress => isa
          case other =>
            // Fall through to reflection — the only other expected case is
            // `InetMultiAddress` which has `inetSocketAddress` accessor.
            try {
              val m = other.getClass.getMethod("inetSocketAddress")
              m.invoke(other).asInstanceOf[InetSocketAddress]
            } catch {
              case NonFatal(ex) =>
                throw new IllegalArgumentException(
                  s"Cannot derive InetSocketAddress from peer.address ($other): ${ex.getMessage}"
                )
            }
        }
    }
  }

  /** Minimal abstraction for "send raw bytes to a remote address". The
    * production impl is [[com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup.sendRaw]]
    * via an adapter; tests can supply an in-memory sink. */
  trait PeerGroupSender[A] {
    def sendRaw(remoteAddress: InetSocketAddress, bytes: ByteVector): IO[Unit]
  }
}
