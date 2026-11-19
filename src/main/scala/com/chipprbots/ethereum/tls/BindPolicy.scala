package com.chipprbots.ethereum.tls

import java.net.InetAddress

import scala.util.Try

import com.typesafe.config.Config

/** No plaintext listener on a non-loopback interface (spec 030 FR-018, research D-8).
  *
  * An HTTP, WS, Engine API or metrics listener whose `interface` is not loopback refuses the node's start, before any
  * listener binds, unless that listener has `tls.enabled = true` or `allow-insecure-bind = true`. An interface is
  * loopback only when every address it resolves to is a loopback address (`127.0.0.0/8`, `::1`); `0.0.0.0` and `::`
  * accept connections on every interface and are not; a name that does not resolve is a startup error, as a bind to it
  * would be.
  */
object BindPolicy:

  /** One listener as the policy sees it. `key` is its section under `fukuii` (`network.rpc.http`, `metrics`, …). */
  final case class Listener(
      id: ListenerId,
      key: String,
      scheme: String,
      enabled: Boolean,
      interface: String,
      port: Int,
      tls: Boolean,
      allowInsecure: Boolean
  ):
    def path: String = s"${TlsConfig.Prefix}.$key"

  /** Whether `interface` is loopback: every address it resolves to is; `Left` with the reason when it does not resolve.
    */
  def isLoopback(interface: String): Either[String, Boolean] =
    val host = interface.trim.stripPrefix("[").stripSuffix("]")
    if host.isEmpty then Left("an empty interface")
    else
      Try(InetAddress.getAllByName(host)).toEither.left
        .map(e => s"$host does not resolve (${e.getClass.getSimpleName})")
        .map(addresses => addresses.nonEmpty && addresses.forall(_.isLoopbackAddress))

  /** The listeners of a node's configuration (the `fukuii` section), enabled or not. */
  def listeners(config: Config): Seq[Listener] =
    def bool(path: String, default: Boolean = false) = if config.hasPath(path) then config.getBoolean(path) else default
    def string(path: String, default: String) =
      if config.hasPath(path) && !config.getIsNull(path) then config.getString(path) else default
    def int(path: String, default: Int) = if config.hasPath(path) then config.getInt(path) else default
    def listener(id: ListenerId, key: String, scheme: String, enabledDefault: Boolean, port: Int, extraTls: Boolean) =
      Listener(
        id,
        key,
        scheme,
        bool(s"$key.enabled", enabledDefault),
        string(s"$key.interface", "localhost"),
        int(s"$key.port", port),
        bool(s"$key.tls.enabled") || extraTls,
        bool(s"$key.allow-insecure-bind")
      )
    Seq(
      // the deprecated mode = "https" is TLS too (FR-007)
      listener(
        ListenerId.RpcHttp,
        "network.rpc.http",
        "http",
        true,
        8546,
        string("network.rpc.http.mode", "http") == "https"
      ),
      listener(ListenerId.RpcWs, "network.rpc.ws", "ws", false, 8552, false),
      listener(ListenerId.EngineApi, "network.engine-api", "http", false, 8551, false),
      listener(ListenerId.Metrics, "metrics", "http", false, 13798, false)
    )

  /** The refusal of the first enabled plaintext listener on a non-loopback interface without the opt-out, with the text
    * of contracts/observability.md.
    */
  def check(config: Config): Either[String, Unit] =
    listeners(config).filter(_.enabled).foldLeft[Either[String, Unit]](Right(())) { (acc, l) =>
      acc.flatMap { _ =>
        isLoopback(l.interface) match
          case Left(reason) =>
            Left(s"${TlsStartupCheck.RefusalPrefix}${l.path}.interface: $reason")
          case Right(true)                              => Right(())
          case Right(false) if l.tls || l.allowInsecure => Right(())
          case Right(false)                             => Left(refusal(l))
      }
    }

  def refusal(l: Listener): String =
    s"${TlsStartupCheck.RefusalPrefix}${l.path} binds ${l.interface} (not loopback) without TLS. Set " +
      s"${l.path}.tls.enabled = true with a profile, or ${l.path}.allow-insecure-bind = true to accept plaintext on " +
      "that interface (docs/runbooks/tls-operations.md#migration)"

  /** One WARN line per enabled plaintext listener a non-loopback interface serves under the opt-out, for every start
    * (FR-018).
    */
  def insecureWarnings(config: Config): Seq[String] =
    listeners(config)
      .filter(l => l.enabled && !l.tls && l.allowInsecure && isLoopback(l.interface).contains(false))
      .map(l =>
        s"Plaintext listener ${l.id.label} ${l.scheme}://${l.interface}:${l.port} allow-insecure-bind=true " +
          s"(${l.path}.tls.enabled = false: anyone who can reach it reads and writes in the clear)"
      )
