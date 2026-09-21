package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.ByteUtils

import BlockHeaderImplicits.*

/** Slice A of the Amsterdam feature: strict header arity.
  *
  * Two distinct properties are asserted here.
  *
  *   1. `HefPostAmsterdam` exists, carries `blockAccessListHash` (RLP item 21) and `slotNumber` (RLP item 22) in the
  *      order measured in `specs/009-amsterdam-fork-support/contracts/header-rlp.md`, and a 23-item header round-trips
  *      byte-identically through decode/encode.
  *   1. The decoder maps item count to shape **exactly**. Before this change the sole tolerant branch,
  *      `case n if n >= 21`, accepted 22, 23 and 24 items as `HefPostPrague`, silently dropped the trailing items and
  *      re-encoded to 21 — producing a hash that is not the hash that went in. That is the defect this spec pins.
  *
  * **Provenance of the vectors**: synthetic. The reference fixture (hive's devp2p `chain.rlp`, whose block 600 hashes
  * to `6372c88f…`) is not vendored in this repository — see `scripts/amsterdam-fixture/README.md` — so no canonical
  * Amsterdam header bytes were available. Every header below is constructed from a `BlockHeader` value, encoded, and
  * checked for self-consistency. That establishes the arity table and the round-trip invariant; it does **not**
  * establish agreement with go-ethereum on the canonical hash. No canonical hash is asserted, deliberately: an
  * invented expectation would be worse than none.
  */
// scalastyle:off magic.number
class BlockHeaderAmsterdamRlpSpec extends AnyWordSpec with Matchers:

  private val base: BlockHeader = Fixtures.Blocks.ValidBlock.header

  private def b32(fill: Byte): ByteString = ByteString(Array.fill[Byte](32)(fill))

  private val postOlympia = HefPostOlympia(baseFee = BigInt(1_000_000_000))

  private val postShanghai = HefPostShanghai(
    baseFee = BigInt(1_000_000_000),
    withdrawalsRoot = b32(0x11)
  )

  private val postCancun = HefPostCancun(
    baseFee = BigInt(1_000_000_000),
    withdrawalsRoot = b32(0x11),
    blobGasUsed = BigInt(131072),
    excessBlobGas = BigInt(262144),
    parentBeaconBlockRoot = b32(0x22)
  )

  private val postPrague = HefPostPrague(
    baseFee = BigInt(1_000_000_000),
    withdrawalsRoot = b32(0x11),
    blobGasUsed = BigInt(131072),
    excessBlobGas = BigInt(262144),
    parentBeaconBlockRoot = b32(0x22),
    requestsHash = b32(0x33)
  )

  private val postAmsterdam = HefPostAmsterdam(
    baseFee = BigInt(1_000_000_000),
    withdrawalsRoot = b32(0x11),
    blobGasUsed = BigInt(131072),
    excessBlobGas = BigInt(262144),
    parentBeaconBlockRoot = b32(0x22),
    requestsHash = b32(0x33),
    blockAccessListHash = b32(0x44),
    slotNumber = BigInt(600)
  )

  /** The six shapes in `contracts/header-rlp.md`, paired with their RLP item counts. */
  private val shapes: Seq[(String, Int, HeaderExtraFields)] = Seq(
    ("HefEmpty", 15, HefEmpty),
    ("HefPostOlympia", 16, postOlympia),
    ("HefPostShanghai", 17, postShanghai),
    ("HefPostCancun", 20, postCancun),
    ("HefPostPrague", 21, postPrague),
    ("HefPostAmsterdam", 23, postAmsterdam)
  )

  private def headerOf(ef: HeaderExtraFields): BlockHeader = base.copy(extraFields = ef)

  private def itemsOf(header: BlockHeader): Seq[RLPEncodeable] =
    header.toRLPEncodable match
      case l: RLPList => l.items
      case other      => fail(s"expected RLPList, got $other")

  private def encodeItems(items: Seq[RLPEncodeable]): Array[Byte] = rlp.encode(RLPList(items*))

  "BlockHeader RLP — Amsterdam (23 items)" should {

    "encode exactly 23 items, with blockAccessListHash at 21 and slotNumber at 22" taggedAs (UnitTest,
      ConsensusTest) in {
      val items = itemsOf(headerOf(postAmsterdam))
      items.length shouldBe 23

      // Positions are the measured ones; assert on the wire bytes rather than on the case class,
      // so a field reorder in the encoder cannot pass.
      items(20) shouldBe RLPValue(b32(0x33).toArray)
      items(21) shouldBe RLPValue(b32(0x44).toArray)
      items(22) shouldBe RLPValue(ByteUtils.bigIntToUnsignedByteArray(BigInt(600)))
    }

    "decode a 23-item header to HefPostAmsterdam, not HefPostPrague" taggedAs (UnitTest, ConsensusTest) in {
      val bytes = rlp.encode(headerOf(postAmsterdam).toRLPEncodable)
      val decoded = bytes.toBlockHeader

      decoded.extraFields shouldBe a[HefPostAmsterdam]
      decoded.extraFields shouldBe postAmsterdam
    }

    "re-encode a 23-item header byte-identically and preserve its hash" taggedAs (UnitTest, ConsensusTest) in {
      val header = headerOf(postAmsterdam)
      val bytes = rlp.encode(header.toRLPEncodable)
      val decoded = bytes.toBlockHeader
      val reencoded = rlp.encode(decoded.toRLPEncodable)

      reencoded shouldBe bytes
      itemsOf(decoded).length shouldBe 23
      decoded.hash shouldBe header.hash
    }

    "expose every Amsterdam field through the total accessors (a missing case is a silent None)" taggedAs (UnitTest,
      ConsensusTest) in {
      val decoded = rlp.encode(headerOf(postAmsterdam).toRLPEncodable).toBlockHeader

      decoded.baseFee shouldBe Some(BigInt(1_000_000_000))
      decoded.withdrawalsRoot shouldBe Some(b32(0x11))
      decoded.blobGasUsed shouldBe Some(BigInt(131072))
      decoded.excessBlobGas shouldBe Some(BigInt(262144))
      decoded.parentBeaconBlockRoot shouldBe Some(BlockHash(b32(0x22)))
      decoded.requestsHash shouldBe Some(b32(0x33))
    }

    "round-trip zero-valued big-int fields through minimal unsigned encoding" taggedAs (UnitTest, ConsensusTest) in {
      val zeroed = postAmsterdam.copy(baseFee = 0, blobGasUsed = 0, excessBlobGas = 0, slotNumber = 0)
      val bytes = rlp.encode(headerOf(zeroed).toRLPEncodable)
      val decoded = bytes.toBlockHeader

      decoded.extraFields shouldBe zeroed
      rlp.encode(decoded.toRLPEncodable) shouldBe bytes
    }
  }

  "BlockHeader RLP — the arity table" should {

    shapes.foreach { case (name, count, ef) =>
      s"emit $count items and satisfy encode(decode(bytes)) == bytes for $name" taggedAs (UnitTest, ConsensusTest) in {
        val header = headerOf(ef)
        val bytes = rlp.encode(header.toRLPEncodable)

        itemsOf(header).length shouldBe count

        val decoded = bytes.toBlockHeader
        decoded shouldBe header
        decoded.extraFields shouldBe ef
        rlp.encode(decoded.toRLPEncodable) shouldBe bytes
        decoded.hash shouldBe header.hash
      }
    }
  }

  "BlockHeader RLP — rejected arities" should {

    // 18, 19, 22 and 24 have no shape. Before Slice A, 22 and 24 were ACCEPTED as HefPostPrague by
    // the `case n if n >= 21` catch-all and re-encoded to 21 items with a different hash; only 18
    // and 19 were rejected. All four must now be refused, with the count named.
    val amsterdamItems = itemsOf(headerOf(postAmsterdam))

    Seq(18, 19, 22).foreach { n =>
      s"reject a $n-item header and name the count" taggedAs (UnitTest, ConsensusTest) in {
        val bytes = encodeItems(amsterdamItems.take(n))
        val thrown = the[Exception] thrownBy bytes.toBlockHeader
        thrown.getMessage should include(n.toString)
      }
    }

    "reject a 24-item header and name the count" taggedAs (UnitTest, ConsensusTest) in {
      val bytes = encodeItems(amsterdamItems :+ RLPValue(Array[Byte](0x01)))
      val thrown = the[Exception] thrownBy bytes.toBlockHeader
      thrown.getMessage should include("24")
    }

    "reject a 14-item header (below the base-field minimum)" taggedAs (UnitTest, ConsensusTest) in {
      val bytes = encodeItems(amsterdamItems.take(14))
      val thrown = the[Exception] thrownBy bytes.toBlockHeader
      thrown.getMessage should include("14")
    }
  }
// scalastyle:on magic.number
