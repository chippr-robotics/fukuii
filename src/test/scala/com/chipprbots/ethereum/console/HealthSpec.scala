package com.chipprbots.ethereum.console

import java.time.Duration

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The health model (spec 029 FR-045, SC-011, T084): every alert rule as a check; a bar in warning or error exactly
  * when the alert row is; the room left 1 at the healthy end and 0 at the threshold.
  */
class HealthSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val t0 = TuiFixtures.Now
  private val GiB = 1024L * 1024 * 1024

  private def check(s: TuiState, subsystem: Subsystem, name: String): HealthCheck =
    Health.checks(s).find(c => c.subsystem == subsystem && c.name == name).value

  private def bar(s: TuiState, subsystem: Subsystem): HealthBar = s.healthBars.find(_.subsystem == subsystem).value

  private def withLocal(s: TuiState, f: LocalSample => LocalSample): TuiState = TuiFixtures.withLocal(s, f)

  private val fixtures: Seq[(String, GlyphSet => TuiState)] =
    TuiFixtures.all ++ TuiFixtures.alerts ++ Seq[(String, GlyphSet => TuiState)](
      "resources" -> (g => TuiFixtures.resourcesEtc(g)),
      "busy" -> (g => TuiFixtures.resourcesSepolia(g)),
      "pressure" -> (g => TuiFixtures.pressureEtc(g)),
      "cl-disconnected" -> (g => TuiFixtures.clDisconnectedSepolia(g)),
      "cl-waiting" -> (g => TuiFixtures.clWaitingSepolia(g)),
      "no-hashrate" -> (g => TuiFixtures.noHashrateEtc(g))
    )

  "The alert row" should "be the checks' alerts, errors first, on every fixture" taggedAs (UnitTest) in {
    for (name, fixture) <- fixtures do
      val s = fixture(GlyphSet.Emoji)
      withClue(s"$name: ") {
        val raised = Health.checks(s).flatMap(_.alert)
        Alerts.of(s) shouldBe raised.filter(_.severity == Severity.Error) ++ raised.filter(_.severity == Severity.Warn)
      }
  }

  "A bar" should "be in warning or error exactly when one of its checks raises that alert" taggedAs (UnitTest) in {
    for (name, fixture) <- fixtures do
      val s = fixture(GlyphSet.Emoji)
      for b <- s.healthBars do
        withClue(s"$name, ${b.subsystem}: ") {
          val severities = b.checks.flatMap(_.alert).map(_.severity)
          (b.state == CheckState.Error) shouldBe severities.contains(Severity.Error)
          (b.state == CheckState.Warn) shouldBe (severities.nonEmpty && !severities.contains(Severity.Error))
          // The check it names is one of its own, and raises the alert when one is raised.
          b.checks should contain(b.limiting.value)
          if severities.nonEmpty then
            b.limiting.value.alert shouldBe defined
            b.headroom shouldBe Some(0.0)
          b.headroom.foreach(room => room should ((be >= 0.0).and(be <= 1.0)))
        }
  }

  it should "exist for the consensus client on an ETH node and for mining on a mining ETC node, never both" taggedAs (
    UnitTest
  ) in {
    val eth = TuiFixtures.resourcesSepolia().healthBars.map(_.subsystem)
    eth should contain(Subsystem.ClLink)
    eth should not contain Subsystem.Mining
    val etc = TuiFixtures.resourcesEtc().healthBars.map(_.subsystem)
    etc should contain(Subsystem.Mining)
    etc should not contain Subsystem.ClLink
    // A node that does not mine has no mining bar.
    TuiFixtures.syncedEtc().healthBars.map(_.subsystem) should not contain Subsystem.Mining
  }

  it should "have no bar for what was not read, and one as soon as it is" taggedAs (UnitTest) in {
    val bare = TuiFixtures.syncedEtc().healthBars.map(_.subsystem)
    bare should contain.allOf(Subsystem.P2p, Subsystem.Chain, Subsystem.Disk)
    bare should contain.noneOf(Subsystem.Database, Subsystem.Memory, Subsystem.Rpc)
    TuiFixtures.resourcesEtc().healthBars.map(_.subsystem) should contain.allOf(
      Subsystem.Database,
      Subsystem.Memory,
      Subsystem.Rpc
    )
  }

  it should "not exist before the node has been read: no bar is drawn from nothing" taggedAs (UnitTest) in {
    val unread = TuiState(maxPeers = 50, startTime = t0, now = t0)
    unread.healthBars shouldBe empty
    // The checks there are say they do not know, and raise nothing.
    Health.checks(unread).map(_.state).toSet shouldBe Set(CheckState.Unknown)
    Alerts.of(unread) shouldBe empty
  }

  "A source that missed the last poll" should "not be said to be answering, and raise nothing yet" taggedAs (UnitTest) in {
    val s = TuiFixtures.syncedEtc()
    val stale = TuiFixtures.withPeers(s, Sourced.Stale(TuiFixtures.peerList(41), t0.minusSeconds(2)))
    val manager = check(stale, Subsystem.P2p, "peer manager")
    manager.value shouldBe "last answered 2s ago"
    manager.alert shouldBe None
    check(s, Subsystem.P2p, "peer manager").value shouldBe "answering"
  }

  "The last minute" should "be covered only by enough readings, across it, the last of them recent" taggedAs (UnitTest) in {
    def every(seconds: Long, count: Int, lastAgo: Long = 0): Seq[java.time.Instant] =
      (0 until count).map(i => t0.minusSeconds(lastAgo + (count - 1 - i) * seconds))
    Health.covered(every(5, 13), t0) shouldBe true
    // Under a minute of watching.
    Health.covered(every(5, 10), t0) shouldBe false
    // Two readings a minute apart say nothing of the minute between them.
    Health.covered(every(60, 2), t0) shouldBe false
    // A dashboard polling every fifteen seconds has just enough; every twenty, too few.
    Health.covered(every(15, 5), t0) shouldBe true
    Health.covered(every(20, 4), t0) shouldBe true
    Health.covered(every(30, 3), t0) shouldBe false
    // The reader stalled: the newest reading is old.
    Health.covered(every(5, 13, lastAgo = 21), t0) shouldBe false
    Health.covered(Nil, t0) shouldBe false
  }

  "The heap and the collector" should "not be judged across a gap the reader left" taggedAs (UnitTest) in {
    val whole = TuiFixtures.systemSample(heapFloor = 0.97, gc = 0.4)
    // Only two readings of the last minute survive, a second apart, at its far end.
    val gapped = whole.copy(history = whole.history.dropRight(13) ++ whole.history.takeRight(1))
    val s = TuiFixtures.withResources(TuiFixtures.syncedEtc(), system = gapped)
    check(s, Subsystem.Memory, "heap").state shouldBe CheckState.Unknown
    check(s, Subsystem.Memory, "garbage collection").state shouldBe CheckState.Unknown
    Alerts.of(s) shouldBe empty
  }

  // ---- the room left: 1 at the healthy end, 0 at the threshold ------------------------------------------------------

  "The disk's room" should "be 1 with the whole disk free, 0 at the warning, and none when the disk's size is not known" taggedAs (
    UnitTest
  ) in {
    def disk(free: Long, total: Option[Long] = Some(1_020 * GiB)): HealthCheck =
      check(
        withLocal(TuiFixtures.syncedEtc(), _.copy(diskFree = Some(free), diskTotal = total)),
        Subsystem.Disk,
        "free space"
      )
    disk(1_020 * GiB).room.value shouldBe 1.0
    disk(520 * GiB).room.value shouldBe 0.5 +- 0.0001
    disk(20 * GiB).room.value shouldBe 0.0
    disk(20 * GiB).state shouldBe CheckState.Ok
    disk(20 * GiB - 1).state shouldBe CheckState.Warn
    disk(20 * GiB - 1).room shouldBe Some(0.0)
    disk(5 * GiB - 1).state shouldBe CheckState.Error
    // The rule still judges a disk whose size is not known; the bar has no length to draw.
    disk(400 * GiB, None).room shouldBe None
    disk(400 * GiB, None).state shouldBe CheckState.Ok
    disk(4 * GiB, None).state shouldBe CheckState.Error
    disk(4 * GiB, None).room shouldBe Some(0.0)
    disk(388 * GiB).value shouldBe "388G free of 1020G"
    disk(388 * GiB).threshold shouldBe "warn below 20G, error below 5G"
  }

  "The peer count's room" should "be 1 at the limit and 0 at the warning's share of it" taggedAs (UnitTest) in {
    def peers(n: Int): HealthCheck =
      val s = TuiFixtures.syncedEtc()
      check(
        TuiFixtures.withPeers(s, Sourced.Fresh(TuiFixtures.peerList(n), t0)).copy(peerCount = n),
        Subsystem.P2p,
        "peer count"
      )
    peers(50).room.value shouldBe 1.0
    // The warning begins under 12.5 of 50.
    peers(13).state shouldBe CheckState.Ok
    peers(13).room.value shouldBe ((13 - 12.5) / (50 - 12.5)) +- 0.0001
    peers(12).state shouldBe CheckState.Warn
    peers(12).room shouldBe Some(0.0)
    // With none connected the count's rule does not apply: the 0-peers rule's clock is the measure.
    peers(0).alert shouldBe None
    peers(0).room shouldBe None
  }

  "A time limit's room" should "run from 1 to 0 as the limit is used up" taggedAs (UnitTest) in {
    def silentFor(seconds: Long): HealthCheck =
      val s = TuiState(maxPeers = 50, startTime = t0, now = t0)
        .withStatus(TuiFixtures.snapshot(current = 100, best = 100, now = t0, peers = 0))
        .withStatus(TuiFixtures.snapshot(current = 100, best = 100, now = t0.plusSeconds(seconds), peers = 0))
      check(s, Subsystem.P2p, "peers connected")
    silentFor(0).room.value shouldBe 1.0
    silentFor(15).room.value shouldBe 0.5 +- 0.0001
    silentFor(30).room.value shouldBe 0.0
    silentFor(30).state shouldBe CheckState.Ok
    silentFor(31).state shouldBe CheckState.Error
    silentFor(31).value shouldBe "none for 31s"
    silentFor(31).threshold shouldBe "error after 30s with none"
  }

  // ---- the four checks new with spec 029 ----------------------------------------------------------------------------

  "The heap check" should "warn only when the heap never fell under the limit's share for a minute" taggedAs (
    UnitTest
  ) in {
    def heap(floor: Double, points: Int = 121): HealthCheck =
      check(
        TuiFixtures.withResources(TuiFixtures.syncedEtc(), system = TuiFixtures.systemSample(floor, points = points)),
        Subsystem.Memory,
        "heap"
      )
    heap(0.35).state shouldBe CheckState.Ok
    heap(0.35).room.value shouldBe ((0.90 - 0.35) / 0.90) +- 0.001
    heap(0.89).state shouldBe CheckState.Ok
    heap(0.905).state shouldBe CheckState.Warn
    heap(0.93).alert.value shouldBe Alert(
      Severity.Warn,
      "heap above 93% of 8.0G for a minute (warn at 90%)"
    )
    // A heap that touches its limit between collections but falls back raises nothing: the fixture's heap moves
    // eleven points above its floor.
    heap(0.80).state shouldBe CheckState.Ok
    // Watched for under a minute, it is not judged, whatever it reads.
    heap(0.97, points = 5).state shouldBe CheckState.Unknown
    heap(0.97, points = 5).room shouldBe None
  }

  "The collector's check" should "warn at its share of the last minute, and not on one long pause" taggedAs (
    UnitTest
  ) in {
    def gc(share: Double): HealthCheck =
      check(
        TuiFixtures.withResources(TuiFixtures.syncedEtc(), system = TuiFixtures.systemSample(gc = share)),
        Subsystem.Memory,
        "garbage collection"
      )
    gc(0.004).state shouldBe CheckState.Ok
    gc(0.25).state shouldBe CheckState.Warn
    gc(0.25).alert.value.text should startWith("GC pauses 25% of the last minute")
    // One poll's share is the JVM sample's and is not what the rule reads.
    val spike = withLocal(
      TuiFixtures.withResources(TuiFixtures.syncedEtc()),
      l => l.copy(jvm = l.jvm.map(_.copy(gcShare = Some(0.9))))
    )
    check(spike, Subsystem.Memory, "garbage collection").state shouldBe CheckState.Ok
  }

  "The RPC check" should "warn at its share of failed calls, with at least twenty calls to take a share of" taggedAs (
    UnitTest
  ) in {
    def rpc(calls: Long, errors: Long): HealthCheck =
      check(
        TuiFixtures.withResources(
          TuiFixtures.syncedEtc(),
          recent = RpcWindow(calls, errors, Duration.ofSeconds(60))
        ),
        Subsystem.Rpc,
        "failed calls"
      )
    rpc(1_000, 0).room.value shouldBe 1.0
    rpc(1_000, 50).room.value shouldBe 0.5 +- 0.0001
    rpc(1_000, 99).state shouldBe CheckState.Ok
    rpc(1_000, 100).state shouldBe CheckState.Warn
    rpc(1_000, 100).alert.value.text shouldBe "RPC 10% of 1,000 calls failed in the last minute"
    // Nineteen calls, all failed: too few to judge, and said.
    rpc(19, 19).state shouldBe CheckState.Ok
    rpc(19, 19).value shouldBe "19 of 19 in the last minute, too few to judge"
    rpc(19, 19).room.value shouldBe 1.0
    rpc(20, 20).state shouldBe CheckState.Warn
    // Before the reader has watched ten seconds there is no window, and no judgement.
    val unwatched = withLocal(
      TuiFixtures.withResources(TuiFixtures.syncedEtc()),
      l => l.copy(rpc = l.rpc.map(_.copy(recent = None)))
    )
    check(unwatched, Subsystem.Rpc, "failed calls").state shouldBe CheckState.Unknown
  }

  "The database check" should "warn when writes are refused, not when they are held, and not on an old reading" taggedAs (
    UnitTest
  ) in {
    def writes(storage: StorageSample, thresholds: AlertThresholds = AlertThresholds.Default): HealthCheck =
      check(
        TuiFixtures.withResources(TuiFixtures.syncedEtc(), storage = storage).copy(thresholds = thresholds),
        Subsystem.Database,
        "writes"
      )
    val quiet = TuiFixtures.storageSample
    writes(quiet).state shouldBe CheckState.Ok
    writes(quiet).room.value shouldBe 1.0
    // Held is the database catching up: said, and no alert.
    writes(TuiFixtures.storageBusy).state shouldBe CheckState.Ok
    writes(TuiFixtures.storageBusy).value shouldBe "accepted, held to 16M/s"
    val refused = quiet.copy(writeStopped = Some(true))
    writes(refused).alert.value shouldBe Alert(
      Severity.Warn,
      "database refuses writes until flush and compaction catch up"
    )
    // The operator turned the alert off: the check says what it reads and raises nothing.
    val off = writes(refused, AlertThresholds.Default.copy(writeStall = false))
    off.alert shouldBe None
    off.value shouldBe "refused, flush and compaction behind"
    off.threshold should include("write-stall is off")
    // A reading that said "accepted" before the database went busy says nothing of now.
    val old = writes(quiet.copy(at = t0.minusSeconds(31)))
    old.state shouldBe CheckState.Unknown
    old.alert shouldBe None
    old.room shouldBe None
    old.value should startWith("not read for 31s")
    // One that said "refused" keeps its alert: a database that refuses writes holds its writers, and the reading
    // gives way to them, so that reading may be the last for as long as it refuses.
    val held = writes(refused.copy(at = t0.minusSeconds(95)))
    held.alert.value shouldBe Alert(
      Severity.Warn,
      "database refused writes 1m 35s ago and has not been read since"
    )
    held.value shouldBe "refused at the last reading, 1m 35s ago; not read since"
    held.room shouldBe Some(0.0)
  }

  // ---- thresholds and time ------------------------------------------------------------------------------------------

  "A threshold from the configuration" should "move the check, its words and its room together" taggedAs (UnitTest) in {
    val s = withLocal(TuiFixtures.syncedEtc(), _.copy(diskFree = Some(60 * GiB), diskTotal = Some(1_000 * GiB)))
    check(s, Subsystem.Disk, "free space").state shouldBe CheckState.Ok
    val strict = s.copy(thresholds = AlertThresholds.Default.copy(diskWarnBytes = 100 * GiB, diskErrorBytes = 50 * GiB))
    val moved = check(strict, Subsystem.Disk, "free space")
    moved.state shouldBe CheckState.Warn
    moved.threshold shouldBe "warn below 100G, error below 50G"
    Alerts.of(strict).map(_.text) shouldBe Seq("disk 60.0G free (warn < 100G)")
  }

  "The time a check has been in its state" should "run from the poll that first saw it, and start again when it changes" taggedAs (
    UnitTest
  ) in {
    def poll(s: TuiState, seconds: Long, free: Long): TuiState =
      val snapshot = TuiFixtures.snapshot(current = 100, best = 100, now = t0.plusSeconds(seconds))
      s.withStatus(snapshot.copy(local = snapshot.local.copy(diskFree = Some(free), diskTotal = Some(1_000 * GiB))))
    val key = s"${Subsystem.Disk.label}/free space"
    val first = poll(TuiState(maxPeers = 50, startTime = t0, now = t0), 0, 400 * GiB)
    first.healthSince(key) shouldBe (CheckState.Ok -> t0)
    val still = poll(first, 60, 300 * GiB)
    still.healthSince(key) shouldBe (CheckState.Ok -> t0)
    val low = poll(still, 90, 10 * GiB)
    low.healthSince(key) shouldBe (CheckState.Warn -> t0.plusSeconds(90))
    val lower = poll(low, 120, 4 * GiB)
    lower.healthSince(key) shouldBe (CheckState.Error -> t0.plusSeconds(120))
    val back = poll(lower, 150, 400 * GiB)
    back.healthSince(key) shouldBe (CheckState.Ok -> t0.plusSeconds(150))
    bar(back, Subsystem.Disk).state shouldBe CheckState.Ok
  }
