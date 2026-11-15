package com.chipprbots.ethereum.console

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.ops.ActionRequest
import com.chipprbots.ethereum.console.ops.Actions
import com.chipprbots.ethereum.testing.Tags.*

/** The prompt's commands (spec 029 FR-007, contracts/operator.md): parsing, completion, the nearest command. */
class CommandsSpec extends AnyFlatSpec with Matchers:

  "parse" should "read a view's name in any case, with spaces around it" taggedAs (UnitTest) in {
    for v <- TuiView.values do
      Commands.parse(v.title) shouldBe Command.OpenView(v)
      Commands.parse(s"  ${v.title.toUpperCase}  ") shouldBe Command.OpenView(v)
  }

  it should "read the fixed commands and their arguments" taggedAs (UnitTest) in {
    Commands.parse("quit") shouldBe Command.Act(TuiFixtures.Quit)
    Commands.parse("detach") shouldBe Command.Detach
    Commands.parse("help") shouldBe Command.Help
    Commands.parse("peer 0xab12") shouldBe Command.OpenPeer("0xab12")
    Commands.parse("glyphs ASCII") shouldBe Command.Glyphs(GlyphSet.Ascii)
    Commands.parse("glyphs emoji") shouldBe Command.Glyphs(GlyphSet.Emoji)
  }

  it should "say how a command is used when its argument is missing or wrong" taggedAs (UnitTest) in {
    // With no id it is the list: what `:pe`, Tab, Enter reaches (the terminal gate of slices 1 and 2 found it a dead end).
    Commands.parse("peer") shouldBe Command.OpenView(TuiView.Peers)
    Commands.parse(Commands.complete("pe")) shouldBe Command.OpenView(TuiView.Peers)
    Commands.parse("glyphs") shouldBe a[Command.Usage]
    Commands.parse("glyphs braille") shouldBe a[Command.Usage]
  }

  it should "name the nearest commands for a word it does not know" taggedAs (UnitTest) in {
    Commands.parse("mempol") shouldBe Command.Unknown("mempol", Seq("mempool"))
    Commands.parse("stor") shouldBe Command.Unknown("stor", Seq("storage"))
    Commands.parse("xyzzy") shouldBe Command.Unknown("xyzzy", Nil)
    Commands.parse("   ") shouldBe Command.Unknown("", Nil)
  }

  "complete" should "finish a word only one command starts with, and go as far as several agree" taggedAs (UnitTest) in {
    Commands.complete("me") shouldBe "mempool"
    Commands.complete("m") shouldBe "m" // mempool, maxpeers and mining agree on no more
    Commands.complete("thr") shouldBe "threshold "
    Commands.complete("ov") shouldBe "overview"
    Commands.complete("gl") shouldBe "glyphs "
    Commands.complete("p") shouldBe "peer" // peers, peer
    Commands.complete("s") shouldBe "s" // storage, system
    Commands.complete("st") shouldBe "storage"
    Commands.complete("zz") shouldBe "zz"
    Commands.complete("peer 0x") shouldBe "peer 0x" // an argument is not completed
    Commands.complete("") shouldBe ""
  }

  "Every view" should "be a command" taggedAs (UnitTest) in {
    for v <- TuiView.values do Commands.Words should contain(v.title.toLowerCase)
    Commands.Words.distinct shouldBe Commands.Words
  }

  "distance" should "count edits" taggedAs (UnitTest) in {
    Commands.distance("logs", "logs") shouldBe 0
    Commands.distance("log", "logs") shouldBe 1
    Commands.distance("pers", "peers") shouldBe 1
    Commands.distance("", "ops") shouldBe 3
  }

  // ---- the operator's actions (spec 029 FR-054, T097; contracts/actions.md) -----------------------------------------

  private def act(spec: ops.ActionSpec, params: (String, String)*): Command =
    Command.Act(ActionRequest(spec.name, params.toVector))

  private val NodeId = "ab12cd34" * 16
  private val Enode = s"enode://$NodeId@198.51.100.7:30303"
  private val Address = "0x1000000000000000000000000000000000000001"

  /** A command line for each action that asks to be confirmed, with what it parses to. */
  private val actions: Seq[(String, Command)] = Seq(
    "loglevel debug" -> act(Actions.LogLevel, "level" -> "DEBUG", "logger" -> "ROOT"),
    "loglevel WARN com.chipprbots.ethereum.network" ->
      act(Actions.LogLevel, "level" -> "WARN", "logger" -> "com.chipprbots.ethereum.network"),
    "LOGLEVEL Trace" -> act(Actions.LogLevel, "level" -> "TRACE", "logger" -> "ROOT"),
    "loglevel off ROOT" -> act(Actions.LogLevel, "level" -> "OFF", "logger" -> "ROOT"),
    "loglevel reset" -> act(Actions.LogLevel, "reset" -> "all"),
    "maxpeers 40" -> act(Actions.MaxPeers, "n" -> "40"),
    "maxpeers 1" -> act(Actions.MaxPeers, "n" -> "1"),
    "maxpeers 1000" -> act(Actions.MaxPeers, "n" -> "1000"),
    "maxpeers 0040" -> act(Actions.MaxPeers, "n" -> "40"),
    // A peer is named by the start of its id, as it was typed: `Update` finds the peer.
    "peer trust 3fa1" -> act(Actions.PeerTrust, "id" -> "3fa1"),
    "peer untrust 0x3FA1" -> act(Actions.PeerUntrust, "id" -> "0x3FA1"),
    "peer drop 3fa1" -> act(Actions.PeerDrop, "id" -> "3fa1"),
    "Peer DROP 3fa1" -> act(Actions.PeerDrop, "id" -> "3fa1"),
    s"peer add $Enode" -> act(Actions.PeerAdd, "enode" -> Enode),
    s"peer add enode://$NodeId@[2001:db8::7]:30303" -> act(
      Actions.PeerAdd,
      "enode" -> s"enode://$NodeId@[2001:db8::7]:30303"
    ),
    "peer block 203.0.113.9" -> act(Actions.PeerBlock, "address" -> "203.0.113.9"),
    "peer block 2001:db8::7" -> act(Actions.PeerBlock, "address" -> "2001:db8::7"),
    "peer unblock 203.0.113.9" -> act(Actions.PeerUnblock, "address" -> "203.0.113.9"),
    "peer unblock ::1" -> act(Actions.PeerUnblock, "address" -> "::1"),
    "threshold stall-after 5m" -> act(Actions.Threshold, "name" -> "stall-after", "value" -> "5m"),
    // A value may be two words.
    "threshold disk-warn 50 GiB" -> act(Actions.Threshold, "name" -> "disk-warn", "value" -> "50 GiB"),
    "threshold reset" -> act(Actions.Threshold, "reset" -> "all"),
    "mining start" -> act(Actions.MiningStart),
    "mining stop" -> act(Actions.MiningStop),
    "Mining STOP" -> act(Actions.MiningStop),
    s"coinbase $Address" -> act(Actions.Coinbase, "address" -> Address),
    "coinbase 0xABCDEF0000000000000000000000000000000001" ->
      act(Actions.Coinbase, "address" -> "0xabcdef0000000000000000000000000000000001"),
    "rpc stop http" -> act(Actions.RpcStop, "transport" -> "http"),
    "rpc stop ws" -> act(Actions.RpcStop, "transport" -> "ws"),
    "rpc start http" -> act(Actions.RpcStart, "transport" -> "http"),
    "RPC Start WS" -> act(Actions.RpcStart, "transport" -> "ws"),
    "dbcheck" -> act(Actions.DbCheck),
    "detect-ip" -> act(Actions.DetectIp),
    "bundle" -> act(Actions.Bundle),
    "quit" -> act(Actions.Quit)
  )

  /** A command line for each Observe action: a screen opens, and nothing asks to be confirmed. */
  private val observing: Seq[(String, String, Command)] = Seq(
    ("config", "config", Command.Show(Screen.Config("", 0))),
    ("config", "config RPC http", Command.Show(Screen.Config("rpc http", 0))),
    ("tunables", "tunables", Command.Show(Screen.Tunables)),
    ("deps", "deps", Command.Show(Screen.Deps)),
    ("test-rpc", "test-rpc", Command.Show(Screen.TestRpc)),
    ("net", "net", Command.Show(Screen.Net)),
    ("art", "art", Command.Art),
    ("lookup", "block latest", Command.Explore(LookupQuery.LatestBlock)),
    ("lookup", "block 21,884,102", Command.Explore(LookupQuery.BlockByNumber(21_884_102L))),
    ("lookup", s"tx 0x${"ab" * 32}", Command.Explore(LookupQuery.Tx(s"0x${"ab" * 32}"))),
    ("lookup", s"receipt 0x${"ab" * 32}", Command.Explore(LookupQuery.Receipt(s"0x${"ab" * 32}"))),
    ("lookup", s"account $Address", Command.Explore(LookupQuery.Account(Address)))
  )

  "An action's command" should "be read as the request its dialog asks for" taggedAs (UnitTest) in {
    for (line, command) <- actions do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe command
        Commands.parse(s"   $line  ") shouldBe command
      }
  }

  it should "open a screen and ask nothing when the action is an Observe" taggedAs (UnitTest) in {
    for (_, line, command) <- observing do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe command
      }
  }

  "Every action of the registry" should "have a command that asks for it" taggedAs (UnitTest) in {
    val asked = actions.collect { case (_, Command.Act(request)) => request.action } ++ observing.map(_._1)
    asked.toSet shouldBe Actions.all.map(_.name).toSet
    // And the action a command asks for is one the registry has.
    for case (line, Command.Act(request)) <- actions do
      withClue(s"'$line': ") {
        Actions.named(request.action) should not be empty
      }
  }

  it should "be offered by the prompt's completion, by its first word" taggedAs (UnitTest) in {
    // `lookup` is typed as block, tx, receipt or account; `rpc stop` begins with the RPC view's name.
    val words = Actions.all.filterNot(_ == Actions.Lookup).map(_.name.takeWhile(_ != ' ')).distinct
    for word <- words do Commands.Words should contain(word)
    for word <- Seq("block", "tx", "receipt", "account") do Commands.Words should contain(word)
    Commands.Words should not contain "compact"
  }

  "loglevel" should "say how it is used for a level that is none, and for too many words" taggedAs (UnitTest) in {
    val usage = Command.Usage(
      "usage: loglevel <level> [logger] | loglevel reset; a level is one of OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL"
    )
    for line <- Seq(
        "loglevel",
        "loglevel loud",
        "loglevel 3",
        "loglevel debug a b",
        "loglevel reset now",
        "loglevel fukuii.sync debug"
      )
    do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
    Commands.LogLevels shouldBe Seq("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL")
  }

  "maxpeers" should "say how it is used for what is no number, and for a number out of range" taggedAs (UnitTest) in {
    val usage = Command.Usage("usage: maxpeers <n>, from 1 to 1000")
    for line <- Seq(
        "maxpeers",
        "maxpeers many",
        "maxpeers 0",
        "maxpeers 1001",
        "maxpeers 12345",
        "maxpeers 99999999999999999999",
        "maxpeers -3",
        "maxpeers 2.5",
        "maxpeers 40 50",
        "maxpeers 0x10"
      )
    do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
    Commands.MaxPeersCeiling shouldBe 1000
  }

  "peer add" should "say how it is used for what is no whole enode" taggedAs (UnitTest) in {
    val usage = Command.Usage("usage: peer add enode://<128 hex digits>@<host>:<port>")
    for line <- Seq(
        "peer add enode://ab12@198.51.100.7:30303", // an id that is no node id
        s"peer add enode://$NodeId@198.51.100.7", // no port
        s"peer add enode://$NodeId@198.51.100.7:0",
        s"peer add enode://$NodeId@198.51.100.7:70000",
        s"peer add enode://$NodeId@:30303", // no host
        s"peer add enode://198.51.100.7:30303", // no id
        s"peer add enode://0x${NodeId.drop(2)}@198.51.100.7:30303",
        s"peer add enode://${NodeId.dropRight(1)}g@198.51.100.7:30303",
        s"peer add http://$NodeId@198.51.100.7:30303",
        s"peer add $NodeId@198.51.100.7:30303",
        "peer add 198.51.100.7:30303",
        s"peer add $NodeId"
      )
    do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
  }

  "peer block and peer unblock" should "say how they are used for a name, and for what is no address" taggedAs (
    UnitTest
  ) in {
    val block = Command.Usage("usage: peer block <address>, the address written out (203.0.113.9), not a name")
    val unblock = Command.Usage("usage: peer unblock <address>, the address written out (203.0.113.9), not a name")
    for address <- Seq(
        "example.org",
        "localhost",
        "256.0.113.9",
        "203.0.113",
        "203.0.113.9.1",
        "203.0.113.9:30303",
        "[::1]",
        "fe80::1%eth0"
      )
    do
      withClue(s"'$address': ") {
        Commands.parse(s"peer block $address") shouldBe block
        Commands.parse(s"peer unblock $address") shouldBe unblock
      }
  }

  "An address written out" should "be an IPv4 or an IPv6 address, and never a name" taggedAs (UnitTest) in {
    for address <- Seq(
        "203.0.113.9",
        "0.0.0.0",
        "255.255.255.255",
        "::1",
        "2001:db8::7",
        "2001:db8:0:0:0:0:0:7",
        "::ffff:203.0.113.9"
      )
    do
      withClue(s"'$address': ") {
        Commands.isIpLiteral(address) shouldBe true
      }
    for address <- Seq(
        "",
        "example.org",
        "localhost",
        "256.0.113.9",
        "1.2.3",
        "a.b.c.d",
        "dead.beef",
        "[::1]",
        "fe80::1%eth0",
        "host:30303"
      )
    do
      withClue(s"'$address': ") {
        Commands.isIpLiteral(address) shouldBe false
      }
  }

  // FAILS (reported): anything made of hex digits and at least two colons is taken for an IPv6 address. Its dialog
  // opens with a whole preview, and the action then fails in the node ("Not an IP address literal").
  it should "not be what is only made of an address's characters" taggedAs (UnitTest) in {
    for address <- Seq(":::", "1:2:3", "12345::1", "::g", "1:2:3:4:5:6:7:8:9", "2001:db8:::7")
    do
      withClue(s"'$address': ") {
        Commands.isIpLiteral(address) shouldBe false
      }
  }

  "coinbase" should "say how it is used for what is no address" taggedAs (UnitTest) in {
    val usage = Command.Usage("usage: coinbase <address>: 0x followed by exactly 40 hex digits")
    for line <- Seq(
        "coinbase",
        "coinbase 0x12",
        s"coinbase ${Address.drop(2)}", // no 0x
        s"coinbase ${Address}00",
        s"coinbase ${Address.dropRight(1)}g",
        s"coinbase $Address $Address"
      )
    do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
  }

  "mining" should "say how it is used for anything but start and stop" taggedAs (UnitTest) in {
    val usage = Command.Usage("usage: mining start | mining stop")
    for line <- Seq("mining", "mining pause", "mining stop now", "mining start 4") do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
  }

  "rpc" should "say how stop and start are used, and be the RPC view otherwise" taggedAs (UnitTest) in {
    val usage = Command.Usage("usage: rpc stop <http | ws> | rpc start <http | ws>")
    for line <- Seq(
        "rpc stop",
        "rpc start",
        "rpc stop ipc",
        "rpc start engine",
        "rpc stop http now",
        "rpc start http ws"
      )
    do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
    Commands.parse("rpc") shouldBe Command.OpenView(TuiView.Rpc)
    Commands.parse("RPC") shouldBe Command.OpenView(TuiView.Rpc)
  }

  "threshold" should "say how it is used for a name that is no threshold, and for no value" taggedAs (UnitTest) in {
    val usage = Command.Usage(
      s"usage: threshold <name> <value> | threshold reset; a name is one of ${AlertThresholds.Keys.mkString(", ")}"
    )
    for line <- Seq("threshold", "threshold peer-count 3", "threshold stall-after", "threshold reset all") do
      withClue(s"'$line': ") {
        Commands.parse(line) shouldBe usage
      }
    // Every threshold is a name it takes.
    for key <- AlertThresholds.Keys do
      Commands.parse(s"threshold $key 1") shouldBe act(Actions.Threshold, "name" -> key, "value" -> "1")
  }

  "compact" should "answer that it is not offered, and why" taggedAs (UnitTest) in {
    val answer = Commands.parse("compact")
    answer shouldBe Command.Usage(
      "compact is not offered: it holds the database lock for its whole run, so the node would import nothing until " +
        "it ended, which on a large database may take hours and cannot be stopped (B-281)"
    )
    Commands.parse("compact now") shouldBe answer
    Commands.parse("COMPACT") shouldBe answer
  }

  "A peer action with nothing after it" should "say how it is used, and not open a peer whose id starts with the verb" taggedAs (
    UnitTest
  ) in {
    // `add` is three hex digits: read as the start of an id it would open a peer.
    for verb <- Seq("trust", "untrust", "add", "drop", "block", "unblock") do
      Commands.parse(s"peer $verb") shouldBe Command.Usage(s"usage: ${Actions.named(s"peer $verb").get.usage}")
  }
