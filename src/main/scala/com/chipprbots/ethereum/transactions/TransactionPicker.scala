package com.chipprbots.ethereum.transactions

import akka.actor.ActorRef
import akka.util.Timeout

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.TaskActorOps
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.Logger

trait TransactionPicker extends Logger {

  protected def pendingTransactionsManager: ActorRef
  protected def getTransactionFromPoolTimeout: FiniteDuration

  implicit val timeout: Timeout = Timeout(getTransactionFromPoolTimeout)

  def getTransactionsFromPool: IO[PendingTransactionsResponse] =
    pendingTransactionsManager
      .askFor[PendingTransactionsResponse](PendingTransactionsManager.GetPendingTransactions)
      .onErrorHandle { ex =>
        log.error("Failed to get transactions, mining block with empty transactions list", ex)
        PendingTransactionsResponse(Nil)
      }
}
