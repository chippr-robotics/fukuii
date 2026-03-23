package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.implicits._

import enumeratum._
import mouse.all._

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses._
import com.chipprbots.ethereum.jsonrpc.QAService.MineBlocksResponse.MinerResponseType
import com.chipprbots.ethereum.jsonrpc.QAService._
import com.chipprbots.ethereum.utils.Logger

class QAService(
    mining: Mining
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
        log.debug("Unable to mine requested blocks", throwable)
        Left(JsonRpcError.InternalError)
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
      val values: IndexedSeq[MinerResponseType] = findValues

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
}
