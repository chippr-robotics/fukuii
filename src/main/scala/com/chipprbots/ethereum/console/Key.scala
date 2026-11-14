package com.chipprbots.ethereum.console

/** A key as the operator means it (spec 029 FR-003): what [[KeyDecoder]] makes of the terminal's bytes, and the only
  * thing [[Update]] ever sees. An escape sequence or a terminal's reply that is not one of these produces no key.
  */
enum Key:
  /** A printable character, as typed. */
  case Char(c: scala.Char)
  case Up, Down, Left, Right
  case PageUp, PageDown, Home, End
  case Enter, Esc, Backspace, Tab, BackTab

  /** A function key, F1 to F12. */
  case F(n: Int)

  /** Text pasted into the terminal (bracketed paste), control characters removed. Only a text field takes it (FR-009).
    */
  case Paste(text: String)
