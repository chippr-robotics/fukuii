package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.params.ECPublicKeyParameters

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.TxPoolService._
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse

/** Tests for TxPoolService — txpool_status, txpool_content, txpool_inspect, txpool_contentFrom.
  */
class TxPoolServiceSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience with SecureRandomBuilder {

  implicit val runtime: IORuntime = IORuntime.global

  // ===== getStatus =====

  "TxPoolService" should "return pending count and queued always 0" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getStatus(TxPoolStatusRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Seq(pendingTx1, pendingTx2)))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    result.toOption.get.pending shouldBe 2
    result.toOption.get.queued shouldBe 0
  }

  it should "return 0 pending when pool is empty" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getStatus(TxPoolStatusRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Nil))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    result.toOption.get.pending shouldBe 0
  }

  // ===== getContent =====

  it should "group transactions by sender and nonce in getContent" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getContent(TxPoolContentRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Seq(pendingTx1, pendingTx2)))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    val content = result.toOption.get
    content.pending should not be empty
    content.queued shouldBe empty
    // Both txs from same sender
    content.pending(sender1Address.bytes) should have size 2
  }

  it should "return empty maps when pool is empty in getContent" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getContent(TxPoolContentRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Nil))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    result.toOption.get.pending shouldBe empty
    result.toOption.get.queued shouldBe empty
  }

  // ===== getInspect =====

  it should "return human-readable summaries per tx in getInspect" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getInspect(TxPoolInspectRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Seq(pendingTx1)))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    val inspect = result.toOption.get
    inspect.pending should not be empty
    inspect.queued shouldBe empty
    val summaries = inspect.pending(sender1Address.bytes)
    summaries(BigInt(0)) should include("wei")
    summaries(BigInt(0)) should include("gas")
  }

  it should "show contract creation for txs with no recipient in getInspect" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getInspect(TxPoolInspectRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Seq(contractCreationTx)))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    val summaries = result.toOption.get.pending(sender1Address.bytes)
    summaries(BigInt(2)) should include("contract creation")
  }

  it should "return empty when pool is empty in getInspect" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getInspect(TxPoolInspectRequest()).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Nil))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    result.toOption.get.pending shouldBe empty
  }

  // ===== getContentFrom =====

  it should "filter by specific sender address in getContentFrom" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val resF = txPoolService.getContentFrom(TxPoolContentFromRequest(sender1Address)).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Seq(pendingTx1, pendingTx2, pendingTxOtherSender)))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    val content = result.toOption.get
    content.pending should have size 2  // only sender1's txs
    content.queued shouldBe empty
  }

  it should "return empty when address has no pending txs in getContentFrom" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val unknownAddr = Address(ByteString(new Array[Byte](20)))
    val resF = txPoolService.getContentFrom(TxPoolContentFromRequest(unknownAddr)).unsafeToFuture()

    ptm.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    ptm.reply(PendingTransactionsResponse(Seq(pendingTx1)))

    val result = resF.futureValue
    result shouldBe Symbol("right")
    result.toOption.get.pending shouldBe empty
  }

  // ===== Test Setup =====

  trait TestSetup {
    implicit val system: ActorSystem = ActorSystem("TxPoolServiceSpec_System")

    val ptm: TestProbe = TestProbe()

    val txPoolService = new TxPoolService(ptm.ref, 5.seconds)

    // Generate real keypairs for proper sender recovery
    val sender1KeyPair = generateKeyPair(secureRandom)
    val sender1Address: Address = Address(
      kec256(sender1KeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail)
    )

    val sender2KeyPair = generateKeyPair(secureRandom)
    val sender2Address: Address = Address(
      kec256(sender2KeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail)
    )

    val recipientAddress: Address = Address(ByteString(org.bouncycastle.util.encoders.Hex.decode("abcdef0123" * 4)))

    val stx1: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(nonce = 0, gasPrice = 20000000000L, gasLimit = 21000,
        receivingAddress = recipientAddress, value = BigInt("1000000000000000000"), payload = ByteString.empty),
      sender1KeyPair, Some(BigInt(1))
    )

    val stx2: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(nonce = 1, gasPrice = 20000000000L, gasLimit = 21000,
        receivingAddress = recipientAddress, value = BigInt("2000000000000000000"), payload = ByteString.empty),
      sender1KeyPair, Some(BigInt(1))
    )

    val stx3: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(nonce = 0, gasPrice = 10000000000L, gasLimit = 21000,
        receivingAddress = recipientAddress, value = BigInt("500000000000000000"), payload = ByteString.empty),
      sender2KeyPair, Some(BigInt(1))
    )

    // Contract creation tx (no receivingAddress)
    val contractTx: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(nonce = 2, gasPrice = 20000000000L, gasLimit = 100000,
        receivingAddress = None, value = 0, payload = ByteString(Array[Byte](0x60, 0x00))),
      sender1KeyPair, Some(BigInt(1))
    )

    val pendingTx1 = PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(stx1, sender1Address), System.currentTimeMillis()
    )
    val pendingTx2 = PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(stx2, sender1Address), System.currentTimeMillis()
    )
    val pendingTxOtherSender = PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(stx3, sender2Address), System.currentTimeMillis()
    )
    val contractCreationTx = PendingTransactionsManager.PendingTransaction(
      SignedTransactionWithSender(contractTx, sender1Address), System.currentTimeMillis()
    )
  }
}
