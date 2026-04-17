package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.TransactionPicker
import com.chipprbots.ethereum.utils.TxPoolConfig

/** JSON-RPC txpool_* namespace.
  *
  * Besu reference: TxPoolBesuTransactions, TxPoolBesuStatistics, TxPoolBesuPendingTransactions,
  *   PendingTransactionsStatisticsResult, TransactionPendingResult, PendingTransactionFilter
  *
  * Divergences from Besu: none. All three methods are fully aligned.
  *
  * localCount/remoteCount: tracked via PendingTransaction.receivedFromLocalSource, which is set
  *   to true when a transaction is submitted via local RPC (eth_sendRawTransaction,
  *   personal_sendTransaction) and false for transactions received from P2P peers.
  *
  * txpool_besuPendingTransactions: implements both limit and the PendingTransactionsParams filter
  *   object (from/to address, gas/gasPrice/value/nonce with eq/gt/lt/action predicates).
  */
object TxPoolService {
  case class TxPoolBesuTransactionsRequest()
  case class TxPoolBesuTransactionsResponse(pendingTransactions: Seq[TransactionResponse])

  case class TxPoolBesuStatisticsRequest()
  case class TxPoolBesuStatisticsResponse(maxSize: Long, localCount: Long, remoteCount: Long)

  // Filter predicate — matches Besu's Predicate enum (EQ, GT, LT, ACTION)
  sealed trait TxPoolFilterPredicate
  case object Eq     extends TxPoolFilterPredicate
  case object Gt     extends TxPoolFilterPredicate
  case object Lt     extends TxPoolFilterPredicate
  case object Action extends TxPoolFilterPredicate // "to" field only: filters contract creation txs

  case class TxPoolFilter(field: String, predicate: TxPoolFilterPredicate, value: String)

  case class TxPoolBesuPendingTransactionsParams(filters: Seq[TxPoolFilter])

  case class TxPoolBesuPendingTransactionsRequest(
      limit: Option[Int],
      params: Option[TxPoolBesuPendingTransactionsParams] = None
  )
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
    * localCount: transactions submitted via local RPC (AddOrOverrideTransaction path).
    * remoteCount: transactions received from P2P peers (AddTransactions path).
    */
  def besuStatistics(req: TxPoolBesuStatisticsRequest): ServiceResponse[TxPoolBesuStatisticsResponse] =
    getTransactionsFromPool.map { resp =>
      val local  = resp.pendingTransactions.count(_.receivedFromLocalSource).toLong
      val remote = resp.pendingTransactions.size.toLong - local
      Right(TxPoolBesuStatisticsResponse(
        maxSize = txPoolConfig.txPoolSize.toLong,
        localCount = local,
        remoteCount = remote
      ))
    }

  /** txpool_besuPendingTransactions — returns pending transactions with optional limit and filters.
    *
    * Besu: TxPoolBesuPendingTransactions accepts limit + PendingTransactionsParams filter object.
    * Supported filter fields: from (eq), to (eq, action), gas (eq/gt/lt), gasPrice (eq/gt/lt),
    *   value (eq/gt/lt), nonce (eq/gt/lt).
    * Numeric fields (gas, gasPrice, value) expect hex string values.
    * Nonce expects a decimal string.
    * action predicate on "to" selects contract creation transactions (receivingAddress is empty).
    */
  def besuPendingTransactions(
      req: TxPoolBesuPendingTransactionsRequest
  ): ServiceResponse[TxPoolBesuPendingTransactionsResponse] =
    getTransactionsFromPool.map { resp =>
      val filters = req.params.map(_.filters).getOrElse(Seq.empty)
      val filtered =
        if (filters.isEmpty) resp.pendingTransactions
        else resp.pendingTransactions.filter(pt => applyFilters(pt, filters))
      val txs = req.limit match {
        case Some(n) => filtered.take(n)
        case None    => filtered
      }
      Right(TxPoolBesuPendingTransactionsResponse(txs.map(pt => TransactionResponse(pt.stx.tx))))
    }

  /** Apply all filters to a pending transaction. Returns true if the tx passes all filters.
    *
    * Mirrors Besu's PendingTransactionFilter.applyFilters().
    */
  private def applyFilters(pt: PendingTransaction, filters: Seq[TxPoolFilter]): Boolean =
    filters.forall { f =>
      val tx   = pt.stx.tx.tx
      val from = pt.stx.senderAddress
      f.field match {
        case "from" =>
          f.predicate == Eq && Address(f.value) == from
        case "to" =>
          f.predicate match {
            case Action => tx.isContractInit
            case Eq     => tx.receivingAddress.contains(Address(f.value))
            case _      => false
          }
        case "gas" =>
          compareNumerically(tx.gasLimit, f.predicate, BigInt(f.value.stripPrefix("0x"), 16))
        case "gasPrice" =>
          compareNumerically(tx.gasPrice, f.predicate, BigInt(f.value.stripPrefix("0x"), 16))
        case "value" =>
          compareNumerically(tx.value, f.predicate, BigInt(f.value.stripPrefix("0x"), 16))
        case "nonce" =>
          val n =
            if (f.value.startsWith("0x")) BigInt(f.value.stripPrefix("0x"), 16)
            else BigInt(f.value)
          compareNumerically(tx.nonce, f.predicate, n)
        case _ => true
      }
    }

  private def compareNumerically(a: BigInt, pred: TxPoolFilterPredicate, b: BigInt): Boolean =
    pred match {
      case Eq => a == b
      case Gt => a > b
      case Lt => a < b
      case _  => false
    }
}
