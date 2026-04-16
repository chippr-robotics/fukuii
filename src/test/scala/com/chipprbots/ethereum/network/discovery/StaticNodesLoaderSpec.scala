package com.chipprbots.ethereum.network.discovery

import java.io.File
import java.nio.file.Files

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StaticNodesLoaderSpec extends AnyFlatSpec with Matchers {

  "StaticNodesLoader" should "load valid static nodes from a JSON file" in {
    val tempDir = Files.createTempDirectory("fukuii-test").toFile
    val staticNodesFile = new File(tempDir, "static-nodes.json")

    val jsonContent =
      """[
        |  "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076",
        |  "enode://a335a7e86eab05929266de232bec201a49fdcfc1115e8f8b861656e8afb3a6e5d3ffd172d153ae6c080401a56e3d620db2ac0695038a19e9b0c5220212651493@192.168.1.2:9076"
        |]""".stripMargin

    Files.write(staticNodesFile.toPath, jsonContent.getBytes)

    try {
      val nodes = StaticNodesLoader.loadFromFile(staticNodesFile.getAbsolutePath)

      nodes should have size 2
      nodes should contain(
        "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076"
      )
      nodes should contain(
        "enode://a335a7e86eab05929266de232bec201a49fdcfc1115e8f8b861656e8afb3a6e5d3ffd172d153ae6c080401a56e3d620db2ac0695038a19e9b0c5220212651493@192.168.1.2:9076"
      )
    } finally {
      staticNodesFile.delete()
      tempDir.delete()
    }
  }

  it should "return empty set for non-existent file" in {
    val nodes = StaticNodesLoader.loadFromFile("/nonexistent/path/static-nodes.json")
    nodes shouldBe empty
  }

  it should "return empty set for invalid JSON" in {
    val tempDir = Files.createTempDirectory("fukuii-test").toFile
    val staticNodesFile = new File(tempDir, "static-nodes.json")

    val invalidJson = "{ invalid json }"
    Files.write(staticNodesFile.toPath, invalidJson.getBytes)

    try {
      val nodes = StaticNodesLoader.loadFromFile(staticNodesFile.getAbsolutePath)
      nodes shouldBe empty
    } finally {
      staticNodesFile.delete()
      tempDir.delete()
    }
  }

  it should "filter out invalid enode URLs" in {
    val tempDir = Files.createTempDirectory("fukuii-test").toFile
    val staticNodesFile = new File(tempDir, "static-nodes.json")

    val jsonContent =
      """[
        |  "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076",
        |  "http://invalid-url",
        |  "not-an-enode"
        |]""".stripMargin

    Files.write(staticNodesFile.toPath, jsonContent.getBytes)

    try {
      val nodes = StaticNodesLoader.loadFromFile(staticNodesFile.getAbsolutePath)

      nodes should have size 1
      nodes should contain(
        "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076"
      )
    } finally {
      staticNodesFile.delete()
      tempDir.delete()
    }
  }

  it should "handle empty JSON array" in {
    val tempDir = Files.createTempDirectory("fukuii-test").toFile
    val staticNodesFile = new File(tempDir, "static-nodes.json")

    val jsonContent = "[]"
    Files.write(staticNodesFile.toPath, jsonContent.getBytes)

    try {
      val nodes = StaticNodesLoader.loadFromFile(staticNodesFile.getAbsolutePath)
      nodes shouldBe empty
    } finally {
      staticNodesFile.delete()
      tempDir.delete()
    }
  }

  it should "load from datadir" in {
    val tempDir = Files.createTempDirectory("fukuii-test").toFile
    val staticNodesFile = new File(tempDir, "static-nodes.json")

    val jsonContent =
      """[
        |  "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076"
        |]""".stripMargin

    Files.write(staticNodesFile.toPath, jsonContent.getBytes)

    try {
      val nodes = StaticNodesLoader.loadFromDatadir(tempDir.getAbsolutePath)

      nodes should have size 1
      nodes should contain(
        "enode://6eecbdcc74c0b672ce505b9c639c3ef2e8ee8cddd8447ca7ab82c65041932db64a9cd4d7e723ba180b0c3d88d1f0b2913fda48972cdd6742fea59f900af084af@192.168.1.1:9076"
      )
    } finally {
      staticNodesFile.delete()
      tempDir.delete()
    }
  }
}
