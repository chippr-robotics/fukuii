package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.testing.Tags.*

/** spec 022 T050a (hunter N4): a coordinator whose supervisor has run out of restarts. Before this the controller did
  * not see it, and the phase hung. Each child here really fails, under the spawn sites' real supervision, on the
  * fixture's manual clock.
  */
class SNAPCoordinatorGoneSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  private def fixture(dying: String*): SnapControllerFixture =
    val f = new SnapControllerFixture(testKit)
    f.dying.set(dying.toSet)
    f.appStateStorage.stampSnapHealInvariant().commit()
    f

  /** The clock runs on until `line` is logged: the child's ticks fail it, and each restart replays what waited. */
  private def runUntil(f: SnapControllerFixture, snap: ActorRef[Command], log: SnapLogCapture, line: String): Unit =
    var seconds = 0
    while !log.contains(line) && seconds < 120 do
      f.manualTime.timePasses(1.second)
      f.awaitProcessed(snap)
      seconds += 1
    withClue(s"after $seconds s of the clock, in the log: ${log.lines.takeRight(5)}: ") {
      log.contains(line) shouldBe true
    }

  private val Dormant = "Entering dormant mode"

  private def lostTrack(f: SnapControllerFixture, name: String) =
    f.appStateStorage.hasSnapHealInvariant() shouldBe false
    f.appStateStorage.getSnapCodeScanOwed().get should include(s"the $name coordinator stopped")

  "A storage coordinator out of restarts" should "withdraw the marker, owe the scan, go dormant, and wake with the phase flags as they were" taggedAs UnitTest in {
    val f = fixture(ChildStopped.Storage)
    Using.resource(new SnapLogCapture) { log =>
      val snap = f.enterStorageResume()
      f.pollWith(snap, f.peersAt(height = 1_000))
      runUntil(f, snap, log, Dormant)
      lostTrack(f, "storage")
      withClue("no phase flag moved: the wake resumes from disk: ") {
        f.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
        f.appStateStorage.isSnapSyncStorageComplete() shouldBe false
      }

      f.dying.set(Set.empty)
      log.clear()
      f.pollWith(snap, f.peersAt(height = 2_000))
      snap ! DormantWakeUp
      log.awaitLine("Waking from dormant mode")
      f.awaitProcessed(snap)
      withClue("the debt stands until a finalisation: ")(f.appStateStorage.getSnapCodeScanOwed() shouldBe defined)
    }
  }

  "A bytecode coordinator out of restarts" should "withdraw the marker, owe the scan, and go dormant while its phase is open" taggedAs UnitTest in {
    val f = fixture(ChildStopped.ByteCode)
    Using.resource(new SnapLogCapture) { log =>
      val snap = f.enterStorageAndByteCodeResume()
      f.pollWith(snap, f.peersAt(height = 1_000))
      runUntil(f, snap, log, Dormant)
      lostTrack(f, "bytecode")
    }
  }

  "An account coordinator out of restarts" should "withdraw the marker, owe the scan, and go dormant" taggedAs UnitTest in {
    val f = fixture(ChildStopped.Account)
    Using.resource(new SnapLogCapture) { log =>
      val snap = f.enterGenesisAccountSync()
      runUntil(f, snap, log, Dormant)
      lostTrack(f, "account")
    }
  }

  "A healing coordinator out of restarts" should "withdraw the marker, owe the scan, and go dormant" taggedAs UnitTest in {
    val f = fixture(ChildStopped.Healing)
    Using.resource(new SnapLogCapture) { log =>
      val snap = f.enterStateHealing()
      f.pollWith(snap, f.peersAt(height = 1_000))
      runUntil(f, snap, log, Dormant)
      lostTrack(f, "healing")
    }
  }

  "The bytecode coordinator healing spawned" should "cost the marker and the scan, but not the phase: the next ask spawns another" taggedAs UnitTest in {
    val f = fixture(ChildStopped.ByteCode)
    Using.resource(new SnapLogCapture) { log =>
      val snap = f.enterStateHealing()
      f.pollWith(snap, f.peersAt(height = 1_000))
      val code = ByteString(Array.fill(32)(0x5a.toByte))
      snap ! HealedCodeHashes(Seq(code))
      runUntil(f, snap, log, "the bytecode coordinator stopped")
      lostTrack(f, "bytecode")
      log.contains(Dormant) shouldBe false
      f.factories.byteCodeSpawns.get() shouldBe 1

      f.dying.set(Set.empty)
      snap ! HealedCodeHashes(Seq(code))
      f.awaitProcessed(snap)
      f.factories.byteCodeSpawns.get() shouldBe 2
    }
  }

  "A coordinator stopped on purpose" should "cost nothing: stopAll takes its handle first" taggedAs UnitTest in {
    val f = fixture()
    val snap = f.enterStorageResume()
    snap ! DelayedRestart("spec 022 test")
    f.stops.fishForMessage(10.seconds) {
      case ChildStopped(ChildStopped.Storage) =>
        org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.complete
      case _ => org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes.continueAndIgnore
    }
    f.awaitProcessed(snap)
    f.appStateStorage.getSnapCodeScanOwed() shouldBe None
    f.appStateStorage.hasSnapHealInvariant() shouldBe true
  }

  "An account coordinator that stopped itself" should "cost nothing once accounts are complete: it finalised the trie" taggedAs UnitTest in {
    val f = fixture()
    val snap = f.enterGenesisAccountSync()
    val account = f.factories.selves.get(ChildStopped.Account)
    snap ! AccountRangeSyncComplete
    snap ! CoordinatorGone(account)
    f.awaitProcessed(snap)
    f.appStateStorage.getSnapCodeScanOwed() shouldBe None
    f.appStateStorage.hasSnapHealInvariant() shouldBe true
  }

  "A stale report" should "cost nothing: its handle is gone" taggedAs UnitTest in {
    val f = fixture()
    val snap = f.enterStorageResume()
    snap ! CoordinatorGone(testKit.createTestProbe[Any]().ref)
    f.awaitProcessed(snap)
    f.appStateStorage.getSnapCodeScanOwed() shouldBe None
    f.appStateStorage.hasSnapHealInvariant() shouldBe true
  }

  // hunter 3, as the controller lives it: the account phase ends inside the capability check's grace period.
  "A capability check that outlives a fast account phase" should "not start a second one" taggedAs UnitTest in {
    val f = fixture()
    val snap = f.enterGenesisAccountSync()
    val account = f.factories.selves.get(ChildStopped.Account)
    snap ! AccountRangeSyncComplete
    snap ! CoordinatorGone(account)
    snap ! CheckSnapCapability
    f.awaitProcessed(snap)
    f.factories.accountSpawns.get() shouldBe 1
  }
