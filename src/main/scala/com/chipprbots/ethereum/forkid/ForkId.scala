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

  def create(genesisHash: ByteString, config: BlockchainConfig)(head: BigInt): ForkId = {
    val crc = new CRC32()
    crc.update(genesisHash.asByteBuffer)
    val forks = gatherForks(config)

    // WORKAROUND: When at block 0, report the latest known fork to match peer expectations.
    // While EIP-2124 technically requires reporting genesis fork at block 0, many peers
    // (including Core-Geth v1.12.20+) reject this as too old, preventing initial sync.
    // This matches core-geth's practical approach to enable initial peer connections.
    val effectiveHead = if (head == 0 && forks.nonEmpty) forks.last else head

    val next = forks.find { fork =>
      if (fork <= effectiveHead) {
        crc.update(bigIntToBytes(fork, 8))
      }
      fork > effectiveHead
    }
    new ForkId(crc.getValue(), next)
  }

  val noFork: BigInt = BigInt("1000000000000000000")

  def gatherForks(config: BlockchainConfig): List[BigInt] = {
    val maybeDaoBlock: Option[BigInt] = config.daoForkConfig.flatMap { daoConf =>
      if (daoConf.includeOnForkIdList) Some(daoConf.forkBlockNumber)
      else None
    }

    (maybeDaoBlock.toList ++ config.forkBlockNumbers.all)
      .filterNot(v => v == 0 || v == noFork)
      .distinct
      .sorted
  }

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
