package com.chipprbots.ethereum.jsonrpc.server.http

import java.util.Locale

import com.chipprbots.ethereum.utils.Logger

/** Allow-list for the `Origin` header of a JSON-RPC WebSocket upgrade request.
  *
  * Browsers always send `Origin` on a WebSocket handshake. The list lets the operator decide which web pages may open a
  * connection to the node's WebSocket port. A request with no `Origin` header comes from a program that is not a
  * browser (a CLI, a library, a consensus client) and is never filtered here.
  *
  * Semantics follow go-ethereum's `--ws.origins` (`rpc/websocket.go`, `wsHandshakeValidator`):
  *   - an entry is an origin, `scheme://host[:port]`, or a scheme-less `host[:port]`;
  *   - a part the entry leaves out matches anything: no scheme matches any scheme, no port matches any port, so
  *     `http://localhost` allows `http://localhost:3000` and `localhost` allows `https://localhost:8443`;
  *   - comparison is case-insensitive; the port is compared as written (`http://a:80` does not match `http://a`);
  *   - IPv6 hosts are written in brackets: `http://[::1]`;
  *   - `*` allows every origin;
  *   - an empty list means the default: the loopback origins in [[WsOriginPolicy.DefaultAllowedOrigins]]. A non-empty
  *     list replaces the default, so list `http://localhost` as well to keep it.
  *
  * Two differences from geth, both narrower: an entry without a host (`http://`) is ignored with a warning instead of
  * matching every host, and the default names the loopback addresses rather than the machine's hostname.
  */
final class WsOriginPolicy private (allowAll: Boolean, rules: Seq[WsOriginPolicy.OriginSpec]):
  import WsOriginPolicy.*

  /** Whether one `Origin` header value is on the list. */
  def allows(origin: String): Boolean =
    allowAll || parse(origin).exists(candidate => rules.exists(matches(_, candidate)))

  /** Decision for an upgrade request, given the value of every `Origin` header it carried. No header at all is allowed;
    * if the request carries several, each one must be allowed.
    */
  def permits(originHeaderValues: Seq[String]): Boolean =
    originHeaderValues.forall(allows)

object WsOriginPolicy extends Logger:

  /** An origin split into its parts, lower case. `scheme` and `port` are empty when the text had none. */
  final private[http] case class OriginSpec(scheme: String, host: String, port: String)

  /** Used when no origin is configured: pages served from this machine, over http or https, on any port. */
  val DefaultAllowedOrigins: Seq[String] =
    for
      scheme <- Seq("http", "https")
      host <- Seq("localhost", "127.0.0.1", "[::1]")
    yield s"$scheme://$host"

  def apply(entries: Seq[String]): WsOriginPolicy =
    val configured = entries.map(_.trim).filter(_.nonEmpty)
    if configured.contains("*") then new WsOriginPolicy(allowAll = true, rules = Nil)
    else
      val effective = if configured.isEmpty then DefaultAllowedOrigins else configured
      val rules = effective.flatMap { entry =>
        parse(entry).filter(_.host.nonEmpty).orElse {
          log.warn(s"Ignoring WebSocket allowed-origins entry that is not a valid origin: '${sanitize(entry)}'")
          None
        }
      }
      new WsOriginPolicy(allowAll = false, rules = rules)

  private def matches(rule: OriginSpec, origin: OriginSpec): Boolean =
    (rule.scheme.isEmpty || rule.scheme == origin.scheme) &&
      rule.host == origin.host &&
      (rule.port.isEmpty || rule.port == origin.port)

  /** Splits an origin or an allow-list entry into scheme, host and port. `None` when it is not well formed. Any path,
    * query or fragment after the authority is ignored, so a trailing slash on an entry is harmless.
    */
  private[http] def parse(raw: String): Option[OriginSpec] =
    val text = raw.trim.toLowerCase(Locale.ROOT)
    val sep = text.indexOf("://")
    val (scheme, rest) = if sep >= 0 then (text.substring(0, sep), text.substring(sep + 3)) else ("", text)
    val authority = rest.takeWhile(c => c != '/' && c != '?' && c != '#')
    if sep >= 0 && !scheme.matches("[a-z][a-z0-9+.-]*") then None
    else splitAuthority(authority).map((host, port) => OriginSpec(scheme, host, port))

  private def splitAuthority(authority: String): Option[(String, String)] =
    if authority.contains('@') then None
    else if authority.startsWith("[") then
      val close = authority.indexOf(']')
      if close < 0 then None
      else
        val host = authority.substring(1, close)
        val tail = authority.substring(close + 1)
        val port =
          if tail.isEmpty then Some("")
          else if tail.startsWith(":") then Some(tail.substring(1))
          else None
        port.filter(isPort).filter(_ => isIpv6Literal(host)).map(p => (host, p))
    else
      authority.split(":", -1) match
        case Array(host)       => hostName(host).map(h => (h, ""))
        case Array(host, port) => if isPort(port) then hostName(host).map(h => (h, port)) else None
        case _                 => None

  private def hostName(host: String): Option[String] =
    Option.when(host.forall(c => (c >= 'a' && c <= 'z') || isDigit(c) || c == '.' || c == '-' || c == '_'))(host)

  private def isIpv6Literal(host: String): Boolean =
    host.contains(':') && host.forall(c => isDigit(c) || (c >= 'a' && c <= 'f') || c == ':' || c == '.')

  private def isPort(port: String): Boolean = port.length <= 5 && port.forall(isDigit)

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

  /** A header or config value made safe to put in a log line: bounded, no control characters. */
  private[http] def sanitize(value: String): String =
    value.take(120).map(c => if c < ' ' || c == '\u007f' then '?' else c)
