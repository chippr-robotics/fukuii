package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.ByteCodeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.StorageRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 016 T023: characterization of the in-place pivot refresh (`refreshPivotInPlace` →
  * `completePivotRefreshWithStateRoot`) as it behaves today. M10 must keep it.
  *
  * ETC variant (Hash scheme, pre-merge): the controller is in `AccountRangeSync` at a network pivot, with the account,
  * bytecode and storage children and the ChainDownloader. ETH variant (Path scheme, PoS, CL pivot hint): the controller
  * is in `StateHealing` with the healing child and the ChainDownloader. Children's messages are read up to a fence (see
  * `SnapControllerFixture`).
  */
class SNAPPivotRefreshCharacterizationSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:
  import SnapControllerFixture.*

  private val Offset = SNAPSyncConfig().pivotBlockOffset

  private def expectBootstrapRequest(f: SnapControllerFixture, target: BigInt): Unit =
    f.parent.fishForMessage(10.seconds) {
      case SNAPSyncController.StartRegularSyncBootstrap(`target`) => FishingOutcomes.complete
      case _                                                      => FishingOutcomes.continueAndIgnore
    }
    ()

  /** The refresh messages the three download children received up to a fence. */
  private def downloadChildRefreshes(
      f: SnapControllerFixture,
      snap: ActorRef[SNAPSyncController.Command]
  ): (Seq[AccountRangeCoordinator.Command], Seq[StorageRangeCoordinator.Command], Seq[ByteCodeCoordinator.Command]) =
    val id = f.sendFence(snap)
    (
      f.accountBefore(id).collect { case m: AccountRangeCoordinator.PivotRefreshed => m },
      f.storageBefore(id).collect { case m: StorageRangeCoordinator.StoragePivotRefreshed => m },
      f.byteCodeBefore(id).filter(_ == ByteCodeCoordinator.ByteCodePivotRefreshed)
    )

  // ── ETC / Hash ─────────────────────────────────────────────────────────────────────────────────

  "SNAPSyncController pivot refresh (ETC, Hash scheme)" should
    "pause the chain download and bootstrap a missing header, then notify every child, resume the chain download " +
    "and persist the new pivot and root" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val entry = f.enterAccountSync(height = 1_000)
      val snap = entry.snap
      val oldPivot = entry.header.number.value
      f.pollWith(snap, f.peersAt(height = 1_200))
      downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))

      // A peer-scarcity unservable report refreshes in place (not counted toward restart).
      snap ! SNAPSyncController.PivotStateUnservable(
        entry.header.stateRoot.value,
        "s0d",
        3,
        SNAPSyncController.UnservableCause.PeerScarcity
      )
      val newPivot = BigInt(1_200) - Offset
      f.fishFor(f.chainInbox)(_ == ChainDownloader.Pause)
      expectBootstrapRequest(f, newPivot)
      downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil)) // nothing until the header arrives
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(oldPivot)

      val newRoot = root(0x44)
      snap ! SNAPSyncController.BootstrapComplete(Some(f.headerAt(newPivot, newRoot)))
      f.awaitProcessed(snap)
      downloadChildRefreshes(f, snap) shouldBe ((
        Seq(AccountRangeCoordinator.PivotRefreshed(newRoot)),
        Seq(StorageRangeCoordinator.StoragePivotRefreshed(newRoot)),
        Seq(ByteCodeCoordinator.ByteCodePivotRefreshed)
      ))
      f.fishFor(f.chainInbox)(_ == ChainDownloader.UpdateTarget(newPivot))
      f.chainInbox.expectMessage(ChainDownloader.Resume)
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(newPivot)
      f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(newRoot)
      f.appStateStorage.getBestBlockNumber() shouldBe newPivot
    }

  /** Proactive roll: a stagnation tick sees the pivot more than 100 blocks behind the network head. The new pivot's
    * header is local, so the refresh goes straight to the readiness probe. Returns the probe's request id.
    */
  private def startProbedRoll(
      f: SnapControllerFixture,
      snap: ActorRef[SNAPSyncController.Command]
  ): (BigInt, ByteString) =
    val newPivot = BigInt(1_200) - Offset
    val newRoot = root(0x55)
    f.storeHeaderAt(newPivot, newRoot)
    f.pollWith(snap, f.peersAt(height = 1_200))
    downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))
    snap ! SNAPSyncController.CheckDownloadStagnation
    val probe = f.fishFor(f.npm) {
      case NetworkPeerManagerActor.SendMessageCmd(_: GetAccountRangeEnc, _) => true
      case _                                                                => false
    }
    val request = probe match
      case NetworkPeerManagerActor.SendMessageCmd(enc: GetAccountRangeEnc, _) => enc.underlyingMsg
      case other                                                              => fail(s"not a probe: $other")
    request.rootHash shouldBe newRoot
    // Deferred: no child is told until the probe answers.
    downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))
    (request.requestId, newRoot)

  it should "commit a proactive roll when the readiness probe returns accounts" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(height = 1_000)
    val snap = entry.snap
    val (requestId, newRoot) = startProbedRoll(f, snap)
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(entry.header.number.value)

    val accounts = Seq(ByteString(Array.fill(32)(0x01.toByte)) -> Account.empty())
    snap ! SNAPSyncController.AccountRangeResponse(SNAP.AccountRange(requestId, accounts, Seq.empty))
    f.awaitProcessed(snap)
    downloadChildRefreshes(f, snap) shouldBe ((
      Seq(AccountRangeCoordinator.PivotRefreshed(newRoot)),
      Seq(StorageRangeCoordinator.StoragePivotRefreshed(newRoot)),
      Seq(ByteCodeCoordinator.ByteCodePivotRefreshed)
    ))
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(BigInt(1_200) - Offset)
    f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(newRoot)
  }

  it should "abort a proactive roll whose readiness probe times out (15 s), keeping the old pivot" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val entry = f.enterAccountSync(height = 1_000)
    val snap = entry.snap
    startProbedRoll(f, snap)
    Using.resource(new SnapLogCapture) { log =>
      f.manualTime.timePasses(15.seconds)
      f.awaitProcessed(snap)
      log.contains("[PIVOT-PROBE] Probe timed out (attempt 1/") shouldBe true
    }
    downloadChildRefreshes(f, snap) shouldBe ((Nil, Nil, Nil))
    f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(entry.header.number.value)
    f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(entry.header.stateRoot.value)
  }

  // ── ETH / Path, PoS, CL pivot hint ─────────────────────────────────────────────────────────────

  "SNAPSyncController pivot refresh (ETH, Path scheme, CL hint)" should
    "re-peg healing on the CL head, extend the chain download, and leave the persisted anchor alone during healing" taggedAs UnitTest in {
      val f = new SnapControllerFixture(testKit)
      val pivot0 = BigInt(2_000)
      val root0 = root(0x66)
      val snap = f.enterStateHealing(
        SNAPSyncConfig(deferredMerkleization = false, movingRootDeltaHeal = true, storageScheme = StorageScheme.Path),
        pivot = pivot0,
        root = root0,
        isPoS = true
      )
      val healing0 = f.healingBefore(f.sendFence(snap))
      healing0.collect { case m: TrieNodeHealingCoordinator.HealingPivotRefreshed => m } shouldBe empty

      // A post-merge-shaped header for the CL-anchored pivot, so the PoS header gate accepts it.
      val pivot1 = BigInt(2_100)
      val root1 = root(0x77)
      val posHeader = Fixtures.Blocks.Genesis.header.copy(
        number = BlockNumber(pivot1),
        stateRoot = TrieRoot(root1),
        difficulty = Difficulty.Zero,
        nonce = ByteString(new Array[Byte](8)),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        extraFields = BlockHeader.HeaderExtraFields.HefEmpty
      )
      f.blockchainWriter.storeBlock(Block(posHeader, BlockBody.empty)).commit()
      val clHead = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(pivot1 + Offset))
      snap ! SNAPSyncController.CLPivotHint(clHead.hash.value, Some(clHead))
      snap ! SNAPSyncController.HealingAllPeersStateless
      f.awaitProcessed(snap)

      f.healingBefore(f.sendFence(snap)).collect { case m: TrieNodeHealingCoordinator.HealingPivotRefreshed =>
        m
      } shouldBe Seq(TrieNodeHealingCoordinator.HealingPivotRefreshed(root1))
      f.fishFor(f.chainInbox)(_ == ChainDownloader.UpdateTarget(pivot1))
      f.chainInbox.expectMessage(ChainDownloader.Resume)
      // BUG-006 guard: no anchor write while healing; the self-reported best block does move.
      f.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(pivot0)
      f.appStateStorage.getSnapSyncStateRoot() shouldBe Some(root0)
      f.appStateStorage.getBestBlockNumber() shouldBe pivot1
    }
