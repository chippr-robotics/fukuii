package com.chipprbots.ethereum.console

/** The TUI's views (spec 029 FR-006). Help is not one of them: it opens over the current view ([[TuiState.helpOpen]]).
  * The Logs view also opens on `l`. Sync has no view of its own: its panels lead the Chain view while the node is
  * syncing.
  *
  * @param key
  *   the number key that opens the view
  * @param title
  *   the name the header, the sidebar and the prompt use
  * @param short
  *   the name a narrow footer shows
  */
enum TuiView(val key: Char, val title: String, val short: String):
  case Overview extends TuiView('1', "Overview", "Over")
  case Chain extends TuiView('2', "Chain", "Chain")
  case Peers extends TuiView('3', "Peers", "Peers")
  case Mempool extends TuiView('4', "Mempool", "Pool")
  case Rpc extends TuiView('5', "RPC", "RPC")
  case Storage extends TuiView('6', "Storage", "Store")
  case System extends TuiView('7', "System", "Sys")
  case Logs extends TuiView('8', "Logs", "Logs")
  case Ops extends TuiView('9', "Ops", "Ops")

  /** The next view, wrapping after the last (`]`). */
  def next: TuiView = TuiView.fromOrdinal((ordinal + 1) % TuiView.values.length)

  /** The previous view, wrapping before the first (`[`). */
  def previous: TuiView = TuiView.fromOrdinal((ordinal + TuiView.values.length - 1) % TuiView.values.length)

object TuiView:
  /** The view a number key opens. */
  def forKey(key: Char): Option[TuiView] = values.find(_.key == key)

  /** The view a prompt command names: its title, in any case. */
  def named(name: String): Option[TuiView] = values.find(_.title.equalsIgnoreCase(name))
