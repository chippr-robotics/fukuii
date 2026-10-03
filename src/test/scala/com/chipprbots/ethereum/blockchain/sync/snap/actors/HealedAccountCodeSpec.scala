package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** Account-range sync builds its bytecode list from the account responses it receives, so an account that reaches this
  * node ONLY through trie healing (a contract created after the pivot) never has its bytecode requested. Devnet-8 block
  * 318074 was the result: a call into such a contract ran as a call to an EOA. The healing coordinator reports the
  * codeHash of every healed account leaf whose code is absent, so the controller can fetch it.
  */
class HealedAccountCodeSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private val code = ByteString(Array[Byte](0x60, 0x00, 0x60, 0x00, 0xf3.toByte))
  private val codeHash: CodeHash = CodeHash(kec256(code))

  /** Heal an ABSENT root that is a single account leaf carrying `codeHash`, with the moving-root path that seeds and
    * fetches the root, and return what the controller probe saw until the node was accepted.
    */
  private def healAccountLeaf(codes: EvmCodeStorage): Seq[SNAPSyncController.Command] =
    val storage = new TestMptStorage()
    val leaf = com.chipprbots.ethereum.mpt.LeafNode(
      ByteString(Array[Byte](0x0a, 0x0b, 0x0c)),
      ByteString(Account(codeHash = codeHash).toBytes)
    )
    val rootHash = ByteString(leaf.hash)
    val networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]()
    val snapSyncController = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = rootHash,
      networkPeerManager = networkPeerManager.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = storage,
      batchSize = 16,
      snapSyncController = snapSyncController.ref,
      healingWriterEcOverride = Some(classicSystem.dispatcher),
      movingRootDeltaHeal = true,
      evmCodeStorage = Some(codes)
    )
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(rootHash)
    val peerProbe = testKit.createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
    coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
      PeerTestHelpers.createTestPeer("code-peer", peerProbe.ref.toClassic)
    )
    val send = networkPeerManager.expectMessageType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    val request = send.message.underlyingMsg.asInstanceOf[SNAP.GetTrieNodes]
    coordinator ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
      SNAP.TrieNodes(requestId = request.requestId, nodes = Seq(ByteString(leaf.encode)))
    )
    // The report is sent while the response is processed, BEFORE the healed-progress notification, so everything the
    // controller is told about this node is in `seen` once that notification arrives.
    val seen = scala.collection.mutable.ArrayBuffer.empty[SNAPSyncController.Command]
    snapSyncController.fishForMessage(5.seconds) {
      case m: SNAPSyncController.ProgressNodesHealed => seen += m; FishingOutcomes.complete
      case m                                         => seen += m; FishingOutcomes.continueAndIgnore
    }
    seen.toSeq

  "TrieNodeHealingCoordinator" should
    "report the codeHash of a healed account leaf whose bytecode is not in EvmCodeStorage" taggedAs UnitTest in {
      val codes = new EvmCodeStorage(EphemDataSource())
      val reported = healAccountLeaf(codes).collect { case SNAPSyncController.HealedCodeHashes(hashes) => hashes }
      reported.flatten shouldBe Seq(codeHash.value)
    }

  it should "NOT report a healed account whose bytecode is already stored" taggedAs UnitTest in {
    val codes = new EvmCodeStorage(EphemDataSource())
    codes.put(codeHash.value, code).commit()
    healAccountLeaf(codes).collect { case h: SNAPSyncController.HealedCodeHashes => h } shouldBe empty
  }
