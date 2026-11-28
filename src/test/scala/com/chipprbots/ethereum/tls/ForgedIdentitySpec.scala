package com.chipprbots.ethereum.tls

import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 Security Considerations: identity rules read only a chain the handshake verified. A self-signed
  * certificate, or one from another CA, that copies a rule's SAN and CN fails the handshake under both `need` and
  * `want`, and so never reaches the rules; the genuine one does match.
  */
class ForgedIdentitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given system: ActorSystem = ActorSystem("forged-identity-spec")

  private val dir = TlsTestSupport.tempDir()
  private val pki = new TestPki(dir)
  private val server = pki.keystore("server", Seq("rpc" -> pki.leaf()), "srv-pw")
  private val trust = pki.truststore("trust", Seq(pki.ca.cert), "trust-pw")

  override protected def afterAll(): Unit =
    Await.result(system.terminate(), MtlsTestSupport.Wait)
    TlsTestSupport.deleteTree(dir)

  private val rules = IdentityRules
    .parse(
      ConfigFactory.parseString(
        """network.rpc.client-identities = [
          |  { name = "ops", match { san-dns = "ops.internal", subject-cn = "ops" }, apis = "eth,admin" }
          |]""".stripMargin
      ),
      TlsStartupCheck.KnownApis
    )
    .fold(e => fail(e.message), identity)

  private def config(clientAuth: String) = TlsTestSupport.config(
    TlsTestSupport.profile("rpc", server, Some(trust)),
    s"""tls { enabled = true, profile = "rpc", client-auth = "$clientAuth" }"""
  )

  private def selfSignedCopy: PkiIdentity =
    // issued by a CA of its own, with the genuine certificate's subject and SAN
    pki.leaf("ops", sanDns = Seq("ops.internal"), signedBy = pki.otherCa())

  "A forged copy of a rule's certificate" should "fail the handshake under need and want" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    Seq("need", "want").foreach { mode =>
      withClue(mode) {
        MtlsTestSupport.withServer(config(mode)) { port =>
          val forged = pki.clientContext(presenting = Some(selfSignedCopy))
          MtlsTestSupport.answered(MtlsTestSupport.exchange(forged, port)) shouldBe false
          val selfSigned = pki.clientContext(presenting = Some(pki.selfSignedLeaf("ops")))
          MtlsTestSupport.answered(MtlsTestSupport.exchange(selfSigned, port)) shouldBe false
        }
      }
    }
  }

  it should "match the rule only with the genuine certificate, which the same values copied cannot fake" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val genuine = pki.leaf("ops", sanDns = Seq("ops.internal"))
    rules.resolve(Some(genuine.cert)) shouldBe Caller.Matched(rules.rules.head)
    // the rules themselves would match the copy: the handshake, not the rule, is what keeps it out (above)
    rules.resolve(Some(selfSignedCopy.cert)) shouldBe Caller.Matched(rules.rules.head)
  }
