package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

import scala.jdk.CollectionConverters.*

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.ephemery.ReleaseStore.*
import com.chipprbots.ethereum.testing.Tags.*

/** The Ephemery root on disk (spec 028 data-model.md, "Ephemery root and its entries"): entries placed with one rename,
  * re-validated on load, stale stages swept, and nothing the node or an operator keeps is ever deleted.
  */
class ReleaseStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val now = Instant.parse("2026-11-12T10:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)

  private var root: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = root = Files.createTempDirectory("eph-store-test")
  override def afterEach(): Unit = delete(root)

  private def delete(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)

  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
  private def write(p: Path, text: String): Unit =
    Files.createDirectories(p.getParent)
    Files.write(p, text.getBytes(StandardCharsets.UTF_8))
  private def names(p: Path): List[String] =
    val s = Files.list(p)
    try s.iterator().asScala.map(_.getFileName.toString).toList.sorted
    finally s.close()

  private lazy val entry165: ReleaseEntry =
    val valid = EphemeryRelease.validate(raw(165)).fold(r => fail(r.message), identity)
    ReleaseEntry.of(valid, "/src", None, "test", now)

  private def store(ops: ReleaseLayout.FileOps = ReleaseLayout.FileOps.Default) = new ReleaseStore(root, clock, ops)
  private def stages: List[String] = names(root).filter(_.startsWith(".stage-"))

  /** A directory of consensus-layer files to hand to `place`: the way a fetch leaves its stage. */
  private def testnetStage(): Path =
    val dir = store().newStageDir()
    write(dir.resolve("config.yaml"), "PRESET_BASE: mainnet\n")
    write(dir.resolve("genesis.ssz"), "ssz")
    write(dir.resolve("deploy_block.txt"), "0\n")
    write(dir.resolve("sub/extra"), "x")
    dir

  "place with a testnet directory" should "move it into the new entry, so the entry appears with it" taggedAs UnitTest in {
    val stage = testnetStage()
    stage.getFileName.toString should startWith(".stage-")
    store().place(entry165, Some(stage)) shouldBe Right(Placed)
    val dir = root.resolve("releases/ephemery-165")
    names(dir) shouldBe List("chains", "release.json", "testnet")
    read(dir.resolve("testnet/config.yaml")) shouldBe "PRESET_BASE: mainnet\n"
    read(dir.resolve("testnet/sub/extra")) shouldBe "x"
    Files.exists(stage) shouldBe false
    store().list().entries.map(_.testnetDir.isDefined) shouldBe List(true)
  }

  it should "leave the directory with the caller when the entry is already there, for addTestnet" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val stage = testnetStage()
    store().place(entry165, Some(stage)) shouldBe Right(AlreadyPresent)
    Files.isRegularFile(stage.resolve("config.yaml")) shouldBe true
    Files.exists(root.resolve("releases/ephemery-165/testnet")) shouldBe false
    store().addTestnet(165, stage) shouldBe Right(true)
    read(root.resolve("releases/ephemery-165/testnet/config.yaml")) shouldBe "PRESET_BASE: mainnet\n"
  }

  it should "leave the directory with the caller when the entry was published again (Republished)" taggedAs UnitTest in {
    store().place(SyntheticRelease.variantEntry(raw(165), now)) shouldBe Right(Placed)
    val stage = testnetStage()
    store().place(entry165, Some(stage)).left.map(_.exitCode) shouldBe Left(78)
    Files.isRegularFile(stage.resolve("config.yaml")) shouldBe true
    Files.exists(root.resolve("releases/ephemery-165/testnet")) shouldBe false
  }

  "place" should "put the entry in releases/ephemery-N with one rename and leave no stage" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val dir = root.resolve("releases/ephemery-165")
    names(dir) shouldBe List("chains", "release.json")
    read(dir.resolve("chains/ephemery-chain.conf")) shouldBe entry165.chainConf
    read(dir.resolve("chains/ephemery-genesis.json")) shouldBe entry165.genesisJson
    ReleaseMetadata.decode(read(dir.resolve("release.json"))) shouldBe Right(entry165.metadata)
    stages shouldBe Nil
  }

  it should "leave nothing at the target and no stage when the move fails" taggedAs UnitTest in {
    val failing = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit = throw new java.io.IOException("injected")
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
    store(failing).place(entry165).isLeft shouldBe true
    Files.exists(root.resolve("releases/ephemery-165")) shouldBe false
    stages shouldBe Nil
  }

  it should "discard the stage when the same N is placed again with the same hash" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val file = root.resolve("releases/ephemery-165/release.json")
    val before = Files.getLastModifiedTime(file)
    store().place(entry165) shouldBe Right(AlreadyPresent)
    Files.getLastModifiedTime(file) shouldBe before
    stages shouldBe Nil
  }

  it should "refuse with Republished when the same N arrives with another hash, and keep what is held" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val other = "0x" + "ab" * 32
    val republished = entry165.copy(metadata = entry165.metadata.copy(genesisHash = other))
    store().place(republished) shouldBe Left(ReleaseRefusal.Republished(165, Hash165, other))
    read(root.resolve("releases/ephemery-165/release.json")) shouldBe entry165.metadata.toJson
    stages shouldBe Nil
  }

  it should "set an entry directory that is not valid aside, never delete it, and place the entry" taggedAs UnitTest in {
    write(root.resolve("releases/ephemery-165/release.json"), "not json")
    val aside = root.resolve("releases/.invalid-ephemery-165-1")
    store().place(entry165) shouldBe Right(SetAside(aside))
    read(aside.resolve("release.json")) shouldBe "not json"
    store().list().entries.map(_.entry.release) shouldBe List("ephemery-165")
    // a second invalid one takes the next name, and the first is still there
    delete(root.resolve("releases/ephemery-165"))
    write(root.resolve("releases/ephemery-165/release.json"), "torn")
    store().place(entry165) shouldBe Right(SetAside(root.resolve("releases/.invalid-ephemery-165-2")))
    read(aside.resolve("release.json")) shouldBe "not json"
    // the set-aside directories are not entries
    store().list().entries.map(_.entry.release) shouldBe List("ephemery-165")
    stages shouldBe Nil
  }

  it should "write the entry's files durably before the rename: the files are there and complete" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    Files.size(root.resolve("releases/ephemery-165/chains/ephemery-genesis.json")) shouldBe
      entry165.genesisJson.getBytes(StandardCharsets.UTF_8).length.toLong
  }

  "addTestnet" should "stage a testnet/ and rename it into an existing entry, once" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val cl = completeClDir()
    store().addTestnet(165, cl) shouldBe Right(true)
    val dir = root.resolve("releases/ephemery-165")
    names(dir) shouldBe List("chains", "release.json", "testnet")
    read(dir.resolve("testnet/retention.vars")) shouldBe read(releaseDir(165).resolve("retention.vars"))
    // a second call finds it there and changes nothing
    write(dir.resolve("testnet/marker"), "kept")
    store().addTestnet(165, cl) shouldBe Right(false)
    read(dir.resolve("testnet/marker")) shouldBe "kept"
    stages shouldBe Nil
  }

  it should "refuse an entry that is not there" taggedAs UnitTest in {
    store().addTestnet(165, releaseDir(165)).isLeft shouldBe true
    Files.exists(root.resolve("releases/ephemery-165")) shouldBe false
    stages shouldBe Nil
  }

  it should "repair a torn testnet/ (a required CL file missing or empty) through a stage, keeping the old one aside" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val dir = root.resolve("releases/ephemery-165")
    write(dir.resolve("testnet/config.yaml"), "PRESET_BASE: mainnet\n")
    write(dir.resolve("testnet/genesis.ssz"), "") // empty: torn
    store().list().entries.head.testnetDir shouldBe None
    store().addTestnet(165, completeClDir()) shouldBe Right(true)
    read(dir.resolve("testnet/genesis.ssz")) shouldBe "content of genesis.ssz"
    store().list().entries.head.testnetDir shouldBe Some(dir.resolve("testnet"))
    // the torn one is kept, never deleted
    names(dir).filter(_.startsWith(".testnet-torn-")) shouldBe List(".testnet-torn-1")
    read(dir.resolve(".testnet-torn-1/config.yaml")) shouldBe "PRESET_BASE: mainnet\n"
    stages shouldBe Nil
  }

  "list" should "show a valid entry and log, but never delete, a bad or missing release.json" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    write(root.resolve("releases/ephemery-170/release.json"), "{ nope")
    write(root.resolve("releases/ephemery-171/chains/ephemery-chain.conf"), "x")
    write(root.resolve("releases/not-an-entry/release.json"), "{}")
    val listed = store().list()
    listed.entries.map(_.entry.release) shouldBe List("ephemery-165")
    listed.problems.exists(_.contains("ephemery-170")) shouldBe true
    listed.problems.exists(_.contains("ephemery-171")) shouldBe true
    listed.problems.exists(_.contains("not-an-entry")) shouldBe false
    Files.exists(root.resolve("releases/ephemery-170/release.json")) shouldBe true
    Files.exists(root.resolve("releases/ephemery-171/chains/ephemery-chain.conf")) shouldBe true
  }

  it should "re-validate an entry: a changed genesis makes it invisible" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    val genesis = root.resolve("releases/ephemery-165/chains/ephemery-genesis.json")
    write(genesis, entry165.genesisJson.replace("\"gasLimit\": \"0x2255100\"", "\"gasLimit\": \"0x2255101\""))
    val listed = store().list()
    listed.entries shouldBe Nil
    listed.problems.exists(_.contains("ephemery-165")) shouldBe true
    Files.exists(genesis) shouldBe true
  }

  it should "report where an entry's testnet/ is when it has one" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    store().list().entries.head.testnetDir shouldBe None
    store().addTestnet(165, completeClDir()) shouldBe Right(true)
    store().list().entries.head.testnetDir shouldBe Some(root.resolve("releases/ephemery-165/testnet"))
  }

  "sweepStages" should "remove .stage-* directories older than an hour and nothing else" taggedAs UnitTest in {
    def aged(name: String, age: java.time.Duration): Path =
      val d = root.resolve(name)
      write(d.resolve("x"), "x")
      Files.setLastModifiedTime(d, FileTime.from(now.minus(age)))
      d
    val old = aged(".stage-old", java.time.Duration.ofHours(2))
    val fresh = aged(".stage-fresh", java.time.Duration.ofMinutes(30))
    val kept = aged(".stage-kept-x", java.time.Duration.ofHours(5))
    val holdsOld = aged(".stage-holds", java.time.Duration.ofHours(5))
    Files.createDirectories(holdsOld.resolve(".old"))
    store().place(entry165) shouldBe Right(Placed)
    val datadir = root.resolve("ephemery-164")
    write(datadir.resolve("rocksdb/CURRENT"), "x")
    Files.setLastModifiedTime(datadir, FileTime.from(now.minus(java.time.Duration.ofDays(30))))
    val releases = root.resolve("releases/ephemery-165")
    Files.setLastModifiedTime(releases, FileTime.from(now.minus(java.time.Duration.ofDays(30))))

    store().sweepStages()

    Files.exists(old) shouldBe false
    Files.exists(fresh) shouldBe true
    Files.exists(kept) shouldBe true
    Files.exists(holdsOld) shouldBe true
    Files.exists(datadir.resolve("rocksdb/CURRENT")) shouldBe true
    Files.exists(releases.resolve("release.json")) shouldBe true
  }

  "seedDatadir" should "copy static-nodes.json from the root into a new iteration datadir, and never overwrite it" taggedAs UnitTest in {
    write(root.resolve("static-nodes.json"), "[\"enode://a\"]")
    val seeded = store().seedDatadir(165)
    val datadir = seeded.dir
    seeded.warning shouldBe None
    datadir shouldBe root.resolve("ephemery-165")
    read(datadir.resolve("static-nodes.json")) shouldBe "[\"enode://a\"]"
    // the node's own copy is its own: a later change at the root does not reach it, and a second call changes nothing
    write(datadir.resolve("static-nodes.json"), "[\"enode://own\"]")
    write(root.resolve("static-nodes.json"), "[\"enode://b\"]")
    store().seedDatadir(165).dir shouldBe datadir
    read(datadir.resolve("static-nodes.json")) shouldBe "[\"enode://own\"]"
  }

  it should "seed only a datadir it created, and make one with no file when the root has none" taggedAs UnitTest in {
    store().seedDatadir(165)
    Files.isDirectory(root.resolve("ephemery-165")) shouldBe true
    Files.exists(root.resolve("ephemery-165/static-nodes.json")) shouldBe false
    // an existing datadir (the node's) is not seeded afterwards
    write(root.resolve("static-nodes.json"), "[]")
    store().seedDatadir(165)
    Files.exists(root.resolve("ephemery-165/static-nodes.json")) shouldBe false
  }

  "earlierDatadirs" should "list the other iterations' datadirs by name, and find a pre-028 datadir" taggedAs UnitTest in {
    write(root.resolve("ephemery-163/rocksdb/a"), "x" * 1000)
    write(root.resolve("ephemery-164/rocksdb/a"), "x" * 2000)
    write(root.resolve("ephemery-164/rocksdb/b"), "x" * 24)
    write(root.resolve("ephemery-165/rocksdb/a"), "x" * 10)
    write(root.resolve("releases/ephemery-163/release.json"), "{}")
    write(root.resolve("logs/fukuii.log"), "log")
    val s = store()
    s.earlierDatadirs(except = 165) shouldBe List("ephemery-163", "ephemery-164")
    s.legacyDatadir() shouldBe None
    write(root.resolve("rocksdb/CURRENT"), "x" * 7)
    s.legacyDatadir() shouldBe Some(root.resolve("rocksdb"))
    s.earlierDatadirs(except = 165) shouldBe List("ephemery-163", "ephemery-164")
  }

  "pointCurrent" should "make current a symlink to releases/ephemery-N, and re-point it" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    store().pointCurrent(165) shouldBe Right(())
    Files.readSymbolicLink(root.resolve("current")).toString shouldBe "releases/ephemery-165"
    store().pointCurrent(166) shouldBe Right(())
    Files.readSymbolicLink(root.resolve("current")).toString shouldBe "releases/ephemery-166"
  }

  it should "leave a current that is not a symlink alone" taggedAs UnitTest in {
    write(root.resolve("current/mine"), "mine")
    store().pointCurrent(165).isLeft shouldBe true
    read(root.resolve("current/mine")) shouldBe "mine"
  }

  "seedDatadir" should "warn, not fail, when the seed cannot be copied" taggedAs UnitTest in {
    val seed = root.resolve("static-nodes.json")
    write(seed, "[]")
    Files.setPosixFilePermissions(seed, java.nio.file.attribute.PosixFilePermissions.fromString("---------"))
    try
      // a process that can read it anyway (root) cannot show the failure
      assume(!Files.isReadable(seed))
      val seeded = store().seedDatadir(165)
      Files.isDirectory(seeded.dir) shouldBe true
      seeded.warning.exists(_.contains("static-nodes.json")) shouldBe true
      Files.exists(seeded.dir.resolve("static-nodes.json")) shouldBe false
    finally Files.setPosixFilePermissions(seed, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
  }

  "sweepStages" should "skip the sweep when another writer holds the root's lock" taggedAs UnitTest in {
    val stale = root.resolve(".stage-dead")
    write(stale.resolve("x"), "x")
    Files.setLastModifiedTime(stale, FileTime.from(now.minus(java.time.Duration.ofHours(3))))
    val held = StageSupport.acquire(root)
    held shouldBe defined
    try
      store().sweepStages()
      Files.exists(stale) shouldBe true
    finally StageSupport.release(held)
    store().sweepStages()
    Files.exists(stale) shouldBe false
  }

  it should "remove stale .current-* temporary links too, and keep a fresh one" taggedAs UnitTest in {
    Files.createDirectories(root)
    def link(name: String, age: java.time.Duration): Path =
      val l = root.resolve(name)
      Files.createSymbolicLink(l, java.nio.file.Paths.get("releases/ephemery-165"))
      Files
        .getFileAttributeView(l, classOf[java.nio.file.attribute.BasicFileAttributeView], LinkOption.NOFOLLOW_LINKS)
        .setTimes(FileTime.from(now.minus(age)), null, null)
      l
    val old = link(".current-1", java.time.Duration.ofHours(2))
    val fresh = link(".current-2", java.time.Duration.ofMinutes(5))
    store().sweepStages()
    Files.exists(old, LinkOption.NOFOLLOW_LINKS) shouldBe false
    Files.exists(fresh, LinkOption.NOFOLLOW_LINKS) shouldBe true
  }
