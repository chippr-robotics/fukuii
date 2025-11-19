package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

/** Represents a bootstrap checkpoint - a trusted block at a known height that can be used as a starting point for
  * syncing without waiting for peers.
  *
  * @param blockNumber
  *   The block number of the checkpoint
  * @param blockHash
  *   The hash of the block at this checkpoint
  */
case class BootstrapCheckpoint(
    blockNumber: BigInt,
    blockHash: ByteString
)

object BootstrapCheckpoint {

  /** Parse a checkpoint from a configuration string in the format "blockNumber:blockHash"
    */
  def fromString(s: String): Option[BootstrapCheckpoint] =
    s.split(":") match {
      case Array(numberStr, hashStr) =>
        try {
          val number = BigInt(numberStr)
          val hash = ByteString(Hex.decode(hashStr.stripPrefix("0x")))
          Some(BootstrapCheckpoint(number, hash))
        } catch {
          case _: Exception => None
        }
      case _ => None
    }
}
