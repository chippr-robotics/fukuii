package com.chipprbots.ethereum.network

import java.net.URI

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.Props
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestProbe

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.network.KnownNodesManager.KnownNodesManagerConfig

class KnownNodesManagerSpec extends AnyFlatSpec with Matchers {

  "KnownNodesManager" should "keep a list of nodes and persist changes" in new TestSetup {
    knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
    client.expectMsg(KnownNodesManager.KnownNodes(Set.empty))

    knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(1)), client.ref)
    knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(2)), client.ref)
    knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
    client.expectMsg(KnownNodesManager.KnownNodes(Set(uri(1), uri(2))))
    storagesInstance.storages.knownNodesStorage.getKnownNodes() shouldBe Set.empty

    testScheduler.timePasses(config.persistInterval + 10.seconds)

    knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
    client.expectMsg(KnownNodesManager.KnownNodes(Set(uri(1), uri(2))))
    storagesInstance.storages.knownNodesStorage.getKnownNodes() shouldBe Set(uri(1), uri(2))

    knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(3)), client.ref)
    knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(4)), client.ref)
    knownNodesManager.tell(KnownNodesManager.RemoveKnownNode(uri(1)), client.ref)
    knownNodesManager.tell(KnownNodesManager.RemoveKnownNode(uri(4)), client.ref)

    testScheduler.timePasses(config.persistInterval + 10.seconds)

    knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
    client.expectMsg(KnownNodesManager.KnownNodes(Set(uri(2), uri(3))))

    storagesInstance.storages.knownNodesStorage.getKnownNodes() shouldBe Set(uri(2), uri(3))
  }

  it should "respect max nodes limit" in new TestSetup {
    knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
    client.expectMsg(KnownNodesManager.KnownNodes(Set.empty))

    (1 to 10).foreach { n =>
      knownNodesManager.tell(KnownNodesManager.AddKnownNode(uri(n)), client.ref)
    }
    testScheduler.timePasses(config.persistInterval + 1.seconds)

    knownNodesManager.tell(KnownNodesManager.GetKnownNodes, client.ref)
    client.expectMsgClass(classOf[KnownNodesManager.KnownNodes])

    storagesInstance.storages.knownNodesStorage.getKnownNodes().size shouldBe 5
  }

  trait TestSetup extends EphemBlockchainTestSetup {
    implicit override lazy val system: ActorSystem = ActorSystem("KnownNodesManagerSpec_System")
    
    def testScheduler = system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]
    val config: KnownNodesManagerConfig = KnownNodesManagerConfig(persistInterval = 5.seconds, maxPersistedNodes = 5)

    val client: TestProbe = TestProbe()

    def uri(n: Int): URI = new URI(s"enode://test$n@test$n.com:9000")

    val knownNodesManager: ActorRef = system.actorOf(
      Props(new KnownNodesManager(config, storagesInstance.storages.knownNodesStorage, Some(testScheduler)))
    )
  }

}
