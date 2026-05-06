package com.chipprbots.ethereum.forkid

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.{Hex => BCHex}
import org.scalatest.matchers.should._
import org.scalatest.wordspec.AnyWordSpec
import scodec.bits.{BitVector, ByteVector}

import com.chipprbots.ethereum.forkid.ForkId._
import com.chipprbots.ethereum.network.discovery.ForkIdTag
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.Config._
import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord

/** Cross-chain ForkId filtering tests — the networkId=1 collision case.
  *
  * ETC mainnet and ETH mainnet both have networkId=1 (a legacy artifact from before the DAO fork). NetworkId alone
  * CANNOT distinguish an ETC peer from an ETH peer. The forkId (EIP-2124), embedded in the ENR at discovery time, is
  * the ONLY reliable peer filter. These tests verify that:
  *
  *   1. Mordor's forkId is rejected by ETC mainnet's ForkIdTag (and vice versa) 2. ETH forkIds are rejected by ETC
  *      mainnet's ForkIdTag 3. The Olympia fork signal state machine transitions correctly through all three states
  */
class NetworkForkIdFilteringSpec extends AnyWordSpec with Matchers {

  private val config = blockchains
  private val etcConf = config.blockchains("etc")
  private val mordorConf = config.blockchains("mordor")

  private val etcGenesisHash: ByteString =
    ByteString(BCHex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"))

  private val mordorGenesisHash: ByteString =
    ByteString(BCHex.decode("a68ebde7932eccb177d38d55dcc6461a019dd795a681e59b5a3e4f3a7259a3f1"))

  private val ethKey = EthereumNodeRecord.Keys.key("eth")
  private val dummySig = Signature(BitVector.empty)

  private def enrWith(forkId: ForkId): EthereumNodeRecord =
    EthereumNodeRecord(dummySig, 0L, ethKey -> ByteVector(encode(forkId.toRLPEncodable)))

  private def etcTag(head: BigInt): ForkIdTag =
    new ForkIdTag(() => etcGenesisHash, etcConf, () => head)

  private def mordorTag(head: BigInt): ForkIdTag =
    new ForkIdTag(() => mordorGenesisHash, mordorConf, () => head)

  // Known forkId values, verified against core-geth reference implementation.
  private val EtcSpiralForkId = ForkId(0xbe46d57cL, None)
  private val MordorSpiralForkId = ForkId(0x3a6b00d7L, None)
  private val EthPetersburgForkId = ForkId(0x668db0afL, None)

  "NetworkId=1 collision: ForkId as the distinguishing filter" must {

    // -----------------------------------------------------------------------
    // ETC mainnet peer filtering
    // -----------------------------------------------------------------------

    "accept an ETC mainnet peer (same Spiral forkId) on the ETC mainnet ForkIdTag" in {
      etcTag(20000000).toFilter(enrWith(EtcSpiralForkId)) shouldBe Right(())
    }

    "reject a Mordor peer (Mordor Spiral forkId 0x3a6b00d7) on the ETC mainnet ForkIdTag" in {
      // Mordor's checksum chain diverges at genesis (different genesis hash).
      // 0x3a6b00d7 never appears in ETC mainnet's checksum chain → incompatible.
      etcTag(20000000).toFilter(enrWith(MordorSpiralForkId)) shouldBe a[Left[_, _]]
    }

    "reject an ETH mainnet peer (ETH Petersburg forkId 0x668db0af) on the ETC mainnet ForkIdTag" in {
      // ETH diverges from ETC at the DAO fork (block 1,920,000).
      // Both have networkId=1 — forkId is the only reliable filter.
      etcTag(20000000).toFilter(enrWith(EthPetersburgForkId)) shouldBe a[Left[_, _]]
    }

    // -----------------------------------------------------------------------
    // Mordor peer filtering
    // -----------------------------------------------------------------------

    "accept a Mordor peer (same Spiral forkId) on the Mordor ForkIdTag" in {
      mordorTag(20000000).toFilter(enrWith(MordorSpiralForkId)) shouldBe Right(())
    }

    "reject an ETC mainnet peer (ETC Spiral forkId 0xbe46d57c) on the Mordor ForkIdTag" in {
      // ETC mainnet's checksum 0xbe46d57c never appears in Mordor's chain (different genesis).
      mordorTag(20000000).toFilter(enrWith(EtcSpiralForkId)) shouldBe a[Left[_, _]]
    }

    "reject an ETH mainnet peer on the Mordor ForkIdTag" in {
      mordorTag(20000000).toFilter(enrWith(EthPetersburgForkId)) shouldBe a[Left[_, _]]
    }

    // -----------------------------------------------------------------------
    // ETC forkId value stability
    // -----------------------------------------------------------------------

    "confirm ETC mainnet Spiral forkId is 0xbe46d57c (verified against core-geth)" in {
      val created = ForkId.create(etcGenesisHash, etcConf)(19250000)
      created.hash shouldBe 0xbe46d57cL
      created.next shouldBe None
    }

    "confirm Mordor Spiral forkId is 0x3a6b00d7 (verified against core-geth)" in {
      val created = ForkId.create(mordorGenesisHash, mordorConf)(9957000)
      created.hash shouldBe 0x3a6b00d7L
      created.next shouldBe None
    }
  }

  private val olympiaConf = etcConf.copy(
    forkBlockNumbers = etcConf.forkBlockNumbers.copy(olympiaBlockNumber = 30000000)
  )

  "Olympia fork signal state machine" must {

    // State 1: No Olympia block configured (sentinel 10^18 excluded from fork list).
    // ForkId = Spiral hash, next=None. This is the current production state.
    "emit Spiral/None when Olympia block is not configured (current production state)" in {
      val id = ForkId.create(etcGenesisHash, etcConf)(20000000)
      id shouldBe ForkId(0xbe46d57cL, None)
    }

    // State 2: Olympia block configured but not yet reached.
    // ForkId = Spiral hash, next=Some(olympiaBlock).
    // This signals to peers that a fork is coming, per EIP-2124 §4.
    "emit Spiral/next=Olympia when Olympia block is configured but not yet reached" in {
      val id = ForkId.create(etcGenesisHash, olympiaConf)(25000000)
      id.hash shouldBe 0xbe46d57cL
      id.next shouldBe Some(BigInt(30000000))
    }

    // State 3: Olympia block reached (head >= olympiaBlock).
    // ForkId = new Olympia hash, next=None.
    "emit Olympia hash/None when past the Olympia activation block" in {
      val id = ForkId.create(etcGenesisHash, olympiaConf)(30000000)
      (id.hash should not).equal(0xbe46d57cL) // hash changes at fork
      id.next shouldBe None // no upcoming fork known
    }

    // The filter must reject ETH peers in all three states (regression guard for the Olympia-pending bug).
    "reject ETH mainnet peers in all three Olympia state machine states" in {
      // State 1: no Olympia configured
      etcTag(20000000).toFilter(enrWith(EthPetersburgForkId)) shouldBe a[Left[_, _]]

      // State 2: Olympia pending
      val tag2 = new ForkIdTag(() => etcGenesisHash, olympiaConf, () => 25000000)
      tag2.toFilter(enrWith(EthPetersburgForkId)) shouldBe a[Left[_, _]]

      // State 3: Olympia activated
      val tag3 = new ForkIdTag(() => etcGenesisHash, olympiaConf, () => 31000000)
      tag3.toFilter(enrWith(EthPetersburgForkId)) shouldBe a[Left[_, _]]
    }

    "accept a syncing ETC peer that knows about Olympia from a post-Spiral head" in {
      // Local is past Olympia, remote is still at Spiral but reports next=Some(olympiaBlock).
      // Per EIP-2124 subset rule: Spiral checksum with correct next-pointer matches → Connect.
      val spiralWithOlympiaPending = ForkId(0xbe46d57cL, Some(30000000))
      val tag = new ForkIdTag(() => etcGenesisHash, olympiaConf, () => 35000000)
      tag.toFilter(enrWith(spiralWithOlympiaPending)) shouldBe Right(())
    }
  }
}
