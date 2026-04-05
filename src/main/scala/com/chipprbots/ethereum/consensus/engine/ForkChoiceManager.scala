package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.utils.Logger

/** Thread-safe manager for CL-driven fork choice (post-Merge Ethereum).
  *
  * When active, this replaces total-difficulty-based fork choice with CL-driven fork choice.
  * The CL (Prysm, Lighthouse, etc.) drives the canonical chain via forkchoiceUpdated calls.
  */
class ForkChoiceManager(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter
) extends Logger {

  private val currentState: AtomicReference[Option[ForkChoiceState]] =
    new AtomicReference(None)

  def isActive: Boolean = currentState.get().isDefined

  def getState: Option[ForkChoiceState] = currentState.get()

  def getHeadBlockHash: Option[ByteString] = currentState.get().map(_.headBlockHash)

  def getSafeBlockHash: Option[ByteString] = currentState.get().map(_.safeBlockHash)

  def getFinalizedBlockHash: Option[ByteString] = currentState.get().map(_.finalizedBlockHash)

  /** Apply a new fork choice state from the CL via engine_forkchoiceUpdated.
    *
    * @param newState the fork choice state from CL
    * @return Right(()) if valid, Left(error) if head block is unknown
    */
  def applyForkChoiceState(newState: ForkChoiceState): Either[String, Unit] = {
    val headKnown = blockchainReader.getBlockHeaderByHash(newState.headBlockHash).isDefined

    if (!headKnown) {
      log.info(s"Fork choice head ${newState.headBlockHash} not known yet (SYNCING)")
      Left("SYNCING")
    } else {
      log.info(
        s"Fork choice updated: head=${newState.headBlockHash}, " +
          s"safe=${newState.safeBlockHash}, finalized=${newState.finalizedBlockHash}"
      )
      currentState.set(Some(newState))

      // Persist canonical head to chain storage
      blockchainReader.getBlockHeaderByHash(newState.headBlockHash).foreach { header =>
        blockchainWriter.saveBestKnownBlocks(newState.headBlockHash, header.number)
      }

      Right(())
    }
  }

  /** Clear fork choice state (e.g., on shutdown or mode switch). */
  def clear(): Unit = currentState.set(None)
}
