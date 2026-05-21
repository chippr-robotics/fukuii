package com.chipprbots.ethereum.forkid

import java.util.zip.CRC32

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.BigIntExtensionMethods._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils._
import com.chipprbots.ethereum.utils.Hex

import RLPImplicitConversions._
import RLPImplicits.given

case class ForkId(hash: BigInt, next: Option[BigInt]) {

  def nextDisplay: String = next match {
    case None    => "None"
    case Some(n) => ForkId.knownSentinels.get(n).fold(n.toString)(name => s"$n ($name)")
  }

  override def toString(): String =
    s"ForkId(0x${Hex.toHexString(hash.toUnsignedByteArray)}, next=${nextDisplay})"
}

object ForkId {

  val knownSentinels: Map[BigInt, String] = Map(
    BigInt("1000000000000000000") -> "Olympia"
  )

  def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt): ForkId =
    create(genesisHash, config)(head, 0L)

  /** EIP-2124 + EIP-6122: ForkId computation with both block number and timestamp. Block-number forks are compared
    * against `head`, timestamp forks against `headTimestamp`.
    */
  def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt, headTimestamp: Long): ForkId = {
    val crc = new CRC32()
    crc.update(genesisHash.asByteBuffer)

    val blockForks = gatherBlockForks(config)
    val timestampForks = gatherTimestampForks(config)

    // Process block forks first (sorted), then timestamp forks (sorted)
    val allForks = blockForks.map((_, false)) ++ timestampForks.map((_, true))

    val next = allForks.find { case (fork, isTimestamp) =>
      val passed = if (isTimestamp) fork <= BigInt(headTimestamp) else fork <= head
      if (passed) {
        crc.update(bigIntToBytes(fork, 8))
      }
      !passed
    }
    new ForkId(crc.getValue(), next.map(_._1))
  }

  // Long.MaxValue is the in-code fallback when a fork config key is missing
  // (BlockchainConfig.fromRawConfig and ForkBlockNumbers.Empty). It must be filtered
  // out of the EIP-2124 fork-id checksum chain as it is not a real fork block.
  // 10^18 is the genesis JSON "not yet scheduled" sentinel. Many ETH-specific fork
  // fields in the ETC config also sit at 10^18 (forks ETC never activated); filtering
  // all 10^18 values from forkBlockNumbers.all prevents those from polluting the fork
  // list. Olympia is re-appended explicitly below when olympiaBlockNumber itself is
  // the sentinel, ensuring ETC/Mordor advertise Olympia as the next fork.
  private val maxBlockSentinel: BigInt = BigInt(Long.MaxValue)
  private val olympiaSentinel: BigInt = BigInt("1000000000000000000")

  def gatherForks(config: BlockchainConfig): List[BigInt] =
    (gatherBlockForks(config) ++ gatherTimestampForks(config)).distinct.sorted

  def gatherBlockForks(config: BlockchainConfig): List[BigInt] = {
    val maybeDaoBlock: Option[BigInt] = config.daoForkConfig.flatMap { daoConf =>
      if (daoConf.includeOnForkIdList) Some(daoConf.forkBlockNumber)
      else None
    }
    val realForks = (maybeDaoBlock.toList ++ config.forkBlockNumbers.all)
      .filterNot(v => v == 0 || v == olympiaSentinel || v == maxBlockSentinel)
      .distinct
      .sorted
    // Advertise Olympia sentinel as the next fork when not yet scheduled
    val olympiaNext =
      if (config.forkBlockNumbers.olympiaBlockNumber == olympiaSentinel) List(olympiaSentinel) else Nil
    realForks ++ olympiaNext
  }

  /** EIP-6122: Timestamp-based forks for post-Merge chains. */
  def gatherTimestampForks(config: BlockchainConfig): List[BigInt] =
    List(
      config.forkTimestamps.shanghaiTimestamp.map(BigInt(_)),
      config.forkTimestamps.cancunTimestamp.map(BigInt(_)),
      config.forkTimestamps.pragueTimestamp.map(BigInt(_)),
      config.forkTimestamps.osakaTimestamp.map(BigInt(_)),
      config.forkTimestamps.bpo1Timestamp.map(BigInt(_)),
      config.forkTimestamps.bpo2Timestamp.map(BigInt(_))
    ).flatten.filterNot(_ == 0).distinct.sorted

  implicit class ForkIdEnc(forkId: ForkId) extends RLPSerializable {

    import com.chipprbots.ethereum.utils.ByteUtils._
    override def toRLPEncodable: RLPEncodeable = {
      val hash: Array[Byte] = bigIntToBytes(forkId.hash, 4).takeRight(4)
      val next: Array[Byte] = bigIntToUnsignedByteArray(forkId.next.getOrElse(BigInt(0))).takeRight(8)
      RLPList(hash, next)
    }

  }

  implicit val forkIdEnc: RLPDecoder[ForkId] = new RLPDecoder[ForkId] {

    def decode(rlp: RLPEncodeable): ForkId = rlp match {
      case RLPList(hash, next) =>
        val i = bigIntFromEncodeable(next)
        ForkId(bigIntFromEncodeable(hash), if (i == 0) None else Some(i))
      case _ => throw new RuntimeException("Error when decoding ForkId")
    }
  }
}
