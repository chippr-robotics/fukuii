package com.chipprbots.ethereum.tls

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*

import scala.concurrent.Await
import scala.concurrent.duration.*

import com.typesafe.config.Config

/** Test helpers for mutual TLS: a minimal HTTPS server bound the way the RPC listeners bind (observed handshakes, the
  * TLS session on each request), and a raw client that reports how its exchange ended.
  */
object MtlsTestSupport:

  val Wait: FiniteDuration = 10.seconds

  /** A server answering `ok` on every path, over the HTTP listener's TLS of `config`; `body` gets its port. */
  def withServer[A](
      config: Config,
      listener: ListenerId = ListenerId.RpcHttp,
      failureLog: HandshakeFailureLog = new HandshakeFailureLog()
  )(body: Int => A)(using system: ActorSystem): A =
    val server =
      TlsServerEngine.rpcHttp(config, new SecureRandom()).fold(e => throw new AssertionError(e.message), x => x)
    withServerContext(server, listener, failureLog)(body)

  /** As [[withServer]], on a context the test holds, so it can reload it while the server runs. */
  def withServerContext[A](
      server: TlsServerContext,
      listener: ListenerId = ListenerId.RpcHttp,
      failureLog: HandshakeFailureLog = new HandshakeFailureLog()
  )(body: Int => A)(using system: ActorSystem): A =
    val settings = TlsServerEngine.serverSettings(system)
    val route = complete("ok")
    val binding = Await.result(
      Http(system)
        .newServerAt("127.0.0.1", 0)
        .enableHttps(TlsServerEngine.connectionContext(server))
        .withSettings(settings)
        .bindFlow(
          TlsServerEngine.observeHandshakes(listener, TlsServerEngine.routeHandler(route, settings), failureLog)
        ),
      Wait
    )
    try body(binding.localAddress.getPort)
    finally Await.result(binding.terminate(1.second), Wait)

  /** How a client's exchange ended: the HTTP status line it read, or the exception that ended it. */
  def exchange(
      ctx: SSLContext,
      port: Int,
      protocols: Array[String] = Array("TLSv1.2", "TLSv1.3"),
      path: String = "/"
  ): Either[Throwable, String] =
    try
      val socket = ctx.getSocketFactory.createSocket("localhost", port).asInstanceOf[SSLSocket]
      try
        socket.setSoTimeout(Wait.toMillis.toInt)
        socket.setEnabledProtocols(protocols)
        socket.startHandshake()
        // Under TLS 1.3 the client's side of the handshake ends before the server has checked its certificate: the
        // server's refusal arrives on the first read.
        val out = socket.getOutputStream
        out.write(
          s"GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
        )
        out.flush()
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
        Option(in.readLine()).toRight(new java.io.EOFException("the server closed the connection without an answer"))
      finally socket.close()
    catch case e: java.io.IOException => Left(e)

  /** The subject of the certificate the server presents to a new handshake (a client trusting `ctx`). */
  def serverSubject(ctx: SSLContext, port: Int): String =
    val socket = ctx.getSocketFactory.createSocket("localhost", port).asInstanceOf[SSLSocket]
    try
      socket.setSoTimeout(Wait.toMillis.toInt)
      socket.startHandshake()
      socket.getSession.getPeerCertificates.head
        .asInstanceOf[java.security.cert.X509Certificate]
        .getSubjectX500Principal
        .getName
    finally socket.close()

  def answered(result: Either[Throwable, String]): Boolean = result.exists(_.startsWith("HTTP/1.1 200"))
