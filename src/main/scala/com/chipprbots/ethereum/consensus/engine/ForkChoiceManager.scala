package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

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
) extends Logger {

  private val currentState: AtomicReference[Option[ForkChoiceState]] =
    new AtomicReference(None)

  // Listener that wants to know whenever the CL publishes a head, even when the head is unknown
  // (Left("SYNCING") branch) — that's exactly the trigger SNAP needs to begin / re-pivot. Set
  // by SyncController on post-merge chains; never set on ETC mainnet (terminalTotalDifficulty=None).
  private val listenerRef: AtomicReference[Option[ActorRef]] = new AtomicReference(None)

  def isActive: Boolean = currentState.get().isDefined

  def getState: Option[ForkChoiceState] = currentState.get()

  def getHeadBlockHash: Option[ByteString] = currentState.get().map(_.headBlockHash)

  def getSafeBlockHash: Option[ByteString] = currentState.get().map(_.safeBlockHash)

  def getFinalizedBlockHash: Option[ByteString] = currentState.get().map(_.finalizedBlockHash)

  /** Register a listener to receive [[ForkChoiceManager.BeaconHead]] messages. Replaces any previously-registered
    * listener. Only registered on post-merge chains (gated by `blockchainConfig.terminalTotalDifficulty.isDefined` in
    * SyncController).
    */
  def setListener(ref: ActorRef): Unit = listenerRef.set(Some(ref))

  /** Unregister the current listener (e.g. on shutdown / mode switch). */
  def clearListener(): Unit = listenerRef.set(None)

  /** Apply a new fork choice state from the CL via engine_forkchoiceUpdated.
    *
    * @param newState
    *   the fork choice state from CL
    * @return
    *   Right(()) if valid, Left(error) if head block is unknown
    */
  def applyForkChoiceState(newState: ForkChoiceState): Either[String, Unit] = {
    val maybeHeader = blockchainReader.getBlockHeaderByHash(newState.headBlockHash)

    // Publish to the listener regardless of head-known status — SNAP needs the
    // unknown-head case as its trigger to start / re-pivot. The listener message
    // is fire-and-forget; the rest of this method's behavior is unchanged.
    publishBeaconHead(newState.headBlockHash, maybeHeader)

    if (maybeHeader.isEmpty) {
      log.info(s"Fork choice head ${newState.headBlockHash} not known yet (SYNCING)")
      Left("SYNCING")
    } else {
      log.info(
        s"Fork choice updated: head=${newState.headBlockHash}, " +
          s"safe=${newState.safeBlockHash}, finalized=${newState.finalizedBlockHash}"
      )
      currentState.set(Some(newState))

      // Rewrite number→hash mapping for the new canonical branch (no-op if already canonical).
      // Then persist canonical best-block pointer.
      maybeHeader.foreach { header =>
        blockchainWriter.promoteBranchToCanonical(newState.headBlockHash, blockchainReader)
        blockchainWriter.saveBestKnownBlocks(newState.headBlockHash, header.number)
      }

      Right(())
    }
  }

  /** Clear fork choice state (e.g., on shutdown or mode switch). */
  def clear(): Unit = currentState.set(None)

  private def publishBeaconHead(headHash: ByteString, knownHeader: Option[BlockHeader]): Unit =
    listenerRef.get().foreach { ref =>
      ref ! ForkChoiceManager.BeaconHead(headHash, knownHeader)
    }
}

object ForkChoiceManager {

  /** Notification sent by [[ForkChoiceManager]] to its registered listener whenever the CL pushes a fork choice via
    * engine_forkchoiceUpdated. Carries both the head hash (always) and the locally-stored header (when we already have
    * it). When `knownHeader` is `None`, the listener may need to fetch the header by hash from peers — that's the
    * post-merge initial-sync case where the EL is far behind the CL.
    */
  final case class BeaconHead(headHash: ByteString, knownHeader: Option[BlockHeader])
}
