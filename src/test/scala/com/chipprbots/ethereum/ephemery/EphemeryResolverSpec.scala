package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryResolver.*
import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.testing.Tags.*

/** The startup decision with nothing to fetch (spec 028 data-model.md, rows 1, 2, 3 and 5). Every clock is fixed: no
  * test here reads the system clock, so none turns red on the day the bundled iteration ends.
  */
class EphemeryResolverSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  // ephemery-165: genesis 2026-10-22T19:00:00Z, reset 2026-11-19T19:00:00Z
  private val inside165 = Instant.parse("2026-11-12T10:00:00Z")
  private val beforeGenesis165 = Instant.parse("2026-10-01T00:00:00Z")
  private val after165 = Instant.parse("2026-12-01T00:00:00Z")
  private val Reset165 = "2026-11-19T19:00:00Z"

  private var root: Path = scala.compiletime.uninitialized
  private var scratch: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    root = Files.createTempDirectory("eph-resolver-root").resolve("ephemery")
    scratch = Files.createTempDirectory("eph-resolver-scratch")
  override def afterEach(): Unit =
    delete(root.getParent)
    delete(scratch)

  private def delete(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)

  private def write(p: Path, text: String): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private def deps(at: Instant): StartupDeps =
    StartupDeps(Clock.fixed(at, ZoneOffset.UTC), RefusingTransport, _ => ())

  private def settings(
      network: String = "ephemery",
      customChainsDir: Option[Path] = None,
      refresh: Boolean = true
  ): Settings =
    Settings(
      network = network,
      root = root,
      customChainsDir = customChainsDir,
      logsDir = root.resolve("logs").toString,
      refresh = refresh,
      source = "https://github.com/ephemery-testnet/ephemery-genesis",
      refreshTimeout = scala.concurrent.duration.Duration(60, "s"),
      exitAtReset = true
    )

  private def resolve(at: Instant, s: Settings = settings(), bundle: BundleSource = BundleSource.classpath): Result =
    EphemeryResolver.resolve(s, deps(at), bundle)

  private def texts(r: Result, level: Level): List[String] = r.lines.filter(_.level == level).map(_.text)

  private def managed(r: Result): Managed = r.resolution match
    case m: Managed => m
    case other      => fail(s"expected Managed, got $other with ${r.lines}")

  private def refusal(r: Result): ReleaseRefusal = r.resolution match
    case Refused(x) => x
    case other      => fail(s"expected Refused, got $other with ${r.lines}")

  /** An operator's prepared chain directory: ephemery-165's two files, as fetch-genesis.sh writes them. */
  private def preparedDir(): Path =
    val dir = scratch.resolve("prepared/ephemery-165/chains")
    write(dir.resolve("ephemery-chain.conf"), fixture(165, "ephemery-chain.conf"))
    write(dir.resolve("ephemery-genesis.json"), fixture(165, "ephemery-genesis.json"))
    dir

  private def store(at: Instant = inside165) = new ReleaseStore(root, Clock.fixed(at, ZoneOffset.UTC))

  "the resolver" should "do nothing for a network other than ephemery" taggedAs UnitTest in {
    val r = resolve(inside165, settings(network = "mordor"))
    r.resolution shouldBe NotApplicable
    r.properties shouldBe empty
    r.lines shouldBe empty
    Files.exists(root) shouldBe false
  }

  it should "stand down for an operator's prepared directory, touching nothing under the root" taggedAs UnitTest in {
    val dir = preparedDir()
    val r = resolve(inside165, settings(customChainsDir = Some(dir)))
    r.resolution shouldBe a[Prepared]
    texts(r, Level.Info) should contain(
      s"Ephemery: using the prepared chain directory $dir (custom-chains-dir); fukuii will not fetch or exit at its reset"
    )
    texts(r, Level.Warn) shouldBe empty
    r.properties.get("fukuii.ephemery.active.managed") shouldBe Some("false")
    r.properties.get("fukuii.ephemery.active.reset-time") shouldBe Some("1795114800")
    r.properties.keySet should not contain "fukuii.datadir"
    r.properties.keySet should not contain "fukuii.blockchains.custom-chains-dir"
    Files.exists(root) shouldBe false
  }

  it should "warn when the operator's prepared directory has ended" taggedAs UnitTest in {
    val dir = preparedDir()
    val r = resolve(after165, settings(customChainsDir = Some(dir)))
    r.resolution shouldBe a[Prepared]
    texts(r, Level.Warn) shouldBe List(
      s"Ephemery: the prepared iteration in $dir ended at $Reset165; re-run scripts/ephemery/fetch-genesis.sh, " +
        "or unset custom-chains-dir to use the built-in refresh"
    )
  }

  it should "take the reset of an adjacent release.env over genesis plus the default interval" taggedAs UnitTest in {
    val dir = preparedDir()
    write(dir.getParent.resolve("release.env"), "ITERATION=\"165\"\nRESET_TIME=\"1793000000\"\n")
    val r = resolve(inside165, settings(customChainsDir = Some(dir)))
    r.properties.get("fukuii.ephemery.active.reset-time") shouldBe Some("1793000000")
    texts(r, Level.Warn).head should include("ended at 2026-10-26T07:33:20Z")
  }

  it should "override a custom-chains-dir that holds no ephemery-chain.conf, and say so" taggedAs UnitTest in {
    val empty = Files.createDirectories(scratch.resolve("empty"))
    val r = resolve(inside165, settings(customChainsDir = Some(empty)))
    managed(r).entry.release shouldBe "ephemery-165"
    texts(r, Level.Info).exists(l => l.contains(empty.toString) && l.contains("no ephemery-chain.conf")) shouldBe true
    r.properties("fukuii.blockchains.custom-chains-dir") shouldBe root.resolve("releases/ephemery-165/chains").toString
  }

  it should "run the bundled iteration while it is current, placing it in the root" taggedAs UnitTest in {
    val r = resolve(inside165)
    val m = managed(r)
    m.origin shouldBe Origin.Bundled
    m.entry.release shouldBe "ephemery-165"
    m.datadir shouldBe root.resolve("ephemery-165")
    m.chainsDir shouldBe root.resolve("releases/ephemery-165/chains")
    Files.isRegularFile(root.resolve("releases/ephemery-165/release.json")) shouldBe true
    Files.isRegularFile(root.resolve("releases/ephemery-165/chains/ephemery-chain.conf")) shouldBe true
    Files.isDirectory(root.resolve("ephemery-165")) shouldBe true
    Files.readSymbolicLink(root.resolve("current")).toString shouldBe "releases/ephemery-165"
    r.properties shouldBe Map(
      "fukuii.blockchains.custom-chains-dir" -> root.resolve("releases/ephemery-165/chains").toString,
      "fukuii.datadir" -> root.resolve("ephemery-165").toString,
      "logging.logs-dir" -> root.resolve("logs").toString,
      "fukuii.ephemery.active.iteration" -> "165",
      "fukuii.ephemery.active.reset-time" -> "1795114800",
      "fukuii.ephemery.active.genesis-hash" -> Hash165,
      "fukuii.ephemery.active.managed" -> "true"
    )
  }

  it should "report the start as one line in the wording of contracts/exit-codes.md" taggedAs UnitTest in {
    val r = resolve(inside165)
    texts(r, Level.Info) should contain(
      "Ephemery ephemery-165 (chain id 39438165, genesis 0x9485…5ecc) from bundled; " +
        s"resets $Reset165 (local clock 2026-11-12T10:00:00Z); datadir ${root.resolve("ephemery-165")}; " +
        "CL dir not present: run `fukuii cli ephemery-fetch --release ephemery-165`"
    )
  }

  it should "name the CL directory when the entry has one, and say from cache for a placed entry" taggedAs UnitTest in {
    resolve(inside165)
    store().addTestnet(165, completeClDir()) shouldBe Right(true)
    val r = resolve(inside165)
    managed(r).origin shouldBe Origin.Cache
    texts(r, Level.Info).exists(l =>
      l.contains("from cache;") && l.contains(s"CL dir ${root.resolve("releases/ephemery-165/testnet")}")
    ) shouldBe true
  }

  it should "prefer a newer current cached entry to the bundle" taggedAs UnitTest in {
    val e166 = SyntheticRelease.nextEntry(raw(165), inside165)
    store().place(e166) shouldBe Right(ReleaseStore.Placed)
    val at166 = Instant.ofEpochSecond(e166.metadata.genesisTimestamp + 3600)
    val m = managed(resolve(at166))
    m.entry.release shouldBe "ephemery-166"
    m.origin shouldBe Origin.Cache
    m.datadir shouldBe root.resolve("ephemery-166")
  }

  it should "not run a cached entry before its window opens" taggedAs UnitTest in {
    val e166 = SyntheticRelease.nextEntry(raw(165), inside165)
    store().place(e166) shouldBe Right(ReleaseStore.Placed)
    managed(resolve(inside165)).entry.release shouldBe "ephemery-165"
  }

  it should "run a cached copy of 165 in place of the bundle" taggedAs UnitTest in {
    val valid = EphemeryRelease.validate(raw(165)).fold(r => fail(r.message), identity)
    val cached = ReleaseEntry.of(valid, "/elsewhere", None, "test", inside165)
    store().place(cached) shouldBe Right(ReleaseStore.Placed)
    val m = managed(resolve(inside165))
    m.origin shouldBe Origin.Cache
    texts(resolve(inside165), Level.Warn) shouldBe empty
  }

  it should "refuse with exit 78, and name the way to supply the next iteration, when every iteration has ended and refresh is off" taggedAs UnitTest in {
    val r = resolve(after165, settings(refresh = false))
    val x = refusal(r)
    x shouldBe a[ReleaseRefusal.Ended]
    x.exitCode shouldBe 78
    x.message should include("ephemery-165 ended at " + Reset165)
    x.message should include("fetching is disabled (fukuii.ephemery.refresh = false)")
    x.message should include("supply ephemery-166 with `fukuii cli ephemery-fetch --from-dir DIR` or enable refresh")
    r.properties shouldBe empty
    Files.exists(root.resolve("ephemery-165")) shouldBe false
  }

  it should "refuse with exit 69 before the first iteration's window opens" taggedAs UnitTest in {
    val x = refusal(resolve(beforeGenesis165))
    x shouldBe a[ReleaseRefusal.WindowMismatch]
    x.exitCode shouldBe 69
  }

  it should "set an invalid entry aside, warn, and start on the bundle" taggedAs UnitTest in {
    write(root.resolve("releases/ephemery-165/release.json"), "garbage")
    val r = resolve(inside165)
    val aside = root.resolve("releases/.invalid-ephemery-165-1")
    managed(r).origin shouldBe Origin.Bundled
    texts(r, Level.Warn).exists(_.contains("ephemery-165")) shouldBe true
    texts(r, Level.Warn).exists(l => l.contains(aside.toString) && l.contains("set aside")) shouldBe true
    read(aside.resolve("release.json")) shouldBe "garbage"
    Files.isRegularFile(root.resolve("releases/ephemery-165/chains/ephemery-chain.conf")) shouldBe true
  }

  it should "log, and leave untouched, a datadir from before built-in Ephemery" taggedAs UnitTest in {
    write(root.resolve("rocksdb/CURRENT"), "x" * 2048)
    val r = resolve(inside165)
    managed(r)
    texts(r, Level.Info).exists(l =>
      l.contains(root.resolve("rocksdb").toString) && l.contains("not used")
    ) shouldBe true
    read(root.resolve("rocksdb/CURRENT")) shouldBe "x" * 2048
  }

  it should "log the earlier iterations' datadirs once, with their sizes, and keep them" taggedAs UnitTest in {
    write(root.resolve("ephemery-163/rocksdb/a"), "x" * 1536)
    write(root.resolve("ephemery-164/rocksdb/a"), "x" * (3 * 1024 * 1024))
    val r = resolve(inside165)
    managed(r)
    texts(r, Level.Info) should contain(
      "Ephemery: earlier iteration datadirs kept: ephemery-163, ephemery-164; fukuii never deletes them"
    )
    Files.exists(root.resolve("ephemery-163/rocksdb/a")) shouldBe true
  }

  it should "seed static-nodes.json into the iteration datadir it creates" taggedAs UnitTest in {
    write(root.resolve("static-nodes.json"), "[]")
    resolve(inside165)
    read(root.resolve("ephemery-165/static-nodes.json")) shouldBe "[]"
  }

  it should "sweep the stages of dead runs when it starts" taggedAs UnitTest in {
    val stale = root.resolve(".stage-dead")
    write(stale.resolve("x"), "x")
    Files.setLastModifiedTime(
      stale,
      java.nio.file.attribute.FileTime.from(inside165.minus(java.time.Duration.ofHours(3)))
    )
    resolve(inside165)
    Files.exists(stale) shouldBe false
  }

  // ---- the half-open window [genesis, reset) of each iteration (beacon B1) ----------------------------------------------

  private val genesis165 = Instant.ofEpochSecond(1792695600L)
  private val reset165 = Instant.ofEpochSecond(1795114800L)

  it should "refuse one second before the first iteration's genesis (WindowMismatch), with no earlier one cached" taggedAs UnitTest in {
    refusal(resolve(genesis165.minusSeconds(1))) shouldBe a[ReleaseRefusal.WindowMismatch]
  }

  it should "run the previous iteration one second before the next one's genesis, and the next at its genesis" taggedAs UnitTest in {
    val valid164 = EphemeryRelease.validate(raw(164)).fold(r => fail(r.message), identity)
    store().place(ReleaseEntry.of(valid164, "/src", None, "test", inside165)) shouldBe Right(ReleaseStore.Placed)
    managed(resolve(genesis165.minusSeconds(1))).entry.release shouldBe "ephemery-164"
    managed(resolve(genesis165)).entry.release shouldBe "ephemery-165"
  }

  it should "run an iteration one second before its reset, and not at the reset second" taggedAs UnitTest in {
    managed(resolve(reset165.minusSeconds(1))).entry.release shouldBe "ephemery-165"
    val x = refusal(resolve(reset165, settings(refresh = false)))
    x shouldBe a[ReleaseRefusal.Ended]
  }

  it should "give the reset second to the next iteration when it is cached" taggedAs UnitTest in {
    val e166 = SyntheticRelease.nextEntry(raw(165), inside165)
    store().place(e166) shouldBe Right(ReleaseStore.Placed)
    managed(resolve(reset165.minusSeconds(1))).entry.release shouldBe "ephemery-165"
    managed(resolve(reset165)).entry.release shouldBe "ephemery-166"
  }

  // ---- a cached copy that differs from the bundle (prism P5) -------------------------------------------------------------

  it should "warn that the cached copy differs from the bundle, and run the cached one when it is current" taggedAs UnitTest in {
    val variant = SyntheticRelease.variantEntry(raw(165), inside165)
    store().place(variant) shouldBe Right(ReleaseStore.Placed)
    val r = resolve(inside165)
    managed(r).origin shouldBe Origin.Cache
    managed(r).entry.genesisHash shouldBe variant.genesisHash
    texts(r, Level.Warn) should contain(
      s"Ephemery: the cached ephemery-165 (genesis ${variant.genesisHash}) differs from the bundled one " +
        s"($Hash165); running the cached one"
    )
  }

  it should "not claim to run a differing cached copy that is not the iteration it runs" taggedAs UnitTest in {
    val variant = SyntheticRelease.variantEntry(raw(165), inside165)
    store().place(variant) shouldBe Right(ReleaseStore.Placed)
    val e166 = SyntheticRelease.nextEntry(raw(165), inside165)
    store().place(e166) shouldBe Right(ReleaseStore.Placed)
    val r = resolve(Instant.ofEpochSecond(e166.metadata.genesisTimestamp + 60))
    managed(r).entry.release shouldBe "ephemery-166"
    val warnings = texts(r, Level.Warn).filter(_.contains("differs from the bundled one"))
    warnings should have size 1
    (warnings.head should not).include("running the cached one")
    warnings.head should include("not the iteration run now")
  }

  // ---- startup: the properties, and the report once (prism P2, P5) ------------------------------------------------------

  it should "set the system properties in startup, hold the report back, and write it once" taggedAs UnitTest in {
    val touched = EphemeryResolver.PropertyKeys
    touched.foreach(System.clearProperty)
    ConfigFactory.invalidateCaches()
    try
      val raw = ConfigFactory.parseString(
        s"""fukuii.blockchains.network = "ephemery"
           |fukuii.datadir = "$root"
           |logging.logs-dir = "$root/logs"
           |""".stripMargin
      )
      var reloads = 0
      val code = EphemeryResolver.startup(deps(inside165), BundleSource.classpath, raw, () => reloads += 1)
      code shouldBe None
      reloads shouldBe 1
      System.getProperty("fukuii.datadir") shouldBe root.resolve("ephemery-165").toString
      System.getProperty("fukuii.ephemery.active.iteration") shouldBe "165"
      System.getProperty("fukuii.ephemery.active.managed") shouldBe "true"
      System.getProperty("logging.logs-dir") shouldBe root.resolve("logs").toString

      val written = scala.collection.mutable.ListBuffer.empty[Line]
      EphemeryResolver.flushReport(ls => written ++= ls)
      written.map(_.text).exists(_.startsWith("Ephemery ephemery-165 (chain id 39438165")) shouldBe true
      val count = written.size
      count should be > 0
      EphemeryResolver.flushReport(ls => written ++= ls)
      written.size shouldBe count
    finally
      touched.foreach(System.clearProperty)
      ConfigFactory.invalidateCaches()
  }

  it should "return the exit code of a refusal from startup, with the properties untouched" taggedAs UnitTest in {
    val raw = ConfigFactory.parseString(
      s"""fukuii.blockchains.network = "ephemery"
         |fukuii.datadir = "$root"
         |fukuii.ephemery.refresh = false
         |""".stripMargin
    )
    EphemeryResolver.startup(deps(after165), BundleSource.classpath, raw, () => ()) shouldBe Some(78)
    System.getProperty("fukuii.ephemery.active.managed") shouldBe null
  }

  // ---- the refresh: rows 4 and 6 (spec 028 S4b) ------------------------------------------------------------------------

  private val afterReset = Instant.ofEpochSecond(1795114800L).plusSeconds(60)
  private val Mirror = "https://mirror.example.org/eph"

  private lazy val inputs166 = SyntheticRelease.next(raw(165))
  private lazy val archive166 = Reply.Body(SyntheticRelease.archive(inputs166))

  /** A deps whose factory hands out one fake transport and records the policy it was asked for. */
  final private class Wiring(at: Instant, cost: java.time.Duration = java.time.Duration.ZERO):
    val clock = new ManualClock(at)
    val sleeper = new ManualSleeper(clock)
    val transport = new FakeTransport(clock, cost)
    var policies: List[SourcePolicy] = Nil
    val deps: StartupDeps = StartupDeps(
      clock,
      (p: SourcePolicy) =>
        policies = policies :+ p
        Right(transport)
      ,
      _ => (),
      sleeper
    )

  private def fetchSettings(source: String = EphemeryUrls.Base, refresh: Boolean = true) =
    settings(refresh = refresh).copy(source = source)

  it should "fetch the next iteration after a reset, place it with its testnet/, and run it" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    w.transport.on(EphemeryUrls.asset(166), archive166)
    val r = EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath)
    val m = managed(r)
    m.origin shouldBe Origin.Fetched
    m.entry.release shouldBe "ephemery-166"
    m.datadir shouldBe root.resolve("ephemery-166")
    m.chainsDir shouldBe root.resolve("releases/ephemery-166/chains")
    val testnet = root.resolve("releases/ephemery-166/testnet")
    m.clDir shouldBe Some(testnet)
    Files.isRegularFile(testnet.resolve("config.yaml")) shouldBe true
    Files.isRegularFile(testnet.resolve("genesis.ssz")) shouldBe true
    Files.exists(testnet.resolve("parsedBeaconState.json")) shouldBe false
    Files.exists(testnet.resolve("bootnode-keys")) shouldBe false
    Files.readSymbolicLink(root.resolve("current")).toString shouldBe "releases/ephemery-166"
    r.properties("fukuii.ephemery.active.iteration") shouldBe "166"
    r.properties("fukuii.datadir") shouldBe root.resolve("ephemery-166").toString
    w.policies shouldBe List(SourcePolicy.DefaultGithub)
    w.transport.closed shouldBe 1
    texts(r, Level.Info).exists(l =>
      l.contains("from fetched;") && l.contains(s"CL dir $testnet") && l.startsWith(
        "Ephemery ephemery-166 (chain id 39438166"
      )
    ) shouldBe true
    // the entry carries where it came from and the archive hash
    m.entry.metadata.source shouldBe s"${EphemeryUrls.Base}/releases/tag/ephemery-166"
    m.entry.metadata.archiveSha256.isDefined shouldBe true
    // 165's datadir is not made, and its entry is not touched
    Files.exists(root.resolve("ephemery-165")) shouldBe false
  }

  it should "run the fetched entry from the cache at the next start, with no call to the network" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    w.transport.on(EphemeryUrls.asset(166), archive166)
    managed(EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath))
    val again = new Wiring(afterReset.plusSeconds(3600))
    val m = managed(EphemeryResolver.resolve(fetchSettings(), again.deps, BundleSource.classpath))
    m.origin shouldBe Origin.Cache
    again.transport.calls shouldBe empty
    again.policies shouldBe empty
  }

  it should "make no transport and no call while an iteration is current (SC-003)" taggedAs UnitTest in {
    val w = new Wiring(inside165)
    val r = EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath)
    managed(r).origin shouldBe Origin.Bundled
    w.policies shouldBe empty
    w.transport.calls shouldBe empty
    // and a cached current one
    val cached = new Wiring(inside165)
    managed(EphemeryResolver.resolve(fetchSettings(), cached.deps, BundleSource.classpath)).origin shouldBe Origin.Cache
    cached.policies shouldBe empty
    cached.transport.calls shouldBe empty
    // one second before the reset is still current
    val edge = new Wiring(Instant.ofEpochSecond(1795114800L).minusSeconds(1))
    managed(EphemeryResolver.resolve(fetchSettings(), edge.deps, BundleSource.classpath))
    edge.transport.calls shouldBe empty
  }

  it should "make no call when refresh is off, and refuse with 78" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    w.transport.on(EphemeryUrls.asset(166), archive166)
    val r = EphemeryResolver.resolve(fetchSettings(refresh = false), w.deps, BundleSource.classpath)
    refusal(r) shouldBe a[ReleaseRefusal.Ended]
    refusal(r).exitCode shouldBe 78
    w.policies shouldBe empty
    w.transport.calls shouldBe empty
  }

  it should "fetch from an https mirror under the Mirror policy" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    w.transport.on(EphemeryUrls.asset(166, Mirror), archive166)
    val m = managed(EphemeryResolver.resolve(fetchSettings(source = Mirror), w.deps, BundleSource.classpath))
    m.entry.release shouldBe "ephemery-166"
    w.policies shouldBe List(SourcePolicy.Mirror)
    w.transport.calls.map(_.url) shouldBe List(EphemeryUrls.asset(166, Mirror))
  }

  it should "keep the GitHub allow-list for another path on github.com" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    val fork = "https://github.com/someone/ephemery-genesis"
    w.transport.on(EphemeryUrls.asset(166, fork), archive166)
    managed(EphemeryResolver.resolve(fetchSettings(source = fork), w.deps, BundleSource.classpath))
    w.policies shouldBe List(SourcePolicy.DefaultGithub)
  }

  it should "refuse with 69 when the next iteration is not published, after three attempts and two waits" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    val x = refusal(EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath))
    x shouldBe ReleaseRefusal.NotYetPublished(166)
    x.exitCode shouldBe 69
    w.transport.calls should have size 3
    w.sleeper.waits should have size 2
    w.transport.closed shouldBe 1
    Files.exists(root.resolve("ephemery-166")) shouldBe false
    Files.exists(root.resolve("releases/ephemery-166")) shouldBe false
  }

  it should "map a republished iteration to exit 78" taggedAs UnitTest in {
    // the server publishes, under the target's tag, a variant of 165 (another genesis): the cached 165 is the bundle's
    val w = new Wiring(afterReset)
    val variant = SyntheticRelease.variant(raw(165))
    w.transport.on(EphemeryUrls.asset(166), Reply.Body(SyntheticRelease.archive(variant)))
    val x = refusal(EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath))
    x shouldBe a[ReleaseRefusal.Republished]
    x.exitCode shouldBe 78
  }

  it should "exit 1, a restartable code, on an unexpected failure while fetching, not 78" taggedAs UnitTest in {
    val boom = new ReleaseTransport:
      override def get(
          url: java.net.URI,
          maxBytes: Long,
          deadline: Instant,
          accept: Option[String]
      ): Either[TransportFailure, FetchedBody] = throw new IllegalStateException("disk exploded")
    val deps = StartupDeps(new ManualClock(afterReset), boom, _ => ())
    val x = refusal(EphemeryResolver.resolve(fetchSettings(), deps, BundleSource.classpath))
    x shouldBe a[ReleaseRefusal.CannotStore]
    x.exitCode shouldBe 1
    ReleaseRefusal.CannotStore("x").exitCode shouldBe 1
  }

  it should "refuse with 69 when the source is unreachable" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    w.transport.on(EphemeryUrls.asset(166), Reply.Fail(TransportFailure.Io("no route to host")))
    val x = refusal(EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath))
    x shouldBe a[ReleaseRefusal.Unreachable]
    x.exitCode shouldBe 69
  }

  it should "use the configured refresh-timeout as the whole budget" taggedAs UnitTest in {
    val w = new Wiring(afterReset, cost = java.time.Duration.ofSeconds(8))
    val s = fetchSettings().copy(refreshTimeout = scala.concurrent.duration.Duration(10, "s"))
    refusal(EphemeryResolver.resolve(s, w.deps, BundleSource.classpath))
    // 8 s spent, a 2 s wait would reach the 10 s limit: one attempt
    w.transport.calls should have size 1
  }

  it should "refuse with 78 when the transport cannot be built (a bad proxy variable)" taggedAs UnitTest in {
    val deps = StartupDeps(
      new ManualClock(afterReset),
      (_: SourcePolicy) => Left("HTTPS_PROXY carries credentials"),
      _ => (),
      Sleeper.real
    )
    val x = refusal(EphemeryResolver.resolve(fetchSettings(), deps, BundleSource.classpath))
    x shouldBe a[ReleaseRefusal.BadSource]
    x.exitCode shouldBe 78
    x.message should include("HTTPS_PROXY carries credentials")
  }

  it should "refuse a source that is not https or a directory, without a call (78)" taggedAs UnitTest in {
    List(
      "http://github.com/ephemery-testnet/ephemery-genesis",
      "https://user:pw@mirror.example.org/eph",
      "https://mirror.example.org/eph?token=1",
      "ftp://mirror.example.org/eph",
      "relative/dir",
      "file:///tmp/x"
    ).foreach { source =>
      val w = new Wiring(afterReset)
      val x = refusal(EphemeryResolver.resolve(fetchSettings(source = source), w.deps, BundleSource.classpath))
      withClue(source)(x shouldBe a[ReleaseRefusal.BadSource])
      withClue(source)(x.exitCode shouldBe 78)
      withClue(source)(w.policies shouldBe empty)
    }
  }

  it should "not name the credentials of a refused source in the message" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    val x = refusal(
      EphemeryResolver.resolve(
        fetchSettings(source = "https://user:hunter2@mirror.example.org/eph"),
        w.deps,
        BundleSource.classpath
      )
    )
    (x.message should not).include("hunter2")
  }

  private def releaseDirOf(inputs: ReleaseInputs, name: String): Path =
    val dir = Files.createDirectories(scratch.resolve(name))
    write(dir.resolve("genesis.json"), inputs.genesisJson)
    write(dir.resolve("retention.vars"), inputs.retentionVars)
    write(dir.resolve("deposit_contract_block_hash.txt"), inputs.publishedHashText)
    inputs.enodesText.foreach(write(dir.resolve("enodes.txt"), _))
    inputs.bootEnrText.foreach(write(dir.resolve("boot_enr.txt"), _))
    dir

  it should "run a release from a source directory with no network" taggedAs UnitTest in {
    val dir = releaseDirOf(inputs166, "src166")
    val w = new Wiring(afterReset)
    val m = managed(EphemeryResolver.resolve(fetchSettings(source = dir.toString), w.deps, BundleSource.classpath))
    m.entry.release shouldBe "ephemery-166"
    m.origin shouldBe Origin.Fetched
    w.policies shouldBe empty
    w.transport.calls shouldBe empty
    Files.isRegularFile(root.resolve("releases/ephemery-166/chains/ephemery-chain.conf")) shouldBe true
  }

  it should "use a source directory even when refresh is off, since it needs no network" taggedAs UnitTest in {
    val dir = releaseDirOf(inputs166, "src166")
    val w = new Wiring(afterReset)
    managed(
      EphemeryResolver.resolve(fetchSettings(source = dir.toString, refresh = false), w.deps, BundleSource.classpath)
    ).entry.release shouldBe "ephemery-166"
  }

  it should "refuse with 78 when the source directory holds an iteration that has ended" taggedAs UnitTest in {
    val dir = releaseDirOf(raw(165), "src165")
    val x = refusal(
      EphemeryResolver.resolve(
        fetchSettings(source = dir.toString),
        new Wiring(afterReset).deps,
        BundleSource.classpath
      )
    )
    x shouldBe a[ReleaseRefusal.Ended]
    x.exitCode shouldBe 78
    x.message should include("ephemery-165")
  }

  it should "refuse with 78 when the source directory is empty or not a directory" taggedAs UnitTest in {
    val empty = Files.createDirectories(scratch.resolve("empty"))
    refusal(
      EphemeryResolver.resolve(
        fetchSettings(source = empty.toString),
        new Wiring(afterReset).deps,
        BundleSource.classpath
      )
    ).exitCode shouldBe 78
    val missing = scratch.resolve("missing")
    val x = refusal(
      EphemeryResolver.resolve(
        fetchSettings(source = missing.toString),
        new Wiring(afterReset).deps,
        BundleSource.classpath
      )
    )
    x shouldBe a[ReleaseRefusal.BadSource]
    x.exitCode shouldBe 78
  }

  it should "refuse a source directory that holds a release that does not validate" taggedAs UnitTest in {
    val bad = SyntheticRelease.next(raw(165)).copy(publishedHashText = "0x" + "cd" * 32)
    val dir = releaseDirOf(bad, "bad166")
    val x = refusal(
      EphemeryResolver.resolve(
        fetchSettings(source = dir.toString),
        new Wiring(afterReset).deps,
        BundleSource.classpath
      )
    )
    x shouldBe a[ReleaseRefusal.HashMismatch]
  }

  it should "leave no stage behind after a refusal or a fetch" taggedAs UnitTest in {
    val w = new Wiring(afterReset)
    refusal(EphemeryResolver.resolve(fetchSettings(), w.deps, BundleSource.classpath))
    val w2 = new Wiring(afterReset)
    w2.transport.on(EphemeryUrls.asset(166), archive166)
    managed(EphemeryResolver.resolve(fetchSettings(), w2.deps, BundleSource.classpath))
    Files.list(root).iterator().asScala.map(_.getFileName.toString).filter(_.startsWith(".stage-")).toList shouldBe Nil
  }

  "Settings.from" should "read the raw configuration, with the operator keys' defaults when they are absent" taggedAs UnitTest in {
    val raw = ConfigFactory.parseString(
      s"""fukuii.blockchains.network = "ephemery"
         |fukuii.datadir = "$root"
         |logging.logs-dir = "$root/logs"
         |""".stripMargin
    )
    val s = Settings.from(raw)
    s.network shouldBe "ephemery"
    s.root shouldBe root
    s.customChainsDir shouldBe None
    s.logsDir shouldBe s"$root/logs"
    s.refresh shouldBe true
    s.exitAtReset shouldBe true
    s.source shouldBe "https://github.com/ephemery-testnet/ephemery-genesis"
  }

  it should "read the operator's keys" taggedAs UnitTest in {
    val raw = ConfigFactory.parseString(
      s"""fukuii.blockchains.network = "ephemery"
         |fukuii.datadir = "$root"
         |fukuii.blockchains.custom-chains-dir = "/some/dir"
         |logging.logs-dir = "/logs"
         |fukuii.ephemery { refresh = false, source = "/a/dir", refresh-timeout = 20s, exit-at-reset = false }
         |""".stripMargin
    )
    val s = Settings.from(raw)
    s.customChainsDir shouldBe Some(java.nio.file.Paths.get("/some/dir"))
    s.refresh shouldBe false
    s.exitAtReset shouldBe false
    s.source shouldBe "/a/dir"
    s.refreshTimeout.toSeconds shouldBe 20L
  }

  it should "refuse a malformed refresh-timeout in startup with exit 1: no properties, no transport call" taggedAs UnitTest in {
    EphemeryResolver.PropertyKeys.foreach(System.clearProperty)
    var calls = 0
    val spy = new ReleaseTransport:
      override def get(
          url: java.net.URI,
          maxBytes: Long,
          deadline: Instant,
          accept: Option[String]
      ): Either[TransportFailure, FetchedBody] =
        calls += 1
        RefusingTransport.get(url, maxBytes, deadline, accept)
    List("\"soon\"", "5s", "301s").foreach { bad =>
      val raw = ConfigFactory.parseString(
        s"""fukuii.blockchains.network = "ephemery"
           |fukuii.datadir = "$root"
           |fukuii.ephemery.refresh-timeout = $bad
           |""".stripMargin
      )
      val d = StartupDeps(Clock.fixed(after165, ZoneOffset.UTC), spy, _ => ())
      EphemeryResolver.startup(d, BundleSource.classpath, raw, () => ()) shouldBe Some(1)
    }
    calls shouldBe 0
    EphemeryResolver.PropertyKeys.foreach(k => System.getProperty(k) shouldBe null)
    Files.exists(root) shouldBe false
  }

  it should "not throw in Settings.from on a malformed refresh-timeout (defence only; startup refuses first)" taggedAs UnitTest in {
    val raw = ConfigFactory.parseString(
      s"""fukuii.blockchains.network = "ephemery"
         |fukuii.datadir = "$root"
         |fukuii.ephemery.refresh-timeout = "soon"
         |""".stripMargin
    )
    Settings.from(raw).refreshTimeout.toSeconds shouldBe 60L
  }
