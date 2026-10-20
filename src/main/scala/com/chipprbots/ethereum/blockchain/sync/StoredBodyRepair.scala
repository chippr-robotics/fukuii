package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter

/** Start-up repair for bodies an earlier version stored without their content (CQ-020-07). Two blocks only, both read
  * by number, so it costs two header reads, two body reads and one block's validation:
  *
  *   - the SNAP pivot: finalization wrote `BlockBody.empty` over it (Ephemery 137,594 lost 16 withdrawals). When the
  *     header commits to an empty body the right one is derived and written; otherwise the wrong body is removed and
  *     the background backfill is re-armed for that one block (target raised to the pivot, body cursor put just below
  *     it), so the standalone resumer `SyncController.maybeStartBackfillResume` fetches it from a peer and validates it
  *     through the same path as every other backfilled body. No new network code.
  *   - genesis: a Shanghai-at-genesis chain stored it with `withdrawals = None` (a 3-item body). Always derivable.
  *
  * Idempotent: a second run finds both bodies valid (or the pivot absent with the backfill already armed). Inert on a
  * node whose bodies validate — which is every ETC node whose pivot block had no transactions and no ommers.
  */
object StoredBodyRepair:

  /** `refetchArmed`: the backfill was re-armed to fetch this block's body from a peer on this start. */
  final case class Outcome(
      label: String,
      number: BigInt,
      hash: BlockHash,
      action: StoredBodyCheck.Action,
      refetchArmed: Boolean
  )

  def run(reader: BlockchainReader, writer: BlockchainWriter, appState: AppStateStorage): Seq[Outcome] =
    // Genesis is always derivable (its header commits to an empty body); never remove it or arm a fetch for it.
    val genesis = reader
      .getBlockHeaderByNumber(0)
      .flatMap(h => repairOne("genesis", h, reader, writer, appState, mayRefetch = false))
    val pivot =
      if !appState.isSnapSyncDone() then None
      else
        appState
          .getSnapSyncPivotBlock()
          .filter(_ > 0)
          .flatMap(reader.getBlockHeaderByNumber)
          .flatMap(h => repairOne("SNAP pivot", h, reader, writer, appState, mayRefetch = true))
    genesis.toSeq ++ pivot.toSeq

  private def repairOne(
      label: String,
      header: BlockHeader,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appState: AppStateStorage,
      mayRefetch: Boolean
  ): Option[Outcome] =
    val number = header.number.value
    def outcome(action: StoredBodyCheck.Action, armed: Boolean) = Some(
      Outcome(label, number, header.hash, action, armed)
    )
    StoredBodyCheck.decide(header, reader.getBlockBodyByHash(header.hash)) match
      case StoredBodyCheck.Action.Keep => None
      case action @ StoredBodyCheck.Action.Write(body, _) =>
        writer.storeCheckedBlockBody(header, body).commit()
        outcome(action, armed = false)
      case action @ StoredBodyCheck.Action.Remove(_) if mayRefetch =>
        // A missing body is served as missing; a wrong one is served as if it were right (RPC, GetBlockBodies).
        writer.removeBlockBody(header.hash).commit()
        outcome(action, armed = armBackfill(number, appState))
      case StoredBodyCheck.Action.LeaveAbsent
          if mayRefetch && appState.getBackfillTarget() < number && appState.getBackfillBestBody() >= number =>
        // Absent although the completed backfill's cursor claims it stored: queue it again. While a backfill is still
        // running towards it (target at or above it), absent is the normal state and nothing is done.
        outcome(StoredBodyCheck.Action.LeaveAbsent, armed = armBackfill(number, appState))
      case _ => None

  /** Make `needsBackfillResume()` true with the body at `number` still to fetch: raise the target to `number` and put
    * the body cursor just below it. Only when the header backfill already covers `number`: then the standalone resumer
    * walks one block and fetches one body. Below that (backfill disabled, or still running) nothing is armed: a running
    * backfill fetches the now-absent body itself, and arming a disabled one would start a whole-chain download. Only
    * ever lowers the cursor and raises the target, so a backfill in progress keeps its work. Returns whether it armed.
    */
  private def armBackfill(number: BigInt, appState: AppStateStorage): Boolean =
    val headerCovers = appState.getBackfillBestHeader() >= number
    if headerCovers then
      appState
        .putBackfillTarget(appState.getBackfillTarget().max(number))
        .and(appState.putBackfillBestBody(appState.getBackfillBestBody().min(number - 1)))
        .commit()
    headerCovers
