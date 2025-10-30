package com.chipprbots.ethereum.transactions.testing
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.transactions.PendingTransactionsManager._
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

case class PendingTransactionsManagerAutoPilot(pendingTransactions: Set[PendingTransaction] = Set.empty)
    extends AutoPilot {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def run(sender: ActorRef, msg: Any): AutoPilot =
    msg match {
      case AddUncheckedTransactions(transactions) =>
        val validTxs = SignedTransactionWithSender.getSignedTransactions(transactions)
        this.addTransactions(validTxs.toSet)

      case AddTransactions(signedTransactions) =>
        this.addTransactions(signedTransactions)

      case AddOrOverrideTransaction(newStx) =>
        // Only validated transactions are added this way, it is safe to call get
        val newStxSender = SignedTransaction.getSender(newStx).get
        val obsoleteTxs = pendingTransactions
          .filter(ptx => ptx.stx.senderAddress == newStxSender && ptx.stx.tx.tx.nonce == newStx.tx.nonce)
          .map(_.stx.tx.hash)

        removeTransactions(obsoleteTxs).addTransactions(Set(SignedTransactionWithSender(newStx, newStxSender)))

      case GetPendingTransactions =>
        sender ! PendingTransactionsResponse(pendingTransactions.toSeq)
        this

      case RemoveTransactions(signedTransactions) =>
        this.removeTransactions(signedTransactions.map(_.hash).toSet)

      case ProperSignedTransactions(transactions, _) =>
        this.addTransactions(transactions)

      case ClearPendingTransactions =>
        copy(pendingTransactions = Set.empty)
    }

  def addTransactions(signedTransactions: Set[SignedTransactionWithSender]): PendingTransactionsManagerAutoPilot = {
    val timestamp = System.currentTimeMillis()
    val stxs = pendingTransactions.map(_.stx)
    val transactionsToAdd = signedTransactions.diff(stxs).map(tx => PendingTransaction(tx, timestamp))

    copy(pendingTransactions ++ transactionsToAdd)
  }

  def removeTransactions(hashes: Set[ByteString]): PendingTransactionsManagerAutoPilot =
    copy(pendingTransactions.filterNot(ptx => hashes.contains(ptx.stx.tx.hash)))
}
