package com.chipprbots.ethereum.db.storage

import com.chipprbots.ethereum.db.storage.pathstate.StateLayout

/** Whether a data directory may be opened by this build as it is configured (spec 018 data-model 5.3, FR-016): one
  * marker, one rule, both directions of `fukuii.state.path-live`, both chains.
  *
  * The marker, `state.layout`, is written with the genesis of a data directory the unified state layout creates, and by
  * nothing else ([[pathstate.PathStateStorage.commitGenesis]]). So a directory with a marker was written by the unified
  * layout, and one that holds anything and has no marker by the hash layout. Neither can be read as the other: there is
  * no adoption, no migration and no reader of the other's rows.
  *
  * '''The guard writes no row.''' It does not stamp an empty directory: the stamp belongs to the genesis commit's own
  * batch, which refuses a directory that already has one, so a guard that stamped first would leave a node killed
  * between the two unable to start (vault). Opening the directory to ask it is not free of effect: RocksDB creates the
  * column families this build has and the directory lacks, which is a record in its manifest and no row.
  *
  * It is asked wherever the data directory is opened ([[checkOrClose]]): where a node's storages are built, before the
  * state is reconciled, before a repair of the best block or the index, before the genesis is loaded, and in test mode
  * too; and by a tool that opens the directory itself.
  *
  * '''Known, and left''': the hash layout's genesis is not written in one batch. A node killed between its state rows
  * and its block leaves rows of the hash store in a directory with no best block and no block at height 0, which reads
  * as empty. The unified layout would then write its genesis beside them; they are in a column family it never reads.
  */
object StateLayoutGuard:

  /** A data directory that is refused: the node does not start on it. */
  final class Refused(val why: String) extends IllegalStateException(why)

  /** Why the directory is refused; none when it may be opened.
    *
    * @param pathLive
    *   `fukuii.state.path-live` as this instance has it
    * @param marker
    *   the directory's `state.layout`, if it has one
    * @param empty
    *   nothing was ever written to it: no best block and no block at height 0
    */
  def refusal(pathLive: Boolean, marker: Option[String], empty: Boolean): Option[String] =
    (pathLive, marker) match
      case (false, Some(found)) =>
        Some(
          s"This data directory holds its state in the unified layout (state.layout = '$found'), and " +
            "fukuii.state.path-live is off: this build would read a state store that holds nothing of it. Turn " +
            "fukuii.state.path-live on, or use another data directory."
        )
      case (true, Some(StateLayout.PathLive1)) => None
      case (true, Some(other)) =>
        Some(s"This data directory was written by a state layout this build does not know (state.layout = '$other').")
      case (true, None) if !empty =>
        Some(
          "This data directory was written by an earlier storage layout and cannot be opened with " +
            "fukuii.state.path-live on. Stop the node, move or delete the rocksdb directory under the data " +
            "directory, and start again."
        )
      // with the guard on, an empty directory: the genesis commit that follows stamps it. With the guard off, a
      // directory with no marker: the hash layout's, as it always was
      case _ => None

  /** [[refusal]] for a data directory as it is on disk. Reads only. */
  def refusalOf(
      pathLive: Boolean,
      appState: AppStateStorage,
      blockNumbers: BlockNumberMappingStorage
  ): Option[String] =
    val best = appState.getBestBlockInfo()
    val empty = best.hash.isEmpty && best.number == 0 && blockNumbers.get(BigInt(0)).isEmpty
    refusal(pathLive, appState.getStateLayout(), empty)

  /** [[check]] for whoever has just opened the directory: one that is refused is closed, not held, and the refusal is
    * what is thrown whatever the close does.
    */
  @throws[Refused]("when the directory may not be opened by this build as it is configured")
  def checkOrClose(pathLive: Boolean, appState: AppStateStorage, blockNumbers: BlockNumberMappingStorage)(
      close: => Unit
  ): Unit =
    try check(pathLive, appState, blockNumbers)
    catch
      case refused: Refused =>
        try close
        catch case scala.util.control.NonFatal(failed) => refused.addSuppressed(failed)
        throw refused

  /** Refuses the directory by throwing, or returns having written no row. */
  @throws[Refused]("when the directory may not be opened by this build as it is configured")
  def check(pathLive: Boolean, appState: AppStateStorage, blockNumbers: BlockNumberMappingStorage): Unit =
    refusalOf(pathLive, appState, blockNumbers).foreach(why => throw new Refused(why))
