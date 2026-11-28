package com.chipprbots.ethereum.tls

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Duration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters

import org.apache.pekko.http.scaladsl.ConnectionContext
import org.apache.pekko.http.scaladsl.HttpsConnectionContext

import scala.util.Try

import com.typesafe.config.Config

/** Where an outbound client may follow a redirect (spec 030 FR-043): nowhere, or to another `https` URL only. Never
  * from `https` to `http`.
  */
enum RedirectPolicy:
  case Never, HttpsOnly

/** The one source of TLS for the node's outbound HTTPS clients (spec 030 FR-040–FR-045, research D-9).
  *
  * A client names a profile of `fukuii.tls.profiles`, or `system`: the JDK's default trust and no client certificate. A
  * named profile's truststore is then the only trust (FR-012: no fallback to the JDK's), and its keystore, if it has
  * one, is presented to a server that asks for a client certificate (FR-042). Every client the factory makes checks the
  * server's host name against its certificate (FR-041); none can be told not to.
  */
object OutboundTls:

  val System: String = TlsConfig.SystemProfile

  /** The client context of `profile`: `system`, or a profile of `fukuii.tls.profiles` loaded and validated now. */
  def context(config: Config, profile: String, secureRandom: SecureRandom): Either[TlsConfigError, SSLContext] =
    if profile == System then Right(SSLContext.getDefault)
    else
      for
        all <- TlsConfig.profiles(config)
        named <- all
          .get(profile)
          .toRight(TlsConfigError(s"there is no profile '$profile' under ${TlsConfig.Prefix}.tls.profiles"))
        material <- TlsMaterialLoader.loadClient(named)
      yield fromMaterial(material, secureRandom)

  /** A client context of loaded material: its key (if any) and its trust, nothing else. */
  def fromMaterial(material: TlsMaterial, secureRandom: SecureRandom): SSLContext =
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(material.keyManager.toArray, Array(material.trustManager), secureRandom)
    ctx

  /** The profile a use site names at `key` (relative to the `fukuii` section), `system` where unset (FR-044). */
  def profileAt(config: Config, key: String): String =
    if config.hasPath(key) && !config.getIsNull(key) then
      config.getString(key).trim match
        case "" => System
        case p  => p
    else System

  /** The client context for the use site at `key`: its profile, loaded. The error names the key. */
  def forSite(config: Config, key: String, secureRandom: SecureRandom): Either[TlsConfigError, SSLContext] =
    context(config, profileAt(config, key), secureRandom).left.map(e =>
      TlsConfigError(s"${TlsConfig.Prefix}.$key = ${profileAt(config, key)}: ${e.message}")
    )

  /** Parameters that make an engine or a client check the server's host name (FR-041). */
  def verifyingParameters(ctx: SSLContext): SSLParameters =
    val params = ctx.getDefaultSSLParameters
    params.setEndpointIdentificationAlgorithm("HTTPS")
    params

  /** A Pekko client context whose engines check the host name they connect to. */
  def pekkoHttpsContext(ctx: SSLContext): HttpsConnectionContext =
    ConnectionContext.httpsClient { (host: String, port: Int) =>
      val engine: SSLEngine = ctx.createSSLEngine(host, port)
      engine.setUseClientMode(true)
      engine.setSSLParameters(verifyingParameters(ctx))
      engine
    }

  /** A JDK client on `ctx`, checking host names, that follows redirects only as `redirect` allows. */
  def jdkHttpClientBuilder(ctx: SSLContext, redirect: RedirectPolicy): HttpClient.Builder =
    HttpClient
      .newBuilder()
      .sslContext(ctx)
      .sslParameters(verifyingParameters(ctx))
      // NORMAL follows every redirect except one from https to http (java.net.http.HttpClient.Redirect)
      .followRedirects(
        if redirect == RedirectPolicy.Never then HttpClient.Redirect.NEVER else HttpClient.Redirect.NORMAL
      )

  def jdkHttpClient(ctx: SSLContext, redirect: RedirectPolicy, connectTimeout: Duration): HttpClient =
    jdkHttpClientBuilder(ctx, redirect).connectTimeout(connectTimeout).build()

  /** Opens `url` (`https` only) on `ctx`, following at most `maxRedirects` redirects to other `https` URLs. A redirect
    * to anything else, or a URL that is not `https`, is an error (FR-043).
    */
  def openHttpsStream(
      url: String,
      ctx: SSLContext,
      maxRedirects: Int = 5,
      timeout: Duration = Duration.ofSeconds(30)
  ): Either[String, InputStream] =
    def open(current: URI, left: Int): Either[String, InputStream] =
      if !Option(current.getScheme).exists(_.equalsIgnoreCase("https")) then
        Left(s"refused: $current is not an https URL")
      else
        Try(current.toURL.openConnection()).toEither.left.map(_.getMessage).flatMap {
          case conn: HttpsURLConnection =>
            conn.setSSLSocketFactory(ctx.getSocketFactory)
            conn.setInstanceFollowRedirects(false)
            conn.setConnectTimeout(timeout.toMillis.toInt)
            conn.setReadTimeout(timeout.toMillis.toInt)
            Try(conn.getResponseCode).toEither.left.map(e => s"$current: ${e.getMessage}").flatMap {
              case code if code >= 300 && code < 400 =>
                val location = Option(conn.getHeaderField("Location"))
                conn.disconnect()
                (location, left) match
                  case (None, _)         => Left(s"$current: redirect $code without a Location")
                  case (_, 0)            => Left(s"$current: more than $maxRedirects redirects")
                  case (Some(target), _) => open(current.resolve(target), left - 1)
              case code if code == HttpURLConnection.HTTP_OK => Right(conn.getInputStream)
              case code =>
                conn.disconnect()
                Left(s"$current: HTTP $code")
            }
          case other => Left(s"refused: $current did not open an HTTPS connection (${other.getClass.getSimpleName})")
        }
    Try(URI.create(url)).toEither.left.map(_.getMessage).flatMap(open(_, maxRedirects))

  /** The faucet's legacy `rpc-client.certificate` block as a client profile (FR-007, FR-008): its keystore is both the
    * certificate presented and the trust, as the client built from it before did. `None` where the block is unset.
    */
  def legacyCertificateProfile(section: Config, path: String): Either[TlsConfigError, Option[TlsProfile]] =
    if !section.hasPath("certificate") || section.getIsNull("certificate") then Right(None)
    else
      Try {
        val cert = section.getConfig("certificate")
        StoreRef(
          Paths.get(cert.getString("keystore-path")),
          cert.getString("keystore-type"),
          Paths.get(cert.getString("password-file"))
        )
      }.toEither.left
        .map(e => TlsConfigError(s"$path.certificate: ${e.getMessage}"))
        .map(ref =>
          Some(
            TlsProfile(
              s"legacy-${path.split('.').last}",
              s"$path.certificate",
              Some(ref),
              None,
              Some(ref),
              Nil,
              Nil,
              legacy = true
            )
          )
        )
