package com.chipprbots.scalanet.discovery.ethereum.v5

import cats.effect.IO
import scodec.bits.ByteVector

import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord

/** RPC surface for the discv5 protocol.
  *
  * Topic-discovery operations (`regtopic`/`topicquery`) were dropped
  * intentionally: geth removed them from production in v1.16.4 and the
  * spec marks them optional. They can be added back when there's a real
  * implementation; stubs would just be dead code.
  */
trait DiscoveryRPC[A] {

  /** PING → PONG. Returns the peer's current ENR seq plus its view of our
    * own external (ip, port), or `None` on timeout/error. */
  def ping(peer: A, localEnrSeq: Long): IO[Option[DiscoveryRPC.PingResult]]

  /** FINDNODE → one-or-more NODES. Returns all collected ENRs from the
    * paginated response, or `None` on timeout/error. */
  def findNode(peer: A, distances: List[Int]): IO[Option[List[EthereumNodeRecord]]]

  /** TALKREQ → TALKRESP. The protocol-bytes are app-defined; an empty
    * `message` response is the spec-blessed "I don't support this protocol"
    * reply. */
  def talkRequest(peer: A, protocol: ByteVector, message: ByteVector): IO[Option[ByteVector]]
}

object DiscoveryRPC {

  /** Result of a successful PING. `recipientIp` is raw 4 (IPv4) or 16 (IPv6)
    * bytes — the IP's bytes themselves, no length prefix. */
  final case class PingResult(
      enrSeq: Long,
      recipientIp: ByteVector,
      recipientPort: Int
  )
}
