package com.chipprbots.ethereum.consensus.pow.validators

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.OmmerAncestor
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.utils.BlockchainConfig

trait OmmersValidator:

  def validate(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      getBlockByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  )(implicit blockchainConfig: BlockchainConfig): Either[OmmersError, OmmersValid]

  def validate(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      blockchainReader: BlockchainReader
  )(implicit blockchainConfig: BlockchainConfig): Either[OmmersError, OmmersValid] =

    val getBlockHeaderByHash: ByteString => Option[BlockHeader] =
      (h: ByteString) => blockchainReader.getBlockHeaderByHash(BlockHash(h))
    val getNBlocksBack: GetNBlocksBack =
      (tailBlockHash, n) =>
        Iterator
          .iterate(blockchainReader.getBlockHeaderByHash(BlockHash(tailBlockHash)))(
            _.filter(_.number != BlockNumber.Zero) // avoid trying to fetch parent of genesis
              .flatMap(header => blockchainReader.getBlockHeaderByHash(header.parentHash))
          )
          // up to the first header that is not stored: a walk that skipped it would never end, since every block
          // after a missing one is missing too (B-283). Fewer than `n` is for the caller to judge.
          .takeWhile(_.isDefined)
          .take(n)
          .flatten
          // a body is read only where the header names ommers (B-302)
          .map(header => OmmerAncestor.of(header, blockchainReader.getBlockBodyByHash(header.hash)))
          .toList
          .reverse

    validate(parentHash, blockNumber, ommers, getBlockHeaderByHash, getNBlocksBack)

object OmmersValidator:
  sealed trait OmmersError

  object OmmersError:
    case object OmmersLengthError extends OmmersError
    case class OmmersHeaderError(errors: List[BlockHeaderError]) extends OmmersError
    case object OmmersUsedBeforeError extends OmmersError
    case object OmmerIsAncestorError extends OmmersError
    case object OmmerParentIsNotAncestorError extends OmmersError
    case object OmmersDuplicatedError extends OmmersError

  sealed trait OmmersValid
  case object OmmersValid extends OmmersValid
