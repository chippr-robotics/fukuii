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
        // Only surface receipts for CANONICAL transactions. Under engine-API, a block
        // may be stored (with receipts + tx-location mapping) immediately after newPayload
        // but not promoted to canonical until a subsequent forkchoiceUpdated — hive's
        // 'Transaction Re-Org' test expects eth_getTransactionReceipt to return nil
        // in that window. Require BOTH (a) the block lives at its number in the canonical
        // index, AND (b) its number is <= the client's best-block pointer (i.e. FCU has
        // advanced past it). (a) alone is true right after newPayload's storeBlock but
        // (b) flips only when the subsequent FCU updates saveBestKnownBlocks.
        _ <- blockchainReader.getBlockHeaderByNumber(header.number).filter(_.hash == blockHash)
        bestNum = blockchainReader.getBestBlockNumber() if header.number <= bestNum
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

        // Compute cumulative log index from prior receipts in the block
        val baseLogIndex = receipts.take(txIndex).map(_.logs.size).sum

        TransactionReceiptResponse(
          receipt = receipt,
          stx = stx,
          signedTransactionSender = sender,
          transactionIndex = txIndex,
          blockHeader = header,
          gasUsedByTransaction = gasUsed,
          baseLogIndex = baseLogIndex
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
        // Median, not mean. A single Type-2 / blob tx with `maxFeePerGas = 1 gwei` reads out
        // through `tx.gasPrice` as ~10^9, which drags the arithmetic mean into the tens of
        // millions and fails hive's graphql test 07 (accepts 0x10 or 0x1 — both low). geth
        // and besu both use the median of the recent-blocks sample for the same reason.
        val sorted   = gasPrice.sorted
        val midIndex = sorted.length / 2
        val median   = if (sorted.length % 2 == 0 && sorted.length >= 2)
          (sorted(midIndex - 1) + sorted(midIndex)) / 2
        else sorted(midIndex)
        Right(GetGasPriceResponse(median))
      } else {
        Right(GetGasPriceResponse(0))
      }
    }
  }

  def sendRawTransaction(req: SendRawTransactionRequest): ServiceResponse[SendRawTransactionResponse] = {
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.SignedTransactionDec

    Try(req.data.toArray.toSignedTransactionWithSidecar) match {
      case Success((signedTransaction, rawBytesOpt)) =>
        if (SignedTransaction.getSender(signedTransaction).isEmpty) {
          IO.pure(Left(JsonRpcError.InvalidRequest))
        } else {
          // EIP-3860 (Shanghai+): reject contract-creation txs whose initcode exceeds the
          // per-EVM-config maximum. Derived from the CURRENT chain tip's fork state. Must
          // use the timestamp-aware forBlock variant — Shanghai activates by timestamp on
          // post-merge chains, not block number.
          val tip = blockchainReader.getBestBlock().map(_.header)
          val bestNum = tip.map(_.number).getOrElse(blockchainReader.getBestBlockNumber())
          val ts = tip.map(_.unixTimestamp).getOrElse(0L)
          val evmConfig = com.chipprbots.ethereum.vm.EvmConfig.forBlock(bestNum, ts, blockchainConfig)
          val tx = signedTransaction.tx
          val initCodeTooLarge =
            tx.isContractInit &&
              evmConfig.eip3860Enabled &&
              evmConfig.maxInitCodeSize.exists(max => tx.payload.size > max)
          if (initCodeTooLarge) {
            IO.pure(
              Left(
                JsonRpcError.InvalidParams(
                  s"INITCODE_SIZE_EXCEEDED: initcode size ${tx.payload.size} exceeds max " +
                    s"${evmConfig.maxInitCodeSize.getOrElse(BigInt(0))}"
                )
              )
            )
          } else {
            pendingTransactionsManager ! PendingTransactionsManager.AddOrOverrideTransaction(
              signedTransaction,
              rawBytesOpt.map(org.apache.pekko.util.ByteString(_))
            )
            IO.pure(Right(SendRawTransactionResponse(signedTransaction.hash)))
          }
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
}
