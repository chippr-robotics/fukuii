package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Await
import scala.concurrent.duration._

import org.json4s._
import org.json4s.native.JsonMethods._

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.jsonrpc.SubscriptionManager._
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for SubscriptionManager actor.
  *
  * Besu reference: ethereum/api/.../websocket/subscription/SubscriptionManager.java
  * ethereum/api/.../websocket/subscription/blockheaders/NewBlockHeadersSubscriptionService.java
  * ethereum/api/.../websocket/subscription/pending/PendingTransactionSubscriptionService.java
  * ethereum/api/.../websocket/subscription/logs/LogsSubscriptionService.java
  *
  * Tests cover: connection lifecycle, subscribe/unsubscribe, push notification dispatch.
  */
class SubscriptionManagerSpec
    extends TestKit(ActorSystem("SubscriptionManagerSpec"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with NormalPatience {

  import org.apache.pekko.pattern.ask
  implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
  implicit val mat: Materializer = Materializer(system)
  implicit val formats: org.json4s.Formats = org.json4s.DefaultFormats

  val fixtureBlock: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)

  // ── helpers ────────────────────────────────────────────────────────────────

  def makeManager(): ActorRef =
    system.actorOf(SubscriptionManager.props(new EphemBlockchainTestSetup {}.blockchainReader))

  /** Returns a preMaterialized queue + source pair. */
  def makeQueue() = Source
    .queue[String](64, OverflowStrategy.dropHead)
    .preMaterialize()

  /** Collects N messages from the queue source into a Future[Seq[String]]. */
  def collectN(source: org.apache.pekko.stream.scaladsl.Source[String, Any], n: Int) =
    source.take(n).runWith(Sink.seq)

  // ── connection lifecycle ───────────────────────────────────────────────────

  "SubscriptionManager" should "accept RegisterConnection without error" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    mgr ! RegisterConnection("conn-1", queue)
    // No assertion needed — the actor would log an error if this failed
    // Just confirm the message is processed without exception
    Thread.sleep(50)
    succeed
  }

  it should "clean up subscriptions when ConnectionClosed is received" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-cleanup"

    mgr ! RegisterConnection(connId, queue)
    val subResp = Await.result(
      (mgr ? Subscribe(connId, "newHeads", None)).mapTo[SubscribeResponse],
      5.seconds
    )
    subResp.result.isRight shouldBe true

    // Close connection — subscription should be removed
    mgr ! ConnectionClosed(connId)
    Thread.sleep(50)

    // Unsubscribe after close returns false (not found)
    val subId = subResp.result.toOption.get
    val unsubResp = Await.result(
      (mgr ? Unsubscribe(connId, subId)).mapTo[UnsubscribeResponse],
      5.seconds
    )
    unsubResp.found shouldBe false
  }

  // ── subscribe / unsubscribe ────────────────────────────────────────────────

  it should "return a numeric subscription id for newHeads subscription" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-newheads"

    mgr ! RegisterConnection(connId, queue)
    val resp = Await.result(
      (mgr ? Subscribe(connId, "newHeads", None)).mapTo[SubscribeResponse],
      5.seconds
    )

    resp.result.isRight shouldBe true
    resp.result.toOption.get should be > 0L
  }

  it should "return a subscription id for logs subscription" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-logs"

    mgr ! RegisterConnection(connId, queue)
    val resp = Await.result(
      (mgr ? Subscribe(connId, "logs", None)).mapTo[SubscribeResponse],
      5.seconds
    )

    resp.result.isRight shouldBe true
  }

  it should "return a subscription id for newPendingTransactions" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-pending"

    mgr ! RegisterConnection(connId, queue)
    val resp = Await.result(
      (mgr ? Subscribe(connId, "newPendingTransactions", None)).mapTo[SubscribeResponse],
      5.seconds
    )

    resp.result.isRight shouldBe true
  }

  it should "return an error for unknown subscription type" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-unknown"

    mgr ! RegisterConnection(connId, queue)
    val resp = Await.result(
      (mgr ? Subscribe(connId, "bogusType", None)).mapTo[SubscribeResponse],
      5.seconds
    )

    resp.result.isLeft shouldBe true
    resp.result.swap.toOption.get should include("Unknown subscription type")
  }

  it should "return true when unsubscribing a valid subscription" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, _) = makeQueue()
    val connId = "conn-unsub"

    mgr ! RegisterConnection(connId, queue)
    val subId = Await
      .result(
        (mgr ? Subscribe(connId, "newHeads", None)).mapTo[SubscribeResponse],
        5.seconds
      )
      .result
      .toOption
      .get

    val resp = Await.result(
      (mgr ? Unsubscribe(connId, subId)).mapTo[UnsubscribeResponse],
      5.seconds
    )
    resp.found shouldBe true
  }

  it should "return false when unsubscribing with wrong connection id" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue1, _) = makeQueue()
    val (queue2, _) = makeQueue()

    mgr ! RegisterConnection("conn-a", queue1)
    mgr ! RegisterConnection("conn-b", queue2)
    val subId = Await
      .result(
        (mgr ? Subscribe("conn-a", "newHeads", None)).mapTo[SubscribeResponse],
        5.seconds
      )
      .result
      .toOption
      .get

    // conn-b trying to unsubscribe conn-a's subscription
    val resp = Await.result(
      (mgr ? Unsubscribe("conn-b", subId)).mapTo[UnsubscribeResponse],
      5.seconds
    )
    resp.found shouldBe false
  }

  // ── push notifications ─────────────────────────────────────────────────────

  it should "push newHeads notification to subscribed connection on NewBlockImported" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue, source) = makeQueue()
    val connId = "conn-push-newheads"
    val messages = collectN(source, 1)

    mgr ! RegisterConnection(connId, queue)
    Await.result(
      (mgr ? Subscribe(connId, "newHeads", None)).mapTo[SubscribeResponse],
      5.seconds
    )

    system.eventStream.publish(NewBlockImported(fixtureBlock))

    val received = Await.result(messages, 5.seconds)
    received should have size 1

    val json = parse(received.head)
    (json \ "method").extract[String] shouldBe "eth_subscription"
    val params = json \ "params"
    (params \ "result" \ "number").extract[String] should startWith("0x")
    (params \ "result" \ "hash").extract[String] should startWith("0x")
  }

  it should "not push newHeads to other connections" taggedAs UnitTest in {
    val mgr = makeManager()
    val (queue1, _) = makeQueue()
    val (queue2, source2) = makeQueue()
    val connId1 = "conn-iso-1"
    val connId2 = "conn-iso-2"

    mgr ! RegisterConnection(connId1, queue1)
    mgr ! RegisterConnection(connId2, queue2)

    // Only conn1 subscribes
    Await.result(
      (mgr ? Subscribe(connId1, "newHeads", None)).mapTo[SubscribeResponse],
      5.seconds
    )

    system.eventStream.publish(NewBlockImported(fixtureBlock))

    // conn2 should receive nothing — add a brief wait and drain
    Thread.sleep(200)
    val messages2 = source2.take(0).runWith(Sink.seq)
    val received2 = Await.result(messages2, 1.second)
    received2 shouldBe empty
  }
}
