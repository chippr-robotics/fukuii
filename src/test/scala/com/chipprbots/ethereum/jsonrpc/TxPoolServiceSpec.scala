package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import cats.effect.unsafe.IORuntime

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

/** Unit tests for TxPoolService.
  *
  * Besu reference: TxPoolBesuTransactions, TxPoolBesuStatistics, TxPoolBesuPendingTransactions
  */
class TxPoolServiceSpec
    extends TestKit(ActorSystem("TxPoolServiceSpec"))
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with NormalPatience {

  import TxPoolService._

  implicit val runtime: IORuntime = IORuntime.global

  val txPoolConfig: TxPoolConfig = new TxPoolConfig {
    val txPoolSize: Int                            = 4096
    val pendingTxManagerQueryTimeout: FiniteDuration = 5.seconds
    val transactionTimeout: FiniteDuration         = 2.hours
    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds
  }

  val block = Fixtures.Blocks.Block3125369
  val tx1   = block.body.transactionList.head
  val tx2   = block.body.transactionList.last

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def makePendingTx(stx: com.chipprbots.ethereum.domain.SignedTransaction): PendingTransaction = {
    val withSender = SignedTransactionWithSender.getSignedTransactions(Seq(stx))
    PendingTransaction(withSender.head, System.currentTimeMillis())
  }

  trait TestSetup {
    val probe   = TestProbe()
    val service = new TxPoolService(probe.ref, 5.seconds, txPoolConfig)
  }

  "TxPoolService.besuTransactions" should "return all pending transactions" taggedAs UnitTest in new TestSetup {
    val pt1 = makePendingTx(tx1)
    val pt2 = makePendingTx(tx2)

    val future = service.besuTransactions(TxPoolBesuTransactionsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq(pt1, pt2)))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 2
  }

  it should "return empty list when pool is empty" taggedAs UnitTest in new TestSetup {
    val future = service.besuTransactions(TxPoolBesuTransactionsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq.empty))

    future.futureValue shouldBe Right(TxPoolBesuTransactionsResponse(Seq.empty))
  }

  "TxPoolService.besuStatistics" should "return pool statistics with localCount=0" taggedAs UnitTest in new TestSetup {
    val pt1 = makePendingTx(tx1)
    val pt2 = makePendingTx(tx2)

    val future = service.besuStatistics(TxPoolBesuStatisticsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq(pt1, pt2)))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    val stats = result.toOption.get
    stats.maxSize shouldBe 4096L
    stats.localCount shouldBe 0L
    stats.remoteCount shouldBe 2L
  }

  it should "report remoteCount=0 for an empty pool" taggedAs UnitTest in new TestSetup {
    val future = service.besuStatistics(TxPoolBesuStatisticsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq.empty))

    future.futureValue shouldBe Right(
      TxPoolBesuStatisticsResponse(maxSize = 4096L, localCount = 0L, remoteCount = 0L)
    )
  }

  "TxPoolService.besuPendingTransactions" should "return all txs when no limit given" taggedAs UnitTest in new TestSetup {
    val pt1 = makePendingTx(tx1)
    val pt2 = makePendingTx(tx2)

    val future =
      service.besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(None)).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq(pt1, pt2)))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 2
  }

  it should "honour the limit parameter" taggedAs UnitTest in new TestSetup {
    val pt1 = makePendingTx(tx1)
    val pt2 = makePendingTx(tx2)

    val future =
      service.besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(Some(1))).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq(pt1, pt2)))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 1
  }
}
