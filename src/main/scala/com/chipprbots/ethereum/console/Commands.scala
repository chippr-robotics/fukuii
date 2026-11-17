package com.chipprbots.ethereum.console

import java.util.Locale

/** What a line typed at the `:` prompt means (spec 029 FR-007, contracts/operator.md). */
enum Command:
  case OpenView(view: TuiView)

  /** `peer <id prefix>`: the details of the peer whose id starts so. */
  case OpenPeer(prefix: String)
  case Detach
  case Help
  case Glyphs(set: GlyphSet)

  /** `graphics <mode>`: draw graphics so, if the terminal can. */
  case Graphics(choice: GraphicsChoice)

  /** `art`: every sprite, a pose a page. */
  case Art

  /** `block`, `tx`, `receipt`, `account`: look it up and show it (spec 029 FR-028). */
  case Explore(query: LookupQuery)

  /** An action that asks to be confirmed, or that needs no confirmation and runs (spec 029 FR-048). `peer` actions that
    * name a connected peer carry what was typed of its id: [[Update]] finds the peer.
    */
  case Act(request: ops.ActionRequest)

  /** `config`, `tunables`, `deps`, `test-rpc`, `net`: a screen that shows and changes nothing. */
  case Show(screen: Screen)

  /** A known command used wrongly: `message` says how it is used. */
  case Usage(message: String)

  /** Not a command; `nearest` are the ones it may have meant. */
  case Unknown(word: String, nearest: Seq[String])

/** The prompt's commands: parsing, completion, and the nearest command to a mistyped one. Pure. */
object Commands:

  private val Fixed: Seq[String] =
    Seq("peer", "block", "tx", "receipt", "account", "glyphs", "graphics", "art", "help", "detach", "quit") ++
      Seq("config", "tunables", "deps", "test-rpc", "net", "loglevel", "maxpeers", "threshold", "mining") ++
      Seq("coinbase", "dbcheck", "detect-ip", "bundle")

  /** The commands that take an argument: completing one leaves a space after it. */
  private val WithArgument: Set[String] =
    Set("peer", "block", "tx", "receipt", "account", "glyphs", "graphics") ++
      Set("loglevel", "maxpeers", "threshold", "mining", "coinbase")

  import ops.ActionRequest
  import ops.Actions

  /** The levels a logger takes, as logback names them. */
  val LogLevels: Seq[String] = Seq("OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL")

  /** The most outgoing peers `maxpeers` takes: more is a mistyped number. */
  val MaxPeersCeiling: Int = 1000

  /** An IPv4 or IPv6 address written out, with no name in it: nothing here asks a name server. */
  private[console] def isIpLiteral(text: String): Boolean = ops.PeerActions.canonical(text).isDefined

  /** `enode://<128 hex digits>@<host>:<port>`, with nothing missing: a peer manager handed one with no node id fails
    * inside itself (herald, slice 7).
    */
  private[console] def isEnode(text: String): Boolean =
    scala.util
      .Try(new java.net.URI(text))
      .toOption
      .exists(uri =>
        uri.getScheme == "enode" && Option(uri.getUserInfo).exists(isHex(_, 128)) &&
          !Option(uri.getUserInfo).exists(_.startsWith("0x")) &&
          Option(uri.getHost).exists(_.nonEmpty) && uri.getPort >= 1 && uri.getPort <= 65535
      )

  /** `peer <verb> <argument>`: the actions on a peer. None when the words are not one of them, and `peer <id>` is the
    * peer's details as before.
    */
  private def peerAction(args: Seq[String]): Option[Command] =
    (args.headOption.map(_.toLowerCase(Locale.ROOT)), args.lift(1)) match
      case (Some("trust"), Some(id))   => Some(Command.Act(ActionRequest(Actions.PeerTrust.name, Vector("id" -> id))))
      case (Some("untrust"), Some(id)) => Some(Command.Act(ActionRequest(Actions.PeerUntrust.name, Vector("id" -> id))))
      case (Some("drop"), Some(id))    => Some(Command.Act(ActionRequest(Actions.PeerDrop.name, Vector("id" -> id))))
      case (Some("add"), Some(enode)) =>
        Some(
          if isEnode(enode) then Command.Act(ActionRequest(Actions.PeerAdd.name, Vector("enode" -> enode)))
          else Command.Usage("usage: peer add enode://<128 hex digits>@<host>:<port>")
        )
      case (Some(verb @ ("block" | "unblock")), Some(address)) =>
        val spec = if verb == "block" then Actions.PeerBlock else Actions.PeerUnblock
        Some(
          if isIpLiteral(address) then Command.Act(ActionRequest(spec.name, Vector("address" -> address)))
          else Command.Usage(s"usage: ${spec.usage}, the address written out (203.0.113.9), not a name")
        )
      // A verb with nothing after it is not a peer's id: `peer add` alone would open a peer whose id starts "add".
      case (Some(verb @ ("trust" | "untrust" | "drop" | "add" | "block" | "unblock")), None) =>
        Actions.named(s"peer $verb").map(spec => Command.Usage(s"usage: ${spec.usage}"))
      case _ => None

  /** The commands that are actions, by their first word. None when `word` is none of them. */
  private def action(word: String, args: Seq[String]): Option[Command] =
    def first: Option[String] = args.headOption.map(_.toLowerCase(Locale.ROOT))
    word match
      case "config"   => Some(Command.Show(Screen.Config(args.mkString(" ").toLowerCase(Locale.ROOT), 0)))
      case "tunables" => Some(Command.Show(Screen.Tunables))
      case "deps"     => Some(Command.Show(Screen.Deps))
      case "test-rpc" => Some(Command.Show(Screen.TestRpc))
      case "net"      => Some(Command.Show(Screen.Net))
      case "loglevel" =>
        Some(first match
          case Some("reset") if args.size == 1 =>
            Command.Act(ActionRequest(Actions.LogLevel.name, Vector("reset" -> "all")))
          case Some(level) if LogLevels.contains(level.toUpperCase(Locale.ROOT)) && args.size <= 2 =>
            Command.Act(
              ActionRequest(
                Actions.LogLevel.name,
                Vector("level" -> level.toUpperCase(Locale.ROOT), "logger" -> args.lift(1).getOrElse("ROOT"))
              )
            )
          case _ => Command.Usage(s"usage: ${Actions.LogLevel.usage}; a level is one of ${LogLevels.mkString(", ")}")
        )
      case "maxpeers" =>
        Some(
          args.headOption.filter(n => n.nonEmpty && n.length <= 4 && n.forall(_.isDigit)).map(_.toInt) match
            case Some(n) if n >= 1 && n <= MaxPeersCeiling && args.size == 1 =>
              Command.Act(ActionRequest(Actions.MaxPeers.name, Vector("n" -> n.toString)))
            case _ => Command.Usage(s"usage: ${Actions.MaxPeers.usage}, from 1 to $MaxPeersCeiling")
        )
      case "threshold" =>
        Some(first match
          case Some("reset") if args.size == 1 =>
            Command.Act(ActionRequest(Actions.Threshold.name, Vector("reset" -> "all")))
          case Some(name) if AlertThresholds.Keys.contains(name) && args.size >= 2 =>
            Command.Act(
              ActionRequest(Actions.Threshold.name, Vector("name" -> name, "value" -> args.tail.mkString(" ")))
            )
          case _ =>
            Command.Usage(s"usage: ${Actions.Threshold.usage}; a name is one of ${AlertThresholds.Keys.mkString(", ")}")
        )
      case "mining" =>
        Some(first match
          case Some("start") if args.size == 1 => Command.Act(ActionRequest(Actions.MiningStart.name))
          case Some("stop") if args.size == 1  => Command.Act(ActionRequest(Actions.MiningStop.name))
          case _                               => Command.Usage("usage: mining start | mining stop")
        )
      case "coinbase" =>
        Some(
          args.headOption.filter(a => args.size == 1 && a.startsWith("0x") && isHex(a, 40)) match
            case Some(address) => Command.Act(ActionRequest(Actions.Coinbase.name, Vector("address" -> hex(address))))
            case None          => Command.Usage("usage: coinbase <address>: 0x followed by exactly 40 hex digits")
        )
      case "rpc" =>
        (first, args.lift(1).map(_.toLowerCase(Locale.ROOT))) match
          case (Some(verb @ ("stop" | "start")), Some(transport @ ("http" | "ws"))) if args.size == 2 =>
            val spec = if verb == "stop" then Actions.RpcStop else Actions.RpcStart
            Some(Command.Act(ActionRequest(spec.name, Vector("transport" -> transport))))
          case (Some("stop" | "start"), _) => Some(Command.Usage("usage: rpc stop <http | ws> | rpc start <http | ws>"))
          // `rpc` alone, or with anything else, is the view.
          case _ => None
      case "dbcheck"   => Some(Command.Act(ActionRequest(Actions.DbCheck.name)))
      case "detect-ip" => Some(Command.Act(ActionRequest(Actions.DetectIp.name)))
      case "bundle"    => Some(Command.Act(ActionRequest(Actions.Bundle.name)))
      case "quit"      => Some(Command.Act(ActionRequest(Actions.Quit.name)))
      case "compact" =>
        Some(Command.Usage(s"compact is not offered: ${Actions.NotOffered.toMap.apply("Compact the database")}"))
      case "peer" => peerAction(args)
      case _      => None

  private def isHex(text: String, digits: Int): Boolean =
    val body = if text.startsWith("0x") || text.startsWith("0X") then text.drop(2) else text
    body.length == digits && body.forall(ch => Character.digit(ch, 16) >= 0)

  /** `0x` and the digits in lower case, whether or not the `0x` was typed. */
  private def hex(text: String): String =
    "0x" + (if text.startsWith("0x") || text.startsWith("0X") then text.drop(2) else text).toLowerCase(Locale.ROOT)

  /** `block` with a number (commas allowed), a hash, `latest`, or nothing at all, which is the head. */
  private def block(arg: Option[String]): Command = arg.map(_.replace(",", "")) match
    case None | Some("latest") | Some("head") => Command.Explore(LookupQuery.LatestBlock)
    case Some(n) if n.nonEmpty && n.forall(_.isDigit) && n.length <= 18 =>
      Command.Explore(LookupQuery.BlockByNumber(n.toLong))
    case Some(h) if isHex(h, 64) => Command.Explore(LookupQuery.BlockByHash(hex(h)))
    case _                       => Command.Usage("usage: block <number> | <hash: 0x and 64 digits> | latest")

  private def hashed(arg: Option[String], word: String, of: String => LookupQuery): Command =
    arg
      .filter(isHex(_, 64))
      .fold(Command.Usage(s"usage: $word <transaction hash: 0x and 64 digits>"))(h => Command.Explore(of(hex(h))))

  /** Every command word, the views first. */
  val Words: Seq[String] = TuiView.values.toSeq.map(_.title.toLowerCase(Locale.ROOT)) ++ Fixed

  def parse(line: String): Command =
    val words = line.trim.split("\\s+").toSeq.filter(_.nonEmpty)
    words.headOption.map(_.toLowerCase(Locale.ROOT)) match
      case None => Command.Unknown("", Nil)
      case Some(word) =>
        val args = words.tail
        action(word, args).getOrElse(TuiView.named(word) match
          case Some(view) => Command.OpenView(view)
          case None =>
            word match
              case "detach" => Command.Detach
              case "help"   => Command.Help
              // With no id it is the list: Tab completes `pe` to `peer`, the common start of `peers` and `peer`, and
              // Enter after that must go somewhere.
              case "peer" => args.headOption.fold(Command.OpenView(TuiView.Peers))(Command.OpenPeer(_))
              case "glyphs" =>
                args.headOption.map(_.toLowerCase(Locale.ROOT)) match
                  case Some("emoji") => Command.Glyphs(GlyphSet.Emoji)
                  case Some("ascii") => Command.Glyphs(GlyphSet.Ascii)
                  case _             => Command.Usage("usage: glyphs emoji | ascii")
              case "graphics" =>
                args.headOption.flatMap(GraphicsChoice.parse) match
                  case Some(choice) => Command.Graphics(choice)
                  case None         => Command.Usage("usage: graphics auto | pixel | block | text")
              case "art"     => Command.Art
              case "block"   => block(args.headOption)
              case "tx"      => hashed(args.headOption, "tx", LookupQuery.Tx(_))
              case "receipt" => hashed(args.headOption, "receipt", LookupQuery.Receipt(_))
              case "account" =>
                args.headOption
                  .filter(isHex(_, 40))
                  .fold(Command.Usage("usage: account <address: 0x and 40 digits>"))(a =>
                    Command.Explore(LookupQuery.Account(hex(a)))
                  )
              case _ => Command.Unknown(word, nearest(word))
        )

  /** `line` with its first word completed as far as the commands that start with it agree; unchanged when none does or
    * when the first word is already followed by an argument.
    */
  def complete(line: String): String =
    if line.contains(' ') || line.isEmpty then line
    else
      val lower = line.toLowerCase(Locale.ROOT)
      val matches = Words.filter(_.startsWith(lower))
      matches match
        case Seq()     => line
        case Seq(only) => only + (if WithArgument.contains(only) then " " else "")
        case several   => several.reduce(commonPrefix)

  /** The commands `word` may have meant: those it is the start of, else those within two edits of it. */
  def nearest(word: String): Seq[String] =
    val lower = word.toLowerCase(Locale.ROOT)
    val starting = Words.filter(_.startsWith(lower))
    if starting.nonEmpty then starting
    else Words.map(w => w -> distance(lower, w)).filter(_._2 <= 2).sortBy(_._2).map(_._1)

  private def commonPrefix(a: String, b: String): String = a.zip(b).takeWhile(_ == _).map(_._1).mkString

  /** The edit distance between two words. */
  private[console] def distance(a: String, b: String): Int =
    val row = Array.tabulate(b.length + 1)(identity)
    for i <- 1 to a.length do
      var diagonal = row(0)
      row(0) = i
      for j <- 1 to b.length do
        val above = row(j)
        row(j) = (row(j) + 1).min(row(j - 1) + 1).min(diagonal + (if a(i - 1) == b(j - 1) then 0 else 1))
        diagonal = above
    row(b.length)
