package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.util.ByteString

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
    blockchainWriter: BlockchainWriter
) extends Logger:

  private val currentState: AtomicReference[Option[ForkChoiceState]] =
    new AtomicReference(None)

  // Listener that wants to know whenever the CL publishes a head, even when the head is unknown
  // (Left("SYNCING") branch) — that's exactly the trigger SNAP needs to begin / re-pivot. Set
  // by SyncController on post-merge chains; never set on ETC mainnet (terminalTotalDifficulty=None).
  private val listenerRef: AtomicReference[Option[TypedActorRef[ForkChoiceManager.BeaconHead]]] =
    new AtomicReference(None)

  def isActive: Boolean = currentState.get().isDefined

  def getState: Option[ForkChoiceState] = currentState.get()

  def getHeadBlockHash: Option[ByteString] = currentState.get().map(_.headBlockHash)

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
    val maybeHeader = blockchainReader.getBlockHeaderByHash(BlockHash(newState.headBlockHash))

    // Publish to the listener regardless of head-known status — SNAP needs the
    // unknown-head case as its trigger to start / re-pivot. The listener message
    // is fire-and-forget; the rest of this method's behavior is unchanged.
    publishBeaconHead(newState.headBlockHash, maybeHeader)

    if maybeHeader.isEmpty then
      log.info(s"Fork choice head ${newState.headBlockHash} not known yet (SYNCING)")
      Left("SYNCING")
    else
      log.info(
        s"Fork choice updated: head=${newState.headBlockHash}, " +
          s"safe=${newState.safeBlockHash}, finalized=${newState.finalizedBlockHash}"
      )
      currentState.set(Some(newState))

      // Rewrite number→hash mapping for the new canonical branch (no-op if already canonical).
      // Then persist canonical best-block pointer.
      maybeHeader.foreach { header =>
        blockchainWriter.promoteBranchToCanonical(BlockHash(newState.headBlockHash), blockchainReader)
        blockchainWriter.saveBestKnownBlocks(BlockHash(newState.headBlockHash), header.number.value)
      }

      Right(())

  /** Publish the CL head to the registered listener and do **nothing else**.
    *
    * This is the notify-only half of [[applyForkChoiceState]], for the SYNCING branches of `engine_forkchoiceUpdated`.
    * Those branches need the [[ForkChoiceManager.BeaconHead]] publish — it is the trigger SyncController forwards as
    * `CLPivotHint` to drive SNAP-sync pivot selection (#1207) — but they must NOT write a canonical number→hash
    * mapping, must NOT move the best-block pointer, and must NOT cache `currentState`: the head they are reporting on
    * has not been executed by us.
    *
    * Calling [[applyForkChoiceState]] here instead was a consensus defect. When the head was present by hash but
    * unexecuted (stored via `storeBlockByHashOnly`), the header lookup succeeded, so `promoteBranchToCanonical` +
    * `saveBestKnownBlocks` ran and wrote number→hash for a block we never validated. `engine_newPayload`'s dedup branch
    * then read that mapping back as proof of prior successful execution and answered VALID for an invalid block. hive
    * `invalid_payload.go:242` ("Invalid NewPayload, Transaction *, Syncing=True") requires INVALID there.
    */
  def notifyBeaconHead(newState: ForkChoiceState): Unit =
    val maybeHeader = blockchainReader.getBlockHeaderByHash(BlockHash(newState.headBlockHash))
    log.info(
      "Fork choice head {} not executed yet (SYNCING, notify-only): headerKnown={}",
      newState.headBlockHash,
      maybeHeader.isDefined
    )
    publishBeaconHead(newState.headBlockHash, maybeHeader)

  /** Clear fork choice state (e.g., on shutdown or mode switch). */
  def clear(): Unit = currentState.set(None)

  private def publishBeaconHead(headHash: ByteString, knownHeader: Option[BlockHeader]): Unit =
    listenerRef.get().foreach { ref =>
      ref ! ForkChoiceManager.BeaconHead(headHash, knownHeader)
    }

object ForkChoiceManager:

  /** Notification sent by [[ForkChoiceManager]] to its registered listener whenever the CL pushes a fork choice via
    * engine_forkchoiceUpdated. Carries both the head hash (always) and the locally-stored header (when we already have
    * it). When `knownHeader` is `None`, the listener may need to fetch the header by hash from peers — that's the
    * post-merge initial-sync case where the EL is far behind the CL.
    */
  final case class BeaconHead(headHash: ByteString, knownHeader: Option[BlockHeader])
