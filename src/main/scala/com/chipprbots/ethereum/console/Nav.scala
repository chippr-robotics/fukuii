package com.chipprbots.ethereum.console

/** A screen opened over a view by Enter, and left by Esc (spec 029 FR-005). The view under it keeps its selection, so
  * Esc returns to the row that was opened.
  */
enum Screen:
  /** One peer's details, opened from the Peers list. */
  case PeerDetail(peerId: String)

  /** What a lookup found (spec 029 FR-028): a block, a transaction, a receipt, an account. `selected` is the row of the
    * screen that Enter opens.
    */
  case Explore(query: LookupQuery, selected: Int)

  /** Every check behind one health bar (spec 029 FR-045), opened from the System view's bars. */
  case HealthDetail(subsystem: Subsystem)

  /** The effective configuration, secrets hidden, the keys that have `filter` in them (spec 029 FR-054, FR-058).
    * `scroll` rows of it are above the screen.
    */
  case Config(filter: String, scroll: Int)

  /** The settings that change without a restart, with their values now. */
  case Tunables

  /** Each subsystem, what it depends on, and its health. */
  case Deps

  /** What each enabled RPC endpoint answered when this dashboard called it. */
  case TestRpc

  /** The listening and the detected external addresses, and the inbound peers. */
  case Net

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
  /** The confirmation of an action (spec 029 FR-048): what it will change, and the answer its class asks for.
    *
    * @param preview
    *   what will change, read from the node when the dialog opened and since; None while it is being read
    * @param focus
    *   the button Enter accepts, for an Adjust: Cancel when the dialog opens
    * @param typed
    *   what has been typed of the word a Halt asks for
    * @param changed
    *   the preview is not the one the dialog first showed: what it said stopped being true, and it asks again (FR-050)
    */
  case Confirm(
      request: ops.ActionRequest,
      risk: ops.RiskClass,
      preview: Option[ops.Preview],
      focus: DialogFocus = DialogFocus.Cancel,
      typed: String = "",
      changed: Boolean = false
  )

/** The buttons of an Adjust dialog. */
enum DialogFocus:
  case Cancel, Apply

/** What a key asks of the world outside the state (spec 029 data-model): [[Update]] returns these, [[Tui]] runs them.
  */
enum Effect:
  /** Stop the node. */
  case Quit

  /** Close the TUI; the node keeps running. */
  case Detach

  /** Clear the screen and draw the whole frame again. */
  case Redraw

  /** Have the lookup worker read `query` (spec 029 FR-029): never on the frame loop. */
  case Lookup(query: LookupQuery)

  /** Esc on a lookup that is still running: its result is not wanted. */
  case CancelLookup(query: LookupQuery)

  /** `r` on an explorer screen: read it again, whatever is known of it (an account is the head's of when it was read).
    */
  case RefreshLookup(query: LookupQuery)

  /** `v`: the next graphics mode this terminal can draw. */
  case CycleGraphics

  /** `:graphics <mode>`: that mode, if this terminal can draw it. */
  case SetGraphics(choice: GraphicsChoice)

  /** `r` on the Art screen: read the sprite sheets again (`fukuii.tui.art-dir`). */
  case ReloadArt

  /** A dialog opened for `request`: have the action runner read what it would change (spec 029 FR-050). */
  case Preview(request: ops.ActionRequest)

  /** The operator confirmed `request`, or it needs no confirmation: the action runner runs it, or refuses and says. */
  case Confirm(request: ops.ActionRequest)

  /** The operator cancelled `request`: said in the audit log. */
  case CancelAction(request: ops.ActionRequest)
