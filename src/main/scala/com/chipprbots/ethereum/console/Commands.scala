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

  /** A known command used wrongly: `message` says how it is used. */
  case Usage(message: String)

  /** Not a command; `nearest` are the ones it may have meant. */
  case Unknown(word: String, nearest: Seq[String])

/** The prompt's commands: parsing, completion, and the nearest command to a mistyped one. Pure. */
object Commands:

  private val Fixed: Seq[String] = Seq("peer", "glyphs", "help", "detach", "quit")

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
              case "peer" =>
                args.headOption.fold(Command.Usage("usage: peer <the start of a peer's id>"))(Command.OpenPeer(_))
              case "glyphs" =>
                args.headOption.map(_.toLowerCase(Locale.ROOT)) match
                  case Some("emoji") => Command.Glyphs(GlyphSet.Emoji)
                  case Some("ascii") => Command.Glyphs(GlyphSet.Ascii)
                  case _             => Command.Usage("usage: glyphs emoji | ascii")
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
        case Seq(only) => only + (if only == "peer" || only == "glyphs" then " " else "")
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
