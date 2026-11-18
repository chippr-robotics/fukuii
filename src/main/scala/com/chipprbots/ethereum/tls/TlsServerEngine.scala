package com.chipprbots.ethereum.tls

import java.security.SecureRandom
import javax.net.ssl.SSLEngine

import org.apache.pekko.http.scaladsl.ConnectionContext
import org.apache.pekko.http.scaladsl.HttpsConnectionContext

import com.typesafe.config.Config

/** The server side of a TLS listener: its engines and its connection context (spec 030 research D-2). */
object TlsServerEngine:

  /** A fresh server engine, with the protocol floor and the listener's client-auth mode set. */
  def newEngine(server: TlsServerContext): SSLEngine =
    val engine = server.context.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setEnabledProtocols(server.protocols.toArray)
    if server.cipherSuites.nonEmpty then engine.setEnabledCipherSuites(server.cipherSuites.toArray)
    server.clientAuth match
      case ClientAuth.None =>
        engine.setNeedClientAuth(false)
        engine.setWantClientAuth(false)
      case other =>
        // Slice 2. The start-up check refuses the configuration, so no listener reaches this line.
        throw new IllegalStateException(s"client-auth = ${other.toString.toLowerCase} is not served by this build")
    engine

  def connectionContext(server: TlsServerContext): HttpsConnectionContext =
    ConnectionContext.httpsServer(() => newEngine(server))

  /** The HTTP listener's server context, loaded from the node's configuration: what a server (re)binds with. */
  def rpcHttp(config: Config, secureRandom: SecureRandom): Either[TlsConfigError, TlsServerContext] =
    TlsConfig.rpcHttp(config).flatMap {
      case None =>
        Left(TlsConfigError(s"${TlsConfig.Prefix}.network.rpc.http: TLS is not configured on this listener"))
      case Some(resolved) =>
        for
          _ <- TlsStartupCheck.supported(resolved)
          material <- TlsMaterialLoader.load(resolved.profile, resolved.listener.clientAuth, resolved.configPath)
        yield TlsContexts.server(resolved.profile, material, resolved.listener.clientAuth, secureRandom)
    }
