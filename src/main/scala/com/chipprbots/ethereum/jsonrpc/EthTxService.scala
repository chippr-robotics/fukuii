package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.annotation.unused
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
)(implicit val blockchainConfig: BlockchainConfig)
    extends TransactionPicker
    with ResolveBlock {
  import EthTxService._
  // blockchainConfig is taken as an implicit constructor parameter so multi-instance
  // runtime callers (NodeBuilder) automatically supply the per-instance config in scope
  // (see NodeBuilder line 72), instead of every service silently shadowing it with the
  // global Config.blockchains.blockchainConfig singleton — which yields the FIRST chain's
  // config and breaks EIP-155 sender recovery for non-default chains (chainId mismatch
  // → InvalidRequest -32600 on legacy txs).

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

  private val GasPriceMaxCap: BigInt = BigInt(500) * BigInt(10).pow(9) // 500 gwei — matches go-ethereum
  private val GasPriceCheckBlocks = 20 // matches go-ethereum default
  private val GasPriceTxsPerBlock = 3 // matches go-ethereum default

  // Network-aware minimum viable gas price. Derives from current network state — works on all
  // Fukuii-supported chains (ETC mainnet/Mordor and ETH/Sepolia) without any per-chain flag.
  //
  //   Pre-EIP-1559 (baseFee absent): 1 wei — smallest non-zero price on any legacy chain.
  //   Post-EIP-1559: max(currentBaseFee, baseFeeFloor) + minTip, minimum 1 wei.
  //     ETC/Mordor post-Olympia: max(≥1 gwei, 1 gwei) + 1 gwei = ≥ 2 gwei
  //     ETH post-London:         baseFee (dynamic) + 1 gwei (minTip default)
  private[jsonrpc] def minimumGasPrice(): BigInt = {
    val minViable = blockchainReader
      .getBestBlock()
      .flatMap(_.header.baseFee) match {
      case Some(baseFee) => baseFee.max(blockchainConfig.baseFeeFloor) + blockchainConfig.minTip
      case None          => BigInt(0) // floor set by .max(1) below
    }
    minViable.max(BigInt(1)) // always non-zero on every network
  }

  // Synchronous gas price oracle — used by both eth_gasPrice RPC and PersonalService.sendTransaction.
  //
  // Algorithm matches go-ethereum and core-geth:
  //   - Sample up to GasPriceCheckBlocks recent blocks, at most GasPriceTxsPerBlock txs each.
  //   - Exclude transactions sent by the block's coinbase (miner self-txs drag the percentile down).
  //   - Take the 60th percentile of effective gas prices (not median — geth uses 60th).
  //   - Clamp to [minimumGasPrice(), GasPriceMaxCap].
  //   - Fall back to minimumGasPrice() when no transactions are available (never returns 0).
  private[jsonrpc] def suggestGasPrice(): BigInt = {
    val floor = minimumGasPrice()
    val bestBlock = blockchainReader.getBestBlockNumber()
    val bestBranch = blockchainReader.getBestBranch()

    val gasPrices = ((bestBlock - GasPriceCheckBlocks).max(BigInt(0)) to bestBlock)
      .flatMap(nb => blockchainReader.getBlockByNumber(bestBranch, nb))
      .flatMap { block =>
        val coinbase = block.header.beneficiary
        // Exclude miner self-transactions — matches go-ethereum, core-geth, erigon, Besu, Nethermind.
        // A PoW miner can self-include 0-tip transactions; excluding them prevents the oracle
        // from suggesting a price below the actual market clearing rate.
        block.body.transactionList
          .filterNot(stx => SignedTransaction.getSender(stx).exists(_.bytes == coinbase))
          .take(GasPriceTxsPerBlock)
          .map(_.tx.gasPrice)
      }

    if (gasPrices.nonEmpty) {
      val sorted = gasPrices.sorted
      // 60th percentile — matches go-ethereum/core-geth default (configurable there, fixed here).
      // Biases slightly above the median to reduce stuck-transaction risk during fee spikes.
      val idx = math.min((sorted.length * 60) / 100, sorted.length - 1)
      sorted(idx).max(floor).min(GasPriceMaxCap)
    } else {
      floor // no transactions in window: return floor, never 0
    }
  }

  def getGetGasPrice(@unused req: GetGasPriceRequest): ServiceResponse[GetGasPriceResponse] =
    IO(Right(GetGasPriceResponse(suggestGasPrice())))

  def sendRawTransaction(req: SendRawTransactionRequest): ServiceResponse[SendRawTransactionResponse] = {
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.SignedTransactionDec

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
  def ethPendingTransactions(
      @unused req: EthPendingTransactionsRequest
  ): ServiceResponse[EthPendingTransactionsResponse] =
    getTransactionsFromPool.map { resp =>
      Right(EthPendingTransactionsResponse(resp.pendingTransactions))
    }
}
