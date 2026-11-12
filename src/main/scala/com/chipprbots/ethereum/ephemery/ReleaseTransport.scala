package com.chipprbots.ethereum.ephemery

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext

import scala.util.Try
import scala.util.control.NonFatal

import com.chipprbots.ethereum.utils.Logger

/** Why a release fetch was refused or failed. Messages carry a scheme, a host or a status, never a full URL: a redirect
  * URL holds a signed query.
  */
enum TransportFailure(val message: String):
  case NotHttps(scheme: String) extends TransportFailure(s"refused: only https is accepted, got scheme '$scheme'")
  case HostNotAllowed(host: String)
      extends TransportFailure(s"refused: host '$host' is not on the allow-list for this source")
  case TooManyRedirects(max: Int) extends TransportFailure(s"refused: more than $max redirects")
  case BadRedirect(why: String) extends TransportFailure(s"refused: bad redirect ($why)")
  case BodyTooLarge(cap: Long) extends TransportFailure(s"refused: body larger than $cap bytes")
  case DeadlineExceeded extends TransportFailure("the deadline passed before the body was complete")
  case PortNotAllowed(port: Int) extends TransportFailure(s"refused: port $port is not allowed for this source")
  case ContentEncoding(value: String)
      extends TransportFailure(s"refused: the response has Content-Encoding '$value'; only identity is accepted")
  case HttpStatus(code: Int) extends TransportFailure(s"the server answered HTTP $code")
  case Io(why: String) extends TransportFailure(s"I/O failure: $why")

/** A complete, size-capped response body. `finalUri` is the URI after redirects; callers log only its host. */
final case class FetchedBody(finalUri: URI, status: Int, bytes: Array[Byte]):
  def finalHost: String = Option(finalUri.getHost).getOrElse("")

/** Which hosts a redirect chain may visit. There is no configuration for this; a caller picks one in code. */
sealed trait SourcePolicy:
  def permits(host: String): Boolean

  /** `port` is the URI's port, -1 when it has none (which means 443 for https). */
  def permitsPort(port: Int): Boolean

object SourcePolicy:
  /** Exact hosts, or `*.suffix` for any proper subdomain of `suffix`. Matching ignores case. */
  final case class AllowList(patterns: Seq[String], allowedPorts: Set[Int] = Set(443)) extends SourcePolicy:
    def permitsPort(port: Int): Boolean = port == -1 || allowedPorts.contains(port)
    def permits(host: String): Boolean =
      val h = host.toLowerCase(Locale.ROOT)
      h.nonEmpty && patterns.exists { p =>
        val pat = p.toLowerCase(Locale.ROOT)
        if pat.startsWith("*.") then h.endsWith(pat.drop(1)) && h.length > pat.length - 1
        else h == pat
      }

  /** An operator-configured https mirror: any https host, because the mirror's own redirects are its business. */
  case object Mirror extends SourcePolicy:
    def permits(host: String): Boolean = host.nonEmpty
    def permitsPort(port: Int): Boolean = true

  /** The release host: github.com, api.github.com and `*.githubusercontent.com` (assets 302 to
    * release-assets.githubusercontent.com).
    */
  val DefaultGithub: AllowList = AllowList(Seq("github.com", "api.github.com", "*.githubusercontent.com"))

trait ReleaseTransport:

  /** Fetch `url` with every hop checked; the whole body must arrive before `deadline` and within `maxBytes`. */
  def get(
      url: URI,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String] = None
  ): Either[TransportFailure, FetchedBody]

/** An https-only client on `java.net.http.HttpClient`.
  *
  * The `client`, `policy`, `clock` and `userAgent` are constructor parameters so tests can pass a self-signed
  * `SSLContext`, an allow-list for 127.0.0.1 and a clock. No configuration key reaches this class: HTTPS and the host
  * policy cannot be switched off from outside the code that builds it.
  *
  * What the JDK properties still reach, because the JDK client reads them itself: `https.proxyHost`, `https.proxyPort`
  * and `http.nonProxyHosts` (the proxy), `javax.net.ssl.trustStore*` (the default trust store) and
  * `jdk.internal.httpclient.disableHostnameVerification` (which [[JdkHttpsTransport.default]] refuses to run with).
  * Operators control the proxy and the trust store, as for any JVM; neither can turn off https or the host policy.
  *
  * Redirects are followed here, one hop at a time (at most [[JdkHttpsTransport.MaxRedirects]]), because
  * `Redirect.NORMAL` cannot check each hop's host. A client that follows redirects itself is refused at construction.
  * Only a 200 response is a success, with no content encoding. No Authorization or cookie header is ever sent. Nothing
  * but a fatal error escapes `get`: every other exception becomes a [[TransportFailure]].
  */
final class JdkHttpsTransport(
    private[ephemery] val client: HttpClient,
    policy: SourcePolicy,
    clock: Clock,
    userAgent: String
) extends ReleaseTransport
    with AutoCloseable
    with Logger:
  import JdkHttpsTransport.*

  require(
    client.followRedirects() == HttpClient.Redirect.NEVER,
    "JdkHttpsTransport needs a client with Redirect.NEVER; it checks each hop itself"
  )

  /** Releases the client's connections and selector thread (JDK 21+). */
  def close(): Unit = client.close()

  def get(
      url: URI,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String] = None
  ): Either[TransportFailure, FetchedBody] =
    val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, WatchdogThreadName)
      t.setDaemon(true)
      t
    }
    try hop(url, 0, maxBytes, deadline, accept, watchdog)
    catch case NonFatal(e) => Left(classify(e))
    finally
      watchdog.shutdownNow()
      try watchdog.awaitTermination(1, TimeUnit.SECONDS)
      catch case _: InterruptedException => Thread.currentThread().interrupt()

  private def remaining(deadline: Instant): Duration = Duration.between(clock.instant(), deadline)

  private def checkUri(uri: URI): Either[TransportFailure, String] =
    val scheme = Option(uri.getScheme).getOrElse("").toLowerCase(Locale.ROOT)
    val host = Option(uri.getHost).getOrElse("")
    if scheme != "https" then Left(TransportFailure.NotHttps(scheme))
    else if uri.getUserInfo != null then Left(TransportFailure.BadRedirect("credentials in the URL"))
    else if host.isEmpty then Left(TransportFailure.BadRedirect("no host"))
    else if !policy.permits(host) then Left(TransportFailure.HostNotAllowed(host))
    else if !policy.permitsPort(uri.getPort) then Left(TransportFailure.PortNotAllowed(uri.getPort))
    else Right(host)

  private def hop(
      uri: URI,
      redirects: Int,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String],
      watchdog: ScheduledExecutorService
  ): Either[TransportFailure, FetchedBody] =
    checkUri(uri).flatMap { host =>
      val left = remaining(deadline)
      if left.isZero || left.isNegative then Left(TransportFailure.DeadlineExceeded)
      else
        log.debug(s"release fetch: hop ${redirects + 1} to https://$host")
        val builder = HttpRequest
          .newBuilder(uri)
          .version(HttpClient.Version.HTTP_1_1)
          .GET()
          .timeout(left)
          .header("User-Agent", userAgent)
        accept.foreach(a => builder.header("Accept", a))
        sendAndRead(builder.build(), left, redirects, maxBytes, deadline, accept, uri, watchdog)
    }

  /** Gives up on a request: cancels it and closes a response that arrives anyway. */
  private def abandon(future: java.util.concurrent.CompletableFuture[HttpResponse[InputStream]]): Unit =
    future.cancel(true)
    future.whenComplete { (r, _) =>
      if r != null then Try(r.body().close())
      ()
    }
    ()

  private def sendAndRead(
      request: HttpRequest,
      left: Duration,
      redirects: Int,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String],
      uri: URI,
      watchdog: ScheduledExecutorService
  ): Either[TransportFailure, FetchedBody] =
    val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
    val response: Either[TransportFailure, HttpResponse[InputStream]] =
      try Right(future.get(left.toNanos, TimeUnit.NANOSECONDS))
      catch
        case _: TimeoutException =>
          abandon(future)
          Left(TransportFailure.DeadlineExceeded)
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          abandon(future)
          Left(TransportFailure.Io("interrupted"))
        case NonFatal(e) =>
          abandon(future)
          Left(classify(e))
    response.flatMap { resp =>
      val body = resp.body()
      val status = resp.statusCode()
      if REDIRECTS.contains(status) then
        Try(body.close())
        if redirects >= MaxRedirects then Left(TransportFailure.TooManyRedirects(MaxRedirects))
        else
          resp.headers().firstValue("Location").toScala match
            case None => Left(TransportFailure.BadRedirect(s"HTTP $status without a Location"))
            case Some(loc) =>
              Try(uri.resolve(loc)).toEither match
                case Left(_)     => Left(TransportFailure.BadRedirect("unparseable Location"))
                case Right(next) => hop(next, redirects + 1, maxBytes, deadline, accept, watchdog)
      else if status != 200 then
        Try(body.close())
        Left(TransportFailure.HttpStatus(status))
      else
        val encoding = resp.headers().firstValue("Content-Encoding").toScala.map(_.trim)
        val declared = resp.headers().firstValueAsLong("Content-Length")
        if encoding.exists(e => e.nonEmpty && !e.equalsIgnoreCase("identity")) then
          Try(body.close())
          Left(TransportFailure.ContentEncoding(encoding.getOrElse("").take(32)))
        else if declared.isPresent && declared.getAsLong > maxBytes then
          Try(body.close())
          Left(TransportFailure.BodyTooLarge(maxBytes))
        else
          readBody(
            body,
            uri,
            status,
            maxBytes,
            deadline,
            if declared.isPresent then Some(declared.getAsLong) else None,
            watchdog
          )
    }

  private def readBody(
      body: InputStream,
      uri: URI,
      status: Int,
      maxBytes: Long,
      deadline: Instant,
      declared: Option[Long],
      watchdog: ScheduledExecutorService
  ): Either[TransportFailure, FetchedBody] =
    // HttpRequest.timeout covers only the headers; the watchdog closes the stream at the deadline.
    val fired = new AtomicBoolean(false)
    val timer = watchdog.schedule(
      new Runnable:
        def run(): Unit =
          fired.set(true)
          Try(body.close())
      ,
      math.max(0L, remaining(deadline).toNanos),
      TimeUnit.NANOSECONDS
    )
    try
      val out = new ByteArrayOutputStream()
      val buf = new Array[Byte](16384)
      var total = 0L
      var tooLarge = false
      var n = body.read(buf)
      while n >= 0 && !tooLarge do
        total += n
        if total > maxBytes then tooLarge = true // counted from bytes read, not from any header
        else
          out.write(buf, 0, n)
          n = body.read(buf)
      if tooLarge then Left(TransportFailure.BodyTooLarge(maxBytes))
      else if fired.get() then Left(TransportFailure.DeadlineExceeded)
      else if declared.exists(_ != total) then Left(TransportFailure.Io("short body"))
      else Right(FetchedBody(uri, status, out.toByteArray))
    catch
      case _: IOException if fired.get() => Left(TransportFailure.DeadlineExceeded)
      case NonFatal(e)                   => Left(classify(e))
    finally
      timer.cancel(false)
      Try(body.close())

object JdkHttpsTransport extends Logger:
  val MaxRedirects: Int = 5
  val ConnectTimeout: Duration = Duration.ofSeconds(10)
  val WatchdogThreadName: String = "release-transport-watchdog"
  private val REDIRECTS: Set[Int] = Set(301, 302, 303, 307, 308)
  private val HostnameVerificationProperty = "jdk.internal.httpclient.disableHostnameVerification"

  extension [A](o: java.util.Optional[A]) private def toScala: Option[A] = if o.isPresent then Some(o.get) else None

  /** Maps an exception to a failure. Timeouts (including connect timeouts) are `DeadlineExceeded`; anything else is
    * `Io` with the root cause's class name only, because messages can echo a URL with its query.
    */
  private[ephemery] def classify(e: Throwable): TransportFailure =
    val chain = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(16).toList
    if chain.exists(_.isInstanceOf[HttpTimeoutException]) then TransportFailure.DeadlineExceeded
    else TransportFailure.Io(chain.last.getClass.getName)

  /** The production transport: a 10 s connect timeout, no automatic redirects, HTTP/1.1, an explicit HTTPS endpoint
    * identity check, and the proxy from the JDK's `https.proxyHost` properties or else from `HTTPS_PROXY` / `NO_PROXY`.
    * `Left` when the proxy variable is unusable (credentials, a scheme the JDK cannot speak) or when hostname
    * verification has been switched off by property.
    *
    * `sslContext` and `props` are parameters for tests; production passes neither (the JDK default context and the
    * system properties).
    */
  def default(
      policy: SourcePolicy,
      version: String,
      env: Map[String, String] = sys.env,
      clock: Clock = Clock.systemUTC(),
      sslContext: Option[SSLContext] = None,
      props: String => Option[String] = k => Option(System.getProperty(k))
  ): Either[String, JdkHttpsTransport] =
    if props(HostnameVerificationProperty).exists(_.trim.toLowerCase(Locale.ROOT) != "false") then
      Left(
        s"refused: the system property $HostnameVerificationProperty switches off TLS hostname verification; unset it"
      )
    else
      val jdkProxyConfigured = props("https.proxyHost").exists(_.nonEmpty)
      val selector: Either[String, Option[ProxySelector]] =
        if jdkProxyConfigured then Right(None) else ProxyEnv.selector(env)
      selector.map { sel =>
        val source =
          if jdkProxyConfigured then "JDK properties (https.proxyHost)"
          else if sel.isDefined then "HTTPS_PROXY environment variable"
          else "none"
        val trust = if props("javax.net.ssl.trustStore").exists(_.nonEmpty) then "yes" else "no"
        log.info(s"release transport: proxy: $source; custom trust store: $trust")
        val ctx = sslContext.getOrElse(SSLContext.getDefault)
        val params = ctx.getDefaultSSLParameters
        params.setEndpointIdentificationAlgorithm("HTTPS")
        val b = HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(ConnectTimeout)
          .sslContext(ctx)
          .sslParameters(params)
        sel.foreach(b.proxy)
        new JdkHttpsTransport(b.build(), policy, clock, s"fukuii/$version")
      }

/** Maps `HTTPS_PROXY` / `https_proxy` and `NO_PROXY` / `no_proxy` to a `ProxySelector`. */
object ProxyEnv:

  /** `Right(None)` when no proxy variable is set. A proxy URL with credentials, or a scheme other than http, is
    * refused: the credential is never used and never echoed.
    */
  def selector(env: Map[String, String]): Either[String, Option[ProxySelector]] =
    // lower-case variables win over upper-case ones, as in curl
    val raw = env.get("https_proxy").orElse(env.get("HTTPS_PROXY")).map(_.trim).filter(_.nonEmpty)
    raw match
      case None => Right(None)
      case Some(value) =>
        val withScheme = if value.contains("://") then value else s"http://$value"
        Try(new URI(withScheme)).toOption match
          case None => Left("HTTPS_PROXY is not a valid URL (value not shown)")
          case Some(u) if u.getUserInfo != null =>
            Left("HTTPS_PROXY carries credentials; refused (credentials are not used or logged)")
          case Some(u) if !Option(u.getScheme).exists(_.equalsIgnoreCase("http")) =>
            Left(s"HTTPS_PROXY scheme '${u.getScheme}' is not supported; use an http:// proxy")
          case Some(u) if u.getHost == null || u.getHost.isEmpty =>
            Left("HTTPS_PROXY has no host")
          case Some(u) =>
            val port = if u.getPort == -1 then 80 else u.getPort
            val noProxy = env.get("no_proxy").orElse(env.get("NO_PROXY")).map(parseNoProxy).getOrElse(Nil)
            val host = u.getHost.stripPrefix("[").stripSuffix("]") // IPv6 literals come with brackets
            Right(Some(new EnvProxySelector(host, port, noProxy)))

  def parseNoProxy(s: String): List[String] =
    s.split(',').iterator.map(e => stripPort(e.trim.toLowerCase(Locale.ROOT))).filter(_.nonEmpty).toList

  /** `host:port` becomes `host`. An entry with several colons (an IPv6 literal) is left alone. */
  private def stripPort(e: String): String =
    val first = e.indexOf(':')
    if first >= 0 && first == e.lastIndexOf(':') then e.substring(0, first) else e

  /** `*` bypasses everything; `example.com` and `.example.com` match the host and its subdomains (a `:port` suffix is
    * ignored). IPv6 and CIDR entries are not supported.
    */
  def bypasses(noProxy: List[String], host: String): Boolean =
    val h = host.toLowerCase(Locale.ROOT)
    noProxy.exists { e =>
      e == "*" || {
        val bare = e.stripPrefix("*").stripPrefix(".")
        h == bare || h.endsWith("." + bare)
      }
    }

  final private class EnvProxySelector(host: String, port: Int, noProxy: List[String]) extends ProxySelector:
    private val proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
    override def select(uri: URI): java.util.List[Proxy] =
      val h = Option(uri.getHost).getOrElse("")
      if bypasses(noProxy, h) then java.util.List.of(Proxy.NO_PROXY) else java.util.List.of(proxy)
    override def connectFailed(uri: URI, sa: SocketAddress, ioe: IOException): Unit = ()
    // never print the address in logs
    override def toString: String = "EnvProxySelector"
