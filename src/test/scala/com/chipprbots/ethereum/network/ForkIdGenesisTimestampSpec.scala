package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.*

/** EIP-6122: timestamp forks at or before the genesis timestamp are part of the genesis ruleset and must NOT enter the
  * fork-id checksum chain.
  *
  * go-ethereum's `gatherForks` drops them with `for len(forksByTime) > 0 && forksByTime[0] <= genesis`. fukuii filtered
  * `== 0` instead, which coincides with the correct rule only when the genesis timestamp happens to be zero.
  *
  * WHY THIS SPEC EXISTS. On hive's engine suite the entire `Genesis=1` Fork ID family failed with
  * `have 0xb9fc74b5 / want 0x4107882a`: with genesis at time 1 and Cancun also at 1, geth dropped Cancun and fukuii
  * accumulated it. Twelve tests, one rule. The zero-genesis cases in this spec are the control showing the old rule was
  * right for that case and only that case — which is why nothing caught it.
  *
  * The genesis hash here is synthetic (32 bytes of 0xab) so the checksums are reproducible by hand; each expected value
  * is CRC32 over the genesis hash followed by each accumulated fork as a big-endian uint64, computed independently of
  * this implementation.
  */
class ForkIdGenesisTimestampSpec extends AnyWordSpec with Matchers:

  private val genesisHash = ByteString(Hex.decode("ab" * 32))

  /** CRC32 of the genesis hash alone — the checksum when no fork has been accumulated. */
  private val BareGenesisChecksum = 0xd98acb08L

  /** Two timestamp forks, at times 1 and 2, on a chain with no block forks. */
  private def confWithForksAt(first: Long, second: Long): BlockchainConfig =
    val hive = blockchains.blockchains("hive")
    hive.copy(
      // Olympia at 0 keeps gatherBlockForks empty: the shipped sentinel would otherwise be
      // re-appended as a pending block fork and halt accumulation before any timestamp.
      forkBlockNumbers = hive.forkBlockNumbers.copy(olympiaBlockNumber = 0),
      forkTimestamps = hive.forkTimestamps.copy(
        shanghaiTimestamp = Some(first),
        cancunTimestamp = Some(second),
        pragueTimestamp = None,
        osakaTimestamp = None,
        bpo1Timestamp = None,
        bpo2Timestamp = None,
        amsterdamTimestamp = None
      )
    )

  "with a genesis timestamp of 0" must {

    "keep a fork scheduled after genesis and announce it as upcoming" taggedAs (UnitTest, NetworkTest) in {
      // The `Genesis=0` control: a fork at 1 is genuinely upcoming at head time 0, so the
      // checksum is bare and `next` names the fork. Advertising next=0 here is what the
      // handshake's wall-clock substitution used to cause.
      val conf = confWithForksAt(1L, 2L)
      ForkId.gatherTimestampForks(conf, 0L) shouldBe List[BigInt](1, 2)
      ForkId.create(genesisHash, 0L, conf)(BigInt(0), 0L) shouldBe ForkId(BareGenesisChecksum, Some(1))
    }

    "drop a fork sitting exactly at genesis time" taggedAs (UnitTest, NetworkTest) in {
      // Unchanged by this fix — `== 0` and `<= 0` agree here, which is the whole reason the
      // wrong rule survived.
      val conf = confWithForksAt(0L, 2L)
      ForkId.gatherTimestampForks(conf, 0L) shouldBe List[BigInt](2)
      ForkId.create(genesisHash, 0L, conf)(BigInt(0), 0L) shouldBe ForkId(BareGenesisChecksum, Some(2))
    }
  }

  "with a genesis timestamp of 1" must {

    "drop a fork sitting exactly at genesis time" taggedAs (UnitTest, NetworkTest) in {
      // THE pin for the `Genesis=1, Cancun=1` family: both forks are at or before genesis, so
      // the chain has no fork transitions at all and the checksum stays bare.
      val conf = confWithForksAt(1L, 1L)
      ForkId.gatherTimestampForks(conf, 1L) shouldBe empty
      ForkId.create(genesisHash, 1L, conf)(BigInt(0), 1L) shouldBe ForkId(BareGenesisChecksum, None)
    }

    "drop only the at-genesis fork and keep the later one" taggedAs (UnitTest, NetworkTest) in {
      // The `Genesis=1, Cancun=2, Shanghai=1` case: Shanghai@1 is genesis ruleset, Cancun@2 is
      // a real transition still ahead of a head at time 1.
      val conf = confWithForksAt(1L, 2L)
      ForkId.gatherTimestampForks(conf, 1L) shouldBe List[BigInt](2)
      ForkId.create(genesisHash, 1L, conf)(BigInt(0), 1L) shouldBe ForkId(BareGenesisChecksum, Some(2))
    }

    "accumulate only the post-genesis fork once the head passes it" taggedAs (UnitTest, NetworkTest) in {
      // `Genesis=1, Cancun=2, Shanghai=1, BlocksBeforePeering=1`: exactly one fork accumulates,
      // never the at-genesis one.
      val conf = confWithForksAt(1L, 2L)
      ForkId.create(genesisHash, 1L, conf)(BigInt(1), 2L) shouldBe ForkId(0xad1d4417L, None)
    }

    "regress to accumulating the at-genesis fork under the old == 0 rule" taggedAs (UnitTest, NetworkTest) in {
      // Negative control. Passing genesis timestamp 0 reproduces the old behaviour exactly:
      // Shanghai@1 is no longer dropped, so it enters the checksum and the value diverges from
      // every peer's. This is the shape of the measured have/want mismatch.
      val conf = confWithForksAt(1L, 2L)
      ForkId.create(genesisHash, 0L, conf)(BigInt(0), 1L) shouldBe ForkId(0x341415adL, Some(2))
    }
  }
