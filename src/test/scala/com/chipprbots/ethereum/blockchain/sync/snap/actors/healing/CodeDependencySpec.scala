package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealedCodeStored
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingBlockedTick
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 T031 and T037 (plan D4, geth `AddCodeEntry`): an account leaf whose bytecode is missing is held, and
  * everything above it with it, until the bytecode coordinator acknowledges the code write. If the code stays
  * unavailable healing stays incomplete: it logs and asks again every minute, and after 30 minutes with no progress it
  * abandons (the lazy hand-off), never completes.
  *
  * Fixture: a root branch over two contract accounts with no storage, each with its own bytecode.
  */
class CodeDependencySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private val code0 = ByteString("code-dependency/code0")
  private val code1 = ByteString("code-dependency/code1")
  private val hash0: ByteString = kec256(code0)
  private val hash1: ByteString = kec256(code1)

  private def contract(codeHash: ByteString, nonce: Long): ByteString =
    ByteString(
      Account.accountSerializer.toBytes(
        Account(nonce = UInt256(nonce), balance = UInt256(1L), codeHash = CodeHash(codeHash))
      )
    )

  private val rest0: Array[Byte] = (0 until 63).map(i => ((i * 5 + 1) % 16).toByte).toArray
  private val rest1: Array[Byte] = (0 until 63).map(i => ((i * 3 + 7) % 16).toByte).toArray

  private def trie(): Trie =
    val acct0 = LeafNode(ByteString(rest0), contract(hash0, 1L))
    val acct1 = LeafNode(ByteString(rest1), contract(hash1, 2L))
    val root = branch(0 -> acct0, 1 -> acct1)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("acct0", None, nibbles(0), acct0),
        Placed("acct1", None, nibbles(1), acct1)
      )
    )

  final private class Rig(scheme: StorageScheme):
    val t: Trie = trie()
    val ec = new ManualExecutionContext
    val hashStore = new TestMptStorage()
    val pns = new PathNodeStorage(new EphemDataSource(Map.empty))
    val codes = new EvmCodeStorage(new EphemDataSource(Map.empty))
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = t.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = if scheme == StorageScheme.Hash then hashStore else new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true,
      evmCodeStorage = Some(codes)
    )
    peer.coordinator.set(coordinator)
    peer.serveFrom.set(Some(t.served))

    def begin(): Unit =
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(t.root.hash)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("code-peer", createTestProbe[Any]().ref.toClassic)
      )
      drive()

    def settle(): Unit =
      (1 to 4).foreach { _ =>
        val probe = createTestProbe[HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    def drive(steps: Int = 24): Unit =
      settle()
      var left = steps
      while left > 0 && ec.pending > 0 do
        ec.runNext()
        settle()
        left -= 1

    def onDisk(label: String): Boolean =
      val n = t(label)
      if scheme == StorageScheme.Hash then
        try
          hashStore.get(n.hash.toArray); true
        catch case _: Exception => false
      else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

    def seen(): Seq[SNAPSyncController.Command] =
      val out = Seq.newBuilder[SNAPSyncController.Command]
      var open = true
      while open do
        try out += controller.receiveMessage(Duration.Zero)
        catch case _: AssertionError => open = false
      out.result()

    def store(code: ByteString): Unit =
      codes.put(kec256(code), code).commit()

    def ack(code: ByteString): Unit =
      store(code)
      coordinator ! HealedCodeStored(Seq(kec256(code)))

    def ticks(n: Int): Unit =
      (1 to n).foreach(_ => coordinator ! HealingBlockedTick)
      settle()

    def awaitComplete(): Unit =
      drive()
      controller.fishForMessage(30.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }

  private val Schemes = Seq(StorageScheme.Hash, StorageScheme.Path)

  for scheme <- Schemes do
    "A healing coordinator" should s"hold an account leaf with missing code, and all above it, until the code write is acknowledged, on $scheme (T031)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      val first = rig.seen()
      withClue("the code is reported for fetching: ") {
        first.collect { case SNAPSyncController.HealedCodeHashes(h) => h }.flatten.toSet shouldBe Set(hash0, hash1)
      }
      withClue("no completion: ")(first should not contain SNAPSyncController.StateHealingComplete)
      withClue("leaves and root held, not on disk: ") {
        rig.onDisk("acct0") shouldBe false
        rig.onDisk("acct1") shouldBe false
        rig.onDisk("root") shouldBe false
      }
      rig.ack(code0)
      rig.drive()
      withClue("acct0 goes with its code, the root waits for acct1: ") {
        rig.onDisk("acct0") shouldBe true
        rig.onDisk("root") shouldBe false
      }
      rig.ack(code1)
      rig.awaitComplete()
      rig.onDisk("root") shouldBe true
    }

    it should s"commit on a stored code whose acknowledgement was lost, at the next tick, on $scheme (P3)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      withClue("held: ")(rig.onDisk("root") shouldBe false)
      rig.store(code0)
      rig.store(code1)
      rig.ticks(1)
      rig.awaitComplete()
      rig.onDisk("root") shouldBe true
    }

    it should s"ask again every tick and abandon after ${TrieNodeHealingCoordinator.MaxBlockedTicks} ticks with no progress, never complete, on $scheme (T037)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.ticks(1) // the nodes healed by begin() are progress: this tick takes them
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - 1)
      val before = rig.seen()
      withClue("still waiting: ")(before should not contain SNAPSyncController.StateHealingAbandoned)
      withClue("it asks again: ") {
        before.count(
          _.isInstanceOf[SNAPSyncController.HealedCodeHashes]
        ) shouldBe TrieNodeHealingCoordinator.MaxBlockedTicks - 1
      }
      rig.ticks(1)
      val at = rig.seen()
      at should contain(SNAPSyncController.StateHealingAbandoned)
      at should not contain SNAPSyncController.StateHealingComplete
      withClue("nothing above the waiting leaf is on disk: ")(rig.onDisk("root") shouldBe false)
    }

    it should s"count a code write as progress, on $scheme (T037)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.ticks(1) // the nodes healed by begin() are progress: this tick takes them
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - 1)
      rig.ack(code0)
      rig.settle()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks) // the first of these sees the progress and resets the count
      rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
      rig.ticks(1)
      rig.seen() should contain(SNAPSyncController.StateHealingAbandoned)
    }

    it should s"keep a held leaf's code wait through a same-root refresh, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(rig.t.root.hash) // same root: nothing changes
      rig.drive()
      rig.onDisk("root") shouldBe false
      rig.ack(code0)
      rig.ack(code1)
      rig.awaitComplete()
    }

    it should s"not run the dead-pulse walk while a held leaf waits on code, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      withClue("held: ")(rig.onDisk("root") shouldBe false)
      (1 to 4).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck)
      rig.settle()
      withClue("no walk: nothing is missing that a walk could find, and a mainnet walk takes hours: ") {
        rig.ec.pending shouldBe 0
      }
    }
