package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.*

/** EIP-2124 fork id for ETH mainnet, computed from the SHIPPED `eth-chain.conf` rather than a hand-built schedule, so
  * that dropping a fork from the config fails here and not on the wire.
  *
  * WHY THIS SPEC EXISTS. `eth-chain.conf` declared no Arrow Glacier (EIP-4345, block 13773000) and no Gray Glacier
  * (EIP-5133, block 15050000), and `ForkBlockNumbers` had no fields to hold them. Both are difficulty-bomb delays with
  * no EVM or state effect — and fukuii never implemented ETH's bomb schedule, which is presumably why they were left
  * out — but go-ethereum's `gatherForks` enumerates every `*Block` field of ChainConfig, so both enter the checksum
  * chain regardless.
  *
  * The consequence was not subtle: at a Prague head fukuii computed 0x8e91a3e4 where every mainnet peer computes
  * 0xc376cf8b, so the ETH status exchange was rejected with "wrong fork ID in status". fukuii could not peer on ETH
  * mainnet at ANY head past December 2021.
  *
  * Expected checksums are CRC32 accumulated over the mainnet genesis hash followed by each passed fork as a big-endian
  * uint64, computed independently of this implementation.
  *
  * SECOND GAP, SAME CLASS. `eth-chain.conf` also declared no Osaka (1764798551), BPO1 (1765290071) or BPO2 (1767747671)
  * timestamp — its comment still said Osaka was "not yet scheduled" after Fusaka activated on 2025-12-03. fukuii
  * therefore announced 0xc376cf8b with NO next fork at every head since, where mainnet peers announce 0x5167e2a6,
  * 0xcba2a1c0 and now 0x07c9462e, and it ran none of Osaka's rules on mainnet. The table below is go-ethereum's own
  * mainnet table, copied row for row from `core/forkid/forkid_test.go` at master 5b5c9d06810f (2026-09-22), so a fork
  * dropped from — or never added to — the shipped config fails here.
  */
class ForkIdEthMainnetSpec extends AnyWordSpec with Matchers:

  /** ETH mainnet genesis hash. */
  /** ETH mainnet's genesis header declares timestamp 0. */
  private val GenesisTimestamp: Long = 0L

  private val mainnetGenesisHash =
    ByteString(Hex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"))

  private val ethConf: BlockchainConfig = blockchains.blockchains("eth")

  private def create(head: BigInt, ts: Long): ForkId =
    ForkId.create(mainnetGenesisHash, GenesisTimestamp, ethConf)(head, ts)

  /** go-ethereum `core/forkid/forkid_test.go` TestCreation, mainnet: (head, time, hash, next, geth's comment). */
  private val gethMainnetTable: Seq[(Long, Long, Long, Option[Long], String)] = Seq(
    (0L, 0L, 0xfc64ec04L, Some(1150000L), "Unsynced"),
    (1149999L, 0L, 0xfc64ec04L, Some(1150000L), "Last Frontier block"),
    (1150000L, 0L, 0x97c2c34cL, Some(1920000L), "First Homestead block"),
    (1919999L, 0L, 0x97c2c34cL, Some(1920000L), "Last Homestead block"),
    (1920000L, 0L, 0x91d1f948L, Some(2463000L), "First DAO block"),
    (2462999L, 0L, 0x91d1f948L, Some(2463000L), "Last DAO block"),
    (2463000L, 0L, 0x7a64da13L, Some(2675000L), "First Tangerine block"),
    (2674999L, 0L, 0x7a64da13L, Some(2675000L), "Last Tangerine block"),
    (2675000L, 0L, 0x3edd5b10L, Some(4370000L), "First Spurious block"),
    (4369999L, 0L, 0x3edd5b10L, Some(4370000L), "Last Spurious block"),
    (4370000L, 0L, 0xa00bc324L, Some(7280000L), "First Byzantium block"),
    (7279999L, 0L, 0xa00bc324L, Some(7280000L), "Last Byzantium block"),
    (7280000L, 0L, 0x668db0afL, Some(9069000L), "First and last Constantinople, first Petersburg block"),
    (9068999L, 0L, 0x668db0afL, Some(9069000L), "Last Petersburg block"),
    (9069000L, 0L, 0x879d6e30L, Some(9200000L), "First Istanbul and first Muir Glacier block"),
    (9199999L, 0L, 0x879d6e30L, Some(9200000L), "Last Istanbul and first Muir Glacier block"),
    (9200000L, 0L, 0xe029e991L, Some(12244000L), "First Muir Glacier block"),
    (12243999L, 0L, 0xe029e991L, Some(12244000L), "Last Muir Glacier block"),
    (12244000L, 0L, 0x0eb440f6L, Some(12965000L), "First Berlin block"),
    (12964999L, 0L, 0x0eb440f6L, Some(12965000L), "Last Berlin block"),
    (12965000L, 0L, 0xb715077dL, Some(13773000L), "First London block"),
    (13772999L, 0L, 0xb715077dL, Some(13773000L), "Last London block"),
    (13773000L, 0L, 0x20c327fcL, Some(15050000L), "First Arrow Glacier block"),
    (15049999L, 0L, 0x20c327fcL, Some(15050000L), "Last Arrow Glacier block"),
    (15050000L, 0L, 0xf0afd0e3L, Some(1681338455L), "First Gray Glacier block"),
    (20000000L, 1681338454L, 0xf0afd0e3L, Some(1681338455L), "Last Gray Glacier block"),
    (20000000L, 1681338455L, 0xdce96c2dL, Some(1710338135L), "First Shanghai block"),
    (30000000L, 1710338134L, 0xdce96c2dL, Some(1710338135L), "Last Shanghai block"),
    (30000000L, 1710338135L, 0x9f3d2254L, Some(1746612311L), "First Cancun block"),
    (30000000L, 1746022486L, 0x9f3d2254L, Some(1746612311L), "Last Cancun block"),
    (30000000L, 1746612311L, 0xc376cf8bL, Some(1764798551L), "First Prague block"),
    (30000000L, 1764798550L, 0xc376cf8bL, Some(1764798551L), "Last Prague block"),
    (30000000L, 1764798551L, 0x5167e2a6L, Some(1765290071L), "First Osaka block"),
    (30000000L, 1765290070L, 0x5167e2a6L, Some(1765290071L), "Last Osaka block"),
    (30000000L, 1765290071L, 0xcba2a1c0L, Some(1767747671L), "First BPO1 block"),
    (30000000L, 1767747670L, 0xcba2a1c0L, Some(1767747671L), "Last BPO1 block"),
    (30000000L, 1767747671L, 0x07c9462eL, None, "First BPO2 block"),
    (50000000L, 2000000000L, 0x07c9462eL, None, "Future BPO2 block")
  )

  "ForkId for ETH mainnet" must {

    "match every row of go-ethereum's mainnet fork-id table" taggedAs (UnitTest, NetworkTest) in {
      gethMainnetTable.foreach { case (head, time, hash, next, label) =>
        withClue(s"$label (head=$head, time=$time): ") {
          create(head, time) shouldBe ForkId(hash, next.map(BigInt(_)))
        }
      }
    }

    "enumerate the timestamp forks through BPO2, as go-ethereum does" taggedAs (UnitTest, NetworkTest) in {
      ForkId.gatherTimestampForks(ethConf, GenesisTimestamp) shouldBe List[BigInt](
        1681338455L, // Shanghai
        1710338135L, // Cancun
        1746612311L, // Prague
        1764798551L, // Osaka
        1765290071L, // BPO1
        1767747671L // BPO2
      )
    }

    "announce 0x5167e2a6 at an Osaka head" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1764798551L) shouldBe ForkId(0x5167e2a6L, Some(1765290071L))
    }

    "announce 0xcba2a1c0 at a BPO1 head" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1765290071L) shouldBe ForkId(0xcba2a1c0L, Some(1767747671L))
    }

    "announce 0x07c9462e at a BPO2 head, with no fork left to announce" taggedAs (UnitTest, NetworkTest) in {
      create(30000000, 1767747671L) shouldBe ForkId(0x07c9462eL, None)
    }

    "regress to the stale Prague checksum if Osaka, BPO1 and BPO2 are dropped" taggedAs (UnitTest, NetworkTest) in {
      // Negative control pinning the exact symptom: this is what fukuii announced at every
      // mainnet head after 2025-12-03 — the Prague hash, and no next fork at all.
      val withoutFusaka = ethConf.copy(forkTimestamps =
        ethConf.forkTimestamps.copy(osakaTimestamp = None, bpo1Timestamp = None, bpo2Timestamp = None)
      )
      ForkId.create(mainnetGenesisHash, GenesisTimestamp, withoutFusaka)(30000000, 1767747671L) shouldBe
        ForkId(0xc376cf8bL, None)
    }

    "enumerate both glacier forks in the block-fork chain" taggedAs (UnitTest, NetworkTest) in {
      // The DAO fork (1920000) is included via daoForkConfig.includeOnForkIdList.
      ForkId.gatherBlockForks(ethConf) shouldBe List[BigInt](
        1150000, 1920000, 2463000, 2675000, 4370000, 7280000, 9069000, 9200000, 12244000, 12965000,
        13773000, // Arrow Glacier (EIP-4345)
        15050000 // Gray Glacier (EIP-5133)
      )
    }

    "announce Arrow Glacier as the next fork while the head is between London and it" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      // Unchanged by this fix — the divergence only begins once Arrow Glacier is passed,
      // which is why nothing caught it until a modern head was tried.
      create(13000000, 0) shouldBe ForkId(0xb715077dL, Some(13773000))
    }

    "match the published mainnet checksum at a Shanghai head" taggedAs (UnitTest, NetworkTest) in {
      // 0xdce96c2d is mainnet's published Shanghai fork id — an oracle independent of this
      // codebase, and of the CRC reconstruction used to derive the Prague value below.
      create(17000000, 1681338455L) shouldBe ForkId(0xdce96c2dL, Some(1710338135L))
    }

    "match the published mainnet checksum at a Cancun head" taggedAs (UnitTest, NetworkTest) in {
      // 0x9f3d2254 is mainnet's published Cancun fork id. Together with the Shanghai case
      // this pins the ENTIRE preceding fork list: both glacier forks and the DAO block.
      create(19500000, 1710338135L) shouldBe ForkId(0x9f3d2254L, Some(1746612311L))
    }

    "produce 0xc376cf8b at a Prague head, announcing Osaka next, matching go-ethereum" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      // The hash pin is unchanged. `next` was pinned as None while eth-chain.conf lacked Osaka;
      // go-ethereum's table ("Last Prague block") announces 1764798551 here.
      create(23000000, 1760000000L) shouldBe ForkId(0xc376cf8bL, Some(1764798551L))
    }

    "regress to an unpeerable checksum if the DAO block leaves the fork-id list" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      // `include-on-fork-id-list` is correctly false on ETC, which rejected the DAO fork.
      // The ETH config inherited that false, which is how mainnet lost block 1920000.
      val daoExcluded = ethConf.copy(daoForkConfig = ethConf.daoForkConfig.map { d =>
        new com.chipprbots.ethereum.utils.DaoForkConfig:
          override val forkBlockNumber: BigInt = d.forkBlockNumber
          override val forkBlockHash = d.forkBlockHash
          override val blockExtraData = d.blockExtraData
          override val range: Int = d.range
          override val refundContract = d.refundContract
          override val drainList = d.drainList
          override val includeOnForkIdList: Boolean = false
      })
      ForkId.create(mainnetGenesisHash, GenesisTimestamp, daoExcluded)(17000000, 1681338455L) shouldBe
        ForkId(0x5de97580L, Some(1710338135L))
    }

    "regress to the unpeerable checksum if either glacier fork is dropped" taggedAs (UnitTest, NetworkTest) in {
      // Negative control pinning the exact symptom: 0x8e91a3e4 is what fukuii put on the
      // wire, and what every geth peer rejected.
      val withoutGlaciers = ethConf.copy(
        forkBlockNumbers = ethConf.forkBlockNumbers.copy(
          arrowGlacierBlockNumber = Long.MaxValue,
          grayGlacierBlockNumber = Long.MaxValue
        )
      )
      // `next` is now Osaka: the glacier gap corrupts the hash, not the fork announcement.
      ForkId.create(mainnetGenesisHash, GenesisTimestamp, withoutGlaciers)(23000000, 1760000000L) shouldBe
        ForkId(0x8e91a3e4L, Some(1764798551L))
    }
  }
