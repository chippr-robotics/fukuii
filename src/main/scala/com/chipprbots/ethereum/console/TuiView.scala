package com.chipprbots.ethereum.console

/** The TUI's views (spec 027 FR-015, research §2.1). Help is not one of them: it opens over the current view, and any
  * key but `q`, `r` and `d` returns to it ([[TuiState.helpOpen]]). The Logs view also opens on `l`.
  *
  * @param key
  *   the number key that opens the view
  * @param title
  *   the name the header and the M- and L-tier footer show
  * @param short
  *   the name the S-tier footer shows
  */
enum TuiView(val key: Char, val title: String, val short: String):
  case Overview extends TuiView('1', "Overview", "Over")
  case Sync extends TuiView('2', "Sync", "Sync")
  case Peers extends TuiView('3', "Peers", "Peers")
  case Chain extends TuiView('4', "Chain", "Chain")
  case Logs extends TuiView('5', "Logs", "Logs")

  /** The view `Tab` opens: the next one, wrapping after the last. */
  def next: TuiView = TuiView.fromOrdinal((ordinal + 1) % TuiView.values.length)

object TuiView:
  /** The view a number key opens. */
  def forKey(key: Char): Option[TuiView] = values.find(_.key == key)
