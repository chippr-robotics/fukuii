package com.chipprbots.ethereum

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError.OmmersHeaderError
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderNumberError
import com.chipprbots.ethereum.consensus.validators._
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockError
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockTransactionsHashError
import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator.BlockValid
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.handshaker.ConnectedState
import com.chipprbots.ethereum.network.handshaker.DisconnectedState
import com.chipprbots.ethereum.network.handshaker.Handshaker
import com.chipprbots.ethereum.network.handshaker.HandshakerState
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm._

object Mocks {
  private val defaultProgramResult: PC => PR = context =>
    ProgramResult(
      returnData = ByteString.empty,
      gasRemaining = 1000000 - 25000,
      world = context.world,
      addressesToDelete = Set.empty,
      logs = Nil,
      internalTxs = Nil,
      gasRefund = 20000,
      error = None,
      Set.empty,
      Set.empty
    )

  class MockVM(runFn: PC => PR = defaultProgramResult) extends VMImpl {
    override def run(context: PC): PR =
      runFn(context)
  }

  class MockValidatorsFailingOnBlockBodies extends MockValidatorsAlwaysSucceed {

    override val blockValidator: BlockValidator = new BlockValidator {
      override def validateBlockAndReceipts(
          blockHeader: BlockHeader,
          receipts: Seq[Receipt]
      ): Either[BlockError, BlockValid] = Right(BlockValid)
      override def validateHeaderAndBody(
          blockHeader: BlockHeader,
          blockBody: BlockBody
      ): Either[BlockError, BlockValid] = Left(
        BlockTransactionsHashError
      )
    }
  }

  class MockValidatorsAlwaysSucceed extends ValidatorsExecutor {

    override val blockValidator: BlockValidator = new BlockValidator {
      override def validateBlockAndReceipts(
          blockHeader: BlockHeader,
          receipts: Seq[Receipt]
      ): Either[BlockError, BlockValid] = Right(BlockValid)
      override def validateHeaderAndBody(
          blockHeader: BlockHeader,
          blockBody: BlockBody
      ): Either[BlockError, BlockValid] = Right(BlockValid)
    }

    override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator {
      override def validate(
          blockHeader: BlockHeader,
          getBlockHeaderByHash: GetBlockHeaderByHash
      )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = Right(
        BlockHeaderValid
      )

      override def validateHeaderOnly(
          blockHeader: BlockHeader
      )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = Right(
        BlockHeaderValid
      )
    }

    override val ommersValidator: OmmersValidator = new OmmersValidator {
      def validate(
          parentHash: ByteString,
          blockNumber: BigInt,
          ommers: Seq[BlockHeader],
          getBlockByHash: GetBlockHeaderByHash,
          getNBlocksBack: GetNBlocksBack
      )(implicit blockchainConfig: BlockchainConfig): Either[OmmersValidator.OmmersError, OmmersValid] = Right(
        OmmersValid
      )
    }

    override val signedTransactionValidator: SignedTransactionValidator =
      new SignedTransactionValidator {
        def validate(
            stx: SignedTransaction,
            senderAccount: Account,
            blockHeader: BlockHeader,
            upfrontGasCost: UInt256,
            accumGasUsed: BigInt
        )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
          Right(SignedTransactionValid)
      }
  }

  object MockValidatorsAlwaysSucceed extends MockValidatorsAlwaysSucceed

  object MockValidatorsAlwaysFail extends ValidatorsExecutor {
    override val signedTransactionValidator: SignedTransactionValidator =
      new SignedTransactionValidator {
        def validate(
            stx: SignedTransaction,
            senderAccount: Account,
            blockHeader: BlockHeader,
            upfrontGasCost: UInt256,
            accumGasUsed: BigInt
        )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
          Left(SignedTransactionError.TransactionSignatureError)
      }

    override val blockHeaderValidator: BlockHeaderValidator = new BlockHeaderValidator {
      override def validate(
          blockHeader: BlockHeader,
          getBlockHeaderByHash: GetBlockHeaderByHash
      )(implicit blockchainConfig: BlockchainConfig): Either[BlockHeaderError, BlockHeaderValid] = Left(
        HeaderNumberError
      )

      override def validateHeaderOnly(blockHeader: BlockHeader)(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockHeaderError, BlockHeaderValid] = Left(
        HeaderNumberError
      )
    }

    override val ommersValidator: OmmersValidator = new OmmersValidator {
      def validate(
          parentHash: ByteString,
          blockNumber: BigInt,
          ommers: Seq[BlockHeader],
          getBlockByHash: GetBlockHeaderByHash,
          getNBlocksBack: GetNBlocksBack
      )(implicit blockchainConfig: BlockchainConfig): Either[OmmersValidator.OmmersError, OmmersValid] =
        Left(OmmersHeaderError(List(HeaderDifficultyError)))
    }

    override val blockValidator: BlockValidator = new BlockValidator {
      override def validateHeaderAndBody(
          blockHeader: BlockHeader,
          blockBody: BlockBody
      ): Either[BlockError, BlockValid] = Left(
        BlockTransactionsHashError
      )
      override def validateBlockAndReceipts(
          blockHeader: BlockHeader,
          receipts: Seq[Receipt]
      ): Either[BlockError, BlockValid] = Left(
        BlockTransactionsHashError
      )
    }
  }

  class MockValidatorsFailOnSpecificBlockNumber(number: BigInt) extends MockValidatorsAlwaysSucceed {
    override val blockValidator: BlockValidator = new BlockValidator {
      override def validateHeaderAndBody(
          blockHeader: BlockHeader,
          blockBody: BlockBody
      ): Either[BlockError, BlockValid] =
        if (blockHeader.number == number) Left(BlockTransactionsHashError) else Right(BlockValid)
      override def validateBlockAndReceipts(
          blockHeader: BlockHeader,
          receipts: Seq[Receipt]
      ): Either[BlockError, BlockValid] =
        if (blockHeader.number == number) Left(BlockTransactionsHashError) else Right(BlockValid)
    }

    override def validateBlockAfterExecution(
        block: Block,
        stateRootHash: ByteString,
        receipts: Seq[Receipt],
        gasUsed: BigInt
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] =
      if (block.header.number == number) Left(ValidationAfterExecError("")) else Right(BlockExecutionSuccess)
  }

  case class MockHandshakerAlwaysSucceeds(
      initialStatus: RemoteStatus,
      currentMaxBlockNumber: BigInt,
      forkAccepted: Boolean
  ) extends Handshaker[PeerInfo] {
    override val handshakerState: HandshakerState[PeerInfo] =
      ConnectedState(
        PeerInfo(
          initialStatus,
          initialStatus.chainWeight,
          forkAccepted,
          currentMaxBlockNumber,
          initialStatus.bestHash
        )
      )
    override def copy(handshakerState: HandshakerState[PeerInfo]): Handshaker[PeerInfo] = this
  }

  case class MockHandshakerAlwaysFails(reason: Int) extends Handshaker[PeerInfo] {
    override val handshakerState: HandshakerState[PeerInfo] = DisconnectedState(reason)

    override def copy(handshakerState: HandshakerState[PeerInfo]): Handshaker[PeerInfo] = this
  }

}
