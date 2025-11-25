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

    // EIP-2124 ForkId calculation with practical workaround for unsynced nodes
    // 
    // Problem: When at block 0, strictly following EIP-2124 reports genesis ForkId
    // (e.g., 0xfc64ec04 for ETC). However, synced peers running core-geth and other
    // clients often reject this as "incompatible" because they expect nodes to be
    // at or near the current chain head.
    //
    // Solution: When head is 0, report the latest fork's ForkId to match what
    // synced peers expect. This is a pragmatic deviation from strict EIP-2124
    // that allows unsynced nodes to establish peer connections.
    //
    // Once the node advances past block 0, normal ForkId calculation resumes.
    if (head == 0 && forks.nonEmpty) {
      // Calculate ForkId for the latest known fork
      forks.foreach { fork =>
        crc.update(bigIntToBytes(fork, 8))
      }
      new ForkId(crc.getValue(), None)
    } else {
      val next = forks.find { fork =>
        if (fork <= head) {
          crc.update(bigIntToBytes(fork, 8))
        }
        fork > head
      }
      new ForkId(crc.getValue(), next)
    }
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
