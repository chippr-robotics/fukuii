package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO

import com.softwaremill.diffx.scalatest.DiffMatcher
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.transactions.TransactionHistoryService.MinedTransactionData
import com.chipprbots.ethereum.transactions.testing.PendingTransactionsManagerAutoPilot
import com.chipprbots.ethereum.{blockchain => _, _}
import com.chipprbots.ethereum.testing.Tags._

class LegacyTransactionHistoryServiceSpec
    extends TestKit(ActorSystem("TransactionHistoryServiceSpec-system"))
    with FreeSpecBase
    with SpecFixtures
    with WithActorSystemShutDown
    with Matchers
    with DiffMatcher {
  class Fixture extends EphemBlockchainTestSetup {
    val pendingTransactionManager: TestProbe = TestProbe()
    pendingTransactionManager.setAutoPilot(PendingTransactionsManagerAutoPilot())
    val transactionHistoryService =
      new TransactionHistoryService(blockchainReader, pendingTransactionManager.ref, Timeouts.normalTimeout)
  }

  def createFixture() = new Fixture

  "returns account recent transactions in newest -> oldest order" in testCaseM { (fixture: Fixture) =>
    import fixture._

    val address = Address("ee4439beb5c71513b080bbf9393441697a29f478")

    val keyPair = generateKeyPair(secureRandom)

    val tx1 = SignedTransaction.sign(LegacyTransaction(0, 123, 456, Some(address), 1, ByteString()), keyPair, None)
    val tx2 = SignedTransaction.sign(LegacyTransaction(0, 123, 456, Some(address), 2, ByteString()), keyPair, None)
    val tx3 = SignedTransaction.sign(LegacyTransaction(0, 123, 456, Some(address), 3, ByteString()), keyPair, None)

    val blockWithTx1 =
      Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body.copy(transactionList = Seq(tx1)))
    val blockTx1Receipts = Seq(LegacyReceipt(HashOutcome(ByteString("foo")), 42, ByteString.empty, Nil))

    val blockWithTxs2and3 = Block(
      Fixtures.Blocks.Block3125369.header.copy(number = 3125370),
      Fixtures.Blocks.Block3125369.body.copy(transactionList = Seq(tx2, tx3))
    )
    val blockTx2And3Receipts = Seq(
      LegacyReceipt(HashOutcome(ByteString("bar")), 43, ByteString.empty, Nil),
      LegacyReceipt(HashOutcome(ByteString("baz")), 43 + 44, ByteString.empty, Nil)
    )

    val expectedTxs = Seq(
      ExtendedTransactionData(
        tx3,
        isOutgoing = false,
        Some(MinedTransactionData(blockWithTxs2and3.header, 1, 44))
      ),
      ExtendedTransactionData(
        tx2,
        isOutgoing = false,
        Some(MinedTransactionData(blockWithTxs2and3.header, 0, 43))
      ),
      ExtendedTransactionData(
        tx1,
        isOutgoing = false,
        Some(MinedTransactionData(blockWithTx1.header, 0, 42))
      )
    )

    for {
      _ <- IO {
        blockchainWriter
          .storeBlock(blockWithTx1)
          .and(blockchainWriter.storeReceipts(blockWithTx1.hash, blockTx1Receipts))
          .and(blockchainWriter.storeBlock(blockWithTxs2and3))
          .and(blockchainWriter.storeReceipts(blockWithTxs2and3.hash, blockTx2And3Receipts))
          .commit()
        blockchainWriter.saveBestKnownBlocks(blockWithTxs2and3.hash, blockWithTxs2and3.number)
      }
      response <- transactionHistoryService.getAccountTransactions(address, BigInt(3125360) to BigInt(3125370))
    } yield assert(response === expectedTxs)
  }

  "does not return account recent transactions from older blocks and return pending txs" in testCaseM {
    (fixture: Fixture) =>
      import fixture._

      val blockWithTx = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)

      val keyPair = generateKeyPair(secureRandom)

      val tx = LegacyTransaction(0, 123, 456, None, 99, ByteString())
      val signedTx = SignedTransaction.sign(tx, keyPair, None)
      val txWithSender = SignedTransactionWithSender(signedTx, Address(keyPair))

      val expectedSent =
        Seq(ExtendedTransactionData(signedTx, isOutgoing = true, None))

      for {
        _ <- IO(blockchainWriter.storeBlock(blockWithTx).commit())
        _ <- IO(pendingTransactionManager.ref ! PendingTransactionsManager.AddTransactions(txWithSender))
        response <- transactionHistoryService.getAccountTransactions(
          txWithSender.senderAddress,
          BigInt(3125371) to BigInt(3125381)
        )
      } yield assert(response === expectedSent)
  }

}
