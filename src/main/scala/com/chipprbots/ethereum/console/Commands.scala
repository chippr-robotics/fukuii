package com.chipprbots.ethereum.console

import java.util.Locale

/** What a line typed at the `:` prompt means (spec 029 FR-007, contracts/operator.md). */
enum Command:
  case OpenView(view: TuiView)

  /** `peer <id prefix>`: the details of the peer whose id starts so. */
  case OpenPeer(prefix: String)
  case Quit
  case Detach
  case Help
  case Glyphs(set: GlyphSet)

  /** `graphics <mode>`: draw graphics so, if the terminal can. */
  case Graphics(choice: GraphicsChoice)

  /** `art`: every sprite, a pose a page. */
  case Art

  /** `block`, `tx`, `receipt`, `account`: look it up and show it (spec 029 FR-028). */
  case Explore(query: LookupQuery)

  /** A known command used wrongly: `message` says how it is used. */
  case Usage(message: String)

  /** Not a command; `nearest` are the ones it may have meant. */
  case Unknown(word: String, nearest: Seq[String])

/** The prompt's commands: parsing, completion, and the nearest command to a mistyped one. Pure. */
object Commands:

  private val Fixed: Seq[String] =
    Seq("peer", "block", "tx", "receipt", "account", "glyphs", "graphics", "art", "help", "detach", "quit")

  /** The commands that take an argument: completing one leaves a space after it. */
  private val WithArgument: Set[String] = Set("peer", "block", "tx", "receipt", "account", "glyphs", "graphics")

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
        TuiView.named(word) match
          case Some(view) => Command.OpenView(view)
          case None =>
            word match
              case "quit"   => Command.Quit
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
