package com.chipprbots.ethereum.console

/** A screen opened over a view by Enter, and left by Esc (spec 029 FR-005). The view under it keeps its selection, so
  * Esc returns to the row that was opened.
  */
enum Screen:
  /** One peer's details, opened from the Peers list. */
  case PeerDetail(peerId: String)

  /** The art, a pose of a sprite a page, in the graphics mode on screen (`:art`; contracts/art.md). `page` counts from
    * the first and wraps.
    */
  case Art(page: Int)

/** What takes the keys instead of the view (spec 029 FR-007 to FR-009). At most one is open. */
enum Overlay:
  case None

  /** The `:` prompt: `text` with the cursor before character `cursor`; `historyAt` while walking the history. */
  case Prompt(text: String, cursor: Int, historyAt: Option[Int])

  /** The `/` filter of the list on screen, as it is typed. */
  case Filter(text: String)

  /** A question that must be answered before anything else happens. */
  case Dialog(kind: DialogKind)

/** The dialogs there are. Slice 7 of spec 029 adds one per operator action. */
enum DialogKind:
  /** `q`: stop the node? */
  case Quit

/** What a key asks of the world outside the state (spec 029 data-model): [[Update]] returns these, [[Tui]] runs them.
  */
enum Effect:
  /** Stop the node. */
  case Quit

  /** Close the TUI; the node keeps running. */
  case Detach

  /** Clear the screen and draw the whole frame again. */
  case Redraw

  /** `v`: the next graphics mode this terminal can draw. */
  case CycleGraphics

  /** `:graphics <mode>`: that mode, if this terminal can draw it. */
  case SetGraphics(choice: GraphicsChoice)

  /** `r` on the Art screen: read the sprite sheets again (`fukuii.tui.art-dir`). */
  case ReloadArt
