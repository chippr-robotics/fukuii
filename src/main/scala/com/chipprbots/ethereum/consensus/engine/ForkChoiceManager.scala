package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.utils.Logger

/** Thread-safe manager for CL-driven fork choice (post-Merge Ethereum).
  *
  * When active, this replaces total-difficulty-based fork choice with CL-driven fork choice. The CL (Prysm, Lighthouse,
  * etc.) drives the canonical chain via forkchoiceUpdated calls.
  *
  * Also publishes [[ForkChoiceManager.BeaconHead]] events to a registered listener every time the CL pushes a new fork
  * choice — this is the wire used by [[com.chipprbots.ethereum.blockchain.sync.SyncController]] to drive SNAP-sync
  * pivot selection on post-merge chains. Closes #1207.
  */
class ForkChoiceManager(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    // What the canonical index move means for the state's reference counts: the blocks it drops are undone, the ones it
    // adopts are put back (idempotent). NoOp for callers with no reference-counted state; the node passes its
    // BlockchainImpl.
    reorgState: ReorgStateHandler = ReorgStateHandler.NoOp
) extends Logger:

  // EXECUTED heads only. Written solely by the head-known branch of `applyForkChoiceState`, never by
  // `notifyBeaconHead`. Everything that treats the head as validated (isActive, safe/finalized reads, RPC) reads this.
  private val currentState: AtomicReference[Option[ForkChoiceState]] =
    new AtomicReference(None)

  // The headBlockHash of the most recent fork-choice request that reached this manager through EITHER entry point,
  // executed or not. Deliberately separate from `currentState`: see `getRequestedHeadBlockHash` for why the p2p fork
  // choice needs this and must not read `currentState`, and why `currentState` must not be widened to cover it.
  private val requestedHead: AtomicReference[Option[ByteString]] =
    new AtomicReference(None)

  // Listener that wants to know whenever the CL publishes a head, even when the head is unknown
  // (Left("SYNCING") branch) — that's exactly the trigger SNAP needs to begin / re-pivot. Set
  // by SyncController on post-merge chains; never set on ETC mainnet (terminalTotalDifficulty=None).
  private val listenerRef: AtomicReference[Option[TypedActorRef[ForkChoiceManager.BeaconHead]]] =
    new AtomicReference(None)

  def isActive: Boolean = currentState.get().isDefined

  def getState: Option[ForkChoiceState] = currentState.get()

  /** The head of the last fork choice we EXECUTED and applied. Stays on the old head while the CL points at a head we
    * have only stored by hash — see [[getRequestedHeadBlockHash]] for the value that follows the CL in that case.
    */
  def getHeadBlockHash: Option[ByteString] = currentState.get().map(_.headBlockHash)

  /** The `headBlockHash` the consensus layer most recently asked for, whether or not we have executed it — the input to
    * PoS fork choice on the p2p import path ([[DesignatedHead]]).
    *
    * WHY NOT [[getHeadBlockHash]]. That reads `currentState`, which only the head-known branch of
    * [[applyForkChoiceState]] writes. The one situation p2p fork choice exists for — the CL names a side-chain head
    * that arrived via `engine_newPayload`, was stored by hash only (ACCEPTED), and whose ancestors we still have to
    * fetch from a peer — is routed by `EngineApiService.forkchoiceUpdated` through [[notifyBeaconHead]], which by
    * design leaves `currentState` alone. So `getHeadBlockHash` still names the OLD canonical head exactly when a
    * competing branch arrives, the ancestry walk from it never meets the branch tip, and the branch is dropped as
    * `NoChainSwitch` unexecuted. Measured on hive `engine` 4854b7d20: 0 of 28 `Invalid Missing Ancestor Syncing ReOrg …
    * CanonicalReOrg=True` / `Withdrawals … Re-Org Sync` targets cleared while bound to `getHeadBlockHash`.
    *
    * WHY NOT WIDEN `currentState` INSTEAD. `currentState` is read as "validated": [[notifyBeaconHead]]'s scaladoc
    * records the consensus defect caused the last time an unexecuted head was treated as applied. This value carries no
    * such claim, and nothing but [[DesignatedHead]] reads it.
    *
    * WHY IT IS SAFE TO FOLLOW. It only ever lets the importer ATTEMPT a branch that leads to this head; every block is
    * still executed by `ConsensusImpl`, and an invalid one is reported through `InvalidChainReporter`. Heads already
    * known INVALID never reach this manager: `forkchoiceUpdated` answers INVALID before calling either entry point.
    */
  def getRequestedHeadBlockHash: Option[ByteString] = requestedHead.get()

  def getSafeBlockHash: Option[ByteString] = currentState.get().map(_.safeBlockHash)

  def getFinalizedBlockHash: Option[ByteString] = currentState.get().map(_.finalizedBlockHash)

  /** Register a listener to receive [[ForkChoiceManager.BeaconHead]] messages. Replaces any previously-registered
    * listener. Only registered on post-merge chains (gated by `blockchainConfig.terminalTotalDifficulty.isDefined` in
    * SyncController).
    */
  def setListener(ref: TypedActorRef[ForkChoiceManager.BeaconHead]): Unit = listenerRef.set(Some(ref))

  /** Unregister the current listener (e.g. on shutdown / mode switch). */
  def clearListener(): Unit = listenerRef.set(None)

  /** Apply a new fork choice state from the CL via engine_forkchoiceUpdated.
    *
    * @param newState
    *   the fork choice state from CL
    * @return
    *   Right(()) if valid, Left(error) if head block is unknown
    */
  def applyForkChoiceState(newState: ForkChoiceState): Either[String, Unit] =
    requestedHead.set(Some(newState.headBlockHash))
    val maybeHeader = blockchainReader.getBlockHeaderByHash(BlockHash(newState.headBlockHash))

    // Publish to the listener regardless of head-known status — SNAP needs the
    // unknown-head case as its trigger to start / re-pivot. The listener message
    // is fire-and-forget; the rest of this method's behavior is unchanged.
    publishBeaconHead(newState.headBlockHash, maybeHeader)

    if maybeHeader.isEmpty then
      log.info(s"Fork choice head ${newState.headBlockHash} not known yet (SYNCING)")
      Left("SYNCING")
    else if blockchainWriter.holdsStateInPlace then
      maybeHeader.fold[Either[String, Unit]](Left("SYNCING"))(confirmHeldHead(newState, _))
    else
      log.info(
        s"Fork choice updated: head=${newState.headBlockHash}, " +
          s"safe=${newState.safeBlockHash}, finalized=${newState.finalizedBlockHash}"
      )
      currentState.set(Some(newState))

      // One batch: the number→hash index becomes exactly the head's ancestry (the new branch written, every entry
      // above the head deleted) and the best-block pointer moves to the head — whether the head moved up, sideways,
      // or DOWN to an ancestor or a shorter side chain.
      maybeHeader.foreach { header =>
        val move = blockchainWriter.promoteToCanonicalHead(header, blockchainReader)
        // Reference counts follow the canonical index: a block is applied exactly while it is canonical (see
        // ReorgStateHandler). Abandon first, then re-adopt, as ConsensusImpl.settleHead does; both are idempotent.
        reorgState.abandonBlockStates(move.dropped.map { case (n, h) => (n, h.value) })
        reorgState.readoptBlockStates(move.adopted.map { case (n, h) => (n, h.value) })
      }

      Right(())

  /** The fork choice on the unified state layout (spec 018 C4b part 3b), where the head moves with the state or not at
    * all: through [[BlockchainWriter.moveHeadInPlace]], never [[BlockchainWriter.promoteToCanonicalHead]], which
    * refuses there.
    *
    * `EngineApiService.forkchoiceUpdated` lets through only a head that is the block whose state is held, and a commit
    * of the next block in place may land between that look and this write. So the head is confirmed by a move of
    * nothing, written under the state lock against the anchor it was planned from: a commit that landed first makes it
    * a move back, which is refused, and the best block stays on the block just committed (beacon, plan.md "C4b part 3"
    * T145). Since C4d part 2b a fork choice moves the head up a chain of executed blocks, or across a fork along the
    * changeset records and the other branch's executed blocks ([[BlockchainWriter.moveHeadInPlace]]), and never down to
    * an ancestor of the head, which go-ethereum skips too. The reference counts of the hash store are not touched: the
    * changeset records are what the unified layout keeps of a block it leaves.
    *
    * `currentState` is set only when the head was confirmed: it is read as the head that was executed and applied.
    *
    * @return
    *   `Left("SYNCING")` when the head was not followed, and nothing was written (its state is not held: a block whose
    *   executed state was not kept, or a fork beyond the records; or an ancestor of the head); when the plan found the
    *   records corrupt, the state has failed closed too. `Left` starting [[ForkChoiceManager.FinalizedReversed]] when
    *   the move would reverse the finalized block
    */
  private def confirmHeldHead(newState: ForkChoiceState, header: BlockHeader): Either[String, Unit] =
    def notMoved(why: String): Either[String, Unit] =
      log.warn(s"Fork choice head ${header.number.value} ${header.hashAsHexString} not applied (SYNCING): $why")
      Left("SYNCING")
    // the finalized block it names, by its header (the Engine API has found it on the head's chain); the zero hash
    // names none, and leaves the one named before (C4d part 1)
    val finalized =
      Option.when(newState.finalizedBlockHash != ForkChoiceManager.ZeroHash)(newState.finalizedBlockHash).flatMap {
        hash =>
          val named = blockchainReader
            .getBlockHeaderByHash(BlockHash(hash))
            .map(finalizedHeader => FinalizedBlock(finalizedHeader.number.value, finalizedHeader.hash.value))
          if named.isEmpty then
            log.warn(
              s"Fork choice names finalized block ${ForkChoiceManager.hex(hash)}, whose header is not stored: the one named before is kept"
            )
          named.foreach { block =>
            // taken, as go-ethereum's SetFinalized takes it: on the held state's chain it only loosens what is kept
            blockchainWriter.finalizedOfTheState.filter(_.number > block.number).foreach { before =>
              log.warn(
                s"Fork choice names finalized block ${block.number}, below the one named before (${before.number})"
              )
            }
          }
          named
      }
    try
      val moved = blockchainWriter.moveHeadInPlace(
        header,
        blockchainReader,
        finalized = finalized,
        // up a chain of executed blocks, or across a fork along the records and the executed blocks of the other
        // branch; never down to an ancestor of the head, which go-ethereum skips too ("Ignoring beacon update to old
        // head"): the Engine API answers that one VALID, and moves nothing (C4d part 2b)
        admit = plan =>
          Option.when(plan.forward.isEmpty && plan.reverse.nonEmpty)(
            s"block ${header.number.value} is an ancestor of the head (${plan.anchor.show}): the head is not moved down " +
              "to follow a fork choice"
          )
      )
      log.info(
        s"Fork choice updated: head=${newState.headBlockHash} ($moved), " +
          s"safe=${newState.safeBlockHash}, finalized=${newState.finalizedBlockHash}"
      )
      currentState.set(Some(newState))
      Right(())
    catch
      case unavailable: StateUnavailable => notMoved(unavailable.reason)
      // A record that is not what it should be is this datadir's fault, not the fork choice's: the state fails closed
      // (no further in-place write, no state given out, ERROR) and the head is answered as one not followed (plan.md
      // "C4b part 1": `Corrupt` fails closed at the Engine API; the escalation is C4d's)
      case refused: ReorgTooDeep if refused.reason == ReorgRefusal.Corrupt =>
        blockchainWriter.failStateClosed(s"the changeset records are corrupt (${refused.getMessage})")
        notMoved(refused.getMessage)
      // a move that would reverse the finalized block: the fork choice is not consistent with what it finalized
      // (go-ethereum: "final block not in canonical chain"), answered -38002 and not SYNCING (the user's decision of
      // 2026-11-14)
      case refused: ReorgTooDeep if refused.reason == ReorgRefusal.Finalized =>
        log.warn(s"Fork choice head ${header.number.value} ${header.hashAsHexString} refused: ${refused.getMessage}")
        Left(ForkChoiceManager.FinalizedReversed + refused.getMessage)
      case refused: ReorgTooDeep => notMoved(refused.getMessage)

  /** Publish the CL head to the registered listener and record it as the requested head — **nothing else**.
    *
    * This is the notify-only half of [[applyForkChoiceState]], for the SYNCING branches of `engine_forkchoiceUpdated`.
    * Those branches need the [[ForkChoiceManager.BeaconHead]] publish — it is the trigger SyncController forwards as
    * `CLPivotHint` to drive SNAP-sync pivot selection (#1207) — but they must NOT write a canonical number→hash
    * mapping, must NOT move the best-block pointer, and must NOT cache `currentState`: the head they are reporting on
    * has not been executed by us. It DOES record the head as requested ([[getRequestedHeadBlockHash]]), which claims
    * nothing about execution and is what lets the p2p import path fetch and execute the branch that leads to it.
    *
    * Calling [[applyForkChoiceState]] here instead was a consensus defect. When the head was present by hash but
    * unexecuted (stored via `storeBlockByHashOnly`), the header lookup succeeded, so the canonical-head write
    * (`BlockchainWriter.promoteToCanonicalHead`) wrote number→hash for a block we never validated.
    * `engine_newPayload`'s dedup branch then read that mapping back as proof of prior successful execution and answered
    * VALID for an invalid block. hive `invalid_payload.go:242` ("Invalid NewPayload, Transaction *, Syncing=True")
    * requires INVALID there.
    */
  def notifyBeaconHead(newState: ForkChoiceState): Unit =
    requestedHead.set(Some(newState.headBlockHash))
    val maybeHeader = blockchainReader.getBlockHeaderByHash(BlockHash(newState.headBlockHash))
    log.info(
      "Fork choice head {} not executed yet (SYNCING, notify-only): headerKnown={}",
      newState.headBlockHash,
      maybeHeader.isDefined
    )
    publishBeaconHead(newState.headBlockHash, maybeHeader)

  /** Clear fork choice state (e.g., on shutdown or mode switch). */
  def clear(): Unit =
    currentState.set(None)
    requestedHead.set(None)

  private def publishBeaconHead(headHash: ByteString, knownHeader: Option[BlockHeader]): Unit =
    listenerRef.get().foreach { ref =>
      ref ! ForkChoiceManager.BeaconHead(headHash, knownHeader)
    }

object ForkChoiceManager:

  /** The start of what [[ForkChoiceManager.applyForkChoiceState]] answers for a fork choice whose head would reverse
    * the finalized block: an inconsistent fork choice state, not one this node has yet to sync.
    */
  val FinalizedReversed: String = "FINALIZED: "

  /** The hash a fork choice names when it names no block (a safe or finalized block before there is one). */
  val ZeroHash: ByteString = ByteString(new Array[Byte](32))

  private[engine] def hex(hash: ByteString): String =
    com.chipprbots.ethereum.utils.Hex.toHexString(hash.toArray).take(16)

  /** Notification sent by [[ForkChoiceManager]] to its registered listener whenever the CL pushes a fork choice via
    * engine_forkchoiceUpdated. Carries both the head hash (always) and the locally-stored header (when we already have
    * it). When `knownHeader` is `None`, the listener may need to fetch the header by hash from peers — that's the
    * post-merge initial-sync case where the EL is far behind the CL.
    */
  final case class BeaconHead(headHash: ByteString, knownHeader: Option[BlockHeader])
