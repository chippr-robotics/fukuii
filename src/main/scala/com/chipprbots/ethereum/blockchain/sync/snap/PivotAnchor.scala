package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.appstate.BlockInfo

/** The SNAP pivot's anchor in the validated header chain, where the header download validates it
  * ([[HeaderChainCheck.Validated]]).
  *
  * The persisted pivot (`SnapSyncPivotBlock`, `SnapSyncStateRoot` and `SnapSyncPivotHash`) names the header whose state
  * root SNAP downloaded. At finalisation that header must be the one the validated header download holds at the pivot's
  * number, and its state root the persisted one. The download's header at that number is found from the download's
  * recorded tip (`BackfillBestHeaderHash`) by parent hash, never through the number→hash index: the pivot bootstrap and
  * the pivot's best-block record write that index too, with headers no one validated.
  */
private[sync] object PivotAnchor:

  enum Check:
    /** The validated chain's header at the pivot; it has the persisted state root and, when one is pinned, the pinned
      * hash.
      */
    case Anchored(header: BlockHeader)

    /** The validated chain's header at the pivot differs from the persisted pivot (hash or state root). */
    case Diverged(validated: BlockHeader, pinnedHash: Option[ByteString], persistedRoot: Option[ByteString])

    /** The validated chain's header at the pivot cannot be determined (the download is below it, or its tip is not
      * recorded).
      */
    case Undecided(reason: String)

  // The SNAP controller's entry points: the same, through its storage capability.
  def check(ingest: StateIngest, pivot: BigInt): Check = check(ingest.appState, ingest.chainReader, pivot)
  def pinFor(ingest: StateIngest, number: BigInt, stateRoot: ByteString): DataSourceBatchUpdate =
    pinFor(ingest.appState, ingest.chainReader, number, stateRoot)
  def reanchor(ingest: StateIngest, validated: BlockHeader): Unit =
    reanchor(ingest.appState, ingest.chainWriter, validated)
  def committed(ingest: StateIngest, number: BigInt, header: BlockHeader): DataSourceBatchUpdate =
    committed(ingest.appState, number, header)

  /** The persisted pivot for a pivot taken from `header` at `number`: number, state root and the header's hash, in one
    * batch. Every site that commits a pivot from a header writes it through here, so none leaves a stale hash.
    */
  def committed(appState: AppStateStorage, number: BigInt, header: BlockHeader): DataSourceBatchUpdate =
    appState
      .putSnapSyncPivotBlock(number)
      .and(appState.putSnapSyncStateRoot(header.stateRoot.value))
      .and(appState.putSnapSyncPivotHash(header.hash.value))

  def check(appState: AppStateStorage, reader: BlockchainReader, pivot: BigInt): Check =
    validatedHeaderAt(appState, reader, pivot) match
      case Left(reason) => Check.Undecided(reason)
      case Right(validated) =>
        val pinned = appState.getSnapSyncPivotHash()
        val root = appState.getSnapSyncStateRoot()
        if pinned.forall(_ == validated.hash.value) && root.contains(validated.stateRoot.value) then
          Check.Anchored(validated)
        else Check.Diverged(validated, pinned, root)

  /** The header the validated download holds at `number`: the recorded tip, walked back by parent hash. With nothing
    * downloaded (cursor 0) the chain is genesis alone.
    */
  def validatedHeaderAt(
      appState: AppStateStorage,
      reader: BlockchainReader,
      number: BigInt
  ): Either[String, BlockHeader] =
    val cursor = appState.getBackfillBestHeader()
    if cursor < number then Left(s"the header download is at block $cursor, below block $number")
    else if cursor == 0 then Right(reader.genesisHeader)
    else
      appState.getBackfillBestHeaderHash() match
        case None => Left(s"the header download's tip (block $cursor) has no recorded hash")
        case Some(tipHash) =>
          reader.getBlockHeaderByHash(BlockHash(tipHash)) match
            case Some(tip) if tip.number.value == cursor => walkBack(reader, tip, number)
            case Some(tip) =>
              Left(
                s"the recorded tip hash names block ${tip.number.value}, but the header download is at block $cursor"
              )
            case None => Left(s"the recorded tip header (block $cursor) is not stored")

  @tailrec
  private def walkBack(reader: BlockchainReader, header: BlockHeader, number: BigInt): Either[String, BlockHeader] =
    if header.number.value == number then Right(header)
    else if header.number.value < number then Left(s"the walk passed block $number at block ${header.number.value}")
    else
      reader.getBlockHeaderByHash(header.parentHash) match
        case Some(parent) => walkBack(reader, parent, number)
        case None =>
          Left(s"the parent of block ${header.number.value} on the downloaded chain is not stored")

  /** The pivot-hash write for a pivot persisted from the SNAP controller's in-memory `(number, stateRoot)`: the stored
    * header at `number` when it has `stateRoot`, which is the header that pivot was taken from; otherwise the pinned
    * hash is removed (a stale one would name another pivot's header), and finalisation compares the state root alone.
    */
  def pinFor(
      appState: AppStateStorage,
      reader: BlockchainReader,
      number: BigInt,
      stateRoot: ByteString
  ): DataSourceBatchUpdate =
    reader.getBlockHeaderByNumber(number).filter(_.stateRoot.value == stateRoot) match
      case Some(header) => appState.putSnapSyncPivotHash(header.hash.value)
      case None         => appState.removeSnapSyncPivotHash()

  /** Persist `validated` as the SNAP pivot, in one write: pivot number, state root, pinned hash, its number→hash index
    * entry and the best-block record. A SNAP restart then resumes on the validated chain's header and heals toward its
    * state root.
    */
  def reanchor(appState: AppStateStorage, writer: BlockchainWriter, validated: BlockHeader): Unit =
    committed(appState, validated.number.value, validated)
      .and(writer.storeBlockHeader(validated))
      .and(appState.putBestBlockInfo(BlockInfo(validated.hash.value, validated.number.value)))
      .commit()
