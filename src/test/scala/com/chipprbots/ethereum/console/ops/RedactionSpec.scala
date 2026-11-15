package com.chipprbots.ethereum.console.ops

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** What may be shown of a configuration value (spec 029 FR-058, research §7.4): a secret never, a path always, a URL
  * without its credentials.
  */
class RedactionSpec extends AnyFlatSpec with Matchers:

  private val Marker = "s3cr3t-MARKER-do-not-show"

  "A key that holds a secret" should "have its value hidden, by the list and by its name" taggedAs (UnitTest) in {
    val secret = Seq(
      "faucet.wallet-password",
      "fukuii.faucet.wallet-password",
      "fukuii.some.new.api-token",
      "fukuii.network.rpc.http.certificate.password",
      "fukuii.keyStore.passphrase",
      "fukuii.engine.jwt-secret",
      "anything.private-key",
      "wallet.mnemonic",
      "PASSWORD"
    )
    for key <- secret do
      withClue(s"$key: ") {
        Redaction.isSecret(key) shouldBe true
        Redaction.show(key, Marker) shouldBe Redaction.Hidden
      }
  }

  "A key that holds a path to a secret" should "be shown: the path says where the secret is and is not the secret" taggedAs (
    UnitTest
  ) in {
    val paths = Seq(
      "fukuii.node-key-file" -> "/var/lib/fukuii/node.key",
      "fukuii.network.engine-api.jwt-secret-path" -> "/var/lib/fukuii/jwt.hex",
      "fukuii.network.rpc.http.certificate.password-file" -> "/etc/fukuii/keystore.pass",
      "fukuii.network.rpc.http.certificate.keystore-path" -> "/etc/fukuii/keystore.p12",
      "fukuii.keyStore.keystore-dir" -> "/var/lib/fukuii/keystore"
    )
    for (key, value) <- paths do
      withClue(s"$key: ") {
        Redaction.isSecret(key) shouldBe false
        Redaction.show(key, value) shouldBe value
      }
  }

  "A setting that is not a secret" should "be shown as it is, whatever its name resembles" taggedAs (UnitTest) in {
    Redaction.show(
      "fukuii.keyStore.minimal-passphrase-length",
      "7"
    ) shouldBe Redaction.Hidden // by name: hidden is the safe side
    Redaction.show("fukuii.keyStore.allow-no-passphrase", "false") shouldBe Redaction.Hidden
    Redaction.show("fukuii.network.rpc.http.port", "8546") shouldBe "8546"
    Redaction.show("fukuii.datadir", "/var/lib/fukuii") shouldBe "/var/lib/fukuii"
    Redaction.show("fukuii.mining.coinbase", "0x1000000000000000000000000000000000000001") shouldBe
      "0x1000000000000000000000000000000000000001"
  }

  "A URL" should "be shown without its credentials, whatever key holds it" taggedAs (UnitTest) in {
    Redaction.show("fukuii.sync.checkpoint-sync-url", s"https://user:$Marker@checkpoints.example.org/etc") shouldBe
      "https://checkpoints.example.org/etc"
    Redaction.show(
      "fukuii.anything.url",
      s"http://$Marker@10.0.0.1:8080/path?q=1"
    ) shouldBe "http://10.0.0.1:8080/path?q=1"
    Redaction.withoutUserInfo("https://checkpoints.example.org/etc") shouldBe "https://checkpoints.example.org/etc"
    // One the JDK does not parse still loses its credentials.
    (Redaction.withoutUserInfo(s"https://user:$Marker@host with space/x") should not).include(Marker)
    // Not a URL: unchanged, an `@` or a colon in it or not.
    Redaction.withoutUserInfo("user@example.org") shouldBe "user@example.org"
    Redaction.withoutUserInfo("0.0.0.0:30303") shouldBe "0.0.0.0:30303"
    Redaction.withoutUserInfo("") shouldBe ""
  }
