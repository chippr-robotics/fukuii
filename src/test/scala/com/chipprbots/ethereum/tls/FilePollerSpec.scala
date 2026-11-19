package com.chipprbots.ethereum.tls

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 research D-13: a change is the SHA-256 of the content, so it is seen whatever the clock or the file's time
  * says, and through the symlink swap Kubernetes uses to update a mounted secret.
  */
class FilePollerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private var dir: Path = scala.compiletime.uninitialized

  override protected def beforeEach(): Unit = dir = TlsTestSupport.tempDir("poller")
  override protected def afterEach(): Unit = TlsTestSupport.deleteTree(dir)

  private def write(file: Path, text: String): Path = Files.writeString(file, text)

  "A FilePoller" should "report nothing while the files are unchanged, and a change once" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val a = write(dir.resolve("a"), "one")
    val b = write(dir.resolve("b"), "two")
    val poller = new FilePoller(Seq(a, b))
    poller.changed() shouldBe false
    write(b, "three")
    poller.changed() shouldBe true
    poller.changed() shouldBe false
  }

  it should "see an edit that keeps the size and the modification time" taggedAs (UnitTest, RPCTest) in {
    val f = write(dir.resolve("keystore"), "AAAA")
    val time = Files.getLastModifiedTime(f)
    val poller = new FilePoller(Seq(f))
    write(f, "BBBB")
    Files.setLastModifiedTime(f, time)
    Files.getLastModifiedTime(f) shouldBe time
    poller.changed() shouldBe true
  }

  it should "not report a touch that leaves the content as it was" taggedAs (UnitTest, RPCTest) in {
    val f = write(dir.resolve("keystore"), "same")
    val poller = new FilePoller(Seq(f))
    Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 60000))
    poller.changed() shouldBe false
  }

  it should "see a Kubernetes ..data symlink swap" taggedAs (UnitTest, RPCTest) in {
    // <dir>/..2026_1/tls.p12 and <dir>/..2026_2/tls.p12; <dir>/..data -> ..2026_1; <dir>/tls.p12 -> ..data/tls.p12
    val v1 = Files.createDirectory(dir.resolve("..2026_1"))
    val v2 = Files.createDirectory(dir.resolve("..2026_2"))
    write(v1.resolve("tls.p12"), "certificate A")
    write(v2.resolve("tls.p12"), "certificate B")
    val data = dir.resolve("..data")
    Files.createSymbolicLink(data, v1.getFileName)
    val mounted = Files.createSymbolicLink(dir.resolve("tls.p12"), dir.getFileSystem.getPath("..data", "tls.p12"))
    val poller = new FilePoller(Seq(mounted))
    // the swap: a new link renamed over ..data, as the kubelet does
    val next = Files.createSymbolicLink(dir.resolve("..data_tmp"), v2.getFileName)
    Files.move(next, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    Files.readString(mounted) shouldBe "certificate B"
    poller.changed() shouldBe true
  }

  it should "count a file that disappears, and its return, as changes" taggedAs (UnitTest, RPCTest) in {
    val f = write(dir.resolve("password"), "pw")
    val poller = new FilePoller(Seq(f))
    Files.delete(f)
    poller.changed() shouldBe true
    poller.changed() shouldBe false
    write(f, "pw")
    poller.changed() shouldBe true
  }
