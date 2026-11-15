package com.chipprbots.ethereum.console.ops

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.Screen
import com.chipprbots.ethereum.console.TuiConfig
import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.console.TuiRenderer
import com.chipprbots.ethereum.testing.Tags.*

/** The configuration view (spec 029 FR-054, FR-058, T099): a row a key, sorted, under its prefix, with where the value
  * came from; a long value cut and marked as cut; every secret hidden through [[Redaction]]; and the rows the screen
  * shows for a filter.
  */
class ConfigViewSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val Marker = "PLANTED-SECRET-7f3a"

  private def parse(text: String): Config = ConfigFactory.parseString(text).resolve()

  /** A row without where it came from: `key = value`. */
  private def shown(row: String): String = row.take(row.indexOf("   # "))

  private def rows(text: String, prefix: String = "fukuii"): Vector[String] =
    ConfigView.rows(parse(text), prefix).map(shown)

  "The rows" should "be a row a key, sorted, each under the prefix" taggedAs (UnitTest) in {
    val all = ConfigView.rows(
      parse("""zeta = 1
              |network { rpc { http { port = 8546, enabled = true } }, peer { max-outgoing-peers = 25 } }
              |datadir = "/var/lib/fukuii/etc"
              |sync.do-fast-sync = false
              |""".stripMargin),
      "fukuii"
    )
    all.map(shown) shouldBe Vector(
      "fukuii.datadir = /var/lib/fukuii/etc",
      "fukuii.network.peer.max-outgoing-peers = 25",
      "fukuii.network.rpc.http.enabled = true",
      "fukuii.network.rpc.http.port = 8546",
      "fukuii.sync.do-fast-sync = false",
      "fukuii.zeta = 1"
    )
    all shouldBe all.sorted
    // Every row says where its value came from.
    for row <- all do row should include("   # ")
  }

  it should "have no prefix when none is given, and write a list and a key in quotes as they are" taggedAs (UnitTest) in {
    rows(
      """a.b = [1, 2, 3]
           |a."c.d" = "two words"
           |a.e = []
           |""".stripMargin,
      prefix = ""
    ) shouldBe Vector(
      "a.b = [1,2,3]",
      "a.c.d = two words",
      "a.e = []"
    )
    ConfigView.rows(ConfigFactory.empty(), "fukuii") shouldBe empty
  }

  it should "say the file and the line a value came from, or what the library says of it" taggedAs (UnitTest) in {
    val dir = Files.createTempDirectory("config-view-spec")
    val file = dir.resolve("node.conf")
    try
      Files.write(file, "# a comment\ndatadir = \"/data\"\n\nport = 9076\n".getBytes(StandardCharsets.UTF_8))
      val fromFile = ConfigFactory.parseFile(file.toFile)
      val set = fromFile.withValue("verbose", ConfigValueFactory.fromAnyRef(true, "the command line"))
      ConfigView.rows(set, "fukuii") shouldBe Vector(
        "fukuii.datadir = /data   # node.conf:2",
        "fukuii.port = 9076   # node.conf:4",
        "fukuii.verbose = true   # the command line"
      )
    finally
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
  }

  // ---- long values --------------------------------------------------------------------------------------------------

  "A long value" should "be cut at the longest value shown, and marked as cut" taggedAs (UnitTest) in {
    ConfigView.MaxValueChars shouldBe 160
    val exact = "a" * 160
    val long = "b" * 161
    val list = (1 to 40).map(i => s""""enode://${"%04x".format(i) * 32}@203.0.113.$i:30303"""").mkString("[", ",", "]")
    val all = rows(s"""exact = "$exact"
                      |long = "$long"
                      |nodes = $list
                      |""".stripMargin)
    all(0) shouldBe s"fukuii.exact = $exact"
    // Never cut without saying so: the mark stands where the value stops.
    all(1) shouldBe s"fukuii.long = ${"b" * 160}…"
    all(2) should startWith("""fukuii.nodes = ["enode://0001""")
    all(2) should endWith("…")
    (all(2).stripPrefix("fukuii.nodes = ") should have).length(161)
  }

  // ---- secrets ------------------------------------------------------------------------------------------------------

  "A secret" should "be hidden whatever key shape holds it" taggedAs (UnitTest) in {
    val all = rows(s"""faucet.wallet-password = "$Marker-1"
                      |keyStore.passphrase = "$Marker-2"
                      |network.rpc.http.certificate.password = "$Marker-3"
                      |engine.jwt-secret = "$Marker-4"
                      |some.new.api-token = "$Marker-5"
                      |node.private-key = "$Marker-6"
                      |node.privatekey = "$Marker-7"
                      |wallet.mnemonic = "$Marker-8"
                      |wallet.seed = "$Marker-9"
                      |wallet.SECRET = "$Marker-10"
                      |""".stripMargin)
    all should have size 10
    for row <- all do
      withClue(s"$row: ") {
        row should endWith(s" = ${Redaction.Hidden}")
        (row should not).include(Marker)
      }
  }

  it should "be hidden whole when it is a list, or a number" taggedAs (UnitTest) in {
    rows(s"""api.tokens = ["$Marker-1", "$Marker-2"]
            |wallet.seed = 123456789
            |""".stripMargin) shouldBe Vector(
      s"fukuii.api.tokens = ${Redaction.Hidden}",
      s"fukuii.wallet.seed = ${Redaction.Hidden}"
    )
  }

  // Was a defect, fixed: `rows` says "a list or an object under a secret key is hidden whole", and only the last part of
  // a key is looked at: the keys of an object under a secret key are shown.
  it should "be hidden whole when it is an object under a secret key" taggedAs (UnitTest) in {
    val all = rows(s"""engine.jwt-secret { value = "$Marker-1", encoding = "hex" }
                      |wallet.password { current = "$Marker-2" }
                      |""".stripMargin)
    for row <- all do
      withClue(s"$row: ") {
        (row should not).include(Marker)
      }
  }

  "A path to a secret" should "be shown: it says where the secret is" taggedAs (UnitTest) in {
    rows("""network.engine-api.jwt-secret-path = "/var/lib/fukuii/jwt.hex"
           |network.rpc.http.certificate.password-file = "/etc/fukuii/keystore.pass"
           |node-key-file = "/var/lib/fukuii/node.key"
           |""".stripMargin) shouldBe Vector(
      "fukuii.network.engine-api.jwt-secret-path = /var/lib/fukuii/jwt.hex",
      "fukuii.network.rpc.http.certificate.password-file = /etc/fukuii/keystore.pass",
      "fukuii.node-key-file = /var/lib/fukuii/node.key"
    )
  }

  "A URL" should "be shown without its credentials, whatever key holds it" taggedAs (UnitTest) in {
    rows(s"""sync.checkpoint-sync-url = "https://operator:$Marker@checkpoints.example.org/etc"
            |metrics.push-gateway = "http://$Marker@10.0.0.1:9091/metrics"
            |""".stripMargin) shouldBe Vector(
      "fukuii.metrics.push-gateway = http://10.0.0.1:9091/metrics",
      "fukuii.sync.checkpoint-sync-url = https://checkpoints.example.org/etc"
    )
  }

  // Was a defect, fixed: a list is rendered as one text, `["http://…"]`, which is not a URL, so the credentials of every
  // URL in it are shown. `fukuii.mining.notify-urls` is such a list.
  it should "be shown without its credentials when it is one of a list" taggedAs (UnitTest) in {
    val all = rows(s"""mining.notify-urls = ["http://pool:$Marker@pool.example.org/work", "http://10.0.0.9/work"]""")
    all should have size 1
    (all.head should not).include(Marker)
    all.head should include("pool.example.org/work")
  }

  // Was a defect, fixed: the value is cut to its longest length before its credentials are looked for. Where the cut
  // falls before the `@`, what is left has no user-info to find, and the start of the password is shown.
  it should "be shown without its credentials when it is longer than the longest value shown" taggedAs (UnitTest) in {
    val token = Marker * 12 // a bearer token as a password: 228 characters
    val all = rows(s"""sync.checkpoint-sync-url = "https://operator:$token@checkpoints.example.org/etc"""")
    (all.head should not).include(Marker)
  }

  // ---- the handler, and the screen's filter -------------------------------------------------------------------------

  "The handler" should "read the configuration again each time, and give its rows a line each" taggedAs (UnitTest) in {
    var config = parse(s"""datadir = "/data"
                          |engine.jwt-secret = "$Marker"
                          |""".stripMargin)
    val handler = ConfigView.handler(() => config, "fukuii")
    val request = ActionRequest(Actions.Config.name)
    handler.preview(request) shouldBe Preview(Vector("Shows the effective configuration; secrets are hidden."))
    val first = handler.run(request, _ => ()).toOption.value
    first.split('\n').toVector.map(shown) shouldBe
      Vector("fukuii.datadir = /data", s"fukuii.engine.jwt-secret = ${Redaction.Hidden}")
    (first should not).include(Marker)
    config = parse("""datadir = "/elsewhere"""")
    handler.run(request, _ => ()).toOption.value.split('\n').toVector.map(shown) shouldBe
      Vector("fukuii.datadir = /elsewhere")
  }

  "The configuration screen" should "show the rows that have the filter in them, in any case, and count them" taggedAs (
    UnitTest
  ) in {
    val report = ActionReport(
      Vector(
        "fukuii.datadir = /var/lib/fukuii/etc   # app.conf:3",
        "fukuii.network.rpc.http.port = 8546   # network.conf:251",
        "fukuii.network.rpc.ws.port = 8552   # network.conf:310",
        "fukuii.sync.do-fast-sync = false   # sync.conf:12"
      ),
      TuiFixtures.Now
    )
    val read = TuiFixtures.syncedEtc().copy(actions = ActionsView(reports = Map(Actions.Config.name -> report)))
    def frame(filter: String): String =
      TuiRenderer(TuiConfig.default)
        .render(read.copy(screens = List(Screen.Config(filter, 0))), 120, 40)
        .map(_.toString)
        .mkString("\n")
    val all = frame("")
    for row <- report.lines do all should include(row)
    all should include("4 rows")
    val filtered = frame("RPC")
    filtered should include("fukuii.network.rpc.http.port = 8546   # network.conf:251")
    filtered should include("fukuii.network.rpc.ws.port = 8552   # network.conf:310")
    (filtered should not).include("fukuii.datadir")
    (filtered should not).include("fukuii.sync.do-fast-sync")
    filtered should include("4 rows")
    filtered should include("2 with 'rpc'")
    // A filter nothing has in it shows no row, and says so in its count.
    val none = frame("nothing-has-this")
    (none should not).include("fukuii.datadir")
    none should include("0 with 'nothing-has-this'")
  }
