package com.chipprbots.scalanet.discovery.ethereum.v5

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

import scala.util.control.NonFatal

import com.chipprbots.scalanet.discovery.crypto.{PrivateKey, PublicKey, SigAlg, Signature}
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
import com.typesafe.scalalogging.LazyLogging
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

/** Synchronous discv5 fast-path — handles inbound discv5 packets on the
  * netty event-loop thread, mirroring the v4 [[com.chipprbots.scalanet.discovery.ethereum.v4.Discv4SyncResponder]]
  * pattern from PR #1092.
  *
  * Responsibilities (per-packet on the netty thread):
  *
  *   - Inbound `MessagePacket` with an existing session: decrypt, dispatch
  *     to the local handler, encrypt the response, write it back.
  *   - Inbound `MessagePacket` without a session: respond with WHOAREYOU
  *     and remember the challenge so we can verify the follow-up handshake.
  *   - Inbound `HandshakePacket`: verify the ID-nonce signature, derive
  *     session keys via ECDH+HKDF, store the session, decrypt the embedded
  *     message and respond.
  *   - Inbound `WhoareyouPacket`: not handled by the sync path — that's an
  *     unsolicited challenge meaning we should INITIATE a handshake with
  *     them, which lives in the async DiscoveryNetwork (Step 4). Returns
  *     `None`.
  *
  * Time budget: ECDSA verify (~3-5 ms) + ECDH (~3-5 ms) + AES-GCM
  * encrypt/decrypt (~50 µs) ≈ 10 ms total per handshake — well under hive's
  * 300 ms `waitTime` deadline.
  *
  * The responder MUST NOT throw — any exception is caught and turned into
  * `None` so netty's event-loop stays alive.
  */
object Discv5SyncResponder extends LazyLogging {

  // ---- Caches ------------------------------------------------------------

  /** In-flight WHOAREYOU challenges we sent. Keyed on `(srcId, peerAddr)` so
    * the follow-up handshake — whose own `header.nonce` is a fresh GCM nonce
    * unrelated to the trigger — is matched back to the right challenge.
    *
    * Two forms of the WHOAREYOU bytes are stored:
    *   - `challengeBytes`: the spec-defined `masking-iv || unmasked
    *     static-header || unmasked authdata`, used as the HKDF salt for
    *     session-key derivation and the input to the id-nonce signature
    *     hash. Both peers compute this from their own view of the WHOAREYOU
    *     after unmasking.
    *   - `wireBytes`: the encoded wire-form (with masked region) — used to
    *     re-send the same challenge if the peer retransmits the trigger
    *     packet (HandshakeResend semantics).
    *
    * `triggerNonce` is the inbound message's nonce the WHOAREYOU echoed
    * back; reused when the peer retries the same packet. */
  final case class PendingChallenge(
      idNonce: ByteVector,
      challengeBytes: ByteVector,
      wireBytes: ByteVector,
      triggerNonce: ByteVector,
      sentAtMillis: Long
  )

  class ChallengeCache(maxAgeMillis: Long = 30_000L) {
    private val map = new ConcurrentHashMap[Session.SessionId, PendingChallenge]()

    def get(key: Session.SessionId): Option[PendingChallenge] = Option(map.get(key))

    def put(key: Session.SessionId, ch: PendingChallenge): Unit = {
      if (map.size > 1024) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        map.entrySet.removeIf(e => e.getValue.sentAtMillis < cutoff)
      }
      val _ = map.put(key, ch)
    }

    def remove(key: Session.SessionId): Unit = { val _ = map.remove(key) }
  }

  /** ENRs we've seen in incoming handshakes. Used to satisfy hive's
    * `TestFindnodeResults` which expects `findNode([d])` to return ENRs for
    * peers that have previously bonded with us. */
  class BystanderEnrTable(maxAgeMillis: Long = 60L * 60 * 1000) {
    private val map = new ConcurrentHashMap[ByteVector, (EthereumNodeRecord, Long)]()

    def add(nodeId: ByteVector, enr: EthereumNodeRecord): Unit = {
      val now = System.currentTimeMillis()
      if (map.size > 4096) {
        val cutoff = now - maxAgeMillis
        map.entrySet.removeIf(e => e.getValue._2 < cutoff)
      }
      val _ = map.put(nodeId, (enr, now))
    }

    def all: List[EthereumNodeRecord] =
      map.values.iterator.asInstanceOf[java.util.Iterator[(EthereumNodeRecord, Long)]]
        .asScala
        .map(_._1)
        .toList

    /** ENRs at the given log-distance from our local node ID. */
    def atDistance(localNodeId: ByteVector, distance: Int): List[EthereumNodeRecord] = {
      val it = map.entrySet().iterator()
      val builder = List.newBuilder[EthereumNodeRecord]
      while (it.hasNext) {
        val entry = it.next()
        val nodeId = entry.getKey
        val (enr, _) = entry.getValue
        if (logDistance(localNodeId, nodeId) == distance) builder += enr
      }
      builder.result()
    }
  }

  /** Log-distance between two node IDs as defined in discv4/discv5: the
    * number of bits in the longer common prefix XOR. Returns 0 if equal,
    * 256 for maximally different IDs. */
  def logDistance(a: ByteVector, b: ByteVector): Int = {
    require(a.size == 32L && b.size == 32L, "node IDs must be 32 bytes")
    val xor = a.xor(b)
    var i = 0
    while (i < 32 && xor(i.toLong) == 0) i += 1
    if (i == 32) 0
    else {
      val byte = xor(i.toLong) & 0xff
      val bitsInByte = (Integer.SIZE - 1) - Integer.numberOfLeadingZeros(byte)
      256 - (i * 8) - (8 - bitsInByte - 1)
    }
  }

  // ---- Rate limiter ------------------------------------------------------

  /** Token-bucket limiter to bound netty-thread cost under flood. Same shape
    * as the v4 limiter; tuned for discv5's heavier per-packet ECDSA+ECDH
    * cost — defaults to 100 tokens/sec, burst 50. */
  class RateLimiter(tokensPerSecond: Int, maxBurst: Int) {
    require(tokensPerSecond > 0)
    require(maxBurst > 0)
    private val tokens = new AtomicInteger(maxBurst)
    private val lastRefillNanos = new AtomicLong(System.nanoTime())
    private val nanosPerToken: Long = 1_000_000_000L / tokensPerSecond.toLong

    def tryAcquire(): Boolean = {
      val now = System.nanoTime()
      val last = lastRefillNanos.get()
      val elapsed = now - last
      if (elapsed >= nanosPerToken) {
        val toAdd = (elapsed / nanosPerToken).toInt
        if (toAdd > 0 && lastRefillNanos.compareAndSet(last, now)) {
          tokens.updateAndGet(t => math.min(t + toAdd, maxBurst))
        }
      }
      var t = tokens.get()
      while (t > 0) {
        if (tokens.compareAndSet(t, t - 1)) return true
        t = tokens.get()
      }
      false
    }
  }

  private val DefaultTokensPerSecond = 100
  private val DefaultMaxBurst = 50

  /** Delay applied to the post-handshake ping-back so it doesn't race the
    * synchronous response back to the same peer. Hive's `Ping` and
    * `PingMultiIP` tests interrogate fukuii in tight ~300 ms reqresp cycles
    * and treat any unsolicited Ping interleaved with the response as a
    * protocol violation; `FindnodeResults` waits 60 seconds for the bond
    * signal so a 1-second delay is well within the budget. */
  private val PingBackDelayMillis: Long = 1000L

  // ---- Handler interface --------------------------------------------------

  /** Side-effect-free handler the responder calls into to fetch local state.
    * The implementations live in [[com.chipprbots.ethereum.network.discovery.DiscoveryServiceBuilder]]
    * which has the keys / ENR / kademlia-table view. Synchronous so the
    * netty thread doesn't go through cats-effect. */
  trait Handler {

    /** The current local ENR. Used as auth-data record on outbound handshakes
      * (when we initiate; not used in the responder) and as the response to
      * `findNode([0])`. */
    def localEnr: EthereumNodeRecord

    /** The current local ENR sequence number. */
    def localEnrSeq: Long

    /** ENRs at the given log-distances from our local node id, drawn from
      * the kademlia table. Returns an empty list if there are no buckets
      * yet (early-startup case is fine — hive's `FindnodeResults` populates
      * via prior PINGs and we look up via the bystander table). */
    def findNodes(distances: List[Int]): List[EthereumNodeRecord]
  }

  // ---- The responder factory ----------------------------------------------

  /** Side-channel sender for unsolicited outbound packets. Set by the wiring
    * layer once the underlying UDP peer group exists. The `delayMillis`
    * argument lets the responder request a fire-and-forget delay before the
    * UDP write — used for the post-handshake ping-back so it doesn't race
    * the immediate response back to the same peer. Multi-packet replies
    * (e.g. chunked NODES) pass `0` and go out as soon as netty can flush. */
  type OutboundSender = (InetSocketAddress, ByteVector, Long) => Unit

  def apply(
      privateKey: PrivateKey,
      localNodeId: ByteVector,
      handler: Handler,
      sessions: Session.SessionCache,
      challenges: ChallengeCache,
      bystanders: BystanderEnrTable,
      outboundSenderRef: AtomicReference[Option[OutboundSender]] = new AtomicReference(None),
      rateLimiter: RateLimiter = new RateLimiter(DefaultTokensPerSecond, DefaultMaxBurst)
  )(implicit
      payloadCodec: Codec[Payload],
      enrCodec: Codec[EthereumNodeRecord],
      sigalg: SigAlg
  ): StaticUDPPeerGroup.SyncResponder = (sender: InetSocketAddress, incomingBits: BitVector) => {
    if (!rateLimiter.tryAcquire()) {
      logger.debug(s"discv5 sync-fastpath: rate-limited request from $sender")
      StaticUDPPeerGroup.SyncResult.Pass
    } else {
      val maybeReply: Option[BitVector] =
        try
          respond(
            sender,
            incomingBits,
            privateKey,
            localNodeId,
            handler,
            sessions,
            challenges,
            bystanders,
            outboundSenderRef
          )
        catch {
          case NonFatal(ex) =>
            logger.warn(
              s"discv5 sync-fastpath: error handling packet from $sender: ${ex.getClass.getSimpleName}: ${ex.getMessage}"
            )
            None
        }
      // The discv5 sync responder claims any v5-shaped packet that decodes
      // under the local node id mask — even if it produces no reply (e.g. the
      // peer sent a Pong correlated by the async pipeline). That keeps v5
      // bytes out of the v4 codec's error stream. v5 packets that we DON'T
      // recognize at the wire level (mask mismatch) return None and fall
      // through to v4 as a regular Pass.
      maybeReply match {
        case Some(bits) => StaticUDPPeerGroup.SyncResult.Reply(bits)
        case None       =>
          // Did `respond` see a v5-shaped packet at all? If yes, claim it
          // silently with `Stop`; if no, pass through.
          val isV5 = isDiscv5Packet(incomingBits.toByteVector, localNodeId)
          if (isV5) StaticUDPPeerGroup.SyncResult.Stop
          else StaticUDPPeerGroup.SyncResult.Pass
      }
    }
  }

  /** Cheap peek to decide whether a packet is a discv5 packet under our mask
    * — used by [[apply]] to choose between [[StaticUDPPeerGroup.SyncResult.Stop]]
    * and `Pass` when there's no synchronous reply to send. */
  private def isDiscv5Packet(bytes: ByteVector, localNodeId: ByteVector): Boolean =
    Packet.decode(bytes, localNodeId).isSuccessful

  // ---- Core dispatch ------------------------------------------------------

  private def respond(
      sender: InetSocketAddress,
      incomingBits: BitVector,
      privateKey: PrivateKey,
      localNodeId: ByteVector,
      handler: Handler,
      sessions: Session.SessionCache,
      challenges: ChallengeCache,
      bystanders: BystanderEnrTable,
      outboundSenderRef: AtomicReference[Option[OutboundSender]]
  )(implicit
      payloadCodec: Codec[Payload],
      enrCodec: Codec[EthereumNodeRecord],
      sigalg: SigAlg
  ): Option[BitVector] = {
    val incoming = incomingBits.toByteVector
    val pkt = Packet.decode(incoming, localNodeId).toOption.orNull
    if (pkt == null) return None

    pkt match {
      case msg: Packet.MessagePacket =>
        handleMessage(
          sender,
          msg,
          incoming,
          privateKey,
          localNodeId,
          handler,
          sessions,
          challenges,
          outboundSenderRef
        )

      case hs: Packet.HandshakePacket =>
        handleHandshake(
          sender,
          hs,
          incoming,
          privateKey,
          localNodeId,
          handler,
          sessions,
          challenges,
          bystanders,
          outboundSenderRef
        )

      case _: Packet.WhoareyouPacket =>
        // The peer is challenging us — they don't know our node id. The
        // proper response is to initiate a handshake (sign their idNonce,
        // generate ephemeral keys, send a handshake packet). That requires
        // outbound logic which lives in the async DiscoveryNetwork. The
        // sync path falls through and lets the async pipeline handle it.
        None
    }
  }

  // ---- MessagePacket: with-session decrypt+respond, no-session WHOAREYOU --

  private def handleMessage(
      sender: InetSocketAddress,
      msg: Packet.MessagePacket,
      rawIncoming: ByteVector,
      privateKey: PrivateKey,
      localNodeId: ByteVector,
      handler: Handler,
      sessions: Session.SessionCache,
      challenges: ChallengeCache,
      outboundSenderRef: AtomicReference[Option[OutboundSender]]
  )(implicit
      payloadCodec: Codec[Payload],
      sigalg: SigAlg
  ): Option[BitVector] = {
    val sid = Session.SessionId(msg.header.srcId, sender)
    sessions.get(sid) match {
      case Some(session) =>
        // AAD per discv5-wire.md is the UNMASKED `masking-iv || static-header
        // || authdata`. Geth unmasks in place before passing to AES-GCM; we
        // never mutate the rawIncoming, so we reconstruct the unmasked region
        // from the decoded header instead.
        val aad = unmaskedHeaderRegion(msg.header.iv, Packet.Flag.Message, msg.header.nonce, msg.header.authData)

        Session.decrypt(session.keys.readKey, msg.header.nonce, msg.messageCiphertext, aad).toOption.flatMap {
          plaintext =>
            payloadCodec.decode(plaintext.bits).toOption.map(_.value).flatMap { payload =>
              val responses = dispatchRequest(payload, sender, msg.header.srcId, handler)
              sendReplies(responses, sender, msg.header.srcId, localNodeId, session, outboundSenderRef)
            }
        }

      case None =>
        // No session — respond with WHOAREYOU. The peer's follow-up
        // handshake packet will echo `msg.header.nonce`, so we key the
        // pending-challenge table on that nonce.
        sendWhoareyou(sender, msg.header.srcId, msg.header.nonce, localNodeId, challenges)
    }
  }

  private def sendWhoareyou(
      sender: InetSocketAddress,
      destNodeId: ByteVector,
      triggerNonce: ByteVector,
      localNodeId: ByteVector,
      challenges: ChallengeCache
  ): Option[BitVector] = {
    val sid = Session.SessionId(destNodeId, sender)
    // If we already have a pending challenge for this peer, resend it
    // verbatim. Geth does this so the peer can complete handshake under the
    // challenge they already saw — even if they re-sent (a different) Ping
    // before the handshake finished. The peer signs over the challenge bytes,
    // not the trigger nonce, so the trigger field of the resent WHOAREYOU is
    // intentionally the original ping's nonce. This is the behavior hive's
    // PingHandshakeInterrupted (a.k.a. HandshakeResend) test asserts.
    val (idNonce, challengeData, wireBytes) = challenges.get(sid) match {
      case Some(existing) =>
        (existing.idNonce, existing.challengeBytes, existing.wireBytes)
      case None =>
        val newIdNonce = Session.randomIdNonce
        val iv = Packet.randomIv
        val whoPkt = Packet.WhoareyouPacket(
          Packet.Header.Whoareyou(
            iv = iv,
            nonce = triggerNonce,
            idNonce = newIdNonce,
            recordSeq = 0L
          )
        )
        // The spec defines `challenge-data = masking-iv || static-header || authdata`
        // — i.e. the UNMASKED form of the static header + auth-data, not the
        // wire-form (which has them masked). The peer recomputes this same
        // unmasked form by unmasking the WHOAREYOU it received. We need to
        // sign / HKDF-salt over the same bytes for the handshake to verify.
        val staticHeader =
          Packet.ProtocolId ++
            ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
            ByteVector(Packet.Flag.Whoareyou) ++
            triggerNonce ++
            ByteVector.fromInt(whoPkt.header.authData.size.toInt, Packet.AuthSizeSize)
        val cdata = iv ++ staticHeader ++ whoPkt.header.authData
        Packet.encode(whoPkt, destNodeId).toOption match {
          case Some(encoded) => (newIdNonce, cdata, encoded)
          case None          => return None
        }
    }
    challenges.put(
      sid,
      PendingChallenge(idNonce, challengeData, wireBytes, triggerNonce, System.currentTimeMillis())
    )
    Some(wireBytes.bits)
  }

  // ---- HandshakePacket: verify, derive session, respond -------------------

  private def handleHandshake(
      sender: InetSocketAddress,
      hs: Packet.HandshakePacket,
      rawIncoming: ByteVector,
      privateKey: PrivateKey,
      localNodeId: ByteVector,
      handler: Handler,
      sessions: Session.SessionCache,
      challenges: ChallengeCache,
      bystanders: BystanderEnrTable,
      outboundSenderRef: AtomicReference[Option[OutboundSender]]
  )(implicit
      payloadCodec: Codec[Payload],
      enrCodec: Codec[EthereumNodeRecord],
      sigalg: SigAlg
  ): Option[BitVector] = {
    // Look up the WHOAREYOU we sent. The handshake packet's `header.nonce`
    // is a fresh GCM nonce for the handshake's own encrypted payload — it
    // does NOT echo the trigger nonce. So we key on `(srcId, peerAddr)`,
    // mirroring how the session cache itself is keyed. The handshake proves
    // knowledge of the challenge implicitly by signing a hash that includes
    // the WHOAREYOU bytes (`Session.idNonceHash`).
    val sid = Session.SessionId(hs.header.srcId, sender)
    val challenge = challenges.get(sid).orNull
    if (challenge == null) {
      logger.debug(s"discv5 sync-fastpath: handshake from $sender with no matching challenge")
      return None
    }
    val _ = challenges.remove(sid)

    // Verify the ID signature: sigalg.verify(peerPubkey, idNonceHash, signature).
    // The peer's pubkey is derived from the ENR record they included
    // (handshake auth-data optionally carries it) — if no record, we'd
    // need it from a prior interaction. Hive's tests always include the
    // ENR on the first handshake, so the simple case suffices here.
    val ephPubkey = hs.header.ephemeralPubkey
    val expectedHash = Session.idNonceHash(challenge.challengeBytes, ephPubkey, localNodeId)

    // Recover the peer's pubkey from the ID-signature. Discv5 signatures
    // are 64 bytes (recovery ID stripped). We try both possible recovery IDs
    // (0x00 and 0x01) and pick the one that yields a pubkey whose nodeID
    // matches the handshake's srcId. We use the hash-already-computed variant
    // since the discv5 signing input is already `sha256(...)` per spec —
    // the keccak step in [[SigAlg.recoverPublicKey]] would double-hash.
    val sig64 = hs.header.idSignature.toArray
    val candidates: List[PublicKey] = List(0.toByte, 1.toByte).flatMap { recId =>
      val sig65 = sig64 :+ recId
      sigalg.recoverPublicKeyFromHash(Signature(BitVector(sig65)), expectedHash.bits).toOption
    }
    val peerPubkeyOpt = candidates.find { pk =>
      Session.nodeIdFromPublicKey(pk.value.bytes) == hs.header.srcId
    }
    if (peerPubkeyOpt.isEmpty) {
      logger.debug(s"discv5 sync-fastpath: handshake from $sender failed pubkey recovery / srcId mismatch")
      return None
    }
    val peerPubkey: PublicKey = peerPubkeyOpt.get

    // Derive session keys: we are the recipient, so flip the result.
    val keys = Session.deriveKeys(
      ephemeralPrivate = privateKey.value.bytes,
      peerPublic = ephPubkey,
      initiatorNodeId = hs.header.srcId,
      recipientNodeId = localNodeId,
      challengeBytes = challenge.challengeBytes
    ).flip

    val session = Session.Session(keys, lastSeenMillis = System.currentTimeMillis())
    sessions.put(sid, session)

    // Decrypt the embedded message under the new session. AAD is the unmasked
    // `iv || static-header || authdata` (geth unmasks in place; we
    // reconstruct from the decoded header).
    val aad = unmaskedHeaderRegion(hs.header.iv, Packet.Flag.Handshake, hs.header.nonce, hs.header.authData)
    val plaintext = Session.decrypt(keys.readKey, hs.header.nonce, hs.messageCiphertext, aad).toOption.orNull
    if (plaintext == null) {
      logger.debug(s"discv5 sync-fastpath: handshake message decrypt failed for $sender")
      return None
    }

    val payload = payloadCodec.decode(plaintext.bits).toOption.map(_.value).orNull
    if (payload == null) return None

    // If the peer included an ENR record in the handshake auth, decode it and
    // stash in the bystander table. The findNode handler reads from this table
    // for non-zero distances, so populating it makes future FINDNODE responses
    // include the bonded peer (hive's `FindnodeResults`).
    hs.header.record.foreach { recordBytes =>
      enrCodec.decode(recordBytes.bits).toOption.map(_.value) match {
        case Some(enr) => bystanders.add(hs.header.srcId, enr)
        case None      =>
          logger.debug(s"discv5 sync-fastpath: handshake from $sender included an ENR we couldn't decode")
      }
    }

    // PING the peer back so they know we recognise them as a fully-bonded
    // peer. Hive's `FindnodeResults` uses this signal to mark a bystander as
    // "added to remote table". Only do this when the inbound was a Ping —
    // for TalkRequest / Findnode handshakes the peer doesn't expect (and
    // doesn't tolerate) an unsolicited Ping interleaved with the response.
    // Fire-and-forget; we don't track the response (the async pipeline does,
    // when wired up).
    if (payload.isInstanceOf[Payload.Ping]) {
      outboundSenderRef.get.foreach { send =>
        val pingPayload = Payload.Ping(
          requestId = Payload.randomRequestId(),
          enrSeq = handler.localEnrSeq
        )
        buildEncryptedReply(
          destNodeId = hs.header.srcId,
          srcNodeId = localNodeId,
          session = session,
          payload = pingPayload
        ).foreach { pingBits =>
          try send(sender, pingBits.toByteVector, PingBackDelayMillis)
          catch {
            case NonFatal(ex) =>
              logger.debug(s"discv5 sync-fastpath: ping-back to $sender failed: ${ex.getClass.getSimpleName}")
          }
        }
      }
    }

    val responses = dispatchRequest(payload, sender, hs.header.srcId, handler)
    sendReplies(responses, sender, hs.header.srcId, localNodeId, session, outboundSenderRef)
  }

  // ---- Reply dispatch -----------------------------------------------------

  /** Build encrypted bytes for each response payload and dispatch them: the
    * first goes back via the synchronous Reply (returned as `Some(bits)`),
    * any additional payloads are sent fire-and-forget via the outbound
    * sender. This is how a multi-packet NODES response gets delivered when
    * the result set wouldn't fit in one wire packet. */
  private def sendReplies(
      responses: List[Payload],
      peerAddr: InetSocketAddress,
      peerNodeId: ByteVector,
      localNodeId: ByteVector,
      session: Session.Session,
      outboundSenderRef: AtomicReference[Option[OutboundSender]]
  )(implicit payloadCodec: Codec[Payload]): Option[BitVector] = responses match {
    case Nil => None
    case head :: tail =>
      val firstReply = buildEncryptedReply(peerNodeId, localNodeId, session, head)
      // Send tail (if any) via outbound sender. If the sender ref isn't set
      // yet (peer group not initialised), the extra packets are silently
      // dropped — same fate as the ping-back when wiring is incomplete.
      if (tail.nonEmpty) {
        outboundSenderRef.get.foreach { send =>
          tail.foreach { p =>
            buildEncryptedReply(peerNodeId, localNodeId, session, p).foreach { bits =>
              try send(peerAddr, bits.toByteVector, 0L)
              catch {
                case NonFatal(ex) =>
                  logger.debug(s"discv5 sync-fastpath: extra reply to $peerAddr failed: ${ex.getClass.getSimpleName}")
              }
            }
          }
        }
      }
      firstReply
  }

  // ---- Request dispatch ---------------------------------------------------

  /** Per-message ENR cap for NODES responses. Discv5 lets the responder split
    * a NODES reply across multiple packets (the `total` field carries the
    * count); each packet must fit under the 1280-byte wire-form limit after
    * encryption. ENRs are roughly ~150-300 bytes RLP-encoded, so 3 per packet
    * matches geth's choice and keeps us comfortably under the cap. */
  private val MaxEnrsPerNodes: Int = 3

  /** Build the response payloads for an incoming decrypted request. Returns
    * the empty list if the message type isn't a request (e.g. we received a
    * Pong; the async pipeline correlates those by request id). For FINDNODE,
    * returns multiple NODES payloads when the result set wouldn't fit in a
    * single packet. The caller sends the head via the synchronous Reply and
    * the tail via the outbound sender. */
  private def dispatchRequest(
      payload: Payload,
      sender: InetSocketAddress,
      peerNodeId: ByteVector,
      handler: Handler
  ): List[Payload] = payload match {

    case ping: Payload.Ping =>
      val recipientIp =
        ByteVector.view(sender.getAddress.getAddress) // 4 or 16 bytes raw
      List(
        Payload.Pong(
          requestId = ping.requestId,
          enrSeq = handler.localEnrSeq,
          recipientIp = recipientIp,
          recipientPort = sender.getPort
        )
      )

    case fn: Payload.FindNode =>
      val nodes = handler.findNodes(fn.distances)
      // Spec: split into multiple NODES messages when needed; each carries
      // the same `total` so the peer knows how many to wait for. An empty
      // result is still a single NODES message with `total=1` and no enrs.
      val chunks = if (nodes.isEmpty) List(List.empty[EthereumNodeRecord]) else nodes.grouped(MaxEnrsPerNodes).toList
      val total = chunks.size
      chunks.map(chunk => Payload.Nodes(requestId = fn.requestId, total = total, enrs = chunk))

    case tq: Payload.TalkRequest =>
      // Per spec, an empty message is the valid "I don't support this
      // protocol" reply. Hive's TalkRequest test expects exactly that.
      List(Payload.TalkResponse(requestId = tq.requestId, message = ByteVector.empty))

    case _: Payload.Pong       => Nil // responses are correlated by the async pipeline
    case _: Payload.Nodes      => Nil
    case _: Payload.TalkResponse => Nil
  }

  /** Reconstruct the unmasked `iv || static-header || authdata` region used
    * as AES-GCM AAD per discv5-wire.md. `Packet.decode` already unmasks these
    * fields into the [[Packet.Header]] structure, so we re-serialize from the
    * decoded values rather than touching the raw incoming buffer. */
  private def unmaskedHeaderRegion(
      iv: ByteVector,
      flag: Byte,
      nonce: ByteVector,
      authData: ByteVector
  ): ByteVector = {
    val staticHeader =
      Packet.ProtocolId ++
        ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
        ByteVector(flag) ++
        nonce ++
        ByteVector.fromInt(authData.size.toInt, Packet.AuthSizeSize)
    iv ++ staticHeader ++ authData
  }

  // ---- Encrypted reply construction ---------------------------------------

  private def buildEncryptedReply(
      destNodeId: ByteVector,
      srcNodeId: ByteVector,
      session: Session.Session,
      payload: Payload
  )(implicit
      payloadCodec: Codec[Payload]
  ): Option[BitVector] = {
    val plaintext = payloadCodec.encode(payload).toOption.map(_.toByteVector).orNull
    if (plaintext == null) return None

    val nonce = Packet.randomNonce
    val iv = Packet.randomIv

    // AAD per spec is the UNMASKED `iv || static-header || authdata`. The
    // wire form has the static-header + authdata masked, but both peers feed
    // the unmasked form into AES-GCM. We compute the AAD first, then build
    // the wire form by masking the static-header+authdata portion.
    val authData = srcNodeId
    val staticHeader =
      Packet.ProtocolId ++
        ByteVector.fromInt(Packet.Version, Packet.VersionSize) ++
        ByteVector(Packet.Flag.Message) ++
        nonce ++
        ByteVector.fromInt(authData.size.toInt, Packet.AuthSizeSize)
    val aad = iv ++ staticHeader ++ authData

    val ct = Session.encrypt(session.keys.writeKey, nonce, plaintext, aad).toOption.orNull
    if (ct == null) return None

    val masked = Packet.aesCtrMask(destNodeId, iv, staticHeader ++ authData)
    Some((iv ++ masked ++ ct).bits)
  }

  // Lazy bridge for ConcurrentHashMap.values iteration.
  private implicit class IterableHasAsScala[A](val it: java.util.Iterator[A]) {
    def asScala: Iterator[A] = new Iterator[A] {
      def hasNext: Boolean = it.hasNext
      def next(): A = it.next()
    }
  }
}
