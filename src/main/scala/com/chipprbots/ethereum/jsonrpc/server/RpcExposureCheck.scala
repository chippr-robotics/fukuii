package com.chipprbots.ethereum.jsonrpc.server

import java.net.InetAddress

import scala.util.Try

/** The startup check behind the "namespace on a non-loopback endpoint" warning.
  *
  * `personal` (key management), `debug`, `admin` (peers, addresses, the blacklist, chain import and export, log level),
  * `qa`, `miner` (mining control), `test` and `testing` (test-mode and test-harness tools that can rewrite the chain)
  * change node state or reveal node internals. They are off by default on HTTP and WebSocket. An operator may enable
  * them, and on a loopback endpoint only local programs can reach them; on any other address every client that can
  * reach the port can, so the node says so at startup.
  *
  * IPC is not checked: its socket is protected by file permissions, not by the network (the node sets the socket to
  * owner read/write only when it binds it).
  */
object RpcExposureCheck:

  val SensitiveNamespaces: Seq[String] = Seq("personal", "debug", "admin", "qa", "miner", "test", "testing")

  /** One JSON-RPC endpoint: `name` for the message, `key` the config key (`http`, `ws`) of its `apis` list. */
  final case class Transport(name: String, key: String, enabled: Boolean, interface: String, apis: Seq[String])

  /** One warning for each enabled endpoint that is not bound to loopback and serves a sensitive namespace. */
  def warnings(transports: Seq[Transport]): Seq[String] =
    transports.flatMap { transport =>
      val exposed = SensitiveNamespaces.filter(transport.apis.contains)
      if transport.enabled && exposed.nonEmpty && !isLoopback(transport.interface) then
        Some(
          s"JSON-RPC ${transport.name} is bound to '${transport.interface}', which is not a loopback address, and " +
            s"serves ${exposed.mkString(", ")}. Those namespaces can unlock accounts, change node settings or reveal " +
            s"node internals to every client that can reach the port. List only the namespaces you need in " +
            s"fukuii.network.rpc.${transport.key}.apis (or fukuii.network.rpc.apis), or bind to localhost."
        )
      else None
    }

  /** Whether `interface` (a bind address or host name, as in `network.rpc.http.interface`) is a loopback address.
    * `0.0.0.0` and `::` accept connections on every interface and are not loopback. A name that does not resolve is
    * treated as not loopback.
    */
  def isLoopback(interface: String): Boolean =
    val host = interface.trim.stripPrefix("[").stripSuffix("]")
    host.nonEmpty &&
    (host.equalsIgnoreCase("localhost") || Try(InetAddress.getByName(host).isLoopbackAddress).getOrElse(false))
