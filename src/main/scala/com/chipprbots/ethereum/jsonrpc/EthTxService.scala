package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto.KzgCellProofs
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.BlobSidecar
import com.chipprbots.ethereum.transactions.TransactionPicker
import com.chipprbots.ethereum.transactions.TxAdmission
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

object EthTxService:
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

  /** A local transaction that passed [[EthTxService.admitLocal]]: its sender, the sender's account at `head` and the
    * head it was checked against, which the pool's own rules read.
    */
  case class LocalAdmission(sender: Address, account: Account, head: BlockHeader)

class EthTxService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    val pendingTransactionsManager: ActorRef[PendingTransactionsManager.Command],
    val getTransactionFromPoolTimeout: FiniteDuration,
    transactionMappingStorage: TransactionMappingStorage,
    val scheduler: Scheduler,
    val rpcTxSubmission: RpcTxSubmission = RpcTxSubmission.Default
)(implicit val blockchainConfig: BlockchainConfig)
    extends TransactionPicker
    with ResolveBlock
    with Logger:
  import EthTxService.*
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
  def getTransactionByHash(req: GetTransactionByHashRequest): ServiceResponse[GetTransactionByHashResponse] =
    val eventualMaybeData = getTransactionDataByHash(req.txHash)
    eventualMaybeData.map(txResponse => Right(GetTransactionByHashResponse(txResponse.map(TransactionResponse(_)))))

  private def getTransactionDataByHash(txHash: ByteString): IO[Option[TransactionData]] =
    val maybeTxPendingResponse: IO[Option[TransactionData]] = getTransactionsFromPool.map {
      _.pendingTransactions.map(_.stx.tx).find(_.hash.value == txHash).map(TransactionData(_))
    }

    maybeTxPendingResponse.map { txPending =>
      txPending.orElse {
        for
          TransactionLocation(blockHash, txIndex) <- transactionMappingStorage.get(txHash)
          Block(header, body) <- blockchainReader.getBlockByHash(BlockHash(blockHash))
          stx <- body.transactionList.lift(txIndex)
        yield TransactionData(stx, Some(header), Some(txIndex))
      }
    }

  def getTransactionReceipt(req: GetTransactionReceiptRequest): ServiceResponse[GetTransactionReceiptResponse] =
    IO {
      val result: Option[TransactionReceiptResponse] = for
        TransactionLocation(blockHash, txIndex) <- transactionMappingStorage.get(req.txHash)
        Block(header, body) <- blockchainReader.getBlockByHash(BlockHash(blockHash))
        // Only surface receipts for CANONICAL transactions. Under engine-API, a block
        // may be stored (with receipts + tx-location mapping) immediately after newPayload
        // but not promoted to canonical until a subsequent forkchoiceUpdated — hive's
        // 'Transaction Re-Org' test expects eth_getTransactionReceipt to return nil
        // in that window. Require BOTH (a) the block lives at its number in the canonical
        // index, AND (b) its number is <= the client's best-block pointer (i.e. FCU has
        // advanced past it). (a) alone is true right after newPayload's storeBlock but
        // (b) flips only when the subsequent FCU updates saveBestKnownBlocks.
        _ <- blockchainReader.getBlockHeaderByNumber(header.number.value).filter(_.hash.value == blockHash)
        bestNum = blockchainReader.getBestBlockNumber if header.number.value <= bestNum
        stx <- body.transactionList.lift(txIndex)
        receipts <- blockchainReader.getReceiptsByHash(BlockHash(blockHash))
        receipt: Receipt <- receipts.lift(txIndex)
        // another possibility would be to throw an exception and fail hard, as if we cannot calculate sender for transaction
        // included in blockchain it means that something is terribly wrong
        sender <- SignedTransaction.getSender(stx)
      yield

        val gasUsed =
          if txIndex == 0 then receipt.cumulativeGasUsed
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
      for
        blockWithTx <- blockchainReader.getBlockByHash(BlockHash(blockHash))
        blockTxs = blockWithTx.body.transactionList if transactionIndex >= 0 && transactionIndex < blockTxs.size
        transaction <- blockTxs.lift(transactionIndex.toInt)
      yield TransactionData(transaction, Some(blockWithTx.header), Some(transactionIndex.toInt))
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
  private[jsonrpc] def minimumGasPrice(): BigInt =
    val minViable = blockchainReader.getBestBlock
      .flatMap(_.header.baseFee) match
      case Some(baseFee) => baseFee.max(blockchainConfig.baseFeeFloor) + blockchainConfig.minTip
      case None          => BigInt(0) // floor set by .max(1) below
    minViable.max(BigInt(1)) // always non-zero on every network

  // Synchronous gas price oracle — used by both eth_gasPrice RPC and PersonalService.sendTransaction.
  //
  // Algorithm matches go-ethereum and core-geth:
  //   - Sample up to GasPriceCheckBlocks recent blocks, at most GasPriceTxsPerBlock txs each.
  //   - Exclude transactions sent by the block's coinbase (miner self-txs drag the percentile down).
  //   - Take the 60th percentile of effective gas prices (not median — geth uses 60th).
  //   - Clamp to [minimumGasPrice(), GasPriceMaxCap].
  //   - Fall back to minimumGasPrice() when no transactions are available (never returns 0).
  private[jsonrpc] def suggestGasPrice(): BigInt =
    val floor = minimumGasPrice()
    val bestBlock = blockchainReader.getBestBlockNumber
    val bestBranch = blockchainReader.getBestBranch

    val gasPrices = ((bestBlock - GasPriceCheckBlocks + 1).max(BigInt(0)) to bestBlock)
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

    if gasPrices.nonEmpty then
      val sorted = gasPrices.sorted
      // 60th percentile — matches go-ethereum/core-geth default (configurable there, fixed here).
      // Biases slightly above the median to reduce stuck-transaction risk during fee spikes.
      val idx = math.min((sorted.length * 60) / 100, sorted.length - 1)
      sorted(idx).value.max(floor).min(GasPriceMaxCap)
    else floor // no transactions in window: return floor, never 0

  def getGetGasPrice(@unused req: GetGasPriceRequest): ServiceResponse[GetGasPriceResponse] =
    IO(Right(GetGasPriceResponse(suggestGasPrice())))

  def sendRawTransaction(req: SendRawTransactionRequest): ServiceResponse[SendRawTransactionResponse] =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*

    // The sidecar of a network-form blob tx is parsed with the tx: a wrapper whose parts are not byte-string lists is
    // as undecodable as a malformed tx.
    Try {
      val (stx, rawBytesOpt) = req.data.toArray.toSignedTransactionWithSidecar
      val sidecar =
        rawBytesOpt.map(raw => BlobSidecar.fromNetworkForm(raw).fold(e => throw new RuntimeException(e), identity))
      (stx, rawBytesOpt, sidecar)
    } match
      case Success((signedTransaction, rawBytesOpt, sidecar)) =>
        IO(sidecarForHead(signedTransaction, rawBytesOpt.map(ByteString(_)), sidecar)).flatMap {
          case Left(error) => IO.pure(Left(error))
          case Right((networkForm, headSidecar)) =>
            IO(admitLocal(signedTransaction, headSidecar)).flatMap {
              case Left(error) => IO.pure(Left(error))
              case Right(admission) =>
                submitToPool(pendingTransactionsManager, signedTransaction, admission, networkForm)
                  .map(_.map(SendRawTransactionResponse.apply))
            }
        }
      case Failure(e) =>
        // Keep the wire response exactly as before (-32600), but do NOT swallow the cause:
        // a raw-tx decode failure previously surfaced as a bare "not a valid Request object",
        // which is indistinguishable from a malformed JSON envelope and cost real debugging
        // time on the EIP-7594 blob wrapper. The decoder's message is the only signal here.
        log.debug(s"eth_sendRawTransaction could not decode the supplied raw transaction: ${e.getMessage}", e)
        IO.pure(Left(JsonRpcError.InvalidRequest))

  /** go-ethereum `SendRawTransaction` (internal/ethapi/api.go:1738-1756): once Osaka is active at the head, a blob
    * transaction sent with a version-0 sidecar (one blob proof per blob) has it converted to version 1 (cell proofs
    * computed from the blobs, `BlobTxSidecar.ToV1`) before submission; the pool then verifies the computed proofs
    * against the commitments. Returns the network form and sidecar to submit.
    */
  private def sidecarForHead(
      stx: SignedTransaction,
      networkForm: Option[ByteString],
      sidecar: Option[BlobSidecar]
  ): Either[JsonRpcError, (Option[ByteString], Option[BlobSidecar])] =
    sidecar match
      case Some(sc)
          if sc.version == BlobSidecar.Version0 &&
            blockchainReader.getBestBlockHeader.exists(h => blockchainConfig.isOsakaTimestamp(h.unixTimestamp)) =>
        try
          val cellProofs =
            sc.blobs.flatMap(b => KzgCellProofs.computeCellsAndKzgProofs(b.toArray)._2.map(ByteString(_)))
          val converted = BlobSidecar(BlobSidecar.Version1, sc.blobs, sc.commitments, cellProofs)
          val wrapper =
            BlobTxSidecar(BlobTxSidecar.Version1, sc.blobs.toList, sc.commitments.toList, cellProofs.toList)
          Right((Some(wrapper.networkEncoding(stx)), Some(converted)))
        catch
          case e @ (_: Exception | _: LinkageError) =>
            Left(
              JsonRpcError.LogicError(
                s"blob sidecar conversion failed: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
              )
            )
      case _ => Right((networkForm, sidecar))

  /** Admission of a locally submitted transaction, in go-ethereum's `SubmitTransaction` order (api.go:1640-1651):
    *
    *   1. the RPC layer ([[RpcTxSubmission.submissionRejection]]): the transaction-fee cap, then replay protection
    *      unless unprotected transactions are allowed;
    *   1. the pool's rules against the chain head, the rules a peer's transaction meets and go-ethereum's
    *      `txpool.ValidateTransaction` applies (type active at the head, chain ID, intrinsic gas and floor, initcode
    *      size, gas caps, tip, blob sidecar), then the sender's nonce and balance in the head's state
    *      ([[TxAdmission.validateLocal]]).
    *
    * The pool's rules against what it already holds follow in [[submitToPool]]. A rejected transaction never reaches
    * the pool, so it is never announced.
    *
    * Every rejection is -32000 with go-ethereum's message, except initcode over EIP-3860's limit, which keeps the
    * -32602 INITCODE_SIZE_EXCEEDED that hive's withdrawals "Max Initcode Size" test was matched against (07f0bc608).
    *
    * Shared with eth_sendTransaction / personal_sendTransaction (PersonalService.submitLocal, #1629 S2), so every local
    * submission meets the same gate.
    */
  private[jsonrpc] def admitLocal(
      stx: SignedTransaction,
      sidecar: Option[BlobSidecar]
  ): Either[JsonRpcError, LocalAdmission] =
    rpcTxSubmission.submissionRejection(stx) match
      case Some(message) =>
        log.debug(s"eth_sendRawTransaction rejected ${stx.hash.toHex}: $message")
        Left(JsonRpcError.LogicError(message))
      case None => admitAtHead(stx, sidecar)

  private def admitAtHead(stx: SignedTransaction, sidecar: Option[BlobSidecar]): Either[JsonRpcError, LocalAdmission] =
    blockchainReader.getBestBlockHeader match
      case None => Left(JsonRpcError.LogicError("cannot validate transaction: no chain head"))
      case Some(head) =>
        try
          TxAdmission
            .validateLocal(
              stx,
              sidecar,
              head,
              address => blockchainReader.getAccountAtStateRoot(head.stateRoot.value, address)
            )
            .left
            .map {
              case TxAdmission.Rejection.InitCodeTooLarge(size, max) =>
                JsonRpcError.InvalidParams(s"INITCODE_SIZE_EXCEEDED: initcode size $size exceeds max $max")
              case rejection =>
                log.debug(s"eth_sendRawTransaction rejected ${stx.hash.toHex}: ${rejection.message}")
                JsonRpcError.LogicError(rejection.message)
            }
            .map { case (sender, account) => LocalAdmission(sender, account, head) }
        catch
          case e: MissingNodeException =>
            Left(
              JsonRpcError.LogicError(
                s"cannot validate transaction: state at head ${head.number.value} is not available (${e.getMessage})"
              )
            )

  /** Hand an admitted local transaction to `pool` and wait for its decision: the pool applies its own rules against
    * what it holds (already known, overdraft, delegation limit, replacement price bump; [[TxAdmission.poolRejection]])
    * and pools it, under one message, as go-ethereum holds its pool lock across both. A refusal is -32000 with
    * go-ethereum's message. Returns the transaction hash.
    */
  private[jsonrpc] def submitToPool(
      pool: ActorRef[PendingTransactionsManager.Command],
      stx: SignedTransaction,
      admission: LocalAdmission,
      blobTxRawBytes: Option[ByteString]
  ): IO[Either[JsonRpcError, ByteString]] =
    given timeout: Timeout = Timeout(getTransactionFromPoolTimeout)
    given sc: Scheduler = scheduler
    pool
      .askForTyped[Either[TxAdmission.Rejection, Unit]](
        PendingTransactionsManager.SubmitLocalTransaction(
          stx,
          admission.sender,
          admission.account,
          admission.head,
          blobTxRawBytes,
          _
        )
      )
      .map {
        case Right(()) => Right(stx.hash.value)
        case Left(rejection) =>
          log.debug(s"Pool refused local tx ${stx.hash.toHex}: ${rejection.message}")
          Left(JsonRpcError.LogicError(rejection.message))
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
        if transactionIndex >= 0 && transactionIndex < blockTxs.size then
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
