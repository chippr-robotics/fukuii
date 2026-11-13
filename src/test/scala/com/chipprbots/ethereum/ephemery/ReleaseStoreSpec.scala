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

  it should "refuse to place over an entry directory that is not a valid entry, and delete nothing" taggedAs UnitTest in {
    write(root.resolve("releases/ephemery-165/release.json"), "not json")
    store().place(entry165).left.map(_.exitCode) shouldBe Left(ReleaseRefusal.ExitConfig)
    read(root.resolve("releases/ephemery-165/release.json")) shouldBe "not json"
    stages shouldBe Nil
  }

  "addTestnet" should "stage a testnet/ and rename it into an existing entry, once" taggedAs UnitTest in {
    store().place(entry165) shouldBe Right(Placed)
    store().addTestnet(165, releaseDir(165)) shouldBe Right(true)
    val dir = root.resolve("releases/ephemery-165")
    names(dir) shouldBe List("chains", "release.json", "testnet")
    read(dir.resolve("testnet/retention.vars")) shouldBe read(releaseDir(165).resolve("retention.vars"))
    // a second call finds it there and changes nothing
    write(dir.resolve("testnet/marker"), "kept")
    store().addTestnet(165, releaseDir(165)) shouldBe Right(false)
    read(dir.resolve("testnet/marker")) shouldBe "kept"
    stages shouldBe Nil
  }

  it should "refuse an entry that is not there" taggedAs UnitTest in {
    store().addTestnet(165, releaseDir(165)).isLeft shouldBe true
    Files.exists(root.resolve("releases/ephemery-165")) shouldBe false
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
    store().addTestnet(165, releaseDir(165)) shouldBe Right(true)
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
    val datadir = store().seedDatadir(165)
    datadir shouldBe root.resolve("ephemery-165")
    read(datadir.resolve("static-nodes.json")) shouldBe "[\"enode://a\"]"
    // the node's own copy is its own: a later change at the root does not reach it, and a second call changes nothing
    write(datadir.resolve("static-nodes.json"), "[\"enode://own\"]")
    write(root.resolve("static-nodes.json"), "[\"enode://b\"]")
    store().seedDatadir(165) shouldBe datadir
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

  "earlierDatadirs" should "list the other iterations' datadirs with their sizes, and find a pre-028 datadir" taggedAs UnitTest in {
    write(root.resolve("ephemery-163/rocksdb/a"), "x" * 1000)
    write(root.resolve("ephemery-164/rocksdb/a"), "x" * 2000)
    write(root.resolve("ephemery-164/rocksdb/b"), "x" * 24)
    write(root.resolve("ephemery-165/rocksdb/a"), "x" * 10)
    write(root.resolve("releases/ephemery-163/release.json"), "{}")
    write(root.resolve("logs/fukuii.log"), "log")
    val s = store()
    s.earlierDatadirs(except = 165) shouldBe List(("ephemery-163", 1000L), ("ephemery-164", 2024L))
    s.legacyDatadir() shouldBe None
    write(root.resolve("rocksdb/CURRENT"), "x" * 7)
    s.legacyDatadir() shouldBe Some((root.resolve("rocksdb"), 7L))
    s.earlierDatadirs(except = 165).map(_._1) shouldBe List("ephemery-163", "ephemery-164")
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
