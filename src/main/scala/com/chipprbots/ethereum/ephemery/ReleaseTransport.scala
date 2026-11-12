package com.chipprbots.ethereum.ephemery

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Try

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
  case HttpStatus(code: Int) extends TransportFailure(s"the server answered HTTP $code")
  case Io(why: String) extends TransportFailure(s"I/O failure: $why")

/** A complete, size-capped response body. `finalUri` is the URI after redirects; callers log only its host. */
final case class FetchedBody(finalUri: URI, status: Int, bytes: Array[Byte]):
  def finalHost: String = Option(finalUri.getHost).getOrElse("")

/** Which hosts a redirect chain may visit. There is no configuration for this; a caller picks one in code. */
sealed trait SourcePolicy:
  def permits(host: String): Boolean

object SourcePolicy:
  /** Exact hosts, or `*.suffix` for any proper subdomain of `suffix`. Matching ignores case. */
  final case class AllowList(patterns: Seq[String]) extends SourcePolicy:
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
  * `SSLContext`, an allow-list for 127.0.0.1 and a fixed clock. No configuration key and no system property reaches
  * this class: HTTPS and the host policy cannot be switched off from outside the code that builds it.
  *
  * Redirects are followed here, one hop at a time (at most [[JdkHttpsTransport.MaxRedirects]]), because
  * `Redirect.NORMAL` cannot check each hop's host. A client that follows redirects itself is refused at construction.
  * No Authorization or cookie header is ever sent.
  */
final class JdkHttpsTransport(
    client: HttpClient,
    policy: SourcePolicy,
    clock: Clock,
    userAgent: String
) extends ReleaseTransport
    with Logger:
  import JdkHttpsTransport.*

  require(
    client.followRedirects() == HttpClient.Redirect.NEVER,
    "JdkHttpsTransport needs a client with Redirect.NEVER; it checks each hop itself"
  )

  def get(
      url: URI,
      maxBytes: Long,
      deadline: Instant,
      accept: Option[String] = None
  ): Either[TransportFailure, FetchedBody] =
    val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "release-transport-watchdog")
      t.setDaemon(true)
      t
    }
    try hop(url, 0, maxBytes, deadline, accept, watchdog)
    finally watchdog.shutdownNow()

  private def remaining(deadline: Instant): Duration = Duration.between(clock.instant(), deadline)

  private def checkUri(uri: URI): Either[TransportFailure, String] =
    val scheme = Option(uri.getScheme).getOrElse("").toLowerCase(Locale.ROOT)
    val host = Option(uri.getHost).getOrElse("")
    if scheme != "https" then Left(TransportFailure.NotHttps(scheme))
    else if uri.getUserInfo != null then Left(TransportFailure.BadRedirect("credentials in the URL"))
    else if host.isEmpty then Left(TransportFailure.BadRedirect("no host"))
    else if !policy.permits(host) then Left(TransportFailure.HostNotAllowed(host))
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
          .GET()
          .timeout(left)
          .header("User-Agent", userAgent)
        accept.foreach(a => builder.header("Accept", a))
        sendAndRead(builder.build(), left, redirects, maxBytes, deadline, accept, uri, watchdog)
    }

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
    val response =
      try Right(future.get(left.toNanos, TimeUnit.NANOSECONDS))
      catch
        case _: TimeoutException =>
          future.cancel(true)
          Left(TransportFailure.DeadlineExceeded)
        case e @ (_: ExecutionException | _: CompletionException) =>
          Left(TransportFailure.Io(describe(e)))
        case _: InterruptedException =>
          Thread.currentThread().interrupt()
          future.cancel(true)
          Left(TransportFailure.Io("interrupted"))
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
      else if status / 100 != 2 then
        Try(body.close())
        Left(TransportFailure.HttpStatus(status))
      else
        val declared = resp.headers().firstValueAsLong("Content-Length")
        if declared.isPresent && declared.getAsLong > maxBytes then
          Try(body.close())
          Left(TransportFailure.BodyTooLarge(maxBytes))
        else
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
            else Right(FetchedBody(uri, status, out.toByteArray))
          catch
            case _: IOException if fired.get() => Left(TransportFailure.DeadlineExceeded)
            case e: IOException                => Left(TransportFailure.Io(describe(e)))
          finally
            timer.cancel(false)
            Try(body.close())
    }

  /** The exception class names only: messages can echo a URL with its query. */
  private def describe(e: Throwable): String =
    val root = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).toList.last
    root.getClass.getSimpleName

object JdkHttpsTransport:
  val MaxRedirects: Int = 5
  val ConnectTimeout: Duration = Duration.ofSeconds(10)
  private val REDIRECTS: Set[Int] = Set(301, 302, 303, 307, 308)

  extension [A](o: java.util.Optional[A]) private def toScala: Option[A] = if o.isPresent then Some(o.get) else None

  /** The production transport: the JDK's default TLS context, a 10 s connect timeout, no automatic redirects, and the
    * proxy from the JDK's `https.proxyHost` properties or else from `HTTPS_PROXY` / `NO_PROXY`. `Left` when the proxy
    * variable is unusable (credentials, a scheme the JDK cannot speak).
    */
  def default(
      policy: SourcePolicy,
      version: String,
      env: Map[String, String] = sys.env,
      clock: Clock = Clock.systemUTC()
  ): Either[String, JdkHttpsTransport] =
    val jdkProxyConfigured = Option(System.getProperty("https.proxyHost")).exists(_.nonEmpty)
    val selector: Either[String, Option[ProxySelector]] =
      if jdkProxyConfigured then Right(None) else ProxyEnv.selector(env)
    selector.map { sel =>
      val b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(ConnectTimeout)
      sel.foreach(b.proxy)
      new JdkHttpsTransport(b.build(), policy, clock, s"fukuii/$version")
    }

/** Maps `HTTPS_PROXY` / `https_proxy` and `NO_PROXY` / `no_proxy` to a `ProxySelector`. */
object ProxyEnv:

  /** `Right(None)` when no proxy variable is set. A proxy URL with credentials, or a scheme other than http, is
    * refused: the credential is never used and never echoed.
    */
  def selector(env: Map[String, String]): Either[String, Option[ProxySelector]] =
    val raw = env.get("HTTPS_PROXY").orElse(env.get("https_proxy")).map(_.trim).filter(_.nonEmpty)
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
            val noProxy = env.get("NO_PROXY").orElse(env.get("no_proxy")).map(parseNoProxy).getOrElse(Nil)
            Right(Some(new EnvProxySelector(u.getHost, port, noProxy)))

  def parseNoProxy(s: String): List[String] =
    s.split(',').iterator.map(_.trim.toLowerCase(Locale.ROOT)).filter(_.nonEmpty).toList

  /** `*` bypasses everything; `example.com` and `.example.com` match the host and its subdomains. No CIDR. */
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
