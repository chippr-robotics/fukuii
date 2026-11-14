package com.chipprbots.ethereum.console

import org.jline.keymap.KeyMap
import org.jline.terminal.Terminal
import org.jline.utils.InfoCmp.Capability

/** Turns the characters a terminal sends into [[Key]]s (spec 029 FR-004, research §3 E3 and E4).
  *
  * A terminal sends an arrow or a function key as an escape sequence, and answers a question the program asked with one
  * too. The decoder recognises a whole sequence by its grammar before deciding what it is: a CSI sequence (`ESC [`,
  * parameters, a final character), an SS3 one (`ESC O` and a character), or a string (`ESC _`, `ESC P`, `ESC ]`, `ESC
  * ^`, `ESC X`, up to its terminator). A sequence it knows becomes a key; any other is dropped whole. So no sequence is
  * ever read as the characters it is made of: Left arrow never detaches the TUI by its `D`.
  *
  * It is a pure state machine: [[step]] takes one character, [[idle]] says the input went quiet. Nothing here reads a
  * terminal or a clock.
  *
  * @param keys
  *   what each known CSI or SS3 sequence means, by its text after `ESC`: `"[A"`, `"OP"`, `"[5~"`
  * @param cellReports
  *   the terminal's report of its cell size (`ESC [ 6 ; height ; width t`) becomes a [[Key.CellSize]] instead of
  *   nothing: for the program that asks for it after a resize
  */
final class KeyDecoder(keys: Map[String, Key] = KeyDecoder.Standard, cellReports: Boolean = false):

  import KeyDecoder.*
  import KeyDecoder.State.*

  /** The state after `c`, and the keys it completed: none, or one. */
  def step(state: State, c: Char): (State, Seq[Key]) = state match
    case Ground                   => ground(c)
    case Escape                   => escape(c)
    case Csi(buf)                 => csi(buf, c)
    case Ss3                      => (Ground, keys.get(s"O$c").toSeq)
    case Str(sawEsc, n)           => string(sawEsc, n, c)
    case Pasting(buf, n, dropped) => pasting(buf, n, dropped, c)

  /** The input went quiet for [[EscapeWaitMillis]]: an Esc with nothing after it was the Esc key. An unfinished
    * sequence or paste is kept: its rest may still be on the way, on a slow link or from a terminal slow to answer, and
    * read from the ground it would be typed as keys ([[abandon]]).
    */
  def idle(state: State): (State, Seq[Key]) = state match
    case Escape => (Ground, Seq(Key.Esc))
    case other  => (other, Nil)

  /** The input has been quiet for [[waitMillis]]: a sequence or a paste whose end never came is dropped, so that a
    * terminal that sends no end cannot swallow the keyboard.
    */
  def abandon(state: State): State = state match
    // An Esc waiting for what follows is a key, not a sequence: [[idle]] makes it one, and it is never dropped.
    case Escape => Escape
    case _      => Ground

  private def ground(c: Char): (State, Seq[Key]) = c match
    case EscChar => (Escape, Nil)
    // The same introducers as one character (C1): a terminal in 8-bit mode sends them so.
    case '\u009b'                                             => (Csi(""), Nil)
    case '\u0090' | '\u0098' | '\u009d' | '\u009e' | '\u009f' => (Str(sawEsc = false, 0), Nil)
    case '\r' | '\n'                                          => (Ground, Seq(Key.Enter))
    case '\t'                                                 => (Ground, Seq(Key.Tab))
    case '\u007f' | '\b'                                      => (Ground, Seq(Key.Backspace))
    case _ if isControl(c)                                    => (Ground, Nil)
    case _                                                    => (Ground, Seq(Key.Char(c)))

  private def escape(c: Char): (State, Seq[Key]) = c match
    case '['                         => (Csi(""), Nil)
    case 'O'                         => (Ss3, Nil)
    case '_' | 'P' | ']' | '^' | 'X' => (Str(sawEsc = false, 0), Nil)
    // Esc, then another Esc: the first was the key; the second may start a sequence.
    case EscChar => (Escape, Seq(Key.Esc))
    // Esc and one more character is Alt and a key: neither is dispatched.
    case _ => (Ground, Nil)

  private def csi(buf: String, c: Char): (State, Seq[Key]) =
    if c >= '@' && c <= '~' then
      val sequence = buf + c
      if sequence == PasteStart then (Pasting("", 0, dropped = false), Nil)
      else if cellReports && c == 't' then
        (Ground, TerminalQuery.parse(s"$EscChar[$sequence")._2.map(cell => Key.CellSize(cell.width, cell.height)).toSeq)
      // A sequence with a private marker (`?`, `<`, `=`, `>`) is a report, never a key.
      else if sequence.exists(PrivateMarkers.contains) then (Ground, Nil)
      else (Ground, keys.get("[" + withoutModifier(sequence)).toSeq)
    else if c >= ' ' && c <= '?' then if buf.length >= MaxCsi then (Ground, Nil) else (Csi(buf + c), Nil)
    // Anything else cannot be part of a sequence: the sequence is abandoned, and so is the character.
    else (Ground, Nil)

  /** `1;5A` (Ctrl and Up) is `A`; `5;2~` is `5~`: a modifier changes no meaning here. */
  private def withoutModifier(sequence: String): String =
    val semi = sequence.indexOf(';')
    if semi < 0 then sequence
    else
      val head = sequence.substring(0, semi)
      val last = sequence.last
      if last == '~' then s"$head~" else last.toString

  /** Inside a string sequence: it ends at `ESC \` or at BEL, or is dropped at its bound. */
  private def string(sawEsc: Boolean, n: Int, c: Char): (State, Seq[Key]) =
    if sawEsc && c == '\\' then (Ground, Nil)
    else if c == Bel then (Ground, Nil)
    else if n >= MaxString then (Ground, Nil)
    else (Str(c == EscChar, n + 1), Nil)

  /** Inside a bracketed paste: `matched` characters of the end marker have been seen. A paste longer than [[MaxPaste]]
    * is read to its end and dropped, so that its tail is not typed as keys.
    */
  private def pasting(buf: String, matched: Int, dropped: Boolean, c: Char): (State, Seq[Key]) =
    if c == PasteEndFull.charAt(matched) then
      if matched + 1 < PasteEndFull.length then (Pasting(buf, matched + 1, dropped), Nil)
      else if dropped then (Ground, Nil)
      else (Ground, Seq(Key.Paste(buf.filterNot(isControl))))
    else if dropped then (Pasting("", if c == EscChar then 1 else 0, dropped = true), Nil)
    else
      // The marker's start was text after all.
      val text = buf + PasteEndFull.substring(0, matched)
      if text.length >= MaxPaste then (Pasting("", if c == EscChar then 1 else 0, dropped = true), Nil)
      else if c == EscChar then (Pasting(text, 1, dropped), Nil)
      else (Pasting(text + c, 0, dropped), Nil)

object KeyDecoder:

  /** Where the decoder is in the input. */
  enum State:
    /** Between keys. */
    case Ground

    /** `ESC` was read. */
    case Escape

    /** `ESC [` was read, then `buf`. */
    case Csi(buf: String)

    /** `ESC O` was read. */
    case Ss3

    /** Inside an APC, DCS, OSC, PM or SOS string of `length` characters; `sawEsc` when the last one was `ESC`. */
    case Str(sawEsc: Boolean, length: Int)

    /** Inside a bracketed paste: `text` so far, how much of the end marker has matched, and whether the paste passed
      * its bound and is being read only to find its end.
      */
    case Pasting(text: String, matched: Int, dropped: Boolean)

  private[console] val EscChar: Char = '\u001b'
  private val Bel: Char = '\u0007'

  /** How long an `ESC` waits for the rest of a sequence before it is the Esc key: a terminal sends a sequence in one
    * write (research E3).
    */
  val EscapeWaitMillis: Long = 50L

  /** How long a paste may be quiet before its end is given up on. */
  val PasteWaitMillis: Long = 2000L

  /** The longest CSI parameter text kept; a longer sequence is dropped. */
  val MaxCsi: Int = 64

  /** The longest string sequence read through; a longer one is dropped at this length. */
  val MaxString: Int = 4096

  /** The longest paste kept. */
  val MaxPaste: Int = 64 * 1024

  /** How long an unfinished sequence is waited for before it is given up: longer than any link delays the rest of a key
    * or a reply, short enough that a stray `ESC [` costs the keyboard a moment.
    */
  val SequenceWaitMillis: Long = 1000L

  /** How long the input may stay quiet inside `state` before it is abandoned. */
  def waitMillis(state: State): Long = state match
    case _: State.Pasting => PasteWaitMillis
    case _                => SequenceWaitMillis

  private val PrivateMarkers = "?<=>"

  private val PasteStart = "200~"
  private val PasteEndFull = "\u001b[201~"

  /** Switch bracketed paste on and off: pasted text then arrives between markers, as one [[Key.Paste]]. */
  private[console] val EnablePaste = "\u001b[?2004h"
  private[console] val DisablePaste = "\u001b[?2004l"

  private def isControl(c: Char): Boolean = c < ' ' || (c >= '\u007f' && c <= '\u009f')

  /** The sequences every xterm-like terminal sends, in both the normal (`ESC [`) and the application (`ESC O`) form. */
  val Standard: Map[String, Key] =
    val arrows = Map('A' -> Key.Up, 'B' -> Key.Down, 'C' -> Key.Right, 'D' -> Key.Left, 'H' -> Key.Home, 'F' -> Key.End)
    val both = arrows.flatMap((c, k) => Seq(s"[$c" -> k, s"O$c" -> k))
    val tilde = Map(
      "[1~" -> Key.Home,
      "[7~" -> Key.Home,
      "[4~" -> Key.End,
      "[8~" -> Key.End,
      "[5~" -> Key.PageUp,
      "[6~" -> Key.PageDown,
      "[Z" -> Key.BackTab
    )
    val functionSs3 = Seq('P', 'Q', 'R', 'S').zipWithIndex.map((c, i) => s"O$c" -> Key.F(i + 1))
    val functionCsi =
      Seq(11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 24).zipWithIndex.map((n, i) => s"[$n~" -> Key.F(i + 1))
    both ++ tilde ++ functionSs3 ++ functionCsi

  /** The capabilities whose sequences a terminal's own description may spell differently. */
  private val Capabilities: Seq[(Capability, Key)] = Seq(
    Capability.key_up -> Key.Up,
    Capability.key_down -> Key.Down,
    Capability.key_left -> Key.Left,
    Capability.key_right -> Key.Right,
    Capability.key_home -> Key.Home,
    Capability.key_end -> Key.End,
    Capability.key_ppage -> Key.PageUp,
    Capability.key_npage -> Key.PageDown,
    Capability.key_btab -> Key.BackTab,
    Capability.key_f1 -> Key.F(1)
  )

  /** A decoder for `terminal`: the standard sequences, and the ones its terminfo entry names where they are CSI or SS3
    * sequences. A capability the terminal does not have adds nothing.
    */
  def forTerminal(terminal: Terminal, cellReports: Boolean = false): KeyDecoder =
    val own = Capabilities.flatMap { (capability, key) =>
      Option(KeyMap.key(terminal, capability))
        .filter(s => s.length >= 3 && s.charAt(0) == EscChar && (s.charAt(1) == '[' || s.charAt(1) == 'O'))
        .map(s => s.substring(1) -> key)
    }
    new KeyDecoder(Standard ++ own, cellReports)

  /** Every key in `input`, with the input going quiet at its end: for tests and for text already in hand. */
  def decode(input: String, decoder: KeyDecoder = new KeyDecoder()): Seq[Key] =
    val (state, keys) = input.foldLeft((State.Ground: State, Vector.empty[Key])) { case ((s, out), c) =>
      val (next, ks) = decoder.step(s, c)
      (next, out ++ ks)
    }
    keys ++ decoder.idle(state)._2
