package com.chipprbots.ethereum.tls

import java.net.InetSocketAddress
import java.security.SecureRandom
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.ConnectionContext
import org.apache.pekko.http.scaladsl.HttpsConnectionContext
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/** The server side of a TLS listener: its engines, its connection context, its server settings, and the handler that
  * reports refused handshakes (spec 030 research D-2, FR-003, FR-017).
  */
object TlsServerEngine:

  private val log = LoggerFactory.getLogger("com.chipprbots.ethereum.tls.TlsHandshake")

  /** A fresh server engine, with the protocol floor and the listener's client-auth mode set (FR-003). Under `want` and
    * `need` a presented certificate is verified by the context's trust manager; `need` also refuses a handshake that
    * presents none.
    */
  def newEngine(server: TlsServerContext): SSLEngine =
    val engine = server.context.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setEnabledProtocols(server.protocols.toArray)
    if server.cipherSuites.nonEmpty then engine.setEnabledCipherSuites(server.cipherSuites.toArray)
    // setNeedClientAuth and setWantClientAuth each clear the other, so exactly one of these calls is made
    server.clientAuth match
      case ClientAuth.None => engine.setWantClientAuth(false); engine.setNeedClientAuth(false)
      case ClientAuth.Want => engine.setWantClientAuth(true)
      case ClientAuth.Need => engine.setNeedClientAuth(true)
    engine

  def connectionContext(server: TlsServerContext): HttpsConnectionContext =
    ConnectionContext.httpsServer(() => newEngine(server))

  /** The settings a TLS listener binds with: the TLS session on every request (`AttributeKeys.sslSession`, what the
    * identity rules read), and the peer's address on the connection, which [[observeHandshakes]] reads. Set per server,
    * so the parsing of the Engine API and of other servers is unchanged (research D-2).
    */
  def serverSettings(system: ActorSystem): ServerSettings =
    val base = ServerSettings(system)
    base
      .withParserSettings(base.parserSettings.withIncludeSslSessionAttribute(true))
      .withRemoteAddressAttribute(true)

  /** The HTTP listener's server context, loaded from the node's configuration: what a server (re)binds with. */
  def rpcHttp(config: Config, secureRandom: SecureRandom): Either[TlsConfigError, TlsServerContext] =
    load(TlsConfig.rpcHttp(config), s"${TlsConfig.Prefix}.network.rpc.http", secureRandom)

  /** The WebSocket listener's server context; `Right(None)` where its TLS is off. */
  def rpcWs(config: Config, secureRandom: SecureRandom): Either[TlsConfigError, Option[TlsServerContext]] =
    TlsConfig.rpcWs(config).flatMap {
      case None           => Right(None)
      case Some(resolved) => loadResolved(resolved, secureRandom).map(Some(_))
    }

  private def load(
      resolved: Either[TlsConfigError, Option[ResolvedListener]],
      key: String,
      secureRandom: SecureRandom
  ): Either[TlsConfigError, TlsServerContext] =
    resolved.flatMap {
      case None           => Left(TlsConfigError(s"$key: TLS is not configured on this listener"))
      case Some(listener) => loadResolved(listener, secureRandom)
    }

  private def loadResolved(resolved: ResolvedListener, secureRandom: SecureRandom) =
    TlsMaterialLoader
      .load(resolved.profile, resolved.listener.clientAuth, resolved.configPath)
      .map(material =>
        TlsContexts.server(resolved.profile, material, resolved.listener.clientAuth, secureRandom, resolved.configPath)
      )

  /** The node's one limiter: a scan that tries every listener is still logged once per address per minute. */
  private val nodeFailureLog = new HandshakeFailureLog()

  /** Counts a refused handshake and logs it, at most once per remote address per minute (FR-017). */
  def reportFailure(
      listener: ListenerId,
      remote: Option[InetSocketAddress],
      failure: Throwable,
      failureLog: HandshakeFailureLog = nodeFailureLog
  ): HandshakeFailureReason =
    val reason = HandshakeFailureClassifier.classify(failure)
    TlsHandshakeMetrics.increment(listener, reason)
    val address = remote.map(_.getAddress.getHostAddress).getOrElse("an unknown address")
    failureLog.record(listener.label, address, reason, Option(failure.getMessage).getOrElse(failure.toString)) match
      case Some(line) => log.warn(line)
      case None       => ()
    reason

  /** The connection's handler, with its refused handshake reported.
    *
    * Pekko builds the server engine with no knowledge of the connection, but it puts the peer's address on the
    * connection's stream (with `remote-address-attribute = on`, see [[serverSettings]]), and a failed handshake fails
    * the request side of that stream. So the handler, materialized once per connection, sees both. A failure after a
    * request was read is not a refused handshake, and is left to the HTTP layer.
    */
  def observeHandshakes(
      listener: ListenerId,
      handler: Flow[HttpRequest, HttpResponse, Any],
      failureLog: HandshakeFailureLog = nodeFailureLog
  ): Flow[HttpRequest, HttpResponse, NotUsed] =
    Flow
      .fromMaterializer { (_, attributes) =>
        val remote = remoteAddress(attributes)
        @volatile var requestSeen = false
        Flow[HttpRequest]
          .map { request =>
            requestSeen = true
            request
          }
          .mapError {
            case failure if !requestSeen && isTls(failure) =>
              val _ = reportFailure(listener, remote, failure, failureLog)
              failure
          }
          .via(handler)
      }
      .mapMaterializedValue(_ => NotUsed)

  /** A route as the handler `bind(route)` would make of it: `pipelining-limit` requests of a connection at once. */
  def routeHandler(route: Route, settings: ServerSettings)(using
      system: ActorSystem
  ): Flow[HttpRequest, HttpResponse, NotUsed] =
    val handle: HttpRequest => Future[HttpResponse] = Route.toFunction(route)
    Flow[HttpRequest].mapAsync(settings.pipeliningLimit)(handle)

  private def isTls(failure: Throwable): Boolean =
    Iterator.iterate(failure)(_.getCause).takeWhile(_ != null).take(16).exists(_.isInstanceOf[SSLException])

  /** The peer of the connection, from Pekko's connection attribute (`HttpAttributes.RemoteAddress`, internal to Pekko,
    * hence read by its shape: the one attribute holding an `InetSocketAddress`).
    */
  private def remoteAddress(attributes: Attributes): Option[InetSocketAddress] =
    attributes.attributeList.iterator.collectFirst {
      case attribute: Product if attribute.productPrefix == "RemoteAddress" =>
        attribute.productIterator.collectFirst { case address: InetSocketAddress => address }
    }.flatten
