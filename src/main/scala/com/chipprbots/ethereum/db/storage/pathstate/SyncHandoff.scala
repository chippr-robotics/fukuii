package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.AncestorWindow
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.PathHandoffSample
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.appstate.BlockInfo

/** The end of a sync from scratch on the unified state layout (spec 018 Track C, C5a part 5; data-model 7.4), for the
  * sync that ends: the sample of the rows it wrote, and the one write that makes them the node's state. Nothing is
  * copied.
  *
  * The sync says what it decided (the pivot, its weight, what its recoveries are owed); what is written, and that it is
  * one synced batch with the state's anchor, is this class's and [[PathStateStorage.commitHandoff]]'s. The stores are
  * over the one data source.
  */
final class SyncHandoff(
    state: PathStateStorage,
    rows: PathNodeStorage,
    appState: AppStateStorage,
    headers: BlockHeadersStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    weights: ChainWeightStorage,
    bodies: BlockBodiesStorage,
    codes: EvmCodeStorage
):

  /** The rows sampled from `stateRoot` ([[PathHandoffSample.run]]). Blocking: for a worker thread, with nothing writing
    * the rows.
    *
    * @param accountsLeftOut
    *   whether the sync left accounts out, whose storage is fetched when a block asks for it
    * @throws IllegalStateException
    *   when the sample stops the hand-off
    */
  def sample(stateRoot: ByteString, accountsLeftOut: Boolean): PathHandoffSample.Result =
    PathHandoffSample.run(rows, stateRoot, hash => codes.get(hash).isDefined, accountsLeftOut = accountsLeftOut)

  /** What the node does not hold of the window below `pivot` ([[AncestorWindow.check]]): the headers of the 256 blocks
    * below it, each found by the hash its child names, and on a proof-of-work chain the bodies of the pivot and the
    * five below it. `BLOCKHASH` and the check of ommers read them in the first blocks above the pivot, so a sync does
    * not hand off while any is missing.
    */
  def windowMissing(pivot: BlockHeader, proofOfWork: Boolean): AncestorWindow.Missing =
    AncestorWindow.check(pivot, proofOfWork, hash => headers.get(hash.value), hash => bodies.get(hash.value))

  /** '''One synced batch''': the pivot's header and its place in the index of blocks by number, its weight, the best
    * block, the flags of the recoveries (set or cleared, never left as an earlier sync wrote them), the scan that was
    * owed no longer owed, the heal's marker removed when asked, and with them the state's anchor, the block the state
    * began at and that the sync is done.
    *
    * The best block and the index move here with the state, in its batch: the one way they may on this layout.
    *
    * @param storageRecoveryDone
    *   whether no account's storage is known to be missing
    * @param bytecodeRecoveryDone
    *   whether no account's code is known to be missing
    * @param cleanDecision
    *   whether the heal decided cleanly on the pivot's root. Without that, the marker that says "present means
    *   complete" goes, in the same write (spec 022 T043)
    * @return
    *   whether the marker stood and was removed
    * @throws IllegalStateException
    *   as [[PathStateStorage.commitHandoff]]: the datadir holds a state already, or is not this layout's, or the rows
    *   do not begin at the pivot's state root
    */
  def commit(
      pivot: BlockHeader,
      weight: ChainWeight,
      storageRecoveryDone: Boolean,
      bytecodeRecoveryDone: Boolean,
      cleanDecision: Boolean
  ): Boolean =
    val hash = pivot.hash.value
    val number = pivot.number.value
    val records = headers
      .put(hash, pivot)
      .and(blockNumberMappingStorage.put(number, hash))
      .and(weights.put(hash, weight))
      .and(appState.putBestBlockInfo(BlockInfo(hash, number)))
      .and(if storageRecoveryDone then appState.storageRecoveryDone() else appState.clearStorageRecoveryDone())
      .and(if bytecodeRecoveryDone then appState.bytecodeRecoveryDone() else appState.clearBytecodeRecoveryDone())
      .and(appState.clearSnapCodeScanOwed())
    val unmarked = !cleanDecision && appState.isSnapHealInvariantPresent()
    val batch = if unmarked then records.and(appState.clearSnapHealInvariant()) else records
    state.commitHandoff(BlockRef(hash, number, pivot.stateRoot.value), Some(batch))
    unmarked
