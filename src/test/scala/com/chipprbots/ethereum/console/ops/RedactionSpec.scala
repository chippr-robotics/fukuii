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
      "PASSWORD",
      // However the words of the name are joined.
      "fukuii.metrics.api-key",
      "fukuii.metrics.api_key",
      "fukuii.metrics.apiKey",
      "wallet.private_key",
      "wallet.privateKey",
      "db.passwd",
      "x.pwd",
      "auth.credentials",
      "fukuii.rpc.bearer-token",
      "fukuii.rpc.bearer",
      "fukuii.rpc.access_token",
      "fukuii.rpc.jwt_secret"
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
    // A setting about a secret is not the secret: the two the node has are shown, under `fukuii` or not.
    Redaction.ShownKeys shouldBe Set("keystore.minimal-passphrase-length", "keystore.allow-no-passphrase")
    Redaction.show("fukuii.keyStore.minimal-passphrase-length", "7") shouldBe "7"
    Redaction.show("fukuii.keyStore.allow-no-passphrase", "false") shouldBe "false"
    Redaction.show("keystore.minimal-passphrase-length", "7") shouldBe "7"
    Redaction.isSecret("fukuii.keystore.allow-no-passphrase") shouldBe false
    // Only those two: another key with the word in it, and the same name under a secret's object, stay hidden.
    Redaction.show("fukuii.keyStore.passphrase-hint", Marker) shouldBe Redaction.Hidden
    Redaction.show("fukuii.wallet.minimal-passphrase-length", Marker) shouldBe Redaction.Hidden
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

  // ---- text that no key names (a log line, a row of a view) --------------------------------------------------------

  "Scrubbed text" should "lose the secret of a query string, and keep the rest of the URL" taggedAs (UnitTest) in {
    Redaction.scrubbed(s"GET https://rpc.example.org/v1?token=$Marker") shouldBe
      "GET https://rpc.example.org/v1?token=<hidden>"
    Redaction.scrubbed(s"https://rpc.example.org/v1?chain=etc&api_key=$Marker&page=2") shouldBe
      "https://rpc.example.org/v1?chain=etc&api_key=<hidden>&page=2"
    for name <- Seq("token", "access_token", "access-token", "api_key", "api-key", "apikey", "API_KEY", "key", "secret",
        "sig", "signature", "password", "passwd", "auth")
    do
      withClue(s"$name: ") {
        val first = Redaction.scrubbed(s"fetching https://example.org/x?$name=$Marker now")
        first shouldBe s"fetching https://example.org/x?$name=<hidden> now"
        val second = Redaction.scrubbed(s"""fetching "https://example.org/x?a=1&$name=$Marker" now""")
        second shouldBe s"""fetching "https://example.org/x?a=1&$name=<hidden>" now"""
      }
    Redaction.Hidden shouldBe "<hidden>"
    // What is no secret stays.
    Redaction.scrubbed("https://example.org/x?page=2&limit=50") shouldBe "https://example.org/x?page=2&limit=50"
  }

  it should "lose the credentials of an Authorization header" taggedAs (UnitTest) in {
    Redaction.scrubbed("Authorization: Bearer abc123def456ghi789.jkl-012_mno") shouldBe "Authorization: Bearer <hidden>"
    Redaction.scrubbed("authorization: bearer abc123def456ghi789") shouldBe "authorization: bearer <hidden>"
    Redaction.scrubbed("Authorization: Basic b3BlcmF0b3I6cGFzc3dvcmQ=") shouldBe "Authorization: Basic <hidden>"
    (Redaction.scrubbed(s"request failed (Authorization: Bearer $Marker) with 401") should not).include(Marker)
    // The words alone are no credentials.
    Redaction.scrubbed("a basic check of the bearer") shouldBe "a basic check of the bearer"
  }

  it should "lose the user and password of a URL, as a configuration value does" taggedAs (UnitTest) in {
    Redaction.scrubbed(s"fetching https://operator:$Marker@checkpoints.example.org/etc?token=$Marker") shouldBe
      "fetching https://checkpoints.example.org/etc?token=<hidden>"
    // A value under a key that holds no secret goes through the same.
    Redaction.show("fukuii.sync.checkpoint-sync-url", s"https://checkpoints.example.org/etc?token=$Marker") shouldBe
      "https://checkpoints.example.org/etc?token=<hidden>"
  }

  it should "keep the node id of every enode of a list: it is public, and it names the peer" taggedAs (UnitTest) in {
    val first = s"enode://${"ab12cd34" * 16}@203.0.113.9:30303"
    val second = s"enode://${"0f1e2d3c" * 16}@198.51.100.7:30303?discport=30301"
    val list = s"$first,$second"
    Redaction.scrubbed(list) shouldBe list
    Redaction.withoutUserInfo(list) shouldBe list
    Redaction.show("fukuii.network.discovery.bootstrap-nodes", s"[$first, $second]") shouldBe s"[$first, $second]"
    Redaction.scrubbed(s"Handshaked peer $first") shouldBe s"Handshaked peer $first"
    // What stands before an enode's `@` and is no node id is a credential like any other.
    Redaction.scrubbed(s"enode://$Marker@203.0.113.9:30303") shouldBe "enode://203.0.113.9:30303"
  }

  it should "leave text with nothing to hide as it is" taggedAs (UnitTest) in {
    for line <- Seq(
        "Imported block 21,884,102 0x6d21…1a0e with 14 txs in 22ms",
        "peers=41 best=21884102 key=value",
        "listening on http://127.0.0.1:8546/",
        "operator@example.org wrote at 12:00:00",
        ""
      )
    do Redaction.scrubbed(line) shouldBe line
  }

  it should "read a long line with no URL in it once, not once from every character" taggedAs (UnitTest) in {
    // A hex blob as a log line can hold one: every character of it could start a URL's scheme.
    val blob = "0123456789abcdef" * 12_500
    blob should have length 200_000L
    for line <- Seq(blob, s"payload=$blob", s"$blob://", s"see http://example.org/x $blob") do
      val started = System.nanoTime()
      val out = Redaction.scrubbed(line)
      val elapsed = (System.nanoTime() - started) / 1_000_000L
      out shouldBe line
      withClue(s"${line.take(12)}… took $elapsed ms: ") {
        elapsed should be < 2_000L
      }
  }
