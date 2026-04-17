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
  * Besu reference: TxPoolBesuTransactions, TxPoolBesuStatistics, TxPoolBesuPendingTransactions,
  *   PendingTransactionFilter, PendingTransactionsParams
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
    val txPoolSize: Int                              = 4096
    val pendingTxManagerQueryTimeout: FiniteDuration = 5.seconds
    val transactionTimeout: FiniteDuration           = 2.hours
    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds
  }

  val block = Fixtures.Blocks.Block3125369
  // Block3125369 has 4 txs: nonces 438550, 438551, 438552, 438553; gasLimit 50000 each
  val txList = block.body.transactionList

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def makePendingTx(
      stx: com.chipprbots.ethereum.domain.SignedTransaction,
      local: Boolean = false
  ): PendingTransaction = {
    val withSender = SignedTransactionWithSender.getSignedTransactions(Seq(stx))
    PendingTransaction(withSender.head, System.currentTimeMillis(), receivedFromLocalSource = local)
  }

  trait TestSetup {
    val probe   = TestProbe()
    val service = new TxPoolService(probe.ref, 5.seconds, txPoolConfig)
  }

  // ── besuTransactions ────────────────────────────────────────────────────────

  "TxPoolService.besuTransactions" should "return all pending transactions" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))

    val future = service.besuTransactions(TxPoolBesuTransactionsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 4
  }

  it should "return empty list when pool is empty" taggedAs UnitTest in new TestSetup {
    val future = service.besuTransactions(TxPoolBesuTransactionsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq.empty))

    future.futureValue shouldBe Right(TxPoolBesuTransactionsResponse(Seq.empty))
  }

  // ── besuStatistics ──────────────────────────────────────────────────────────

  "TxPoolService.besuStatistics" should "count remote txs (receivedFromLocalSource=false)" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_)) // all remote (default)

    val future = service.besuStatistics(TxPoolBesuStatisticsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    val stats = result.toOption.get
    stats.maxSize     shouldBe 4096L
    stats.localCount  shouldBe 0L
    stats.remoteCount shouldBe 4L
  }

  it should "count local txs (receivedFromLocalSource=true) separately from remote" taggedAs UnitTest in new TestSetup {
    // 1 local (via AddOrOverrideTransaction), 3 remote (via AddTransactions)
    val localPt  = makePendingTx(txList.head, local = true)
    val remotePts = txList.tail.map(makePendingTx(_))

    val future = service.besuStatistics(TxPoolBesuStatisticsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(localPt +: remotePts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    val stats = result.toOption.get
    stats.maxSize     shouldBe 4096L
    stats.localCount  shouldBe 1L
    stats.remoteCount shouldBe 3L
  }

  it should "report all counts=0 for an empty pool" taggedAs UnitTest in new TestSetup {
    val future = service.besuStatistics(TxPoolBesuStatisticsRequest()).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(Seq.empty))

    future.futureValue shouldBe Right(
      TxPoolBesuStatisticsResponse(maxSize = 4096L, localCount = 0L, remoteCount = 0L)
    )
  }

  // ── besuPendingTransactions ─────────────────────────────────────────────────

  "TxPoolService.besuPendingTransactions" should "return all txs when no limit or filter given" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))

    val future =
      service.besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(None)).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 4
  }

  it should "honour the limit parameter" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))

    val future =
      service.besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(Some(1))).unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 1
  }

  it should "filter by nonce eq" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))
    // nonces are 438550, 438551, 438552, 438553 — eq 438551 returns 1 tx
    val params = TxPoolBesuPendingTransactionsParams(
      Seq(TxPoolFilter("nonce", Eq, "438551"))
    )

    val future =
      service
        .besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(None, Some(params)))
        .unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 1
  }

  it should "filter by nonce gt" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))
    // nonces 438550-438553; gt 438551 returns 438552 and 438553
    val params = TxPoolBesuPendingTransactionsParams(
      Seq(TxPoolFilter("nonce", Gt, "438551"))
    )

    val future =
      service
        .besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(None, Some(params)))
        .unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 2
  }

  it should "filter by gasLimit eq (hex)" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))
    // all txs have gasLimit=50000=0xC350
    val params = TxPoolBesuPendingTransactionsParams(
      Seq(TxPoolFilter("gas", Eq, "0xC350"))
    )

    val future =
      service
        .besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(None, Some(params)))
        .unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 4
  }

  it should "filter by to action (contract creation) returns empty when all txs have recipients" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))
    // all Block3125369 txs have receivingAddress — none are contract creation
    val params = TxPoolBesuPendingTransactionsParams(
      Seq(TxPoolFilter("to", Action, "deploy"))
    )

    val future =
      service
        .besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(None, Some(params)))
        .unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 0
  }

  it should "apply filter and limit together" taggedAs UnitTest in new TestSetup {
    val pts = txList.map(makePendingTx(_))
    // nonce gt 438550 returns 3 txs (438551, 438552, 438553); limit=2 keeps first 2
    val params = TxPoolBesuPendingTransactionsParams(
      Seq(TxPoolFilter("nonce", Gt, "438550"))
    )

    val future =
      service
        .besuPendingTransactions(TxPoolBesuPendingTransactionsRequest(Some(2), Some(params)))
        .unsafeToFuture()

    probe.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    probe.reply(PendingTransactionsResponse(pts))

    val result = future.futureValue
    result shouldBe a[Right[_, _]]
    result.toOption.get.pendingTransactions should have size 2
  }
}
