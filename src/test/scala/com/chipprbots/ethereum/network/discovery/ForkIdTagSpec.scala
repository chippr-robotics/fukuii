package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.util.ByteString

import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import org.bouncycastle.util.encoders.Hex as BCHex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec
import scodec.bits.BitVector
import scodec.bits.ByteVector

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId.*
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.*

class ForkIdTagSpec extends AnyWordSpec with Matchers:

  val config = blockchains
  val etcConf: BlockchainConfig = config.blockchains("etc")
  val etcGenesis: ByteString =
    ByteString(BCHex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"))

  private val ethKey = EthereumNodeRecord.Keys.key("eth")
  private val dummySig = Signature(BitVector.empty)

  private def makeTag(head: BigInt, conf: com.chipprbots.ethereum.utils.BlockchainConfig = etcConf): ForkIdTag =
    new ForkIdTag(() => etcGenesis, () => 0L, conf, () => head)

  /** An ENR whose `eth` entry is in the standard form every other client writes: `[[fork-hash, fork-next], ...rest]`.
    * Built by hand, not with ForkIdTag.encodeEthEntry, so the filter tests do not just agree with our own encoder.
    */
  private def enrWith(forkId: ForkId, rest: RLPEncodeable*): EthereumNodeRecord =
    EthereumNodeRecord(dummySig, 0L, ethKey -> ByteVector(encode(RLPList((forkId.toRLPEncodable +: rest)*))))

  /** The bare `[fork-hash, fork-next]` fukuii wrote before the entry was fixed. */
  private def enrWithLegacyEntry(forkId: ForkId): EthereumNodeRecord =
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
      makeTag(20000000).toFilter(enrWith(ethPetersburg)) shouldBe a[Left[?, ?]]
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
      tag.toFilter(enrWith(ethPetersburg)) shouldBe a[Left[?, ?]]
    }

    "reject an ENR with malformed eth key bytes" in {
      val badBytes = ByteVector(0xff.toByte, 0xfe.toByte, 0x00.toByte)
      val enr = EthereumNodeRecord(dummySig, 0L, ethKey -> badBytes)
      makeTag(20000000).toFilter(enr) shouldBe a[Left[?, ?]]
    }

    "ignore fields after the fork ID, as go-ethereum's `Rest` tail allows" in {
      val spiralForkId = ForkId(0xbe46d57cL, None)
      val extra = RLPValue(Array[Byte](1, 2, 3))
      makeTag(20000000).toFilter(enrWith(spiralForkId, extra)) shouldBe Right(())
    }

    "still read the bare fork ID older fukuii nodes wrote" in {
      val spiralForkId = ForkId(0xbe46d57cL, None)
      makeTag(20000000).toFilter(enrWithLegacyEntry(spiralForkId)) shouldBe Right(())
      val ethPetersburg = ForkId(0x668db0afL, None)
      makeTag(20000000).toFilter(enrWithLegacyEntry(ethPetersburg)) shouldBe a[Left[?, ?]]
    }
  }

  "ForkIdTag.toAttr" must {

    "write the eth entry as [[fork-hash, fork-next]], the form go-ethereum's node filter loads" in {
      // Derived from the RLP rules, independently of our encoder: fork-hash is a 4-byte string
      // (84 fc64ec04), fork-next 1150000 = 0x118c30 is a 3-byte string (83 118c30), their list
      // has 9 bytes of payload (c9 ...), and the entry wraps that in a second list (ca ...).
      ForkIdTag.encodeEthEntry(ForkId(0xfc64ec04L, Some(1150000))).toHex shouldBe "cac984fc64ec0483118c30"
    }

    "advertise a record whose own filter reads it back" in {
      val tag = makeTag(20000000)
      val (key, value) = tag.toAttr.get
      key shouldBe ethKey
      rawDecode(value.toArray) match
        case RLPList(_: RLPList) => ()
        case other               => fail(s"eth entry is not [[fork-hash, fork-next]]: $other")
      tag.toFilter(EthereumNodeRecord(dummySig, 0L, key -> value)) shouldBe Right(())
    }
  }
