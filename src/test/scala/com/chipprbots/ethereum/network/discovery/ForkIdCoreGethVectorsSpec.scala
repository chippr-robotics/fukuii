package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.util.ByteString

import cats.effect.SyncIO

import com.chipprbots.scalanet.discovery.crypto.Signature
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import org.bouncycastle.util.encoders.Hex as BCHex
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec
import scodec.bits.BitVector
import scodec.bits.ByteVector

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkIdValidationResult.Connect
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.*

/** fukuii's ETC and Mordor fork IDs, and the ENR `eth` entry that carries them, against core-geth.
  *
  * Oracle: core-geth core/forkid/forkid_test.go, its "classic" and "mordor" vectors (head, hash, next), and core-geth
  * eth/protocols/eth/discovery.go's enrEntry{ForkID; Rest "tail"} as go-rlp encodes it. A fork ID that drifts from
  * these makes core-geth peers refuse fukuii, or fukuii's discovery refuse them, which on ETC is most of the network.
  *
  * One known difference: past ETC's last scheduled fork, fukuii's `next` is its far-future Olympia placeholder where
  * core-geth's is 0. EIP-2124 still reads that as compatible (a fork the other side has not scheduled yet), and the
  * Connect checks below hold it to that.
  */
class ForkIdCoreGethVectorsSpec extends AnyWordSpec with Matchers:

  private val ethKey = EthereumNodeRecord.Keys.key("eth")
  private val dummySig = Signature(BitVector.empty)
  private val Olympia = BigInt("1000000000000000000")

  private val etcConf: BlockchainConfig = blockchains.blockchains("etc")
  private val mordorConf: BlockchainConfig = blockchains.blockchains("mordor")
  private val etcGenesis =
    ByteString(BCHex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3"))
  private val mordorGenesis =
    ByteString(BCHex.decode("a68ebde7932eccb177d38d55dcc6461a019dd795a681e59b5a3e4f3a7259a3f1"))

  private val etcVectors: Seq[(BigInt, Long, BigInt)] = Seq(
    (0, 0xfc64ec04L, 1150000),
    (1149999, 0xfc64ec04L, 1150000),
    (1150000, 0x97c2c34cL, 2500000),
    (2499999, 0x97c2c34cL, 2500000),
    (2500000, 0xdb06803fL, 3000000),
    (2999999, 0xdb06803fL, 3000000),
    (3000000, 0xaff4bed4L, 5000000),
    (4999999, 0xaff4bed4L, 5000000),
    (5000000, 0xf79a63c0L, 5900000),
    (5899999, 0xf79a63c0L, 5900000),
    (5900000, 0x744899d6L, 8772000),
    (8771999, 0x744899d6L, 8772000),
    (8772000, 0x518b59c6L, 9573000),
    (9572999, 0x518b59c6L, 9573000),
    (9573000, 0x7ba22882L, 10500839),
    (10500838, 0x7ba22882L, 10500839),
    (10500839, 0x9007bfccL, 11700000),
    (11699999, 0x9007bfccL, 11700000),
    (11700000, 0xdb63a1caL, 13189133),
    (13189132, 0xdb63a1caL, 13189133),
    (13189133, 0x0f6bf187L, 14525000),
    (14524999, 0x0f6bf187L, 14525000),
    (14525000, 0x7fd1bb25L, 19250000),
    (19249999, 0x7fd1bb25L, 19250000),
    (19250000, 0xbe46d57cL, 0),
    (19250001, 0xbe46d57cL, 0)
  ).map { case (h, x, n) => (BigInt(h), x, BigInt(n)) }

  private val mordorVectors: Seq[(BigInt, Long, BigInt)] = Seq(
    (0, 0x175782aaL, 301243),
    (301242, 0x175782aaL, 301243),
    (301243, 0x604f6ee1L, 999983),
    (999982, 0x604f6ee1L, 999983),
    (999983, 0xf42f5539L, 2520000),
    (2519999, 0xf42f5539L, 2520000),
    (2520000, 0x66b5c286L, 3985893),
    (3985892, 0x66b5c286L, 3985893),
    (3985893, 0x92b323e0L, 5520000),
    (5519999, 0x92b323e0L, 5520000),
    (5520000, 0x8c9b1797L, 9957000),
    (9956999, 0x8c9b1797L, 9957000),
    (9957000, 0x3a6b00d7L, 0),
    (9957001, 0x3a6b00d7L, 0)
  ).map { case (h, x, n) => (BigInt(h), x, BigInt(n)) }

  private val etcHeads: Seq[BigInt] =
    Seq(0, 1149999, 1150000, 2500000, 3000000, 5000000, 5900000, 8772000, 9573000, 10500839, 11700000, 13189133,
      14525000, 19249999, 19250000, 22000000).map(BigInt(_))
  private val mordorHeads: Seq[BigInt] =
    Seq(0, 301243, 999983, 2520000, 3985893, 5520000, 9956999, 9957000, 15000000).map(BigInt(_))

  /** go-rlp encoding of core-geth's enrEntry{ForkID{Hash [4]byte, Next uint64}}, built by hand. */
  private def coreGethEntry(hash: Long, next: BigInt): Array[Byte] =
    val h = Array(
      ((hash >> 24) & 0xff).toByte,
      ((hash >> 16) & 0xff).toByte,
      ((hash >> 8) & 0xff).toByte,
      (hash & 0xff).toByte
    )
    val n = if next == 0 then Array.empty[Byte] else next.toByteArray.dropWhile(_ == 0)
    encode(RLPList(RLPList(RLPValue(h), RLPValue(n))))

  private def enr(entry: Array[Byte]): EthereumNodeRecord =
    EthereumNodeRecord(dummySig, 0L, ethKey -> ByteVector(entry))

  private def tag(genesis: ByteString, conf: BlockchainConfig, head: BigInt) =
    new ForkIdTag(() => genesis, () => 0L, conf, () => head)

  /** go-rlp's strict view of an eth entry: [[4-byte string, canonical uint64], ...tail]. */
  private def strictCoreGethLoad(bytes: Array[Byte]): (Long, BigInt) = rawDecode(bytes) match
    case RLPList(RLPList(RLPValue(h), RLPValue(n)), _*) if h.length == 4 && n.length <= 8 && (n.isEmpty || n(0) != 0) =>
      (BigInt(1, h).toLong, if n.isEmpty then BigInt(0) else BigInt(1, n))
    case other => fail(s"core-geth could not Load this eth entry: $other")

  private def validate(genesis: ByteString, conf: BlockchainConfig, head: BigInt, id: ForkId) =
    import ForkIdValidator.syncIoLogger
    ForkIdValidator.validatePeer[SyncIO](genesis, 0L, conf)(head, id).unsafeRunSync()

  "fukuii's ETC and Mordor fork IDs" should {
    "equal core-geth's vectors (hash always; next except fukuii's Olympia placeholder past the last fork)" in {
      for (head, hash, next) <- etcVectors do
        withClue(s"etc head=$head: ") {
          ForkId.create(etcGenesis, 0L, etcConf)(head) shouldBe ForkId(hash, Some(if next == 0 then Olympia else next))
        }
      for (head, hash, next) <- mordorVectors do
        withClue(s"mordor head=$head: ") {
          ForkId.create(mordorGenesis, 0L, mordorConf)(head) shouldBe
            ForkId(hash, Some(if next == 0 then Olympia else next))
        }
    }
  }

  "ForkIdTag.toFilter (discv4) on a core-geth ENR" should {
    "decode every core-geth ETC entry to the vector's fork ID" in {
      for (_, hash, next) <- etcVectors do
        ForkIdTag.decodeEthEntry(coreGethEntry(hash, next)) shouldBe ForkId(
          hash,
          if next == 0 then None else Some(next)
        )
    }
    "accept every core-geth ETC record at every local ETC height" in {
      for (rHead, hash, next) <- etcVectors; lHead <- etcHeads do
        withClue(s"remote=$rHead local=$lHead: ")(
          tag(etcGenesis, etcConf, lHead).toFilter(enr(coreGethEntry(hash, next))) shouldBe Right(())
        )
    }
    "accept every core-geth Mordor record at every local Mordor height" in {
      for (rHead, hash, next) <- mordorVectors; lHead <- mordorHeads do
        withClue(s"remote=$rHead local=$lHead: ")(
          tag(mordorGenesis, mordorConf, lHead).toFilter(enr(coreGethEntry(hash, next))) shouldBe Right(())
        )
    }
    "reject every Mordor record on ETC, and every ETC record on Mordor" in {
      for (_, hash, next) <- mordorVectors; lHead <- etcHeads do
        tag(etcGenesis, etcConf, lHead).toFilter(enr(coreGethEntry(hash, next))) shouldBe a[Left[?, ?]]
      for (_, hash, next) <- etcVectors; lHead <- mordorHeads do
        tag(mordorGenesis, mordorConf, lHead).toFilter(enr(coreGethEntry(hash, next))) shouldBe a[Left[?, ?]]
    }
    "reject ETH mainnet records past the shared genesis era, at the ETC tip" in {
      // go-ethereum mainnet: Homestead (same checksum as ETC's, but next = DAO 1920000), DAO, Petersburg, Cancun
      val ethIds = Seq(
        (0x97c2c34cL, BigInt(1920000)),
        (0x91d1f948L, BigInt(2463000)),
        (0x668db0afL, BigInt(9069000)),
        (0x9f3d2254L, BigInt(1746612311L))
      )
      for (hash, next) <- ethIds do
        tag(etcGenesis, etcConf, 22000000).toFilter(enr(coreGethEntry(hash, next))) shouldBe a[Left[?, ?]]
    }
  }

  "fukuii's own eth entry, as core-geth loads and validates it" should {
    "be loadable by go-rlp's strict rules and validate as Connect at every core-geth height (ETC)" in {
      for lHead <- etcHeads; cgHead <- etcHeads do
        val (_, value) = tag(etcGenesis, etcConf, lHead).toAttr.get
        val (hash, next) = strictCoreGethLoad(value.toArray)
        withClue(s"fukuii=$lHead core-geth=$cgHead: ") {
          validate(etcGenesis, etcConf, cgHead, ForkId(hash, if next == 0 then None else Some(next))) shouldBe Connect
        }
    }
    "be loadable and validate as Connect at every core-geth height (Mordor)" in {
      for lHead <- mordorHeads; cgHead <- mordorHeads do
        val (_, value) = tag(mordorGenesis, mordorConf, lHead).toAttr.get
        val (hash, next) = strictCoreGethLoad(value.toArray)
        validate(
          mordorGenesis,
          mordorConf,
          cgHead,
          ForkId(hash, if next == 0 then None else Some(next))
        ) shouldBe Connect
    }
  }

  "DnsDiscovery's EnrForkIdFilter" should {
    "agree with the discv4 filter on core-geth records (ETC tip)" in {
      val f = new DnsDiscovery.EnrForkIdFilter(() => etcGenesis, () => 0L, etcConf, () => BigInt(22000000))
      for (_, hash, next) <- etcVectors do f.accepts(ForkIdTag.decodeEthEntry(coreGethEntry(hash, next))) shouldBe true
      for (_, hash, next) <- mordorVectors do
        f.accepts(ForkIdTag.decodeEthEntry(coreGethEntry(hash, next))) shouldBe false
    }
  }
