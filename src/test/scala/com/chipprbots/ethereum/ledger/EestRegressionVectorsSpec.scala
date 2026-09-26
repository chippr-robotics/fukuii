package com.chipprbots.ethereum.ledger

import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags.*

/** execution-spec-tests v5.4.0 (`fixtures_stable.tar.gz`, `blockchain_tests/`) vectors that hive's consume-engine /
  * consume-rlp full pass on 33b730e75 failed, replayed by [[EestBlockchainReplay]] through the path `ChainImporter`
  * takes for hive's chain.rlp.
  *
  * The JSON under `src/test/resources/eest-regression/` is the fixture verbatim, trimmed to the fields replayed here
  * (`network`, `pre`, `genesisRLP`, `blocks[].rlp`, `blocks[].expectException`, `lastblockhash`); block RLP, signatures
  * and the expected head hash are byte-for-byte the release's.
  */
class EestRegressionVectorsSpec extends AnyWordSpec with Matchers:

  /** file → what it pins. */
  private val vectorFiles: Seq[(String, String)] = Seq(
    "genesis-shared-storage-nodes.json" -> "genesis storage tries that share a node both keep it",
    "eip7685-requests.json" -> "EIP-6110 deposit-log layout, EIP-7002/7251 system-call failure, EIP-7685 requestsHash",
    "eip7702-authorizations.json" -> "EIP-7702 per-tuple validity, authority warming, refunds and delegation access costs",
    "eip7702-failed-tx-rollback.json" -> "a failed Type-4 transaction keeps its authorizations and their refund",
    "mcopy-huge-offsets.json" -> "MCOPY memory expansion with offsets near 2^256"
  )

  "execution-spec-tests v5.4.0 regression vectors" should {
    vectorFiles.foreach { case (file, pins) =>
      s"replay $file byte-for-byte ($pins)" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        val src = scala.io.Source.fromResource(s"eest-regression/$file")
        val json =
          try parse(src.mkString)
          finally src.close()
        val JObject(tests) = json: @unchecked
        tests should not be empty
        val failures = tests.flatMap { case (name, t) => EestBlockchainReplay.replay(t).map(d => s"$name: $d") }
        failures shouldBe empty
      }
    }
  }
