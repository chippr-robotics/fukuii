package com.chipprbots.ethereum.ephemery

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.TarFixture.*
import com.chipprbots.ethereum.testing.Tags.*

class StrictTarReaderSpec extends AnyFlatSpec with Matchers:

  private val Excludes = Set("parsedBeaconState.json", "bootnode-keys/")
  private val ElNames = Set("genesis.json", "retention.vars", "enodes.txt")

  private def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      val s = Files.walk(p)
      try s.iterator().asScala.toList.reverse.foreach(Files.delete)
      finally s.close()

  private def withStage[A](f: Path => A): A =
    val stage = Files.createTempDirectory("e028-stage")
    try f(stage)
    finally deleteTree(stage)

  private def read(
      bytes: Array[Byte],
      stage: Path,
      limits: TarLimits = TarLimits(),
      el: Set[String] = ElNames,
      excludes: Set[String] = Excludes
  ): Either[TarRefusal, TarResult] =
    StrictTarReader.read(new ByteArrayInputStream(bytes), el, stage, excludes, limits)

  private def refusal(bytes: Array[Byte], limits: TarLimits = TarLimits()): TarRefusal =
    withStage { stage =>
      val r = read(bytes, stage, limits)
      withClue(s"expected a refusal, got $r: ")(r.isLeft shouldBe true)
      r.left.getOrElse(fail())
    }

  private val one = "x".getBytes(StandardCharsets.UTF_8)

  "StrictTarReader" should "refuse bad names, each with its own message" taggedAs UnitTest in {
    val cases = Seq(
      "an absolute name" -> file("/etc/passwd", one) -> "absolute name",
      "a drive letter" -> file("C:foo", one) -> "drive letter",
      "dotdot" -> file("..", one) -> "'..'",
      "dotdot in the middle" -> file("a/../b", one) -> "'..'",
      "escaping dotdot" -> file("a/../../x", one) -> "'..'",
      "a backslash" -> file("a\\b", one) -> "backslash",
      "a dot segment" -> file("a/./b", one) -> "'.' segment",
      "an empty segment" -> file("a//b", one) -> "empty",
      "a name over 255 bytes" -> longNamed("d/" + "n" * 260, one) -> "name over 255 bytes",
      "nine levels" -> file("a/b/c/d/e/f/g/h/i", one) -> "more than 8 levels"
    )
    cases.foreach { case (((label, archive), fragment)) =>
      val r = refusal(tgz(archive))
      withClue(s"$label: ")(r.message should include(fragment))
    }
  }

  it should "refuse a NUL inside a GNU long name and a long name over 1 KiB" taggedAs UnitTest in {
    val withNul = "a/b\u0000c".getBytes(StandardCharsets.UTF_8) :+ 0.toByte
    val nulArchive = header("././@LongLink", 'L', withNul.length.toLong) ++ padded(withNul) ++ file("x", one)
    refusal(tgz(nulArchive)).message should include("NUL inside a GNU long name")

    val big = ("n" * 1100).getBytes(StandardCharsets.UTF_8) :+ 0.toByte
    val bigArchive = header("././@LongLink", 'L', big.length.toLong) ++ padded(big) ++ file("x", one)
    refusal(tgz(bigArchive)).message should include("GNU long name of 1101 bytes")
  }

  it should "accept exactly eight levels, and refuse a duplicate name" taggedAs UnitTest in {
    withStage { stage =>
      val ok = read(tgz(file("a/b/c/d/e/f/g/h", one)), stage)
      ok.isRight shouldBe true
    }
    refusal(tgz(file("a.txt", one), file("a.txt", one))).message should include("duplicate name")
    // a later entry that differs only in case would collide on a case-insensitive file system
    refusal(tgz(file("a.txt", one), file("A.TXT", one))).message should include("duplicate name")
  }

  it should "refuse every non-file entry type, naming the entry" taggedAs UnitTest in {
    val kinds = Seq(
      '1' -> "hard link",
      '2' -> "symlink",
      '3' -> "character device",
      '4' -> "block device",
      '6' -> "fifo",
      'S' -> "sparse entry",
      'x' -> "pax extended header",
      'g' -> "pax global header",
      'K' -> "GNU long link target"
    )
    kinds.foreach { case (flag, text) =>
      val r = refusal(tgz(file("evil", typeflag = flag)))
      withClue(s"typeflag $flag: ") {
        r.message should include(text)
        r.message should include("'evil'")
      }
    }
  }

  it should "refuse too many entries, an entry over 64 MiB, a total over 128 MiB and a compressed size over 32 MiB" taggedAs
    UnitTest in {
      TarLimits() shouldBe TarLimits(256, 64L << 20, 128L << 20, 32L << 20, 50, 255, 8, 1024, 1L << 20)

      // 257 entries with the default limit; 256 is accepted
      val names = (0 until 257).map(i => s"f$i")
      refusal(tgz(names.map(file(_, one))*)).message should include("more than 256 entries")
      withStage(stage => read(tgz(names.take(256).map(file(_, one))*), stage).isRight shouldBe true)

      // a header that claims 64 MiB + 1 is refused before any data is read (default limit)
      refusal(tar(header("big", '0', (64L << 20) + 1)) |> gz).message should include("entry over 67108864 bytes")

      // the byte counts are of bytes actually read: small limits make each rule reachable with small input
      val data = new Array[Byte](10000)
      new java.util.Random(7).nextBytes(data) // incompressible, so the ratio rule stays out of the way
      val loose = TarLimits(maxRatio = 10000)
      refusal(tgz(file("a", data)), loose.copy(maxEntryBytes = 9999)).message should include("entry over 9999 bytes")
      refusal(tgz(file("a", data), file("b", data)), loose.copy(maxTotalBytes = 15000)).message should include(
        "more than 15000 bytes in total"
      )
      refusal(tgz(file("a", data)), loose.copy(maxCompressedBytes = 5000)).message should include(
        "compressed size over 5000 bytes"
      )
    }

  it should "refuse an expansion ratio over 50 as the bytes are read" taggedAs UnitTest in {
    // 20 MiB of zeros compresses to about 20 KiB: ratio about 1000, under every size limit
    val bomb = new Array[Byte](20 << 20)
    val r = refusal(tgz(file("zeros", bomb)))
    r.message should include("expansion ratio over 50")
    r.entry shouldBe Some("zeros")
  }

  it should "refuse a truncated archive and a bad header checksum" taggedAs UnitTest in {
    val full = tgz(file("a", one), file("b", one))
    refusal(full.take(full.length - 10)).message should include("truncated")
    // a valid gzip of a tar that stops in the middle of an entry's data
    val cut = tar(file("a", new Array[Byte](2000)))
    refusal(gz(cut.take(600))).message should include("truncated")
    // a valid gzip of a tar with no end-of-archive marker
    refusal(gz(file("a", one))).message should include("no end-of-archive marker")
    refusal(tgz(header("a", badChecksum = true))).message should include("bad header checksum")
    refusal("not gzip at all".getBytes(StandardCharsets.UTF_8)).message should include("bad gzip stream")
    refusal(tgz(header("a", gnu = true).updated(0, 'Z'.toByte))).message should include("bad header checksum")
    val noMagic = header("a")
    java.util.Arrays.fill(noMagic, 257, 265, 0.toByte)
    refusal(tgz(noMagic)).message should include("unknown magic")
  }

  it should "refuse a stage that is not an empty directory" taggedAs UnitTest in {
    withStage { stage =>
      Files.writeString(stage.resolve("old"), "x")
      read(tgz(file("a", one)), stage).left.map(_.rule) shouldBe Left("the stage is not empty")
      Files.exists(stage.resolve("a")) shouldBe false
    }
    withStage { stage =>
      read(tgz(file("a", one)), stage.resolve("missing")).left.map(_.rule) shouldBe Left("the stage is not a directory")
    }
  }

  it should "refuse a file whose name needs a directory that is a file" taggedAs UnitTest in {
    refusal(tgz(file("a", one), file("a/b", one))).message should include("conflicts with a file")
  }

  it should "accept GNU long names up to 1 KiB of header, within the 255 byte name rule, and both magics" taggedAs
    UnitTest in {
      val longName = "d1/d2/" + "n" * 190 + ".txt"
      withStage { stage =>
        val r = read(tgz(longNamed(longName, "long".getBytes(StandardCharsets.UTF_8))), stage)
        r.isRight shouldBe true
        Files.readString(stage.resolve(longName)) shouldBe "long"
      }
      withStage { stage =>
        read(tgz(file("gnu.txt", one, gnu = true), file("posix.txt", one, gnu = false)), stage).isRight shouldBe true
        Files.readString(stage.resolve("posix.txt")) shouldBe "x"
      }
      // POSIX prefix field joins the name; GNU headers ignore it
      withStage { stage =>
        val prefixed = header("leaf.txt", size = 1, gnu = false, prefix = "dir") ++ padded(one)
        read(tgz(prefixed), stage).isRight shouldBe true
        Files.exists(stage.resolve("dir/leaf.txt")) shouldBe true
      }
    }

  it should "write 0644 files and 0755 directories whatever the archive says" taggedAs UnitTest in {
    withStage { stage =>
      val setuid = 2541 // 04755
      val r =
        read(tgz(dir("d", mode = 448), file("d/exec.sh", one, mode = setuid), file("plain", one, mode = 493)), stage)
      r.isRight shouldBe true
      PosixFilePermissions.toString(Files.getPosixFilePermissions(stage.resolve("d"))) shouldBe "rwxr-xr-x"
      PosixFilePermissions.toString(Files.getPosixFilePermissions(stage.resolve("d/exec.sh"))) shouldBe "rw-r--r--"
      PosixFilePermissions.toString(Files.getPosixFilePermissions(stage.resolve("plain"))) shouldBe "rw-r--r--"
    }
  }

  it should "accept the 29 names and types of the real ephemery-165 archive" taggedAs UnitTest in {
    val keys = Seq(
      "bootnode_eph2.key.enc",
      "bootnode_pk.enr",
      "bootnode_pk2.key.enc",
      "bootnode_pk2.enr",
      "bootnode_pk.key.enc",
      "bootnode_eph1.enr",
      "bootnode_eph1.key.enc",
      "bootnode_eph2.enr"
    ).map("bootnode-keys/" + _)
    val before = Seq("boot_enode.txt", "boot_enr.txt", "boot_enr.yaml")
    val after = Seq(
      "bootnode.txt",
      "bootstrap_nodes.txt",
      "bootstrap_nodes.yaml",
      "config.yaml",
      "deploy_block.txt",
      "deposit_contract.txt",
      "deposit_contract_block.txt",
      "deposit_contract_block_hash.txt",
      "enodes.txt",
      "genesis.json",
      "genesis.ssz",
      "genesis_validators_root.txt",
      "nodevars_env.txt",
      "parsedBeaconState.json",
      "retention.vars",
      "validator-names.yaml",
      "validator_names.yaml"
    )
    def content(n: String): Array[Byte] = s"content of $n".getBytes(StandardCharsets.UTF_8)
    val parts = before.map(n => file(n, content(n), mode = 420)) ++ Seq(dir("bootnode-keys/")) ++
      keys.map(n => file(n, content(n), mode = 493)) ++ after.map(n => file(n, content(n)))
    parts.size shouldBe 29
    withStage { stage =>
      val r = read(tgz(parts*), stage).getOrElse(fail("the real listing must be accepted"))
      r.entries shouldBe 29
      r.el.keySet shouldBe ElNames
      r.el("genesis.json").toSeq shouldBe content("genesis.json").toSeq
      r.staged.toSet shouldBe (before ++ after).toSet - "parsedBeaconState.json"
      Files.exists(stage.resolve("parsedBeaconState.json")) shouldBe false
      Files.exists(stage.resolve("bootnode-keys")) shouldBe false
      Files.readString(stage.resolve("config.yaml")) shouldBe "content of config.yaml"
    }
  }

  /** The pipe is a tiny helper so a `tar(...)` can be gzipped in an expression. */
  extension (b: Array[Byte]) private def |>(f: Array[Byte] => Array[Byte]): Array[Byte] = f(b)
