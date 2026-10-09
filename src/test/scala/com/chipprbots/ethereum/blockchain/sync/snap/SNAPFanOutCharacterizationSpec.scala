package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T026: the fan-outs that P1's `CoordinatorHandles` replaces, as they behave today.
  *   - A peer disconnect (debounced 3 s) sends the peer-unavailable message to every coordinator that exists.
  *   - Each SNAP response type is routed to exactly its own coordinator.
  *
  * Children's messages are read up to a fence (see `SnapControllerFixture`).
  */
class SNAPFanOutCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  "SNAPSyncController peer-disconnect fan-out" should
    "send PeerUnavailable to the account, storage and bytecode coordinators after the 3 s debounce" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      val sync = f.sendFence(snap)
      f.accountBefore(sync)
      f.storageBefore(sync)
      f.byteCodeBefore(sync)

      snap ! SNAPSyncController.WrappedPeerDisconnected(PeerId("gone"))
      f.manualTime.timePasses(3.seconds) // DisconnectFlushKey: FlushPeerDisconnects
      f.awaitProcessed(snap)
      val id = f.sendFence(snap)
      f.accountBefore(id) should contain(AccountRangeCoordinator.PeerUnavailable("gone"))
      f.storageBefore(id) should contain(StorageRangeCoordinator.StoragePeerUnavailable("gone"))
      f.byteCodeBefore(id) should contain(ByteCodeCoordinator.ByteCodePeerUnavailable("gone"))
    }

  it should "send HealingPeerUnavailable to the healing coordinator during StateHealing" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing()
    f.healingBefore(f.sendFence(snap))
    snap ! SNAPSyncController.WrappedPeerDisconnected(PeerId("gone"))
    f.manualTime.timePasses(3.seconds)
    f.awaitProcessed(snap)
    f.healingBefore(f.sendFence(snap)) should contain(TrieNodeHealingCoordinator.HealingPeerUnavailable("gone"))
  }

  "SNAPSyncController SNAP response routing" should
    "route AccountRange, StorageRanges and ByteCodes responses to exactly their own coordinator" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val snap = f.enterGenesisAccountSync()
      val sync = f.sendFence(snap)
      f.accountBefore(sync)
      f.storageBefore(sync)
      f.byteCodeBefore(sync)

      val account = SNAP.AccountRange(BigInt(7001), Seq.empty, Seq.empty)
      val storage = SNAP.StorageRanges(BigInt(7002), Seq.empty, Seq.empty)
      val codes = SNAP.ByteCodes(BigInt(7003), Seq.empty)
      snap ! SNAPSyncController.AccountRangeResponse(account)
      snap ! SNAPSyncController.StorageRangesResponse(storage)
      snap ! SNAPSyncController.ByteCodesResponse(codes)
      val id = f.sendFence(snap)

      def responses(msgs: Seq[Any]): Seq[Any] = msgs.collect {
        case m: AccountRangeCoordinator.AccountRangeResponseMsg  => m
        case m: StorageRangeCoordinator.StorageRangesResponseMsg => m
        case m: ByteCodeCoordinator.ByteCodesResponseMsg         => m
        case m: TrieNodeHealingCoordinator.TrieNodesResponseMsg  => m
      }
      responses(f.accountBefore(id)) shouldBe Seq(AccountRangeCoordinator.AccountRangeResponseMsg(account))
      responses(f.storageBefore(id)) shouldBe Seq(StorageRangeCoordinator.StorageRangesResponseMsg(storage))
      responses(f.byteCodeBefore(id)) shouldBe Seq(ByteCodeCoordinator.ByteCodesResponseMsg(codes))
    }

  it should "route TrieNodes responses to the healing coordinator during StateHealing" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing()
    f.healingBefore(f.sendFence(snap))
    val nodes = SNAP.TrieNodes(BigInt(7004), Seq.empty)
    snap ! SNAPSyncController.TrieNodesResponse(nodes)
    f.healingBefore(f.sendFence(snap)) should contain(TrieNodeHealingCoordinator.TrieNodesResponseMsg(nodes))
  }
