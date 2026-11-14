package com.chipprbots.ethereum.ephemery

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

import scala.jdk.CollectionConverters.*

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.ephemery.EphemeryUrls.*
import com.chipprbots.ethereum.testing.Tags.*

/** The fetch of one release (spec 028 R7): the target, the asset URL, the retries inside one budget, the single API
  * fallback, and the validation of what arrives. Every wait runs on a manual clock: nothing here sleeps or reads the
  * system clock.
  */
class ReleaseFetcherSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  // ephemery-165: genesis 2026-10-22T19:00:00Z, reset 2026-11-19T19:00:00Z
  private val genesis165 = Instant.ofEpochSecond(1792695600L)
  private val reset165 = Instant.ofEpochSecond(1795114800L)
  private val interval = Duration.ofSeconds(2419200L)
  private val afterReset = reset165.plusSeconds(60)
  private val Budget = Duration.ofSeconds(60)

  private var scratch: Path = scala.compiletime.uninitialized
  private var clock: ManualClock = scala.compiletime.uninitialized
  private var sleeper: ManualSleeper = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    scratch = Files.createTempDirectory("eph-fetcher")
    stages = 0
    clock = new ManualClock(afterReset)
    sleeper = new ManualSleeper(clock)
  override def afterEach(): Unit = delete(scratch)

  private def delete(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)

  private var stages = 0
  private def newStage(): Path =
    stages += 1
    Files.createDirectory(scratch.resolve(s"stage-$stages"))

  private def fetcher(t: ReleaseTransport, base: String = Base, budget: Duration = Budget) =
    new ReleaseFetcher(t, clock, sleeper, new URI(base), budget)

  private lazy val inputs166 = SyntheticRelease.next(raw(165))
  private lazy val inputs167 = SyntheticRelease.next(inputs166)
  private lazy val archive166 = Reply.Body(SyntheticRelease.archive(inputs166))
  private lazy val archive167 = Reply.Body(SyntheticRelease.archive(inputs167))

  private def entry(iteration: Int): ReleaseEntry =
    val i = iteration match
      case 165 => raw(165)
      case 166 => inputs166
    ReleaseEntry.of(EphemeryRelease.validate(i).fold(r => fail(r.message), identity), "/x", None, "test", afterReset)

  private def refusal[A](r: Either[ReleaseRefusal, A]): ReleaseRefusal = r match
    case Left(x)  => x
    case Right(_) => fail("expected a refusal")

  private def fetched(r: Either[ReleaseRefusal, ReleaseFetcher.Fetched]): ReleaseFetcher.Fetched = r match
    case Right(f)  => f
    case Left(why) => fail(s"expected a release, got: ${why.message}")

  // ---- the target ---------------------------------------------------------------------------------------------------

  "the target" should "be the iteration whose window contains now, counted from the newest known one" taggedAs UnitTest in {
    val e165 = entry(165)
    ReleaseFetcher.targetIteration(e165, reset165) shouldBe 166
    ReleaseFetcher.targetIteration(e165, reset165.minusSeconds(1)) shouldBe 165
    ReleaseFetcher.targetIteration(e165, reset165.plusSeconds(interval.getSeconds - 1)) shouldBe 166
    ReleaseFetcher.targetIteration(e165, reset165.plusSeconds(interval.getSeconds)) shouldBe 167
  }

  it should "skip the iterations in between when several resets have passed" taggedAs UnitTest in {
    ReleaseFetcher.targetIteration(entry(165), genesis165.plus(interval.multipliedBy(4)).plusSeconds(5)) shouldBe 169
  }

  it should "stay on the newest known iteration before its window opens" taggedAs UnitTest in {
    ReleaseFetcher.targetIteration(entry(165), genesis165.minusSeconds(100)) shouldBe 165
  }

  // ---- the request --------------------------------------------------------------------------------------------------

  "fetchCurrent" should "ask the asset URL of the target, with an absolute deadline inside the budget" taggedAs UnitTest in {
    val t = new FakeTransport(clock).on(asset(166), archive166)
    val start = clock.instant()
    val f = fetched(fetcher(t).fetchCurrent(166, () => newStage()))
    f.release.release shouldBe "ephemery-166"
    t.calls.map(_.url) shouldBe List(asset(166))
    t.calls.head.maxBytes shouldBe ReleaseFetcher.MaxArchiveBytes
    t.calls.head.deadline.isAfter(start) shouldBe true
    t.calls.head.deadline.isAfter(start.plus(Budget)) shouldBe false
    f.sourceUrl shouldBe s"$Base/releases/tag/ephemery-166"
    (f.archiveSha256 should fullyMatch).regex("[0-9a-f]{64}")
  }

  it should "build the asset URL from a mirror base, with or without a trailing slash" taggedAs UnitTest in {
    val mirror = "https://mirror.example.org/eph"
    val t = new FakeTransport(clock).on(asset(166, mirror), archive166)
    fetched(fetcher(t, base = mirror + "/").fetchCurrent(166, () => newStage()))
    t.calls.map(_.url) shouldBe List(asset(166, mirror))
  }

  it should "keep the EL members in memory and stage the CL ones, without the excluded members" taggedAs UnitTest in {
    val t = new FakeTransport(clock).on(asset(166), archive166)
    val f = fetched(fetcher(t).fetchCurrent(166, () => newStage()))
    def names(p: Path): Set[String] =
      val s = Files.walk(p)
      try s.iterator().asScala.filter(Files.isRegularFile(_)).map(x => p.relativize(x).toString).toSet
      finally s.close()
    val staged = names(f.stage)
    (staged should contain).allOf("config.yaml", "genesis.ssz", "deploy_block.txt", "boot_enr.yaml")
    staged should not contain "parsedBeaconState.json"
    staged.exists(_.startsWith("bootnode-keys")) shouldBe false
    f.release.chainConf should include("genesis-hash")
  }

  // ---- retries, inside one budget -----------------------------------------------------------------------------------

  it should "retry a 404 with 2 s then 4 s of backoff, and then say it is not published yet (69)" taggedAs UnitTest in {
    val t = new FakeTransport(clock)
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x shouldBe ReleaseRefusal.NotYetPublished(166)
    x.exitCode shouldBe 69
    t.calls should have size 3
    sleeper.waits.toList shouldBe List(Duration.ofSeconds(2), Duration.ofSeconds(4))
  }

  it should "accept the release when a 404 turns into the archive within the budget" taggedAs UnitTest in {
    val t = new FakeTransport(clock).on(asset(166), Reply.status(404), archive166)
    fetched(fetcher(t).fetchCurrent(166, () => newStage())).release.release shouldBe "ephemery-166"
    t.calls should have size 2
    sleeper.waits.toList shouldBe List(Duration.ofSeconds(2))
  }

  it should "retry connect errors and 5xx, and succeed on the third attempt" taggedAs UnitTest in {
    val t = new FakeTransport(clock)
      .on(asset(166), Reply.Fail(TransportFailure.Io("connection refused")), Reply.status(503), archive166)
    fetched(fetcher(t).fetchCurrent(166, () => newStage()))
    t.calls should have size 3
    sleeper.waits.toList shouldBe List(Duration.ofSeconds(2), Duration.ofSeconds(4))
  }

  it should "retry a 429 and a timeout" taggedAs UnitTest in {
    val t = new FakeTransport(clock)
      .on(asset(166), Reply.status(429), Reply.Fail(TransportFailure.DeadlineExceeded), archive166)
    fetched(fetcher(t).fetchCurrent(166, () => newStage()))
    t.calls should have size 3
  }

  it should "be unreachable (69) after three failed attempts, naming the host and no URL" taggedAs UnitTest in {
    val t = new FakeTransport(clock).on(asset(166), Reply.Fail(TransportFailure.Io("connection refused")))
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x shouldBe a[ReleaseRefusal.Unreachable]
    x.exitCode shouldBe 69
    x.message should include("github.com")
    x.message should include("connection refused")
    (x.message should not).include("https://")
    t.calls should have size 3
  }

  it should "not retry a permanent refusal, and exit 78" taggedAs UnitTest in {
    List(
      TransportFailure.HostNotAllowed("evil.example"),
      TransportFailure.NotHttps("http"),
      TransportFailure.BadRedirect("no Location"),
      TransportFailure.TooManyRedirects(5),
      TransportFailure.BodyTooLarge(ReleaseFetcher.MaxArchiveBytes),
      TransportFailure.PortNotAllowed(8080),
      TransportFailure.ContentEncoding("gzip"),
      TransportFailure.HttpStatus(401)
    ).foreach { failure =>
      val t = new FakeTransport(clock).on(asset(166), Reply.Fail(failure))
      val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
      withClue(failure)(x.exitCode shouldBe 78)
      withClue(failure)(t.calls should have size 1)
    }
    sleeper.waits shouldBe empty
  }

  it should "run every attempt and wait inside the one budget" taggedAs UnitTest in {
    // each call takes 30 s of the 60 s budget: the second attempt is the last one that starts
    val t =
      new FakeTransport(clock, cost = Duration.ofSeconds(30)).on(asset(166), Reply.Fail(TransportFailure.Io("slow")))
    val start = clock.instant()
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x shouldBe a[ReleaseRefusal.Unreachable]
    t.calls should have size 2
    t.calls.foreach { c =>
      c.deadline.isAfter(c.at) shouldBe true
      c.deadline.isAfter(start.plus(Budget)) shouldBe false
    }
  }

  it should "give each attempt a fresh absolute deadline, none past the budget" taggedAs UnitTest in {
    val t = new FakeTransport(clock, cost = Duration.ofSeconds(3)).on(asset(166), Reply.status(500))
    val start = clock.instant()
    refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    t.calls should have size 3
    // each deadline is min(end of the budget, the attempt's own start + budget / 2)
    t.calls.foreach { c =>
      val expected = List(start.plus(Budget), c.at.plus(Budget.dividedBy(2))).minBy(_.toEpochMilli)
      c.deadline shouldBe expected
    }
    t.calls.map(_.at).distinct should have size 3
    t.calls.map(_.deadline).distinct should have size 3
  }

  it should "cap an attempt's deadline at the end of the budget when half a budget would pass it" taggedAs UnitTest in {
    val short = Duration.ofSeconds(10)
    val t = new FakeTransport(clock, cost = Duration.ofSeconds(1)).on(asset(166), Reply.status(500))
    val start = clock.instant()
    refusal(fetcher(t, budget = short).fetchCurrent(166, () => newStage()))
    t.calls should have size 3
    // attempts start at 0 s, 3 s and 8 s: the deadlines are 5 s, 8 s and (13 s capped to) 10 s
    t.calls.map(c => Duration.between(start, c.deadline).getSeconds) shouldBe List(5L, 8L, 10L)
  }

  it should "not start an attempt with less than a second of budget left" taggedAs UnitTest in {
    // 8.5 s used by the call; a 2 s wait ends at 10.5 s of 11 s: 0.5 s would be left for an attempt
    val t = new FakeTransport(clock, cost = Duration.ofMillis(8500)).on(asset(166), Reply.status(500))
    refusal(fetcher(t, budget = Duration.ofSeconds(11)).fetchCurrent(166, () => newStage()))
    t.calls should have size 1
    sleeper.waits shouldBe empty
  }

  it should "abort at once when the sleeper is interrupted, keeping the flag, and say so (69)" taggedAs UnitTest in {
    val interrupting = new Sleeper:
      override def sleep(d: Duration): Unit = Thread.currentThread().interrupt()
    val t = new FakeTransport(clock).on(asset(166), Reply.status(503))
    try
      val x = refusal(
        new ReleaseFetcher(t, clock, interrupting, new URI(Base), Budget).fetchCurrent(166, () => newStage())
      )
      x shouldBe a[ReleaseRefusal.Unreachable]
      x.exitCode shouldBe 69
      x.message should include("interrupted")
      t.calls should have size 1
      Thread.currentThread().isInterrupted shouldBe true
    finally Thread.interrupted()
  }

  it should "abort when an attempt ends with the thread interrupted, without waiting" taggedAs UnitTest in {
    var calls = 0
    val self = new ReleaseTransport:
      override def get(
          url: URI,
          maxBytes: Long,
          deadline: Instant,
          accept: Option[String]
      ): Either[TransportFailure, FetchedBody] =
        calls += 1
        Thread.currentThread().interrupt()
        Left(TransportFailure.Io("interrupted"))
    try
      refusal(fetcher(self).fetchCurrent(166, () => newStage())).message should include("interrupted")
      calls shouldBe 1
      sleeper.waits shouldBe empty
      Thread.currentThread().isInterrupted shouldBe true
    finally Thread.interrupted()
  }

  "Sleeper.real" should "return at once when interrupted, and keep the flag set" taggedAs UnitTest in {
    Thread.currentThread().interrupt()
    try
      Sleeper.real.sleep(Duration.ofSeconds(60))
      Thread.currentThread().isInterrupted shouldBe true
    finally Thread.interrupted()
  }

  "fetchCurrent" should "treat a 404 from the API as permanent: a window mismatch, with no wait" taggedAs UnitTest in {
    clock = new ManualClock(reset165.plus(interval).plusSeconds(60))
    sleeper = new ManualSleeper(clock)
    val t = new FakeTransport(clock).on(asset(166), archive166).on(ApiLatest, Reply.status(404))
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x shouldBe a[ReleaseRefusal.WindowMismatch]
    x.message should include("the API has no release")
    t.calls.count(_.url == ApiLatest) shouldBe 1
    sleeper.waits shouldBe empty
  }

  it should "name a wrong source when a mirror answers 404 every time" taggedAs UnitTest in {
    val mirror = "https://mirror.example.org/eph"
    val x = refusal(fetcher(new FakeTransport(clock), base = mirror).fetchCurrent(166, () => newStage()))
    x shouldBe ReleaseRefusal.NotYetPublished(166, wrongSourcePossible = true)
    x.exitCode shouldBe 69
    x.message should include("fukuii.ephemery.source is wrong")
    // GitHub keeps the plain wording
    (refusal(fetcher(new FakeTransport(clock)).fetchCurrent(166, () => newStage())).message should not).include("wrong")
  }

  it should "not wait for a backoff that would end past the budget" taggedAs UnitTest in {
    val t = new FakeTransport(clock, cost = Duration.ofSeconds(9)).on(asset(166), Reply.status(500))
    refusal(fetcher(t, budget = Duration.ofSeconds(10)).fetchCurrent(166, () => newStage()))
    // 9 s used, 2 s wait would reach 11 s > 10 s
    t.calls should have size 1
    sleeper.waits shouldBe empty
  }

  it should "report a 404 that ran the budget out as not published, not as unreachable" taggedAs UnitTest in {
    val t = new FakeTransport(clock, cost = Duration.ofSeconds(30))
    refusal(fetcher(t).fetchCurrent(166, () => newStage())) shouldBe ReleaseRefusal.NotYetPublished(166)
  }

  // ---- the window check and the single API call ---------------------------------------------------------------------

  it should "ask the API's latest once when the target's window does not contain now, and take that release" taggedAs UnitTest in {
    // now is inside 167's window; 166 is published and has ended
    clock = new ManualClock(reset165.plus(interval).plusSeconds(60))
    sleeper = new ManualSleeper(clock)
    val t = new FakeTransport(clock)
      .on(asset(166), archive166)
      .on(ApiLatest, latestJson("ephemery-167"))
      .on(asset(167), archive167)
    val f = fetched(fetcher(t).fetchCurrent(166, () => newStage()))
    f.release.release shouldBe "ephemery-167"
    t.calls.map(_.url) shouldBe List(asset(166), ApiLatest, asset(167))
    t.calls(1).accept shouldBe Some("application/vnd.github+json")
    f.sourceUrl shouldBe s"$Base/releases/tag/ephemery-167"
  }

  it should "refuse with 69 and both windows when the API's latest does not contain now either" taggedAs UnitTest in {
    clock = new ManualClock(reset165.plus(interval).plusSeconds(60))
    sleeper = new ManualSleeper(clock)
    val t = new FakeTransport(clock).on(asset(166), archive166).on(ApiLatest, latestJson("ephemery-166"))
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x shouldBe a[ReleaseRefusal.WindowMismatch]
    x.exitCode shouldBe 69
    t.calls.count(_.url == ApiLatest) shouldBe 1
    x.message should include("ephemery-166")
  }

  it should "not call the API for a mirror, and refuse a window mismatch with 69" taggedAs UnitTest in {
    clock = new ManualClock(reset165.plus(interval).plusSeconds(60))
    sleeper = new ManualSleeper(clock)
    val mirror = "https://mirror.example.org/eph"
    val t = new FakeTransport(clock).on(asset(166, mirror), archive166)
    refusal(fetcher(t, base = mirror).fetchCurrent(166, () => newStage())) shouldBe a[ReleaseRefusal.WindowMismatch]
    t.calls.map(_.url) shouldBe List(asset(166, mirror))
  }

  it should "retry the API on a 403, which is its rate limit, and not an asset 403" taggedAs UnitTest in {
    clock = new ManualClock(reset165.plus(interval).plusSeconds(60))
    sleeper = new ManualSleeper(clock)
    val t = new FakeTransport(clock)
      .on(asset(166), archive166)
      .on(ApiLatest, Reply.status(403), latestJson("ephemery-167"))
      .on(asset(167), archive167)
    fetched(fetcher(t).fetchCurrent(166, () => newStage())).release.release shouldBe "ephemery-167"
    t.calls.count(_.url == ApiLatest) shouldBe 2

    val plain = new FakeTransport(clock).on(asset(166), Reply.status(403))
    refusal(fetcher(plain).fetchCurrent(166, () => newStage())).exitCode shouldBe 78
    plain.calls should have size 1
  }

  it should "refuse an API answer that does not name a release" taggedAs UnitTest in {
    clock = new ManualClock(reset165.plus(interval).plusSeconds(60))
    sleeper = new ManualSleeper(clock)
    val bad = List(
      Reply.Body("not json".getBytes(StandardCharsets.UTF_8)),
      Reply.Body("""{"tag_name":"v1.2.3"}""".getBytes(StandardCharsets.UTF_8)),
      Reply.Body("""{"name":"x"}""".getBytes(StandardCharsets.UTF_8))
    )
    bad.foreach { reply =>
      val t = new FakeTransport(clock).on(asset(166), archive166).on(ApiLatest, reply)
      refusal(fetcher(t).fetchCurrent(166, () => newStage())) shouldBe a[ReleaseRefusal.WindowMismatch]
    }
  }

  // ---- what arrives -------------------------------------------------------------------------------------------------

  it should "refuse a published iteration that was published again, with another genesis (78)" taggedAs UnitTest in {
    val t = new FakeTransport(clock).on(asset(166), archive166)
    val held = "0x" + "11" * 32
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage(), known = n => Option.when(n == 166)(held)))
    x shouldBe a[ReleaseRefusal.Republished]
    x.exitCode shouldBe 78
    x.message should include(held)
  }

  it should "accept a release whose hash is the cached one" taggedAs UnitTest in {
    val t = new FakeTransport(clock).on(asset(166), archive166)
    val hash = entry(166).genesisHash
    fetched(fetcher(t).fetchCurrent(166, () => newStage(), known = n => Option.when(n == 166)(hash)))
  }

  it should "refuse a release that does not validate, and leave no stage content" taggedAs UnitTest in {
    val tampered = inputs166.editConfig(_.set("chainId", num(39438999L)))
    val t = new FakeTransport(clock).on(asset(166), Reply.Body(SyntheticRelease.archive(tampered)))
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x.exitCode shouldBe 78
    Files.list(scratch.resolve("stage-1")).count() shouldBe 0L
  }

  it should "refuse a hash mismatch (78)" taggedAs UnitTest in {
    val t = new FakeTransport(clock)
      .on(asset(166), Reply.Body(SyntheticRelease.archive(inputs166.copy(publishedHashText = "0x" + "ab" * 32))))
    refusal(fetcher(t).fetchCurrent(166, () => newStage())) shouldBe a[ReleaseRefusal.HashMismatch]
  }

  it should "refuse an archive that breaks the reader's rules (78), cleaning the stage" taggedAs UnitTest in {
    val evil = TarFixture.tgz(TarFixture.file("../escape", "x".getBytes(StandardCharsets.UTF_8)))
    val t = new FakeTransport(clock).on(asset(166), Reply.Body(evil))
    val x = refusal(fetcher(t).fetchCurrent(166, () => newStage()))
    x shouldBe a[ReleaseRefusal.CannotRepresent]
    x.exitCode shouldBe 78
    Files.list(scratch.resolve("stage-1")).count() shouldBe 0L
  }

  it should "refuse an archive with no genesis.json (78)" taggedAs UnitTest in {
    val none = TarFixture.tgz(TarFixture.file("config.yaml", "x".getBytes(StandardCharsets.UTF_8)))
    val t = new FakeTransport(clock).on(asset(166), Reply.Body(none))
    refusal(fetcher(t).fetchCurrent(166, () => newStage())).exitCode shouldBe 78
  }

  // ---- fetchExact: an operator asked for this release ---------------------------------------------------------------

  "fetchExact" should "take the named release without a window check, and without the API" taggedAs UnitTest in {
    clock = new ManualClock(reset165.plus(interval).plus(interval))
    val t = new FakeTransport(clock).on(asset(166), archive166)
    fetched(fetcher(t).fetchExact(166, () => newStage())).release.release shouldBe "ephemery-166"
    t.calls.map(_.url) shouldBe List(asset(166))
  }

  it should "report a release that is not published as exit 69" taggedAs UnitTest in {
    refusal(fetcher(new FakeTransport(clock)).fetchExact(300, () => newStage())) shouldBe
      ReleaseRefusal.NotYetPublished(300)
  }
