package com.chipprbots.ethereum.blockchain.sync.regular

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 C5b, T213: the importer's two on-demand writes (the fetched node that arrives while it waits, and the one
  * that arrives late) go through one site, `repairFetched`, which is `repairNodeAt` on the unified layout and
  * `repairNode` on the hash layout, and nothing in the main sources calls `saveNode`, the blind upsert (#1692).
  */
class BlockImporterRepairSitesSpec extends AnyFlatSpec with Matchers:

  private val main: Path = Path.of("src/main/scala").toAbsolutePath
  private val importer: Path = main.resolve("com/chipprbots/ethereum/blockchain/sync/regular/BlockImporter.scala")

  private def code(file: Path): String =
    // comments stripped: a mention of a name is not a call of it
    Files.readString(file, UTF_8).linesIterator.filterNot(_.trim.startsWith("//")).mkString("\n")

  private def mainSources: Seq[Path] =
    Using.resource(Files.walk(main))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toSeq)

  "The importer" should "write a fetched state node only through repairFetched, which is repairNodeAt on the unified layout" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val source = code(importer)
    "\\bsaveNode\\(".r.findAllIn(source).toList shouldBe empty
    "\\bstateStorage\\.repairNode\\(".r
      .findAllIn(source)
      .toList should have size 1 // the hash layout's, in repairFetched
    "\\.repairNodeAt\\(".r.findAllIn(source).toList should have size 1
    "\\brepairFetched\\(".r.findAllIn(source).toList should have size 3 // the definition and the two sites
    "\\.deleteBadNodeAt\\(".r.findAllIn(source).toList should have size 1
    "\\bstateStorage\\.deleteBadNode\\(".r.findAllIn(source).toList should have size 1
  }

  it should "leave no caller of saveNode in the main sources but the store's own definitions" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val callers = mainSources.filter(file => "\\.saveNode\\(".r.findFirstIn(code(file)).isDefined)
    callers shouldBe empty
  }
