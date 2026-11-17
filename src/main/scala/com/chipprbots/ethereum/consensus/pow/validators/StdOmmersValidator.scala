package com.chipprbots.ethereum.consensus.pow.validators

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.OmmerAncestor
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError.*
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.db.storage.pathstate.AncestorNotStored
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.BlockchainConfig

class StdOmmersValidator(blockHeaderValidator: BlockHeaderValidator) extends OmmersValidator:

  val OmmerGenerationLimit: Int = 6 // Stated on section 11.1, eq. (143) of the YP
  val OmmerSizeLimit: Int = 2

  /** How many ancestors' bodies the used-before rule reads, at most: see [[collectOmmersFromAncestors]]. A test reads
    * all six, the rule before B-302, to pin that the verdicts are the same.
    */
  protected def usedBeforeDepth: Int = OmmerGenerationLimit - 1

  /** This method allows validating the ommers of a Block. It performs the following validations (stated on section 11.1
    * of the YP):
    *   - OmmersValidator.validateOmmersLength
    *   - OmmersValidator.validateOmmersHeaders
    *   - OmmersValidator.validateOmmersAncestors
    * It also includes validations mentioned in the white paper (https://github.com/ethereum/wiki/wiki/White-Paper) and
    * implemented in the different ETC clients:
    *   - OmmersValidator.validateOmmersNotUsed
    *   - OmmersValidator.validateDuplicatedOmmers
    *
    * @param parentHash
    *   the hash of the parent of the block to which the ommers belong
    * @param blockNumber
    *   the number of the block to which the ommers belong
    * @param ommers
    *   the list of ommers to validate
    * @param getBlockHeaderByHash
    *   function to obtain an ancestor block header by hash
    * @param getNBlocksBack
    *   function to obtain N blocks including one given by hash and its N-1 ancestors
    *
    * @return
    *   [[com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid]] if valid, an
    *   [[com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError]] otherwise
    */
  def validate(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  )(implicit blockchainConfig: BlockchainConfig): Either[OmmersError, OmmersValid] =
    if ommers.isEmpty then Right(OmmersValid)
    else
      for
        _ <- validateOmmersLength(ommers)
        _ <- validateDuplicatedOmmers(ommers)
        _ <- validateOmmersHeaders(ommers, getBlockHeaderByHash)
        _ <- validateOmmersAncestors(parentHash, blockNumber, ommers, getNBlocksBack)
        _ <- validateOmmersNotUsed(parentHash, blockNumber, ommers, getNBlocksBack)
      yield OmmersValid

  /** Validates ommers length based on validations stated in section 11.1 of the YP
    *
    * @param ommers
    *   the list of ommers to validate
    *
    * @return
    *   [[OmmersValidator.OmmersValid]] if valid, an [[OmmersValidator.OmmersError.OmmersLengthError]] otherwise
    */
  private def validateOmmersLength(ommers: Seq[BlockHeader]): Either[OmmersError, OmmersValid] =
    if ommers.length <= OmmerSizeLimit then Right(OmmersValid)
    else Left(OmmersLengthError)

  /** Validates that each ommer's header is valid based on validations stated in section 11.1 of the YP
    *
    * @param ommers
    *   the list of ommers to validate
    * @param getBlockParentsHeaderByHash
    *   function to obtain ommers' parents
    * @return
    *   [[OmmersValidator.OmmersValid]] if valid, an [[OmmersValidator.OmmersError.OmmersHeaderError]] otherwise
    */
  private def validateOmmersHeaders(
      ommers: Seq[BlockHeader],
      getBlockParentsHeaderByHash: GetBlockHeaderByHash
  )(implicit blockchainConfig: BlockchainConfig): Either[OmmersError, OmmersValid] =
    val validationsResult: Seq[Either[BlockHeaderError, BlockHeaderValid]] =
      ommers.map(blockHeaderValidator.validate(_, getBlockParentsHeaderByHash))

    if validationsResult.forall(_.isRight) then Right(OmmersValid)
    else
      val errors = validationsResult.collect { case Left(error) => error }.toList
      Left(OmmersHeaderError(errors))

  /** Validates that each ommer is not too old and that it is a sibling as one of the current block's ancestors based on
    * validations stated in section 11.1 of the YP
    *
    * @param parentHash
    *   the hash of the parent of the block to which the ommers belong
    * @param blockNumber
    *   the number of the block to which the ommers belong
    * @param ommers
    *   the list of ommers to validate
    * @param getNBlocksBack
    *   from where the ommers' parents will be obtained
    * @return
    *   [[OmmersValidator.OmmersValid]] if valid, an [[OmmersValidator.OmmersError.OmmerIsAncestorError]] or
    *   [[OmmersValidator.OmmersError.OmmerParentIsNotAncestorError]] otherwise
    */
  private[validators] def validateOmmersAncestors(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      getNBlocksBack: GetNBlocksBack
  ): Either[OmmersError, OmmersValid] =

    val ancestors = collectAncestors(parentHash, blockNumber, getNBlocksBack)
    lazy val ommersHashes: Seq[BlockHash] = ommers.map(_.hash)
    lazy val ommersThatAreAncestors: Seq[BlockHash] = ancestors.map(_.hash).intersect(ommersHashes)

    lazy val ancestorsParents: Seq[BlockHash] = ancestors.map(_.parentHash)
    lazy val ommersParentsHashes: Seq[BlockHash] = ommers.map(_.parentHash)

    // parent not an ancestor or is too old (we only compare up to 6 previous ancestors)
    lazy val ommersParentsAreAllAncestors: Boolean = ommersParentsHashes.forall(ancestorsParents.contains)

    if ommersThatAreAncestors.nonEmpty then Left(OmmerIsAncestorError)
    else if !ommersParentsAreAllAncestors then Left(OmmerParentIsNotAncestorError)
    else Right(OmmersValid)

  /** Validates that each ommer was not previously used based on validations stated in the white paper
    * (https://github.com/ethereum/wiki/wiki/White-Paper)
    *
    * @param parentHash
    *   the hash of the parent of the block to which the ommers belong
    * @param blockNumber
    *   the number of the block to which the ommers belong
    * @param ommers
    *   the list of ommers to validate
    * @param getNBlocksBack
    *   from where the ommers' parents will be obtained
    * @return
    *   [[OmmersValidator.OmmersValid]] if valid, an [[OmmersValidator.OmmersError.OmmersUsedBeforeError]] otherwise
    */
  private def validateOmmersNotUsed(
      parentHash: ByteString,
      blockNumber: BigInt,
      ommers: Seq[BlockHeader],
      getNBlocksBack: GetNBlocksBack
  ): Either[OmmersError, OmmersValid] =

    val ommersFromAncestors = collectOmmersFromAncestors(parentHash, blockNumber, getNBlocksBack)

    if ommers.intersect(ommersFromAncestors).isEmpty then Right(OmmersValid)
    else Left(OmmersUsedBeforeError)

  /** Validates that there are no duplicated ommers based on validations stated in the white paper
    * (https://github.com/ethereum/wiki/wiki/White-Paper)
    *
    * @param ommers
    *   the list of ommers to validate
    * @return
    *   [[OmmersValidator.OmmersValid]] if valid, an [[OmmersValidator.OmmersError.OmmersDuplicatedError]] otherwise
    */
  private def validateDuplicatedOmmers(ommers: Seq[BlockHeader]): Either[OmmersError, OmmersValid] =
    if ommers.distinct.length == ommers.length then Right(OmmersValid)
    else Left(OmmersDuplicatedError)

  private def collectAncestors(
      parentHash: ByteString,
      blockNumber: BigInt,
      getNBlocksBack: GetNBlocksBack
  ): Seq[BlockHeader] =
    ancestorsOf(parentHash, blockNumber, getNBlocksBack).map(_.header)

  /** The ommers the ancestors used that an ommer of this block could be (B-302, forge W3). An ommer of block `N` has
    * its parent among `N - 2` down to `N - 7`, so it is of a height from `N - 6` to `N - 1`; an ancestor `A` used
    * ommers of heights `A - 6` to `A - 1`. Only the ancestors from `N - 1` down to `N - 5` can have used one of a
    * height this block's could have: their bodies are read, where they have ommers at all, and the sixth's is not. An
    * ommer is a whole header, equal only to itself, so the verdicts are the ones a read of all six bodies gives.
    */
  private def collectOmmersFromAncestors(
      parentHash: ByteString,
      blockNumber: BigInt,
      getNBlocksBack: GetNBlocksBack
  ): Seq[BlockHeader] =
    val read = ancestorsOf(parentHash, blockNumber, getNBlocksBack).filter(
      _.header.number.value >= blockNumber - usedBeforeDepth
    )
    read.flatMap { ancestor =>
      ancestor.ommers.getOrElse(
        throw new AncestorNotStored(
          read.size,
          read.count(_.ommers.isDefined),
          s"block $blockNumber cannot be checked until the body of block ${ancestor.header.number}, which has " +
            "ommers, is stored"
        )
      )
    }

  /** The blocks the ommers are checked against: the parent and the blocks before it, six of them or as many as there
    * are down to the genesis, by their headers; the bodies are read only where the rules need them
    * ([[collectOmmersFromAncestors]]). '''All of them, or no verdict''': with fewer, an ommer whose parent is one of
    * the missing blocks reads as `OmmerParentIsNotAncestorError` and one a missing block used reads as new, so a valid
    * block would be refused and an invalid one accepted. That is this node's own gap (the bodies below a SNAP pivot
    * arrive after the hand-off), never the block's: it is thrown as a [[AncestorNotStored]], which an import takes for
    * a fault of this node and tries again, in a batch and for a single block, and a block builder for a reason to take
    * no ommers (B-283, forge).
    */
  @throws[AncestorNotStored]("when this node does not hold the headers of all of the ancestors")
  private def ancestorsOf(
      parentHash: ByteString,
      blockNumber: BigInt,
      getNBlocksBack: GetNBlocksBack
  ): Seq[OmmerAncestor] =
    val wanted = blockNumber.min(OmmerGenerationLimit).toInt
    val ancestors = getNBlocksBack(parentHash, wanted)
    if ancestors.size < wanted then
      throw new AncestorNotStored(
        wanted,
        ancestors.size,
        s"block $blockNumber cannot be checked until the headers of the blocks below it are stored"
      )
    ancestors
