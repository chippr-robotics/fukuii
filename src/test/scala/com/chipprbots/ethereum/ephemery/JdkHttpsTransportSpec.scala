package com.chipprbots.ethereum.ephemery

import java.io.InputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

class JdkHttpsTransportSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  // A fixed clock: the deadline arithmetic is exact and the suite never reads the system clock.
  private val Now = Instant.parse("2026-11-12T00:00:00Z")
  private val fixedClock = Clock.fixed(Now, ZoneOffset.UTC)
  private val farDeadline = Now.plusSeconds(60)

  /** A clock the server handlers advance, so a budget visibly shrinks between hops. */
  final private class TestClock(start: Instant) extends Clock:
    private val now = new AtomicReference(start)
    def advance(d: java.time.Duration): Unit = now.updateAndGet(_.plus(d))
    override def getZone: java.time.ZoneId = ZoneOffset.UTC
    override def withZone(z: java.time.ZoneId): Clock = this
    override def instant(): Instant = now.get

  private val created = new ConcurrentLinkedQueue[JdkHttpsTransport]()
  private val okHits = new AtomicInteger(0)
  @volatile private var stallInBody = new CountDownLatch(1)
  @volatile private var hopClock: TestClock = new TestClock(Now)

  private val keyPair =
    val g = KeyPairGenerator.getInstance("RSA")
    g.initialize(2048)
    g.generateKeyPair()

  private val cert: X509Certificate =
    val name = new X500Name("CN=localhost")
    val san = new GeneralNames(
      Array(new GeneralName(GeneralName.dNSName, "localhost"), new GeneralName(GeneralName.iPAddress, "127.0.0.1"))
    )
    val builder = new JcaX509v3CertificateBuilder(
      name,
      BigInteger.valueOf(1L),
      new Date(0L),
      new Date(4102444800000L), // 2100-01-01
      name,
      keyPair.getPublic
    )
    builder.addExtension(Extension.subjectAlternativeName, false, san)
    val signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate)
    new JcaX509CertificateConverter().getCertificate(builder.build(signer))

  private val password = "changeit".toCharArray

  private val serverContext: SSLContext =
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry("server", keyPair.getPrivate, password, Array(cert))
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, password)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, null)
    ctx

  private val clientContext: SSLContext =
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setCertificateEntry("server", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, null)
    ctx

  private val release = new CountDownLatch(1)
  private val pool = Executors.newFixedThreadPool(8)
  private val server: HttpsServer = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
  private def port: Int = server.getAddress.getPort
  private def base: String = s"https://localhost:$port"

  private def reply(ex: HttpExchange, status: Int, body: Array[Byte]): Unit =
    ex.sendResponseHeaders(status, if body.isEmpty then -1L else body.length.toLong)
    if body.nonEmpty then ex.getResponseBody.write(body)
    ex.close()

  private def redirect(ex: HttpExchange, location: String): Unit =
    ex.getResponseHeaders.add("Location", location)
    reply(ex, 302, Array.emptyByteArray)

  private def redirectWith(ex: HttpExchange, status: Int, location: String): Unit =
    ex.getResponseHeaders.add("Location", location)
    reply(ex, status, Array.emptyByteArray)

  private def route(path: String)(h: HttpExchange => Unit): Unit =
    server.createContext(
      path,
      (ex: HttpExchange) =>
        try h(ex)
        catch case _: java.io.IOException => ex.close()
    )

  override def beforeAll(): Unit =
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext))
    server.setExecutor(pool)
    route("/ok") { ex =>
      okHits.incrementAndGet()
      reply(ex, 200, "hello".getBytes("UTF-8"))
    }
    route("/ua")(ex =>
      reply(
        ex,
        200,
        s"${ex.getRequestHeaders.getFirst("User-Agent")}|${ex.getRequestHeaders.getFirst("Accept")}".getBytes("UTF-8")
      )
    )
    route("/missing")(ex => reply(ex, 404, "no".getBytes("UTF-8")))
    route("/hop/") { ex =>
      val n = ex.getRequestURI.getPath.stripPrefix("/hop/").toInt
      if n == 0 then reply(ex, 200, "arrived".getBytes("UTF-8")) else redirect(ex, s"/hop/${n - 1}")
    }
    route("/to-http")(ex => redirect(ex, s"http://localhost:$port/ok"))
    route("/to-127")(ex => redirect(ex, s"https://127.0.0.1:$port/ok"))
    route("/to-userinfo")(ex => redirect(ex, s"https://user:hunter2@localhost:$port/ok"))
    route("/no-location")(ex => reply(ex, 302, Array.emptyByteArray))
    route("/signed")(ex => redirect(ex, s"$base/ok?X-Amz-Signature=SECRETSIG"))
    route("/to-mirror-signed")(ex => redirect(ex, s"https://127.0.0.1:$port/ok?X-Amz-Signature=SECRETSIG"))
    route("/big")(ex => reply(ex, 200, new Array[Byte](10000)))
    route("/chunked-big") { ex =>
      ex.sendResponseHeaders(200, 0L) // chunked: no Content-Length to trust
      val out = ex.getResponseBody
      (0 until 10).foreach(_ => out.write(new Array[Byte](1000)))
      ex.close()
    }
    route("/stall") { ex =>
      ex.sendResponseHeaders(200, 100000L)
      ex.getResponseBody.write(new Array[Byte](10))
      ex.getResponseBody.flush()
      stallInBody.countDown() // the headers and the first 10 bytes are out: the client is in the body phase
      release.await(30, TimeUnit.SECONDS)
      ex.close()
    }
    route("/slow-headers") { ex =>
      release.await(30, TimeUnit.SECONDS)
      ex.close()
    }
    route("/code/") { ex =>
      redirectWith(ex, ex.getRequestURI.getPath.stripPrefix("/code/").toInt, "/ok")
    }
    route("/rel")(ex => redirect(ex, "ok"))
    route("/slashes")(ex => redirect(ex, s"//127.0.0.1:$port/ok"))
    route("/slow-redirect") { ex =>
      hopClock.advance(java.time.Duration.ofSeconds(10))
      redirect(ex, "/ok")
    }
    route("/nocontent")(ex => reply(ex, 204, Array.emptyByteArray))
    route("/partial")(ex => reply(ex, 206, "part".getBytes("UTF-8")))
    route("/encoded") { ex =>
      ex.getResponseHeaders.add("Content-Encoding", "gzip")
      reply(ex, 200, "x".getBytes("UTF-8"))
    }
    route("/short") { ex =>
      ex.sendResponseHeaders(200, 100L)
      ex.getResponseBody.write(new Array[Byte](10))
      ex.close()
    }
    server.start()

  override def afterAll(): Unit =
    release.countDown()
    server.stop(0) // drops the stalled exchanges, so closing a client cannot wait on them
    created.asScala.foreach(_.close())
    pool.shutdownNow()

  private def transport(
      policy: SourcePolicy,
      ssl: SSLContext = clientContext,
      ua: String = "fukuii/9.9.9-test",
      clock: Clock = fixedClock
  ): JdkHttpsTransport =
    val client = HttpClient
      .newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .sslContext(ssl)
      .followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(java.time.Duration.ofSeconds(10))
      .build()
    val t = new JdkHttpsTransport(client, policy, clock, ua)
    created.add(t)
    t

  private def localhostOnly = SourcePolicy.AllowList(Seq("localhost"), Set(port))

  private def get(t: JdkHttpsTransport, path: String, maxBytes: Long = 1 << 20, deadline: Instant = farDeadline) =
    t.get(new URI(s"$base$path"), maxBytes, deadline)

  private def failure(r: Either[TransportFailure, FetchedBody]): TransportFailure =
    withClue(s"expected a failure, got ${r.map(_.status)}: ")(r.isLeft shouldBe true)
    r.left.getOrElse(fail())

  private def withLog[A](f: => A): (A, List[String]) =
    val logger = LoggerFactory.getLogger(classOf[JdkHttpsTransport]).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    val result =
      try f
      finally
        logger.setLevel(previous)
        logger.detachAppender(appender)
    (result, appender.list.asScala.toList.map(_.getFormattedMessage))

  "JdkHttpsTransport" should "fetch a body over https from an allow-listed host" taggedAs UnitTest in {
    val r = get(transport(localhostOnly), "/ok")
    val body = r.getOrElse(fail(s"$r"))
    new String(body.bytes, "UTF-8") shouldBe "hello"
    body.status shouldBe 200
    body.finalHost shouldBe "localhost"
  }

  it should "refuse an http: start URL without connecting" taggedAs UnitTest in {
    val r = transport(SourcePolicy.Mirror).get(new URI(s"http://localhost:$port/ok"), 1000, farDeadline)
    failure(r) shouldBe TransportFailure.NotHttps("http")
    failure(r).message should include("only https")
    failure(transport(SourcePolicy.Mirror).get(new URI("ftp://localhost/x"), 1000, farDeadline)) shouldBe
      TransportFailure.NotHttps("ftp")
    failure(transport(SourcePolicy.Mirror).get(new URI("/relative"), 1000, farDeadline)) shouldBe
      TransportFailure.NotHttps("")
  }

  it should "refuse a redirect hop to http, even under the mirror policy" taggedAs UnitTest in {
    failure(get(transport(localhostOnly), "/to-http")) shouldBe TransportFailure.NotHttps("http")
    failure(get(transport(SourcePolicy.Mirror), "/to-http")) shouldBe TransportFailure.NotHttps("http")
  }

  it should "refuse a hop to a host off the allow-list, and accept it under the mirror policy" taggedAs UnitTest in {
    val refused = failure(get(transport(localhostOnly), "/to-127"))
    refused shouldBe TransportFailure.HostNotAllowed("127.0.0.1")
    refused.message should include("not on the allow-list")
    val mirror = get(transport(SourcePolicy.Mirror), "/to-127").getOrElse(fail())
    new String(mirror.bytes, "UTF-8") shouldBe "hello"
    mirror.finalHost shouldBe "127.0.0.1"
    // the start URL is checked too
    failure(get(transport(SourcePolicy.AllowList(Seq("example.org"))), "/ok")) shouldBe
      TransportFailure.HostNotAllowed("localhost")
  }

  it should "match the default GitHub allow-list on whole host labels only" taggedAs UnitTest in {
    val p = SourcePolicy.DefaultGithub
    Seq(
      "github.com",
      "api.github.com",
      "release-assets.githubusercontent.com",
      "objects.githubusercontent.com",
      "GitHub.com"
    ).foreach(h => withClue(h)(p.permits(h) shouldBe true))
    Seq(
      "githubusercontent.com",
      "evilgithubusercontent.com",
      "github.com.evil.example",
      "evil.example",
      "notgithub.com",
      ""
    ).foreach(h => withClue(h)(p.permits(h) shouldBe false))
  }

  it should "follow five redirects and refuse the sixth" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    new String(get(t, "/hop/5").getOrElse(fail()).bytes, "UTF-8") shouldBe "arrived"
    val r = failure(get(t, "/hop/6"))
    r shouldBe TransportFailure.TooManyRedirects(5)
    r.message should include("more than 5 redirects")
  }

  it should "refuse a redirect with no Location and one with credentials in the URL" taggedAs UnitTest in {
    failure(get(transport(SourcePolicy.Mirror), "/no-location")) shouldBe a[TransportFailure.BadRedirect]
    val withUser = failure(get(transport(SourcePolicy.Mirror), "/to-userinfo"))
    withUser shouldBe a[TransportFailure.BadRedirect]
    (withUser.message should not).include("hunter2")
  }

  it should "stop at the body cap, by content length and by bytes read" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    failure(get(t, "/big", maxBytes = 1000)) shouldBe TransportFailure.BodyTooLarge(1000)
    // chunked: the server sends no length, so only the bytes read can stop it
    failure(get(t, "/chunked-big", maxBytes = 4000)) shouldBe TransportFailure.BodyTooLarge(4000)
    get(t, "/chunked-big", maxBytes = 10000).map(_.bytes.length) shouldBe Right(10000)
    get(t, "/chunked-big", maxBytes = 9999).isLeft shouldBe true
  }

  it should "honour the body deadline against a server stalled on a latch, after reaching the body phase" taggedAs
    UnitTest in {
      val t = transport(localhostOnly)
      get(t, "/ok").isRight shouldBe true // warm the connection, so the 3 s budget is spent in the body
      stallInBody = new CountDownLatch(1)
      val r = failure(get(t, "/stall", deadline = Now.plusSeconds(3)))
      // the server only counts down after it has sent the headers and the first 10 bytes
      stallInBody.getCount shouldBe 0L
      r shouldBe TransportFailure.DeadlineExceeded
    }

  it should "return Io(interrupted) and keep the interrupt flag when the calling thread is interrupted in the body" taggedAs
    UnitTest in {
      val t = transport(localhostOnly)
      get(t, "/ok").isRight shouldBe true
      stallInBody = new CountDownLatch(1)
      val result = new AtomicReference[Either[TransportFailure, FetchedBody]]()
      val flag = new AtomicReference[java.lang.Boolean]()
      val worker = new Thread(() =>
        result.set(get(t, "/stall", deadline = Now.plusSeconds(60)))
        flag.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
      )
      worker.start()
      stallInBody.await(10, TimeUnit.SECONDS) shouldBe true // the worker is blocked reading the body
      worker.interrupt()
      worker.join(10000)
      worker.isAlive shouldBe false
      result.get().left.map(identity) shouldBe Left(TransportFailure.Io("interrupted"))
      flag.get() shouldBe true
    }

  it should "map a timeout while waiting for the headers to DeadlineExceeded, never Io" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    failure(get(t, "/slow-headers", deadline = Now.plusSeconds(2))) shouldBe TransportFailure.DeadlineExceeded
  }

  it should "give the second hop a smaller budget: the clock advances in the first hop's handler" taggedAs UnitTest in {
    hopClock = new TestClock(Now)
    val t = transport(localhostOnly, clock = hopClock)
    val before = okHits.get()
    failure(get(t, "/slow-redirect", deadline = Now.plusSeconds(5))) shouldBe TransportFailure.DeadlineExceeded
    okHits.get() shouldBe before // the second hop was never sent
  }

  it should "refuse a deadline that has already passed without sending a request" taggedAs UnitTest in {
    failure(get(transport(localhostOnly), "/ok", deadline = Now.minusSeconds(1))) shouldBe
      TransportFailure.DeadlineExceeded
  }

  it should "report an HTTP error status" taggedAs UnitTest in {
    failure(get(transport(localhostOnly), "/missing?token=SECRETTOKEN")) shouldBe TransportFailure.HttpStatus(404)
  }

  it should "send User-Agent fukuii/<version> and the Accept header on request, and nothing else identifying" taggedAs
    UnitTest in {
      val t = transport(localhostOnly, ua = "fukuii/1.2.3")
      val plain = get(t, "/ua").getOrElse(fail())
      new String(plain.bytes, "UTF-8") shouldBe "fukuii/1.2.3|null"
      val api = t.get(new URI(s"$base/ua"), 1000, farDeadline, Some("application/vnd.github+json")).getOrElse(fail())
      new String(api.bytes, "UTF-8") shouldBe "fukuii/1.2.3|application/vnd.github+json"
    }

  it should "not trust a certificate the client does not know (TLS verification is on)" taggedAs UnitTest in {
    val defaultCtx = SSLContext.getDefault
    val r = failure(get(transport(localhostOnly, ssl = defaultCtx), "/ok"))
    r shouldBe a[TransportFailure.Io]
  }

  it should "refuse a client that follows redirects itself" taggedAs UnitTest in {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    an[IllegalArgumentException] should be thrownBy new JdkHttpsTransport(client, SourcePolicy.Mirror, fixedClock, "x")
  }

  it should "never log a query string, a signed redirect URL or a failure's URL" taggedAs UnitTest in {
    val (results, logs) = withLog {
      val a = get(transport(localhostOnly), "/signed?token=SECRETTOKEN")
      val b = get(transport(SourcePolicy.Mirror), "/to-mirror-signed?token=SECRETTOKEN")
      val c = get(transport(localhostOnly), "/missing?token=SECRETTOKEN")
      val d = get(transport(localhostOnly), "/to-http?token=SECRETTOKEN")
      List(a, b, c, d)
    }
    results.take(2).foreach(_.isRight shouldBe true)
    logs should not be empty // the hop lines exist, so the absence below means something
    logs.foreach { l =>
      (l should not).include("SECRET")
      (l should not).include("?")
      (l should not).include("Signature")
    }
    logs.exists(_.contains("https://localhost")) shouldBe true
    results.flatMap(_.left.toOption).foreach { f =>
      (f.message should not).include("SECRET")
      (f.message should not).include("?")
    }
  }

  it should "classify exceptions: timeouts are DeadlineExceeded, anything else Io with the class name" taggedAs
    UnitTest in {
      import JdkHttpsTransport.classify
      classify(new HttpTimeoutException("x")) shouldBe TransportFailure.DeadlineExceeded
      classify(new HttpConnectTimeoutException("x")) shouldBe TransportFailure.DeadlineExceeded
      classify(new ExecutionException(new HttpTimeoutException("x"))) shouldBe TransportFailure.DeadlineExceeded
      classify(new ExecutionException(new IllegalStateException("https://h/p?secret=1"))) shouldBe
        TransportFailure.Io("java.lang.IllegalStateException")
      classify(new java.util.concurrent.CancellationException("c")) shouldBe
        TransportFailure.Io("java.util.concurrent.CancellationException")
      val anon = new RuntimeException("q") {}
      classify(anon) shouldBe a[TransportFailure.Io]
      (classify(anon).message should not).include("q")
      classify(anon).asInstanceOf[TransportFailure.Io].why should not be empty
    }

  it should "return a runtime exception from the JDK as Io, not throw" taggedAs UnitTest in {
    val r = transport(SourcePolicy.Mirror).get(new URI("https://localhost:99999/ok"), 1000, farDeadline)
    failure(r) shouldBe a[TransportFailure.Io]
  }

  it should "refuse a start URL with userinfo and a //host redirect off the policy" taggedAs UnitTest in {
    val withUser = transport(localhostOnly).get(new URI(s"https://user:hunter2@localhost:$port/ok"), 1000, farDeadline)
    failure(withUser) shouldBe a[TransportFailure.BadRedirect]
    (failure(withUser).message should not).include("hunter2")
    failure(get(transport(localhostOnly), "/slashes")) shouldBe TransportFailure.HostNotAllowed("127.0.0.1")
    get(transport(SourcePolicy.Mirror), "/slashes").map(_.finalHost) shouldBe Right("127.0.0.1")
  }

  it should "follow 301, 303, 307 and 308, and a relative Location" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    Seq(301, 303, 307, 308).foreach { code =>
      withClue(s"HTTP $code: ")(get(t, s"/code/$code").map(b => new String(b.bytes, "UTF-8")) shouldBe Right("hello"))
    }
    get(t, "/rel").map(b => new String(b.bytes, "UTF-8")) shouldBe Right("hello")
  }

  it should "accept a body exactly at the cap and refuse one byte over" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    get(t, "/big", maxBytes = 10000).map(_.bytes.length) shouldBe Right(10000)
    failure(get(t, "/big", maxBytes = 9999)) shouldBe TransportFailure.BodyTooLarge(9999)
  }

  it should "refuse a body shorter than its Content-Length" taggedAs UnitTest in {
    failure(get(transport(localhostOnly), "/short")) shouldBe a[TransportFailure.Io]
  }

  it should "treat only 200 as success, and refuse a content encoding" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    failure(get(t, "/nocontent")) shouldBe TransportFailure.HttpStatus(204)
    failure(get(t, "/partial")) shouldBe TransportFailure.HttpStatus(206)
    val enc = failure(get(t, "/encoded"))
    enc shouldBe TransportFailure.ContentEncoding("gzip")
    enc.message should include("gzip")
  }

  it should "allow only port 443 under the allow-list" taggedAs UnitTest in {
    val t = transport(SourcePolicy.DefaultGithub)
    failure(t.get(new URI("https://github.com:8443/x"), 1000, farDeadline)) shouldBe TransportFailure.PortNotAllowed(
      8443
    )
    failure(t.get(new URI("https://x.githubusercontent.com:22/x"), 1000, farDeadline)) shouldBe
      TransportFailure.PortNotAllowed(22)
    SourcePolicy.DefaultGithub.permitsPort(-1) shouldBe true
    SourcePolicy.DefaultGithub.permitsPort(443) shouldBe true
    SourcePolicy.Mirror.permitsPort(8443) shouldBe true
  }

  it should "leave no watchdog thread behind after get returns" taggedAs UnitTest in {
    val t = transport(localhostOnly)
    get(t, "/ok").isRight shouldBe true
    failure(get(t, "/missing")) shouldBe TransportFailure.HttpStatus(404)
    failure(get(t, "/big", maxBytes = 10)) shouldBe TransportFailure.BodyTooLarge(10)
    val names = Thread.getAllStackTraces.keySet.asScala.toList.map(_.getName)
    names.count(_ == "release-transport-watchdog") shouldBe 0
  }

  it should "build the production client with HTTP/1.1 and an explicit HTTPS endpoint check" taggedAs UnitTest in {
    val t = JdkHttpsTransport
      .create(
        localhostOnly,
        "1",
        env = Map.empty,
        clock = fixedClock,
        sslContext = Some(clientContext),
        props = _ => None
      )
      .getOrElse(fail())
    created.add(t)
    t.client.version() shouldBe HttpClient.Version.HTTP_1_1
    t.client.followRedirects() shouldBe HttpClient.Redirect.NEVER
    t.client.sslParameters().getEndpointIdentificationAlgorithm shouldBe "HTTPS"
  }

  it should "refuse to build when hostname verification is switched off by a system property" taggedAs UnitTest in {
    val key = "jdk.internal.httpclient.disableHostnameVerification"
    Seq("true", "", "1").foreach { v =>
      val r = JdkHttpsTransport.create(SourcePolicy.DefaultGithub, "1", Map.empty, fixedClock, None, Map(key -> v).get)
      withClue(s"value '$v': ")(r.left.map(_.contains("hostname verification")) shouldBe Left(true))
    }
    // "false" is the JDK default and is fine
    val ok =
      JdkHttpsTransport.create(SourcePolicy.DefaultGithub, "1", Map.empty, fixedClock, None, Map(key -> "false").get)
    ok.isRight shouldBe true
    ok.foreach(_.close())
  }

  it should "log the proxy source and whether a trust store is set, never the address or the path" taggedAs UnitTest in {
    val (r, logs) = withLog {
      JdkHttpsTransport.create(
        SourcePolicy.DefaultGithub,
        "1",
        Map("HTTPS_PROXY" -> "http://proxy.hidden.example:3128"),
        fixedClock,
        None,
        Map("javax.net.ssl.trustStore" -> "/private/trust.jks").get
      )
    }
    r.foreach(_.close())
    val text = logs.mkString("\n")
    text should include("HTTPS_PROXY")
    text should include("trust store: yes")
    (text should not).include("hidden.example")
    (text should not).include("trust.jks")
    val (r2, logs2) =
      withLog(JdkHttpsTransport.create(SourcePolicy.DefaultGithub, "1", Map.empty, fixedClock, None, _ => None))
    r2.foreach(_.close())
    logs2.mkString("\n") should (include("proxy: none").and(include("trust store: no")))
    val (r3, logs3) = withLog(
      JdkHttpsTransport.create(
        SourcePolicy.DefaultGithub,
        "1",
        Map.empty,
        fixedClock,
        None,
        Map("https.proxyHost" -> "p.hidden.example").get
      )
    )
    r3.foreach(_.close())
    logs3.mkString("\n") should include("JDK properties")
    (logs3.mkString("\n") should not).include("hidden.example")
  }

  it should "tunnel through an HTTP proxy named by HTTPS_PROXY (CONNECT seen by the proxy)" taggedAs UnitTest in {
    val seen = new LinkedBlockingQueue[String]()
    val proxy = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    def line(in: InputStream): String =
      val sb = new StringBuilder
      var c = in.read()
      while c >= 0 && c != '\n' do
        if c != '\r' then sb.append(c.toChar)
        c = in.read()
      sb.toString
    def pipe(from: InputStream, to: java.io.OutputStream): Unit =
      try
        val buf = new Array[Byte](8192)
        var n = from.read(buf)
        while n >= 0 do
          to.write(buf, 0, n)
          to.flush()
          n = from.read(buf)
      catch case _: java.io.IOException => ()
    val acceptor = new Thread(() =>
      try
        while true do
          val client = proxy.accept()
          val handler = new Thread(() =>
            try
              val first = line(client.getInputStream)
              seen.add(first)
              var l = line(client.getInputStream)
              while l.nonEmpty do l = line(client.getInputStream)
              val upstream = new Socket("127.0.0.1", port)
              client.getOutputStream.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("US-ASCII"))
              client.getOutputStream.flush()
              val back = new Thread(() => pipe(upstream.getInputStream, client.getOutputStream))
              back.setDaemon(true)
              back.start()
              pipe(client.getInputStream, upstream.getOutputStream)
              upstream.close()
              client.close()
            catch case _: java.io.IOException => ()
          )
          handler.setDaemon(true)
          handler.start()
      catch case _: java.io.IOException => ()
    )
    acceptor.setDaemon(true)
    acceptor.start()
    try
      val t = JdkHttpsTransport
        .create(
          localhostOnly,
          "1",
          Map("HTTPS_PROXY" -> s"http://127.0.0.1:${proxy.getLocalPort}"),
          fixedClock,
          Some(clientContext),
          _ => None
        )
        .getOrElse(fail())
      created.add(t)
      val body = get(t, "/ok").getOrElse(fail())
      new String(body.bytes, "UTF-8") shouldBe "hello"
      Option(seen.poll(5, TimeUnit.SECONDS)).getOrElse("") shouldBe s"CONNECT localhost:$port HTTP/1.1"
    finally proxy.close()
  }

  // ── proxy environment ───────────────────────────────────────────────────────────────────────────────────────────

  private def select(env: Map[String, String], uri: String): Proxy =
    val sel = ProxyEnv.selector(env).getOrElse(fail()).getOrElse(fail("no selector"))
    sel.select(new URI(uri)).get(0)

  "ProxyEnv" should "map HTTPS_PROXY and NO_PROXY into a ProxySelector" taggedAs UnitTest in {
    val env = Map("HTTPS_PROXY" -> "http://proxy.example:3128", "NO_PROXY" -> "localhost, .corp.example,10.0.0.1")
    val p = select(env, "https://github.com/x")
    p.`type`() shouldBe Proxy.Type.HTTP
    val addr = p.address().asInstanceOf[InetSocketAddress]
    addr.getHostString shouldBe "proxy.example"
    addr.getPort shouldBe 3128
    select(env, "https://localhost/x") shouldBe Proxy.NO_PROXY
    select(env, "https://api.corp.example/x") shouldBe Proxy.NO_PROXY
    select(env, "https://corp.example/x") shouldBe Proxy.NO_PROXY
    select(env, "https://10.0.0.1/x") shouldBe Proxy.NO_PROXY
    select(env, "https://notcorp.example/x").`type`() shouldBe Proxy.Type.HTTP
    select(Map("HTTPS_PROXY" -> "http://p:1", "NO_PROXY" -> "*"), "https://github.com") shouldBe Proxy.NO_PROXY
  }

  it should "accept the lower-case names, a bare host:port and a default port" taggedAs UnitTest in {
    select(Map("https_proxy" -> "proxy.example:8080", "no_proxy" -> "skip.example"), "https://a.example")
      .address()
      .asInstanceOf[InetSocketAddress]
      .getPort shouldBe 8080
    select(Map("https_proxy" -> "http://proxy.example"), "https://a.example")
      .address()
      .asInstanceOf[InetSocketAddress]
      .getPort shouldBe 80
    select(Map("https_proxy" -> "http://p:1", "no_proxy" -> "skip.example"), "https://skip.example") shouldBe
      Proxy.NO_PROXY
  }

  it should "map to no selector when no proxy variable is set (the JDK default then applies)" taggedAs UnitTest in {
    ProxyEnv.selector(Map.empty) shouldBe Right(None)
    ProxyEnv.selector(Map("HTTPS_PROXY" -> "  ")) shouldBe Right(None)
    ProxyEnv.selector(Map("NO_PROXY" -> "x")) shouldBe Right(None)
  }

  it should "refuse a proxy URL with credentials, without echoing them" taggedAs UnitTest in {
    val r = ProxyEnv.selector(Map("HTTPS_PROXY" -> "http://alice:hunter2@proxy.example:3128"))
    r.isLeft shouldBe true
    val msg = r.left.getOrElse("")
    msg should include("credentials")
    (msg should not).include("hunter2")
    (msg should not).include("alice")
    val onlyUser = ProxyEnv.selector(Map("https_proxy" -> "http://alice@proxy.example:3128"))
    onlyUser.isLeft shouldBe true
    (onlyUser.left.getOrElse("") should not).include("alice")
    // the production factory refuses it too
    JdkHttpsTransport
      .default(SourcePolicy.DefaultGithub, "1", Map("HTTPS_PROXY" -> "http://a:b@p:1"))
      .left
      .map(_.contains("credentials")) shouldBe Left(true)
  }

  it should "refuse a proxy scheme the JDK cannot speak" taggedAs UnitTest in {
    ProxyEnv.selector(Map("HTTPS_PROXY" -> "socks5://p:1080")).isLeft shouldBe true
    ProxyEnv.selector(Map("HTTPS_PROXY" -> "https://p:443")).isLeft shouldBe true
  }

  it should "strip the brackets from an IPv6 proxy host" taggedAs UnitTest in {
    val p = select(Map("HTTPS_PROXY" -> "http://[::1]:3128"), "https://github.com/x")
    p.address().asInstanceOf[InetSocketAddress].getHostString shouldBe "::1"
    p.address().asInstanceOf[InetSocketAddress].getPort shouldBe 3128
  }

  it should "strip a :port from a NO_PROXY entry, and let lower-case variables win" taggedAs UnitTest in {
    val env = Map("HTTPS_PROXY" -> "http://p:1", "NO_PROXY" -> "skip.example:8443, .corp.example:443")
    select(env, "https://skip.example/x") shouldBe Proxy.NO_PROXY
    select(env, "https://a.corp.example/x") shouldBe Proxy.NO_PROXY
    val both = Map(
      "HTTPS_PROXY" -> "http://upper:1",
      "https_proxy" -> "http://lower:2",
      "NO_PROXY" -> "upper.example",
      "no_proxy" -> "lower.example"
    )
    select(both, "https://a.example").address().asInstanceOf[InetSocketAddress].getPort shouldBe 2
    select(both, "https://lower.example") shouldBe Proxy.NO_PROXY
    select(both, "https://upper.example").`type`() shouldBe Proxy.Type.HTTP
  }
