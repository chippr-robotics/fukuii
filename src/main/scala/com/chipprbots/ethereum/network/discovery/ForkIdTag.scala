package com.chipprbots.ethereum.network.discovery

import org.apache.pekko.util.ByteString

import cats.effect.SyncIO

import scala.util.Try

import com.chipprbots.scalanet.discovery.ethereum.EthereumNodeRecord
import com.chipprbots.scalanet.discovery.ethereum.KeyValueTag
import scodec.bits.ByteVector

import com.chipprbots.ethereum.forkid.ForkIdValidationResult.Connect
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.forkid.ForkId.*
import com.chipprbots.ethereum.forkid.ForkIdValidator
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** ENR-based forkId filter (EIP-2124). Rejects peers on incompatible chains before TCP is dialed by reading the `eth`
  * ENR key. Network-aware: derives fork ID from the runtime genesis hash and the selected network's fork schedule
  * (works correctly on ETC mainnet, Mordor, etc.).
  *
  * No `eth` key in peer ENR → accept (pre-EIP-2124 node; ETH Status decides). Incompatible forkHash → reject (removed
  * from routing table, TCP never dialed). Ambiguous cases (peer ahead or behind on same chain) → accept; ETH Status
  * does the authoritative check.
  *
  * Delegates to [[ForkIdValidator.validatePeer]] for the full three-pass EIP-2124 algorithm (same-state, remote-subset,
  * remote-superset) — consistent with the ETH Status handshaker.
  */
class ForkIdTag(
    genesisHash: () => ByteString,
    genesisTimestamp: () => Long,
    blockchainConfig: BlockchainConfig,
    currentBestBlock: () => BigInt
) extends KeyValueTag:

  private val ethKey: ByteVector = EthereumNodeRecord.Keys.key("eth")

  override def toAttr: Option[(ByteVector, ByteVector)] =
    val forkId = ForkId.create(genesisHash(), genesisTimestamp(), blockchainConfig)(currentBestBlock())
    Some(ethKey -> ByteVector(encode(forkId.toRLPEncodable)))

  override def toFilter: KeyValueTag.EnrFilter = enr =>
    enr.content.attrs.get(ethKey) match
      case None => Right(()) // no eth key — pre-EIP-2124 node, accept optimistically
      case Some(ethBytes) =>
        Try {
          val rlp = rawDecode(ethBytes.toArray)
          decode[ForkId](rlp)
        }.toEither.left.map(e => s"ENR eth key: cannot decode ForkId: ${e.getMessage}") match
          case Left(err) => Left(err)
          case Right(remoteForkId) =>
            import ForkIdValidator.syncIoLogger
            ForkIdValidator
              .validatePeer[SyncIO](genesisHash(), genesisTimestamp(), blockchainConfig)(
                currentBestBlock(),
                remoteForkId
              )
              .unsafeRunSync() match
              case Connect => Right(())
              case other   => Left(s"ENR fork ID incompatible ($other): $remoteForkId")
