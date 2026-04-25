package com.chipprbots.scalanet.discovery.ethereum.v4

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

import com.chipprbots.scalanet.discovery.crypto.PrivateKey
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import com.chipprbots.scalanet.peergroup.udp.StaticUDPPeerGroup
import com.typesafe.scalalogging.LazyLogging
import scodec.Codec
import scodec.bits.BitVector

/** Synchronous discv4 fast-path responder, designed for the hive devp2p suite's
  * 300 ms Ping/Pong deadline that the cats-effect IO scheduler can't hit under load.
  *
  * Hooked into `StaticUDPPeerGroup.channelRead` via the `syncResponder` Config field.
  * Runs on the netty event-loop thread for every inbound datagram. If the bytes
  * decode as a non-expired Ping, the responder builds, signs, and returns the Pong
  * bytes — netty then writes them back to the sender without a fiber hop.
  *
  * The async pipeline still receives the same Ping and runs `handler.ping(caller)`
  * as before, so bonding and kademlia bookkeeping are unaffected. The async path's
  * Pong is redundant but not wrong (idempotent at the protocol layer); accepting
  * one extra Pong per Ping is the price we pay for not threading "already responded"
  * state down through the layers. Worst case the simulator gets two valid Pongs.
  *
  * Design contract:
  *   - Total work is dominated by two ECDSA ops (verify Ping sig, sign Pong),
  *     ~3–5 ms each on a modern x86 — well under the 300 ms budget.
  *   - The responder MUST NOT throw. Any exception is caught and swallowed; the
  *     async path then handles the Ping normally.
  *   - Reads no IO state. The local ENR seq comes from an `AtomicReference`
  *     supplier that the discovery service updates whenever the ENR rotates.
  */
object Discv4SyncResponder extends LazyLogging {

  /** Build a SyncResponder for discv4 Ping → Pong.
    *
    * @param privateKey local node's secp256k1 private key, used to sign Pong
    * @param expirationSeconds how far into the future to set the Pong's `expiration` field
    * @param maxClockDriftSeconds incoming Pings older than `now - drift` are ignored
    * @param localEnrSeqRef supplier of the local ENR seq number; pass an
    *        `AtomicReference[Option[Long]]` so the discovery service can update it
    *        as the ENR rotates without re-allocating the responder
    */
  def apply(
      privateKey: PrivateKey,
      expirationSeconds: Long,
      maxClockDriftSeconds: Long,
      localEnrSeqRef: AtomicReference[Option[Long]]
  )(implicit
      payloadCodec: Codec[Payload],
      packetCodec: Codec[Packet],
      sigalg: SigAlg
  ): StaticUDPPeerGroup.SyncResponder = (sender: InetSocketAddress, incomingBits: BitVector) => {
    try respond(sender, incomingBits, privateKey, expirationSeconds, maxClockDriftSeconds, localEnrSeqRef)
    catch {
      case NonFatal(ex) =>
        logger.warn(
          s"discv4 sync-fastpath: unexpected error producing Pong for $sender: ${ex.getClass.getSimpleName}: ${ex.getMessage}"
        )
        None
    }
  }

  private def respond(
      sender: InetSocketAddress,
      incomingBits: BitVector,
      privateKey: PrivateKey,
      expirationSeconds: Long,
      maxClockDriftSeconds: Long,
      localEnrSeqRef: AtomicReference[Option[Long]]
  )(implicit
      payloadCodec: Codec[Payload],
      packetCodec: Codec[Packet],
      sigalg: SigAlg
  ): Option[BitVector] = {
    // Decode the wire-format Packet wrapper (hash + sig + payload bytes).
    val packet = packetCodec.decode(incomingBits).toOption.map(_.value).orNull
    if (packet == null) return None

    // Unpack: verify the Ping's hash and signature, decode the payload.
    val unpacked = Packet.unpack(packet).toOption.orNull
    if (unpacked == null) return None
    val (payload, _) = unpacked

    payload match {
      case ping: Payload.Ping =>
        val nowSeconds = System.currentTimeMillis() / 1000
        if (ping.expiration < nowSeconds - maxClockDriftSeconds) {
          // Expired — let the async path log and drop.
          None
        } else {
          val pong = Payload.Pong(
            to = ping.to,
            pingHash = packet.hash,
            expiration = nowSeconds + expirationSeconds,
            enrSeq = localEnrSeqRef.get()
          )
          Packet.pack(pong, privateKey).toOption.flatMap { pongPacket =>
            packetCodec.encode(pongPacket).toOption.map { pongBits =>
              logger.debug(s"discv4 sync-fastpath: replied to Ping from $sender")
              pongBits
            }
          }
        }

      case _ =>
        // FindNode, ENRRequest, Pong, etc. — let the async path handle.
        None
    }
  }
}
