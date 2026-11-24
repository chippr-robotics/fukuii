package com.chipprbots.ethereum.network

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MaintainedPeersChanged
import com.chipprbots.ethereum.network.PeerEventBusActor.PublishCmd
import com.chipprbots.ethereum.testing.Tags.*

/** B-453: the node ids of the peers PeerManagerActor maintains (static-nodes.json and admin_addPeer, less
  * admin_removePeer), as the peer event bus last said them, for the snap-peer precondition to read at each count.
  */
class MaintainedPeerIdsSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with Eventually:

  private val nodeA = "aa" * 64
  private val nodeB = "0b" * 64

  "MaintainedPeerIds" should "follow the maintained set the bus publishes: an add, then a removal" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val bus = spawn(PeerEventBusActor.behavior())
    val ids = new MaintainedPeerIds
    val subscribed = createTestProbe[Unit]()
    spawn(MaintainedPeerIds.behavior(ids, bus, onSubscribed = () => subscribed.ref ! ()))
    subscribed.expectMessage(())
    ids.current shouldBe empty

    bus ! PublishCmd(MaintainedPeersChanged(Set(nodeA, nodeB)))
    eventually(ids.current shouldBe Set(ByteString(Hex.decode(nodeA)), ByteString(Hex.decode(nodeB))))

    bus ! PublishCmd(MaintainedPeersChanged(Set(nodeB)))
    eventually(ids.current shouldBe Set(ByteString(Hex.decode(nodeB))))
  }

  it should "leave out a node id that is not hex, or is empty" taggedAs (UnitTest, NetworkTest) in {
    val bus = spawn(PeerEventBusActor.behavior())
    val ids = new MaintainedPeerIds
    val subscribed = createTestProbe[Unit]()
    spawn(MaintainedPeerIds.behavior(ids, bus, onSubscribed = () => subscribed.ref ! ()))
    subscribed.expectMessage(())

    bus ! PublishCmd(MaintainedPeersChanged(Set(nodeA, "not-hex", "")))
    eventually(ids.current shouldBe Set(ByteString(Hex.decode(nodeA))))
  }
