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
  * ([[HeaderChainCheck.Validated]], [[HeaderChainCheck.ProofOfStake]]).
  *
  * The persisted pivot (`SnapSyncPivotBlock`, `SnapSyncStateRoot` and `SnapSyncPivotHash`) names the header whose state
  * root SNAP downloaded. At finalisation that header must be the one the validated header download holds at the pivot's
  * number, and its state root the persisted one. The download's header at that number is the one it recorded at its
  * target (`BackfillTargetHash`), or else its recorded tip (`BackfillBestHeaderHash`) walked back by parent hash; never
  * the number→hash index: the pivot bootstrap and the pivot's best-block record write that index too, with headers no
  * one validated.
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

  /** `chosenByConsensusLayer`: the pivot came from the consensus layer (post-merge chains), see [[pinForChosenPivot]].
    */
  def pinFor(
      ingest: StateIngest,
      number: BigInt,
      stateRoot: ByteString,
      chosenByConsensusLayer: Boolean = false
  ): DataSourceBatchUpdate =
    if chosenByConsensusLayer then pinForChosenPivot(ingest.appState, ingest.chainReader, number, stateRoot)
    else pinFor(ingest.appState, ingest.chainReader, number, stateRoot)
  def currentPin(ingest: StateIngest, pivot: BigInt): Option[ByteString] =
    currentPin(ingest.appState, ingest.chainReader, pivot)
  def storedAncestor(ingest: StateIngest, headHash: ByteString, depth: Int): Option[BlockHeader] =
    storedAncestor(ingest.chainReader, headHash, depth)

  /** The header stored under `hash`. */
  def storedHeader(ingest: StateIngest, hash: ByteString): Option[BlockHeader] =
    ingest.chainReader.getBlockHeaderByHash(BlockHash(hash))

  /** What to do with a downloaded chain that does not reach the pinned pivot ([[rewindTarget]]). */
  enum Rewind:
    /** Download again above the [[forkPoint]]. */
    case ToForkPoint(header: BlockHeader)

    /** The pinned header is stored but its stored ancestry holds no header of the downloaded chain: download again
      * above genesis.
      */
    case ToGenesis(genesis: BlockHeader)

    /** The pinned header is not stored: there is nothing to walk, and no reason to discard the download. */
    case PinnedNotStored

  /** Where to rewind a downloaded chain that does not reach the pinned pivot `pinnedHash` (its header at the pivot's
    * number is `downloaded`).
    */
  def rewindTarget(ingest: StateIngest, pinnedHash: Option[ByteString], downloaded: BlockHeader): Rewind =
    val reader = ingest.chainReader
    pinnedHash.flatMap(h => reader.getBlockHeaderByHash(BlockHash(h))) match
      case None => Rewind.PinnedNotStored
      case Some(pinned) =>
        forkPoint(reader, pinned, downloaded) match
          case Some(fork) => Rewind.ToForkPoint(fork)
          case None       => Rewind.ToGenesis(reader.genesisHeader)
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

  /** The longest walk back by parent hash from the download's tip to the pivot. The pivot is normally the download's
    * target, read directly ([[validatedHeaderAt]]); a pivot further below the tip than this is not decided.
    */
  val MaxWalkBack: Int = 65536

  def check(appState: AppStateStorage, reader: BlockchainReader, pivot: BigInt): Check =
    validatedHeaderAt(appState, reader, pivot) match
      case Left(reason) => Check.Undecided(reason)
      case Right(validated) =>
        val pinned = currentPin(appState, reader, pivot)
        val root = appState.getSnapSyncStateRoot()
        if pinned.forall(_ == validated.hash.value) && root.contains(validated.stateRoot.value) then
          Check.Anchored(validated)
        else Check.Diverged(validated, pinned, root)

  /** The persisted pivot hash, if it can name the pivot at `pivot`. A pin naming a stored header at another height is
    * stale (written for an earlier pivot and not replaced, say by a build without the key between two that have it): it
    * says nothing about this pivot, so it is ignored and the state root decides. A pin naming no stored header is still
    * compared.
    */
  def currentPin(appState: AppStateStorage, reader: BlockchainReader, pivot: BigInt): Option[ByteString] =
    appState
      .getSnapSyncPivotHash()
      .filterNot(h => reader.getBlockHeaderByHash(BlockHash(h)).exists(_.number.value != pivot))

  /** Where the downloaded chain and the pinned pivot's chain part, for a pivot the consensus layer chose
    * (HeaderChainCheck.trustsPinnedPivot): the highest header that is on both. `pinned` and `downloaded` are the two
    * headers at the pivot's number; both are walked down by parent hash in step, through headers stored by hash, at
    * most [[MaxWalkBack]] steps. The header returned is an ancestor of the pinned one, so it and every header below it
    * on the downloaded chain are the pinned chain's. None when the pinned header's stored ancestry ends first, or the
    * walk reaches genesis without a common header.
    */
  def forkPoint(reader: BlockchainReader, pinned: BlockHeader, downloaded: BlockHeader): Option[BlockHeader] =
    @tailrec
    def loop(a: BlockHeader, d: BlockHeader, steps: Int): Option[BlockHeader] =
      if a.parentHash == d.parentHash then reader.getBlockHeaderByHash(d.parentHash)
      else if steps >= MaxWalkBack || a.number.value == 0 || d.number.value == 0 then None
      else
        (reader.getBlockHeaderByHash(a.parentHash), reader.getBlockHeaderByHash(d.parentHash)) match
          case (Some(ap), Some(dp)) if ap.number == dp.number => loop(ap, dp, steps + 1)
          case _                                              => None
    if pinned.number != downloaded.number then None
    else if pinned.hash == downloaded.hash then Some(pinned)
    else loop(pinned, downloaded, 0)

  /** The header `depth` blocks below the one stored under `headHash`, by parent hash through headers stored by hash
    * (the consensus layer's blocks are stored by hash as they arrive): None when one on the way is not stored. Every
    * header returned is an ancestor of `headHash` by construction.
    */
  def storedAncestor(reader: BlockchainReader, headHash: ByteString, depth: Int): Option[BlockHeader] =
    @tailrec
    def loop(header: BlockHeader, left: Int): Option[BlockHeader] =
      if left == 0 then Some(header)
      else
        reader.getBlockHeaderByHash(header.parentHash) match
          case Some(parent) if parent.number.value == header.number.value - 1 => loop(parent, left - 1)
          case _                                                              => None
    if depth < 0 then None
    else reader.getBlockHeaderByHash(BlockHash(headHash)).flatMap(loop(_, depth))

  /** The pivot-hash write for a pivot the consensus layer chose (HeaderChainCheck.trustsPinnedPivot), persisted from
    * the SNAP controller's in-memory `(number, stateRoot)`: the header the best-block record names, which every pivot
    * change writes in one batch with that pivot's header (`updateBestBlockForPivot`), or else the one already pinned (a
    * resumed pivot), whichever is at `number` with `stateRoot`. Otherwise the pin is removed, and finalisation chooses
    * the pivot again. Never the number index: the header download writes it with headers from peers.
    */
  def pinForChosenPivot(
      appState: AppStateStorage,
      reader: BlockchainReader,
      number: BigInt,
      stateRoot: ByteString
  ): DataSourceBatchUpdate =
    val best = appState.getBestBlockInfo()
    val candidates = Option.when(best.number == number && best.hash.nonEmpty)(best.hash).toSeq ++
      appState.getSnapSyncPivotHash().toSeq
    val chosen = candidates.iterator
      .flatMap(h => reader.getBlockHeaderByHash(BlockHash(h)))
      .find(h => h.number.value == number && h.stateRoot.value == stateRoot)
    chosen match
      case Some(header) => appState.putSnapSyncPivotHash(header.hash.value)
      case None         => appState.removeSnapSyncPivotHash()

  /** The header the validated download holds at `number`. With nothing downloaded (cursor 0) the chain is genesis
    * alone. When `number` is the download's target, the header it recorded there (`BackfillTargetHash`); otherwise the
    * recorded tip walked back by parent hash, at most [[MaxWalkBack]] headers.
    */
  def validatedHeaderAt(
      appState: AppStateStorage,
      reader: BlockchainReader,
      number: BigInt
  ): Either[String, BlockHeader] =
    val cursor = appState.getBackfillBestHeader()
    lazy val atTarget = appState
      .getBackfillTargetHash()
      .flatMap(h => reader.getBlockHeaderByHash(BlockHash(h)))
      .filter(_.number.value == number)
    if cursor < number then Left(s"the header download is at block $cursor, below block $number")
    else if cursor == 0 then Right(reader.genesisHeader)
    else if atTarget.isDefined then Right(atTarget.get)
    else if cursor - number > MaxWalkBack then
      Left(
        s"block $number is ${cursor - number} blocks below the header download's tip, more than the $MaxWalkBack " +
          "walked back (walk > cap)"
      )
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
    // On the unified state layout the best block moves only with the state (spec 018, plan.md "C4b part 3"): a pivot
    // is moved before the state has an anchor, never after.
    appState.getStateAnchor().foreach { anchor =>
      throw new IllegalStateException(
        s"The pivot cannot be moved to block ${validated.number.value}: the state is held in place at block " +
          s"${anchor.show}, and the best block moves only with it"
      )
    }
    committed(appState, validated.number.value, validated)
      .and(writer.storeBlockHeader(validated))
      .and(appState.putBestBlockInfo(BlockInfo(validated.hash.value, validated.number.value)))
      .commit()
