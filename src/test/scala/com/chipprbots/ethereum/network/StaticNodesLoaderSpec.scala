package com.chipprbots.ethereum.network

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for StaticNodesLoader.
  *
  * Besu reference: StaticNodesParser.java (fromPath / readEnodesFromPath / decodeString) Behaviour traced:
  *   - Missing file → empty Seq (not an error)
  *   - Empty file or empty array → empty Seq
  *   - Valid enode URLs → URI objects with scheme="enode", userInfo=pubkey, host, port
  *   - Invalid port / missing port → skip with warning
  *   - Malformed JSON → empty Seq with warning
  *   - Mixed valid/invalid → only valid entries returned
  */
class StaticNodesLoaderSpec extends AnyFlatSpec with Matchers {

  private val validEnode1 =
    "enode://8f977a3c99a2f3d3beaebf3cc62cf73a7f09c41b4e8cfa06e4e3bbca82d7e66a5a7ef638a4c57b4b07c9c6caac0e2f4f1e9b5f3e6c7a12345678@127.0.0.1:30303"
  private val validEnode2 =
    "enode://1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890@10.0.0.1:30304"

  private def withTempDir(body: Path => Unit): Unit = {
    val dir = Files.createTempDirectory("static-nodes-test")
    try body(dir)
    finally {
      dir.toFile.listFiles().foreach(_.delete())
      dir.toFile.delete()
    }
  }

  // ── missing file ──────────────────────────────────────────────────────────

  "StaticNodesLoader" should "return empty when static-nodes.json does not exist" taggedAs UnitTest in {
    withTempDir { dir =>
      val result = StaticNodesLoader.load(dir)
      result shouldBe empty
    }
  }

  // ── empty file / empty array ──────────────────────────────────────────────

  it should "return empty for an empty file" taggedAs UnitTest in {
    withTempDir { dir =>
      Files.write(dir.resolve(StaticNodesLoader.FileName), Array.emptyByteArray)
      StaticNodesLoader.load(dir) shouldBe empty
    }
  }

  it should "return empty for an empty JSON array" taggedAs UnitTest in {
    withTempDir { dir =>
      Files.write(dir.resolve(StaticNodesLoader.FileName), "[]".getBytes("UTF-8"))
      StaticNodesLoader.load(dir) shouldBe empty
    }
  }

  // ── valid entries ─────────────────────────────────────────────────────────

  it should "parse a single valid enode URL" taggedAs UnitTest in {
    withTempDir { dir =>
      val json = s"""["$validEnode1"]"""
      Files.write(dir.resolve(StaticNodesLoader.FileName), json.getBytes("UTF-8"))
      val result = StaticNodesLoader.load(dir)
      result should have size 1
      result.head.getScheme shouldBe "enode"
      result.head.getHost shouldBe "127.0.0.1"
      result.head.getPort shouldBe 30303
    }
  }

  it should "parse multiple valid enode URLs" taggedAs UnitTest in {
    withTempDir { dir =>
      val json = s"""["$validEnode1", "$validEnode2"]"""
      Files.write(dir.resolve(StaticNodesLoader.FileName), json.getBytes("UTF-8"))
      val result = StaticNodesLoader.load(dir)
      result should have size 2
      (result.map(_.getHost) should contain).allOf("127.0.0.1", "10.0.0.1")
    }
  }

  // ── invalid entries skipped ───────────────────────────────────────────────

  it should "skip an enode URL with port -1 (missing port)" taggedAs UnitTest in {
    withTempDir { dir =>
      val noPort = "enode://8f977a3c99a2f3d3beaebf3cc62cf73a7f09c41b4e8cfa06e4e3bbca82d7e66a5a7ef638@127.0.0.1"
      val json = s"""["$noPort"]"""
      Files.write(dir.resolve(StaticNodesLoader.FileName), json.getBytes("UTF-8"))
      // URI.getPort returns -1 when no port is specified — isValidEnodeUri returns false
      StaticNodesLoader.load(dir) shouldBe empty
    }
  }

  it should "return empty for malformed JSON" taggedAs UnitTest in {
    withTempDir { dir =>
      Files.write(dir.resolve(StaticNodesLoader.FileName), "{bad json".getBytes("UTF-8"))
      StaticNodesLoader.load(dir) shouldBe empty
    }
  }

  it should "return empty when file contains a JSON object instead of array" taggedAs UnitTest in {
    withTempDir { dir =>
      Files.write(dir.resolve(StaticNodesLoader.FileName), """{"enode": "not-an-array"}""".getBytes("UTF-8"))
      StaticNodesLoader.load(dir) shouldBe empty
    }
  }

  it should "skip non-string entries and return only valid ones" taggedAs UnitTest in {
    withTempDir { dir =>
      val json = s"""["$validEnode1", 42, null, "$validEnode2"]"""
      Files.write(dir.resolve(StaticNodesLoader.FileName), json.getBytes("UTF-8"))
      val result = StaticNodesLoader.load(dir)
      result should have size 2
    }
  }

  it should "skip entries with a wrong scheme" taggedAs UnitTest in {
    withTempDir { dir =>
      val wrongScheme = "http://8f977a3c@127.0.0.1:30303"
      val json = s"""["$wrongScheme", "$validEnode1"]"""
      Files.write(dir.resolve(StaticNodesLoader.FileName), json.getBytes("UTF-8"))
      val result = StaticNodesLoader.load(dir)
      result should have size 1
      result.head.getScheme shouldBe "enode"
    }
  }

  // ── load(String) overload ─────────────────────────────────────────────────

  it should "accept a String datadir path" taggedAs UnitTest in {
    withTempDir { dir =>
      val json = s"""["$validEnode1"]"""
      Files.write(dir.resolve(StaticNodesLoader.FileName), json.getBytes("UTF-8"))
      val result = StaticNodesLoader.load(dir.toString)
      result should have size 1
    }
  }
}
