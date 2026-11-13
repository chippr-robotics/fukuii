package com.chipprbots.ethereum.ephemery

import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.testing.Tags.*

/** The iteration built into the jar (spec 028 FR-002, R4): one entry, the files `ephemery-fetch` wrote from the raw 165
  * inputs, and a `release.json` that says what the library computes.
  */
class BundledEphemerySpec extends AnyFlatSpec with Matchers:

  private lazy val bundled: List[BundleSource.EntryText] = BundleSource.classpath.entries()
  private lazy val entry: ReleaseEntry =
    bundled.map(t => ReleaseEntry.verify(t.metadata, t.chainConf, t.genesisJson)) match
      case List(Right(e)) => e
      case other          => fail(s"the bundle is not one valid entry: $other")

  "the bundled iteration" should "be exactly one entry, ephemery-165" taggedAs UnitTest in {
    bundled.map(_.name) shouldBe List("ephemery-165")
    entry.release shouldBe "ephemery-165"
  }

  it should "carry the genesis hash the library computes, which is the hash the release publishes" taggedAs UnitTest in {
    val valid = EphemeryRelease.validate(raw(165)).fold(r => fail(r.message), identity)
    entry.genesisHash shouldBe valid.genesisHashHex
    entry.genesisHash shouldBe Hash165
  }

  it should "be the render of the committed raw 165 inputs, byte for byte" taggedAs UnitTest in {
    val valid = EphemeryRelease.validate(raw(165)).fold(r => fail(r.message), identity)
    entry.chainConf shouldBe valid.chainConf
    entry.genesisJson shouldBe valid.encodedGenesis
    entry.chainConf shouldBe fixture(165, "ephemery-chain.conf")
    entry.genesisJson shouldBe fixture(165, "ephemery-genesis.json")
  }

  it should "have a release.json that obeys contracts/release-json.md" taggedAs UnitTest in {
    val m = entry.metadata
    m.release shouldBe "ephemery-165"
    m.iteration shouldBe 165
    m.chainId shouldBe BigInt(39438165)
    m.chainId - EphemeryRelease.IterationBase shouldBe BigInt(m.iteration)
    m.genesisTimestamp shouldBe 1792695600L
    m.resetInterval shouldBe 2419200L
    m.resetTime shouldBe 1795114800L
    m.resetTime shouldBe m.genesisTimestamp + m.resetInterval
    m.source shouldBe "https://github.com/ephemery-testnet/ephemery-genesis/releases/tag/ephemery-165"
    ReleaseMetadata.sourceProblem(m.source) shouldBe None
    m.archiveSha256 shouldBe Some("c076b69e5cf37a27c1162ad37d8a8432ae086f80eefb53a388579fc4b6cc8757")
    ReleaseMetadata.decode(bundled.head.metadata) shouldBe Right(m)
  }

  it should "be refused when its files do not match its release.json" taggedAs UnitTest in {
    val text = bundled.head
    val tampered = text.genesisJson.replace("\"gasLimit\": \"0x2255100\"", "\"gasLimit\": \"0x2255101\"")
    tampered should not be text.genesisJson
    ReleaseEntry.verify(text.metadata, text.chainConf, tampered).isLeft shouldBe true
    ReleaseEntry.verify("{}", text.chainConf, text.genesisJson).isLeft shouldBe true
  }

  it should "be named by index.txt, which matches the bundled directories exactly" taggedAs UnitTest in {
    val index = Paths.get(getClass.getResource("/" + BundleSource.IndexFile).toURI)
    val listed = Files.readAllLines(index).asScala.map(_.trim).filter(_.nonEmpty).toList
    val dirs =
      val s = Files.list(index.getParent)
      try s.iterator().asScala.filter(Files.isDirectory(_)).map(_.getFileName.toString).toList.sorted
      finally s.close()
    listed shouldBe dirs
    listed shouldBe List("ephemery-165")
    // each is a directory of exactly the three files of an entry
    dirs.foreach { d =>
      val files = Files.list(index.getParent.resolve(d))
      val names =
        try files.iterator().asScala.map(_.getFileName.toString).toList.sorted
        finally files.close()
      names shouldBe List("ephemery-chain.conf", "ephemery-genesis.json", "release.json")
    }
  }

  it should "be read through the index: an iteration the index does not list is not found" taggedAs UnitTest in {
    BundleSource.classpath.entries().map(_.name) shouldBe List("ephemery-165")
    BundleSource.none.entries() shouldBe Nil
  }
