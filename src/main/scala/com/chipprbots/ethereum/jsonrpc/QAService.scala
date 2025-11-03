package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.implicits._

import enumeratum._
import mouse.all._

import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.NewCheckpoint
import com.chipprbots.ethereum.consensus.blocks.CheckpointBlockGenerator
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses._
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Checkpoint
import com.chipprbots.ethereum.jsonrpc.QAService.MineBlocksResponse.MinerResponseType
import com.chipprbots.ethereum.jsonrpc.QAService._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

class QAService(
    mining: Mining,
    blockchainReader: BlockchainReader,
    checkpointBlockGenerator: CheckpointBlockGenerator,
    blockchainConfig: BlockchainConfig,
    syncController: ActorRef
) extends Logger {

  /** qa_mineBlocks that instructs mocked miner to mine given number of blocks
    *
    * @param req
    *   with requested block's data
    * @return
    *   nothing
    */
  def mineBlocks(req: MineBlocksRequest): ServiceResponse[MineBlocksResponse] =
    mining
      .askMiner(MineBlocks(req.numBlocks, req.withTransactions, req.parentBlock))
      .map(_ |> (MineBlocksResponse(_)) |> (_.asRight))
      .handleError { throwable =>
        log.warn("Unable to mine requested blocks", throwable)
        Left(JsonRpcError.InternalError)
      }

  def generateCheckpoint(
      req: GenerateCheckpointRequest
  ): ServiceResponse[GenerateCheckpointResponse] = {
    val hash = req.blockHash.orElse(blockchainReader.getBestBlock().map(_.hash))
    hash match {
      case Some(hashValue) =>
        IO {
          val parent =
            blockchainReader
              .getBlockByHash(hashValue)
              .orElse(blockchainReader.getBestBlock())
              .getOrElse(blockchainReader.genesisBlock)
          val checkpoint = generateCheckpoint(hashValue, req.privateKeys)
          val checkpointBlock: Block = checkpointBlockGenerator.generate(parent, checkpoint)
          syncController ! NewCheckpoint(checkpointBlock)
          Right(GenerateCheckpointResponse(checkpoint))
        }
      case None => IO.pure(Left(JsonRpcError.BlockNotFound))
    }
  }

  private def generateCheckpoint(blockHash: ByteString, privateKeys: Seq[ByteString]): Checkpoint = {
    val keys = privateKeys.map { key =>
      crypto.keyPairFromPrvKey(key.toArray)
    }
    val signatures = keys.map(ECDSASignature.sign(blockHash.toArray, _))
    Checkpoint(signatures)
  }

  def getFederationMembersInfo(
      req: GetFederationMembersInfoRequest
  ): ServiceResponse[GetFederationMembersInfoResponse] =
    IO {
      Right(GetFederationMembersInfoResponse(blockchainConfig.checkpointPubKeys.toList))
    }
}

object QAService {
  case class MineBlocksRequest(numBlocks: Int, withTransactions: Boolean, parentBlock: Option[ByteString] = None)
  case class MineBlocksResponse(responseType: MinerResponseType, message: Option[String])
  object MineBlocksResponse {
    def apply(minerResponse: MockedMinerResponse): MineBlocksResponse =
      MineBlocksResponse(MinerResponseType(minerResponse), extractMessage(minerResponse))

    private def extractMessage(response: MockedMinerResponse): Option[String] = response match {
      case MinerIsWorking | MiningOrdered | MinerNotExist => None
      case MiningError(msg)                               => Some(msg)
      case MinerNotSupported(msg)                         => Some(msg.toString)
    }

    sealed trait MinerResponseType extends EnumEntry
    object MinerResponseType extends Enum[MinerResponseType] {
      val values = findValues

      case object MinerIsWorking extends MinerResponseType
      case object MiningOrdered extends MinerResponseType
      case object MinerNotExist extends MinerResponseType
      case object MiningError extends MinerResponseType
      case object MinerNotSupport extends MinerResponseType

      def apply(minerResponse: MockedMinerResponse): MinerResponseType = minerResponse match {
        case MockedMinerResponses.MinerIsWorking       => MinerIsWorking
        case MockedMinerResponses.MiningOrdered        => MiningOrdered
        case MockedMinerResponses.MinerNotExist        => MinerNotExist
        case MockedMinerResponses.MiningError(_)       => MiningError
        case MockedMinerResponses.MinerNotSupported(_) => MinerNotSupport
      }
    }
  }

  case class GenerateCheckpointRequest(privateKeys: Seq[ByteString], blockHash: Option[ByteString])
  case class GenerateCheckpointResponse(checkpoint: Checkpoint)

  case class GetFederationMembersInfoRequest()
  case class GetFederationMembersInfoResponse(membersPublicKeys: Seq[ByteString])
}
