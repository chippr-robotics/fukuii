package com.chipprbots.ethereum.tls

import java.nio.file.Path
import java.security.SecureRandom
import javax.net.ssl.SSLContext

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Await
import scala.concurrent.duration.*

import com.typesafe.config.Config

/** A local HTTPS server of a test CA, for the outbound clients' specs (spec 030 S8): the server's certificate names
  * `localhost`; profile `ca` trusts the CA and presents nothing, profile `keyed` also presents a client certificate of
  * the CA, and `system` trusts the JDK's CAs only.
  */
final class HttpsTestServer(dir: Path):
  private val pki = new TestPki(dir)
  private val good = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
  private val clientKey = pki.keystore("client", Seq("me" -> pki.leaf("node-client")), "cli-pw")

  /** The `fukuii` section with the profiles `srv` (the server's), `ca` and `keyed`, and `extra` merged in. */
  def config(http: String = "", extra: String = ""): Config =
    com.typesafe.config.ConfigFactory
      .parseString(extra)
      .withFallback(
        TlsTestSupport.config(
          Seq(
            TlsTestSupport.profile("srv", good, Some(trust)),
            s"ca { truststore = ${TlsTestSupport.storeHocon(trust)} }",
            TlsTestSupport.profile("keyed", clientKey, Some(trust))
          ).mkString("\n"),
          http
        )
      )

  def context(profile: String): SSLContext =
    OutboundTls.context(config(), profile, new SecureRandom()).fold(e => throw new AssertionError(e.message), identity)

  /** Serves `route` over HTTPS on profile `srv` with `clientAuth`; `body` gets the port. */
  def serve[A](route: Route, clientAuth: String = "none")(body: Int => A)(using system: ActorSystem): A =
    val listener = config(s"""tls { enabled = true, profile = "srv", client-auth = "$clientAuth" }""")
    val server =
      TlsServerEngine.rpcHttp(listener, new SecureRandom()).fold(e => throw new AssertionError(e.message), identity)
    val binding = Await.result(
      Http().newServerAt("127.0.0.1", 0).enableHttps(TlsServerEngine.connectionContext(server)).bind(route),
      10.seconds
    )
    try body(binding.localAddress.getPort)
    finally Await.result(binding.terminate(1.second), 10.seconds)
