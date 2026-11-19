package com.chipprbots.ethereum.tls

import java.security.SecureRandom

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 US4 scenario 3, research D-1: once a CA is removed from a listener's truststore and the reload has run, a
  * client of that CA is refused, including one that would otherwise resume the session it opened before the reload: a
  * reload invalidates the cached sessions, so the next handshake is a full one that checks trust again.
  */
class SessionInvalidationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("session-invalidation-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val partner = pki.otherCa()
  private val server = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), MtlsTestSupport.Wait)
    TlsTestSupport.deleteTree(dir)

  "Removing a CA from the truststore" should "refuse its clients after the reload, a resuming one included" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val trust = pki.truststore("trust", Seq(pki.ca.cert, partner.cert), "trust-pw")
    val config = TlsTestSupport.config(
      TlsTestSupport.profile("rpc", server, Some(trust)),
      """tls { enabled = true, profile = "rpc", client-auth = "need" }"""
    )
    val ctx = TlsServerEngine.rpcHttp(config, new SecureRandom()).fold(e => fail(e.message), identity)
    val reloader = new TlsReloader(scheduled = false)
    reloader.register("rpc-http", ctx)
    // one client context per client, so its session cache is the one a resumption would use
    val partnerClient = pki.clientContext(presenting = Some(pki.leaf("partner-app", signedBy = partner)))
    val ownClient = pki.clientContext(presenting = Some(pki.leaf("own-app")))
    MtlsTestSupport.withServerContext(ctx) { port =>
      MtlsTestSupport.answered(MtlsTestSupport.exchange(partnerClient, port)) shouldBe true
      MtlsTestSupport.answered(MtlsTestSupport.exchange(partnerClient, port)) shouldBe true

      pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")
      reloader.poll()

      MtlsTestSupport.answered(MtlsTestSupport.exchange(partnerClient, port)) shouldBe false
      MtlsTestSupport.answered(MtlsTestSupport.exchange(ownClient, port)) shouldBe true
    }
  }
