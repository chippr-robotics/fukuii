package com.chipprbots.ethereum.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

import scala.jdk.CollectionConverters.*

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs
import com.chipprbots.ethereum.testing.Tags.*

/** What `fukuii cli ephemery-fetch` exits with, through the launcher's own entry (which `main` passes to `sys.exit`).
  */
class CliLauncherSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
  private var tmp: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = tmp = Files.createTempDirectory("eph-launcher-test")
  override def afterEach(): Unit =
    def delete(p: Path): Unit =
      if Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) then
        Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)
    delete(tmp)

  private def run(args: String*): Option[Int] =
    val sink = new PrintStream(new ByteArrayOutputStream, true, "UTF-8")
    CliLauncher.ephemeryFetch(args.toSeq, Map.empty, clock, sink, sink)

  "CliLauncher" should "exit 0 for a release it writes" taggedAs (UnitTest) in {
    run(
      "ephemery-fetch",
      "--from-dir",
      EphemeryTestInputs.releaseDir(165).toString,
      "--out",
      tmp.resolve("o").toString
    ) shouldBe Some(0)
    Files.isDirectory(tmp.resolve("o/ephemery-165/chains")) shouldBe true
  }

  it should "exit 2 for a usage error" taggedAs (UnitTest) in {
    run("ephemery-fetch", "--release", "x") shouldBe Some(2)
    run("ephemery-fetch", "--bogus") shouldBe Some(2)
  }

  it should "exit 78 for a release that is refused" taggedAs (UnitTest) in {
    val bad = Files.createDirectories(tmp.resolve("bad"))
    List("retention.vars", "deposit_contract_block_hash.txt").foreach(f =>
      Files.copy(EphemeryTestInputs.releaseDir(165).resolve(f), bad.resolve(f))
    )
    Files.write(bad.resolve("genesis.json"), "{}".getBytes(StandardCharsets.UTF_8))
    run("ephemery-fetch", "--from-dir", bad.toString, "--out", tmp.resolve("o").toString) shouldBe Some(78)
    Files.exists(tmp.resolve("o/ephemery-165")) shouldBe false
  }

  it should "leave every other command to the generic parser" taggedAs (UnitTest) in {
    run("generate-private-key") shouldBe None
    run() shouldBe None
  }
