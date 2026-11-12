package com.chipprbots.ethereum.ledger

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.Block.toBlock
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C3c (T110): the harness canary.
  *
  * The fixture replays are evidence about the unified state layout only while they run on it. A replay that was quietly
  * on the hash store (a guard that did not reach the storages, a genesis loaded the old way) would go on passing, and
  * say nothing. So a replay on positions ends by asking its datadir what wrote it
  * ([[EestBlockchainReplay.notOnPositions]]), and this spec holds that question to both answers: a run on the unified
  * layout passes it, and the same fixture's genesis loaded on the hash store does not.
  */
class HarnessCanarySpec extends AnyFlatSpec with Matchers:

  private def hx(s: String): Array[Byte] = org.bouncycastle.util.encoders.Hex.decode(s.stripPrefix("0x"))

  /** The first fixture of a vendored file. */
  private def fixture(file: String): JValue =
    val src = scala.io.Source.fromResource(s"eest-regression/$file")
    try
      parse(src.mkString) match
        case JObject(tests) => tests.head._2
        case other          => fail(s"not a fixture file: ${other.getClass}")
    finally src.close()

  private val t = fixture("selfdestruct-stored-storage.json")

  private def loaded(guard: Boolean): EestBlockchainReplay.EnvOver =
    val env = new EestBlockchainReplay.EnvOver(EphemDataSource(), guard)
    val chainId = (t \ "config" \ "chainid").toOption
      .map(v => BigInt(v.values.toString.stripPrefix("0x"), 16))
      .getOrElse(BigInt(1))
    val config = EestBlockchainReplay
      .configFor(env.blockchainConfig, (t \ "network").values.toString, chainId)
      .flatMap(EestBlockchainReplay.withFixtureBlobSchedule(_, t))
      .fold(why => fail(why), identity)
    given BlockchainConfig = config
    EestBlockchainReplay.loadFixtureGenesis(env, t, hx((t \ "genesisRLP").values.toString).toBlock) shouldBe Right(())
    env

  "A replay on positions" should "be on the position store: blocks committed there, and nothing in the hash store" taggedAs
    (UnitTest, ConsensusTest) in {
      val seen = EestBlockchainReplay.replayOnPositions(t)
      seen.divergences shouldBe empty
      seen.blocksKept should be > 0
      seen.positionsChanged should be > 0
    }

  "The question a replay asks of its datadir" should "be answered by a genesis the unified layout committed" taggedAs
    UnitTest in {
      EestBlockchainReplay.notOnPositions(loaded(guard = true)) shouldBe empty
    }

  it should "see a run on the hash store for what it is" taggedAs UnitTest in {
    // the same fixture, the same loader, the guard off: the genesis block is the fixture's on this layout too
    EestBlockchainReplay.notOnPositions(loaded(guard = false)) shouldBe Seq(
      "the hash store was written to",
      "the datadir has no layout stamp",
      "the datadir has no state anchor"
    )
  }
