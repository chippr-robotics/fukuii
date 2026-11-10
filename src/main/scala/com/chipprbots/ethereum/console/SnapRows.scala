package com.chipprbots.ethereum.console

import com.chipprbots.ethereum.console.TuiText.formatCount
import com.chipprbots.ethereum.console.TuiText.formatNumber

/** One worm row: its label, what its bar shows, and the text after the bar. */
final case class SnapRow(label: String, progress: WormProgress, detail: String)

/** The five SNAP worm rows (spec 027 FR-007), as the log's `SyncProgress.wormBlock` draws them. */
object SnapRows:

  /** Overall, Accounts, Storage, Bytecode and Healing. Overall is the log's own fraction ([[SnapSyncState.overall]]); a
    * part whose phase is over shows COMPLETE, whatever its estimate says, and a finished row shows no zero count.
    *
    * The running phase's row (Accounts during `AccountRangeSync`, Storage during `ByteCodeAndStorageSync`) draws the
    * monitor's own [[SnapSyncState.phaseProgress]], which never falls within a phase (B-044), not a division of the raw
    * counts by an estimate that moves. A count found ahead of its phase, and the bytecodes, whose total is not known
    * until they are all found, are ACTIVE with the count so far, without a percentage.
    */
  def of(snap: SnapSyncState, g: TuiGlyphs): Seq[SnapRow] =
    import WormProgress.*
    val phase = snap.phaseIndex
    val dot = g.dot.text
    def rate(perSec: Double): String = s"@ ${formatCount(perSec.toLong)}/s"
    def count(n: Long, unit: String = ""): String = if n > 0 then s"${formatCount(n)}$unit" else ""
    val inPhase = Known(snap.phaseProgress.toDouble / 100.0)

    // The ETA is on the phase row above the worms (spec 027 slice 3, SC-002), so it is not repeated here.
    val overall = SnapRow("Overall", Known(snap.overall), "")

    val accounts =
      val (n, est) = (snap.accountsSynced, snap.estimatedTotalAccounts)
      if phase > 0 then SnapRow("Accounts", Complete, count(n))
      else if phase == 0 && est > 0 then
        SnapRow("Accounts", inPhase, s"${formatCount(n)} / ~${formatCount(est)} ${rate(snap.recentAccountsPerSec)}")
      else if n > 0 then SnapRow("Accounts", Active, s"${formatCount(n)} ${rate(snap.recentAccountsPerSec)}")
      else SnapRow("Accounts", Queued, "")

    val storage =
      val (n, est) = (snap.storageSlotsSynced, snap.estimatedTotalSlots)
      val contracts =
        if snap.storageContractsTotal > 0 then
          s"${formatNumber(snap.storageContractsCompleted.toLong)}/${formatNumber(snap.storageContractsTotal.toLong)} contracts"
        else ""
      def joined(parts: String*): String = parts.filter(_.nonEmpty).mkString(s" $dot ")
      // The rate before the contract count: when the row is cut at 80 columns, the rate says whether it moves.
      val slots = s"${formatCount(n)} slots"
      val moving = joined(s"$slots ${rate(snap.recentSlotsPerSec)}", contracts)
      if phase > 1 then SnapRow("Storage", Complete, joined(count(n, " slots"), contracts))
      else if phase == 1 && est > 0 then SnapRow("Storage", inPhase, moving)
      else if n > 0 then SnapRow("Storage", Active, moving)
      else SnapRow("Storage", Queued, "")

    val bytecodes =
      val n = snap.bytecodesDownloaded
      if snap.bytecodeComplete || phase > 1 then SnapRow("Bytecode", Complete, count(n))
      else if n > 0 then SnapRow("Bytecode", Active, s"${formatCount(n)} ${rate(snap.recentBytecodesPerSec)}")
      else SnapRow("Bytecode", Queued, "")

    val healing =
      val n = snap.nodesHealed
      if phase > 2 then SnapRow("Healing", Complete, count(n, " nodes"))
      else if n > 0 then SnapRow("Healing", Active, s"${formatCount(n)} nodes ${rate(snap.recentNodesPerSec)}")
      else SnapRow("Healing", Queued, "")

    Seq(overall, accounts, storage, bytecodes, healing)
