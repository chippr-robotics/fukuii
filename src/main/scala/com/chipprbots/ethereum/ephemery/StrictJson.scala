package com.chipprbots.ethereum.ephemery

import scala.collection.mutable

/** A strict JSON reader and a `jq`-format writer, for the files of an Ephemery release.
  *
  * Why not a library: the rules of a release need three things the usual parsers do not give. A duplicate key,
  * anywhere, is an error (`jq`, go-ethereum and a last-wins parser would read different genesis files). A number keeps
  * its source text, so a value above 2^53 stays exact and a float or an exponent can be told from an integer. And the
  * writer keeps the order of the keys and prints the way `jq` does, which is what the committed fixtures hold byte for
  * byte.
  *
  * No actors, no `Config`, no clock.
  */
object StrictJson:

  sealed trait Js
  final case class JObj(fields: Vector[(String, Js)]) extends Js:
    def get(key: String): Option[Js] = fields.collectFirst { case (`key`, v) => v }
    def keys: Vector[String] = fields.map(_._1)
  final case class JArr(items: Vector[Js]) extends Js
  final case class JStr(value: String) extends Js

  /** A number as written. */
  final case class JNum(raw: String) extends Js:
    def isInteger: Boolean = raw.length <= MaxNumberChars && JNum.IntegerLiteral.matcher(raw).matches()
    def toBigInt: Option[BigInt] = Option.when(isInteger)(BigInt(raw))
  final case class JBool(value: Boolean) extends Js
  case object JNull extends Js

  object JNum:
    private[StrictJson] val IntegerLiteral = "-?(0|[1-9][0-9]*)".r.pattern

  /** A number longer than this is refused: no field of a release is, and `BigInt` of the text is then bounded. */
  val MaxNumberChars: Int = 80

  /** Nesting deeper than this is refused: a release is about five levels deep, and the input may come from a network.
    */
  val MaxDepth: Int = 64

  /** Parses `text` as one JSON value. `Left` names the first problem, and for a duplicate key the key. */
  def parse(text: String): Either[String, Js] =
    val reader = new Reader(text)
    try
      val value = reader.value(0)
      reader.skipWhitespace()
      if reader.atEnd then Right(value) else Left(s"unexpected text after the value at offset ${reader.pos}")
    catch case e: ParseError => Left(e.getMessage)

  final private class ParseError(msg: String) extends RuntimeException(msg, null, false, false)

  final private class Reader(text: String):
    var pos: Int = 0

    def atEnd: Boolean = pos >= text.length

    private def fail(msg: String): Nothing = throw new ParseError(s"$msg at offset $pos")

    def skipWhitespace(): Unit =
      while pos < text.length && (text.charAt(pos) match
          case ' ' | '\t' | '\n' | '\r' => true
          case _                        => false
        )
      do pos += 1

    private def expect(c: Char): Unit =
      if pos < text.length && text.charAt(pos) == c then pos += 1 else fail(s"expected '$c'")

    private def literal(word: String, js: Js): Js =
      if text.startsWith(word, pos) then
        pos += word.length
        js
      else fail(s"unexpected token")

    def value(depth: Int): Js =
      if depth >= MaxDepth then fail(s"nesting deeper than $MaxDepth")
      skipWhitespace()
      if atEnd then fail("unexpected end of input")
      text.charAt(pos) match
        case '{'                                     => obj(depth)
        case '['                                     => arr(depth)
        case '"'                                     => JStr(string())
        case 't'                                     => literal("true", JBool(true))
        case 'f'                                     => literal("false", JBool(false))
        case 'n'                                     => literal("null", JNull)
        case c if c == '-' || (c >= '0' && c <= '9') => number()
        case _                                       => fail("unexpected character")

    private def obj(depth: Int): Js =
      expect('{')
      val fields = Vector.newBuilder[(String, Js)]
      val seen = mutable.HashSet.empty[String]
      skipWhitespace()
      if pos < text.length && text.charAt(pos) == '}' then
        pos += 1
        JObj(Vector.empty)
      else
        var more = true
        while more do
          skipWhitespace()
          if atEnd || text.charAt(pos) != '"' then fail("expected a key")
          val key = string()
          if !seen.add(key) then fail(s"the key \"$key\" is declared twice")
          skipWhitespace()
          expect(':')
          fields += (key -> value(depth + 1))
          skipWhitespace()
          if pos < text.length && text.charAt(pos) == ',' then pos += 1
          else
            expect('}')
            more = false
        JObj(fields.result())

    private def arr(depth: Int): Js =
      expect('[')
      val items = Vector.newBuilder[Js]
      skipWhitespace()
      if pos < text.length && text.charAt(pos) == ']' then
        pos += 1
        JArr(Vector.empty)
      else
        var more = true
        while more do
          items += value(depth + 1)
          skipWhitespace()
          if pos < text.length && text.charAt(pos) == ',' then pos += 1
          else
            expect(']')
            more = false
        JArr(items.result())

    private def string(): String =
      expect('"')
      val sb = new java.lang.StringBuilder
      var done = false
      while !done do
        if atEnd then fail("unterminated string")
        val c = text.charAt(pos)
        pos += 1
        c match
          case '"' => done = true
          case '\\' =>
            if atEnd then fail("unterminated escape")
            val e = text.charAt(pos)
            pos += 1
            e match
              case '"'  => sb.append('"')
              case '\\' => sb.append('\\')
              case '/'  => sb.append('/')
              case 'b'  => sb.append('\b')
              case 'f'  => sb.append('\f')
              case 'n'  => sb.append('\n')
              case 'r'  => sb.append('\r')
              case 't'  => sb.append('\t')
              case 'u' =>
                if pos + 4 > text.length then fail("short \\u escape")
                val hex = text.substring(pos, pos + 4)
                if !hex.forall(h => Character.digit(h, 16) >= 0) then fail("bad \\u escape")
                sb.append(Integer.parseInt(hex, 16).toChar)
                pos += 4
              case _ => fail("bad escape")
          case c if c < ' ' => fail("control character in a string")
          case c            => sb.append(c)
      checkSurrogates(sb)
      sb.toString

    /** `\u` escapes may not leave a lone surrogate: it is not text, and two readers would read it differently. */
    private def checkSurrogates(sb: java.lang.StringBuilder): Unit =
      var i = 0
      while i < sb.length do
        val c = sb.charAt(i)
        if Character.isHighSurrogate(c) && i + 1 < sb.length && Character.isLowSurrogate(sb.charAt(i + 1)) then i += 2
        else if Character.isSurrogate(c) then fail("an unpaired surrogate in a string")
        else i += 1

    private def number(): Js =
      val start = pos
      if text.charAt(pos) == '-' then pos += 1
      def digits(): Unit =
        val from = pos
        while pos < text.length && text.charAt(pos) >= '0' && text.charAt(pos) <= '9' do pos += 1
        if pos == from then fail("bad number")
      if pos < text.length && text.charAt(pos) == '0' then pos += 1
      else digits()
      if pos < text.length && text.charAt(pos) == '.' then
        pos += 1
        digits()
      if pos < text.length && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E') then
        pos += 1
        if pos < text.length && (text.charAt(pos) == '+' || text.charAt(pos) == '-') then pos += 1
        digits()
      if pos - start > MaxNumberChars then fail(s"a number longer than $MaxNumberChars characters")
      JNum(text.substring(start, pos))

  /** The way `jq` prints: two-space indent, `"key": value`, an empty object or array as `{}` or `[]`, one trailing
    * newline, keys in the order they are held, strings with only the escapes `jq` writes.
    */
  def render(js: Js): String =
    val sb = new java.lang.StringBuilder
    write(js, 0, sb)
    sb.append('\n')
    sb.toString

  private def indent(sb: java.lang.StringBuilder, depth: Int): Unit =
    var i = 0
    while i < depth do
      sb.append("  ")
      i += 1

  private def write(js: Js, depth: Int, sb: java.lang.StringBuilder): Unit = js match
    case JNull                        => sb.append("null")
    case JBool(b)                     => sb.append(b.toString)
    case JNum(r)                      => sb.append(r)
    case JStr(s)                      => quote(s, sb)
    case JArr(items) if items.isEmpty => sb.append("[]")
    case JArr(items) =>
      sb.append("[\n")
      items.zipWithIndex.foreach { case (item, i) =>
        if i > 0 then sb.append(",\n")
        indent(sb, depth + 1)
        write(item, depth + 1, sb)
      }
      sb.append('\n')
      indent(sb, depth)
      sb.append(']')
    case JObj(fields) if fields.isEmpty => sb.append("{}")
    case JObj(fields) =>
      sb.append("{\n")
      fields.zipWithIndex.foreach { case ((k, v), i) =>
        if i > 0 then sb.append(",\n")
        indent(sb, depth + 1)
        quote(k, sb)
        sb.append(": ")
        write(v, depth + 1, sb)
      }
      sb.append('\n')
      indent(sb, depth)
      sb.append('}')

  private def quote(s: String, sb: java.lang.StringBuilder): Unit =
    sb.append('"')
    s.foreach {
      case '"'                           => sb.append("\\\"")
      case '\\'                          => sb.append("\\\\")
      case '\n'                          => sb.append("\\n")
      case '\t'                          => sb.append("\\t")
      case '\r'                          => sb.append("\\r")
      case '\b'                          => sb.append("\\b")
      case '\f'                          => sb.append("\\f")
      case c if c < ' ' || c == '\u007f' => sb.append(f"\\u${c.toInt}%04x")
      case c                             => sb.append(c)
    }
    sb.append('"')

  /** The compact text of a scalar, as `jq`'s `tostring` gives it (for messages). */
  def show(js: Js): String = js match
    case JStr(s)  => s
    case JNum(r)  => r
    case JBool(b) => b.toString
    case JNull    => "null"
    case other    => render(other).trim
