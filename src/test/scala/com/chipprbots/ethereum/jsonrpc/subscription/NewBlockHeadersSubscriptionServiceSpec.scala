package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.BlockImported
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionEvents.ChainReorg
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager.NotifySubscribers
import com.chipprbots.ethereum.testing.Tags._

class NewBlockHeadersSubscriptionServiceSpec
    extends TestKit(ActorSystem("NewBlockHeadersSubscriptionServiceSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with org.scalamock.scalatest.MockFactory {

  trait TestSetup {
    val subscriptionManagerProbe = TestProbe()
    val blockchainReader: BlockchainReader = mock[BlockchainReader]

    val testHeader: BlockHeader = BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = ByteString(new Array[Byte](32)),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(new Array[Byte](32)),
      transactionsRoot = ByteString(new Array[Byte](32)),
      receiptsRoot = ByteString(new Array[Byte](32)),
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = BigInt(1000),
      number = BigInt(42),
      gasLimit = BigInt(8000000),
      gasUsed = BigInt(21000),
      unixTimestamp = 1234567890L,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8))
    )

    val service = system.actorOf(
      NewBlockHeadersSubscriptionService.props(subscriptionManagerProbe.ref, blockchainReader)
    )

    // Allow eventStream subscription to complete
    Thread.sleep(200)
  }

  "NewBlockHeadersSubscriptionService" should "send notification on BlockImported" taggedAs UnitTest in new TestSetup {
    (blockchainReader.getBlockHeaderByNumber _).expects(BigInt(42)).returning(Some(testHeader))

    system.eventStream.publish(BlockImported(BigInt(42)))

    val msg = subscriptionManagerProbe.expectMsgType[NotifySubscribers](3.seconds)
    msg.subType shouldBe SubscriptionType.NewHeads
  }

  it should "not send notification when block header not found" taggedAs UnitTest in new TestSetup {
    (blockchainReader.getBlockHeaderByNumber _).expects(BigInt(999)).returning(None)

    system.eventStream.publish(BlockImported(BigInt(999)))

    subscriptionManagerProbe.expectNoMessage(500.millis)
  }

  it should "send notifications for added blocks on ChainReorg" taggedAs UnitTest in new TestSetup {
    val header2 = testHeader.copy(number = BigInt(43))
    val block1 = Block(testHeader, BlockBody(Nil, Nil))
    val block2 = Block(header2, BlockBody(Nil, Nil))

    system.eventStream.publish(ChainReorg(
      removedBlocks = Seq(block1), // removed blocks are ignored by newHeads
      addedBlocks = Seq(block1, block2)
    ))

    val msg1 = subscriptionManagerProbe.expectMsgType[NotifySubscribers](3.seconds)
    val msg2 = subscriptionManagerProbe.expectMsgType[NotifySubscribers](3.seconds)
    msg1.subType shouldBe SubscriptionType.NewHeads
    msg2.subType shouldBe SubscriptionType.NewHeads
  }
}
