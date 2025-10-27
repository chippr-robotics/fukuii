package com.chipprbots.ethereum.transactions

import akka.actor.ActorRef
import akka.util.Timeout

import cats.implicits._
import cats.effect.IO
import fs2.Stream

import scala.collection.immutable.NumericRange
import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.TaskActorOps
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.transactions.TransactionHistoryService.MinedTxChecker
import com.chipprbots.ethereum.transactions.TransactionHistoryService.PendingTxChecker
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

class TransactionHistoryService(
    blockchainReader: BlockchainReader,
    pendingTransactionsManager: ActorRef,
    getTransactionFromPoolTimeout: FiniteDuration
) extends Logger {
  def getAccountTransactions(
      account: Address,
      fromBlocks: NumericRange[BigInt]
  )(implicit blockchainConfig: BlockchainConfig): IO[List[ExtendedTransactionData]] = {
    val getLastCheckpoint = IO(blockchainReader.getLatestCheckpointBlockNumber()).memoize
    val txnsFromBlocks = Stream
      .emits(fromBlocks.reverse.toSeq)
      .parEvalMap(10)(blockNr =>
        IO(blockchainReader.getBlockByNumber(blockchainReader.getBestBranch(), blockNr))
      )
      .collect { case Some(block) => block }
      .flatMap { block =>
        val getBlockReceipts = IO {
          blockchainReader.getReceiptsByHash(block.hash).map(_.toVector).getOrElse(Vector.empty)
        }.memoize

        Stream
          .emits(block.body.transactionList.reverse.toSeq)
          .collect(Function.unlift(MinedTxChecker.checkTx(_, account)))
          .evalMap { case (tx, mkExtendedData) =>
            (getBlockReceipts, getLastCheckpoint).mapN(
              MinedTxChecker.getMinedTxData(tx, block, _, _).map(mkExtendedData(_))
            )
          }
          .collect { case Some(data) =>
            data
          }
      }
      .compile.toList

    val txnsFromMempool = getTransactionsFromPool.map { pendingTransactions =>
      pendingTransactions
        .collect(Function.unlift(PendingTxChecker.checkTx(_, account)))
    }

    (txnsFromBlocks, txnsFromMempool).parMapN(_ ++ _)
  }

  private val getTransactionsFromPool: IO[List[PendingTransaction]] = {
    implicit val timeout: Timeout = getTransactionFromPoolTimeout
    pendingTransactionsManager
      .askFor[PendingTransactionsManager.PendingTransactionsResponse](PendingTransactionsManager.GetPendingTransactions)
      .map(_.pendingTransactions.toList)
      .onErrorRecoverWith { case ex: Throwable =>
        log.error("Failed to get pending transactions, passing empty transactions list", ex)
        IO.pure(List.empty)
      }
  }
}
object TransactionHistoryService {
  case class MinedTransactionData(
      header: BlockHeader,
      transactionIndex: Int,
      gasUsed: BigInt,
      isCheckpointed: Boolean
  ) {
    lazy val timestamp: Long = header.unixTimestamp
  }
  case class ExtendedTransactionData(
      stx: SignedTransaction,
      isOutgoing: Boolean,
      minedTransactionData: Option[MinedTransactionData]
  ) {
    val isPending: Boolean = minedTransactionData.isEmpty
  }

  object PendingTxChecker {
    def isSender(tx: PendingTransaction, maybeSender: Address): Boolean = tx.stx.senderAddress == maybeSender
    def isReceiver(tx: PendingTransaction, maybeReceiver: Address): Boolean =
      tx.stx.tx.tx.receivingAddress.contains(maybeReceiver)
    def asSigned(tx: PendingTransaction): SignedTransaction = tx.stx.tx

    def checkTx(tx: PendingTransaction, address: Address): Option[ExtendedTransactionData] =
      if (isSender(tx, address)) {
        Some(ExtendedTransactionData(asSigned(tx), isOutgoing = true, None))
      } else if (isReceiver(tx, address)) {
        Some(ExtendedTransactionData(asSigned(tx), isOutgoing = false, None))
      } else {
        None
      }
  }

  object MinedTxChecker {
    def isSender(tx: SignedTransaction, maybeSender: Address)(implicit blockchainConfig: BlockchainConfig): Boolean =
      tx.safeSenderIsEqualTo(maybeSender)
    def isReceiver(tx: SignedTransaction, maybeReceiver: Address): Boolean =
      tx.tx.receivingAddress.contains(maybeReceiver)

    def checkTx(
        tx: SignedTransaction,
        address: Address
    )(implicit
        blockchainConfig: BlockchainConfig
    ): Option[(SignedTransaction, MinedTransactionData => ExtendedTransactionData)] =
      if (isSender(tx, address)) {
        Some((tx, data => ExtendedTransactionData(tx, isOutgoing = true, Some(data))))
      } else if (isReceiver(tx, address)) {
        Some((tx, data => ExtendedTransactionData(tx, isOutgoing = false, Some(data))))
      } else {
        None
      }

    def getMinedTxData(
        tx: SignedTransaction,
        block: Block,
        blockReceipts: Vector[Receipt],
        lastCheckpointBlockNumber: BigInt
    ): Option[MinedTransactionData] = {
      val maybeIndex = block.body.transactionList.zipWithIndex.collectFirst {
        case (someTx, index) if someTx.hash == tx.hash => index
      }

      val maybeGasUsed = for {
        index <- maybeIndex
        txReceipt <- blockReceipts.lift(index)
      } yield {
        val previousCumulativeGas: BigInt =
          (if (index > 0) blockReceipts.lift(index - 1) else None).map(_.cumulativeGasUsed).getOrElse(0)

        txReceipt.cumulativeGasUsed - previousCumulativeGas
      }

      val isCheckpointed = lastCheckpointBlockNumber >= block.number

      (Some(block.header), maybeIndex, maybeGasUsed, Some(isCheckpointed)).mapN(MinedTransactionData)
    }
  }
}
