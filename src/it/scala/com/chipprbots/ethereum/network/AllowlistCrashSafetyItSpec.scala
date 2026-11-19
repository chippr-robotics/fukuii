package com.chipprbots.ethereum.network

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Hex

/** Spec 030 SC-009, FR-057: the allowlist file is never left half-written. A child JVM edits it in a loop and is killed
  * at a different point each time; afterwards the file always parses and holds a whole list, the one before an edit or
  * the one after. Then 100 alternating `admin_` edits and edits by hand leave the in-memory list equal to the file.
  */
class AllowlistCrashSafetyItSpec extends AnyFlatSpec with Matchers:

  private val base = "aa" * 64
  private def node(i: Int): String = f"$i%02x" * 64

  "An allowlist edit killed at any point" should "leave the file parsing, as the old list or the new" taggedAs (
    IntegrationTest,
    NetworkTest
  ) in {
    val dir = Files.createTempDirectory("allowlist-crash")
    val file = dir.resolve("allowlist.json")
    Files.writeString(file, s"""["$base"]""")
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val classpath = System.getProperty("java.class.path")
    (1 to 20).foreach { round =>
      val child =
        new ProcessBuilder(java, "-cp", classpath, AllowlistEditChild.getClass.getName.stripSuffix("$"), file.toString)
          .redirectErrorStream(true)
          .start()
      val out = new BufferedReader(new InputStreamReader(child.getInputStream))
      // kill after a number of edits that differs every round, so the kill lands at varying points of an edit
      val edits =
        Iterator.continually(out.readLine()).takeWhile(_ != null).filter(_.startsWith("edited")).take(round).size
      child.destroyForcibly()
      child.waitFor()
      withClue(s"round $round, after $edits edits: ") {
        val parsed = NodeAllowlistStore.readFile(file, mustExist = true)
        parsed.isRight shouldBe true
        val ids = parsed.toOption.get._1.map(e => Hex.toHexString(e.id.toArray))
        // the child alternates adding one node and removing it: the list is the base, or the base and that one node
        ids.head shouldBe base
        ids.size should be <= 2
        Files.list(dir).filter(_.getFileName.toString.endsWith(".tmp")).count() should be <= 1L
      }
    }
  }

  "100 alternating admin_ and hand edits" should "leave the in-memory list equal to the file" taggedAs (
    IntegrationTest,
    NetworkTest
  ) in {
    val file = Files.createTempFile("allowlist-alternating", ".json")
    Files.writeString(file, s"""["$base"]""")
    val store = AllowlistEditChild.store(file)
    (1 to 100).foreach { i =>
      if i % 2 == 0 then store.edit(AllowlistEdit.Add, Seq(node(i))) shouldBe Right(1)
      else
        // an edit by hand, then the reload that would pick it up
        val onDisk = NodeAllowlistStore.readFile(file, mustExist = true).toOption.get._1
        Files.write(file, NodeAllowlistStore.render(onDisk :+ NodeAllowlist.parseEntry(node(i)).toOption.get))
        store.reload()
      val fileIds = NodeAllowlistStore.readFile(file, mustExist = true).toOption.get._1.map(_.id).toSet
      store.allowlist.snapshot.entries.keySet shouldBe fileIds
    }
  }

/** The child of the crash test: edits the file given as its argument forever, adding a node then removing it, and
  * prints `edited <n>` after each edit returns.
  */
object AllowlistEditChild:

  def store(file: Path): NodeAllowlistStore =
    NodeAllowlistStore
      .fromConfig(Some(ConfigFactory.parseString(s"""enabled = true, file = "$file"""")), Nil, Nil, None)
      .toOption
      .flatten
      .get

  def main(args: Array[String]): Unit =
    val s = store(Path.of(args(0)))
    val other = "bb" * 64
    var n = 0
    while true do
      n += 1
      val op = if n % 2 == 1 then AllowlistEdit.Add else AllowlistEdit.Remove
      s.edit(op, Seq(other)) match
        case Right(_) => println(s"edited $n")
        case Left(e)  => println(s"refused $n: ${e.message}")
      Console.flush()
