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

  "ForkId for ETH mainnet" must {

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

    "produce 0xc376cf8b at a Prague head, matching go-ethereum" taggedAs (UnitTest, NetworkTest) in {
      // THE pin: this is the checksum every mainnet peer expects today.
      create(23000000, 1760000000L) shouldBe ForkId(0xc376cf8bL, None)
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
      ForkId.create(mainnetGenesisHash, GenesisTimestamp, withoutGlaciers)(23000000, 1760000000L) shouldBe
        ForkId(0x8e91a3e4L, None)
    }
  }
