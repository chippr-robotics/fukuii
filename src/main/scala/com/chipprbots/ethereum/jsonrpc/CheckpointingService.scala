package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO

import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.NewCheckpoint
import com.chipprbots.ethereum.consensus.blocks.CheckpointBlockGenerator
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Checkpoint
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

class CheckpointingService(
    blockchainReader: BlockchainReader,
    blockQueue: BlockQueue,
    checkpointBlockGenerator: CheckpointBlockGenerator,
    syncController: ActorRef
) extends Logger {

  import CheckpointingService._

  def getLatestBlock(req: GetLatestBlockRequest): ServiceResponse[GetLatestBlockResponse] = {
    lazy val bestBlockNum = blockchainReader.getBestBlockNumber()
    lazy val blockToReturnNum =
      if (req.checkpointingInterval != 0)
        bestBlockNum - bestBlockNum % req.checkpointingInterval
      else bestBlockNum
    lazy val isValidParent =
      req.parentCheckpoint.forall(blockchainReader.getBlockHeaderByHash(_).exists(_.number < blockToReturnNum))

    IO {
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), blockToReturnNum)
    }.flatMap {
      case Some(b) if isValidParent =>
        IO.pure(Right(GetLatestBlockResponse(Some(BlockInfo(b.hash, b.number)))))

      case Some(_) =>
        log.debug("No checkpoint candidate found for a specified parent")
        IO.pure(Right(GetLatestBlockResponse(None)))

      case None =>
        log.error(
          s"Failed to retrieve block for checkpointing: block at number $blockToReturnNum was unavailable " +
            s"even though best block number was $bestBlockNum (re-org occurred?)"
        )
        getLatestBlock(req) // this can fail only during a re-org, so we just try again
    }
  }

  def pushCheckpoint(req: PushCheckpointRequest): ServiceResponse[PushCheckpointResponse] = IO {
    val parentHash = req.hash

    blockchainReader.getBlockByHash(parentHash).orElse(blockQueue.getBlockByHash(parentHash)) match {
      case Some(parent) =>
        val checkpointBlock: Block = checkpointBlockGenerator.generate(parent, Checkpoint(req.signatures))
        syncController ! NewCheckpoint(checkpointBlock)

      case None =>
        log.error(s"Could not find parent (${ByteStringUtils.hash2string(parentHash)}) for new checkpoint block")
    }
    Right(PushCheckpointResponse())
  }
}

object CheckpointingService {
  final case class GetLatestBlockRequest(checkpointingInterval: Int, parentCheckpoint: Option[ByteString])
  final case class GetLatestBlockResponse(block: Option[BlockInfo])
  final case class BlockInfo(hash: ByteString, number: BigInt)

  final case class PushCheckpointRequest(hash: ByteString, signatures: List[ECDSASignature])
  final case class PushCheckpointResponse()
}
