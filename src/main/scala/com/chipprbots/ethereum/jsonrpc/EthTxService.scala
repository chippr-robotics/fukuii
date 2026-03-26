package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.TransactionPicker
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

object EthTxService {
  case class GetTransactionByHashRequest(txHash: ByteString) // rename to match request
  case class GetTransactionByHashResponse(txResponse: Option[TransactionResponse])
  case class GetTransactionByBlockHashAndIndexRequest(blockHash: ByteString, transactionIndex: BigInt)
  case class GetTransactionByBlockHashAndIndexResponse(transactionResponse: Option[TransactionResponse])
  case class GetTransactionByBlockNumberAndIndexRequest(block: BlockParam, transactionIndex: BigInt)
  case class GetTransactionByBlockNumberAndIndexResponse(transactionResponse: Option[TransactionResponse])
  case class GetGasPriceRequest()
  case class GetGasPriceResponse(price: BigInt)
  case class SendRawTransactionRequest(data: ByteString)
  case class SendRawTransactionResponse(transactionHash: ByteString)
  case class EthPendingTransactionsRequest()
  case class EthPendingTransactionsResponse(pendingTransactions: Seq[PendingTransaction])
  case class GetTransactionReceiptRequest(txHash: ByteString)
  case class GetTransactionReceiptResponse(txResponse: Option[TransactionReceiptResponse])
  case class RawTransactionResponse(transactionResponse: Option[SignedTransaction])

  case class GetBlockReceiptsRequest(block: BlockParam)
  case class GetBlockReceiptsResponse(receipts: Option[Seq[TransactionReceiptResponse]])

  case class FeeHistoryRequest(blockCount: Int, newestBlock: BlockParam, rewardPercentiles: Seq[Double])
  case class FeeHistoryResponse(
      oldestBlock: BigInt,
      baseFeePerGas: Seq[BigInt],
      gasUsedRatio: Seq[Double],
      reward: Option[Seq[Seq[BigInt]]]
  )

  case class MaxPriorityFeePerGasRequest()
  case class MaxPriorityFeePerGasResponse(maxPriorityFeePerGas: BigInt)
}

class EthTxService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    val pendingTransactionsManager: ActorRef,
    val getTransactionFromPoolTimeout: FiniteDuration,
    transactionMappingStorage: TransactionMappingStorage
) extends TransactionPicker
    with ResolveBlock {
  import EthTxService._

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  /** Implements the eth_getRawTransactionByHash - fetch raw transaction data of a transaction with the given hash.
    *
    * The tx requested will be fetched from the pending tx pool or from the already executed txs (depending on the tx
    * state)
    *
    * @param req
    *   with the tx requested (by it's hash)
    * @return
    *   the raw transaction hask or None if the client doesn't have the tx
    */
  def getRawTransactionByHash(req: GetTransactionByHashRequest): ServiceResponse[RawTransactionResponse] =
    getTransactionDataByHash(req.txHash).map(asRawTransactionResponse)

  /** eth_getRawTransactionByBlockHashAndIndex returns raw transaction data of a transaction with the block hash and
    * index of which it was mined
    *
    * @return
    *   the tx requested or None if the client doesn't have the block or if there's no tx in the that index
    */
  def getRawTransactionByBlockHashAndIndex(
      req: GetTransactionByBlockHashAndIndexRequest
  ): ServiceResponse[RawTransactionResponse] =
    getTransactionByBlockHashAndIndex(req.blockHash, req.transactionIndex)
      .map(asRawTransactionResponse)

  private def asRawTransactionResponse(txResponse: Option[TransactionData]): Right[Nothing, RawTransactionResponse] =
    Right(RawTransactionResponse(txResponse.map(_.stx)))

  /** Implements the eth_getTransactionByHash method that fetches a requested tx. The tx requested will be fetched from
    * the pending tx pool or from the already executed txs (depending on the tx state)
    *
    * @param req
    *   with the tx requested (by it's hash)
    * @return
    *   the tx requested or None if the client doesn't have the tx
    */
  def getTransactionByHash(req: GetTransactionByHashRequest): ServiceResponse[GetTransactionByHashResponse] = {
    val eventualMaybeData = getTransactionDataByHash(req.txHash)
    eventualMaybeData.map(txResponse => Right(GetTransactionByHashResponse(txResponse.map(TransactionResponse(_)))))
  }

  private def getTransactionDataByHash(txHash: ByteString): IO[Option[TransactionData]] = {
    val maybeTxPendingResponse: IO[Option[TransactionData]] = getTransactionsFromPool.map {
      _.pendingTransactions.map(_.stx.tx).find(_.hash == txHash).map(TransactionData(_))
    }

    maybeTxPendingResponse.map { txPending =>
      txPending.orElse {
        for {
          TransactionLocation(blockHash, txIndex) <- transactionMappingStorage.get(txHash)
          Block(header, body) <- blockchainReader.getBlockByHash(blockHash)
          stx <- body.transactionList.lift(txIndex)
        } yield TransactionData(stx, Some(header), Some(txIndex))
      }
    }
  }

  def getTransactionReceipt(req: GetTransactionReceiptRequest): ServiceResponse[GetTransactionReceiptResponse] =
    IO {
      val result: Option[TransactionReceiptResponse] = for {
        TransactionLocation(blockHash, txIndex) <- transactionMappingStorage.get(req.txHash)
        Block(header, body) <- blockchainReader.getBlockByHash(blockHash)
        stx <- body.transactionList.lift(txIndex)
        receipts <- blockchainReader.getReceiptsByHash(blockHash)
        receipt: Receipt <- receipts.lift(txIndex)
        // another possibility would be to throw an exception and fail hard, as if we cannot calculate sender for transaction
        // included in blockchain it means that something is terribly wrong
        sender <- SignedTransaction.getSender(stx)
      } yield {

        val gasUsed =
          if (txIndex == 0) receipt.cumulativeGasUsed
          else receipt.cumulativeGasUsed - receipts(txIndex - 1).cumulativeGasUsed

        TransactionReceiptResponse(
          receipt = receipt,
          stx = stx,
          signedTransactionSender = sender,
          transactionIndex = txIndex,
          blockHeader = header,
          gasUsedByTransaction = gasUsed
        )
      }

      Right(GetTransactionReceiptResponse(result))
    }

  /** eth_getTransactionByBlockHashAndIndex that returns information about a transaction by block hash and transaction
    * index position.
    *
    * @return
    *   the tx requested or None if the client doesn't have the block or if there's no tx in the that index
    */
  def getTransactionByBlockHashAndIndex(
      req: GetTransactionByBlockHashAndIndexRequest
  ): ServiceResponse[GetTransactionByBlockHashAndIndexResponse] =
    getTransactionByBlockHashAndIndex(req.blockHash, req.transactionIndex)
      .map(td => Right(GetTransactionByBlockHashAndIndexResponse(td.map(TransactionResponse(_)))))

  private def getTransactionByBlockHashAndIndex(blockHash: ByteString, transactionIndex: BigInt) =
    IO {
      for {
        blockWithTx <- blockchainReader.getBlockByHash(blockHash)
        blockTxs = blockWithTx.body.transactionList if transactionIndex >= 0 && transactionIndex < blockTxs.size
        transaction <- blockTxs.lift(transactionIndex.toInt)
      } yield TransactionData(transaction, Some(blockWithTx.header), Some(transactionIndex.toInt))
    }

  def getGetGasPrice(req: GetGasPriceRequest): ServiceResponse[GetGasPriceResponse] = {
    val blockDifference = 30
    val bestBlock = blockchainReader.getBestBlockNumber()

    IO {
      val bestBranch = blockchainReader.getBestBranch()
      val gasPrice = ((bestBlock - blockDifference) to bestBlock)
        .flatMap(nb => blockchainReader.getBlockByNumber(bestBranch, nb))
        .flatMap(_.body.transactionList)
        .map(_.tx.gasPrice)
      if (gasPrice.nonEmpty) {
        val avgGasPrice = gasPrice.sum / gasPrice.length
        Right(GetGasPriceResponse(avgGasPrice))
      } else {
        Right(GetGasPriceResponse(0))
      }
    }
  }

  def sendRawTransaction(req: SendRawTransactionRequest): ServiceResponse[SendRawTransactionResponse] = {
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.SignedTransactionDec

    Try(req.data.toArray.toSignedTransaction) match {
      case Success(signedTransaction) =>
        if (SignedTransaction.getSender(signedTransaction).isDefined) {
          pendingTransactionsManager ! PendingTransactionsManager.AddOrOverrideTransaction(signedTransaction)
          IO.pure(Right(SendRawTransactionResponse(signedTransaction.hash)))
        } else {
          IO.pure(Left(JsonRpcError.InvalidRequest))
        }
      case Failure(_) =>
        IO.pure(Left(JsonRpcError.InvalidRequest))
    }
  }

  /** eth_getTransactionByBlockNumberAndIndex Returns the information about a transaction with the block number and
    * index of which it was mined.
    *
    * @param req
    *   block number and index
    * @return
    *   transaction
    */
  def getTransactionByBlockNumberAndIndex(
      req: GetTransactionByBlockNumberAndIndexRequest
  ): ServiceResponse[GetTransactionByBlockNumberAndIndexResponse] = IO {
    getTransactionDataByBlockNumberAndIndex(req.block, req.transactionIndex)
      .map(_.map(TransactionResponse(_)))
      .map(GetTransactionByBlockNumberAndIndexResponse.apply)
  }

  /** eth_getRawTransactionByBlockNumberAndIndex Returns raw transaction data of a transaction with the block number and
    * index of which it was mined.
    *
    * @param req
    *   block number and ordering in which a transaction is mined within its block
    * @return
    *   raw transaction data
    */
  def getRawTransactionByBlockNumberAndIndex(
      req: GetTransactionByBlockNumberAndIndexRequest
  ): ServiceResponse[RawTransactionResponse] = IO {
    getTransactionDataByBlockNumberAndIndex(req.block, req.transactionIndex)
      .map(x => x.map(_.stx))
      .map(RawTransactionResponse.apply)
  }

  private def getTransactionDataByBlockNumberAndIndex(block: BlockParam, transactionIndex: BigInt) =
    resolveBlock(block)
      .map { blockWithTx =>
        val blockTxs = blockWithTx.block.body.transactionList
        if (transactionIndex >= 0 && transactionIndex < blockTxs.size)
          Some(
            TransactionData(
              blockTxs(transactionIndex.toInt),
              Some(blockWithTx.block.header),
              Some(transactionIndex.toInt)
            )
          )
        else None
      }
      .left
      .flatMap(_ => Right(None))

  /** Returns the transactions that are pending in the transaction pool and have a from address that is one of the
    * accounts this node manages.
    *
    * @param req
    *   request
    * @return
    *   pending transactions
    */
  def ethPendingTransactions(req: EthPendingTransactionsRequest): ServiceResponse[EthPendingTransactionsResponse] =
    getTransactionsFromPool.map { resp =>
      Right(EthPendingTransactionsResponse(resp.pendingTransactions))
    }

  /** eth_getBlockReceipts — returns all receipts for a given block. */
  def getBlockReceipts(req: GetBlockReceiptsRequest): ServiceResponse[GetBlockReceiptsResponse] =
    IO {
      resolveBlock(req.block) match {
        case Right(resolved) =>
          val header = resolved.block.header
          val txList = resolved.block.body.transactionList
          val receipts = blockchainReader.getReceiptsByHash(header.hash)

          val receiptResponses = receipts.map { rcpts =>
            txList.zip(rcpts).zipWithIndex.flatMap { case ((stx, receipt), idx) =>
              SignedTransaction.getSender(stx).map { sender =>
                val gasUsed =
                  if (idx == 0) receipt.cumulativeGasUsed
                  else receipt.cumulativeGasUsed - rcpts(idx - 1).cumulativeGasUsed
                TransactionReceiptResponse(receipt, stx, sender, idx, header, gasUsed)
              }
            }
          }
          Right(GetBlockReceiptsResponse(receiptResponses))
        case Left(_) =>
          Right(GetBlockReceiptsResponse(None))
      }
    }

  /** eth_maxPriorityFeePerGas — suggests a max priority fee per gas.
    * Samples recent blocks and returns the median effective priority fee.
    * Matches go-ethereum's eth/gasprice oracle approach.
    */
  def getMaxPriorityFeePerGas(req: MaxPriorityFeePerGasRequest): ServiceResponse[MaxPriorityFeePerGasResponse] =
    IO {
      val sampleBlocks = 20
      val bestBlock = blockchainReader.getBestBlockNumber()
      val bestBranch = blockchainReader.getBestBranch()
      val startBlock = (bestBlock - sampleBlocks).max(0)

      val priorityFees = (startBlock to bestBlock).flatMap { num =>
        blockchainReader.getBlockByNumber(bestBranch, num).toSeq.flatMap { block =>
          val baseFee = block.header.baseFee.getOrElse(BigInt(0))
          block.body.transactionList.map { stx =>
            val effectiveGas = Transaction.effectiveGasPrice(stx.tx, block.header.baseFee)
            (effectiveGas - baseFee).max(0)
          }
        }
      }

      val suggestion =
        if (priorityFees.isEmpty) BigInt(1000000000) // 1 gwei default
        else {
          val sorted = priorityFees.sorted
          sorted(sorted.length / 2) // median
        }

      Right(MaxPriorityFeePerGasResponse(suggestion))
    }

  /** eth_feeHistory — returns historical gas information for a range of blocks.
    * Matches go-ethereum's eth/gasprice/feehistory.go implementation.
    */
  def feeHistory(req: FeeHistoryRequest): ServiceResponse[FeeHistoryResponse] =
    IO {
      val bestBlock = blockchainReader.getBestBlockNumber()
      val bestBranch = blockchainReader.getBestBranch()

      // Resolve newest block
      val newestBlockNum = req.newestBlock match {
        case BlockParam.Latest       => bestBlock
        case BlockParam.Pending      => bestBlock
        case BlockParam.Earliest     => BigInt(0)
        case BlockParam.WithNumber(n) => n
      }

      // Clamp block count to 1024 (go-ethereum limit)
      val blockCount = req.blockCount.min(1024).max(1)
      val oldestBlock = (newestBlockNum - blockCount + 1).max(0)
      val actualCount = (newestBlockNum - oldestBlock + 1).toInt

      val baseFees = new scala.collection.mutable.ArrayBuffer[BigInt](actualCount + 1)
      val gasUsedRatios = new scala.collection.mutable.ArrayBuffer[Double](actualCount)
      val rewards: Option[scala.collection.mutable.ArrayBuffer[Seq[BigInt]]] =
        if (req.rewardPercentiles.nonEmpty) Some(new scala.collection.mutable.ArrayBuffer[Seq[BigInt]](actualCount))
        else None

      (0 until actualCount).foreach { i =>
        val blockNum = oldestBlock + i
        blockchainReader.getBlockByNumber(bestBranch, blockNum) match {
          case Some(block) =>
            val header = block.header
            baseFees += header.baseFee.getOrElse(BigInt(0))

            val ratio =
              if (header.gasLimit > 0) header.gasUsed.toDouble / header.gasLimit.toDouble
              else 0.0
            gasUsedRatios += ratio

            // Calculate reward percentiles if requested
            rewards.foreach { rewardBuf =>
              val baseFee = header.baseFee.getOrElse(BigInt(0))
              val txList = block.body.transactionList
              if (txList.isEmpty) {
                rewardBuf += req.rewardPercentiles.map(_ => BigInt(0))
              } else {
                // Get effective priority fees, weighted by gas used
                val receipts = blockchainReader.getReceiptsByHash(header.hash).getOrElse(Seq.empty)
                val txPriorityFees = txList.zip(receipts).zipWithIndex.map { case ((stx, receipt), idx) =>
                  val gasUsed =
                    if (idx == 0) receipt.cumulativeGasUsed
                    else receipt.cumulativeGasUsed - receipts(idx - 1).cumulativeGasUsed
                  val effectiveGas = Transaction.effectiveGasPrice(stx.tx, header.baseFee)
                  val priorityFee = (effectiveGas - baseFee).max(0)
                  (priorityFee, gasUsed)
                }.sortBy(_._1)

                val totalGas = txPriorityFees.map(_._2).sum
                val percentileValues = req.rewardPercentiles.map { pct =>
                  val threshold = (BigDecimal(totalGas) * BigDecimal(pct) / 100).toBigInt
                  var cumGas = BigInt(0)
                  txPriorityFees.find { case (_, gas) =>
                    cumGas += gas
                    cumGas >= threshold
                  }.map(_._1).getOrElse(BigInt(0))
                }
                rewardBuf += percentileValues
              }
            }

          case None =>
            baseFees += BigInt(0)
            gasUsedRatios += 0.0
            rewards.foreach(_ += req.rewardPercentiles.map(_ => BigInt(0)))
        }
      }

      // Add the baseFee for the next block (one beyond the range)
      blockchainReader.getBlockHeaderByNumber(newestBlockNum).foreach { header =>
        val nextBaseFee = com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator.calcBaseFee(header, blockchainConfig)
        baseFees += nextBaseFee
      }

      Right(FeeHistoryResponse(
        oldestBlock = oldestBlock,
        baseFeePerGas = baseFees.toSeq,
        gasUsedRatio = gasUsedRatios.toSeq,
        reward = rewards.map(_.toSeq)
      ))
    }
}
