package com.chipprbots.ethereum.ledger

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T026: real blocks, executed by the production code on today's hash-keyed store, with a
  * [[com.chipprbots.ethereum.mpt.PositionTestKit.PositionMirror]] in front of the storage every world is built over.
  *
  * The mirror holds what a position-keyed store would hold: it applies each change the tries report, under the owner
  * and at the position they report it, and it checks each read against the rows it has. So a fixture passes only if
  *   - every trie of the state says which trie it is (no read or update reaches the storage unscoped, or by hash
  *     alone);
  *   - every node execution reads is one a position store would find where the trie looks for it;
  *   - every node a trie reports as replaced or absorbed is the row at that position;
  *   - after the genesis load and after each accepted block, the rows are exactly the stored nodes of the account trie
  *     and of each account's storage trie, as a walk of the raw RLP from the block's state root finds them, and an
  *     account that is gone or has no storage has no rows.
  *
  * The vectors are the execution-specs fixtures under `src/test/resources/eest-regression/` that carry blocks, Paris to
  * Amsterdam. Each is also held to its expected head, as [[EestRegressionVectorsSpec]] holds it.
  *
  * `selfdestruct-stored-storage.json` is seven tests@v21.0.0 fixtures for Paris and Shanghai, trimmed to the replayed
  * fields like the others, in which SELFDESTRUCT removes a contract whose storage trie has stored nodes, and in four of
  * them the address is created again. From Cancun on that cannot happen (EIP-6780 removes only a contract created in
  * the same transaction, EIP-7610 refuses a creation over storage), so the Cancun-to-Amsterdam vectors, and the whole
  * Amsterdam corpus, never reset a trie that holds a row. Without the reset in `deleteAccount` these seven leave the
  * removed contract's rows behind, and fail.
  *
  * The whole corpus runs the same mirror with `EEST_POSITIONS=1` ([[EestFixtureCorpusSpec]]).
  */
class EestPositionReplaySpec extends AnyWordSpec with Matchers:

  /** file → whether every fixture in it removes a contract whose storage trie has stored nodes. */
  private val vectorFiles: Seq[(String, Boolean)] = Seq(
    "genesis-shared-storage-nodes.json" -> false,
    "eip7685-requests.json" -> false,
    "eip7702-authorizations.json" -> false,
    "eip7702-failed-tx-rollback.json" -> false,
    "mcopy-huge-offsets.json" -> false,
    "amsterdam-builder.json" -> false,
    "selfdestruct-stored-storage.json" -> true
  )

  private def fixtures(file: String): List[(String, JValue)] =
    val src = scala.io.Source.fromResource(s"eest-regression/$file")
    val json =
      try parse(src.mkString)
      finally src.close()
    val JObject(tests) = json: @unchecked
    tests

  "A position mirror on the execution of execution-specs fixtures" should {
    vectorFiles.foreach { case (file, removesStoredContract) =>
      s"hold exactly the stored nodes of every trie, and find every read, through $file" taggedAs (
        UnitTest,
        VMTest,
        ConsensusTest
      ) in {
        val tests = fixtures(file)
        tests should not be empty
        var updates = 0L
        var reads = 0L
        var resets = 0L
        var kept = 0
        var dropped = 0
        tests.foreach { case (name, t) =>
          val observer = EestPositionMirror.forFixture(t)
          withClue(s"$name: ") {
            EestBlockchainReplay.replay(t, observer) shouldBe empty
            observer.findings shouldBe empty
            // Or the fixture no longer shows what it is here for, and a missing reset would pass.
            if removesStoredContract then observer.mirror.resetsOfStoredTries should be > 0L
          }
          updates += observer.mirror.updates
          reads += observer.readsChecked
          resets += observer.mirror.resetsOfStoredTries
          kept += observer.unitsKept
          dropped += observer.unitsDropped
        }
        // The mirror saw the replay: a wrapper that execution never reached would pass everything above.
        kept should be >= tests.size
        updates should be > 0L
        info(
          s"${tests.size} fixtures: $kept units kept, $dropped rejected, $updates trie updates mirrored, " +
            s"$reads reads checked, $resets stored storage tries reset"
        )
      }
    }
  }

  "A position mirror" should {
    "see the blocks it is given: a fixture with blocks has its reads checked" taggedAs UnitTest in {
      val (_, t) = fixtures("eip7702-authorizations.json").head
      val observer = EestPositionMirror.forFixture(t)
      EestBlockchainReplay.replay(t, observer) shouldBe empty
      observer.unitsKept should be > 1
      observer.readsChecked should be > 0L
    }
  }
