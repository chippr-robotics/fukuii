package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.*

/** ForkId CRC32 accumulation tests for Sepolia (ETH/Sepolia, timestamp-fork chain).
  *
  * Ground truth: go-ethereum `core/forkid/forkid_test.go` Sepolia section (upstream branch, verified 2026-06-25; the
  * Amsterdam rows — "Last BPO2 block" and "First/Future Amsterdam block" — verified against master 2026-09-24). Sepolia
  * genesis hash: 0x25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9
  *
  * Regression guard: a bug in `forTimestamp`-based ForkId accumulation (wrong order, wrong timestamp values) causes
  * every incoming Sepolia peer to disconnect at ETH handshake with ErrLocalIncompatibleOrStale. This is silent —
  * `testEssential` does not exercise the Sepolia ForkId path.
  */
class ForkIdSepoliaSpec extends AnyWordSpec with Matchers:

  private val sepoliaConf = blockchains.blockchains("sepolia")

  // Sepolia genesis hash — go-ethereum params/config.go SepoliaGenesisHash
  /** Sepolia's genesis header timestamp (0x6159af19). Unlike the other fixtures this is NOT zero, which is exactly why
    * the EIP-6122 filter must compare against the genesis time rather than against a hardcoded zero. Every Sepolia fork
    * is later than this, so no fork is dropped and the checksums below are unchanged.
    */
  private val SepoliaGenesisTimestamp: Long = 1633267481L

  private val sepoliaGenesisHash =
    ByteString(Hex.decode("25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9"))

  private def create(block: BigInt, ts: Long): ForkId =
    ForkId.create(sepoliaGenesisHash, SepoliaGenesisTimestamp, sepoliaConf)(block, ts)

  "ForkId for Sepolia" must {

    "produce genesis checksum before MergeNetsplit block" taggedAs (UnitTest, NetworkTest) in {
      // CRC32(genesis_hash) only — no block fork passed yet
      create(0, 0) shouldBe ForkId(0xfe3366e7L, Some(1735371))
      create(1735370, 0) shouldBe ForkId(0xfe3366e7L, Some(1735371))
    }

    "accumulate MergeNetsplit block (1735371) into checksum" taggedAs (UnitTest, NetworkTest) in {
      // Block fork 1735371 crossed — CRC32 now includes that block number
      create(1735371, 0) shouldBe ForkId(0xb96cbd13L, Some(1677557088))
      create(1735372, 1677557087) shouldBe ForkId(0xb96cbd13L, Some(1677557088))
    }

    "accumulate Shanghai timestamp (1677557088) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(1735372, 1677557088) shouldBe ForkId(0xf7f9bc08L, Some(1706655072))
      create(1735372, 1706655071) shouldBe ForkId(0xf7f9bc08L, Some(1706655072))
    }

    "accumulate Cancun timestamp (1706655072) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(1735372, 1706655072) shouldBe ForkId(0x88cf81d9L, Some(1741159776))
      create(1735372, 1741159775) shouldBe ForkId(0x88cf81d9L, Some(1741159776))
    }

    "accumulate Prague timestamp (1741159776) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(1735372, 1741159776) shouldBe ForkId(0xed88b5fdL, Some(1760427360))
      create(1735372, 1760427359) shouldBe ForkId(0xed88b5fdL, Some(1760427360))
    }

    "accumulate Osaka timestamp (1760427360) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(1735372, 1760427360) shouldBe ForkId(0xe2ae4999L, Some(1761017184))
      create(1735372, 1761017183) shouldBe ForkId(0xe2ae4999L, Some(1761017184))
    }

    "accumulate BPO1 timestamp (1761017184) into checksum" taggedAs (UnitTest, NetworkTest) in {
      create(1735372, 1761017184) shouldBe ForkId(0x56078a1eL, Some(1761607008))
      create(1735372, 1761607007) shouldBe ForkId(0x56078a1eL, Some(1761607008))
    }

    "accumulate BPO2 timestamp (1761607008) into checksum, with Amsterdam next" taggedAs (UnitTest, NetworkTest) in {
      // Before Amsterdam was scheduled this was the tail state (next=None). Every BPO2-era Sepolia
      // peer running a Glamsterdam-aware client now announces Next: 1791294816.
      create(1735372, 1761607008) shouldBe ForkId(0x268956b6L, Some(1791294816))
      create(1735372, 1791294815) shouldBe ForkId(0x268956b6L, Some(1791294816))
    }

    "accumulate Amsterdam timestamp (1791294816) into checksum — tail state, next=None" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      // Glamsterdam, 2026-10-06 13:53:36 UTC. Amsterdam is the last scheduled fork; next=None (go-ethereum Next: 0)
      create(1735372, 1791294816) shouldBe ForkId(0x6c1d9423L, None)
      create(1735372, 2000000000) shouldBe ForkId(0x6c1d9423L, None)
    }
  }
