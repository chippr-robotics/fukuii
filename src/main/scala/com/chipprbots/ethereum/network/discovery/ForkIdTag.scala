package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.util.ByteString
import scodec.bits.ByteVector

import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId._
import com.chipprbots.ethereum.rlp._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.KeyValueTag

import scala.util.Try

/** ENR-based forkId filter (EIP-2124). Rejects cross-chain peers (Polygon, ETH mainnet, Linea)
 *  before TCP is dialed by reading the `eth` ENR key. Also advertises our chain's current forkId
 *  in our own ENR so compatible peers can pre-filter us.
 *
 *  No `eth` key in peer ENR → accept (pre-EIP-2124 node; ETH Status decides).
 *  Incompatible forkHash → reject (removed from routing table, TCP never dialed).
 *  The filter is conservative: when ambiguous (peer ahead or behind on same chain), accept and
 *  let the ETH Status handshake do the authoritative check.
 */
class ForkIdTag(
    genesisHash: () => ByteString,
    blockchainConfig: BlockchainConfig,
    currentBestBlock: () => BigInt
) extends KeyValueTag {

  private val ethKey: ByteVector = EthereumNodeRecord.Keys.key("eth")

  override def toAttr: Option[(ByteVector, ByteVector)] = {
    val forkId = ForkId.create(genesisHash(), blockchainConfig)(currentBestBlock())
    Some(ethKey -> ByteVector(encode(forkId.toRLPEncodable)))
  }

  override def toFilter: KeyValueTag.EnrFilter = { enr =>
    enr.content.attrs.get(ethKey) match {
      case None => Right(()) // no eth key — pre-EIP-2124 node, accept optimistically
      case Some(ethBytes) =>
        Try {
          val rlp = rawDecode(ethBytes.toArray)
          decode[ForkId](rlp)
        }.toEither.left.map(e => s"ENR eth key: cannot decode ForkId: ${e.getMessage}") match {
          case Left(err)           => Left(err)
          case Right(remoteForkId) => validateForkId(currentBestBlock(), remoteForkId)
        }
    }
  }

  private def validateForkId(currentBlock: BigInt, remote: ForkId): Either[String, Unit] = {
    val local = ForkId.create(genesisHash(), blockchainConfig)(currentBlock)
    if (local.hash == remote.hash) {
      Right(())
    } else {
      remote.next match {
        case Some(next) if next > currentBlock =>
          // Peer is ahead on same chain, announcing an upcoming fork — accept
          Right(())
        case _ =>
          local.next match {
            case Some(_) =>
              // We have a future fork; remote may be at an earlier state of our chain — accept, let TCP decide
              Right(())
            case None =>
              Left(
                s"Incompatible chain: local forkHash=0x${local.hash.toString(16)}, remote forkHash=0x${remote.hash.toString(16)}"
              )
          }
      }
    }
  }
}
