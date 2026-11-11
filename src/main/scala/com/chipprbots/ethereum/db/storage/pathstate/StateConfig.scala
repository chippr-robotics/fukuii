package com.chipprbots.ethereum.db.storage.pathstate

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

import com.typesafe.config.Config as TsConfig
import com.typesafe.config.ConfigValueType

/** The configuration of the unified state layout (spec 018 data-model 10): the `state` section of the node's
  * configuration (`base/state.conf`), with the two `pruning` keys it is read with.
  *
  * Nothing reads it until the staged commit (C3b). The guard alone is read today, by
  * [[com.chipprbots.ethereum.utils.StateLayoutConfig]].
  */
final case class StateConfig(
    pathLive: Boolean,
    pruning: StateConfig.Pruning,
    changeset: StateConfig.Changeset,
    retention: StateConfig.Retention,
    overlay: StateConfig.Overlay,
    maxBlocksPerMoveBatch: Int,
    archiveViewReach: Int,
    recovery: StateConfig.Recovery,
    reorgMaxDepth: StateConfig.ReorgDepth
):

  /** How many blocks below the anchor keep their changeset record (data-model 4.5), never fewer than the history and
    * never more than the bound.
    *
    * With a finalized block it is the distance to it, with a margin: the consensus client may reorganise down to its
    * finalized block and no further. With none it is `retention.without-finality`: a proof-of-work chain has no such
    * block, and nor has a node whose consensus client has not reported one yet. The history alone (64) is far less than
    * ETC has been reorganised by, and a reorganisation deeper than what is kept is refused and the state synced again
    * (user decision, 2026-11-11).
    */
  def retained(anchorNumber: BigInt, finalized: Option[BigInt]): Int =
    val wanted = finalized.fold(BigInt(retention.withoutFinality))(f => anchorNumber - f + retention.finalityMargin)
    wanted.max(pruning.history).min(retention.maxBlocks).toInt.max(pruning.history)

  /** How far below the head a fork point may lie for a move to be attempted: the retention floor, or a configured depth
    * below it. A configured depth above the floor cannot be honoured, since no record reaches that far.
    */
  def maxReorgDepth(anchorNumber: BigInt, finalized: Option[BigInt]): Int =
    val floor = retained(anchorNumber, finalized)
    reorgMaxDepth match
      case StateConfig.ReorgDepth.Floor        => floor
      case StateConfig.ReorgDepth.Fixed(depth) => depth.min(floor)

object StateConfig:

  /** The two pruning modes of the unified layout: one write path, with different changeset retention. */
  enum Mode:
    /** Changeset records are kept down to the retention floor. */
    case Basic

    /** Every changeset record is kept. */
    case Archive

  final case class Pruning(mode: Mode, history: Int)
  final case class Changeset(maxEntriesPerOwnerDelete: Long, maxRecordBytes: Long)
  final case class Retention(finalityMargin: Int, maxBlocks: Int, withoutFinality: Int)
  final case class Overlay(
      maxBytes: Long,
      pinnedMaxBytes: Long,
      maxForwardLayers: Int,
      residentReverseBlocks: Int,
      maxLayerBytes: Long,
      maxViewAgeMoves: Int
  )
  final case class Recovery(maxBlocks: Int, escalationRefusals: Int, escalationCooldown: FiniteDuration)

  enum ReorgDepth:
    /** The retention floor: what the changeset records cover. */
    case Floor
    case Fixed(depth: Int)

  /** The configuration under `fukuii`, the node's own section. A key that is missing or of the wrong kind throws, as
    * does `pruning.mode = "inmemory"`: that mode has no place on this layout.
    */
  def apply(fukuii: TsConfig): StateConfig =
    val state = fukuii.getConfig("state")
    require(
      state.getInt("retention.max-blocks") >= fukuii.getInt("pruning.history"),
      "state.retention.max-blocks is below pruning.history: the history is the least that is kept"
    )
    require(
      state.getInt("retention.without-finality") <= state.getInt("retention.max-blocks"),
      "state.retention.without-finality is above state.retention.max-blocks, which bounds everything that is kept"
    )
    StateConfig(
      pathLive = state.getBoolean("path-live"),
      pruning = Pruning(pruningMode(fukuii.getString("pruning.mode")), fukuii.getInt("pruning.history")),
      changeset = Changeset(
        maxEntriesPerOwnerDelete = state.getLong("changeset.max-entries-per-owner-delete"),
        maxRecordBytes = state.getLong("changeset.max-record-bytes")
      ),
      retention = Retention(
        finalityMargin = state.getInt("retention.finality-margin"),
        maxBlocks = state.getInt("retention.max-blocks"),
        withoutFinality = state.getInt("retention.without-finality")
      ),
      overlay = Overlay(
        maxBytes = state.getLong("overlay.max-bytes"),
        pinnedMaxBytes = state.getLong("overlay.pinned-max-bytes"),
        maxForwardLayers = state.getInt("overlay.max-forward-layers"),
        residentReverseBlocks = state.getInt("overlay.resident-reverse-blocks"),
        maxLayerBytes = state.getLong("overlay.max-layer-bytes"),
        maxViewAgeMoves = state.getInt("overlay.max-view-age-moves")
      ),
      maxBlocksPerMoveBatch = state.getInt("move.max-blocks-per-batch"),
      archiveViewReach = state.getInt("archive.view-reach"),
      recovery = Recovery(
        maxBlocks = state.getInt("recovery.max-blocks"),
        escalationRefusals = state.getInt("recovery.escalation-refusals"),
        escalationCooldown = state.getDuration("recovery.escalation-cooldown").toScala
      ),
      reorgMaxDepth = reorgDepth(state)
    )

  private def pruningMode(mode: String): Mode = mode match
    case "basic"   => Mode.Basic
    case "archive" => Mode.Archive
    case "inmemory" =>
      throw new IllegalArgumentException(
        "pruning.mode = \"inmemory\" is retired: the unified state layout has no cache that defers state. " +
          "Use \"basic\" or \"archive\"."
      )
    case other =>
      throw new IllegalArgumentException(
        s"pruning.mode = \"$other\" is not a pruning mode: use \"basic\" or \"archive\""
      )

  private def reorgDepth(state: TsConfig): ReorgDepth =
    val path = "reorg.max-depth"
    if state.getValue(path).valueType == ConfigValueType.NUMBER then
      val depth = state.getInt(path)
      require(depth > 0, s"state.$path must be positive, or \"floor\"")
      ReorgDepth.Fixed(depth)
    else if state.getString(path) == "floor" then ReorgDepth.Floor
    else
      throw new IllegalArgumentException(
        s"state.$path = \"${state.getString(path)}\": a number of blocks, or \"floor\""
      )
