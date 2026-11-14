package com.chipprbots.ethereum.console

/** What the mascot shows (spec 029 FR-018, research §5.5): one pose a state of the node, and nothing else. The mascot
  * is a status lamp: it never moves for its own sake, and what it shows the text of the frame says too.
  *
  * The order of the cases is the order of the sprite sheets' rows (contracts/art.md).
  */
enum Pose(val label: String):
  case Starting extends Pose("starting")
  case Syncing extends Pose("syncing")
  case Watching extends Pose("watching")
  case Warning extends Pose("warning")
  case Error extends Pose("error")
  case Asleep extends Pose("asleep")
  case Celebrating extends Pose("celebrating")

object Pose:

  /** The pose for `state`, the most urgent first: an error alert; a warning; a sync that has stalled; the one
    * celebration; a node still starting; a sync in progress; and else, at the head, watching. A stall is itself a
    * warning alert, and is shown as sleep when it is the only one.
    */
  def of(state: TuiState): Pose =
    val alerts = Alerts.of(state)
    val warnings = alerts.count(_.severity == Severity.Warn) - (if state.stalled then 1 else 0)
    if alerts.exists(_.severity == Severity.Error) then Error
    else if warnings > 0 then Warning
    else if state.stalled then Asleep
    else if state.catchup.exists(_.isInstanceOf[Catchup.Celebrating]) then Celebrating
    else if state.snapshot.isEmpty then Starting
    else if state.snapSyncState.isDefined || (state.bestBlock > 0 && !state.isSynchronized) then Syncing
    else Watching

  /** The pose as a face, for a mascot drawn in text (spec 029 FR-012: the region is there in every mode). Plain ASCII,
    * so it reads in either glyph set.
    */
  def face(pose: Pose): String = pose match
    case Starting    => "(._.)"
    case Syncing     => "(o_o)..."
    case Watching    => "(o_o)"
    case Warning     => "(o_O)!"
    case Error       => "(x_x)!!"
    case Asleep      => "(-_-)zZ"
    case Celebrating => "\\(^o^)/"

  /** What the pose means, in words: drawn beside the mascot in every graphics mode, so the mascot is never the only way
    * to know (spec 029 FR-001).
    */
  def caption(pose: Pose): String = pose match
    case Starting    => "starting"
    case Syncing     => "syncing"
    case Watching    => "at head"
    case Warning     => "warning"
    case Error       => "error"
    case Asleep      => "stalled"
    case Celebrating => "synced"
