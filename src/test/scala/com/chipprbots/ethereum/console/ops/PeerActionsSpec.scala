package com.chipprbots.ethereum.console.ops

import scala.collection.mutable

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.Sourced
import com.chipprbots.ethereum.console.TuiFixtures
import com.chipprbots.ethereum.console.TuiPeer
import com.chipprbots.ethereum.console.TuiState
import com.chipprbots.ethereum.testing.Tags.*

/** The peer actions (spec 029 FR-050, FR-052, FR-054, T097): what each preview says of the node as it is, every case in
  * which it refuses, and that carrying one out calls the node's one function with the right argument and hands its
  * failure back as it came.
  */
class PeerActionsSpec extends AnyFlatSpec with Matchers with OptionValues:

  // 41 peers connected, of a limit of 50; every third one dialled this node.
  private val synced = TuiFixtures.syncedEtc()
  private val connected: Seq[TuiPeer] = synced.snapshot.flatMap(_.peers.value).value
  private val inbound = connected(0)
  private val outbound = connected(1)
  private val unscored = connected(6)

  /** An id no connected peer has. */
  private val stranger = "ab12cd34" * 16

  private def short(id: String): String = id.take(8) + "…"

  private val limits =
    PeerActions.Limits(outgoing = 50, incoming = 30, minOutgoing = 20, trusted = Set.empty, maintained = Set.empty)

  /** The node's side: what it answers, and every call made of it, in order. */
  private class Fake(
      limits: Either[String, PeerActions.Limits] = Right(limits),
      blocked: Set[String] = Set.empty,
      answer: Either[String, Unit] = Right(()),
      written: String => String = identity
  ):
    val calls: mutable.Buffer[(String, Any)] = mutable.Buffer.empty
    private def called(what: String, argument: Any): Either[String, Unit] =
      calls += what -> argument
      answer
    val node: PeerActions.Node = PeerActions.Node(
      limits = () => limits,
      blocked = () => blocked,
      setMaxPeers = n => called("setMaxPeers", n),
      trust = enode => called("trust", enode),
      untrust = id => called("untrust", id),
      add = enode => called("add", enode),
      drop = (id, enode) => called("drop", (id, enode)),
      block = address => called("block", address).map(_ => written(address)),
      unblock = address => called("unblock", address)
    )
    def handlers(state: TuiState = synced): Map[String, ActionHandler] = PeerActions.handlers(node, () => state)

  private def request(spec: ActionSpec, params: (String, String)*): ActionRequest =
    ActionRequest(spec.name, params.toVector)

  private def preview(fake: Fake, r: ActionRequest, state: TuiState = synced): Preview =
    fake.handlers(state)(r.action).preview(r)

  private def run(fake: Fake, r: ActionRequest, state: TuiState = synced): Either[String, String] =
    fake.handlers(state)(r.action).run(r, _ => ())

  /** `synced` with `peers` connected and nothing else changed. */
  private def withPeers(peers: Seq[TuiPeer]): TuiState = TuiFixtures.withPeers(synced, Sourced.Fresh(peers, synced.now))

  "The peer actions" should "be the seven of the registry" taggedAs (UnitTest) in {
    new Fake().handlers().keySet shouldBe Set(
      "maxpeers",
      "peer trust",
      "peer untrust",
      "peer add",
      "peer drop",
      "peer block",
      "peer unblock"
    )
  }

  // ---- maxpeers -----------------------------------------------------------------------------------------------------

  private def maxPeers(n: String): ActionRequest = request(Actions.MaxPeers, "n" -> n)

  "maxpeers" should "say the outgoing limit now and after, that incoming stays, and that no connection closes" taggedAs (
    UnitTest
  ) in {
    preview(new Fake(), maxPeers("40")) shouldBe Preview(
      Vector(
        "Sets the limit on outgoing connections from 50 to 40.",
        "Incoming stays at 30, and no connection is closed: 41 peers are connected.",
        "New dials start only while fewer than 20 outgoing peers have handshaked.",
        "Resets when the node restarts."
      )
    )
  }

  // FAILS (reported): the sentence is written with `$now.minOutgoing` inside an interpolated string, which prints the
  // whole `Limits(...)` and then ".minOutgoing"; the `.replace` after it finds nothing to replace.
  it should "warn, in a sentence, when the limit is under what the node tries to keep" taggedAs (UnitTest) in {
    val p = preview(new Fake(), maxPeers("10"))
    p.refusal shouldBe None
    p.lines should have size 5
    p.lines.last shouldBe "10 is under the 20 the node tries to keep: it will hold fewer outgoing peers than it looks for."
  }

  it should "say nothing of the kept number when the limit is at or above it" taggedAs (UnitTest) in {
    preview(new Fake(), maxPeers("20")).lines should have size 4
    preview(new Fake(), maxPeers("21")).lines should have size 4
  }

  it should "be refused with no number, with the limit it already has, and when the limits cannot be read" taggedAs (
    UnitTest
  ) in {
    preview(new Fake(), request(Actions.MaxPeers)) shouldBe Preview(Vector.empty, Some("usage: maxpeers <n>"))
    preview(new Fake(), maxPeers("many")) shouldBe Preview(Vector.empty, Some("usage: maxpeers <n>"))
    preview(new Fake(), maxPeers("50")) shouldBe
      Preview(Vector.empty, Some("the limit on outgoing connections is already 50"))
    preview(new Fake(limits = Left("no answer within 15 s")), maxPeers("40")) shouldBe
      Preview(Vector.empty, Some("the peer manager's limits could not be read: no answer within 15 s"))
  }

  it should "set the limit by the node's function, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    run(ok, maxPeers("40")) shouldBe Right("the limit on outgoing connections is 40")
    ok.calls shouldBe Seq("setMaxPeers" -> 40)
    val refusing = new Fake(answer = Left("the peer manager refused the limit"))
    run(refusing, maxPeers("40")) shouldBe Left("the peer manager refused the limit")
    refusing.calls shouldBe Seq("setMaxPeers" -> 40)
    // With no number nothing is asked of the node.
    val unasked = new Fake()
    run(unasked, request(Actions.MaxPeers)) shouldBe Left("usage: maxpeers <n>")
    unasked.calls shouldBe empty
  }

  // ---- peer trust ---------------------------------------------------------------------------------------------------

  private def trust(id: String): ActionRequest = request(Actions.PeerTrust, "id" -> id)

  "peer trust" should "name the peer as the Peers view shows it, and say what trust exempts it from" taggedAs (
    UnitTest
  ) in {
    preview(new Fake(), trust(outbound.id)) shouldBe Preview(
      Vector(
        s"Trusts ${short(outbound.id)}, besu/v24.1.2/linux-x86_64/openjdk-java-21, 203.0.113.11:30303, " +
          "we dialled it, connected 4h 4m, score 0.58.",
        "Trusted now: no. After: yes.",
        "A trusted peer is exempt from the outgoing limit, from the pruning of incoming peers and from eviction; " +
          "it is not exempt from the incoming cap.",
        "The node dials it now. Resets when the node restarts."
      )
    )
    // The id as it was typed: in capitals, or with 0x before it.
    preview(new Fake(), trust("0x" + outbound.id.toUpperCase)).refusal shouldBe None
  }

  it should "be refused for a peer that dialled this node: its listening port is not known" taggedAs (UnitTest) in {
    inbound.incoming shouldBe true
    preview(new Fake(), trust(inbound.id)) shouldBe Preview(Vector.empty, Some(PeerActions.InboundRefusal))
    PeerActions.InboundRefusal should include("it dialled us")
    PeerActions.InboundRefusal should include("peer add enode://<id>@<host>:<port>")
  }

  it should "be refused for a peer that is gone, one already trusted, and when the trusted peers cannot be read" taggedAs (
    UnitTest
  ) in {
    preview(new Fake(), trust(stranger)) shouldBe
      Preview(Vector.empty, Some("no connected peer has the id ab12cd34… any more"))
    // The node writes an id down in its own case: it is the same peer.
    val trusting = new Fake(limits = Right(limits.copy(trusted = Set("0x" + outbound.id.toUpperCase))))
    preview(trusting, trust(outbound.id)) shouldBe
      Preview(Vector.empty, Some(s"${short(outbound.id)} is already trusted"))
    // A trusted peer that dialled us is said to be trusted: that is the reason nothing would change.
    val trustingInbound = new Fake(limits = Right(limits.copy(trusted = Set(inbound.id))))
    preview(trustingInbound, trust(inbound.id)).refusal.value shouldBe s"${short(inbound.id)} is already trusted"
    preview(new Fake(limits = Left("the peer manager is stopped")), trust(outbound.id)) shouldBe
      Preview(Vector.empty, Some("the trusted peers could not be read: the peer manager is stopped"))
  }

  it should "be refused when no enode can be made of the peer's address" taggedAs (UnitTest) in {
    val portless = outbound.copy(address = "203.0.113.99")
    preview(new Fake(), trust(portless.id), withPeers(Seq(portless))) shouldBe
      Preview(Vector.empty, Some("an enode cannot be made of 203.0.113.99: use peer add with its enode"))
    run(new Fake(), trust(portless.id), withPeers(Seq(portless))) shouldBe Left(
      "an enode cannot be made of 203.0.113.99"
    )
  }

  it should "hand the node the peer's enode, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    run(ok, trust(outbound.id)) shouldBe Right(s"${short(outbound.id)} is trusted")
    ok.calls shouldBe Seq("trust" -> s"enode://${outbound.id}@203.0.113.11:30303")
    val refusing = new Fake(answer = Left("the peer manager refused the enode"))
    run(refusing, trust(outbound.id)) shouldBe Left("the peer manager refused the enode")
    // A peer that dialled us, or one that has gone since the dialog opened: nothing is asked of the node.
    val unasked = new Fake()
    run(unasked, trust(inbound.id)) shouldBe Left(PeerActions.InboundRefusal)
    run(unasked, trust(stranger)) shouldBe Left("no connected peer has the id ab12cd34… any more")
    unasked.calls shouldBe empty
  }

  // ---- peer untrust -------------------------------------------------------------------------------------------------

  private def untrust(id: String): ActionRequest = request(Actions.PeerUntrust, "id" -> id)

  "peer untrust" should "name the peer, and say that its connection stays open" taggedAs (UnitTest) in {
    val trusting = new Fake(limits = Right(limits.copy(trusted = Set(outbound.id, stranger))))
    preview(trusting, untrust(outbound.id)) shouldBe Preview(
      Vector(
        s"Removes trust from ${short(outbound.id)}, besu/v24.1.2/linux-x86_64/openjdk-java-21, 203.0.113.11:30303, " +
          "we dialled it, connected 4h 4m, score 0.58.",
        "Trusted now: yes. After: no.",
        "The connection stays open; the peer may later be pruned or evicted like any other."
      )
    )
    // Trust is of a node, connected or not.
    preview(trusting, untrust("0x" + stranger.toUpperCase)).lines.head shouldBe
      "Removes trust from ab12cd34…, not connected now."
  }

  it should "be refused for a peer that is not trusted, and when the trusted peers cannot be read" taggedAs (UnitTest) in {
    preview(new Fake(), untrust(outbound.id)) shouldBe
      Preview(Vector.empty, Some(s"${short(outbound.id)} is not trusted"))
    preview(new Fake(limits = Left("no answer")), untrust(outbound.id)) shouldBe
      Preview(Vector.empty, Some("the trusted peers could not be read: no answer"))
  }

  it should "hand the node the id, in lower case with no 0x, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    run(ok, untrust("0x" + outbound.id.toUpperCase)) shouldBe Right(s"${short(outbound.id)} is no longer trusted")
    ok.calls shouldBe Seq("untrust" -> outbound.id)
    run(new Fake(answer = Left("it was not trusted")), untrust(outbound.id)) shouldBe Left("it was not trusted")
  }

  // ---- peer add -----------------------------------------------------------------------------------------------------

  private def add(id: String, at: String = "198.51.100.7:30303"): ActionRequest =
    request(Actions.PeerAdd, "enode" -> s"enode://$id@$at")

  "peer add" should "say that the node dials the enode and keeps dialling it, and the peer counts" taggedAs (UnitTest) in {
    preview(new Fake(), add(stranger)) shouldBe Preview(
      Vector(
        "Adds ab12cd34… at 198.51.100.7:30303 as a peer the node keeps.",
        "It is not connected now: the node dials it.",
        "It is dialled again 5 s after every disconnect, and is exempt from the outgoing limit.",
        "41 peers are connected; the limit on outgoing connections is 50.",
        "Held until the node restarts."
      )
    )
  }

  it should "say where the peer is connected now, when it is, and add it all the same" taggedAs (UnitTest) in {
    val p = preview(new Fake(), add(outbound.id, "203.0.113.11:30303"))
    p.refusal shouldBe None
    p.lines(1) shouldBe "It is connected now, at 203.0.113.11:30303."
    // A peer that dialled us can be added: the enode the operator typed has the port it listens on.
    preview(new Fake(), add(inbound.id, "203.0.113.10:30303")).refusal shouldBe None
  }

  it should "be refused for a peer already connected and kept, and when the peer manager cannot be read" taggedAs (
    UnitTest
  ) in {
    val keeping = new Fake(limits = Right(limits.copy(maintained = Set(outbound.id.toUpperCase))))
    preview(keeping, add(outbound.id)) shouldBe
      Preview(Vector.empty, Some(s"${short(outbound.id)} is already connected (203.0.113.11:30303) and already kept"))
    // Kept and not connected: adding it again is harmless, and says that it is dialled.
    val keepingStranger = new Fake(limits = Right(limits.copy(maintained = Set(stranger))))
    preview(keepingStranger, add(stranger)).refusal shouldBe None
    preview(new Fake(limits = Left("no answer within 15 s")), add(stranger)) shouldBe
      Preview(Vector.empty, Some("the peer manager could not be read: no answer within 15 s"))
  }

  it should "hand the node the enode as it was typed, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    run(ok, add(stranger)) shouldBe Right("the node dials ab12cd34… and keeps it")
    ok.calls shouldBe Seq("add" -> s"enode://$stranger@198.51.100.7:30303")
    run(new Fake(answer = Left("the peer manager refused the enode")), add(stranger)) shouldBe
      Left("the peer manager refused the enode")
  }

  // ---- peer drop ----------------------------------------------------------------------------------------------------

  private def drop(id: String): ActionRequest = request(Actions.PeerDrop, "id" -> id)

  "peer drop" should "name the peer with its client, address, direction, age and score, and say it may reconnect" taggedAs (
    UnitTest
  ) in {
    preview(new Fake(), drop(inbound.id)) shouldBe Preview(
      Vector(
        s"Disconnects ${short(inbound.id)}, CoreGeth/v1.12.19-stable-c2b6d2a5/linux-amd64/go1.21.5, 203.0.113.10:30303, " +
          "it dialled us, connected 4h 10m, score 0.95.",
        "It is not a peer the node keeps: nothing else changes.",
        "It may reconnect if discovery finds it again or it dials us; peer block keeps an address out."
      )
    )
    // A peer the scorer has not seen yet says so.
    preview(new Fake(), drop(unscored.id)).lines.head should endWith("not scored yet.")
  }

  it should "say that the node stops re-dialling a peer it keeps" taggedAs (UnitTest) in {
    val keeping = new Fake(limits = Right(limits.copy(maintained = Set("0x" + outbound.id))))
    preview(keeping, drop(outbound.id)).lines(1) shouldBe "It is a peer the node keeps: the node stops re-dialling it."
  }

  // FAILS (reported): with the peer manager unread the preview still states, as a fact, that the peer is not kept.
  it should "not say whether the peer is kept when the peer manager cannot be read" taggedAs (UnitTest) in {
    val unread = preview(new Fake(limits = Left("no answer within 15 s")), drop(outbound.id))
    unread.lines should not contain "It is not a peer the node keeps: nothing else changes."
  }

  it should "be refused for a peer that is gone" taggedAs (UnitTest) in {
    preview(new Fake(), drop(stranger)) shouldBe
      Preview(Vector.empty, Some("no connected peer has the id ab12cd34… any more"))
    // No peer at all has been read yet.
    preview(new Fake(), drop(outbound.id), synced.copy(snapshot = None)).refusal.value should include("any more")
  }

  it should "hand the node the peer's id and its enode, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake()
    run(ok, drop("0x" + outbound.id.toUpperCase)) shouldBe Right(s"${short(outbound.id)} is disconnected")
    ok.calls shouldBe Seq("drop" -> (outbound.id, Some(s"enode://${outbound.id}@203.0.113.11:30303")))
    run(new Fake(answer = Left("it is no longer connected")), drop(outbound.id)) shouldBe
      Left("it is no longer connected")
    val unasked = new Fake()
    run(unasked, drop(stranger)) shouldBe Left("no connected peer has the id ab12cd34… any more")
    unasked.calls shouldBe empty
  }

  // ---- peer block ---------------------------------------------------------------------------------------------------

  private def block(address: String): ActionRequest = request(Actions.PeerBlock, "address" -> address)

  "peer block" should "say how long the block lasts, the peers at the address, and what it does not reach" taggedAs (
    UnitTest
  ) in {
    preview(new Fake(), block("203.0.113.11")) shouldBe Preview(
      Vector(
        "Blocks 203.0.113.11 until this node restarts: connections from it are refused, and the node will not dial it.",
        s"1 connected peer at it (${short(outbound.id)}) is disconnected now.",
        "Discovery (UDP) packets to and from it are not blocked."
      )
    )
    preview(new Fake(), block("198.51.100.200")).lines(1) shouldBe "No connected peer is at that address now."
  }

  it should "count every peer at the address, whatever its port" taggedAs (UnitTest) in {
    val second = connected(2).copy(address = "203.0.113.11:40404")
    val p = preview(new Fake(), block("203.0.113.11"), withPeers(Seq(inbound, outbound, second)))
    p.lines(1) shouldBe s"2 connected peers at it (${short(outbound.id)}, ${short(second.id)}) are disconnected now."
  }

  // FAILS (reported): the peers at an address are found by comparing text, and the dashboard writes a peer's IPv6
  // address in full (`InetAddress.getHostAddress`) where an operator types it short.
  it should "find the peers at an IPv6 address however the address is written" taggedAs (UnitTest) in {
    val v6 = outbound.copy(address = "2001:db8:0:0:0:0:0:7:30303")
    val p = preview(new Fake(), block("2001:db8::7"), withPeers(Seq(inbound, v6)))
    p.lines(1) shouldBe s"1 connected peer at it (${short(v6.id)}) is disconnected now."
  }

  it should "be refused for an address already blocked" taggedAs (UnitTest) in {
    preview(new Fake(blocked = Set("203.0.113.11")), block("203.0.113.11")) shouldBe
      Preview(Vector.empty, Some("203.0.113.11 is already blocked"))
  }

  it should "hand the node the address, say it as the node wrote it down, and hand a failure back unchanged" taggedAs (
    UnitTest
  ) in {
    val ok = new Fake(written = _ => "2001:db8:0:0:0:0:0:7")
    run(ok, block("2001:db8::7")) shouldBe Right("2001:db8:0:0:0:0:0:7 is blocked until the node restarts")
    ok.calls shouldBe Seq("block" -> "2001:db8::7")
    run(new Fake(answer = Left("Not an IP address literal: nope")), block("nope")) shouldBe
      Left("Not an IP address literal: nope")
  }

  // ---- peer unblock -------------------------------------------------------------------------------------------------

  private def unblock(address: String): ActionRequest = request(Actions.PeerUnblock, "address" -> address)

  "peer unblock" should "say that the block and any automatic blacklisting go" taggedAs (UnitTest) in {
    preview(new Fake(blocked = Set("203.0.113.11")), unblock("203.0.113.11")) shouldBe Preview(
      Vector(
        "Removes the block on 203.0.113.11, and any automatic blacklisting of it.",
        "It can connect and be dialled again."
      )
    )
  }

  it should "be refused for an address that is not blocked, naming the ones that are" taggedAs (UnitTest) in {
    preview(new Fake(), unblock("203.0.113.11")) shouldBe
      Preview(Vector.empty, Some("203.0.113.11 is not blocked; no address is"))
    val six = Set("203.0.113.6", "203.0.113.5", "203.0.113.4", "203.0.113.3", "203.0.113.2", "203.0.113.1")
    preview(new Fake(blocked = six), unblock("203.0.113.11")) shouldBe Preview(
      Vector.empty,
      // The first five, in order.
      Some("203.0.113.11 is not blocked; blocked now: 203.0.113.1, 203.0.113.2, 203.0.113.3, 203.0.113.4, 203.0.113.5")
    )
  }

  // FAILS (reported): the node writes an IPv6 address down in full; typed short, the same address is refused as "not
  // blocked", so it can be unblocked only by typing it as the node wrote it.
  it should "know a blocked IPv6 address however it is written" taggedAs (UnitTest) in {
    val blocking = new Fake(blocked = Set("2001:db8:0:0:0:0:0:7"))
    preview(blocking, unblock("2001:db8::7")).refusal shouldBe None
    preview(blocking, block("2001:db8::7")).refusal.value should include("already blocked")
  }

  it should "hand the node the address, and hand its failure back unchanged" taggedAs (UnitTest) in {
    val ok = new Fake(blocked = Set("203.0.113.11"))
    run(ok, unblock("203.0.113.11")) shouldBe Right("203.0.113.11 is no longer blocked")
    ok.calls shouldBe Seq("unblock" -> "203.0.113.11")
    run(new Fake(answer = Left("it was not blocked")), unblock("203.0.113.11")) shouldBe Left("it was not blocked")
  }

  // ---- what an enode is made of -------------------------------------------------------------------------------------

  "A peer's address" should "be read as a host and a port, an IPv6 host in brackets or not" taggedAs (UnitTest) in {
    PeerActions.hostPort("203.0.113.9:30303") shouldBe Some("203.0.113.9" -> 30303)
    PeerActions.hostPort("/203.0.113.9:30303") shouldBe Some("203.0.113.9" -> 30303)
    PeerActions.hostPort("[2001:db8::7]:30303") shouldBe Some("2001:db8::7" -> 30303)
    PeerActions.hostPort("2001:db8:0:0:0:0:0:7:30303") shouldBe Some("2001:db8:0:0:0:0:0:7" -> 30303)
    PeerActions.hostPort("203.0.113.9") shouldBe None
    PeerActions.hostPort("203.0.113.9:0") shouldBe None
    PeerActions.hostPort("203.0.113.9:65536") shouldBe None
    PeerActions.hostPort("203.0.113.9:port") shouldBe None
    PeerActions.hostPort(":30303") shouldBe None
    PeerActions.hostPort("") shouldBe None
  }

  "A peer's enode" should "be its 128-digit id at its address, an IPv6 host in brackets" taggedAs (UnitTest) in {
    PeerActions.enode(outbound) shouldBe Some(s"enode://${outbound.id}@203.0.113.11:30303")
    PeerActions.enode(outbound.copy(id = "0x" + outbound.id.toUpperCase)) shouldBe
      Some(s"enode://${outbound.id}@203.0.113.11:30303")
    PeerActions.enode(outbound.copy(address = "2001:db8:0:0:0:0:0:7:30303")) shouldBe
      Some(s"enode://${outbound.id}@[2001:db8:0:0:0:0:0:7]:30303")
    // An id that is not a node id, or an address with no port: there is no enode to make.
    PeerActions.enode(outbound.copy(id = "ab12")) shouldBe None
    PeerActions.enode(outbound.copy(address = "203.0.113.11")) shouldBe None
  }

  "A node id" should "be shown by its first eight digits" taggedAs (UnitTest) in {
    PeerActions.brief(outbound.id) shouldBe short(outbound.id)
    PeerActions.brief("0x" + outbound.id.toUpperCase) shouldBe short(outbound.id)
    PeerActions.brief("AB12") shouldBe "ab12"
  }
