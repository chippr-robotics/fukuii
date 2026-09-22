package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.*

/** EIP-2124/6122 fork-id accumulation over the FULL go-ethereum block-fork set, including the three forks fukuii did
  * not model: Arrow Glacier (EIP-4345), Gray Glacier (EIP-5133) and the post-Merge net-split block (EIP-3675).
  *
  * Ground truth is hive's `ethereum/rpc-compat` fixture chain, decoded from the simulator's own genesis.json:
  *
  * genesis hash 44fd89d504659cd58f48f4796b77a7e7012cf296a2409afa2f6c3cb99b5b3d99, chainId 0xc72dd9d5e883e, head block
  * 0x36 (54) at timestamp 540. Block forks 3, 6, 9, 12, 15, 18, 21, 24, 27, **30, 33, 36**; timestamp forks 390, 420,
  * 450, 480, 510, 540.
  *
  * Each expected checksum is CRC32 accumulated over the genesis hash followed by each passed fork as a big-endian
  * uint64, computed independently of this implementation.
  *
  * WHY THIS SPEC EXISTS. `ForkBlockNumbers` had no `arrowGlacierBlockNumber` or `grayGlacierBlockNumber` fields at all,
  * and `hive/fukuii/fukuii.sh` never mapped `mergeNetsplitBlock`. go-ethereum's `gatherForks` enumerates every `*Block`
  * field of ChainConfig, so all three belong in the checksum chain. Missing all three, fukuii advertised 0x5e0cb820
  * where every geth peer computes 0xe272ecbe, and a peer that disagrees about the fork id rejects the handshake with
  * "wrong fork ID in status" — the node simply never peers.
  *
  * The defect was invisible because nothing anywhere asserted the VALUE of a computed fork id for this chain. That is
  * what the `0xe272ecbe` pin below exists to prevent recurring.
  */
class ForkIdHiveRpcCompatSpec extends AnyWordSpec with Matchers:

  /** hive rpc-compat fixture genesis hash (eth_getBlockByNumber/get-genesis.io). */
  /** hive rpc-compat genesis.json declares `"timestamp": "0x0"`. */
  private val GenesisTimestamp: Long = 0L

  private val fixtureGenesisHash =
    ByteString(Hex.decode("44fd89d504659cd58f48f4796b77a7e7012cf296a2409afa2f6c3cb99b5b3d99"))

  /** The hive chain config as `hive/fukuii/fukuii.sh` produces it at runtime from the fixture genesis — NOT as
    * `hive-chain.conf` ships it. The shipped file leaves every fork at the 10^18 "not scheduled" sentinel; the adapter
    * overrides them from HIVE_FORK_* and, now, from the genesis `config` block.
    *
    * `olympiaBlockNumber` carries London (27): the adapter maps `olympia-block-number=$HIVE_FORK_LONDON`. Leaving it at
    * the sentinel would make `gatherBlockForks` re-append 10^18 as the next fork and halt the accumulation there, so
    * every checksum below would collapse to bare CRC32(genesis).
    */
  private val fixtureConf: BlockchainConfig =
    val hive = blockchains.blockchains("hive")
    hive.copy(
      forkBlockNumbers = hive.forkBlockNumbers.copy(
        homesteadBlockNumber = 0, // genesis-active, filtered out of the checksum chain
        eip150BlockNumber = 3,
        eip155BlockNumber = 6,
        eip160BlockNumber = 6,
        eip161BlockNumber = 6,
        byzantiumBlockNumber = 9,
        constantinopleBlockNumber = 12,
        petersburgBlockNumber = 15,
        istanbulBlockNumber = 18,
        muirGlacierBlockNumber = 21,
        berlinBlockNumber = 24,
        olympiaBlockNumber = 27,
        arrowGlacierBlockNumber = 30,
        grayGlacierBlockNumber = 33,
        mergeNetsplitBlockNumber = 36
      ),
      forkTimestamps = hive.forkTimestamps.copy(
        shanghaiTimestamp = Some(390L),
        cancunTimestamp = Some(420L),
        pragueTimestamp = Some(450L),
        osakaTimestamp = Some(480L),
        bpo1Timestamp = Some(510L),
        bpo2Timestamp = Some(540L)
      )
    )

  /** The fixture chain's real head: block 0x36 (54), comfortably past the last block fork (36), so every block fork has
    * been passed and the accumulation reaches the timestamps.
    */
  private val headBlock = BigInt(54)

  private def create(ts: Long): ForkId = ForkId.create(fixtureGenesisHash, GenesisTimestamp, fixtureConf)(headBlock, ts)

  "ForkId for hive's rpc-compat fixture chain" must {

    "enumerate the glacier and net-split block forks alongside the rest" taggedAs (UnitTest, NetworkTest) in {
      // The direct pin on the defect: 30, 33 and 36 were absent from this list entirely.
      ForkId.gatherBlockForks(fixtureConf) shouldBe
        List[BigInt](3, 6, 9, 12, 15, 18, 21, 24, 27, 30, 33, 36)
    }

    "enumerate every timestamp fork" taggedAs (UnitTest, NetworkTest) in {
      ForkId.gatherTimestampForks(fixtureConf, GenesisTimestamp) shouldBe List[BigInt](390, 420, 450, 480, 510, 540)
    }

    "accumulate all block forks before the first timestamp fork" taggedAs (UnitTest, NetworkTest) in {
      create(0) shouldBe ForkId(0x12e074daL, Some(390))
      create(389) shouldBe ForkId(0x12e074daL, Some(390))
    }

    "accumulate Shanghai (390)" taggedAs (UnitTest, NetworkTest) in {
      create(390) shouldBe ForkId(0xfb7c89b1L, Some(420))
    }

    "accumulate Cancun (420)" taggedAs (UnitTest, NetworkTest) in {
      create(420) shouldBe ForkId(0xe9545c37L, Some(450))
    }

    "accumulate Osaka (480)" taggedAs (UnitTest, NetworkTest) in {
      create(480) shouldBe ForkId(0xfc7f99fbL, Some(510))
    }

    "produce 0xe272ecbe at the fixture head, with no fork left to announce" taggedAs (UnitTest, NetworkTest) in {
      // THE pin. This exact value is what the chain's peers — and eth_config's `forkId`
      // field, per EIP-7910 — expect at head. Nothing asserted it before, which is the only
      // reason three missing forks went unnoticed.
      create(540) shouldBe ForkId(0xe272ecbeL, None)
    }

    "regress to the old wrong checksum if the three forks are dropped again" taggedAs (UnitTest, NetworkTest) in {
      // Negative control, pinning the exact symptom rather than just its absence: this is
      // the value fukuii put on the wire, and the one geth peers rejected.
      val withoutGlaciers = fixtureConf.copy(
        forkBlockNumbers = fixtureConf.forkBlockNumbers.copy(
          arrowGlacierBlockNumber = Long.MaxValue,
          grayGlacierBlockNumber = Long.MaxValue,
          mergeNetsplitBlockNumber = Long.MaxValue
        )
      )
      ForkId.create(fixtureGenesisHash, GenesisTimestamp, withoutGlaciers)(headBlock, 540) shouldBe ForkId(0x5e0cb820L, None)
    }
  }
