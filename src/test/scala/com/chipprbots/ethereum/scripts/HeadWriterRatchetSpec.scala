package com.chipprbots.ethereum.scripts

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4b part 3 (T140): the allow-list ratchet of the head's writers, `scripts/check-head-writers.sh`.
  * On the unified state layout the best block and the index of blocks by number move only with the state; the script
  * counts every write of either and every call of a head writer, and holds the counts to a list in which each line says
  * why that writer cannot move the head alone there. Its engine is the state accessors' ratchet's, which
  * `StateAccessorRatchetSpec` drives through every way it can fail; here, its entry points and this repository.
  */
class HeadWriterRatchetSpec extends AnyFlatSpec with Matchers:

  private val script: Path = Path.of("scripts/check-head-writers.sh").toAbsolutePath

  final private case class Outcome(exit: Int, output: String)

  private def run(args: String*): Outcome =
    val process = new ProcessBuilder(("bash" +: script.toString +: args)*).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8)
    Outcome(process.waitFor(), output)

  private def check(sources: Map[String, String], listed: String): Outcome =
    val root = Files.createTempDirectory("head-writer-ratchet")
    try
      (sources.map((file, text) => s"src/main/scala/$file" -> text) + ("allowlist.txt" -> listed)).foreach {
        (file, text) =>
          val path = root.resolve(file)
          Files.createDirectories(path.getParent)
          Files.writeString(path, text)
      }
      run("--root", root.toString, "--allowlist", root.resolve("allowlist.txt").toString)
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))

  private val writer = "a/Head.scala" ->
    """package a
      |class Head(appState: AppStateStorage, writer: BlockchainWriter):
      |  def best(b: BlockInfo) = appState.putBestBlockInfo(b).commit()
      |  def index(n: BigInt, h: ByteString) = blockNumberMappingStorage.put(n, h)
      |  def head(h: BlockHeader) = writer.promoteToCanonicalHead(h, reader)
      |  def block(b: Block) = writer.storeBlock(b)
      |""".stripMargin

  private val listed =
    """best-pointer 1 src/main/scala/a/Head.scala REFUSED on the unified layout
      |index-write 1 src/main/scala/a/Head.scala REFUSED on the unified layout
      |head-writer 1 src/main/scala/a/Head.scala MOVE through a move of the head
      |block-store 1 src/main/scala/a/Head.scala PRE before the anchor
      |""".stripMargin

  "The head writer ratchet" should "know the four kinds of writer, and pass a tree whose every one is listed" taggedAs
    UnitTest in {
      val outcome = check(Map(writer), listed)
      withClue(outcome.output)(outcome.exit shouldBe 0)
      // a tree with no main sources is no tree to check
      run("--root", Files.createTempDirectory("empty").toString, "--inventory").exit shouldBe 2
    }

  it should "say what a tree holds, kind by kind and file by file" taggedAs UnitTest in {
    val root = Files.createTempDirectory("head-writer-inventory")
    try
      val path = root.resolve("src/main/scala/a/Head.scala")
      Files.createDirectories(path.getParent)
      Files.writeString(path, writer._2)
      val inventory = run("--root", root.toString, "--inventory")
      inventory.exit shouldBe 0
      inventory.output.linesIterator.toSet shouldBe Set(
        "best-pointer 1 src/main/scala/a/Head.scala",
        "index-write 1 src/main/scala/a/Head.scala",
        "head-writer 1 src/main/scala/a/Head.scala",
        "block-store 1 src/main/scala/a/Head.scala"
      )
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))
  }

  it should "fail on a line whose reason does not start with what holds for the writer" taggedAs UnitTest in {
    val outcome = check(Map(writer), listed.replace("PRE before the anchor", "TODO later"))
    outcome.exit shouldBe 1
    outcome.output should include("NO VERDICT")
  }

  it should "fail on a writer that is not listed, and say the line the list would need" taggedAs UnitTest in {
    val added = "b/Stray.scala" -> "package b\nclass Stray:\n  def go = appState.putBestBlockNumber(7).commit()\n"
    val outcome = check(Map(writer, added), listed)
    outcome.exit shouldBe 1
    outcome.output should include("NOT LISTED: best-pointer in src/main/scala/b/Stray.scala")
    outcome.output should include("best-pointer 1 src/main/scala/b/Stray.scala")
    outcome.output should include("moves only with the state")
  }

  it should "fail on a writer that is gone, so that its line goes with it" taggedAs UnitTest in {
    val fewer = "a/Head.scala" -> writer._2.linesIterator.filterNot(_.contains("storeBlock")).mkString("\n")
    val outcome = check(Map(fewer), listed)
    outcome.exit shouldBe 1
    outcome.output should include("NO LONGER USED: block-store in src/main/scala/a/Head.scala")
  }

  "This repository" should "write the head only where the list says" taggedAs UnitTest in {
    val outcome = run()
    withClue(outcome.output)(outcome.exit shouldBe 0)
    outcome.output should include("all as listed")
  }
