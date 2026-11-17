package com.chipprbots.ethereum.blockchain.sync.snap

import java.io.File

import org.apache.pekko.util.ByteString

import scala.io.Source

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BlockchainsConfig

/** Real Ethereum mainnet, Sepolia and Ephemery headers (`src/test/resources/{eth,sepolia,ephemery/ephemery-165}`), from
  * `eth_getBlockByNumber`, and the chain configs block import validates them with. Each line carries the block's hash
  * and the header's RLP; [[load]] decodes the RLP and requires that its hash is that one, and that consecutive blocks
  * link by parent hash, so a damaged fixture fails here rather than as a validation result.
  */
object PosHeaderFixtures:

  /** The shipped chain configs, assembled as at runtime. */
  private lazy val shippedRoot =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  /** The shipped Ethereum mainnet config (`fukuii.blockchains.eth`). */
  lazy val mainnetConfig: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.eth"))

  /** The shipped Sepolia config (`fukuii.blockchains.sepolia`). */
  lazy val sepoliaConfig: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.sepolia"))

  /** Ephemery iteration 165 as an operator runs it: the config `scripts/ephemery/fetch-genesis.sh` generates, through
    * `custom-chains-dir` (terminal total difficulty 0, every fork through Amsterdam at genesis).
    */
  lazy val ephemery165Config: BlockchainConfig =
    val dir = new File(getClass.getResource("/ephemery/ephemery-165").toURI).getAbsolutePath
    BlockchainsConfig(
      ConfigFactory
        .empty()
        .withValue("network", ConfigValueFactory.fromAnyRef("ephemery"))
        .withValue("custom-chains-dir", ConfigValueFactory.fromAnyRef(dir))
    ).blockchainConfig

  /** Mainnet: 15,537,392 to 15,537,395 (the merge), and the first block of Shanghai, Cancun, Prague, Osaka, BPO1 and
    * BPO2 with its parent.
    */
  lazy val mainnet: IndexedSeq[BlockHeader] = load("eth/headers-merge-and-forks.txt")

  /** Sepolia: 1,450,407 to 1,450,410 (the merge), and the first block of Shanghai, Cancun, Prague, Osaka, BPO1, BPO2
    * and Amsterdam with its parent.
    */
  lazy val sepolia: IndexedSeq[BlockHeader] = load("sepolia/headers-merge-and-forks.txt")

  /** Sepolia 11,860,000 to 11,860,069, consecutive (Amsterdam era). */
  lazy val sepoliaRun: IndexedSeq[BlockHeader] = load("sepolia/headers-11860000-11860069.txt")

  /** Ephemery iteration 165, blocks 0 (genesis: difficulty 1, nonce 0x1234) to 3. */
  lazy val ephemery165: IndexedSeq[BlockHeader] = load("ephemery/ephemery-165/headers-0-3.txt")

  /** Mainnet's terminal proof-of-work block and its total difficulty (eth.blockscout.com). */
  val MainnetTerminalBlock: BigInt = BigInt(15_537_393)
  val MainnetTerminalTd: BigInt = BigInt("58750003716598352816469")

  /** Sepolia's terminal proof-of-work block and its total difficulty (eth-sepolia.blockscout.com). */
  val SepoliaTerminalBlock: BigInt = BigInt(1_450_408)
  val SepoliaTerminalTd: BigInt = BigInt("17000018015853232")

  /** The header numbered `number` in `headers`. */
  def at(headers: IndexedSeq[BlockHeader], number: BigInt): BlockHeader =
    headers.find(_.number.value == number).getOrElse(throw new NoSuchElementException(s"no fixture block $number"))

  def load(resource: String): IndexedSeq[BlockHeader] =
    val source = Source.fromResource(resource)
    try
      val headers = source.getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).map(parse).toIndexedSeq
      PowHeaderFixtures.pairs(headers).foreach { case (parent, child) =>
        require(child.parentHash == parent.hash, s"$resource: block ${child.number} does not follow its predecessor")
      }
      headers
    finally source.close()

  private def parse(line: String): BlockHeader =
    val f = line.split(' ')
    require(f.length == 2, s"expected a hash and an RLP, got ${f.length} fields")
    val header = Hex.decode(f(1).stripPrefix("0x")).toBlockHeader
    require(
      header.hash.value == ByteString(Hex.decode(f(0).stripPrefix("0x"))),
      s"fixture header ${header.number}: decoded hash ${header.hashAsHexString} != ${f(0)}"
    )
    header
