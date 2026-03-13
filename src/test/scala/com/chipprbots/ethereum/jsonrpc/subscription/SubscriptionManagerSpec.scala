package com.chipprbots.ethereum.jsonrpc.subscription

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration._

import org.json4s.JsonAST.JString
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.jsonrpc.subscription.SubscriptionManager._
import com.chipprbots.ethereum.testing.Tags._

class SubscriptionManagerSpec
    extends TestKit(ActorSystem("SubscriptionManagerSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers {

  trait TestSetup {
    val testConfig: Config = new Config {
      override val maxActiveConnections: Int = 3
      override val maxSubscriptionsPerConnection: Int = 2
      override val notificationBufferSize: Int = 10000
    }

    val manager = system.actorOf(SubscriptionManager.props(testConfig))
    val connectionProbe = TestProbe()
    val connectionProbe2 = TestProbe()

    def registerConnection(probe: TestProbe): Unit = {
      manager.tell(RegisterConnection(probe.ref), probe.ref)
      probe.expectMsg(3.seconds, ConnectionRegistered)
    }

    def subscribe(
        probe: TestProbe,
        subType: SubscriptionType,
        params: Option[SubscriptionParams] = None
    ): Long = {
      manager.tell(Subscribe(probe.ref, subType, params), probe.ref)
      val result = probe.expectMsgType[SubscribeResult](3.seconds)
      result.subscriptionId shouldBe defined
      result.subscriptionId.get
    }
  }

  // --- Connection registration ---

  "SubscriptionManager" should "register a connection" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
  }

  it should "reject connections when max is reached" taggedAs UnitTest in new TestSetup {
    val probes = (1 to 3).map(_ => TestProbe())
    probes.foreach(registerConnection)

    val extraProbe = TestProbe()
    manager.tell(RegisterConnection(extraProbe.ref), extraProbe.ref)
    extraProbe.expectMsg(3.seconds, ConnectionRejected)
  }

  // --- Subscribe ---

  it should "create a newHeads subscription and return an ID" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    val id = subscribe(connectionProbe, SubscriptionType.NewHeads)
    id should be > 0L
  }

  it should "create subscriptions with unique IDs" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    val id1 = subscribe(connectionProbe, SubscriptionType.NewHeads)
    val id2 = subscribe(connectionProbe, SubscriptionType.Logs)
    id1 should not equal id2
  }

  it should "create all 4 subscription types" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    registerConnection(connectionProbe2)

    val id1 = subscribe(connectionProbe, SubscriptionType.NewHeads)
    val id2 = subscribe(connectionProbe, SubscriptionType.Logs)
    // connectionProbe is at limit (2), use second connection
    val id3 = subscribe(connectionProbe2, SubscriptionType.NewPendingTransactions)
    val id4 = subscribe(connectionProbe2, SubscriptionType.Syncing)

    Set(id1, id2, id3, id4).size shouldBe 4
  }

  it should "reject subscription when per-connection limit reached" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    subscribe(connectionProbe, SubscriptionType.NewHeads)
    subscribe(connectionProbe, SubscriptionType.Logs)

    // Third should be rejected (limit is 2)
    manager.tell(Subscribe(connectionProbe.ref, SubscriptionType.Syncing, None), connectionProbe.ref)
    val result = connectionProbe.expectMsgType[SubscribeResult](3.seconds)
    result.subscriptionId shouldBe None
  }

  // --- Unsubscribe ---

  it should "unsubscribe successfully" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    val id = subscribe(connectionProbe, SubscriptionType.NewHeads)

    manager.tell(Unsubscribe(connectionProbe.ref, id), connectionProbe.ref)
    connectionProbe.expectMsg(3.seconds, UnsubscribeResult(true))
  }

  it should "fail to unsubscribe with wrong connection" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    registerConnection(connectionProbe2)
    val id = subscribe(connectionProbe, SubscriptionType.NewHeads)

    // connectionProbe2 tries to unsubscribe connectionProbe's subscription
    manager.tell(Unsubscribe(connectionProbe2.ref, id), connectionProbe2.ref)
    connectionProbe2.expectMsg(3.seconds, UnsubscribeResult(false))
  }

  it should "fail to unsubscribe non-existent subscription" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)

    manager.tell(Unsubscribe(connectionProbe.ref, 9999L), connectionProbe.ref)
    connectionProbe.expectMsg(3.seconds, UnsubscribeResult(false))
  }

  // --- NotifySubscribers ---

  it should "deliver notifications to matching subscribers" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    subscribe(connectionProbe, SubscriptionType.NewHeads)

    manager ! NotifySubscribers(SubscriptionType.NewHeads, JString("test-block"))
    val notification = connectionProbe.expectMsgType[SendNotification](3.seconds)
    notification.notification.method shouldBe "eth_subscription"
  }

  it should "not deliver notifications to non-matching subscribers" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    subscribe(connectionProbe, SubscriptionType.NewHeads)

    manager ! NotifySubscribers(SubscriptionType.Logs, JString("test-log"))
    connectionProbe.expectNoMessage(500.millis)
  }

  it should "deliver to multiple subscribers of same type" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    registerConnection(connectionProbe2)
    subscribe(connectionProbe, SubscriptionType.NewHeads)
    subscribe(connectionProbe2, SubscriptionType.NewHeads)

    manager ! NotifySubscribers(SubscriptionType.NewHeads, JString("test-block"))
    connectionProbe.expectMsgType[SendNotification](3.seconds)
    connectionProbe2.expectMsgType[SendNotification](3.seconds)
  }

  // --- NotifySubscription (per-subscription) ---

  it should "deliver per-subscription notification" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    val id = subscribe(connectionProbe, SubscriptionType.Logs)

    manager ! NotifySubscription(id, JString("test-log"))
    val notification = connectionProbe.expectMsgType[SendNotification](3.seconds)
    notification.notification.params should not be org.json4s.JNothing
  }

  // --- GetSubscriptionsOfType ---

  it should "return subscriptions of requested type" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    subscribe(connectionProbe, SubscriptionType.NewHeads)
    subscribe(connectionProbe, SubscriptionType.Logs)

    val probe = TestProbe()
    manager.tell(GetSubscriptionsOfType(SubscriptionType.NewHeads), probe.ref)
    val response = probe.expectMsgType[SubscriptionsOfType](3.seconds)
    response.subscriptions.size shouldBe 1
    response.subscriptions.head.subscriptionType shouldBe SubscriptionType.NewHeads
  }

  // --- Connection cleanup on Terminated ---

  it should "remove all subscriptions when connection terminates" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    subscribe(connectionProbe, SubscriptionType.NewHeads)
    subscribe(connectionProbe, SubscriptionType.Logs)

    // Kill the connection actor
    system.stop(connectionProbe.ref)
    Thread.sleep(500) // Allow Terminated message to propagate

    // Notifications should no longer be delivered
    manager ! NotifySubscribers(SubscriptionType.NewHeads, JString("after-disconnect"))
    connectionProbe.expectNoMessage(500.millis)

    // Verify via GetSubscriptionsOfType
    val checkProbe = TestProbe()
    manager.tell(GetSubscriptionsOfType(SubscriptionType.NewHeads), checkProbe.ref)
    val response = checkProbe.expectMsgType[SubscriptionsOfType](3.seconds)
    response.subscriptions shouldBe empty
  }

  it should "not affect other connections when one terminates" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    registerConnection(connectionProbe2)
    subscribe(connectionProbe, SubscriptionType.NewHeads)
    subscribe(connectionProbe2, SubscriptionType.NewHeads)

    // Kill only connectionProbe
    system.stop(connectionProbe.ref)
    Thread.sleep(500)

    // connectionProbe2 should still receive notifications
    manager ! NotifySubscribers(SubscriptionType.NewHeads, JString("still-alive"))
    connectionProbe2.expectMsgType[SendNotification](3.seconds)
  }

  // --- Subscription allows new after unsubscribe frees slot ---

  it should "allow new subscription after unsubscribe frees a slot" taggedAs UnitTest in new TestSetup {
    registerConnection(connectionProbe)
    val id1 = subscribe(connectionProbe, SubscriptionType.NewHeads)
    subscribe(connectionProbe, SubscriptionType.Logs)

    // At limit (2), unsubscribe one
    manager.tell(Unsubscribe(connectionProbe.ref, id1), connectionProbe.ref)
    connectionProbe.expectMsg(3.seconds, UnsubscribeResult(true))

    // Should now be able to subscribe again
    val id3 = subscribe(connectionProbe, SubscriptionType.Syncing)
    id3 should be > 0L
  }
}
