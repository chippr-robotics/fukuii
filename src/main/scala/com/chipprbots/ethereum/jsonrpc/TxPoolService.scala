package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.TransactionPicker
import com.chipprbots.ethereum.utils.TxPoolConfig

/** JSON-RPC txpool_* namespace.
  *
  * Besu methods: TxPoolBesuTransactions, TxPoolBesuStatistics, TxPoolBesuPendingTransactions,
  * PendingTransactionsStatisticsResult, TransactionPendingResult, PendingTransactionFilter
  *
  * Geth-compatible methods (core-geth TxPoolAPI): txpool_content, txpool_contentFrom, txpool_inspect, txpool_status —
  * for interoperability with ETC ecosystem tooling.
  *
  * localCount/remoteCount: tracked via PendingTransaction.receivedFromLocalSource, which is set to true when a
  * transaction is submitted via local RPC (eth_sendRawTransaction, personal_sendTransaction) and false for transactions
  * received from P2P peers.
  *
  * txpool_besuPendingTransactions: implements both limit and the PendingTransactionsParams filter object (from/to
  * address, gas/gasPrice/value/nonce with eq/gt/lt/action predicates).
  *
  * Note: Fukuii has no queued pool (transactions with nonce gaps are dropped). All geth-compat methods return queued:
  * empty/0, matching Besu's behaviour on a pending-only pool.
  */
object TxPoolService {
  // ── Besu methods ──────────────────────────────────────────────────────────

  case class TxPoolBesuTransactionsRequest()
  case class TxPoolBesuTransactionsResponse(pendingTransactions: Seq[TransactionResponse])

  case class TxPoolBesuStatisticsRequest()
  case class TxPoolBesuStatisticsResponse(maxSize: Long, localCount: Long, remoteCount: Long)

  // Filter predicate — matches Besu's Predicate enum (EQ, GT, LT, ACTION)
  sealed trait TxPoolFilterPredicate
  case object Eq extends TxPoolFilterPredicate
  case object Gt extends TxPoolFilterPredicate
  case object Lt extends TxPoolFilterPredicate
  case object Action extends TxPoolFilterPredicate // "to" field only: filters contract creation txs

  case class TxPoolFilter(field: String, predicate: TxPoolFilterPredicate, value: String)

  case class TxPoolBesuPendingTransactionsParams(filters: Seq[TxPoolFilter])

  case class TxPoolBesuPendingTransactionsRequest(
      limit: Option[Int],
      params: Option[TxPoolBesuPendingTransactionsParams] = None
  )
  case class TxPoolBesuPendingTransactionsResponse(pendingTransactions: Seq[TransactionResponse])

  // ── Geth-compatible methods ────────────────────────────────────────────────
  // core-geth reference: internal/ethapi/api.go TxPoolAPI

  /** txpool_content: pending + queued txs grouped by sender → nonce → TransactionObject. queued is always empty (Fukuii
    * has no queued pool).
    */
  case class TxPoolContentRequest()
  case class TxPoolContentResponse(
      pending: Map[String, Map[String, TransactionResponse]],
      queued: Map[String, Map[String, TransactionResponse]]
  )

  /** txpool_contentFrom: same shape as content but scoped to one sender address. */
  case class TxPoolContentFromRequest(address: Address)
  case class TxPoolContentFromResponse(
      pending: Map[String, TransactionResponse],
      queued: Map[String, TransactionResponse]
  )

  /** txpool_status: hex-encoded pending and queued counts. core-geth: map[string]hexutil.Uint{"pending": N, "queued":
    * 0}
    */
  case class TxPoolStatusRequest()
  case class TxPoolStatusResponse(pending: Long, queued: Long)

  /** txpool_inspect: human-readable summary grouped by sender → nonce → string. Format: "0xTo: value wei + gasLimit gas
    * × gasPrice wei" (or "contract creation: ...") core-geth: internal/ethapi/api.go TxPoolAPI.Inspect()
    */
  case class TxPoolInspectRequest()
  case class TxPoolInspectResponse(
      pending: Map[String, Map[String, String]],
      queued: Map[String, Map[String, String]]
  )
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
      Right(
        TxPoolBesuTransactionsResponse(
          resp.pendingTransactions.map(pt => TransactionResponse(pt.stx.tx))
        )
      )
    }

  /** txpool_besuStatistics — returns pool size statistics.
    *
    * Besu: TxPoolBesuStatistics counts PendingTransaction.isReceivedFromLocalSource(). localCount: transactions
    * submitted via local RPC (AddOrOverrideTransaction path). remoteCount: transactions received from P2P peers
    * (AddTransactions path).
    */
  def besuStatistics(req: TxPoolBesuStatisticsRequest): ServiceResponse[TxPoolBesuStatisticsResponse] =
    getTransactionsFromPool.map { resp =>
      val local = resp.pendingTransactions.count(_.receivedFromLocalSource).toLong
      val remote = resp.pendingTransactions.size.toLong - local
      Right(
        TxPoolBesuStatisticsResponse(
          maxSize = txPoolConfig.txPoolSize.toLong,
          localCount = local,
          remoteCount = remote
        )
      )
    }

  /** txpool_besuPendingTransactions — returns pending transactions with optional limit and filters.
    *
    * Besu: TxPoolBesuPendingTransactions accepts limit + PendingTransactionsParams filter object. Supported filter
    * fields: from (eq), to (eq, action), gas (eq/gt/lt), gasPrice (eq/gt/lt), value (eq/gt/lt), nonce (eq/gt/lt).
    * Numeric fields (gas, gasPrice, value) expect hex string values. Nonce expects a decimal string. action predicate
    * on "to" selects contract creation transactions (receivingAddress is empty).
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
      val tx = pt.stx.tx.tx
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

  // ── Geth-compatible methods ────────────────────────────────────────────────

  /** txpool_content — pending txs grouped by sender address → nonce → TransactionObject. core-geth: TxPoolAPI.Content()
    * — pending[account][nonce] = RPCTransaction
    */
  def content(req: TxPoolContentRequest): ServiceResponse[TxPoolContentResponse] =
    getTransactionsFromPool.map { resp =>
      val pending = resp.pendingTransactions
        .groupBy(pt => pt.stx.senderAddress.toString)
        .map { case (sender, pts) =>
          sender -> pts.map(pt => pt.stx.tx.tx.nonce.toString -> TransactionResponse(pt.stx.tx)).toMap
        }
      Right(TxPoolContentResponse(pending, Map.empty))
    }

  /** txpool_contentFrom — pending txs for a single sender, nonce → TransactionObject. core-geth:
    * TxPoolAPI.ContentFrom(addr) — pending[nonce] = RPCTransaction
    */
  def contentFrom(req: TxPoolContentFromRequest): ServiceResponse[TxPoolContentFromResponse] =
    getTransactionsFromPool.map { resp =>
      val pending = resp.pendingTransactions
        .filter(pt => pt.stx.senderAddress == req.address)
        .map(pt => pt.stx.tx.tx.nonce.toString -> TransactionResponse(pt.stx.tx))
        .toMap
      Right(TxPoolContentFromResponse(pending, Map.empty))
    }

  /** txpool_status — hex-encoded count of pending and queued transactions. core-geth: TxPoolAPI.Status() — {"pending":
    * hexutil.Uint(N), "queued": 0}
    */
  def status(req: TxPoolStatusRequest): ServiceResponse[TxPoolStatusResponse] =
    getTransactionsFromPool.map { resp =>
      Right(TxPoolStatusResponse(pending = resp.pendingTransactions.size.toLong, queued = 0L))
    }

  /** txpool_inspect — human-readable summary grouped by sender → nonce → string. Format per tx: "0xTo: value wei +
    * gasLimit gas × gasPrice wei" or: "contract creation: value wei + gasLimit gas × gasPrice wei" core-geth:
    * TxPoolAPI.Inspect()
    */
  def inspect(req: TxPoolInspectRequest): ServiceResponse[TxPoolInspectResponse] =
    getTransactionsFromPool.map { resp =>
      val pending = resp.pendingTransactions
        .groupBy(pt => pt.stx.senderAddress.toString)
        .map { case (sender, pts) =>
          sender -> pts.map { pt =>
            val tx = pt.stx.tx.tx
            val summary = tx.receivingAddress match {
              case Some(to) =>
                s"${to}: ${tx.value} wei + ${tx.gasLimit} gas × ${tx.gasPrice} wei"
              case None =>
                s"contract creation: ${tx.value} wei + ${tx.gasLimit} gas × ${tx.gasPrice} wei"
            }
            tx.nonce.toString -> summary
          }.toMap
        }
      Right(TxPoolInspectResponse(pending, Map.empty))
    }
}
