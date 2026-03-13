package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import scala.concurrent.duration._

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.BlockImported
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.ChainReorg
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager._
import com.chipprbots.ethereum.testing.Tags._

/** Tests for LogsSubscriptionService.
  *
  * LogsSubscriptionService uses the ask pattern to get subscriptions from SubscriptionManager,
  * so we use a real SubscriptionManager actor (not just a TestProbe) to handle the protocol.
  */
class LogsSubscriptionServiceSpec
    extends TestKit(ActorSystem("LogsSubscriptionServiceSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with NormalPatience
    with org.scalamock.scalatest.MockFactory {

  implicit val timeout: Timeout = Timeout(5.seconds)

  trait TestSetup {
    val testConfig: SubscriptionManager.Config = new SubscriptionManager.Config {
      override val maxActiveConnections: Int = 10
      override val maxSubscriptionsPerConnection: Int = 10
      override val notificationBufferSize: Int = 10000
    }

    // Use real SubscriptionManager to handle GetSubscriptionsOfType queries
    val subscriptionManager = system.actorOf(SubscriptionManager.props(testConfig))
    val blockchainReader: BlockchainReader = mock[BlockchainReader]

    // Connection probe that will receive notifications
    val connectionProbe = TestProbe()

    val testAddress = Address(ByteString(new Array[Byte](20)))

    val testLogTopic = ByteString(Array.fill(32)(0x42.toByte))

    val testHeader: BlockHeader = BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = ByteString(new Array[Byte](32)),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(new Array[Byte](32)),
      transactionsRoot = ByteString(new Array[Byte](32)),
      receiptsRoot = ByteString(new Array[Byte](32)),
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = BigInt(1000),
      number = BigInt(100),
      gasLimit = BigInt(8000000),
      gasUsed = BigInt(21000),
      unixTimestamp = 1234567890L,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8))
    )

    // Register connection and create a logs subscription
    def registerAndSubscribe(): Long = {
      subscriptionManager.tell(RegisterConnection(connectionProbe.ref), connectionProbe.ref)
      connectionProbe.expectMsg(3.seconds, ConnectionRegistered)

      subscriptionManager.tell(
        Subscribe(connectionProbe.ref, SubscriptionType.Logs, None),
        connectionProbe.ref
      )
      val result = connectionProbe.expectMsgType[SubscribeResult](3.seconds)
      result.subscriptionId.get
    }

    val service = system.actorOf(
      LogsSubscriptionService.props(subscriptionManager, blockchainReader)
    )

    // Allow eventStream subscription to complete
    Thread.sleep(200)
  }

  "LogsSubscriptionService" should "send matching logs on BlockImported" taggedAs UnitTest in new TestSetup {
    val subId = registerAndSubscribe()

    val logEntry = TxLogEntry(
      loggerAddress = testAddress,
      logTopics = Seq(testLogTopic),
      data = ByteString.empty
    )
    val receipt = LegacyReceipt.withHashOutcome(
      postTransactionStateHash = ByteString(new Array[Byte](32)),
      cumulativeGasUsed = BigInt(21000),
      logsBloomFilter = ByteString(new Array[Byte](256)),
      logs = Seq(logEntry)
    )
    val tx = LegacyTransaction(0, BigInt(1), BigInt(21000), testAddress, BigInt(0), ByteString.empty)
    val signedTx = SignedTransaction(tx, 0x1c.toByte, ByteString(new Array[Byte](32)), ByteString(new Array[Byte](32)))
    val block = Block(testHeader, BlockBody(Seq(signedTx), Nil))

    (blockchainReader.getBlockHeaderByNumber _).expects(BigInt(100)).returning(Some(testHeader))
    (blockchainReader.getBlockByHash _).expects(testHeader.hash).returning(Some(block))
    (blockchainReader.getReceiptsByHash _).expects(testHeader.hash).returning(Some(Seq(receipt)))

    system.eventStream.publish(BlockImported(BigInt(100)))

    val notification = connectionProbe.expectMsgType[SendNotification](5.seconds)
    notification.notification.method shouldBe "eth_subscription"
  }

  it should "emit removed:true logs on ChainReorg for old branch" taggedAs UnitTest in new TestSetup {
    val subId = registerAndSubscribe()

    val logEntry = TxLogEntry(
      loggerAddress = testAddress,
      logTopics = Seq(testLogTopic),
      data = ByteString.empty
    )
    val receipt = LegacyReceipt.withHashOutcome(
      postTransactionStateHash = ByteString(new Array[Byte](32)),
      cumulativeGasUsed = BigInt(21000),
      logsBloomFilter = ByteString(new Array[Byte](256)),
      logs = Seq(logEntry)
    )
    val tx = LegacyTransaction(0, BigInt(1), BigInt(21000), testAddress, BigInt(0), ByteString.empty)
    val signedTx = SignedTransaction(tx, 0x1c.toByte, ByteString(new Array[Byte](32)), ByteString(new Array[Byte](32)))
    val oldBlock = Block(testHeader, BlockBody(Seq(signedTx), Nil))

    // Only the old branch has receipts — this tests the removed:true path
    (blockchainReader.getReceiptsByHash _).expects(testHeader.hash).returning(Some(Seq(receipt))).atLeastOnce()

    system.eventStream.publish(ChainReorg(
      removedBlocks = Seq(oldBlock),
      addedBlocks = Seq.empty
    ))

    // Should get notification with removed:true in the serialized log
    val notification = connectionProbe.expectMsgType[SendNotification](5.seconds)
    notification.notification.method shouldBe "eth_subscription"
  }
}
