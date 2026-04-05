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
  override def toString(): String = s"ForkId(0x${Hex.toHexString(hash.toUnsignedByteArray)}, $next)"
}

object ForkId {

  def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt): ForkId =
    create(genesisHash, config)(head, 0L)

  /** EIP-2124 + EIP-6122: ForkId computation with both block number and timestamp.
    * Block-number forks are compared against `head`, timestamp forks against `headTimestamp`.
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

  val noFork: BigInt = BigInt("1000000000000000000")

  def gatherForks(config: BlockchainConfig): List[BigInt] =
    (gatherBlockForks(config) ++ gatherTimestampForks(config)).distinct.sorted

  def gatherBlockForks(config: BlockchainConfig): List[BigInt] = {
    val maybeDaoBlock: Option[BigInt] = config.daoForkConfig.flatMap { daoConf =>
      if (daoConf.includeOnForkIdList) Some(daoConf.forkBlockNumber)
      else None
    }
    (maybeDaoBlock.toList ++ config.forkBlockNumbers.all)
      .filterNot(v => v == 0 || v == noFork)
      .distinct
      .sorted
  }

  /** EIP-6122: Timestamp-based forks for post-Merge chains. */
  def gatherTimestampForks(config: BlockchainConfig): List[BigInt] =
    List(
      config.forkTimestamps.shanghaiTimestamp.map(BigInt(_)),
      config.forkTimestamps.cancunTimestamp.map(BigInt(_)),
      config.forkTimestamps.pragueTimestamp.map(BigInt(_))
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
