package com.chipprbots.ethereum.sync.util

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.Resource

import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.network.discovery.Node
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

/** A TCP listener that accepts every connection and then says nothing, standing for a node whose RLPx handshake never
  * answers. A peer that dials [[node]] connects, sends its auth message, and waits for an ack that does not come until
  * its own handshake timeout ends the attempt. It needs no clock: it accepts, counts and holds the socket.
  *
  * [[node]] carries a real public key, since the dialer encrypts its auth message to it.
  */
final class SilentTcpServer extends SecureRandomBuilder:
  private val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
  private val held = new CopyOnWriteArrayList[Socket]()
  private val acceptedCount = new AtomicInteger(0)

  val node: Node =
    val key = generateKeyPair(secureRandom)
    val id = NodeStatus(key, ServerStatus.NotListening, ServerStatus.NotListening).nodeId
    Node(ByteString(id), server.getInetAddress, server.getLocalPort, server.getLocalPort)

  /** Connections accepted so far. */
  def accepted: Int = acceptedCount.get()

  private val acceptor = new Thread(
    () =>
      try
        while !server.isClosed do
          val socket = server.accept()
          held.add(socket)
          acceptedCount.incrementAndGet()
      catch case _: java.io.IOException => () // closed by [[close]]
    ,
    "silent-tcp-server"
  )
  acceptor.setDaemon(true)
  acceptor.start()

  def close(): Unit =
    server.close()
    held.forEach(socket => socket.close())
    acceptor.join(5000)

object SilentTcpServer:
  def resource: Resource[IO, SilentTcpServer] = Resource.make(IO(new SilentTcpServer))(server => IO(server.close()))
