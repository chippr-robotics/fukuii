package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.Logger

object TxPoolService {

  case class TxPoolStatusRequest()
  case class TxPoolStatusResponse(pending: Int, queued: Int)

  case class TxPoolContentRequest()
  case class TxPoolContentResponse(
      pending: Map[ByteString, Map[BigInt, TxPoolTxInfo]],
      queued: Map[ByteString, Map[BigInt, TxPoolTxInfo]]
  )

  case class TxPoolInspectRequest()
  case class TxPoolInspectResponse(
      pending: Map[ByteString, Map[BigInt, String]],
      queued: Map[ByteString, Map[BigInt, String]]
  )

  case class TxPoolContentFromRequest(address: Address)
  case class TxPoolContentFromResponse(
      pending: Map[BigInt, TxPoolTxInfo],
      queued: Map[BigInt, TxPoolTxInfo]
  )

  /** Simplified transaction info for txpool responses. */
  case class TxPoolTxInfo(
      hash: ByteString,
      nonce: BigInt,
      from: ByteString,
      to: Option[ByteString],
      value: BigInt,
      gasPrice: BigInt,
      gas: BigInt,
      input: ByteString
  )

  object TxPoolTxInfo {
    def from(stx: SignedTransaction, sender: Address): TxPoolTxInfo =
      TxPoolTxInfo(
        hash = stx.hash,
        nonce = stx.tx.nonce,
        from = sender.bytes,
        to = stx.tx.receivingAddress.map(_.bytes),
        value = stx.tx.value,
        gasPrice = stx.tx.gasPrice,
        gas = stx.tx.gasLimit,
        input = stx.tx.payload
      )
  }
}

class TxPoolService(
    pendingTransactionsManager: ActorRef,
    getTransactionFromPoolTimeout: FiniteDuration
) extends Logger {
  import TxPoolService._

  implicit private val timeout: Timeout = Timeout(getTransactionFromPoolTimeout)

  private def getPendingTransactions: IO[PendingTransactionsResponse] =
    pendingTransactionsManager
      .askFor[PendingTransactionsResponse](PendingTransactionsManager.GetPendingTransactions)
      .handleError { ex =>
        log.error("Failed to get pending transactions for txpool", ex)
        PendingTransactionsResponse(Nil)
      }

  /** Group pending transactions by sender address then by nonce. */
  private def groupBySenderAndNonce(
      txs: Seq[PendingTransactionsManager.PendingTransaction]
  ): Map[ByteString, Map[BigInt, TxPoolTxInfo]] =
    txs
      .map(pt => TxPoolTxInfo.from(pt.stx.tx, pt.stx.senderAddress) -> pt.stx.senderAddress.bytes)
      .groupBy(_._2)
      .map { case (sender, entries) =>
        sender -> entries.map { case (info, _) => info.nonce -> info }.toMap
      }

  def getStatus(req: TxPoolStatusRequest): ServiceResponse[TxPoolStatusResponse] =
    getPendingTransactions.map { response =>
      Right(TxPoolStatusResponse(pending = response.pendingTransactions.size, queued = 0))
    }

  def getContent(req: TxPoolContentRequest): ServiceResponse[TxPoolContentResponse] =
    getPendingTransactions.map { response =>
      val grouped = groupBySenderAndNonce(response.pendingTransactions)
      Right(TxPoolContentResponse(pending = grouped, queued = Map.empty))
    }

  def getInspect(req: TxPoolInspectRequest): ServiceResponse[TxPoolInspectResponse] =
    getPendingTransactions.map { response =>
      val grouped = response.pendingTransactions
        .map(pt => (pt.stx.senderAddress.bytes, pt.stx.tx))
        .groupBy(_._1)
        .map { case (sender, entries) =>
          sender -> entries.map { case (_, stx) =>
            val to = stx.tx.receivingAddress.map(a => s"0x${a.bytes.toArray.map("%02x".format(_)).mkString}").getOrElse("contract creation")
            val summary = s"$to: ${stx.tx.value} wei + ${stx.tx.gasLimit} gas \u00d7 ${stx.tx.gasPrice} wei"
            stx.tx.nonce -> summary
          }.toMap
        }
      Right(TxPoolInspectResponse(pending = grouped, queued = Map.empty))
    }

  def getContentFrom(req: TxPoolContentFromRequest): ServiceResponse[TxPoolContentFromResponse] =
    getPendingTransactions.map { response =>
      val filtered = response.pendingTransactions
        .filter(_.stx.senderAddress == req.address)
        .map(pt => pt.stx.tx.tx.nonce -> TxPoolTxInfo.from(pt.stx.tx, pt.stx.senderAddress))
        .toMap
      Right(TxPoolContentFromResponse(pending = filtered, queued = Map.empty))
    }
}
