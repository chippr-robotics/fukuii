package com.chipprbots.ethereum.scripts

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C5a part 5: `scripts/check-wal-writes.sh`. A sync's hand-off on the unified layout makes its rows
  * durable by one synced write, which syncs the write-ahead log and nothing else; the script fails on a write that goes
  * round the log.
  */
class WalWriteRatchetSpec extends AnyFlatSpec with Matchers:

  private val script: Path = Path.of("scripts/check-wal-writes.sh").toAbsolutePath

  final private case class Outcome(exit: Int, output: String)

  private def run(args: String*): Outcome =
    val process = new ProcessBuilder(("bash" +: script.toString +: args)*).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8)
    Outcome(process.waitFor(), output)

  private def check(sources: (String, String)*): Outcome =
    val root = Files.createTempDirectory("wal-write-ratchet")
    try
      sources.foreach { (file, text) =>
        val path = root.resolve(file)
        Files.createDirectories(path.getParent)
        Files.writeString(path, text)
      }
      run("--root", root.toString)
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))

  private val throughTheLog =
    """package a
      |class Store(db: RocksDB):
      |  // setDisableWAL(true) is what this must never do
      |  def write(batch: WriteBatch, sync: Boolean) = db.write(new WriteOptions().setSync(sync), batch)
      |""".stripMargin

  "The write-ahead log ratchet" should "pass this repository" taggedAs UnitTest in {
    val outcome = run()
    withClue(outcome.output)(outcome.exit shouldBe 0)
  }

  it should "pass a tree that writes through the log, a comment that names the call and all" taggedAs UnitTest in {
    val outcome = check("src/main/scala/a/Store.scala" -> throughTheLog)
    withClue(outcome.output)(outcome.exit shouldBe 0)
  }

  Seq(
    "the log turned off" -> "new WriteOptions().setDisableWAL(true)",
    "a file of rows ingested" -> "db.ingestExternalFile(files, new IngestExternalFileOptions())",
    "a file of rows written" -> "new SstFileWriter(envOptions, options)"
  ).foreach { (what, call) =>
    it should s"fail on $what, and name the file and the line" taggedAs UnitTest in {
      val outcome = check(
        "src/main/scala/a/Store.scala" -> throughTheLog,
        "src/main/scala/a/Bulk.scala" -> s"package a\nclass Bulk:\n  def load() = $call\n"
      )
      withClue(outcome.output)(outcome.exit shouldBe 1)
      outcome.output should include("src/main/scala/a/Bulk.scala:3:")
      (outcome.output should not).include("Store.scala")
    }
  }

  it should "look in the main sources of a module as well" taggedAs UnitTest in {
    val outcome = check("crypto/src/main/scala/b/Bulk.scala" -> "class Bulk:\n  val o = options.setDisableWAL(true)\n")
    withClue(outcome.output)(outcome.exit shouldBe 1)
  }

  it should "not look in test sources: a test may write as it likes" taggedAs UnitTest in {
    val outcome = check(
      "src/main/scala/a/Store.scala" -> throughTheLog,
      "src/test/scala/a/StoreSpec.scala" -> "class StoreSpec:\n  val o = options.setDisableWAL(true)\n"
    )
    withClue(outcome.output)(outcome.exit shouldBe 0)
  }

  it should "say it could not run on a tree with no main sources" taggedAs UnitTest in {
    run("--root", Files.createTempDirectory("empty").toString).exit shouldBe 2
  }
