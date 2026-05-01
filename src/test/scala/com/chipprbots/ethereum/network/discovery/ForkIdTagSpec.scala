package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.{Hex => BCHex}
import org.scalatest.matchers.should._
import org.scalatest.wordspec.AnyWordSpec
import scodec.bits.{BitVector, ByteVector}

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId._
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.Config._
import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord

class ForkIdTagSpec extends AnyWordSpec with Matchers {

  val config = blockchains
  val etcConf = config.blockchains("etc")
  val etcGenesis: ByteString =
    ByteString(BCHex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"))

  private val ethKey = EthereumNodeRecord.Keys.key("eth")
  private val dummySig = Signature(BitVector.empty)

  private def makeTag(head: BigInt, conf: com.chipprbots.ethereum.utils.BlockchainConfig = etcConf): ForkIdTag =
    new ForkIdTag(() => etcGenesis, conf, () => head)

  private def enrWith(forkId: ForkId): EthereumNodeRecord =
    EthereumNodeRecord(dummySig, 0L, ethKey -> ByteVector(encode(forkId.toRLPEncodable)))

  private val enrWithoutEth: EthereumNodeRecord =
    EthereumNodeRecord(dummySig, 0L)

  "ForkIdTag.toFilter" must {

    "accept an ENR with no eth key (pre-EIP-2124 node)" in {
      makeTag(20000000).toFilter(enrWithoutEth) shouldBe Right(())
    }

    "accept a peer on the same chain at the same state (Spiral)" in {
      val spiralForkId = ForkId(0xbe46d57cL, None)
      makeTag(20000000).toFilter(enrWith(spiralForkId)) shouldBe Right(())
    }

    "accept a peer that is ahead on the same chain (local is syncing)" in {
      // Local at genesis-era (block 1000). Remote is at Spiral.
      // checkSuperset: Spiral checksum appears in local's future checksums → Connect.
      val spiralForkId = ForkId(0xbe46d57cL, None)
      makeTag(1000).toFilter(enrWith(spiralForkId)) shouldBe Right(())
    }

    "accept a peer that is behind on the same chain and knows the next fork (remote is syncing)" in {
      // Local at Spiral (block 20M). Remote is at genesis-era but reports next=1150000 correctly.
      // checkSubset: genesis checksum (0xfc64ec04) with next=1150000 matches local fork[0] → Connect.
      val genesisForkId = ForkId(0xfc64ec04L, Some(1150000))
      makeTag(20000000).toFilter(enrWith(genesisForkId)) shouldBe Right(())
    }

    "reject a peer on an incompatible chain when local has no future fork pending" in {
      // Local at Spiral (no Olympia scheduled). Remote is on ETH mainnet (Petersburg hash).
      // The ETH Petersburg hash never appears in ETC's checksum chain → ErrLocalIncompatibleOrStale.
      val ethPetersburg = ForkId(0x668db0afL, None)
      makeTag(20000000).toFilter(enrWith(ethPetersburg)) shouldBe a[Left[_, _]]
    }

    // *** THE CRITICAL BUG REGRESSION ***
    //
    // When olympiaBlockNumber is set to a real block (not the noFork sentinel), local.next becomes
    // Some(olympiaBlock). The old validateForkId had:
    //
    //   local.next match {
    //     case Some(_) => Right(())   // ← BUG: accepts ALL peers while a future fork is pending
    //     ...
    //   }
    //
    // This caused ETH mainnet peers, Polygon nodes, etc. to pass the ENR filter as soon as
    // Olympia's block number was announced. ForkIdValidator.validatePeer must reject them.
    "reject a peer on an incompatible chain even when local has a future fork pending (Olympia scheduled)" in {
      val olympiaConf = etcConf.copy(
        forkBlockNumbers = etcConf.forkBlockNumbers.copy(olympiaBlockNumber = 30000000)
      )
      // Local: past Spiral (20M), Olympia pending at 30M → local.next = Some(30000000).
      val tag = makeTag(20000000, olympiaConf)
      // Remote: ETH mainnet. Hash 0x668db0af never appears in ETC's checksum chain.
      val ethPetersburg = ForkId(0x668db0afL, None)
      tag.toFilter(enrWith(ethPetersburg)) shouldBe a[Left[_, _]]
    }

    "reject an ENR with malformed eth key bytes" in {
      val badBytes = ByteVector(0xff.toByte, 0xfe.toByte, 0x00.toByte)
      val enr = EthereumNodeRecord(dummySig, 0L, ethKey -> badBytes)
      makeTag(20000000).toFilter(enr) shouldBe a[Left[_, _]]
    }
  }
}
