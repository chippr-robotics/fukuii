package com.chipprbots.ethereum.tls

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await
import scala.sys.process.*

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-062: `tls/gen-cert.sh ca|server|client` writes stores the node loads, and a client certificate it issues
  * passes a listener with `client-auth = need`. Cancelled where openssl or keytool is not installed.
  */
class GenCertScriptSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("gen-cert-script-spec")

  private val dir = TlsTestSupport.tempDir("gen-cert")
  private val script = Paths.get("tls/gen-cert.sh").toAbsolutePath

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), MtlsTestSupport.Wait)
    TlsTestSupport.deleteTree(dir)

  private def installed(tool: String): Boolean = Seq("sh", "-c", s"command -v $tool").!(ProcessLogger(_ => ())) == 0

  private def run(args: String*): (Int, String) =
    val out = new StringBuilder
    val code = (Seq("bash", script.toString) ++ args)
      .!(ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n')))
    (code, out.toString)

  private def password(file: Path): String = Files.readString(file, StandardCharsets.UTF_8).trim

  /** A store the script wrote, as a profile's HOCON: `<stem>.p12` and its password file. */
  private def store(stem: String, passwordStem: String) =
    s"""{ path = "${dir.resolve(s"$stem.p12")}", type = "pkcs12", password-file = "${dir.resolve(
        s"$passwordStem.password"
      )}" }"""

  private def clientContext(stem: String, caCert: Path): SSLContext =
    val keys = KeyStore.getInstance("pkcs12")
    val pw = password(dir.resolve(s"$stem.password")).toCharArray
    val in = Files.newInputStream(dir.resolve(s"$stem.p12"))
    try keys.load(in, pw)
    finally in.close()
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(keys, pw)
    val trust = KeyStore.getInstance("pkcs12")
    trust.load(null, null)
    val certIn = Files.newInputStream(caCert)
    try
      trust.setCertificateEntry(
        "ca",
        java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(certIn)
      )
    finally certIn.close()
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(trust)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, tmf.getTrustManagers, new java.security.SecureRandom())
    ctx

  "gen-cert.sh" should "issue a CA, a server and a client certificate that pass a need listener" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    assume(installed("openssl") && installed("keytool"), "openssl and keytool are needed")
    val out = dir.toString
    run("ca", "-o", out)._1 shouldBe 0
    run("server", "--ca", dir.resolve("fukuii-ca").toString, "-o", out)._1 shouldBe 0
    run("client", "--ca", dir.resolve("fukuii-ca").toString, "--cn", "monitor", "-o", out)._1 shouldBe 0

    val config = TlsTestSupport.config(
      s"rpc { keystore = ${store("fukuii-rpc", "fukuii-rpc")}, " +
        s"truststore = ${store("fukuii-ca-truststore", "fukuii-ca-truststore")} }",
      """tls { enabled = true, profile = "rpc", client-auth = "need" }"""
    )
    TlsStartupCheck.run(config).isRight shouldBe true

    MtlsTestSupport.withServer(config) { port =>
      val caCert = dir.resolve("fukuii-ca.crt")
      MtlsTestSupport.answered(MtlsTestSupport.exchange(clientContext("fukuii-client-monitor", caCert), port)) shouldBe
        true
    }
  }

  it should "refuse a server or client command without the CA, and a client without a name" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    assume(installed("openssl"), "openssl is needed")
    run("server", "-o", dir.toString)._2 should include("server needs --ca")
    run("client", "--ca", dir.resolve("absent").toString, "-o", dir.toString)._2 should include("client needs --cn")
    run("client", "--ca", dir.resolve("absent").toString, "--cn", "x", "-o", dir.toString)._2 should include(
      "absent.p12 is missing"
    )
  }
