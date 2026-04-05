package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.implicits._

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockExecutionError.MissingParentError
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.DaoForkConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.EvmConfig

class BlockExecution(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    blockValidation: BlockValidation
) extends Logger {

  /** Executes and validate a block
    *
    * @param alreadyValidated
    *   should we skip pre-execution validation (if the block has already been validated, eg. in the importBlock method)
    */
  def executeAndValidateBlock(
      block: Block,
      alreadyValidated: Boolean = false
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, Seq[Receipt]] = {
    val preExecValidationResult =
      if (alreadyValidated) Right(block) else blockValidation.validateBlockBeforeExecution(block)

    val blockExecResult =
      for {
        _ <- preExecValidationResult
        result <- executeBlock(block)
        _ <- blockValidation.validateBlockAfterExecution(
          block,
          result.worldState.stateRootHash,
          result.receipts,
          result.gasUsed
        )
      } yield result.receipts

    if (blockExecResult.isRight) {
      log.debug(s"Block ${block.header.number} (with hash: ${block.header.hashAsHexString}) executed correctly")
    }

    blockExecResult
  }

  /** Executes a block (executes transactions and pays rewards) */
  private def executeBlock(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] =
    try
      for {
        parentHeader <- blockchainReader
          .getBlockHeaderByHash(block.header.parentHash)
          .toRight(MissingParentError) // Should not never occur because validated earlier
        initialWorld = buildInitialWorld(block, parentHeader)
        execResult <- executeBlockTransactions(block, initialWorld)
        worldToPersist <- Either
          .catchOnly[MPTException](blockPreparator.payBlockReward(block, execResult.worldState))
          .leftMap(BlockExecutionError.MPTError.apply)
        // State root hash needs to be up-to-date for validateBlockAfterExecution
        worldPersisted = InMemoryWorldStateProxy.persistState(worldToPersist)
      } yield execResult.copy(worldState = worldPersisted)
    catch {
      case e: MPTException => Left(BlockExecutionError.MPTError(e))
    }

  protected def buildInitialWorld(block: Block, parentHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      evmCodeStorage = evmCodeStorage,
      blockchain.getBackingMptStorage(block.header.number),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      accountStartNonce = blockchainConfig.accountStartNonce,
      stateRootHash = parentHeader.stateRoot,
      noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

  /** This function runs transactions
    *
    * @param block
    *   the block with transactions to run
    */
  protected[ledger] def executeBlockTransactions(
      block: Block,
      initialWorld: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockResult] = {
    val blockHeaderNumber = block.header.number
    executeBlockTransactions(block, blockHeaderNumber, initialWorld)
  }

  protected def executeBlockTransactions(
      block: Block,
      blockHeaderNumber: BigInt,
      initialWorld: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError.TxsExecutionError, BlockResult] = {
    val worldAfterDao = blockchainConfig.daoForkConfig match {
      case Some(daoForkConfig) if daoForkConfig.isDaoForkBlock(blockHeaderNumber) =>
        drainDaoForkAccounts(initialWorld, daoForkConfig)
      case _ => initialWorld
    }

    // EIP-4788: Store parent beacon block root in system contract (post-Cancun)
    val worldAfterBeaconRoot = applyEip4788(block, worldAfterDao)

    // EIP-2935: Store parent block hash in history storage contract
    val inputWorld = applyEip2935(block, worldAfterBeaconRoot)

    val hashAsHexString = block.header.hashAsHexString
    val transactionList = block.body.transactionList
    log.debug(
      s"About to execute ${transactionList.size} txs from block $blockHeaderNumber (with hash: $hashAsHexString)"
    )
    val blockTxsExecResult = blockPreparator.executeTransactions(transactionList, inputWorld, block.header)
    blockTxsExecResult match {
      case Right(_) => log.debug(s"All txs from block $hashAsHexString were executed successfully")
      case Left(error) =>
        log.debug(s"Not all txs from block $hashAsHexString were executed correctly, due to ${error.reason}")
    }
    blockTxsExecResult
  }

  /** EIP-4788: Store the parent beacon block root in the beacon root system contract.
    *
    * Post-Cancun, the parentBeaconBlockRoot from the CL is stored at the beacon root contract address
    * (0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02) before executing transactions. The contract stores: timestamp → root
    * at slot (timestamp % HISTORY_BUFFER_LENGTH), and root at slot (timestamp % HISTORY_BUFFER_LENGTH +
    * HISTORY_BUFFER_LENGTH).
    */
  private def applyEip4788(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
    import BlockExecution._
    // Only apply post-Cancun (when parentBeaconBlockRoot is present)
    block.header.parentBeaconBlockRoot match {
      case Some(beaconRoot) if blockchainConfig.isCancunTimestamp(block.header.unixTimestamp) =>
        val timestamp = UInt256(block.header.unixTimestamp)
        val timestampIdx = timestamp.mod(UInt256(BeaconRootHistoryBufferLength))
        val rootIdx = timestampIdx + UInt256(BeaconRootHistoryBufferLength)

        // Ensure the contract account exists
        val account = world
          .getAccount(BeaconRootContractAddress)
          .getOrElse(Account.empty(blockchainConfig.accountStartNonce))

        val w1 = if (!world.getAccount(BeaconRootContractAddress).isDefined) {
          world.saveAccount(BeaconRootContractAddress, account)
        } else world

        val storage = w1.getStorage(BeaconRootContractAddress)
        val s1 = storage.store(timestampIdx.toBigInt, timestamp.toBigInt)
        val s2 = s1.store(rootIdx.toBigInt, UInt256(beaconRoot).toBigInt)
        w1.saveStorage(BeaconRootContractAddress, s2)

      case _ => world
    }
  }

  /** EIP-2935: Deploy history storage contract at fork block and store parent block hash.
    *
    * At the Olympia activation block, deploys the history storage contract (sets nonce=1 and code). At every
    * post-Olympia block, writes the parent hash to storage slot (blockNumber - 1) % HistoryServeWindow.
    */
  private def applyEip2935(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
    import BlockExecution._
    val blockNumber = block.header.number
    if (blockNumber < blockchainConfig.forkBlockNumbers.olympiaBlockNumber) return world

    // At the fork block, deploy the history storage contract
    val w1 = if (blockNumber == blockchainConfig.forkBlockNumbers.olympiaBlockNumber) {
      val account = world
        .getAccount(HistoryStorageAddress)
        .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        .copy(nonce = UInt256(1))
      world
        .saveAccount(HistoryStorageAddress, account)
        .saveCode(HistoryStorageAddress, HistoryStorageCode)
    } else {
      world
    }

    // Store parent hash at slot (blockNumber - 1) % HistoryServeWindow
    val parentHashValue = UInt256(block.header.parentHash)
    val slot = (blockNumber - 1) % HistoryServeWindow
    val storage = w1.getStorage(HistoryStorageAddress)
    val updatedStorage = storage.store(slot, parentHashValue.toBigInt)
    w1.saveStorage(HistoryStorageAddress, updatedStorage)
  }

  /** This function updates worldState transferring balance from drainList accounts to refundContract address
    *
    * @param worldState
    *   initial world state
    * @param daoForkConfig
    *   dao fork configuration with drainList and refundContract config
    * @return
    *   updated world state proxy
    */
  private def drainDaoForkAccounts(
      worldState: InMemoryWorldStateProxy,
      daoForkConfig: DaoForkConfig
  ): InMemoryWorldStateProxy =
    daoForkConfig.refundContract match {
      case Some(refundContractAddress) =>
        daoForkConfig.drainList.foldLeft(worldState) { (ws, address) =>
          ws.getAccount(address)
            .map(account => ws.transfer(from = address, to = refundContractAddress, account.balance))
            .getOrElse(ws)
        }
      case None => worldState
    }

  /** Executes and validates a list of blocks, storing the results in the blockchain.
    *
    * @param blocks
    *   blocks to be executed
    * @param parentChainWeight
    *   parent weight
    *
    * @return
    *   a list of blocks in incremental order that were correctly executed and an optional
    *   [[com.chipprbots.ethereum.ledger.BlockExecutionError]]
    */
  def executeAndValidateBlocks(
      blocks: List[Block],
      parentChainWeight: ChainWeight
  )(implicit blockchainConfig: BlockchainConfig): (List[BlockData], Option[BlockExecutionError]) = {
    @tailrec
    def go(
        executedBlocksDecOrder: List[BlockData],
        remainingBlocksIncOrder: List[Block],
        parentWeight: ChainWeight
    ): (List[BlockData], Option[BlockExecutionError]) =
      if (remainingBlocksIncOrder.isEmpty) {
        (executedBlocksDecOrder.reverse, None)
      } else {
        val blockToExecute = remainingBlocksIncOrder.head
        executeAndValidateBlock(blockToExecute, alreadyValidated = true) match {
          case Right(receipts) =>
            val newWeight = parentWeight.increase(blockToExecute.header)
            val newBlockData = BlockData(blockToExecute, receipts, newWeight)
            blockchainWriter.save(
              newBlockData.block,
              newBlockData.receipts,
              newBlockData.weight,
              saveAsBestBlock = false
            )
            go(newBlockData :: executedBlocksDecOrder, remainingBlocksIncOrder.tail, newWeight)
          case Left(executionError) =>
            (executedBlocksDecOrder.reverse, Some(executionError))
        }
      }

    go(List.empty[BlockData], blocks, parentChainWeight)
  }

}

object BlockExecution {

  /** EIP-4788: Address of the beacon block root system contract */
  val BeaconRootContractAddress: Address = Address("0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02")

  /** EIP-4788: History buffer length for beacon root storage (8191 slots) */
  val BeaconRootHistoryBufferLength: BigInt = BigInt(8191)

  /** EIP-2935: Address of the history storage contract */
  val HistoryStorageAddress: Address = Address("0x0000F90827F1C53a10cb7A02335B175320002935")

  /** EIP-2935: Number of historical block hashes served */
  val HistoryServeWindow: BigInt = BigInt(8191)

  /** EIP-2935: Deployed bytecode for the history storage contract */
  val HistoryStorageCode: ByteString = ByteStringUtils.string2hash(
    "3373fffffffffffffffffffffffffffffffffffffffe14604657602036036042575f35600143038111604257611fff81430311604257611fff9006545f5260205ff35b5f5ffd5b5f35611fff60014303065500"
  )
}

sealed trait BlockExecutionError {
  val reason: Any
}

sealed trait BlockExecutionSuccess

case object BlockExecutionSuccess extends BlockExecutionSuccess

object BlockExecutionError {
  final case class ValidationBeforeExecError(reason: Any) extends BlockExecutionError

  final case class StateBeforeFailure(worldState: InMemoryWorldStateProxy, acumGas: BigInt, acumReceipts: Seq[Receipt])

  final case class TxsExecutionError(stx: SignedTransaction, stateBeforeError: StateBeforeFailure, reason: String)
      extends BlockExecutionError

  final case class ValidationAfterExecError(reason: String) extends BlockExecutionError

  case object MissingParentError extends BlockExecutionError {
    override val reason: Any = "Cannot find parent"
  }

  final case class MPTError(reason: MPTException) extends BlockExecutionError
}
