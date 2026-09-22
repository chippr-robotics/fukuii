package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.*

/** EIP-6122 fork-id accumulation across the full ETH-family timestamp schedule, including BPO1/BPO2 (EIP-7892) and
  * Amsterdam.
  *
  * Ground truth is hive's devp2p fixture chain (go-ethereum testdata), decoded from the simulator's own genesis.json
  * and forkenv.json:
  *
  * genesis hash 1518c33dab6ed4dbb2d0c48239ac2f00acc0e28dc43660bb745a0f127552024c cancunTime 60, pragueTime 120,
  * osakaTime 180, bpo1Time 240, bpo2Time 300, amsterdamTime 360 (shanghaiTime 0 == genesis, so it is not a checksum
  * entry)
  *
  * Each expected checksum below is CRC32 accumulated over the genesis hash followed by each passed fork's timestamp as
  * a big-endian uint64 — computed independently of this implementation.
  *
  * WHY THIS SPEC EXISTS. `ForkId.gatherTimestampForks` enumerated every timestamp fork EXCEPT Amsterdam. On any chain
  * declaring `amsterdamTime` the node therefore advertised the checksum of the forks *before* Amsterdam. Measured on
  * hive's devp2p suite: fukuii sent 0x321a21a2 (the checksum of cancun+prague+osaka, because the hive adapter also
  * never passed bpo1/bpo2) where peers wanted 0x5942bfc2, and 27 of that suite's 34 failures were the resulting
  * `peering failed: status exchange failed: wrong fork ID in status` rejection.
  *
  * The defect was invisible to every existing fork-id spec because no shipped chain config sets `amsterdam-timestamp` —
  * ForkIdSepoliaSpec's schedule stops at BPO2. A fork missing from the enumeration cannot fail a test that never
  * configures it, which is precisely why this spec builds its schedule explicitly rather than reading a shipped chain.
  */
class ForkIdHiveAmsterdamSpec extends AnyWordSpec with Matchers:

  /** hive devp2p fixture genesis hash (chain.rlp block 0). */
  /** The fixture genesis declares timestamp 0, so every timestamp fork is strictly after genesis and none is dropped
    * by the EIP-6122 "at or before genesis" rule.
    */
  private val GenesisTimestamp: Long = 0L

  private val fixtureGenesisHash =
    ByteString(Hex.decode("1518c33dab6ed4dbb2d0c48239ac2f00acc0e28dc43660bb745a0f127552024c"))

  /** The hive chain config as `hive/fukuii/fukuii.sh` actually produces it at runtime from the fixture's forkenv.json —
    * NOT as `hive-chain.conf` ships it.
    *
    * The distinction is load-bearing and cost this spec a red run. The shipped file sets `olympia-block-number =
    * "1000000000000000000"`, the "not yet scheduled" sentinel, which `gatherBlockForks` re-appends as the advertised
    * next fork. `ForkId.create` stops at the first fork the head has not passed, so with that sentinel present it halts
    * on a BLOCK fork and never accumulates a single timestamp — every checksum comes back as bare CRC32(genesis) with
    * `next = 10^18`.
    *
    * At runtime the adapter overrides it: `fukuii.sh:82` maps `olympia-block-number=$HIVE_FORK_LONDON`, and the
    * fixture's forkenv.json declares `HIVE_FORK_LONDON: "0"`. Zero is filtered out of the checksum chain as
    * genesis-active, no sentinel is appended, and the timestamp forks are reached. That is why the real node advertised
    * `0x321a21a2` with `next = 0` rather than the genesis checksum.
    *
    * A spec built on the shipped defaults would therefore assert against a configuration the client never runs.
    */
  private val fixtureConf: BlockchainConfig =
    val hive = blockchains.blockchains("hive")
    hive.copy(
      forkBlockNumbers = hive.forkBlockNumbers.copy(olympiaBlockNumber = 0),
      forkTimestamps = hive.forkTimestamps.copy(
        shanghaiTimestamp = Some(0L),
        cancunTimestamp = Some(60L),
        pragueTimestamp = Some(120L),
        osakaTimestamp = Some(180L),
        bpo1Timestamp = Some(240L),
        bpo2Timestamp = Some(300L),
        amsterdamTimestamp = Some(360L)
      )
    )

  /** The fixture chain's real head is block 600. Block number is irrelevant to the checksum here — the assertion below
    * that `gatherBlockForks` is empty is what makes that true, and it fails loudly if a config change ever reintroduces
    * a block fork.
    */
  private def create(ts: Long): ForkId = ForkId.create(fixtureGenesisHash, GenesisTimestamp, fixtureConf)(BigInt(600), ts)

  "ForkId for hive's Amsterdam devp2p fixture chain" must {

    "enumerate every timestamp fork, Amsterdam included" taggedAs (UnitTest, NetworkTest) in {
      ForkId.gatherTimestampForks(fixtureConf, GenesisTimestamp) shouldBe List[BigInt](60, 120, 180, 240, 300, 360)
    }

    "carry no block forks, so the checksum chain reaches the timestamps" taggedAs (UnitTest, NetworkTest) in {
      // Guards the premise of every checksum below. `ForkId.create` halts at the first unpassed fork, so a single
      // stray block fork — the Olympia sentinel in particular — would silently reduce all seven expectations to
      // bare CRC32(genesis) instead of failing on the value under test.
      ForkId.gatherBlockForks(fixtureConf) shouldBe empty
    }

    "produce the genesis checksum before Cancun" taggedAs (UnitTest, NetworkTest) in {
      create(0) shouldBe ForkId(0x4bca00f5L, Some(60))
      create(59) shouldBe ForkId(0x4bca00f5L, Some(60))
    }

    "accumulate Cancun (60)" taggedAs (UnitTest, NetworkTest) in {
      create(60) shouldBe ForkId(0x6296ed4aL, Some(120))
      create(119) shouldBe ForkId(0x6296ed4aL, Some(120))
    }

    "accumulate Prague (120)" taggedAs (UnitTest, NetworkTest) in {
      create(120) shouldBe ForkId(0xc91beee6L, Some(180))
      create(179) shouldBe ForkId(0xc91beee6L, Some(180))
    }

    "accumulate Osaka (180)" taggedAs (UnitTest, NetworkTest) in {
      // 0x321a21a2 is what fukuii advertised at EVERY head, Amsterdam blocks included,
      // before Amsterdam entered the enumeration. Here it is correct — and only here.
      create(180) shouldBe ForkId(0x321a21a2L, Some(240))
      create(239) shouldBe ForkId(0x321a21a2L, Some(240))
    }

    "accumulate BPO1 (240)" taggedAs (UnitTest, NetworkTest) in {
      create(240) shouldBe ForkId(0x209135b7L, Some(300))
      create(299) shouldBe ForkId(0x209135b7L, Some(300))
    }

    "accumulate BPO2 (300)" taggedAs (UnitTest, NetworkTest) in {
      create(300) shouldBe ForkId(0x7f723d62L, Some(360))
      create(359) shouldBe ForkId(0x7f723d62L, Some(360))
    }

    "accumulate Amsterdam (360) — tail state, next=None" taggedAs (UnitTest, NetworkTest) in {
      // The byte-exact value hive's peers demand. The fixture chain's head (block 600,
      // timestamp 6000) sits here, so this is the checksum sent in every Status message.
      create(360) shouldBe ForkId(0x5942bfc2L, None)
      create(6000) shouldBe ForkId(0x5942bfc2L, None)
    }
  }

  "ForkId.gatherTimestampForks" must {

    "omit a fork the chain has not scheduled" taggedAs (UnitTest, NetworkTest) in {
      // Guards the other direction: adding Amsterdam to the enumeration must not make it
      // appear for chains that never declare it. Sepolia's schedule ends at BPO2, and its
      // tail checksum is pinned by ForkIdSepoliaSpec — this asserts the input to that.
      val sepolia = blockchains.blockchains("sepolia")
      sepolia.forkTimestamps.amsterdamTimestamp shouldBe None
      ForkId.gatherTimestampForks(sepolia, GenesisTimestamp) should not contain BigInt(360)
    }

    "treat a zero timestamp as genesis, not as a checksum entry" taggedAs (UnitTest, NetworkTest) in {
      // shanghaiTimestamp = 0 on the fixture chain. Genesis-active forks are already
      // folded into the genesis hash and must not be accumulated again.
      fixtureConf.forkTimestamps.shanghaiTimestamp shouldBe Some(0L)
      ForkId.gatherTimestampForks(fixtureConf, GenesisTimestamp) should not contain BigInt(0)
    }
  }
