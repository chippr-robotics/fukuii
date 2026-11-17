package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateLayout
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.utils.Logger

/** The reconciliation at start of a datadir that holds its state on the unified layout (spec 018 data-model 5.1, I1 and
  * I6; plan.md "C4b part 4", T150). It runs once, before the genesis loader and before any actor, server or sync
  * starts.
  *
  * '''The block whose state the disk holds (the anchor) wins, always.''' A block's commit and every batch of a move of
  * the head write the state's rows, the anchor, the best block and the index of blocks by number in one batch, under
  * the state lock, after the best block was found to be the anchor (part 3a). So no crash leaves the two apart, and a
  * best block that is not the anchor at start was left by a defect, or by a datadir written before part 3a. The state
  * is what execution proved; a best block above it could not be built on (a block is committed in place only on the
  * anchor), and the blocks above the anchor stay stored by hash and are executed again when they are imported again
  * (forge, vault); go-ethereum's start does the same, down to the highest block whose state it has (beacon). What
  * imports them on the unified layout today is a chain import and test mode; the Engine API and sync follow with C4d
  * and C5. So the best block and the index are made the anchor's, the I1 rule 4 of data-model 5.1, and rules 2 and 3
  * (the state moved to the best block) are not built.
  *
  * '''Then the index is held to the anchor's ancestry (I6)''', whatever the best block was:
  * [[BlockchainWriter.heldBlockIndex]] builds the one batch.
  *
  * It writes the index of blocks by number, its transaction lookups and the best block, in one synced batch under the
  * state lock, and nothing else: never a state row, the anchor, a record, a header, a body, a receipt or a weight. A
  * datadir it finds consistent takes no write. What it cannot mend it refuses, before any write, and the node does not
  * start: the anchor's header or weight missing, or a header that says another block than the anchor's row.
  */
object StateReconciliation extends Logger:

  /** How many blocks below the anchor the index is held to its ancestry: one header read each. */
  val DefaultWindow: Int = 1024

  /** How far above the anchor index entries are looked for, at most: one read each. */
  val DefaultScanAbove: Int = 1_000_000

  enum Outcome:
    /** The datadir is not stamped for the unified layout: the genesis loader decides what it is. */
    case NotOnTheUnifiedLayout

    /** There is no anchor yet, and nothing says there should be one. */
    case NoAnchor

    /** The best block is the anchor and the index is its ancestry: nothing was written. */
    case Consistent(anchor: BlockRef)

    /** The best block and the index were made the anchor's. */
    case Reconciled(anchor: BlockRef, from: BlockInfo, rewritten: Int, removed: Int, droppedTxLookups: Int)

    /** Test mode, after a reset of the chain that stopped between its two writes: the chain is gone and the state is
      * not. The next `test_setChainParams` destroys the state; nothing is written here.
      */
    case LeftForTheReset(anchor: BlockRef)

  /** @param testMode
    *   the node runs test mode, whose reset of the chain for a new genesis is two writes (TestService)
    * @throws IllegalStateException
    *   when the datadir cannot be reconciled: nothing was written
    */
  def run(
      appState: AppStateStorage,
      pathState: PathStateStorage,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      testMode: Boolean,
      window: Int = DefaultWindow,
      scanAbove: Int = DefaultScanAbove
  ): Outcome =
    if !appState.getStateLayout().contains(StateLayout.PathLive1) then Outcome.NotOnTheUnifiedLayout
    else
      appState.getStateAnchor() match
        case None =>
          if appState.isSnapSyncDone() then
            refuse(
              "the datadir says SNAP sync is done and holds no anchor: on the unified state layout the state of a " +
                "synced datadir is always one block's"
            )
          else Outcome.NoAnchor
        case Some(anchor) =>
          reader.getBlockHeaderByHash(BlockHash(anchor.hash)).filter(_.hash.value == anchor.hash) match
            case None if testMode =>
              log.warn(
                s"[STATE-ANCHOR] The block whose state is held (${anchor.show}) is not stored: a reset of the chain in " +
                  "test mode stopped between its two writes. Left for the next test_setChainParams, which destroys it"
              )
              Outcome.LeftForTheReset(anchor)
            case None =>
              refuse(s"the header of the block whose state is held (${anchor.show}) is not stored")
            case Some(header) if header.number.value != anchor.number || header.stateRoot.value != anchor.stateRoot =>
              refuse(
                s"the block whose state is held is named ${anchor.show}, and its header says block " +
                  s"${header.number.value} with state root ${hex(header.stateRoot.value)}"
              )
            case Some(header) =>
              if reader.getChainWeightByHash(header.hash).isEmpty then
                refuse(s"the chain weight of the block whose state is held (${anchor.show}) is not stored")
              val repair = writer.heldBlockIndex(header, reader, window, scanAbove)
              if !repair.writes then Outcome.Consistent(anchor)
              else
                pathState.lock.locked("the reconciliation at start") {
                  // the anchor is read again under the lock: nothing else writes at start, and this says so
                  if !appState.getStateAnchor().contains(anchor) then
                    refuse(s"the anchor moved during the reconciliation at start (it was ${anchor.show})")
                  repair.batch.commitSync()
                }
                if repair.movesBest then
                  log.warn(
                    s"[STATE-ANCHOR] best pointer rewound: the best block was ${repair.previousBest.number} " +
                      s"(${hex(repair.previousBest.hash)}) and the state is held at ${anchor.show}. The best block is " +
                      s"now that block; the blocks above it stay stored by hash and are executed again when they are " +
                      s"imported again. Index entries rewritten: ${repair.put.size}, removed: ${repair.removed.size}"
                  )
                else
                  log.warn(
                    s"[STATE-ANCHOR] The index of blocks by number was not the ancestry of the block whose state is " +
                      s"held (${anchor.show}): ${repair.put.size} entries rewritten, ${repair.removed.size} removed"
                  )
                if repair.capped then
                  log.warn(
                    s"[STATE-ANCHOR] Index entries above block ${anchor.number + scanAbove} were not looked for: the " +
                      s"best block was ${repair.previousBest.number}"
                  )
                Outcome.Reconciled(
                  anchor,
                  repair.previousBest,
                  repair.put.size,
                  repair.removed.size,
                  repair.droppedTxLookups
                )

  private def hex(bytes: ByteString): String = com.chipprbots.ethereum.utils.Hex.toHexString(bytes.toArray).take(16)

  private def refuse(why: String): Nothing =
    log.error(s"[STATE-ANCHOR] The datadir cannot be reconciled at start: $why. Nothing was written")
    throw new IllegalStateException(s"The datadir cannot be reconciled at start: $why")
