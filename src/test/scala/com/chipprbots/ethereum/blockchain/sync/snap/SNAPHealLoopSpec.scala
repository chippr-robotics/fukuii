package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.controller.FinalizationPolicy
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.testing.Tags.*

/** #1555 (Platåberget, 2026-10-10): the SNAP heal loop. A frozen CL head kept the heal pivot on a root no peer served
  * (fixed by (a), see `SNAPLazyHealAnchorSpec`); the re-peg budget ran out; the lazy hand-off's Path publish scanned
  * the whole state and failed "Root node not found"; SyncController's HealingImpossible restart resumed healing on the
  * same pivot. This suite pins the loop breaker (b):
  *   - Path scheme: no lazy hand-off and no publish while the pivot root's node is stored nowhere;
  *   - PoS: a heal-only recovery does not resume on the pivot that failed finalization (nor on one the CL head shows to
  *     be past maxPivotStalenessBlocks); ETC/pre-merge resumes on it as before.
  */
class SNAPHealLoopSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  import SnapControllerFixture.*

  private val PivotOffset = SNAPSyncConfig().pivotBlockOffset // 64
  private val Margin = BigInt(50) // SNAPSyncController's SnapServeWindowMargin
  private val PathHeal =
    SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true, storageScheme = StorageScheme.Path)

  private def clHint(f: SnapControllerFixture, snap: ActorRef[SNAPSyncController.Command], head: BigInt): Unit =
    val header = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(head))
    snap ! SNAPSyncController.CLPivotHint(header.hash.value, Some(header))
    f.awaitProcessed(snap)

  /** Everything the parent receives within `quiet` of silence. */
  private def drainParent(
      f: SnapControllerFixture,
      quiet: FiniteDuration = 500.millis
  ): Seq[SyncProtocol.SyncControllerReply] =
    val got = mutable.ArrayBuffer.empty[SyncProtocol.SyncControllerReply]
    var more = true
    while more do
      try
        got += f.parent.receiveMessage(quiet)
        ()
      catch case _: AssertionError => more = false
    got.toSeq

  private def isTerminal(m: SyncProtocol.SyncControllerReply): Boolean = m match
    case SyncProtocol.HealingImpossible          => true
    case _: SNAPSyncController.SnapSyncFinalized => true
    case _                                       => false

  // ── Path scheme: is the pivot root stored anywhere the publish can find it? ─────────────────────

  "FinalizationPolicy.pathPivotRootAbsent" should
    "report the root absent when neither the empty path nor the hash-keyed store holds it" taggedAs UnitTest in {
      val ds = EphemDataSource()
      val pathNodes = new PathNodeStorage(ds)
      val hashStore = StateStorage.createTestStateStorage(ds)._1.getBackingStorage(BigInt(0))
      val trie = PathHealPresenceFixtures.accountTrieNew()

      FinalizationPolicy.pathPivotRootAbsent(pathNodes, trie.root.hash, hashStore) shouldBe true
    }

  it should "report the root present when its node is at the empty path" taggedAs UnitTest in {
    val ds = EphemDataSource()
    val pathNodes = new PathNodeStorage(ds)
    val hashStore = StateStorage.createTestStateStorage(ds)._1.getBackingStorage(BigInt(0))
    val trie = PathHealPresenceFixtures.accountTrieNew()
    PathHealPresenceFixtures.seedPath(pathNodes, trie.nodes)

    FinalizationPolicy.pathPivotRootAbsent(pathNodes, trie.root.hash, hashStore) shouldBe false
  }

  it should "report the root absent when the empty path holds an older root (the #1555 state)" taggedAs UnitTest in {
    val ds = EphemDataSource()
    val pathNodes = new PathNodeStorage(ds)
    val hashStore = StateStorage.createTestStateStorage(ds)._1.getBackingStorage(BigInt(0))
    val oldTrie = PathHealPresenceFixtures.accountTrieOld()
    val newTrie = PathHealPresenceFixtures.accountTrieNew()
    oldTrie.root.hash should not be newTrie.root.hash
    PathHealPresenceFixtures.seedPath(pathNodes, oldTrie.nodes)

    FinalizationPolicy.pathPivotRootAbsent(pathNodes, newTrie.root.hash, hashStore) shouldBe true
  }

  it should "report the root present when only the hash-keyed store holds it" taggedAs UnitTest in {
    val ds = EphemDataSource()
    val pathNodes = new PathNodeStorage(ds)
    val hashStore = StateStorage.createTestStateStorage(ds)._1.getBackingStorage(BigInt(0))
    val trie = PathHealPresenceFixtures.accountTrieNew()
    hashStore.storeRawNodes(Seq(trie.root.hash -> trie.root.encoded.toArray))
    hashStore.persist()

    FinalizationPolicy.pathPivotRootAbsent(pathNodes, trie.root.hash, hashStore) shouldBe false
  }

  // ── (b) 1: no lazy hand-off while the Path pivot root is absent ─────────────────────────────────

  "SNAPSyncController under the Path scheme" should
    "keep healing instead of handing off when the re-peg budget runs out and the pivot root is absent" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      // Pivot root Root (0x11...) is stored nowhere: the Path stores are empty.
      val snap = f.enterStateHealing(PathHeal, isPoS = true)
      // CL stalled at pivot + margin and no snap peer: no re-peg target exists, so every stateless report counts.
      clHint(f, snap, Pivot + Margin)

      Using.resource(new SnapLogCapture) { log =>
        (1 to 10).foreach { _ =>
          snap ! SNAPSyncController.HealingAllPeersStateless
          f.awaitProcessed(snap)
        }
        log.awaitLine("so the lazy hand-off could not publish it. NOT handing off")
        log.contains("[SNAP-COMPLETE]") shouldBe false
        log.contains("[PATH-PUBLISH] publishing") shouldBe false
      }
      drainParent(f).filter(isTerminal) shouldBe empty
    }

  it should "still take the lazy hand-off when the budget runs out and the pivot root is stored" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val trie = PathHealPresenceFixtures.accountTrieNew()
    // The node at the empty path hashes to the pivot root: the hand-off is not blocked.
    PathHealPresenceFixtures.seedPath(
      new PathNodeStorage(f.storagesInstance.storages.flatSlotStorage.dataSource),
      trie.nodes
    )
    val snap = f.enterStateHealing(PathHeal, root = trie.root.hash, isPoS = true)
    clHint(f, snap, Pivot + Margin)

    Using.resource(new SnapLogCapture) { log =>
      (1 to 10).foreach { _ =>
        snap ! SNAPSyncController.HealingAllPeersStateless
        f.awaitProcessed(snap)
      }
      log.awaitLine("[SNAP-COMPLETE]")
      log.contains("NOT handing off") shouldBe false
    }
  }

  it should "not start the Path publish when finalization is reached with the pivot root absent" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val snap = f.enterStateHealing(PathHeal)

    Using.resource(new SnapLogCapture) { log =>
      snap ! SNAPSyncController.StateHealingAbandoned
      f.awaitProcessed(snap)
      drainParent(f).filter(isTerminal) shouldBe Seq(SyncProtocol.HealingImpossible)
      log.contains("[PATH-PUBLISH] not publishing") shouldBe true
      log.contains("[PATH-PUBLISH] publishing") shouldBe false
    }
  }

  // ── (b) 2: the HealingImpossible restart does not heal at the failed pivot (PoS) ─────────────────

  private def startRecovery(
      f: SnapControllerFixture,
      isPoS: Boolean,
      failed: Option[BigInt],
      clHead: Option[BigInt]
  ): ActorRef[SNAPSyncController.Command] =
    f.storeGenesis()
    f.storeHeaderAt(Pivot, Root)
    f.seedAllComplete(Pivot, Root)
    val snap = f.spawnController(PathHeal, isPoS = isPoS, failedFinalizationPivot = failed)
    // SyncController primes the buffered CL head before Start.
    clHead.foreach(h => clHint(f, snap, h))
    snap ! SNAPSyncController.Start
    f.awaitProcessed(snap)
    snap

  private def healingSpawns(f: SnapControllerFixture): Seq[ChildSpawn.Healing] =
    val got = mutable.ArrayBuffer.empty[ChildSpawn]
    var more = true
    while more do
      try
        got += f.spawns.receiveMessage(300.millis)
        ()
      catch case _: AssertionError => more = false
    got.collect { case h: ChildSpawn.Healing => h }.toSeq

  "SNAPSyncController's recovery after a failed finalization (PoS)" should
    "heal at clHead - margin, not at the failed pivot, when the CL head is frozen at pivot + offset (#1555)" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      startRecovery(f, isPoS = true, failed = Some(Pivot), clHead = Some(Pivot + PivotOffset))

      val target = Pivot + PivotOffset - Margin
      drainParent(f) should contain(SNAPSyncController.StartRegularSyncBootstrap(target))
      healingSpawns(f) shouldBe empty
      f.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(target)
    }

  it should "heal at the normal CL target when the CL head has advanced" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    startRecovery(f, isPoS = true, failed = Some(Pivot), clHead = Some(Pivot + 200))

    drainParent(f) should contain(SNAPSyncController.StartRegularSyncBootstrap(Pivot + 200 - PivotOffset))
    healingSpawns(f) shouldBe empty
  }

  it should "wait (no heal, no bootstrap) while no pivot newer than the failed one exists" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    // CL head at pivot + margin: target pivot - 14, fallback pivot; no snap peer.
    Using.resource(new SnapLogCapture) { log =>
      startRecovery(f, isPoS = true, failed = Some(Pivot), clHead = Some(Pivot + Margin))
      log.awaitLine("no newer pivot is available yet")
    }
    drainParent(f).collect { case m: SNAPSyncController.StartRegularSyncBootstrap => m } shouldBe empty
    healingSpawns(f) shouldBe empty
  }

  it should "resume healing on the saved pivot when it is not the failed one and is fresh" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    startRecovery(f, isPoS = true, failed = Some(Pivot - 100), clHead = Some(Pivot + PivotOffset))

    healingSpawns(f).map(_.stateRoot) shouldBe Seq(Root)
  }

  it should "heal at a newer pivot when the CL head shows the saved pivot past maxPivotStalenessBlocks" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val clHead = Pivot + SNAPSyncConfig().maxPivotStalenessBlocks + 100
    startRecovery(f, isPoS = true, failed = None, clHead = Some(clHead))

    drainParent(f) should contain(SNAPSyncController.StartRegularSyncBootstrap(clHead - PivotOffset))
    healingSpawns(f) shouldBe empty
  }

  "SNAPSyncController's recovery after a failed finalization (ETC / pre-merge)" should
    "resume healing on the saved pivot, unchanged" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      startRecovery(f, isPoS = false, failed = Some(Pivot), clHead = None)

      healingSpawns(f).map(_.stateRoot) shouldBe Seq(Root)
      drainParent(f).collect { case m: SNAPSyncController.StartRegularSyncBootstrap => m } shouldBe empty
    }
