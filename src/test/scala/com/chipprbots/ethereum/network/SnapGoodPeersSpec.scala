package com.chipprbots.ethereum.network

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

class SnapGoodPeersSpec extends AnyFlatSpec with Matchers:

  private val idA = "aa" * 64
  private val idB = "bb" * 64
  private val idC = "cc" * 64
  private val T0 = 1_000_000_000L

  private def cfg(
      file: Path,
      maxEntries: Int = 16,
      maxFailedDials: Int = 3,
      maxAge: FiniteDuration = 7.days
  ): SnapGoodPeersConfig =
    SnapGoodPeersConfig(
      enabled = true,
      maxEntries = maxEntries,
      halfLife = 1.hour,
      maxFailedDials = maxFailedDials,
      maxAge = maxAge,
      redialInterval = 5.minutes,
      file = file
    )

  private def tmpFile(): Path = Files.createTempDirectory("snap-good-peers").resolve("snap-good-peers.v1")

  "SnapGoodPeers" should "rank candidates by decayed served score" taggedAs (UnitTest, NetworkTest) in {
    val s = new SnapGoodPeers(cfg(tmpFile()))
    s.recordServed(idA, "10.0.0.1", 30303, weight = 5.0, nowMs = T0)
    s.recordServed(idB, "10.0.0.2", 30303, weight = 3.0, nowMs = T0)
    s.candidates(T0).map(_._1) shouldBe Seq(idA, idB)
    // A goes quiet for two half-lives; B keeps serving: B overtakes A.
    s.recordServed(idB, "10.0.0.2", 30303, weight = 1.0, nowMs = T0 + 2.hours.toMillis)
    s.candidates(T0 + 2.hours.toMillis).map(_._1) shouldBe Seq(idB, idA)
    s.candidates(T0).head._2.toString shouldBe s"enode://$idA@10.0.0.1:30303"
  }

  it should "stay bounded, evicting the lowest decayed score" taggedAs (UnitTest, NetworkTest) in {
    val s = new SnapGoodPeers(cfg(tmpFile(), maxEntries = 2))
    s.recordServed(idA, "10.0.0.1", 30303, 5.0, T0)
    s.recordServed(idB, "10.0.0.2", 30303, 1.0, T0)
    s.recordServed(idC, "10.0.0.3", 30303, 3.0, T0)
    s.size shouldBe 2
    s.contains(idB) shouldBe false
  }

  it should "drop an entry after maxFailedDials attempts without a handshake, and reset on handshake" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val s = new SnapGoodPeers(cfg(tmpFile(), maxFailedDials = 3))
    s.recordServed(idA, "10.0.0.1", 30303, 1.0, T0)
    s.recordDialAttempt(idA)
    s.recordDialAttempt(idA)
    s.failedDials(idA) shouldBe Some(2)
    s.markConnected(idA) shouldBe true
    s.failedDials(idA) shouldBe Some(0)
    (1 to 3).foreach(_ => s.recordDialAttempt(idA))
    s.contains(idA) shouldBe true
    s.recordDialAttempt(idA)
    s.contains(idA) shouldBe false
  }

  it should "prune entries that have not served within maxAge" taggedAs (UnitTest, NetworkTest) in {
    val s = new SnapGoodPeers(cfg(tmpFile(), maxAge = 1.day))
    s.recordServed(idA, "10.0.0.1", 30303, 1.0, T0)
    s.candidates(T0 + 2.days.toMillis) shouldBe empty
    s.prune(T0 + 2.days.toMillis) shouldBe 1
    s.size shouldBe 0
  }

  it should "round-trip through the versioned file" taggedAs (UnitTest, NetworkTest) in {
    val file = tmpFile()
    val s = new SnapGoodPeers(cfg(file))
    s.recordServed(idA, "10.0.0.1", 30303, 5.0, T0)
    s.recordServed(idB, "10.0.0.2", 30304, 2.0, T0)
    s.recordDialAttempt(idB)
    s.save()
    Files.readAllLines(file).get(0) shouldBe SnapGoodPeers.Header

    val loaded = new SnapGoodPeers(cfg(file))
    loaded.load(T0)
    loaded.candidates(T0).map(_._1) shouldBe Seq(idA, idB)
    loaded.failedDials(idB) shouldBe Some(1)
  }

  it should "load as empty from a missing, wrong-version or garbage file, and skip malformed lines" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val missing = new SnapGoodPeers(cfg(tmpFile()))
    missing.load(T0)
    missing.size shouldBe 0

    def write(content: String): Path =
      val f = tmpFile()
      Files.write(f, content.getBytes(StandardCharsets.UTF_8))
      f

    val wrongVersion = new SnapGoodPeers(cfg(write(s"fukuii-snap-good-peers v9\n$idA\th\t1\t1.0\t1\t0\n")))
    wrongVersion.load(T0)
    wrongVersion.size shouldBe 0

    val binary = new SnapGoodPeers(cfg(write("\u0000\u0001garbage")))
    binary.load(T0)
    binary.size shouldBe 0

    val mixed = new SnapGoodPeers(
      cfg(
        write(
          s"${SnapGoodPeers.Header}\n" +
            s"$idA\t10.0.0.1\t30303\t2.5\t$T0\t0\n" +
            "not a valid line\n" +
            s"$idB\t10.0.0.2\tNaNport\t1.0\t$T0\t0\n" +
            s"zz\t10.0.0.3\t30303\t1.0\t$T0\t0\n" +
            s"$idC\t10.0.0.4\t30303\t-4.0\t$T0\t0\n"
        )
      )
    )
    mixed.load(T0)
    mixed.candidates(T0).map(_._1) shouldBe Seq(idA)
  }

  it should "not throw when the file cannot be written" taggedAs (UnitTest, NetworkTest) in {
    val dir = Files.createTempDirectory("snap-good-peers-ro")
    val blocker = dir.resolve("blocker")
    Files.write(blocker, Array[Byte](1))
    // parent of the target is a regular file, so createDirectories/write must fail
    val s = new SnapGoodPeers(cfg(blocker.resolve("snap-good-peers.v1")))
    s.recordServed(idA, "10.0.0.1", 30303, 1.0, T0)
    noException should be thrownBy s.save()
  }
