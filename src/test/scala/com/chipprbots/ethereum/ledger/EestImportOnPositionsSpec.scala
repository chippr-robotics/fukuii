package com.chipprbots.ethereum.ledger

import java.io.File

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T075's substitute, T079 through the EVM): execution-spec-tests fixtures imported onto the
  * unified state layout through the node's import path ([[EestBlockchainReplay.replayOnPositions]]).
  *
  * The vendored fixtures are the regression vectors and the one that removes contracts which hold storage
  * (`selfdestruct-stored-storage.json`: SELFDESTRUCT before EIP-6780, on Paris and Shanghai), where a storage trie
  * that is replaced must have every row deleted and put back by a reverse. `EEST_IMPORT_POSITIONS=<dir>` runs every
  * fixture file under a directory the same way (the Amsterdam corpus, or `for_paris` and `for_shanghai` fetched with
  * `scripts/eest/fetch_fixtures.py`).
  */
class EestImportOnPositionsSpec extends AnyWordSpec with Matchers:

  private def tests(json: JValue): List[(String, JValue)] =
    val JObject(named) = json: @unchecked
    named

  private def vendored(file: String): List[(String, JValue)] =
    val src = scala.io.Source.fromResource(s"eest-regression/$file")
    try tests(parse(src.mkString))
    finally src.close()

  private val vectorFiles: Seq[String] = Seq(
    "selfdestruct-stored-storage.json",
    "genesis-shared-storage-nodes.json",
    "eip7685-requests.json",
    "eip7702-authorizations.json",
    "eip7702-failed-tx-rollback.json",
    "mcopy-huge-offsets.json",
    "amsterdam-builder.json"
  )

  private def run(named: List[(String, JValue)]): (Int, EestBlockchainReplay.OnPositions) =
    named.foldLeft((0, EestBlockchainReplay.OnPositions(Nil, 0, 0, 0, 0, 0, 0))) { case ((count, total), (name, t)) =>
      val one = EestBlockchainReplay.replayOnPositions(t)
      (
        count + 1,
        EestBlockchainReplay.OnPositions(
          total.divergences ++ one.divergences.map(d => s"$name: $d"),
          total.blocksKept + one.blocksKept,
          total.blocksRejected + one.blocksRejected,
          total.positionsChanged + one.positionsChanged,
          total.storageRowsDeleted + one.storageRowsDeleted,
          total.storageTriesEmptied + one.storageTriesEmptied,
          total.tombstones + one.tombstones
        )
      )
    }

  private def said(count: Int, seen: EestBlockchainReplay.OnPositions): String =
    s"$count fixtures: ${seen.blocksKept} blocks committed, ${seen.blocksRejected} rejected, " +
      s"${seen.positionsChanged} positions changed, ${seen.storageRowsDeleted} rows of storage tries deleted, " +
      s"${seen.storageTriesEmptied} storage tries emptied, ${seen.tombstones} tombstones"

  "execution-spec-tests fixtures, imported onto the unified state layout" should {
    vectorFiles.foreach { file =>
      s"leave exactly each block's state on disk, and take it back by the block's record: $file" taggedAs (
        UnitTest,
        VMTest,
        ConsensusTest
      ) in {
        val named = vendored(file)
        named should not be empty
        val (count, seen) = run(named)
        seen.divergences shouldBe empty
        seen.tombstones shouldBe 0
        // Or the fixture no longer shows what it is here for: a storage trie removed whole.
        if file == "selfdestruct-stored-storage.json" then
          seen.storageTriesEmptied should be > 0
          seen.blocksKept should be > 0
        info(said(count, seen))
      }
    }

    "do the same for every fixture under EEST_IMPORT_POSITIONS" taggedAs (VMTest, ConsensusTest) in {
      val dir = sys.env.get("EEST_IMPORT_POSITIONS").map(new File(_))
      assume(dir.exists(_.isDirectory), "EEST_IMPORT_POSITIONS is not set to a directory of fixtures")
      def files(f: File): Seq[File] =
        if f.isDirectory then Option(f.listFiles()).toSeq.flatten.sortBy(_.getName).flatMap(files)
        else Seq(f).filter(_.getName.endsWith(".json"))
      var fixtures = 0
      var total = EestBlockchainReplay.OnPositions(Nil, 0, 0, 0, 0, 0, 0)
      files(dir.get).foreach { file =>
        val src = scala.io.Source.fromFile(file)
        val named =
          try tests(parse(src.mkString))
          finally src.close()
        val (count, seen) = run(named)
        fixtures += count
        total = EestBlockchainReplay.OnPositions(
          total.divergences ++ seen.divergences.map(d => s"${file.getName}: $d"),
          total.blocksKept + seen.blocksKept,
          total.blocksRejected + seen.blocksRejected,
          total.positionsChanged + seen.positionsChanged,
          total.storageRowsDeleted + seen.storageRowsDeleted,
          total.storageTriesEmptied + seen.storageTriesEmptied,
          total.tombstones + seen.tombstones
        )
      }
      info(said(fixtures, total))
      total.divergences.take(20) shouldBe empty
      total.tombstones shouldBe 0
    }
  }
