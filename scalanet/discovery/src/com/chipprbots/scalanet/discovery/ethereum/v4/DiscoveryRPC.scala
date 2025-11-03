package com.chipprbots.scalanet.discovery.ethereum.v4

import cats.effect.IO

import scala.language.unsafeNulls

import com.chipprbots.scalanet.discovery.crypto.PublicKey
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.Node

/** The RPC method comprising the Discovery protocol between peers. */
trait DiscoveryRPC[A] {
  import DiscoveryRPC.ENRSeq

  /** Sends a Ping request to the node, waits for the correct Pong response,
    * and returns the ENR sequence, if the Pong had one.
    */
  def ping: A => Option[ENRSeq] => IO[Option[Option[ENRSeq]]]

  /** Sends a FindNode request to the node and collects Neighbours responses
    * until a timeout or if the maximum expected number of nodes are returned.
    */
  def findNode: A => PublicKey => IO[Option[Seq[Node]]]

  /** Sends an ENRRequest to the node and waits for the correct ENRResponse,
    * returning the ENR from it.
    */
  def enrRequest: A => Unit => IO[Option[EthereumNodeRecord]]
}

object DiscoveryRPC {
  type ENRSeq = Long


}
