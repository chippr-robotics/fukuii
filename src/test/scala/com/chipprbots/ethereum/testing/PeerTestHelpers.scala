package com.chipprbots.ethereum.testing

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef

import com.chipprbots.ethereum.network.{Peer, PeerId}

/** Test utilities for creating mock Peer instances in unit tests */
object PeerTestHelpers {
  
  /** Create a test peer with a dummy InetSocketAddress
    *
    * @param id Peer identifier string
    * @param ref ActorRef for the peer
    * @return A properly constructed Peer instance for testing
    */
  def createTestPeer(id: String, ref: ActorRef): Peer = {
    Peer(
      id = PeerId(id),
      remoteAddress = new InetSocketAddress("127.0.0.1", 30303),
      ref = ref,
      incomingConnection = false
    )
  }
}
