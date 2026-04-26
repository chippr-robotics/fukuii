package com.chipprbots.scalanet

import java.net.{InetAddress, InetSocketAddress, ServerSocket}

/** Network test helpers. Lives in `src/` (not test/) so both `ut` and `it` test
  * configs across the discovery module can import it without bespoke test
  * classpath wiring.
  */
object NetUtils {

  /** Allocate a random unused TCP port by binding to port 0 on the loopback
    * interface, reading the bound port number, and immediately releasing the
    * socket. Used by tests that need to spin up multiple peer groups without
    * port conflicts.
    *
    * Race window between this method returning and the caller binding the
    * port is small but real; tests that hit it should retry. In practice the
    * OS rarely re-issues a just-freed ephemeral port within a few ms.
    */
  def aRandomAddress(): InetSocketAddress = {
    val socket = new ServerSocket(0)
    try new InetSocketAddress(InetAddress.getLoopbackAddress, socket.getLocalPort)
    finally socket.close()
  }
}
