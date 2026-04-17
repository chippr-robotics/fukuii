package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.transactions.TransactionPicker
import com.chipprbots.ethereum.utils.TxPoolConfig

/** JSON-RPC txpool_* namespace.
  *
  * Besu reference: TxPoolBesuTransactions, TxPoolBesuStatistics, TxPoolBesuPendingTransactions
  *
  * Divergences from Besu:
  *   - localCount is always 0: Fukuii's PendingTransaction does not track isReceivedFromLocalSource.
  *     All pending txs are reported as remoteCount.
  *   - txpool_besuPendingTransactions accepts an optional limit param only; Besu's PendingTransactionsParams
  *     filter object (from/to address, gas price, nonce ranges) is not implemented.
  */
object TxPoolService {
  case class TxPoolBesuTransactionsRequest()
  case class TxPoolBesuTransactionsResponse(pendingTransactions: Seq[TransactionResponse])

  case class TxPoolBesuStatisticsRequest()
  case class TxPoolBesuStatisticsResponse(maxSize: Long, localCount: Long, remoteCount: Long)

  case class TxPoolBesuPendingTransactionsRequest(limit: Option[Int])
  case class TxPoolBesuPendingTransactionsResponse(pendingTransactions: Seq[TransactionResponse])
}

class TxPoolService(
    override val pendingTransactionsManager: ActorRef,
    override val getTransactionFromPoolTimeout: FiniteDuration,
    txPoolConfig: TxPoolConfig
) extends TransactionPicker {
  import TxPoolService._

  /** txpool_besuTransactions — returns all pending transactions.
    *
    * Besu: TxPoolBesuTransactions wraps getPendingTransactions() in PendingTransactionsResult.
    */
  def besuTransactions(req: TxPoolBesuTransactionsRequest): ServiceResponse[TxPoolBesuTransactionsResponse] =
    getTransactionsFromPool.map { resp =>
      Right(TxPoolBesuTransactionsResponse(
        resp.pendingTransactions.map(pt => TransactionResponse(pt.stx.tx))
      ))
    }

  /** txpool_besuStatistics — returns pool size statistics.
    *
    * Besu: TxPoolBesuStatistics counts PendingTransaction.isReceivedFromLocalSource().
    * Fukuii divergence: localCount is always 0; all txs reported as remoteCount.
    */
  def besuStatistics(req: TxPoolBesuStatisticsRequest): ServiceResponse[TxPoolBesuStatisticsResponse] =
    getTransactionsFromPool.map { resp =>
      val total = resp.pendingTransactions.size.toLong
      Right(TxPoolBesuStatisticsResponse(
        maxSize = txPoolConfig.txPoolSize.toLong,
        localCount = 0L,
        remoteCount = total
      ))
    }

  /** txpool_besuPendingTransactions — returns pending transactions with optional limit.
    *
    * Besu: TxPoolBesuPendingTransactions accepts limit + PendingTransactionsParams filter object.
    * Fukuii divergence: filter object not implemented; only limit is honoured.
    */
  def besuPendingTransactions(
      req: TxPoolBesuPendingTransactionsRequest
  ): ServiceResponse[TxPoolBesuPendingTransactionsResponse] =
    getTransactionsFromPool.map { resp =>
      val txs = req.limit match {
        case Some(n) => resp.pendingTransactions.take(n)
        case None    => resp.pendingTransactions
      }
      Right(TxPoolBesuPendingTransactionsResponse(txs.map(pt => TransactionResponse(pt.stx.tx))))
    }
}
