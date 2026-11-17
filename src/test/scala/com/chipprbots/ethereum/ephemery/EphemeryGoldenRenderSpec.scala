package com.chipprbots.ethereum.ephemery

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.testing.Tags.*

/** The golden test of the Ephemery release engine: the raw inputs of releases 164 and 165 (the members of the published
  * `testnet-all.tar.gz`, committed under `src/test/resources/ephemery/releases/`) render, byte for byte, to the chain
  * configuration and genesis the node reads (`src/test/resources/ephemery/ephemery-N/`).
  *
  * It replaces `scripts/ephemery/check-fixture.sh`. A change of the rendered output is always a reviewed fixture diff,
  * and the fixtures are regenerated with `fukuii cli ephemery-fetch --from-dir <raw> --out <dir>`.
  *
  * The raw inputs and the hashes are ground truth independent of this implementation: the hashes are the releases'
  * `deposit_contract_block_hash.txt`, and `EphemeryGenesisSpec` holds them against block 0 of the live network.
  */
class EphemeryGoldenRenderSpec extends AnyFlatSpec with Matchers:

  private val Releases = List(164 -> Hash164, 165 -> Hash165)

  Releases.foreach { case (iteration, hash) =>
    s"the raw inputs of ephemery-$iteration" should "render the committed chain configuration byte for byte" taggedAs (
      UnitTest
    ) in {
      val release = EphemeryRelease.validate(raw(iteration)).fold(r => fail(r.message), identity)
      release.chainConf shouldBe fixture(iteration, "ephemery-chain.conf")
    }

    it should "re-encode the committed genesis byte for byte" taggedAs (UnitTest) in {
      val release = EphemeryRelease.validate(raw(iteration)).fold(r => fail(r.message), identity)
      release.encodedGenesis shouldBe fixture(iteration, "ephemery-genesis.json")
    }

    it should "compute the published genesis hash" taggedAs (UnitTest) in {
      val release = EphemeryRelease.validate(raw(iteration)).fold(r => fail(r.message), identity)
      release.genesisHashHex shouldBe hash
      release.release shouldBe s"ephemery-$iteration"
      release.warnings shouldBe empty
    }
  }
