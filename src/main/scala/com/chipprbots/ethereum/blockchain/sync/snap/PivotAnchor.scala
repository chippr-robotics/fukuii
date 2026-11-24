package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PivotOnBranch
import com.chipprbots.ethereum.db.storage.pathstate.StateMetrics
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.CanonicalHeadLock
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

  /** What the header download says of the branch a sync is to take its pivot on (B-307). */
  enum BranchCheck:
    /** No sync is bound: no escalation for a branch came before this sync, or the requirement has ended. */
    case NotRequired

    /** The download holds the branch's first block at its height: the pivot above it is on the branch. */
    case OnTheBranch(required: PivotOnBranch)

    /** The download has not reached the branch's height, or what it holds there cannot be told yet. */
    case Undecided(required: PivotOnBranch, reason: String)

    /** The download holds another block at the branch's height: the chain the peers served is not the branch. Nothing
      * is written here: the sync controller stops this sync, counts the attempt (`attempt` is the number it will have)
      * and clears what the sync wrote
      * ([[com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage.clearForBranchRetry]]).
      */
    case OffTheBranch(required: PivotOnBranch, found: BlockHeader, attempt: Int)

    /** The requirement is ended, and the sync goes on with the chain it has: it was missed once too often, its record
      * could not be read, or the branch's height is further below the download's tip than is walked back.
      */
    case Dropped(why: String)

  /** Whether the chain the header download follows is the branch the sync is to take its pivot on, and what is done
    * about it when it is not (B-307; forge's design, the user's decision of 2026-11-16).
    *
    * Asked once the download has reached the pivot, '''before the pivot is anchored to the download''': anchored first,
    * a pivot of another chain would be pinned.
    */
  def branchCheck(ingest: StateIngest): BranchCheck = branchCheck(ingest.appState, ingest.chainReader)

  def branchCheck(appState: AppStateStorage, reader: BlockchainReader): BranchCheck =
    scala.util.Try(appState.getPivotOnBranch()) match
      case scala.util.Failure(unreadable) =>
        dropped(appState, s"its record cannot be read (${unreadable.getMessage})")
      case scala.util.Success(None)           => BranchCheck.NotRequired
      case scala.util.Success(Some(required)) =>
        // Only the validated download says it (forge): the index of blocks by number is also written by the pivot
        // bootstrap, with headers nothing validated.
        val cursor = appState.getBackfillBestHeader()
        if cursor >= required.number && cursor - required.number > MaxWalkBack then
          dropped(
            appState,
            s"block ${required.number} is ${cursor - required.number} blocks below the header download's tip, more " +
              s"than the $MaxWalkBack walked back: what the download holds there cannot be told"
          )
        else
          validatedHeaderAt(appState, reader, required.number) match
            case Left(reason)                                      => BranchCheck.Undecided(required, reason)
            case Right(found) if found.hash.value == required.hash => BranchCheck.OnTheBranch(required)
            case Right(found) if required.attempts + 1 >= PivotOnBranch.MaxAttempts =>
              StateMetrics.pivotRequirementMissed()
              dropped(
                appState,
                s"${required.attempts + 1} syncs found another chain at block ${required.number} " +
                  s"(this one found ${found.hash.value.toArray.take(4).map("%02x".format(_)).mkString})"
              )
            case Right(found) => BranchCheck.OffTheBranch(required, found, required.attempts + 1)

  /** What the header download does with a response that holds the height of the branch a sync is bound to (B-307 c2;
    * forge's design). Nothing in a sync follows the weight of a chain: the download asks one peer by number, and the
    * first answer at the branch's height decides the chain every later header stands on. So the answer is looked at
    * before it is stored.
    */
  enum BranchGate:
    /** No sync is bound, the response does not hold the branch's height, or it holds the branch's first block. */
    case Pass

    /** The response holds another block at the branch's height: nothing of it is stored, and its peer is not asked for
      * headers for a while. The peer is on another chain; it is not at fault.
      */
    case Refuse(required: PivotOnBranch, found: BlockHeader)

    /** No peer has served the branch for [[BranchWaitMillis]]: the requirement is ended, and the response is stored. */
    case Expired(why: String)

  /** How long a header download waits below the branch for a peer that serves it, from the first response that was of
    * another chain there (forge; the user's decision of 2026-11-16 bounds the attempts).
    */
  val BranchWaitMillis: Long = 30L * 60 * 1000

  /** Asked by the header download of each run of headers it is about to store.
    *
    * The first response of another chain is written down with its time (`firstMissMillis`), so that a restart does not
    * begin the wait again; the branch's block, once served, takes the time away.
    */
  def branchGate(appState: AppStateStorage, run: Seq[BlockHeader], nowMillis: Long): BranchGate =
    scala.util.Try(appState.getPivotOnBranch()).toOption.flatten match
      case None => BranchGate.Pass // a record that cannot be read is dropped, and said, where the pivot is checked
      case Some(required) =>
        run.find(_.number.value == required.number) match
          case None => BranchGate.Pass
          case Some(found) if found.hash.value == required.hash =>
            if required.firstMissMillis.isDefined then
              appState.putPivotOnBranch(required.copy(firstMissMillis = None)).commit()
            BranchGate.Pass
          case Some(found) =>
            val since = required.firstMissMillis.getOrElse(nowMillis)
            if nowMillis - since >= BranchWaitMillis then
              dropped(
                appState,
                s"for ${(nowMillis - since) / 60000} minutes every peer asked for block ${required.number} served " +
                  s"another block than the branch's (the last served ${found.hash.value.toArray.take(4).map("%02x".format(_)).mkString})"
              ) match
                case BranchCheck.Dropped(why) => BranchGate.Expired(why)
                case _                        => BranchGate.Pass
            else
              if required.firstMissMillis.isEmpty then
                appState.putPivotOnBranch(required.copy(firstMissMillis = Some(nowMillis))).commit()
              StateMetrics.pivotRequirementRefused()
              BranchGate.Refuse(required, found)

  /** The branch a sync's pivot is to be on, as the pivot's bootstrap asks for it: its first block's number and hash. A
    * record that cannot be read binds no bootstrap; it is dropped, and said, where the pivot is checked.
    */
  def requiredBranch(appState: AppStateStorage): Option[(BigInt, ByteString)] =
    scala.util.Try(appState.getPivotOnBranch()).toOption.flatten.map(bound => (bound.number, bound.hash))

  /** A peer served the branch: the wait for one ([[BranchWaitMillis]]) is over. Called when the pivot's bootstrap took
    * its header from a peer on the branch; the header download does the same when it stores the branch's block. Left
    * standing, a wait the bootstrap began would end the requirement at the download's first answer of another chain
    * (forge).
    */
  def branchServed(appState: AppStateStorage): Unit =
    branchServed(appState, servedAt = None)

  /** As [[branchServed]], for a header taken at `servedAt`: only a header at or above the branch's height was asked for
    * on the branch. Below it the bootstrap asks one header by number with no check of the branch (B-310, forge), and it
    * says nothing of a peer on the branch.
    */
  def branchServed(appState: AppStateStorage, servedAt: Option[BigInt]): Unit =
    scala.util
      .Try(appState.getPivotOnBranch())
      .toOption
      .flatten
      .filter(_.firstMissMillis.isDefined)
      .filter(required => servedAt.forall(_ >= required.number))
      .foreach(waiting => appState.putPivotOnBranch(waiting.copy(firstMissMillis = None)).commit())

  /** The lowest pivot a refresh may take while a sync is bound to a branch (B-310, forge): the branch's first block. A
    * pivot below it is of the blocks both chains have, and its bootstrap does not ask for the branch: a refresh that
    * backtracked under it would end the requirement with a WARN at the end of the sync, never with the ERROR the user
    * decided on.
    */
  def refreshFloor(appState: AppStateStorage): Option[BigInt] = requiredBranch(appState).map(_._1)

  /** Whether the wait for a peer on the branch ([[BranchWaitMillis]]) has run out, where nothing else would look: a
    * refresh that finds no peer to take a target from, when every peer left is one on another chain (B-310, forge). At
    * its end the requirement is dropped.
    *
    * @return
    *   why the requirement was dropped, when it was: said at ERROR by the caller
    */
  def waitExpired(appState: AppStateStorage, nowMillis: Long): Option[String] =
    scala.util.Try(appState.getPivotOnBranch()).toOption.flatten.flatMap { required =>
      required.firstMissMillis.filter(since => nowMillis - since >= BranchWaitMillis).flatMap { since =>
        dropped(
          appState,
          s"for ${(nowMillis - since) / 60000} minutes no peer served the branch's block at block ${required.number}"
        ) match
          case BranchCheck.Dropped(why) => Some(why)
          case _                        => None
      }
    }

  /** The peers the pivot's bootstrap found on another chain than the branch (B-307 c2; forge's design). The target of a
    * sync is the best height its peers claim: where the chain that was left is the longer, the target is above every
    * peer of the branch, and only the other chain's peers are asked for its header. Leaving those out of the next
    * choice lowers the target to a height the branch's peers have.
    *
    * In memory, each for [[OffBranchPeers.HeldMillis]]: a peer's chain may reorganize onto the branch. Owned by one
    * actor.
    */
  final class OffBranchPeers(appStateOf: () => AppStateStorage):
    private var until: Map[com.chipprbots.ethereum.network.PeerId, Long] = Map.empty

    /** Whether `peer` is left out of the choice of a target. Only while a sync is bound to a branch (B-310, forge): a
      * requirement that ended elsewhere (the header download's gate, the check at the end of a sync, a hand-off) leaves
      * no peer held.
      */
    def holds(peer: com.chipprbots.ethereum.network.PeerId, nowMillis: Long): Boolean =
      if requiredBranch(appStateOf()).isEmpty then
        until = Map.empty
        false
      else until.get(peer).exists(_ > nowMillis)

    /** [[PivotAnchor.refreshFloor]] of this actor's store. */
    def refreshFloor: Option[BigInt] = PivotAnchor.refreshFloor(appStateOf())

    /** Whether the network's best, measured without the peers held, is under the branch's first block: no peer left can
      * serve a pivot on the branch (B-310, forge). The wait for one is begun, so that [[waitExpired]] can end it:
      * nothing else would, since no bootstrap is asked.
      */
    def belowTheBranch(networkBest: BigInt, nowMillis: Long): Boolean =
      val appState = appStateOf()
      scala.util.Try(appState.getPivotOnBranch()).toOption.flatten.filter(networkBest < _.number) match
        case Some(required) =>
          if required.firstMissMillis.isEmpty then
            appState.putPivotOnBranch(required.copy(firstMissMillis = Some(nowMillis))).commit()
          true
        case None => false

    /** [[PivotAnchor.waitExpired]] of this actor's store. */
    def waitExpired(nowMillis: Long): Option[String] = PivotAnchor.waitExpired(appStateOf(), nowMillis)

    /** Leaves `offBranch` out for a while, and counts nothing towards the wait for a peer on the branch: for a
      * bootstrap that is not the pivot's (B-320), whose miss is no verdict on the requirement.
      */
    def hold(offBranch: Set[com.chipprbots.ethereum.network.PeerId], nowMillis: Long): Unit =
      until = until.filter(_._2 > nowMillis) ++ offBranch.map(_ -> (nowMillis + OffBranchPeers.HeldMillis))

    /** A bootstrap ended without the pivot's header. With peers on another chain among those it asked, they are left
      * out for a while, and the wait for a peer on the branch is begun or gone on with ([[BranchWaitMillis]], the same
      * wait the header download counts). At its end the requirement is dropped.
      *
      * @return
      *   why the requirement was dropped, when it was: said at ERROR by the caller
      */
    def missed(offBranch: Set[com.chipprbots.ethereum.network.PeerId], nowMillis: Long): Option[String] =
      val appState = appStateOf()
      until = until.filter(_._2 > nowMillis) ++ offBranch.map(_ -> (nowMillis + OffBranchPeers.HeldMillis))
      if offBranch.isEmpty then None
      else
        scala.util.Try(appState.getPivotOnBranch()).toOption.flatten.flatMap { required =>
          val since = required.firstMissMillis.getOrElse(nowMillis)
          if nowMillis - since >= BranchWaitMillis then
            until = Map.empty
            dropped(
              appState,
              s"for ${(nowMillis - since) / 60000} minutes no peer that has the pivot's height served the branch's " +
                s"block at block ${required.number}"
            ) match
              case BranchCheck.Dropped(why) => Some(why)
              case _                        => None
          else
            if required.firstMissMillis.isEmpty then
              appState.putPivotOnBranch(required.copy(firstMissMillis = Some(nowMillis))).commit()
            StateMetrics.pivotRequirementRefused()
            None
        }

  object OffBranchPeers:
    /** The peers of the actor that owns `ingest`: its store is read when asked, as the controller reads it. */
    def of(ingest: StateIngest): OffBranchPeers = new OffBranchPeers(() => ingest.appState)

    val HeldMillis: Long = 10L * 60 * 1000

  /** The log tag of the requirement that the sync after an escalation take its pivot on the refused branch (B-307). */
  val PivotOnBranchTag: String = "[STATE-ESCALATION-PIVOT]"

  /** What is said, at ERROR, when the requirement is ended without a sync on the branch. */
  def requirementDroppedText(why: String): String =
    s"$PivotOnBranchTag This node gave its state up to follow a branch, and the sync after it is no longer held to " +
      s"that branch: $why. It syncs the chain the peers serve. The branch stays given up: if it is offered again and " +
      "is the heavier, this node does not give its state up for it a second time. To let it, start the node once " +
      "with -Dfukuii.state.recovery.forget-given-up-branches=true."

  /** Ends the requirement where the header download cannot be asked at all: it validates no chain of headers below the
    * pivot (forge). Said, never silent.
    *
    * @return
    *   why it was ended, or none when no sync was bound
    */
  def dropUnchecked(ingest: StateIngest): Option[String] = dropUnchecked(ingest.appState)

  def dropUnchecked(appState: AppStateStorage): Option[String] =
    scala.util.Try(appState.getPivotOnBranch()) match
      case scala.util.Success(None) => None
      case _ =>
        dropped(appState, "this chain's header download validates no chain of headers below the pivot") match
          case BranchCheck.Dropped(why) => Some(why)
          case _                        => None

  private def dropped(appState: AppStateStorage, why: String): BranchCheck =
    appState.removePivotOnBranch().commit()
    StateMetrics.pivotRequirementDropped()
    BranchCheck.Dropped(why)

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
    // under the head's lock (B-511): a consensus client's fork choice may come while SNAP runs; one write
    CanonicalHeadLock.held(s"SNAP's re-anchor of its pivot to block ${validated.number.value}") {
      committed(appState, validated.number.value, validated)
        .and(writer.storeBlockHeader(validated))
        .and(appState.putBestBlockInfo(BlockInfo(validated.hash.value, validated.number.value)))
        .commit()
    }
