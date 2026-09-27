package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.discovery.DnsDiscovery
import com.chipprbots.ethereum.network.discovery.ForkIdTag
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config.*

/** EIP-2124 / EIP-6122 fork id for Platåberget, the public Glamsterdam testnet, from the SHIPPED chain config.
  *
  * Ground truth:
  *   - the post-Amsterdam checksum 0x05842a50 is what the live network reports (`eth_config` → `current.forkId` on
  *     https://rpc.plataberget.ethpandaops.io, 2026-09-24; `next` is null)
  *   - both checksums were computed independently as CRC32 over the genesis hash, then each passed fork's timestamp as
  *     a big-endian uint64 (Python `zlib.crc32`), which reproduces the live value above
  *
  * Platåberget is the first shipped chain whose genesis timestamp (1786622400) is non-zero AND later than forks it
  * declares at 0: Shanghai..BPO2 are all genesis-active, so the EIP-6122 "at or before genesis" rule must drop all six
  * and leave Amsterdam as the only checksum entry. A node that kept any of them would advertise a checksum no
  * Platåberget peer shares and be refused at every Status exchange.
  */
class ForkIdPlatabergetSpec extends AnyWordSpec with Matchers:

  private val platabergetConf = blockchains.blockchains("plataberget")

  private val GenesisTimestamp: Long = 1786622400L
  private val AmsterdamTimestamp: Long = 1787212224L

  private val platabergetGenesisHash =
    ByteString(Hex.decode("ee33ef92bbabcf07bcf44fea1d18a7925c5f7f9da8f81334ea19b0f3cb892b31"))

  /** Block number is irrelevant: the chain carries no block forks, which the first case below asserts. */
  private def create(ts: Long): ForkId =
    ForkId.create(platabergetGenesisHash, GenesisTimestamp, platabergetConf)(BigInt(0), ts)

  "ForkId for Platåberget" must {

    "carry no block forks, so the checksum chain reaches the timestamp forks" taggedAs (UnitTest, NetworkTest) in {
      // Every pre-Merge fork is "0" or the 10^18 sentinel, and London (olympia) is "0". A stray sentinel on
      // olympia-block-number would be re-appended as the advertised next fork and halt the chain before Amsterdam.
      ForkId.gatherBlockForks(platabergetConf) shouldBe empty
    }

    "drop the six genesis-active timestamp forks and keep Amsterdam alone" taggedAs (UnitTest, NetworkTest) in {
      ForkId.gatherTimestampForks(platabergetConf, GenesisTimestamp) shouldBe List(BigInt(AmsterdamTimestamp))
    }

    "advertise the genesis checksum with Amsterdam next, before Amsterdam" taggedAs (UnitTest, NetworkTest) in {
      create(GenesisTimestamp) shouldBe ForkId(0x94ebe4edL, Some(BigInt(AmsterdamTimestamp)))
      create(AmsterdamTimestamp - 1) shouldBe ForkId(0x94ebe4edL, Some(BigInt(AmsterdamTimestamp)))
    }

    "accumulate Amsterdam into the checksum the live network advertises — tail state, next=None" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      create(AmsterdamTimestamp) shouldBe ForkId(0x05842a50L, None)
      create(2000000000L) shouldBe ForkId(0x05842a50L, None)
    }

    /** WI-13 / issue #1429, acceptance criterion: the ENR `eth` entry ForkIdTag advertises must equal 0x05842a50 once
      * the head is past Amsterdam — not the pre-Shanghai genesis id ForkIdTag emitted while its 1-arg
      * `ForkId.create(head)` convenience call fixed headTimestamp at 0 (block number is irrelevant here, same as
      * `create` above: Platåberget carries no block forks). Exercises `ForkIdTag.toAttr` directly, the actual ENR
      * advertisement path — not `ForkId.create`, which the other tests in this file already cover directly.
      */
    "advertise 0x05842a50 through ForkIdTag.toAttr once the head is past Amsterdam" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      val tag = new ForkIdTag(
        genesisHash = () => platabergetGenesisHash,
        genesisTimestamp = () => GenesisTimestamp,
        blockchainConfig = platabergetConf,
        currentBestBlock = () => BigInt(0),
        currentBestBlockTimestamp = () => AmsterdamTimestamp
      )
      val (_, value) = tag.toAttr.get
      ForkIdTag.decodeEthEntry(value.toArray) shouldBe ForkId(0x05842a50L, None)
    }

    /** DNS seeding (EIP-1459) mirrors discv4's ForkIdTag filter at resolution time (DnsDiscovery.EnrForkIdFilter), so
      * it needs the same fix. Block number pinned at a tiny value throughout — Platåberget has no block forks (see the
      * first test in this file), so only the TIMESTAMP axis can be doing the work below.
      */
    "reject a stale pre-Amsterdam peer and accept an upgraded one through DnsDiscovery's EnrForkIdFilter" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      def filterAtHead(headTimestamp: Long) = new DnsDiscovery.EnrForkIdFilter(
        genesisHash = () => platabergetGenesisHash,
        genesisTimestamp = () => GenesisTimestamp,
        blockchainConfig = platabergetConf,
        currentBestBlock = () => BigInt(5),
        currentBestBlockTimestamp = () => headTimestamp
      )

      // Local past Amsterdam. Remote is the bare genesis-era ForkId with next=None: software that predates
      // every one of Platåberget's timestamp forks, not merely a peer that's behind but aware of what's next.
      val staleRemote = ForkId(create(0L).hash, None)
      filterAtHead(AmsterdamTimestamp).accepts(staleRemote) shouldBe false

      // Same local state; an honestly upgraded, past-Amsterdam remote is accepted.
      val upgradedRemote = create(AmsterdamTimestamp)
      filterAtHead(AmsterdamTimestamp).accepts(upgradedRemote) shouldBe true
    }
  }
