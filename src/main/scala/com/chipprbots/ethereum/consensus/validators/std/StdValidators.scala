package com.chipprbots.ethereum.consensus.validators.std

import org.apache.pekko.util.ByteString

import scala.annotation.unused

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.validators.*
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockExecutionSuccess
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Implements validators that adhere to the original
  * [[com.chipprbots.ethereum.consensus.validators.Validators Validators]] interface.
  *
  * @see
  *   [[com.chipprbots.ethereum.consensus.pow.validators.StdValidatorsExecutor StdEthashValidators]] for the
  *   PoW-specific counterpart.
  */
final class StdValidators(
    val blockValidator: BlockValidator,
    val blockHeaderValidator: BlockHeaderValidator,
    val signedTransactionValidator: SignedTransactionValidator
) extends Validators:

  def validateBlockBeforeExecution(
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      getNBlocksBack: GetNBlocksBack
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =
    StdValidators.validateBlockBeforeExecution(
      self = this,
      block = block,
      getBlockHeaderByHash = getBlockHeaderByHash,
      getNBlocksBack = getNBlocksBack
    )

  def validateBlockAfterExecution(
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
    StdValidators.validateBlockAfterExecution(
      self = this,
      block = block,
      stateRootHash = stateRootHash,
      receipts = receipts,
      gasUsed = gasUsed
    )

object StdValidators:
  def validateBlockBeforeExecution(
      self: Validators,
      block: Block,
      getBlockHeaderByHash: GetBlockHeaderByHash,
      @unused getNBlocksBack: GetNBlocksBack
  )(implicit blockchainConfig: BlockchainConfig): Either[ValidationBeforeExecError, BlockExecutionSuccess] =

    val header = block.header
    val body = block.body

    val result = for
      _ <- self.blockHeaderValidator.validate(header, getBlockHeaderByHash)
      _ <- self.blockValidator.validateHeaderAndBody(header, body)
    yield BlockExecutionSuccess

    result.left.map(ValidationBeforeExecError.apply)

  /** Appended to the gas-used mismatch message when the block's own receipts PROVE the header's `gasUsed` is wrong.
    *
    * Consumed by `InvalidChainReporter.provesConsensusInvalid`. It must never contain a substring that some other
    * consumer matches on (`"root mismatch"`, `"Proof root"`, `"Missing root node"`, `"Missing proof for empty account
    * range"`, `"Peer disconnected"`, `"ATTR:"`, `"fork-id mismatch"`), and the message it is appended to keeps `"Block
    * has invalid gas used"` as a contiguous prefix so `BlockImporter`'s gas-used arm matches exactly as before.
    */
  val HeaderGasContradictsReceiptsMarker: String = "header gasUsed contradicts the receipts it commits to"

  def validateBlockAfterExecution(
      self: Validators,
      block: Block,
      stateRootHash: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =

    val header = block.header
    val blockAndReceiptsValidation = self.blockValidator.validateBlockAndReceipts(header, receipts)

    // DO NOT REORDER THE gasUsed AND stateRoot CHECKS BELOW. The gasUsed check MUST come first and MUST return
    // early. `InvalidChainReporter.provesConsensusInvalid` reports a state-root mismatch to the Engine API as a
    // proven consensus failure, and the ONLY reason that is safe is this ordering: reaching the state-root branch
    // proves gasUsed already matched, which proves execution was complete. A node missing contract code executes a
    // contract call as a plain transfer (`InMemoryWorldStateProxy.getCode` returns ByteString.empty rather than
    // throwing), which perturbs gas and would trip the FIRST branch — where the mismatch stays ambiguous and is
    // disambiguated by `BlockImporter.findMissingContractCode`. Swap these two and a partially-synced node starts
    // permanently marking honest blocks invalid through the state-root branch, and refuses its own canonical chain.
    if header.gasUsed.value != gasUsed then
      // Is this mismatch PROVEN, independent of how complete our local state is? Yes, when the header contradicts
      // the receipts it commits to. Yellow Paper invariant, every fork Frontier→Prague and every ETC fork: the
      // header's gasUsed equals the final receipt's cumulativeGasUsed (H_g = ℓ(R)_u). If our computed receipts hash
      // to header.receiptsRoot, then they ARE the receipts the header commits to, so when their final cumulative gas
      // differs from header.gasUsed the block is self-inconsistent and invalid whatever state we hold. This reasons
      // purely from that invariant; it does not depend on missing code perturbing receipts. When the root does NOT
      // match — the missing-code case among others — nothing is proven and the message is unchanged.
      //
      // Deliberately NOT `gasUsed` (BlockResult.gasUsed is max(executionGasUsed, stateGasUsed)); the receipts are
      // read directly. Gated OFF at Amsterdam and later: under EIP-8037/7778 header gasUsed is the max of two
      // dimensions while receipts carry the sum (hive devp2p block 41: header 183,600 vs receipt 326,947), so there
      // the receipts do not commit to header gasUsed and a root match proves nothing.
      //
      // `blockAndReceiptsValidation` is computed eagerly above, so this adds no work. It checks the receipts root
      // first, so anything other than Left(BlockReceiptsHashError) means the root matched.
      val base = s"Block has invalid gas used, expected ${header.gasUsed} but got $gasUsed"
      val provenByOwnReceipts =
        !blockchainConfig.isAmsterdamTimestamp(header.unixTimestamp) &&
          blockAndReceiptsValidation != Left(StdBlockValidator.BlockReceiptsHashError) &&
          header.gasUsed.value != receipts.lastOption.fold(BigInt(0))(_.cumulativeGasUsed)
      Left(
        ValidationAfterExecError(if provenByOwnReceipts then s"$base; $HeaderGasContradictsReceiptsMarker" else base)
      )
    else if header.stateRoot.value != stateRootHash then
      Left(ValidationAfterExecError(s"Block has invalid state root hash, expected ${Hex
          .toHexString(header.stateRoot.toArray)} but got ${Hex.toHexString(stateRootHash.toArray)}"))
    else
      blockAndReceiptsValidation match
        case Left(err) => Left(ValidationAfterExecError(err.toString))
        case _         => Right(BlockExecutionSuccess)
