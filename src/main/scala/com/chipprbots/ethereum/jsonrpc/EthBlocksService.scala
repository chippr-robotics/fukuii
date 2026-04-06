package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.rlp

import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

object EthBlocksService {
  case class BestBlockNumberRequest()
  case class BestBlockNumberResponse(bestBlockNumber: BigInt)

  case class TxCountByBlockHashRequest(blockHash: ByteString)
  case class TxCountByBlockHashResponse(txsQuantity: Option[Int])

  case class BlockByBlockHashRequest(blockHash: ByteString, fullTxs: Boolean)
  case class BlockByBlockHashResponse(blockResponse: Option[BaseBlockResponse])

  case class BlockByNumberRequest(block: BlockParam, fullTxs: Boolean)
  case class BlockByNumberResponse(blockResponse: Option[BaseBlockResponse])

  case class GetBlockTransactionCountByNumberRequest(block: BlockParam)
  case class GetBlockTransactionCountByNumberResponse(result: BigInt)

  case class UncleByBlockHashAndIndexRequest(blockHash: ByteString, uncleIndex: BigInt)
  case class UncleByBlockHashAndIndexResponse(uncleBlockResponse: Option[BaseBlockResponse])

  case class UncleByBlockNumberAndIndexRequest(block: BlockParam, uncleIndex: BigInt)
  case class UncleByBlockNumberAndIndexResponse(uncleBlockResponse: Option[BaseBlockResponse])

  case class GetUncleCountByBlockNumberRequest(block: BlockParam)
  case class GetUncleCountByBlockNumberResponse(result: BigInt)

  case class GetUncleCountByBlockHashRequest(blockHash: ByteString)
  case class GetUncleCountByBlockHashResponse(result: BigInt)

  case class GetBlockReceiptsRequest(block: BlockParam)
  case class GetBlockReceiptsResponse(receipts: Option[Seq[TransactionReceiptResponse]])

  case class FeeHistoryRequest(blockCount: BigInt, newestBlock: BlockParam, rewardPercentiles: Option[Seq[Double]])
  case class FeeHistoryResponse(
      oldestBlock: BigInt,
      baseFeePerGas: Seq[BigInt],
      gasUsedRatio: Seq[Double],
      reward: Option[Seq[Seq[BigInt]]],
      baseFeePerBlobGas: Seq[BigInt],
      blobGasUsedRatio: Seq[Double]
  )

  case class MaxPriorityFeePerGasRequest()
  case class MaxPriorityFeePerGasResponse(maxPriorityFeePerGas: BigInt)

  case class BlobBaseFeeRequest()
  case class BlobBaseFeeResponse(blobBaseFee: BigInt)

  case class GetRawBlockRequest(block: BlockParam)
  case class GetRawBlockResponse(rawBlock: Option[ByteString])

  case class GetRawHeaderRequest(block: BlockParam)
  case class GetRawHeaderResponse(rawHeader: Option[ByteString])

  case class GetRawReceiptsRequest(block: BlockParam)
  case class GetRawReceiptsResponse(rawReceipts: Option[Seq[ByteString]])

}

class EthBlocksService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    val blockQueue: BlockQueue
) extends ResolveBlock {
  import EthBlocksService._

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  /** eth_blockNumber that returns the number of most recent block.
    *
    * @return
    *   Current block number the client is on.
    */
  def bestBlockNumber(req: BestBlockNumberRequest): ServiceResponse[BestBlockNumberResponse] = IO {
    Right(BestBlockNumberResponse(blockchainReader.getBestBlockNumber()))
  }

  /** Implements the eth_getBlockTransactionCountByHash method that fetches the number of txs that a certain block has.
    *
    * @param request
    *   with the hash of the block requested
    * @return
    *   the number of txs that the block has or None if the client doesn't have the block requested
    */
  def getBlockTransactionCountByHash(request: TxCountByBlockHashRequest): ServiceResponse[TxCountByBlockHashResponse] =
    IO {
      val txsCount = blockchainReader.getBlockBodyByHash(request.blockHash).map(_.transactionList.size)
      Right(TxCountByBlockHashResponse(txsCount))
    }

  /** Implements the eth_getBlockByHash method that fetches a requested block.
    *
    * @param request
    *   with the hash of the block requested
    * @return
    *   the block requested or None if the client doesn't have the block
    */
  def getByBlockHash(request: BlockByBlockHashRequest): ServiceResponse[BlockByBlockHashResponse] = IO {
    val BlockByBlockHashRequest(blockHash, fullTxs) = request
    val blockOpt = blockchainReader.getBlockByHash(blockHash).orElse(blockQueue.getBlockByHash(blockHash))
    val weight = blockchainReader.getChainWeightByHash(blockHash).orElse(blockQueue.getChainWeightByHash(blockHash))

    val blockResponseOpt = blockOpt.map(block => BlockResponse(block, weight, fullTxs = fullTxs))
    Right(BlockByBlockHashResponse(blockResponseOpt))
  }

  /** Implements the eth_getBlockByNumber method that fetches a requested block.
    *
    * @param request
    *   with the block requested (by it's number or by tag)
    * @return
    *   the block requested or None if the client doesn't have the block
    */
  def getBlockByNumber(request: BlockByNumberRequest): ServiceResponse[BlockByNumberResponse] = IO {
    val BlockByNumberRequest(blockParam, fullTxs) = request
    val blockResponseOpt =
      resolveBlock(blockParam).toOption.map { case ResolvedBlock(block, pending) =>
        val weight = blockchainReader.getChainWeightByHash(block.header.hash)
        BlockResponse(block, weight, fullTxs = fullTxs, pendingBlock = pending.isDefined)
      }
    Right(BlockByNumberResponse(blockResponseOpt))
  }

  def getBlockTransactionCountByNumber(
      req: GetBlockTransactionCountByNumberRequest
  ): ServiceResponse[GetBlockTransactionCountByNumberResponse] =
    IO {
      resolveBlock(req.block).map { case ResolvedBlock(block, _) =>
        GetBlockTransactionCountByNumberResponse(block.body.transactionList.size)
      }
    }

  /** Implements the eth_getUncleByBlockHashAndIndex method that fetches an uncle from a certain index in a requested
    * block.
    *
    * @param request
    *   with the hash of the block and the index of the uncle requested
    * @return
    *   the uncle that the block has at the given index or None if the client doesn't have the block or if there's no
    *   uncle in that index
    */
  def getUncleByBlockHashAndIndex(
      request: UncleByBlockHashAndIndexRequest
  ): ServiceResponse[UncleByBlockHashAndIndexResponse] = IO {
    val UncleByBlockHashAndIndexRequest(blockHash, uncleIndex) = request
    val uncleHeaderOpt = blockchainReader
      .getBlockBodyByHash(blockHash)
      .flatMap { body =>
        if (uncleIndex >= 0 && uncleIndex < body.uncleNodesList.size)
          Some(body.uncleNodesList.apply(uncleIndex.toInt))
        else
          None
      }
    val weight = uncleHeaderOpt.flatMap(uncleHeader => blockchainReader.getChainWeightByHash(uncleHeader.hash))

    // The block in the response will not have any txs or uncles
    val uncleBlockResponseOpt = uncleHeaderOpt.map { uncleHeader =>
      BlockResponse(blockHeader = uncleHeader, weight = weight, pendingBlock = false)
    }
    Right(UncleByBlockHashAndIndexResponse(uncleBlockResponseOpt))
  }

  /** Implements the eth_getUncleByBlockNumberAndIndex method that fetches an uncle from a certain index in a requested
    * block.
    *
    * @param request
    *   with the number/tag of the block and the index of the uncle requested
    * @return
    *   the uncle that the block has at the given index or None if the client doesn't have the block or if there's no
    *   uncle in that index
    */
  def getUncleByBlockNumberAndIndex(
      request: UncleByBlockNumberAndIndexRequest
  ): ServiceResponse[UncleByBlockNumberAndIndexResponse] = IO {
    val UncleByBlockNumberAndIndexRequest(blockParam, uncleIndex) = request
    val uncleBlockResponseOpt = resolveBlock(blockParam).toOption
      .flatMap { case ResolvedBlock(block, pending) =>
        if (uncleIndex >= 0 && uncleIndex < block.body.uncleNodesList.size) {
          val uncleHeader = block.body.uncleNodesList.apply(uncleIndex.toInt)
          val weight = blockchainReader.getChainWeightByHash(uncleHeader.hash)

          // The block in the response will not have any txs or uncles
          Some(
            BlockResponse(
              blockHeader = uncleHeader,
              weight = weight,
              pendingBlock = pending.isDefined
            )
          )
        } else
          None
      }

    Right(UncleByBlockNumberAndIndexResponse(uncleBlockResponseOpt))
  }

  def getUncleCountByBlockNumber(
      req: GetUncleCountByBlockNumberRequest
  ): ServiceResponse[GetUncleCountByBlockNumberResponse] =
    IO {
      resolveBlock(req.block).map { case ResolvedBlock(block, _) =>
        GetUncleCountByBlockNumberResponse(block.body.uncleNodesList.size)
      }
    }

  def getUncleCountByBlockHash(
      req: GetUncleCountByBlockHashRequest
  ): ServiceResponse[GetUncleCountByBlockHashResponse] =
    IO {
      blockchainReader.getBlockBodyByHash(req.blockHash) match {
        case Some(blockBody) =>
          Right(GetUncleCountByBlockHashResponse(blockBody.uncleNodesList.size))
        case None =>
          Left(
            JsonRpcError.InvalidParams(s"Block with hash ${Hex.toHexString(req.blockHash.toArray[Byte])} not found")
          )
      }
    }

  def getBlockReceipts(req: GetBlockReceiptsRequest): ServiceResponse[GetBlockReceiptsResponse] = IO {
    val result = resolveBlock(req.block).toOption.flatMap { case ResolvedBlock(block, _) =>
      blockchainReader.getReceiptsByHash(block.header.hash).map { receipts =>
        block.body.transactionList.zip(receipts).zipWithIndex.map { case ((stx, receipt), idx) =>
          val gasUsed = if (idx == 0) receipt.cumulativeGasUsed
                        else receipt.cumulativeGasUsed - receipts(idx - 1).cumulativeGasUsed
          val sender = SignedTransaction.getSender(stx).getOrElse(Address(0))
          TransactionReceiptResponse(receipt, stx, sender, idx, block.header, gasUsed)
        }
      }
    }
    Right(GetBlockReceiptsResponse(result))
  }

  def feeHistory(req: FeeHistoryRequest): ServiceResponse[FeeHistoryResponse] = IO {
    val bestBlock = blockchainReader.getBestBlockNumber()
    val newestBlockNum = resolveBlock(req.newestBlock).toOption.map(_.block.header.number).getOrElse(bestBlock)
    val count = req.blockCount.min(1024).toInt
    val oldestBlock = (newestBlockNum - count + 1).max(0)

    val baseFees = (oldestBlock.toLong to (newestBlockNum + 1).toLong).map { num =>
      blockchainReader.getBlockHeaderByNumber(num).flatMap(_.baseFee).getOrElse(BigInt(0))
    }.toSeq

    val gasUsedRatios = (oldestBlock.toLong to newestBlockNum.toLong).map { num =>
      blockchainReader.getBlockHeaderByNumber(num).map { h =>
        if (h.gasLimit > 0) h.gasUsed.toDouble / h.gasLimit.toDouble else 0.0
      }.getOrElse(0.0)
    }.toSeq

    val blobBaseFees = (oldestBlock.toLong to (newestBlockNum + 1).toLong).map { num =>
      blockchainReader.getBlockHeaderByNumber(num).flatMap(_.excessBlobGas).map(_ => BigInt(1)).getOrElse(BigInt(1))
    }.toSeq

    val blobGasUsedRatios = (oldestBlock.toLong to newestBlockNum.toLong).map { num =>
      blockchainReader.getBlockHeaderByNumber(num).flatMap(_.blobGasUsed).map { used =>
        if (used > 0) used.toDouble / 786432.0 else 0.0
      }.getOrElse(0.0)
    }.toSeq

    val rewards = req.rewardPercentiles.map { _ =>
      (oldestBlock.toLong to newestBlockNum.toLong).map { _ =>
        req.rewardPercentiles.getOrElse(Seq.empty).map(_ => BigInt(0))
      }.toSeq
    }

    Right(FeeHistoryResponse(
      oldestBlock = oldestBlock,
      baseFeePerGas = baseFees,
      gasUsedRatio = gasUsedRatios,
      reward = rewards,
      baseFeePerBlobGas = blobBaseFees,
      blobGasUsedRatio = blobGasUsedRatios
    ))
  }

  def maxPriorityFeePerGas(req: MaxPriorityFeePerGasRequest): ServiceResponse[MaxPriorityFeePerGasResponse] = IO {
    Right(MaxPriorityFeePerGasResponse(BigInt(1000000000)))
  }

  def blobBaseFee(req: BlobBaseFeeRequest): ServiceResponse[BlobBaseFeeResponse] = IO {
    val fee = blockchainReader.getBestBlock().flatMap(_.header.excessBlobGas).map(_ => BigInt(1)).getOrElse(BigInt(1))
    Right(BlobBaseFeeResponse(fee))
  }

  def getRawBlock(req: GetRawBlockRequest): ServiceResponse[GetRawBlockResponse] = IO {
    val raw = resolveBlock(req.block).toOption.map { case ResolvedBlock(block, _) =>
      ByteString(rlp.encode(block.toRLPEncodable))
    }
    Right(GetRawBlockResponse(raw))
  }

  def getRawHeader(req: GetRawHeaderRequest): ServiceResponse[GetRawHeaderResponse] = IO {
    val raw = resolveBlock(req.block).toOption.map { case ResolvedBlock(block, _) =>
      ByteString(rlp.encode(block.header.toRLPEncodable))
    }
    Right(GetRawHeaderResponse(raw))
  }

  def getRawReceipts(req: GetRawReceiptsRequest): ServiceResponse[GetRawReceiptsResponse] = IO {
    import com.chipprbots.ethereum.network.p2p.messages.ETH63.ReceiptImplicits.given
    val raw = resolveBlock(req.block).toOption.flatMap { case ResolvedBlock(block, _) =>
      blockchainReader.getReceiptsByHash(block.header.hash).map { receipts =>
        receipts.map(r => ByteString(r.toBytes))
      }
    }
    Right(GetRawReceiptsResponse(raw))
  }
}
