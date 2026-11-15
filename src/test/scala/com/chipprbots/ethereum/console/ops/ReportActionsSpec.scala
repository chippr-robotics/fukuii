package com.chipprbots.ethereum.console.ops

import java.net.InetAddress

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The actions that read the node and report (spec 029 FR-054, T097; contracts/actions.md): `tunables`, `net`, and
  * `detect-ip`, whose detector here is a function of the test's: nothing is asked of any network.
  *
  * The detection's time limit ([[ReportActions.DetectTimeout]], 30 s) is a constant the handler does not take, so a
  * detector that never answers is not tried here: it would take the 30 real seconds.
  */
class ReportActionsSpec extends AnyFlatSpec with Matchers with OptionValues:

  // ---- tunables -----------------------------------------------------------------------------------------------------

  private val tunables = ActionRequest(Actions.Tunables.name)
  private val Returns = "These change while the node runs, and return to their configured values when it restarts."

  "tunables" should "say each setting, its value now and the command that changes it, in columns" taggedAs (UnitTest) in {
    val handler = ReportActions.tunables(() =>
      Seq(
        ("log levels", "root INFO, none changed", "loglevel <level> [logger], loglevel reset"),
        ("outgoing peer limit", "25 (incoming 30, not changeable here)", "maxpeers <n>"),
        ("coinbase", "0x4f2a0b9c0000000000000000000000000000c91e", "coinbase <address>")
      )
    )
    handler.preview(tunables) shouldBe Preview(Vector("Shows the settings that change without a restart."))
    handler.run(tunables, _ => ()).toOption.value.split('\n').toVector shouldBe Vector(
      Returns,
      "log levels           root INFO, none changed                     change with: loglevel <level> [logger], loglevel reset",
      "outgoing peer limit  25 (incoming 30, not changeable here)       change with: maxpeers <n>",
      "coinbase             0x4f2a0b9c0000000000000000000000000000c91e  change with: coinbase <address>"
    )
  }

  it should "write a value longer than its column whole" taggedAs (UnitTest) in {
    val long = "fukuii.sync DEBUG, " * 5 // 95 characters: the column is 60 at most
    val handler = ReportActions.tunables(() =>
      Seq(("log levels", long, "loglevel reset"), ("coinbase", "0x00", "coinbase <address>"))
    )
    val rows = handler.run(tunables, _ => ()).toOption.value.split('\n').toVector
    rows(1) shouldBe s"log levels  $long  change with: loglevel reset"
    rows(2) shouldBe s"coinbase    ${"0x00".padTo(60, ' ')}  change with: coinbase <address>"
  }

  it should "read the settings again each time, and say only its heading when there is none" taggedAs (UnitTest) in {
    var read = Seq.empty[(String, String, String)]
    val handler = ReportActions.tunables(() => read)
    handler.run(tunables, _ => ()) shouldBe Right(Returns)
    read = Seq(("built-in miner", "mining", "mining stop"))
    handler.run(tunables, _ => ()).toOption.value.split('\n').toVector shouldBe
      Vector(Returns, "built-in miner  mining  change with: mining stop")
  }

  // ---- net ----------------------------------------------------------------------------------------------------------

  private val net = ActionRequest(Actions.Net.name)
  private val Upnp = "UPnP        port mapping is not reported by this node (it is in the log at start)"
  private val Evidence =
    "Whether it can be reached is known only from peers that reach it: detect-ip asks outside services what address they see."

  private val reached = ReportActions.NetInfo(
    listening = "0.0.0.0:30303",
    isListening = true,
    advertised = Some("203.0.113.9:30303"),
    detected = Some("203.0.113.9 by STUN, from an outside server, 12 min ago"),
    detection = "automatic, every 30 minutes",
    inbound = 14,
    connected = 41
  )

  private def said(info: ReportActions.NetInfo): Vector[String] =
    ReportActions.net(() => info).run(net, _ => ()).toOption.value.split('\n').toVector

  "net" should "say where the node listens, what it advertises, how its address was found, and who reached it" taggedAs (
    UnitTest
  ) in {
    ReportActions.net(() => reached).preview(net) shouldBe
      Preview(Vector("Shows the node's listening and external addresses."))
    said(reached) shouldBe Vector(
      "Listening   0.0.0.0:30303",
      "Advertised  203.0.113.9:30303",
      "Detection   automatic, every 30 minutes: 203.0.113.9 by STUN, from an outside server, 12 min ago",
      Upnp,
      "Inbound     14 of 41 connected peers dialled this node: it can be reached from outside",
      Evidence
    )
  }

  it should "say when the node is not listening, and that peers are then told no address" taggedAs (UnitTest) in {
    val deaf = reached.copy(isListening = false, advertised = None, inbound = 0, connected = 0)
    said(deaf).take(2) shouldBe Vector(
      "Listening   0.0.0.0:30303  (NOT listening)",
      "Advertised  none yet: peers are told no address"
    )
    // With no peer at all there is nothing to say of who reached it.
    said(deaf)(4) shouldBe "Inbound     0 of 0 connected peers dialled this node"
  }

  it should "say only how detection is configured when no address was ever detected" taggedAs (UnitTest) in {
    said(reached.copy(detected = None))(2) shouldBe "Detection   automatic, every 30 minutes"
    said(reached.copy(detected = None, detection = "off"))(2) shouldBe "Detection   off"
    said(reached.copy(detected = Some("the last detection found no address")))(2) shouldBe
      "Detection   automatic, every 30 minutes: the last detection found no address"
  }

  it should "say that no peer has reached it yet when all its peers were dialled" taggedAs (UnitTest) in {
    said(reached.copy(inbound = 0))(4) shouldBe
      "Inbound     0 of 41 connected peers dialled this node: none has reached it from outside yet"
  }

  it should "read the node again each time" taggedAs (UnitTest) in {
    var info = reached
    val handler = ReportActions.net(() => info)
    handler.run(net, _ => ()).toOption.value should include("14 of 41")
    info = reached.copy(inbound = 15, connected = 42)
    handler.run(net, _ => ()).toOption.value should include("15 of 42")
  }

  // ---- detect-ip ----------------------------------------------------------------------------------------------------

  private val detect = ActionRequest(Actions.DetectIp.name)

  /** Runs the detector on the caller's thread: the handler has its answer when it asks for it. */
  private given ExecutionContext = ExecutionContext.parasitic

  /** 203.0.113.9, made of its bytes: no name is looked up. */
  private val outside: InetAddress = InetAddress.getByAddress(Array(203.toByte, 0.toByte, 113.toByte, 9.toByte))

  private def detector(
      found: () => Option[InetAddress],
      advertised: Option[String] = Some("203.0.113.9:30303"),
      mode: String = "automatic, every 30 minutes"
  ): ActionHandler = ReportActions.detectIp(found, () => mode, () => advertised)

  "detect-ip" should "name the outside services it asks, and say that it changes nothing the node advertises" taggedAs (
    UnitTest
  ) in {
    var asked = 0
    val handler = detector { () =>
      asked += 1
      Some(outside)
    }
    handler.preview(detect) shouldBe Preview(
      Vector(
        "Asks the gateway on this network (UPnP) for this node's public address.",
        "If it has none: stun.l.google.com, stun1.l.google.com, stun.cloudflare.com (STUN), then icanhazip.com, " +
          "checkip.amazonaws.com, api4.ipify.org, 4.ident.me (HTTPS).",
        "Each service contacted sees this node's address.",
        "It takes up to 30 s, reports what it finds and changes nothing.",
        "What the node advertises (203.0.113.9:30303) changes only when the node's own detection runs: that is " +
          "automatic, every 30 minutes."
      )
    )
    // A preview asks nobody.
    asked shouldBe 0
    detector(() => None, advertised = None, mode = "off").preview(detect).lines.last shouldBe
      "What the node advertises (nothing yet) changes only when the node's own detection runs: that is off."
  }

  it should "say the address an outside service saw, beside the one the node advertises" taggedAs (UnitTest) in {
    val progress = mutable.Buffer.empty[String]
    detector(() => Some(outside)).run(detect, progress += _) shouldBe
      Right("the address seen from outside is 203.0.113.9; the node advertises 203.0.113.9:30303")
    progress shouldBe Seq("asking the gateway, then outside services")
    detector(() => Some(outside), advertised = None).run(detect, _ => ()) shouldBe
      Right("the address seen from outside is 203.0.113.9; the node advertises nothing yet")
  }

  it should "fail when no service gave an address" taggedAs (UnitTest) in {
    detector(() => None).run(detect, _ => ()) shouldBe Left("no service gave an address")
  }

  it should "fail with the detector's own reason when the detector fails" taggedAs (UnitTest) in {
    detector(() => throw new IllegalStateException("no network interface is up")).run(detect, _ => ()) shouldBe
      Left("no network interface is up")
    // One with no message says what it is.
    detector(() => throw new IllegalStateException()).run(detect, _ => ()).left.toOption.value should
      include("IllegalStateException")
  }

  "The detection's time limit" should "be the 30 s its preview states" taggedAs (UnitTest) in {
    ReportActions.DetectTimeout.toSeconds shouldBe 30L
  }
