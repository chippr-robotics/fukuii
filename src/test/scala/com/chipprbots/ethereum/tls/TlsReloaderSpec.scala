package com.chipprbots.ethereum.tls

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import javax.net.ssl.SSLSocket

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Logger as LogbackLogger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-030, FR-031, US4 scenarios 1 and 2: a running listener takes a valid replacement of its keystore for new
  * handshakes, keeps an open connection working across it, and keeps its material through a corrupt one.
  */
class TlsReloaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("tls-reloader-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), MtlsTestSupport.Wait)
    TlsTestSupport.deleteTree(dir)

  /** A listener's context on profile `name`, its keystore holding a leaf with CN `cn`. */
  private def listener(name: String, cn: String): TlsServerContext =
    val store = pki.keystore(s"$name-server", Seq("rpc" -> pki.leaf(cn)), "pw")
    val config = TlsTestSupport.config(
      TlsTestSupport.profile(name, store),
      s"""tls { enabled = true, profile = "$name" }"""
    )
    TlsServerEngine.rpcHttp(config, new SecureRandom()).fold(e => fail(e.message), identity)

  private def rewrite(name: String, cn: String): Unit =
    pki.keystore(s"$name-server", Seq("rpc" -> pki.leaf(cn)), "pw")

  private def errors[A](body: => A): (A, List[String]) =
    val logger = LoggerFactory.getLogger("com.chipprbots.ethereum.tls.TlsReloader").asInstanceOf[LogbackLogger]
    val appender = com.chipprbots.ethereum.testing.LogCapture.open(logger.getName)
    try (body, appender.list.asScala.toList.map(_.getFormattedMessage))
    finally appender.close()

  "A reload" should "serve a valid replacement to new handshakes and count it (scenario 1)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val ctx = listener("swap", "node-a")
    val reloader = new TlsReloader(scheduled = false)
    reloader.register("rpc-http", ctx)
    MtlsTestSupport.withServerContext(ctx) { port =>
      MtlsTestSupport.serverSubject(pki.clientContext(), port) shouldBe "CN=node-a"
      val before = TlsReloadMetrics.reloads("swap").count()
      reloader.poll()
      TlsReloadMetrics.reloads("swap").count() shouldBe before // nothing changed, nothing reloaded
      rewrite("swap", "node-b")
      reloader.poll()
      TlsReloadMetrics.reloads("swap").count() shouldBe before + 1
      MtlsTestSupport.serverSubject(pki.clientContext(), port) shouldBe "CN=node-b"
    }
  }

  it should "leave a connection opened before it working (FR-030)" taggedAs (UnitTest, RPCTest) in {
    val ctx = listener("keepalive", "node-a")
    val reloader = new TlsReloader(scheduled = false)
    reloader.register("rpc-http", ctx)
    MtlsTestSupport.withServerContext(ctx) { port =>
      val socket = pki.clientContext().getSocketFactory.createSocket("localhost", port).asInstanceOf[SSLSocket]
      try
        socket.setSoTimeout(MtlsTestSupport.Wait.toMillis.toInt)
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
        def get(): String =
          socket.getOutputStream.write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
          socket.getOutputStream.flush()
          val status = in.readLine()
          // drain the headers and the 2-byte body ("ok")
          Iterator.continually(in.readLine()).takeWhile(l => l != null && l.nonEmpty).foreach(_ => ())
          in.read(new Array[Char](2))
          status
        get() should startWith("HTTP/1.1 200")
        rewrite("keepalive", "node-b")
        reloader.poll()
        // the same connection, on the keys it negotiated with node-a
        get() should startWith("HTTP/1.1 200")
        socket.getSession.getPeerCertificates.head
          .asInstanceOf[java.security.cert.X509Certificate]
          .getSubjectX500Principal
          .getName shouldBe "CN=node-a"
      finally socket.close()
      MtlsTestSupport.serverSubject(pki.clientContext(), port) shouldBe "CN=node-b"
    }
  }

  it should "keep the material in service through a corrupt replacement, log it and count it (scenario 2, FR-031)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val ctx = listener("corrupt", "node-a")
    val reloader = new TlsReloader(scheduled = false)
    reloader.register("rpc-http", ctx)
    MtlsTestSupport.withServerContext(ctx) { port =>
      val before = TlsReloadMetrics.failures("corrupt").count()
      Files.writeString(dir.resolve("corrupt-server.p12"), "not a keystore")
      val (_, lines) = errors(reloader.poll())
      TlsReloadMetrics.failures("corrupt").count() shouldBe before + 1
      lines.exists(l => l.startsWith("TLS profile corrupt reload failed:") && l.contains("corrupt-server.p12")) shouldBe
        true
      MtlsTestSupport.serverSubject(pki.clientContext(), port) shouldBe "CN=node-a"
      // the same bad file is not retried every pass; a good one is taken
      reloader.poll()
      TlsReloadMetrics.failures("corrupt").count() shouldBe before + 1
      rewrite("corrupt", "node-c")
      reloader.poll()
      MtlsTestSupport.serverSubject(pki.clientContext(), port) shouldBe "CN=node-c"
    }
  }

  "A listener that binds again" should "replace its registration rather than add one" taggedAs (UnitTest, RPCTest) in {
    val first = listener("rebind", "node-a")
    val second = listener("rebind", "node-a")
    val reloader = new TlsReloader(scheduled = false)
    reloader.register("rpc-http", first)
    reloader.register("rpc-http", second)
    val before = TlsReloadMetrics.reloads("rebind").count()
    rewrite("rebind", "node-b")
    reloader.poll()
    TlsReloadMetrics.reloads("rebind").count() shouldBe before + 1
    second.material.leaf.get.getSubjectX500Principal.getName shouldBe "CN=node-b"
    first.material.leaf.get.getSubjectX500Principal.getName shouldBe "CN=node-a"
  }
