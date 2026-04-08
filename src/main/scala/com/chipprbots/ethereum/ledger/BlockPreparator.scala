package com.chipprbots.ethereum.ledger

import scala.annotation.tailrec

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSignatureError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValidator
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.UInt256._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockExecutionError.StateBeforeFailure
import com.chipprbots.ethereum.ledger.BlockExecutionError.TxsExecutionError

import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.DebugTrace
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.{PC => _, _}

/** This is used from a [[com.chipprbots.ethereum.consensus.blocks.BlockGenerator BlockGenerator]].
  */
class BlockPreparator(
    vm: VMImpl,
    signedTxValidator: SignedTransactionValidator,
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader
) extends Logger {

  // NOTE We need a lazy val here, not a plain val, otherwise a mocked BlockChainConfig
  //      in some irrelevant test can throw an exception.
  private[ledger] def blockRewardCalculator(implicit blockchainConfig: BlockchainConfig) = new BlockRewardCalculator(
    blockchainConfig.monetaryPolicyConfig,
    blockchainConfig.forkBlockNumbers.byzantiumBlockNumber,
    blockchainConfig.forkBlockNumbers.constantinopleBlockNumber
  )

  /** This function updates the state in order to pay rewards based on YP section 11.3:
    *   1. Miner receives 100% of the block reward 2. Miner receives a reward for the inclusion of ommers 3. Ommer
    *      miners receive a reward for their inclusion in this block
    *
    * @param block
    *   the block being processed
    * @param worldStateProxy
    *   the initial state
    * @return
    *   the state after paying the appropriate reward to who corresponds
    */
  protected[ledger] def payBlockReward(
      block: Block,
      worldStateProxy: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
    // Post-merge: no PoW rewards, no ommer rewards. Process withdrawals instead.
    if (block.header.isPostMerge) {
      val worldAfterWithdrawals = processWithdrawals(block, worldStateProxy)
      return worldAfterWithdrawals
    }

    val blockNumber = block.header.number
    val minerRewardForBlock = blockRewardCalculator.calculateMiningRewardForBlock(blockNumber)
    val minerRewardForOmmers =
      blockRewardCalculator.calculateMiningRewardForOmmers(blockNumber, block.body.uncleNodesList.size)
    val minerAddress = Address(block.header.beneficiary)

    val minerReward = minerRewardForOmmers + minerRewardForBlock
    val worldAfterPayingBlockReward = increaseAccountBalance(minerAddress, UInt256(minerReward))(worldStateProxy)
    log.debug("Paying block {} reward of {} to miner with address {}", blockNumber, minerReward, minerAddress)

    val worldAfterOmmers = block.body.uncleNodesList.foldLeft(worldAfterPayingBlockReward) { (ws, ommer) =>
      val ommerAddress = Address(ommer.beneficiary)
      val ommerReward = blockRewardCalculator.calculateOmmerRewardForInclusion(blockNumber, ommer.number)

      log.debug("Paying block {} reward of {} to ommer with account address {}", blockNumber, ommerReward, ommerAddress)
      increaseAccountBalance(ommerAddress, UInt256(ommerReward))(ws)
    }

    // ECIP-1111: After Olympia activation, credit baseFee * gasUsed to treasury
    creditBaseFeeToTreasury(block.header, blockchainConfig.treasuryAddress, worldAfterOmmers)
  }

  /** EIP-4895: Process withdrawals from the beacon chain. Each withdrawal credits the target address with the specified
    * amount (in Gwei, converted to Wei).
    */
  private def processWithdrawals(
      block: Block,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    block.body.withdrawals match {
      case Some(withdrawals) =>
        withdrawals.foldLeft(world) { (ws, withdrawal) =>
          val weiAmount = UInt256(withdrawal.amount * BigInt("1000000000")) // Gwei → Wei
          log.debug(
            "Processing withdrawal idx={} validator={} to {} amount={} Gwei",
            withdrawal.index,
            withdrawal.validatorIndex,
            withdrawal.address,
            withdrawal.amount
          )
          increaseAccountBalance(withdrawal.address, weiAmount)(ws)
        }
      case None => world
    }

  /** ECIP-1111: Credit baseFee revenue to the treasury address. This runs AFTER block rewards and ommer rewards,
    * matching core-geth's Finalize() order.
    */
  private def creditBaseFeeToTreasury(
      blockHeader: BlockHeader,
      treasuryAddress: Address,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
    val isOlympiaActivated = blockHeader.number >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if (!isOlympiaActivated) return world

    blockHeader.baseFee match {
      case Some(baseFee) if baseFee > 0 && blockHeader.gasUsed > 0 && treasuryAddress != Address(0) =>
        val treasuryCredit = baseFee * blockHeader.gasUsed
        log.debug(
          "Crediting baseFee revenue {} (baseFee={} * gasUsed={}) to treasury {}",
          treasuryCredit,
          baseFee,
          blockHeader.gasUsed,
          treasuryAddress
        )
        increaseAccountBalance(treasuryAddress, UInt256(treasuryCredit))(world)
      case _ => world
    }
  }

  /** v0 ≡ Tg (Tx gas limit) * Tp (Tx gas price). See YP equation number (68)
    *
    * @param tx
    *   Target transaction
    * @return
    *   Upfront cost
    */
  private[ledger] def calculateUpfrontGas(tx: Transaction): UInt256 = UInt256(tx.gasLimit * tx.gasPrice)

  /** v0 ≡ Tg (Tx gas limit) * Tp (Tx gas price) + Tv (Tx value). See YP equation number (65)
    *
    * @param tx
    *   Target transaction
    * @return
    *   Upfront cost
    */
  private[ledger] def calculateUpfrontCost(tx: Transaction): UInt256 =
    UInt256(calculateUpfrontGas(tx) + tx.value)

  /** Increments account nonce by 1 stated in YP equation (69) and Pays the upfront Tx gas calculated as TxGasPrice *
    * TxGasLimit from balance. YP equation (68)
    *
    * @param stx
    * @param worldStateProxy
    * @return
    */
  private[ledger] def updateSenderAccountBeforeExecution(
      stx: SignedTransaction,
      senderAddress: Address,
      worldStateProxy: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy = {
    val account = worldStateProxy.getGuaranteedAccount(senderAddress)
    worldStateProxy.saveAccount(senderAddress, account.increaseBalance(-calculateUpfrontGas(stx.tx)).increaseNonce())
  }

  /** EIP-4844: Deduct blob gas cost from sender after execution.
    * The blob gas is burned (not paid to miner). Uses actual blobBaseFee from block header.
    */
  private[ledger] def deductBlobGas(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy = stx.tx match {
    case bt: com.chipprbots.ethereum.domain.BlobTransaction =>
      val blobGasUsed = BigInt(bt.blobVersionedHashes.size) * BigInt(131072)
      // Compute blob base fee from header's excessBlobGas
      val blobBaseFee = blockHeader.excessBlobGas.map(computeBlobBaseFee).getOrElse(BigInt(1))
      val blobGasCost = blobGasUsed * blobBaseFee
      val account = world.getGuaranteedAccount(senderAddress)
      world.saveAccount(senderAddress, account.increaseBalance(UInt256(-blobGasCost)))
    case _ => world
  }

  /** Compute the blob base fee from excessBlobGas per EIP-4844 */
  private def computeBlobBaseFee(excessBlobGas: BigInt): BigInt = {
    // fake_exponential(MIN_BLOB_BASE_FEE=1, excess_blob_gas, BLOB_BASE_FEE_UPDATE_FRACTION=3338477)
    val minBlobBaseFee = BigInt(1)
    val updateFraction = BigInt(3338477)
    if (excessBlobGas == 0) minBlobBaseFee
    else {
      // Simplified: baseFee = minBlobBaseFee * e^(excessBlobGas / updateFraction)
      // Use the integer approximation from the spec
      fakeExponential(minBlobBaseFee, excessBlobGas, updateFraction)
    }
  }

  /** Integer approximation of factor * e^(numerator / denominator) per EIP-4844 */
  private def fakeExponential(factor: BigInt, numerator: BigInt, denominator: BigInt): BigInt = {
    var i = 1
    var output = BigInt(0)
    var numeratorAccum = factor * denominator
    while (numeratorAccum > 0) {
      output += numeratorAccum
      numeratorAccum = (numeratorAccum * numerator) / (denominator * i)
      i += 1
    }
    output / denominator
  }

  private[ledger] def runVM(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): PR = {
    val evmConfig = EvmConfig.forBlock(blockHeader.number, blockHeader.unixTimestamp, blockchainConfig)
    val context: PC = ProgramContext(stx, blockHeader, senderAddress, world, evmConfig)
    vm.run(context)
  }

  /** Calculate total gas to be refunded See YP, eq (72)
    *
    * EIP-3529: Changes max refund from gasUsed / 2 to gasUsed / 5
    */
  private[ledger] def calcTotalGasToRefund(
      stx: SignedTransaction,
      result: PR,
      blockNumber: BigInt
  )(implicit blockchainConfig: BlockchainConfig): BigInt =
    result.error.map(_.useWholeGas) match {
      case Some(true)  => 0
      case Some(false) => result.gasRemaining
      case None =>
        val gasUsed = stx.tx.gasLimit - result.gasRemaining
        val blockchainConfigForEvm = BlockchainConfigForEvm(blockchainConfig)
        val etcFork = blockchainConfigForEvm.etcForkForBlockNumber(blockNumber)
        // EIP-3529: post-London refund cap is gasUsed/5 (not gasUsed/2)
        val isPostLondon = blockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
        val maxRefundQuotient = if (BlockchainConfigForEvm.isEip3529Enabled(etcFork) || isPostLondon) 5 else 2
        result.gasRemaining + (gasUsed / maxRefundQuotient).min(result.gasRefund)
    }

  private[ledger] def increaseAccountBalance(address: Address, value: UInt256)(
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
    val account =
      world.getAccount(address).getOrElse(Account.empty(blockchainConfig.accountStartNonce)).increaseBalance(value)
    world.saveAccount(address, account)
  }

  private[ledger] def pay(address: Address, value: UInt256, withTouch: Boolean)(
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    if (world.isZeroValueTransferToNonExistentAccount(address, value)) {
      world
    } else {
      val savedWorld = increaseAccountBalance(address, value)(world)
      if (withTouch) savedWorld.touchAccounts(address) else savedWorld
    }

  /** Delete all accounts (that appear in SUICIDE list). YP eq (78). The contract storage should be cleared during
    * pruning as nodes could be used in other tries. The contract code is also not deleted as there can be contracts
    * with the exact same code, making it risky to delete the code of an account in case it is shared with another one.
    * FIXME: [EC-242] Should we delete the storage associated with the deleted accounts? Should we keep track of
    * duplicated contracts for deletion?
    *
    * @param addressesToDelete
    * @param worldStateProxy
    * @return
    *   a worldState equal worldStateProxy except that the accounts from addressesToDelete are deleted
    */
  private[ledger] def deleteAccounts(addressesToDelete: Set[Address])(
      worldStateProxy: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    addressesToDelete.foldLeft(worldStateProxy) { case (world, address) => world.deleteAccount(address) }

  /** EIP161 - State trie clearing Delete all accounts that have been touched (involved in any potentially
    * state-changing operation) during transaction execution.
    *
    * All potentially state-changing operation are: Account is the target or refund of a SUICIDE operation for zero or
    * more value; Account is the source or destination of a CALL operation or message-call transaction transferring zero
    * or more value; Account is the source or newly-creation of a CREATE operation or contract-creation transaction
    * endowing zero or more value; as the block author ("miner") it is recipient of block-rewards or transaction-fees of
    * zero or more.
    *
    * Deletion of touched account should be executed immediately following the execution of the suicide list
    *
    * @param world
    *   world after execution of all potentially state-changing operations
    * @return
    *   a worldState equal worldStateProxy except that the accounts touched during execution are deleted and touched Set
    *   is cleared
    */
  private[ledger] def deleteEmptyTouchedAccounts(
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = {
    def deleteEmptyAccount(world: InMemoryWorldStateProxy, address: Address) =
      if (world.getAccount(address).exists(_.isEmpty(blockchainConfig.accountStartNonce)))
        world.deleteAccount(address)
      else
        world

    world.touchedAccounts
      .foldLeft(world)(deleteEmptyAccount)
      .clearTouchedAccounts
  }

  /** Public facade for eth_simulateV1 — delegates to the private executeTransaction */
  def executeTransactionForSimulation(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): TxResult =
    executeTransaction(stx, senderAddress, blockHeader, world)

  private[ledger] def executeTransaction(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): TxResult = {
    log.debug(s"Transaction ${stx.hash.toHex} execution start")
    val gasPrice = UInt256(Transaction.effectiveGasPrice(stx.tx, blockHeader.baseFee))
    val gasLimit = stx.tx.gasLimit

    val checkpointWorldState = updateSenderAccountBeforeExecution(stx, senderAddress, world)

    // EIP-7702: Process authorization list for Type-4 transactions before VM execution
    // Track refund for existing accounts (geth refunds CallNewAccountGas - TxAuthTupleGas per existing account)
    var authExistingAccountRefund: BigInt = 0
    val worldAfterAuths = stx.tx match {
      case sct: SetCodeTransaction =>
        val (world, refund) = applyAuthorizationsWithRefund(sct.authorizationList, checkpointWorldState)
        authExistingAccountRefund = refund
        world
      case _ => checkpointWorldState
    }

    val result = runVM(stx, senderAddress, blockHeader, worldAfterAuths)

    val resultWithErrorHandling: PR =
      if (result.error.isDefined) {
        // Rollback to the world before transfer was done if an error happened
        result.copy(world = checkpointWorldState, addressesToDelete = Set.empty, logs = Nil)
      } else
        result

    // EIP-7702: Add auth refund to the VM's refund counter before capping
    val resultWithAuthRefund = if (authExistingAccountRefund > 0) {
      resultWithErrorHandling.copy(gasRefund = resultWithErrorHandling.gasRefund + authExistingAccountRefund)
    } else resultWithErrorHandling
    val totalGasToRefundBase = calcTotalGasToRefund(stx, resultWithAuthRefund, blockHeader.number)
    val executionGasBase = gasLimit - totalGasToRefundBase

    if (DebugTrace.enabledForBlock(blockHeader.number)) {
      val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
      val isCreate = stx.tx.isContractInit
      val intrinsicGas = evmConfig.calcTransactionIntrinsicGas(stx.tx.payload, isCreate, Seq.empty)
      System.err.println(s"[TX-TRACE] block=${blockHeader.number} tx=${stx.hash.toHex} " +
        s"create=$isCreate gasLimit=$gasLimit intrinsic=$intrinsicGas " +
        s"vmGasRemaining=${result.gasRemaining} vmError=${result.error} " +
        s"refund=${result.gasRefund} returnDataLen=${result.returnData.size} " +
        s"gasToRefundBase=$totalGasToRefundBase executionGas=$executionGasBase")
    }

    // EIP-7623: Floor calldata gas — ensure gas charged is at least the floor data cost
    val isOlympiaActivated = blockHeader.number >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    val executionGasToPayToMiner = if (isOlympiaActivated) {
      executionGasBase.max(BlockPreparator.calcFloorDataGas(stx.tx.payload))
    } else {
      executionGasBase
    }
    val totalGasToRefund = gasLimit - executionGasToPayToMiner

    val refundGasFn = pay(senderAddress, (totalGasToRefund * gasPrice).toUInt256, withTouch = false) _
    // EIP-1559: miner receives only the priority fee (effectiveGasPrice - baseFee).
    // The baseFee portion is burned on ETH chains, or credited to treasury on ETC (ECIP-1111).
    val minerGasPrice = blockHeader.baseFee match {
      case Some(baseFee) => UInt256(gasPrice.toBigInt - baseFee)
      case None => gasPrice
    }
    val payMinerForGasFn =
      pay(Address(blockHeader.beneficiary), (executionGasToPayToMiner * minerGasPrice).toUInt256, withTouch = true) _

    val worldAfterPayments = refundGasFn.andThen(payMinerForGasFn)(resultWithErrorHandling.world)

    // EIP-4844: Deduct blob gas cost (burned, not paid to miner)
    val worldAfterBlobGas = deductBlobGas(stx, senderAddress, blockHeader, worldAfterPayments)

    val deleteAccountsFn = deleteAccounts(resultWithErrorHandling.addressesToDelete) _
    val deleteTouchedAccountsFn = deleteEmptyTouchedAccounts _
    val persistStateFn = InMemoryWorldStateProxy.persistState _

    val world2 = deleteAccountsFn.andThen(deleteTouchedAccountsFn).andThen(persistStateFn)(worldAfterBlobGas)

    if (DebugTrace.enabledForTx(blockHeader.number, stx.hash.toHex)) {
      val tx = stx.tx
      val accessList = Transaction.accessList(tx)
      val authListSize = tx match {
        case sct: SetCodeTransaction => sct.authorizationList.size
        case _                       => 0
      }
      val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
      val intrinsicGas = evmConfig.calcTransactionIntrinsicGas(tx.payload, tx.isContractInit, accessList, authListSize)

      val toOrCreate = tx.receivingAddress.map(_.toString).getOrElse("CREATE")
      val isCreate = tx.isContractInit
      val returnDataSize = result.returnData.size
      val codeDepositCost = if (isCreate) evmConfig.calcCodeDepositCost(result.returnData) else 0
      val maxCodeSize = evmConfig.blockchainConfig.maxCodeSize
      val codeSizeExceeded = isCreate && maxCodeSize.exists(limit => returnDataSize.toLong > limit.toLong)

      log.info(
        s"TRACE_TX block=${blockHeader.number} blockHash=${blockHeader.hashAsHexString} " +
          s"tx=${stx.hash.toHex} from=$senderAddress to=$toOrCreate create=$isCreate " +
          s"gasLimit=${tx.gasLimit} intrinsicGas=$intrinsicGas gasUsed=$executionGasToPayToMiner " +
          s"vmError=${result.error.map(_.toString)} logs=${resultWithErrorHandling.logs.size} " +
          s"returnDataSize=$returnDataSize codeDepositCost=$codeDepositCost maxCodeSize=$maxCodeSize " +
          s"maxCodeSizeExceeded=$codeSizeExceeded stateRoot=${world2.stateRootHash.toHex}"
      )
    }

    log.debug(s"""Transaction ${stx.hash.toHex} execution end. Summary:
         | - Error: ${result.error}.
         | - Total Gas to Refund: $totalGasToRefund
         | - Execution gas paid to miner: $executionGasToPayToMiner""".stripMargin)

    TxResult(world2, executionGasToPayToMiner, resultWithErrorHandling.logs, result.returnData, result.error)
  }

  // scalastyle:off method.length
  /** This functions executes all the signed transactions from a block (till one of those executions fails)
    *
    * @param signedTransactions
    *   from the block that are left to execute
    * @param world
    *   that will be updated by the execution of the signedTransactions
    * @param blockHeader
    *   of the block we are currently executing
    * @param acumGas,
    *   accumulated gas of the previoulsy executed transactions of the same block
    * @param acumReceipts,
    *   accumulated receipts of the previoulsy executed transactions of the same block
    * @return
    *   a BlockResult if the execution of all the transactions in the block was successful or a BlockExecutionError if
    *   one of them failed
    */
  @tailrec
  final private[ledger] def executeTransactions(
      signedTransactions: Seq[SignedTransaction],
      world: InMemoryWorldStateProxy,
      blockHeader: BlockHeader,
      acumGas: BigInt = 0,
      acumReceipts: Seq[Receipt] = Nil
  )(implicit blockchainConfig: BlockchainConfig): Either[TxsExecutionError, BlockResult] =
    signedTransactions match {
      case Nil =>
        Right(BlockResult(worldState = world, gasUsed = acumGas, receipts = acumReceipts))

      case Seq(stx, otherStxs @ _*) =>
        val upfrontCost = calculateUpfrontCost(stx.tx)
        val senderAddress = SignedTransaction.getSender(stx)


        val accountDataOpt = senderAddress
          .map { address =>
            world
              .getAccount(address)
              .map(a => (a, address))
              .getOrElse((Account.empty(blockchainConfig.accountStartNonce), address))
          }
          .toRight(TransactionSignatureError)

        val validatedStx = for {
          accData <- accountDataOpt
          _ <- signedTxValidator.validate(stx, accData._1, blockHeader, upfrontCost, acumGas)
        } yield accData

        validatedStx match {
          case Right((account, address)) =>
            val TxResult(newWorld, gasUsed, logs, _, vmError) =
              executeTransaction(stx, address, blockHeader, world.saveAccount(address, account))

            // spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-658.md
            val transactionOutcome =
              if (
                blockHeader.number >= blockchainConfig.forkBlockNumbers.byzantiumBlockNumber ||
                blockHeader.number >= blockchainConfig.forkBlockNumbers.atlantisBlockNumber
              ) {
                if (vmError.isDefined) FailureOutcome else SuccessOutcome
              } else {
                HashOutcome(newWorld.stateRootHash)
              }

            val legacyReceipt = LegacyReceipt(
              postTransactionStateHash = transactionOutcome,
              cumulativeGasUsed = acumGas + gasUsed,
              logsBloomFilter = BloomFilter.create(logs),
              logs = logs
            )
            val receipt = stx.tx match {
              case _: LegacyTransaction         => legacyReceipt
              case _: TransactionWithAccessList => Type01Receipt(legacyReceipt)
              case _: TransactionWithDynamicFee => Type02Receipt(legacyReceipt)
              case _: BlobTransaction           => Type03Receipt(legacyReceipt)
              case _: SetCodeTransaction        => Type04Receipt(legacyReceipt)
            }

            log.debug(s"Receipt generated for tx ${stx.hash.toHex}, $receipt")

            executeTransactions(otherStxs, newWorld, blockHeader, receipt.cumulativeGasUsed, acumReceipts :+ receipt)
          case Left(error) =>
            Left(TxsExecutionError(stx, StateBeforeFailure(world, acumGas, acumReceipts), error.toString))
        }
    }

  @tailrec
  final private[ledger] def executePreparedTransactions(
      signedTransactions: Seq[SignedTransaction],
      world: InMemoryWorldStateProxy,
      blockHeader: BlockHeader,
      acumGas: BigInt = 0,
      acumReceipts: Seq[Receipt] = Nil,
      executed: Seq[SignedTransaction] = Nil
  )(implicit blockchainConfig: BlockchainConfig): (BlockResult, Seq[SignedTransaction]) = {

    val result = executeTransactions(signedTransactions, world, blockHeader, acumGas, acumReceipts)

    result match {
      case Left(TxsExecutionError(stx, StateBeforeFailure(worldState, gas, receipts), reason)) =>
        log.debug(s"failure while preparing block because of $reason in transaction with hash ${stx.hash.toHex}")
        val txIndex = signedTransactions.indexWhere(tx => tx.hash == stx.hash)
        executePreparedTransactions(
          signedTransactions.drop(txIndex + 1),
          worldState,
          blockHeader,
          gas,
          receipts,
          executed ++ signedTransactions.take(txIndex)
        )
      case Right(br) => (br, executed ++ signedTransactions)
    }
  }

  def prepareBlock(
      evmCodeStorage: EvmCodeStorage,
      block: Block,
      parent: BlockHeader,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PreparedBlock = {

    val initialWorld =
      initialWorldStateBeforeExecution.getOrElse(
        InMemoryWorldStateProxy(
          evmCodeStorage = evmCodeStorage,
          mptStorage = blockchain.getReadOnlyMptStorage(),
          getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
          accountStartNonce = blockchainConfig.accountStartNonce,
          stateRootHash = parent.stateRoot,
          noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
          ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
        )
      )

    val prepared = executePreparedTransactions(block.body.transactionList, initialWorld, block.header)

    prepared match {
      case (execResult @ BlockResult(resultingWorldStateProxy, _, _), txExecuted) =>
        val worldToPersist = payBlockReward(block, resultingWorldStateProxy)
        val worldPersisted = InMemoryWorldStateProxy.persistState(worldToPersist)
        PreparedBlock(
          block.copy(body = block.body.copy(transactionList = txExecuted)),
          execResult,
          worldPersisted.stateRootHash,
          worldPersisted
        )
    }
  }

  /** EIP-7702: Process authorization list, setting delegation codes on authorized accounts. Each authorization is
    * validated and applied independently; failures are silently skipped.
    */
  private def applyAuthorizations(
      authList: List[SetCodeAuthorization],
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    applyAuthorizationsWithRefund(authList, world)._1

  /** Apply authorizations and return (world, refund) where refund is the gas to refund
    * for existing accounts per geth's EIP-7702 implementation:
    * Intrinsic charges CallNewAccountGas (25000) per auth.
    * If the authority account exists, refund CallNewAccountGas - TxAuthTupleGas (25000 - 12500 = 12500).
    */
  private def applyAuthorizationsWithRefund(
      authList: List[SetCodeAuthorization],
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): (InMemoryWorldStateProxy, BigInt) =
    authList.foldLeft((world, BigInt(0))) { case ((w, refund), auth) =>
      // Recover authority to check existence (needed for refund even if auth is invalid)
      val authorityOpt = recoverAuthority(auth)
      val existsRefund = authorityOpt match {
        case Some(addr) if w.getAccount(addr).isDefined => BigInt(25000 - 12500)
        case _ => BigInt(0)
      }
      applyAuthorization(auth, w) match {
        case Some(newWorld) => (newWorld, refund + existsRefund)
        case None => (w, refund + existsRefund)
      }
    }

  /** Recover authority address from authorization signature (for gas accounting) */
  private def recoverAuthority(auth: SetCodeAuthorization)(implicit blockchainConfig: BlockchainConfig): Option[Address] = {
    import com.chipprbots.ethereum.crypto.ECDSASignature
    import com.chipprbots.ethereum.rlp.{encode, PrefixedRLPEncodable, RLPList}
    import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
    import com.chipprbots.ethereum.rlp.RLPImplicits.given

    if (auth.chainId != 0 && auth.chainId != blockchainConfig.chainId) return None

    val sigHash = com.chipprbots.ethereum.crypto.kec256(
      encode(PrefixedRLPEncodable(0x05, RLPList(
        toEncodeable(auth.chainId), toEncodeable(auth.address.toArray), toEncodeable(auth.nonce)
      )))
    )
    val rawV = if (auth.v == 0) ECDSASignature.negativePointSign else ECDSASignature.positivePointSign
    val ecdsaSig = ECDSASignature(auth.r, auth.s, BigInt(rawV))
    ecdsaSig.publicKey(sigHash).flatMap { key =>
      val addrBytes = com.chipprbots.ethereum.crypto.kec256(key).slice(12, 32)
      if (addrBytes.length == Address.Length) Some(Address(addrBytes)) else None
    }
  }

  /** Apply a single EIP-7702 authorization. Returns None if the authorization should be skipped. */
  private def applyAuthorization(
      auth: SetCodeAuthorization,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): Option[InMemoryWorldStateProxy] = {
    import com.chipprbots.ethereum.crypto.ECDSASignature
    import com.chipprbots.ethereum.rlp.{encode, PrefixedRLPEncodable, RLPList}
    import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
    import com.chipprbots.ethereum.rlp.RLPImplicits.given

    // 1. Verify chain ID: must be 0 (wildcard) or match current chain
    if (auth.chainId != 0 && auth.chainId != blockchainConfig.chainId) None
    else {
      // 2. Recover authority address from authorization signature
      val sigHash = com.chipprbots.ethereum.crypto.kec256(
        encode(
          PrefixedRLPEncodable(
            0x05,
            RLPList(
              toEncodeable(auth.chainId),
              toEncodeable(auth.address.toArray),
              toEncodeable(auth.nonce)
            )
          )
        )
      )

      // Convert y-parity (0/1) to point sign (27/28) for recovery
      val rawV = if (auth.v == 0) ECDSASignature.negativePointSign else ECDSASignature.positivePointSign
      val ecdsaSig = ECDSASignature(auth.r, auth.s, BigInt(rawV))
      val recoveredKey = ecdsaSig.publicKey(sigHash)
      val authority = recoveredKey.flatMap { key =>
        val addrBytes = com.chipprbots.ethereum.crypto.kec256(key).slice(12, 32)
        if (addrBytes.length == Address.Length) Some(Address(addrBytes)) else None
      }
      authority.flatMap { authorityAddr =>
        // 3. Check that authority does not have code (unless it's already a delegation)
        val code = world.getCode(authorityAddr)
        if (code.nonEmpty && !SetCodeTransaction.isDelegation(code)) None
        else {
          // 4. Verify nonce matches
          val account = world
            .getAccount(authorityAddr)
            .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
          if (account.nonce != UInt256(auth.nonce)) None
          else {
            // 5. Increment nonce
            val updatedAccount = account.copy(nonce = account.nonce + 1)
            val w1 = world.saveAccount(authorityAddr, updatedAccount)

            // 6. Set delegation code (or clear if target is zero address)
            val zeroAddress = Address(0L)
            val w2 = if (auth.address == zeroAddress) {
              w1.saveCode(authorityAddr, ByteString.empty)
            } else {
              w1.saveCode(authorityAddr, SetCodeTransaction.addressToDelegation(auth.address))
            }
            Some(w2)
          }
        }
      }
    }
  }
}

object BlockPreparator {

  /** EIP-7623: Calculate floor data gas for a transaction. Floor ensures calldata-heavy transactions pay a minimum gas
    * cost. tokens = nonzero_bytes * 4 + zero_bytes floorDataGas = 21000 + tokens * 10
    */
  def calcFloorDataGas(payload: ByteString): BigInt = {
    val zeroBytes = payload.count(_ == 0)
    val nonZeroBytes = payload.length - zeroBytes
    val tokens = nonZeroBytes * 4 + zeroBytes
    BigInt(21000) + tokens * 10
  }
}
