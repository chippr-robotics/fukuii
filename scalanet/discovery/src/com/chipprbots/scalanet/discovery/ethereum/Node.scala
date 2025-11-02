package com.chipprbots.scalanet.discovery.ethereum

import java.net.InetAddress

import scala.util.Try

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.hash.Hash
import com.chipprbots.scalanet.discovery.hash.Keccak256
import com.chipprbots.scalanet.peergroup.Addressable
import com.chipprbots.scalanet.peergroup.InetAddressOps._
import scodec.bits.ByteVector

case class Node(id: Node.Id, address: Node.Address) {
  protected[discovery] lazy val kademliaId: Hash = Node.kademliaId(id)
}

object Node {

  /** 64 bit uncompressed Secp256k1 public key. */
  type Id = PublicKey

  /** The ID of the node is the 64 bit public key, but for the XOR distance we use its hash. */
  protected[discovery] def kademliaId(id: PublicKey): Hash =
    Keccak256(id.value)

  case class Address(
      ip: InetAddress,
      udpPort: Int,
      tcpPort: Int
  ) {
    protected[discovery] def checkRelay[A: Addressable](sender: A): Boolean =
      Address.checkRelay(sender = Addressable[A].getAddress(sender).getAddress, address = ip)
  }
  object Address {
    def fromEnr(enr: EthereumNodeRecord): Option[Node.Address] = {
      import EthereumNodeRecord.Keys

      def tryParse[T](key: ByteVector)(f: ByteVector => T): Option[T] =
        enr.content.attrs.get(key).flatMap { value =>
          Try(f(value)).toOption
        }

      def tryParseIP(key: ByteVector): Option[InetAddress] =
        tryParse[InetAddress](key)(bytes => InetAddress.getByAddress(bytes.toArray))

      def tryParsePort(key: ByteVector): Option[Int] =
        tryParse[Int](key)(bytes => bytes.toInt(signed = false))

      for {
        ip <- tryParseIP(Keys.ip6) orElse tryParseIP(Keys.ip)
        udp <- tryParsePort(Keys.udp6) orElse tryParsePort(Keys.udp)
        tcp <- tryParsePort(Keys.tcp6) orElse tryParsePort(Keys.tcp)
      } yield Node.Address(ip, udpPort = udp, tcpPort = tcp)
    }

    /** Check that an address relayed by the sender is valid:
      * - Special and unspecified addresses are invalid.
      * - LAN/loopback addresses are valid if the sender is also LAN/loopback.
      * - Other addresses are valid.
      */
    def checkRelay(sender: InetAddress, address: InetAddress): Boolean = {
      if (address.isSpecial || address.isUnspecified) false
      else if (address.isLoopbackAddress && !sender.isLoopbackAddress) false
      else if (address.isLAN && !sender.isLAN) false
      else true
    }
  }
}
