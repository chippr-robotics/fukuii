package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.io.Source

import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Real mined Mordor (chain id 63) and ETC mainnet (chain id 61) headers, from `eth_getBlockByNumber`
  * (`src/test/resources/mordor/`, `src/test/resources/etc/`), and the shipped chain configs. Each line carries the
  * block's hash; [[load]] rebuilds the header from the other fields and requires that its hash is that one, and that
  * consecutive blocks link by parent hash, so a damaged fixture fails here rather than as a validation result.
  */
object PowHeaderFixtures:

  /** The shipped Mordor config (`fukuii.blockchains.mordor`): what block import validates Mordor headers with. */
  lazy val mordorConfig: BlockchainConfig =
    BlockchainConfig.fromRawConfig(ConfigFactory.load().getConfig("fukuii.blockchains.mordor"))

  /** The shipped ETC mainnet config (`fukuii.blockchains.etc`). */
  lazy val etcConfig: BlockchainConfig =
    BlockchainConfig.fromRawConfig(ConfigFactory.load().getConfig("fukuii.blockchains.etc"))

  /** Mordor blocks 0 (genesis) to 256. */
  lazy val fromGenesis: IndexedSeq[BlockHeader] = load("mordor/headers-0-256.txt")

  /** Mordor blocks 2,519,996 to 2,520,004: across ECIP-1099 (activation 2,520,000), where the epoch length doubles. */
  lazy val acrossEcip1099: IndexedSeq[BlockHeader] = load("mordor/headers-2519996-2520004.txt")

  /** Mordor blocks 17,000,000 to 17,000,004. */
  lazy val recent: IndexedSeq[BlockHeader] = load("mordor/headers-17000000-17000004.txt")

  /** Mordor: one block either side of Agharta, Phoenix, Magneto, Mystique and Spiral. */
  lazy val mordorForkBoundaries: IndexedSeq[BlockHeader] = load("mordor/headers-fork-boundaries.txt")

  /** ETC mainnet: one block either side of Homestead, EIP-150, EIP-155/160 and the difficulty-bomb pause, its
    * continuation and removal, Atlantis, Agharta, Phoenix, ECIP-1099, Magneto, Mystique, Spiral and a recent block; and
    * 1,919,999 to 1,920,011 (the DAO fork block and its extra-data range).
    */
  lazy val etcForkBoundaries: IndexedSeq[BlockHeader] = load("etc/headers-fork-boundaries.txt")

  /** `(parent, child)` for every pair of consecutive blocks in `headers`. */
  def pairs(headers: IndexedSeq[BlockHeader]): IndexedSeq[(BlockHeader, BlockHeader)] =
    (1 until headers.size).collect {
      case i if headers(i).number.value == headers(i - 1).number.value + 1 => (headers(i - 1), headers(i))
    }

  def load(resource: String): IndexedSeq[BlockHeader] =
    val source = Source.fromResource(resource)
    try
      val headers = source.getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map(parse).toIndexedSeq
      pairs(headers).foreach { case (parent, child) =>
        require(child.parentHash == parent.hash, s"$resource: block ${child.number} does not follow its predecessor")
      }
      headers
    finally source.close()

  private def parse(line: String): BlockHeader =
    val f = line.split(' ')
    require(f.length == 16, s"expected 16 fields, got ${f.length}: $line")
    def bytes(i: Int): ByteString = ByteString(Hex.decode(f(i).stripPrefix("0x")))
    def big(i: Int): BigInt = BigInt(f(i).stripPrefix("0x"), 16)
    val header = BlockHeader(
      parentHash = BlockHash(bytes(1)),
      ommersHash = BlockHash(bytes(2)),
      beneficiary = bytes(3),
      stateRoot = TrieRoot(bytes(4)),
      transactionsRoot = TrieRoot(bytes(5)),
      receiptsRoot = TrieRoot(bytes(6)),
      logsBloom = BloomFilter(bytes(7)),
      difficulty = Difficulty(big(8)),
      number = BlockNumber(big(9)),
      gasLimit = GasAmount(big(10)),
      gasUsed = GasAmount(big(11)),
      unixTimestamp = Timestamp(big(12).toLong),
      extraData = bytes(13),
      mixHash = BlockHash(bytes(14)),
      nonce = bytes(15)
    )
    require(
      header.hash.value == bytes(0),
      s"fixture header ${big(9)}: rebuilt hash ${header.hashAsHexString} != ${f(0)}"
    )
    header
