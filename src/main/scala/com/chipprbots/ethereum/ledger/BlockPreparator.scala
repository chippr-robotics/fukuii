package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSignatureError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValidator
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.UInt256.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.StateBeforeFailure
import com.chipprbots.ethereum.ledger.BlockExecutionError.TxsExecutionError
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.DebugTrace
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.{PC as _, *}

/** This is used from a [[com.chipprbots.ethereum.consensus.blocks.BlockGenerator BlockGenerator]].
  */
class BlockPreparator(
    vm: VMImpl,
    signedTxValidator: SignedTransactionValidator,
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader
) extends Logger:

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
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    // Post-merge: no PoW rewards, no ommer rewards. EIP-4895 withdrawals are applied by
    // BlockExecution.processWithdrawals after payBlockReward returns; applying them here
    // too would double-credit every withdrawal and break state-root validation.
    if block.header.isPoS then worldStateProxy
    else
      val blockNumber = block.header.number.value
      val minerRewardForBlock = blockRewardCalculator.calculateMiningRewardForBlock(blockNumber)
      val minerRewardForOmmers =
        blockRewardCalculator.calculateMiningRewardForOmmers(blockNumber, block.body.uncleNodesList.size)
      val minerAddress = Address(block.header.beneficiary)

      val minerReward = minerRewardForOmmers + minerRewardForBlock

      // ECIP-1111: Treasury credit BEFORE miner/ommer rewards (spec order per ECIP-1111)
      val worldAfterTreasury = creditBaseFeeToTreasury(block.header, blockchainConfig.treasuryAddress, worldStateProxy)

      val worldAfterPayingBlockReward = increaseAccountBalance(minerAddress, UInt256(minerReward))(worldAfterTreasury)
      log.debug("Paying block {} reward of {} to miner with address {}", blockNumber, minerReward, minerAddress)

      block.body.uncleNodesList.foldLeft(worldAfterPayingBlockReward) { (ws, ommer) =>
        val ommerAddress = Address(ommer.beneficiary)
        val ommerReward = blockRewardCalculator.calculateOmmerRewardForInclusion(blockNumber, ommer.number.value)

        log.debug(
          "Paying block {} reward of {} to ommer with account address {}",
          blockNumber,
          ommerReward,
          ommerAddress
        )
        increaseAccountBalance(ommerAddress, UInt256(ommerReward))(ws)
      }

  /** ECIP-1111: Credit baseFee * gasUsed to treasury. Applied BEFORE miner and ommer rewards per ECIP-1111 spec. */
  private def creditBaseFeeToTreasury(
      blockHeader: BlockHeader,
      treasuryAddress: Address,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val isOlympiaActivated = blockHeader.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if !isOlympiaActivated then world
    else
      if treasuryAddress == Address(0) && blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETC
      then
        throw new IllegalStateException(
          s"OLYMPIA SAFETY: treasury address is zero at block ${blockHeader.number}. " +
            "Set treasury-address in chain config before activating Olympia."
        )

      blockHeader.baseFee match
        case Some(baseFee) if baseFee > 0 && blockHeader.gasUsed > GasAmount.Zero && treasuryAddress != Address(0) =>
          val treasuryCredit = baseFee * blockHeader.gasUsed.value
          log.debug(
            "Crediting baseFee revenue {} (baseFee={} * gasUsed={}) to treasury {}",
            treasuryCredit,
            baseFee,
            blockHeader.gasUsed,
            treasuryAddress
          )
          increaseAccountBalance(treasuryAddress, UInt256(treasuryCredit))(world)
        case _ => world

  /** v0 ≡ Tg (Tx gas limit) * Tp (Tx gas price). See YP equation number (68)
    *
    * @param tx
    *   Target transaction
    * @return
    *   Upfront cost
    */
  private[ledger] def calculateUpfrontGas(tx: Transaction): UInt256 = UInt256(tx.gasLimit.value * tx.gasPrice.value)

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
    * TxGasLimit from balance. YP equation (68). Per EIP-4844, blob-gas cost is ALSO part of the upfront cost deducted
    * before VM execution (so BALANCE/SELFBALANCE opcodes within the tx see the correct post-upfront sender balance).
    *
    * @param stx
    * @param worldStateProxy
    * @return
    */
  private[ledger] def updateSenderAccountBeforeExecution(
      stx: SignedTransaction,
      senderAddress: Address,
      worldStateProxy: InMemoryWorldStateProxy,
      blockHeader: BlockHeader,
      // eth_simulateV1's overrides for this call; SimulationOverrides.Empty on every other path.
      overrides: SimulationOverrides
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val account = worldStateProxy.getGuaranteedAccount(senderAddress)
    val blobGasCost = stx.tx match
      case bt: com.chipprbots.ethereum.domain.BlobTransaction =>
        val blobGasUsed = BigInt(bt.blobVersionedHashes.size) * BigInt(131072)
        // eth_simulateV1's blockOverrides.blobBaseFee lets the caller force a
        // specific blob fee that doesn't necessarily match what excessBlobGas
        // would derive (e.g. blobBaseFee=0 when excessBlobGas=0 would yield 1).
        val blobBaseFee = overrides.blobBaseFeeOverride.getOrElse(
          blockHeader.excessBlobGas
            .map(eg => BlobGasUtils.getBlobGasPrice(eg, blockHeader.unixTimestamp, blockchainConfig))
            .getOrElse(BigInt(1))
        )
        blobGasUsed * blobBaseFee
      case _ => BigInt(0)
    // EIP-1559 / EELS: the pre-execution balance deduction is
    //   gasLimit * effectiveGasPrice + tx.value + blobGasCost
    // NOT gasLimit * maxFee. The balance *check* (in validate) still requires
    // gasLimit * maxFee to be available — that's the reservation — but the
    // actual amount removed from the sender is only what will ever be spent
    // at the effective gas price. See EELS prague/transactions.py
    // `effective_gas_fee = tx.gas * effective_gas_price`.
    //
    // Consequence: `BALANCE(sender)` inside the VM sees the post-upfront
    // balance computed with effectiveGasPrice, matching geth/nethermind.
    // Surfaces on hive `bcEIP1559/burnVerify_Cancun` which reads BALANCE inside
    // the contract to verify the burn invariant.
    val effectiveGasPrice = Transaction.effectiveGasPrice(stx.tx, blockHeader.baseFee)
    val executionUpfront = (stx.tx.gasLimit * effectiveGasPrice).value
    val upfrontTotal = executionUpfront + blobGasCost
    worldStateProxy.saveAccount(
      senderAddress,
      account.increaseBalance(UInt256(-upfrontTotal)).increaseNonce()
    )

  /** Legacy 3-arg overload — kept for callers that don't have a BlockHeader in scope (e.g. EthSimulateService).
    * Blob-gas is zero for them.
    */
  private[ledger] def updateSenderAccountBeforeExecution(
      stx: SignedTransaction,
      senderAddress: Address,
      worldStateProxy: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    val account = worldStateProxy.getGuaranteedAccount(senderAddress)
    worldStateProxy.saveAccount(
      senderAddress,
      account.increaseBalance(-calculateUpfrontGas(stx.tx)).increaseNonce()
    )

  /** EIP-4844: Deduct blob gas cost from sender after execution. The blob gas is burned (not paid to miner). Uses
    * actual blobBaseFee from block header.
    */
  private[ledger] def deductBlobGas(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy = stx.tx match
    case bt: com.chipprbots.ethereum.domain.BlobTransaction =>
      val blobGasUsed = BigInt(bt.blobVersionedHashes.size) * BigInt(131072)
      // Compute blob base fee from header's excessBlobGas using fork-correct update fraction.
      val blobBaseFee = blockHeader.excessBlobGas
        .map(eg => BlobGasUtils.getBlobGasPrice(eg, blockHeader.unixTimestamp, blockchainConfig))
        .getOrElse(BigInt(1))
      val blobGasCost = blobGasUsed * blobBaseFee
      val account = world.getGuaranteedAccount(senderAddress)
      world.saveAccount(senderAddress, account.increaseBalance(UInt256(-blobGasCost)))
    case _ => world

  private[ledger] def runVM(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      authExecutionGas: BigInt = 0,
      authStateGas: BigInt = 0,
      tracer: Option[com.chipprbots.ethereum.vm.ExecutionTracer] = None,
      // EIP-7702: authorities recovered while processing the authorization list, warm for the whole transaction.
      extraWarmAddresses: Set[Address] = Set.empty,
      // eth_simulateV1's overrides, from executeTransactionForSimulation only. StxLedger's calls leave it Empty.
      overrides: SimulationOverrides = SimulationOverrides.Empty
  )(implicit blockchainConfig: BlockchainConfig): PR =
    vm.run(
      topLevelContext(
        stx,
        senderAddress,
        blockHeader,
        world,
        authExecutionGas,
        authStateGas,
        tracer,
        extraWarmAddresses,
        accessRecorder = None,
        overrides = overrides
      )
    )

  /** The transaction's top-level frame context, as [[runVM]] runs it. Separate so `executeTransaction` can also read
    * what the context decided about EIP-2780's pre-execution phase (`preExecutionOutOfGas`) — that decides how far a
    * failed transaction rolls back.
    */
  private def topLevelContext(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      authExecutionGas: BigInt,
      authStateGas: BigInt,
      tracer: Option[com.chipprbots.ethereum.vm.ExecutionTracer],
      extraWarmAddresses: Set[Address],
      accessRecorder: Option[BlockAccessRecorder],
      // eth_simulateV1's overrides for this transaction; SimulationOverrides.Empty on every other path.
      overrides: SimulationOverrides
  )(implicit blockchainConfig: BlockchainConfig): PC =
    val evmConfig = EvmConfig.forBlock(blockHeader.number.value, blockHeader.unixTimestamp, blockchainConfig)
    val context: PC =
      ProgramContext(
        stx,
        blockHeader,
        senderAddress,
        world,
        evmConfig,
        authExecutionGas,
        authStateGas,
        extraWarmAddresses
      )
    // Apply the eth_simulateV1 overrides, if this call carries any
    var ctx = context
    if extraWarmAddresses.nonEmpty then ctx = ctx.copy(warmAddresses = ctx.warmAddresses ++ extraWarmAddresses)
    if overrides.precompileRelocations.nonEmpty then
      ctx = ctx.copy(precompileRelocations = overrides.precompileRelocations)
    if overrides.traceTransfers then ctx = ctx.copy(traceTransfers = true)
    if tracer.isDefined then ctx = ctx.copy(tracer = tracer)
    // EIP-7928: the transaction's block-access recorder, while an Amsterdam block executes.
    if accessRecorder.isDefined then ctx = ctx.copy(accessRecorder = accessRecorder)
    ctx

  /** Like [[runVM]] but uses a one-off VM instance with the given [[ExecutionTracer]] attached. Called by
    * [[StxLedger.simulateTransactionWithTracer]] for debug_traceTransaction / trace_call etc.
    *
    * Besu reference: DebugTraceTransaction.java — creates DebugOperationTracer, passes to TransactionSimulator
    * core-geth reference: eth/tracers/api.go traceTx() — wraps evm.Config.Tracer
    */
  private[ledger] def runVMWithTracer(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      tracer: ExecutionTracer
  )(implicit blockchainConfig: BlockchainConfig): PR =
    val tracerVm = new VMImpl(Some(tracer))
    val evmConfig = EvmConfig.forBlock(blockHeader.number.value, blockHeader.unixTimestamp, blockchainConfig)
    // No eth_simulateV1 overrides: the debug and trace calls never carry any.
    val context: PC = ProgramContext(stx, blockHeader, senderAddress, world, evmConfig)
    tracerVm.run(context)

  /** Calculate total gas to be refunded See YP, eq (72)
    *
    * EIP-3529: Changes max refund from gasUsed / 2 to gasUsed / 5
    */
  private[ledger] def calcTotalGasToRefund(
      stx: SignedTransaction,
      result: PR,
      blockNumber: BigInt
  )(implicit blockchainConfig: BlockchainConfig): BigInt =
    // EIP-8037: `tx_gas_used_before_refund = tx.gas - gas_left - state_gas_reservoir`. Whatever survives in
    // the reservoir was never spent and goes back to the sender alongside `gasRemaining`, on EVERY exit path
    // — including an exceptional halt, which consumes gas_left but not the separate state allowance.
    // Pre-Amsterdam the reservoir is 0 and every branch below is byte-identical to its previous form.
    val unspentReservoir = result.stateGasReservoir
    result.error.map(_.useWholeGas) match
      case Some(true)  => unspentReservoir
      case Some(false) => result.gasRemaining + unspentReservoir
      case None =>
        val gasUsed = stx.tx.gasLimit.value - result.gasRemaining - unspentReservoir
        val blockchainConfigForEvm = BlockchainConfigForEvm(blockchainConfig)
        val etcFork = blockchainConfigForEvm.etcForkForBlockNumber(blockNumber)
        // EIP-3529: post-London refund cap is gasUsed/5 (not gasUsed/2)
        val isPostLondon = blockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
        val maxRefundQuotient = if BlockchainConfigForEvm.isEip3529Enabled(etcFork) || isPostLondon then 5 else 2
        result.gasRemaining + unspentReservoir + (gasUsed / maxRefundQuotient).min(result.gasRefund)

  private[ledger] def increaseAccountBalance(address: Address, value: UInt256)(
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val account =
      world.getAccount(address).getOrElse(Account.empty(blockchainConfig.accountStartNonce)).increaseBalance(value)
    world.saveAccount(address, account)

  private[ledger] def pay(address: Address, value: UInt256, withTouch: Boolean)(
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    // EIP-161: zero-value transfers to non-existent accounts are a no-op (they
    // must not materialise the account). All other zero-value payments still
    // count as touches when withTouch=true — an existing empty account that
    // receives one becomes a deletion candidate via deleteEmptyTouchedAccounts.
    //
    // Pre-EIP-161 (noEmptyAccounts = false) a zero-value payment to a NON-EXISTENT account still CREATES it, empty.
    // That is what core-geth (`StateDB.AddBalance` -> `getOrNewStateObject`) and Besu (`worldState.getOrCreate(
    // miningBeneficiary)`) do with a zero transaction fee, and nothing clears the empty account before EIP-161, so it
    // is part of the intermediate state root that pre-Byzantium/pre-Atlantis receipts commit to (and of the account
    // set later transactions in the block see). ethereum/legacytests callOutput*/callcodeOutput*_Frontier (gasPrice 0,
    // coinbase absent from pre) pin it. The first branch (`isZeroValueTransferToNonExistentAccount`) has already
    // returned for this case on every post-EIP-161 block, so the materialisation only ever fires pre-EIP-161.
    if world.isZeroValueTransferToNonExistentAccount(address, value) then world
    else if value == UInt256.Zero then
      val materialised =
        if world.accountExists(address) then world
        else world.saveAccount(address, Account.empty(blockchainConfig.accountStartNonce))
      if withTouch then materialised.touchAccounts(address) else materialised
    else
      val savedWorld = increaseAccountBalance(address, value)(world)
      if withTouch then savedWorld.touchAccounts(address) else savedWorld

  /** Delete all accounts (that appear in SUICIDE list). YP eq (78). The contract storage should be cleared during
    * pruning as nodes could be used in other tries. The contract code is also not deleted as there can be contracts
    * with the exact same code, making it risky to delete the code of an account in case it is shared with another one.
    * @param addressesToDelete
    * @param worldStateProxy
    * @return
    *   a worldState equal worldStateProxy except that the accounts from addressesToDelete are deleted
    */
  private[ledger] def deleteAccounts(addressesToDelete: Set[Address])(
      worldStateProxy: InMemoryWorldStateProxy
  ): InMemoryWorldStateProxy =
    addressesToDelete.foldLeft(worldStateProxy) { case (world, address) => world.deleteAccount(address) }

  /** EIP-8246 (Amsterdam) finalization of the accounts SELFDESTRUCT marked for deletion — under EIP-6780 only accounts
    * created in the same transaction. They are no longer deleted: the nonce is reset, the code and all storage are
    * cleared, and the BALANCE IS KEPT, so no ether is burned (execution-specs `clear_account_preserving_balance`,
    * go-ethereum `finaliseAmsterdam`). An account left with a zero balance is empty, and EIP-161 removes it —
    * execution-specs does so in the same `modify_state` step, so it is removed here rather than left for the
    * touched-account sweep.
    *
    * Deleting first and saving a fresh account is what clears the storage: the fresh account carries the empty storage
    * root, and the deletion drops the in-transaction storage and code overlays for the address. The code itself stays
    * in the code store (shared by hash), exactly as [[deleteAccounts]] leaves it.
    */
  private[ledger] def clearSelfDestructedAccounts(addressesToDelete: Set[Address])(
      worldStateProxy: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    addressesToDelete.foldLeft(worldStateProxy) { case (world, address) =>
      val balance = world.getBalance(address)
      val cleared = world.deleteAccount(address)
      if balance.isZero then cleared
      else cleared.saveAccount(address, Account.empty(blockchainConfig.accountStartNonce).copy(balance = balance))
    }

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
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    def deleteEmptyAccount(world: InMemoryWorldStateProxy, address: Address) =
      if world.getAccount(address).exists(_.isEmpty(blockchainConfig.accountStartNonce)) then
        world.deleteAccount(address)
      else world

    world.touchedAccounts
      .foldLeft(world)(deleteEmptyAccount)
      .clearTouchedAccounts

  /** Public facade for eth_simulateV1: executes one transaction as block import does, under this call's overrides —
    * precompile relocations for movePrecompileToAddress, traceTransfers, and the blobBaseFee block override.
    *
    * The overrides are passed down this call's stack as an immutable [[SimulationOverrides]] and exist nowhere else:
    * block import, other eth_simulateV1 calls and StxLedger, which share this preparator and run concurrently, never
    * see them.
    */
  def executeTransactionForSimulation(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      precompileRelocations: Map[Address, Address] = Map.empty,
      traceTransfers: Boolean = false,
      blobBaseFeeOverride: Option[BigInt] = None
  )(implicit blockchainConfig: BlockchainConfig): TxResult =
    executeTransactionWithOverrides(
      stx,
      senderAddress,
      blockHeader,
      world,
      SimulationOverrides(precompileRelocations, traceTransfers, blobBaseFeeOverride),
      accessRecorder = None,
      tracer = None
    )

  /** Executes one transaction of a block, as block import does. There are no eth_simulateV1 overrides here, and no
    * parameter through which any could arrive.
    */
  private[ledger] def executeTransaction(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      // EIP-7928: the transaction's block-access recorder. Present only when executing an Amsterdam block.
      accessRecorder: Option[BlockAccessRecorder] = None,
      // An RPC tracer watching the top-level frame, for a trace replay (StxLedger.replayTransaction). Block import and
      // block building never pass one, and without one the frame runs on `vm` exactly as it always has.
      tracer: Option[ExecutionTracer] = None
  )(implicit blockchainConfig: BlockchainConfig): TxResult =
    executeTransactionWithOverrides(
      stx,
      senderAddress,
      blockHeader,
      world,
      SimulationOverrides.Empty,
      accessRecorder,
      tracer
    )

  /** The one transaction-execution path, shared by [[executeTransaction]] (overrides always Empty) and
    * [[executeTransactionForSimulation]] (the simulate call's own overrides). Every parameter is explicit, so neither
    * caller can drop the block-access recorder or the tracer by omission.
    */
  private def executeTransactionWithOverrides(
      stx: SignedTransaction,
      senderAddress: Address,
      blockHeader: BlockHeader,
      world: InMemoryWorldStateProxy,
      overrides: SimulationOverrides,
      accessRecorder: Option[BlockAccessRecorder],
      tracer: Option[ExecutionTracer]
  )(implicit blockchainConfig: BlockchainConfig): TxResult =
    log.debug(s"Transaction ${stx.hash.toHex} execution start")
    // EIP-7928: execution-specs `check_transaction` reads the sender before anything else.
    BlockAccessRecorder.account(accessRecorder, senderAddress)
    val gasPrice = UInt256(Transaction.effectiveGasPrice(stx.tx, blockHeader.baseFee))
    val gasLimit = stx.tx.gasLimit

    val checkpointWorldState = updateSenderAccountBeforeExecution(stx, senderAddress, world, blockHeader, overrides)

    // EIP-7702: Process authorization list for Type-4 transactions before VM execution
    // Track refund for existing accounts (geth refunds CallNewAccountGas - TxAuthTupleGas per existing account)
    var authExistingAccountRefund: BigInt = 0
    // EIP-2780 replaces EIP-7702's flat PER_AUTH_BASE_COST of 25,000 with EXECUTION_PER_AUTH_BASE_COST
    // of 7,816 plus runtime state charges. The 12,500 "existing authority" refund below exists solely to
    // give back part of that 25,000 — with the charge gone, the refund would be a credit against nothing
    // and would under-charge every Type-4 transaction after activation.
    val amsterdamActive = blockchainConfig.isAmsterdamTimestamp(blockHeader.unixTimestamp)
    val evmConfigForTx = EvmConfig.forBlock(blockHeader.number.value, blockHeader.unixTimestamp, blockchainConfig)
    var authExecutionGas: BigInt = 0
    var authStateGas: BigInt = 0
    var authorityWarmAddresses: Set[Address] = Set.empty
    var authorizationsOutOfGas = false
    val worldAfterAuths = stx.tx match
      case sct: SetCodeTransaction if amsterdamActive =>
        // EIP-2780: the authorizations are charged one by one as they are applied, against the top frame's meter —
        // which is why they need the frame's gas split before the frame exists. The accounts whose first write the
        // transaction already paid for: the sender (TX_BASE) and a value-bearing recipient (TX_VALUE_COST).
        val (executionGrant, reservoir) = ProgramContext.evmGasAllocation(stx, senderAddress, evmConfigForTx)
        val paidWrites = Set(senderAddress) ++ (if sct.value > 0 then sct.receivingAddress.toSet else Set.empty)
        val applied = applyAmsterdamAuthorizations(
          sct.authorizationList,
          checkpointWorldState,
          paidWrites,
          executionGrant,
          reservoir
        )
        authExecutionGas = applied.executionGas
        authStateGas = applied.stateGas
        authorityWarmAddresses = applied.warmAuthorities
        authorizationsOutOfGas = applied.outOfGas
        applied.world
      case sct: SetCodeTransaction =>
        val (world, refund, warm) = applyAuthorizationsWithRefund(sct.authorizationList, checkpointWorldState)
        authExistingAccountRefund = refund
        authorityWarmAddresses = warm
        world
      case _ => checkpointWorldState

    val context = topLevelContext(
      stx,
      senderAddress,
      blockHeader,
      worldAfterAuths,
      authExecutionGas,
      authStateGas,
      tracer = None,
      extraWarmAddresses = authorityWarmAddresses,
      accessRecorder = accessRecorder,
      overrides = overrides
    )
    accessRecorder.foreach { recorder =>
      recordPreExecutionReads(
        stx,
        senderAddress,
        worldAfterAuths,
        context,
        authorityWarmAddresses,
        authorizationsOutOfGas
      )(
        recorder
      )
    }
    // A tracer rides on a VM of its own, as runVMWithTracer's does: the VM's tracer is the one that sees sub-call
    // entries and exits as well as steps. It is not also put on the context, which would report every step twice.
    val result = tracer.fold(vm.run(context))(t => new VMImpl(Some(t)).run(context))

    // A failed top-level frame reverts to the world as it stood when the frame was ENTERED, which is after the
    // EIP-7702 authorizations: they are applied before the call snapshot (go-ethereum `execute`, execution-specs
    // `process_call` snapshots after `create_evm`), so a reverting or exceptionally-halting Type-4 transaction still
    // leaves its delegations and nonce bumps in place (EEST `test_full_gas_consumption[type_4]`,
    // `test_set_code_to_sstore[invalid-*]`, `test_delegation_persists_on_execution_oog`). For every other transaction
    // type `worldAfterAuths` IS `checkpointWorldState`.
    //
    // Amsterdam adds the one exception: EIP-2780's pre-execution phase — the authorization charges, the recipient's
    // account-creation charge and the delegation-target access — can itself run out of gas, and then the frame is
    // never entered and the whole phase is rolled back, delegations included (execution-specs `process_top_level`
    // restores `prep_snapshot`). `preExecutionOutOfGas` is only ever set on an Amsterdam block.
    val rollbackWorld = if context.preExecutionOutOfGas then checkpointWorldState else worldAfterAuths
    val resultWithErrorHandling: PR =
      if result.error.isDefined then
        // Rollback to the world before transfer was done if an error happened
        result.copy(world = rollbackWorld, addressesToDelete = Set.empty, logs = Nil)
      else result

    // EIP-7702: Add auth refund to the VM's refund counter before capping
    val resultWithAuthRefund =
      if authExistingAccountRefund > 0 then
        resultWithErrorHandling.copy(gasRefund = resultWithErrorHandling.gasRefund + authExistingAccountRefund)
      else resultWithErrorHandling

    // go-ethereum applies the refund counter on EVERY exit path (`st.gasRemaining += st.calcRefund()` after the call,
    // regardless of `vmerr`). A failed frame reverts the refunds it accrued itself, but the EIP-7702 existing-authority
    // refund was accrued before the frame's snapshot and survives it. `calcTotalGasToRefund` drops `gasRefund` on
    // error — correct for every refund the VM produced, wrong for this one — so it is capped (EIP-3529, /5) and
    // added back here. Zero for every non-Type-4 transaction and for every Amsterdam one.
    val totalGasToRefundVm = calcTotalGasToRefund(stx, resultWithAuthRefund, blockHeader.number.value)
    val authRefundOnFailure: BigInt =
      if result.error.isDefined && authExistingAccountRefund > 0 then
        ((gasLimit.value - totalGasToRefundVm) / 5).min(authExistingAccountRefund)
      else BigInt(0)
    val totalGasToRefundBase = totalGasToRefundVm + authRefundOnFailure
    val executionGasBase = gasLimit - GasAmount(totalGasToRefundBase)

    if DebugTrace.enabledForBlock(blockHeader.number.value) then
      val evmConfig = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig)
      val isCreate = stx.tx.isContractInit
      val intrinsicGas = evmConfig.calcTransactionIntrinsicGas(
        stx.tx.payload,
        isCreate,
        Seq.empty,
        0,
        stx.tx.receivingAddress,
        UInt256(stx.tx.value),
        senderAddress
      )
      log.debug(
        s"[TX-TRACE] block=${blockHeader.number} tx=${stx.hash.toHex} " +
          s"create=$isCreate gasLimit=$gasLimit intrinsic=$intrinsicGas " +
          s"vmGasRemaining=${result.gasRemaining} vmError=${result.error} " +
          s"refund=${result.gasRefund} returnDataLen=${result.returnData.size} " +
          s"gasToRefundBase=$totalGasToRefundBase executionGas=$executionGasBase"
      )

    // EIP-7623 activation — ETH Prague timestamp, ETC Olympia block (ECIP-1121). The validator's `tx.gas >= floor`
    // rule reads the same predicate; see BlockPreparator.eip7623Active.
    val eip7623Active = BlockPreparator.eip7623Active(blockHeader.number.value, blockHeader.unixTimestamp)
    // EIP-7623's floor sits on the transaction's base cost. Pre-Amsterdam that base is the flat 21,000;
    // EIP-2780 replaces it with the decomposed base, and that substitution is load-bearing rather than
    // cosmetic — a floor still anchored at 21,000 would drag the measured 12,000 self-transfer back up to
    // 21,000 and the measured 17,201 `tx-callrevert` up to 21,040, contradicting the fixture on both.
    //
    // Amsterdam also reprices the floor itself: EIP-7976 charges 64 gas per calldata byte, zero or not, and
    // EIP-7981 adds the access list's data cost. That is a different function, not a different base, so
    // the pre-Amsterdam call below — ETH Prague/Osaka and ETC Olympia — is untouched.
    val floorDataGas =
      if evmConfigForTx.amsterdamEnabled then
        evmConfigForTx.calcAmsterdamCalldataFloorGas(
          stx.tx.payload,
          Transaction.accessList(stx.tx),
          stx.tx.receivingAddress,
          UInt256(stx.tx.value),
          senderAddress
        )
      else
        BlockPreparator.calcFloorDataGas(
          stx.tx.payload,
          evmConfigForTx.transactionBaseCost(stx.tx.receivingAddress, UInt256(stx.tx.value), senderAddress)
        )

    // `tx_gas_used = max(tx_gas_used_after_refund, calldata_floor_gas_cost)` (EIP-8037, unchanged in shape
    // from EIP-7623). This is what the sender pays and what the receipt accumulates.
    val executionGasToPayToMiner =
      if eip7623Active || evmConfigForTx.amsterdamEnabled then executionGasBase.max(GasAmount(floorDataGas))
      else executionGasBase

    // ── EIP-8037 / EIP-7778: the block's two dimensions ──────────────────────
    //
    // THE divergence. The header reports a MAXIMUM over the two dimensions; receipts report a SUM of
    // per-transaction totals. On an Amsterdam block these legitimately disagree — block 41 carries header
    // 183,600 and receipt 326,947 simultaneously — and an implementation that derives either from the
    // other is wrong on one of them.
    //
    // EIP-7778 is why the execution term is computed BEFORE refunds: refunds must not reduce the gas
    // counted toward the block limit, though they still reduce what the sender pays above.
    val txStateGas: BigInt = resultWithAuthRefund.evmStateGasUsed
    val txExecutionGas: BigInt =
      if !evmConfigForTx.amsterdamEnabled then executionGasToPayToMiner.value
      else
        val gasUsedBeforeRefund =
          gasLimit.value - resultWithAuthRefund.gasRemaining - resultWithAuthRefund.stateGasReservoir
        (gasUsedBeforeRefund - txStateGas).max(floorDataGas)

    val totalGasToRefund = gasLimit - executionGasToPayToMiner

    // Only the calldata floor can charge more than the gas limit, and only when tx.gas < floor — which EIP-7623 (ETH
    // Prague, ETC Olympia) and EIP-7976 (Amsterdam) make INVALID, and StdSignedTransactionValidator rejects before
    // anything executes. A negative refund here therefore means a transaction reached execution without that check
    // (#1438). Carried on, `refundAmount.toUInt256` below would wrap it modulo 2^256 and silently credit the sender
    // almost 2^256 wei, or debit it past its upfront payment. Fail loudly instead.
    if totalGasToRefund < GasAmount.Zero then
      throw new IllegalStateException(
        s"transaction ${stx.hash.toHex} would be charged $executionGasToPayToMiner gas, above its gas limit $gasLimit " +
          s"(calldata floor $floorDataGas): a transaction whose gas limit is below its calldata floor is invalid and " +
          "must be rejected before execution"
      )

    // Upfront in `updateSenderAccountBeforeExecution` is gasLimit * effectiveGasPrice
    // (post-EIP-1559: NOT maxFeePerGas — see comment there). So the refund only
    // needs to return the unused portion: (gasLimit - executionGas) * effectiveGasPrice.
    // No maxFee overpay to undo.
    val refundAmount = totalGasToRefund.value * gasPrice
    val refundGasFn = pay(senderAddress, refundAmount.toUInt256, withTouch = false)
    // EIP-1559: miner receives only the priority fee (effectiveGasPrice - baseFee).
    // The baseFee portion is burned on ETH chains, or credited to treasury on ETC (ECIP-1111).
    val minerGasPrice = blockHeader.baseFee match
      case Some(baseFee) if gasPrice.toBigInt >= baseFee => UInt256(gasPrice.toBigInt - baseFee)
      case Some(_)                                       => UInt256.Zero // effectiveGasPrice < baseFee: no priority fee
      case None                                          => gasPrice
    val payMinerForGasFn =
      pay(
        Address(blockHeader.beneficiary),
        (executionGasToPayToMiner.value * minerGasPrice).toUInt256,
        withTouch = true
      )

    val worldAfterPayments = refundGasFn.andThen(payMinerForGasFn)(resultWithErrorHandling.world)
    // EIP-7928: execution-specs `disburse_gas_fees` credits the coinbase on every transaction, a zero fee included.
    BlockAccessRecorder.account(accessRecorder, Address(blockHeader.beneficiary))

    // EIP-4844 blob gas cost is charged UPFRONT in updateSenderAccountBeforeExecution
    // (so BALANCE/SELFBALANCE within the VM see the correct post-upfront value). No
    // post-execution deduction needed here — would double-charge.
    val worldAfterBlobGas = worldAfterPayments

    // EIP-8246: from Amsterdam a self-destructed account keeps its balance; every earlier ETH fork and every ETC fork
    // still deletes it outright, balance included.
    val deleteAccountsFn =
      if amsterdamActive then clearSelfDestructedAccounts(resultWithErrorHandling.addressesToDelete)
      else deleteAccounts(resultWithErrorHandling.addressesToDelete)
    val deleteTouchedAccountsFn = deleteEmptyTouchedAccounts
    val persistStateFn = InMemoryWorldStateProxy.persistState

    val world2 = deleteAccountsFn.andThen(deleteTouchedAccountsFn).andThen(persistStateFn)(worldAfterBlobGas)

    if DebugTrace.enabledForTx(blockHeader.number.value, stx.hash.toHex) then
      val tx = stx.tx
      val accessList = Transaction.accessList(tx)
      val authListSize = tx match
        case sct: SetCodeTransaction => sct.authorizationList.size
        case _                       => 0
      val evmConfig = EvmConfig.forBlock(blockHeader.number.value, blockchainConfig)
      val intrinsicGas = evmConfig.calcTransactionIntrinsicGas(
        tx.payload,
        tx.isContractInit,
        accessList,
        authListSize,
        tx.receivingAddress,
        UInt256(tx.value),
        senderAddress
      )

      val toOrCreate = tx.receivingAddress.map(_.toString).getOrElse("CREATE")
      val isCreate = tx.isContractInit
      val returnDataSize = result.returnData.size
      val codeDepositCost = if isCreate then evmConfig.calcCodeDepositCost(result.returnData) else 0
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

    log.debug(s"""Transaction ${stx.hash.toHex} execution end. Summary:
         | - Error: ${result.error}.
         | - Total Gas to Refund: $totalGasToRefund
         | - Execution gas paid to miner: $executionGasToPayToMiner""".stripMargin)

    TxResult(
      world2,
      executionGasToPayToMiner.value,
      resultWithErrorHandling.logs,
      result.returnData,
      result.error,
      executionGasUsed = txExecutionGas,
      stateGasUsed = txStateGas
    )

  /** EIP-7928: the reads execution-specs `create_evm` makes before the top-level frame exists, in its order.
    *
    *   1. Every authority whose signature recovered, chain id and nonce bound permitting (`validate_authorization`
    *      reads it before the code and nonce checks) — up to and including the tuple whose charge ran out of gas.
    *   1. Unless the authorizations ran out of gas first: the recipient — the call target
    *      (`resolve_delegated_code_address`, or the value-transfer existence check before it), or the creation address
    *      (`account_deployable`).
    *   1. A call target's EIP-7702 delegation target, once its access charge is paid (`get_account(code_address)`):
    *      short of it, `ProgramContext.preExecutionOutOfGas` is set. A delegated account has code, so no
    *      account-creation charge can come between.
    *
    * The sender (`check_transaction`) and the coinbase (`disburse_gas_fees`) are recorded by `executeTransaction`.
    */
  private def recordPreExecutionReads(
      stx: SignedTransaction,
      senderAddress: Address,
      worldAfterAuths: InMemoryWorldStateProxy,
      context: PC,
      recoveredAuthorities: Set[Address],
      authorizationsOutOfGas: Boolean
  )(recorder: BlockAccessRecorder): Unit =
    recoveredAuthorities.foreach(recorder.recordAccount)
    if !authorizationsOutOfGas then
      stx.tx.receivingAddress match
        case None => recorder.recordAccount(worldAfterAuths.createAddress(senderAddress))
        case Some(recipient) =>
          recorder.recordAccount(recipient)
          if !context.preExecutionOutOfGas && context.evmConfig.eip7702Enabled then
            SetCodeTransaction.parseDelegation(worldAfterAuths.getCode(recipient)).foreach(recorder.recordAccount)

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
      acumReceipts: Seq[Receipt] = Nil,
      acumExecutionGas: BigInt = 0,
      acumStateGas: BigInt = 0,
      // EIP-7928: the block's access-list builder, on an Amsterdam block only. Each executed transaction is folded in
      // at block access index `receipts so far + 1`, which is its position among the block's transactions.
      accessList: Option[BlockAccessListBuilder] = None
  )(implicit blockchainConfig: BlockchainConfig): Either[TxsExecutionError, BlockResult] =
    signedTransactions match
      case Nil =>
        // Three counters, not one. `acumGas` feeds receipts (a SUM); the other two feed the header
        // (a MAXIMUM over dimensions). See BlockResult.
        Right(
          BlockResult(
            worldState = world,
            executionGasUsed = acumExecutionGas,
            stateGasUsed = acumStateGas,
            receipts = acumReceipts
          )
        )

      case Seq(stx, otherStxs*) =>
        // EIP-4844: upfront balance check must include blob-gas cost too —
        // otherwise a sender pre-funded with exactly gasLimit*maxFee + blobCost
        // passes the check but underflows when the upfront deduction runs.
        val blobGasCost = stx.tx match
          case bt: com.chipprbots.ethereum.domain.BlobTransaction =>
            val blobGasUsed = BigInt(bt.blobVersionedHashes.size) * BigInt(131072)
            val blobBaseFee = blockHeader.excessBlobGas
              .map(eg => BlobGasUtils.getBlobGasPrice(eg, blockHeader.unixTimestamp, blockchainConfig))
              .getOrElse(BigInt(1))
            blobGasUsed * blobBaseFee
          case _ => BigInt(0)
        val upfrontCost = UInt256(calculateUpfrontCost(stx.tx).toBigInt + blobGasCost)
        val senderAddress = SignedTransaction.getSender(stx)

        val accountDataOpt = senderAddress
          .map { address =>
            world
              .getAccount(address)
              .map(a => (a, address))
              .getOrElse((Account.empty(blockchainConfig.accountStartNonce), address))
          }
          .toRight(TransactionSignatureError)

        // All three counters: EIP-8037 checks block capacity per dimension (execution, state), not against the
        // receipt sum `acumGas`. Before Amsterdam the execution counter equals `acumGas` and the state one is 0.
        val validatedStx = for
          accData <- accountDataOpt
          _ <- signedTxValidator.validate(
            stx,
            accData._1,
            blockHeader,
            upfrontCost,
            acumGas,
            acumExecutionGas,
            acumStateGas
          )
        yield accData

        validatedStx match
          case Right((account, address)) =>
            val accessRecorder = accessList.map(_ => new BlockAccessRecorder)
            val TxResult(newWorld, gasUsed, logs, _, vmError, txExecutionGas, txStateGas) =
              executeTransaction(stx, address, blockHeader, world.saveAccount(address, account), accessRecorder)
            for
              builder <- accessList
              recorder <- accessRecorder
            do builder.addIndex(acumReceipts.size + 1L, recorder, world, newWorld)

            // spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-658.md
            val transactionOutcome =
              if blockHeader.number.value >= blockchainConfig.forkBlockNumbers.byzantiumBlockNumber ||
                blockHeader.number.value >= blockchainConfig.forkBlockNumbers.atlantisBlockNumber
              then if vmError.isDefined then FailureOutcome else SuccessOutcome
              else HashOutcome(newWorld.stateRootHash)

            val legacyReceipt = LegacyReceipt(
              postTransactionStateHash = transactionOutcome,
              cumulativeGasUsed = acumGas + gasUsed,
              logsBloomFilter =
                com.chipprbots.ethereum.domain.BloomFilter(com.chipprbots.ethereum.ledger.BloomFilter.create(logs)),
              logs = logs
            )
            val receipt = stx.tx match
              case _: LegacyTransaction         => legacyReceipt
              case _: TransactionWithAccessList => Type01Receipt(legacyReceipt)
              case _: TransactionWithDynamicFee => Type02Receipt(legacyReceipt)
              case _: BlobTransaction           => Type03Receipt(legacyReceipt)
              case _: SetCodeTransaction        => Type04Receipt(legacyReceipt)

            log.debug(s"Receipt generated for tx ${stx.hash.toHex}, $receipt")

            executeTransactions(
              otherStxs,
              newWorld,
              blockHeader,
              receipt.cumulativeGasUsed,
              acumReceipts :+ receipt,
              acumExecutionGas + txExecutionGas,
              acumStateGas + txStateGas,
              accessList
            )
          case Left(error) =>
            Left(
              TxsExecutionError(
                stx,
                StateBeforeFailure(world, acumGas, acumReceipts, acumExecutionGas, acumStateGas),
                error.toString
              )
            )

  @tailrec
  final private[ledger] def executePreparedTransactions(
      signedTransactions: Seq[SignedTransaction],
      world: InMemoryWorldStateProxy,
      blockHeader: BlockHeader,
      acumGas: BigInt = 0,
      acumReceipts: Seq[Receipt] = Nil,
      executed: Seq[SignedTransaction] = Nil,
      acumExecutionGas: BigInt = 0,
      acumStateGas: BigInt = 0
  )(implicit blockchainConfig: BlockchainConfig): (BlockResult, Seq[SignedTransaction]) =

    val result =
      executeTransactions(signedTransactions, world, blockHeader, acumGas, acumReceipts, acumExecutionGas, acumStateGas)

    result match
      case Left(TxsExecutionError(stx, StateBeforeFailure(worldState, gas, receipts, execGas, stateGas), reason)) =>
        log.debug(s"failure while preparing block because of $reason in transaction with hash ${stx.hash.toHex}")
        val txIndex = signedTransactions.indexWhere(tx => tx.hash == stx.hash)
        executePreparedTransactions(
          signedTransactions.drop(txIndex + 1),
          worldState,
          blockHeader,
          gas,
          receipts,
          executed ++ signedTransactions.take(txIndex),
          execGas,
          stateGas
        )
      case Right(br) => (br, executed ++ signedTransactions)

  def prepareBlock(
      evmCodeStorage: EvmCodeStorage,
      block: Block,
      parent: BlockHeader,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PreparedBlock =

    val initialWorld =
      initialWorldStateBeforeExecution.getOrElse(
        InMemoryWorldStateProxy(
          evmCodeStorage = evmCodeStorage,
          mptStorage = blockchain.getReadOnlyMptStorage(),
          // Mined blocks must answer BLOCKHASH exactly as every importer will: by the ancestry of the block being
          // built (core-geth GetHashFn), not by the canonical index. See AncestorBlockHashes.
          getBlockHashByNumber = AncestorBlockHashes.forBlock(block.header, blockchainReader),
          accountStartNonce = blockchainConfig.accountStartNonce,
          stateRootHash = parent.stateRoot.value,
          noEmptyAccounts = EvmConfig.forBlock(block.header.number.value, blockchainConfig).noEmptyAccounts,
          ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
        )
      )

    val prepared = executePreparedTransactions(block.body.transactionList, initialWorld, block.header)

    prepared match
      case (execResult @ BlockResult(resultingWorldStateProxy, _, _, _, _, _), txExecuted) =>
        val worldToPersist = payBlockReward(block, resultingWorldStateProxy)
        val worldPersisted = InMemoryWorldStateProxy.persistState(worldToPersist)
        PreparedBlock(
          block.copy(body = block.body.copy(transactionList = txExecuted)),
          execResult,
          worldPersisted.stateRootHash,
          worldPersisted
        )

  /** Apply an authorization list in order and return `(world, refund, warmAuthorities)`, following go-ethereum's
    * `stateTransition.applyAuthorization`:
    *   - the intrinsic charge is PER_EMPTY_ACCOUNT_COST (25,000) per tuple; a tuple that is APPLIED to an authority
    *     that already exists refunds PER_EMPTY_ACCOUNT_COST - PER_AUTH_BASE_COST (12,500). A skipped tuple refunds
    *     nothing — even when its authority exists (EEST `test_nonce_validity[nonce=0,account_nonce=1]`: 70,431, not
    *     57,931);
    *   - every authority that is RECOVERED is added to accessed_addresses (EIP-7702 step 4), whether or not the tuple
    *     then applies, so the transaction's first touch of it is warm (100) rather than cold (2,600).
    */
  private def applyAuthorizationsWithRefund(
      authList: List[SetCodeAuthorization],
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): (InMemoryWorldStateProxy, BigInt, Set[Address]) =
    authList.foldLeft((world, BigInt(0), Set.empty[Address])) { case ((w, refund, warm), auth) =>
      processAuthorization(auth, w) match
        case AuthorizationOutcome.Skipped            => (w, refund, warm)
        case AuthorizationOutcome.Invalid(authority) => (w, refund, warm + authority)
        case AuthorizationOutcome.Applied(authority, newWorld, existed) =>
          (newWorld, if existed then refund + BigInt(25000 - 12500) else refund, warm + authority)
    }

  /** EIP-2780 authorization processing at Amsterdam (execution-specs `set_delegation`): the list is applied IN ORDER
    * and every valid tuple is charged, as it is applied, against the top frame's gas meter — `gasLeft` is the
    * execution-gas grant and `reservoir` the state-gas reservoir, and a state charge draws from the reservoir first.
    *
    * Each tuple is validated against the world the tuples before it left, so a second tuple for the same authority with
    * nonce n+1 is valid once the first has applied. A valid tuple pays, in execution-specs' order:
    *   - `STATE_BYTES_PER_NEW_ACCOUNT x CPSB` STATE gas when the authority's account does not EXIST (existence, as
    *     `account_exists`; an existing empty account pays nothing);
    *   - `ACCOUNT_WRITE` EXECUTION gas when the transaction has not paid for a write to the authority yet: `paidWrites`
    *     holds the sender (TX_BASE_COST) and a value-bearing recipient (TX_VALUE_COST), and every authority charged
    *     here joins it, so a repeated authority pays once;
    *   - `STATE_BYTES_PER_AUTH_BASE x CPSB` STATE gas when it sets a non-zero target on an authority that held no
    *     indicator BEFORE the transaction and has not had one set earlier in it. Clearing never refunds: set, clear,
    *     set pays once; clear then set pays once too (the clear set nothing).
    *
    * The first charge the meter cannot cover ends the list there: the authorities after it are never loaded, and the
    * caller rolls the whole pre-execution phase back. The returned totals then INCLUDE that charge, so the frame's
    * aggregate check (`ProgramContext.preExecutionOutOfGas`) sees exactly the shortfall the meter saw.
    *
    * `world` is the world after the sender's nonce and fee update: that update never touches code, so `world` also
    * answers "did the authority hold an indicator before the transaction?" (execution-specs `get_pre_state_account`).
    */
  private[ledger] def applyAmsterdamAuthorizations(
      authList: List[SetCodeAuthorization],
      world: InMemoryWorldStateProxy,
      paidWrites: Set[Address],
      gasLeft: BigInt,
      reservoir: BigInt
  )(implicit blockchainConfig: BlockchainConfig): AmsterdamAuthorizations =

    @tailrec
    def charge(
        meter: AuthorizationMeter,
        charges: List[AuthorizationCharge],
        executionGas: BigInt,
        stateGas: BigInt
    ): (Option[AuthorizationMeter], BigInt, BigInt) =
      charges match
        case Nil => (Some(meter), executionGas, stateGas)
        case AuthorizationCharge.Execution(amount) :: rest =>
          meter.chargeExecution(amount) match
            case Some(next) => charge(next, rest, executionGas + amount, stateGas)
            case None       => (None, executionGas + amount, stateGas)
        case AuthorizationCharge.State(amount) :: rest =>
          meter.chargeState(amount) match
            case Some(next) => charge(next, rest, executionGas, stateGas + amount)
            case None       => (None, executionGas, stateGas + amount)

    @tailrec
    def loop(
        remaining: List[SetCodeAuthorization],
        applied: AmsterdamAuthorizations,
        meter: AuthorizationMeter,
        paid: Set[Address],
        delegationSetFor: Set[Address]
    ): AmsterdamAuthorizations =
      remaining match
        case Nil => applied
        case auth :: rest =>
          processAuthorization(auth, applied.world) match
            case AuthorizationOutcome.Skipped => loop(rest, applied, meter, paid, delegationSetFor)
            case AuthorizationOutcome.Invalid(authority) =>
              loop(
                rest,
                applied.copy(warmAuthorities = applied.warmAuthorities + authority),
                meter,
                paid,
                delegationSetFor
              )
            case AuthorizationOutcome.Applied(authority, newWorld, authorityExisted) =>
              val setsIndicator = auth.address != Address(0L)
              val charges = List(
                AuthorizationCharge.State(if authorityExisted then BigInt(0) else AmsterdamGas.GasNewAccount),
                AuthorizationCharge.Execution(
                  if paid.contains(authority) then BigInt(0) else AmsterdamGas.AccountWrite
                ),
                AuthorizationCharge.State(
                  if setsIndicator && !delegationSetFor.contains(authority) &&
                    !SetCodeTransaction.isDelegation(world.getCode(authority))
                  then AmsterdamGas.GasAuthBase
                  else BigInt(0)
                )
              )
              val warm = applied.warmAuthorities + authority
              charge(meter, charges, applied.executionGas, applied.stateGas) match
                case (None, executionGas, stateGas) =>
                  applied.copy(
                    warmAuthorities = warm,
                    executionGas = executionGas,
                    stateGas = stateGas,
                    outOfGas = true
                  )
                case (Some(next), executionGas, stateGas) =>
                  loop(
                    rest,
                    AmsterdamAuthorizations(newWorld, warm, executionGas, stateGas, outOfGas = false),
                    next,
                    paid + authority,
                    if setsIndicator then delegationSetFor + authority else delegationSetFor
                  )

    loop(
      authList,
      AmsterdamAuthorizations(world, Set.empty, BigInt(0), BigInt(0), outOfGas = false),
      AuthorizationMeter(gasLeft, reservoir),
      paidWrites,
      Set.empty
    )

  /** EIP-7702 steps 1-3 (go-ethereum `validateAuthorization` up to `auth.Authority()`): the checks that decide whether
    * an authority can be recovered at all. `None` means the tuple is skipped WITHOUT warming anything.
    *
    *   1. `chain_id` is 0 or the current chain id; 2. `nonce < 2**64 - 1` (EIP-2681): the authority's nonce is bumped
    *      on success, so 2**64 - 1 would overflow; 3. the signature values are canonical — `y_parity` in {0, 1}, `0 < r
    *      < n`, `0 < s <= n/2` — and recover to a public key. go-ethereum: `crypto.ValidateSignatureValues(v, r, s,
    *      homestead = true)`.
    */
  private def recoverAuthority(
      auth: SetCodeAuthorization
  )(implicit blockchainConfig: BlockchainConfig): Option[Address] =
    import com.chipprbots.ethereum.crypto.ECDSASignature
    import com.chipprbots.ethereum.rlp.{encode, PrefixedRLPEncodable, RLPList}
    import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
    import com.chipprbots.ethereum.rlp.RLPImplicits.given

    val chainIdOk = auth.chainId == 0 || auth.chainId == blockchainConfig.chainId.value
    val nonceOk = auth.nonce < BlockPreparator.AuthorizationNonceLimit
    val sigValuesOk =
      (auth.v == 0 || auth.v == 1) &&
        auth.r > 0 && auth.r < BlockPreparator.Secp256k1N &&
        auth.s > 0 && auth.s <= BlockPreparator.Secp256k1HalfN
    if !(chainIdOk && nonceOk && sigValuesOk) then None
    else
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
      val rawV = if auth.v == 0 then ECDSASignature.negativePointSign else ECDSASignature.positivePointSign
      val ecdsaSig = ECDSASignature(auth.r, auth.s, BigInt(rawV))
      ecdsaSig.publicKey(sigHash).flatMap { key =>
        val addrBytes = com.chipprbots.ethereum.crypto.kec256(key).slice(12, 32)
        if addrBytes.length == Address.Length then Some(Address(addrBytes)) else None
      }

  /** One EIP-7702 authorization tuple, processed against the world as it stands after the tuples before it. */
  private def processAuthorization(
      auth: SetCodeAuthorization,
      world: InMemoryWorldStateProxy
  )(implicit blockchainConfig: BlockchainConfig): AuthorizationOutcome =
    recoverAuthority(auth) match
      case None                => AuthorizationOutcome.Skipped
      case Some(authorityAddr) =>
        // Step 5: the authority has no code, or only a delegation indicator.
        val code = world.getCode(authorityAddr)
        if code.nonEmpty && !SetCodeTransaction.isDelegation(code) then AuthorizationOutcome.Invalid(authorityAddr)
        else
          // Step 6: the authority's nonce equals the tuple's.
          val existing = world.getAccount(authorityAddr)
          val account = existing.getOrElse(Account.empty(blockchainConfig.accountStartNonce))
          if account.nonce != UInt256(auth.nonce) then AuthorizationOutcome.Invalid(authorityAddr)
          else
            // Steps 8-9: bump the nonce, then set (or, for the zero address, clear) the delegation indicator.
            val w1 = world.saveAccount(authorityAddr, account.copy(nonce = account.nonce + 1))
            val w2 =
              if auth.address == Address(0L) then w1.saveCode(authorityAddr, ByteString.empty)
              else w1.saveCode(authorityAddr, SetCodeTransaction.addressToDelegation(auth.address))
            AuthorizationOutcome.Applied(authorityAddr, w2, authorityExisted = existing.isDefined)

/** Outcome of one EIP-7702 authorization tuple (go-ethereum `validateAuthorization` / `applyAuthorization`). */
private[ledger] enum AuthorizationOutcome:
  /** Rejected before the authority was recovered (chain id, nonce overflow, signature): nothing is warmed. */
  case Skipped

  /** Authority recovered — and therefore warmed — but the tuple is skipped (authority has code, or nonce mismatch). */
  case Invalid(authority: Address)

  /** Tuple applied; `authorityExisted` decides the PER_EMPTY_ACCOUNT_COST - PER_AUTH_BASE_COST refund (pre-Amsterdam)
    * and the NEW_ACCOUNT state charge (Amsterdam).
    */
  case Applied(authority: Address, world: InMemoryWorldStateProxy, authorityExisted: Boolean)

/** Amsterdam's EIP-2780 authorization processing, as far as it got (`BlockPreparator.applyAmsterdamAuthorizations`).
  *
  * @param world
  *   the world after every tuple that was applied
  * @param warmAuthorities
  *   every authority recovered so far — warm for the rest of the transaction, valid tuple or not
  * @param executionGas
  *   ACCOUNT_WRITE charges; when `outOfGas`, including the one that did not fit
  * @param stateGas
  *   NEW_ACCOUNT and AUTH_BASE charges; when `outOfGas`, including the one that did not fit
  * @param outOfGas
  *   a charge exceeded the meter, so the list stopped there and the pre-execution phase fails
  */
final private[ledger] case class AmsterdamAuthorizations(
    world: InMemoryWorldStateProxy,
    warmAuthorities: Set[Address],
    executionGas: BigInt,
    stateGas: BigInt,
    outOfGas: Boolean
)

/** The top frame's gas meter while EIP-2780 charges the authorizations, before the frame exists (execution-specs
  * `charge_gas_from_meter` / `charge_state_gas_from_meter`). `None` is an out-of-gas.
  */
final private[ledger] case class AuthorizationMeter(gasLeft: BigInt, reservoir: BigInt):
  def chargeExecution(amount: BigInt): Option[AuthorizationMeter] =
    if gasLeft >= amount then Some(copy(gasLeft = gasLeft - amount)) else None

  /** Reservoir first; the rest spills into `gasLeft`. */
  def chargeState(amount: BigInt): Option[AuthorizationMeter] =
    if reservoir >= amount then Some(copy(reservoir = reservoir - amount))
    else if reservoir + gasLeft >= amount then Some(AuthorizationMeter(gasLeft - (amount - reservoir), BigInt(0)))
    else None

private[ledger] enum AuthorizationCharge:
  case Execution(amount: BigInt)
  case State(amount: BigInt)

object BlockPreparator:

  /** EIP-7702 / EIP-2681: an authorization's nonce must be strictly below 2**64 - 1, because a successful authorization
    * bumps the authority's nonce and 2**64 - 1 has no successor.
    */
  val AuthorizationNonceLimit: BigInt = (BigInt(1) << 64) - 1

  /** secp256k1 group order n, and n/2 (the EIP-2 upper bound on `s`). */
  val Secp256k1N: BigInt =
    BigInt("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
  val Secp256k1HalfN: BigInt = Secp256k1N >> 1

  /** EIP-7623: Calculate floor data gas for a transaction. Floor ensures calldata-heavy transactions pay a minimum gas
    * cost. tokens = nonzero_bytes * 4 + zero_bytes floorDataGas = 21000 + tokens * 10
    */
  def calcFloorDataGas(payload: ByteString): BigInt = calcFloorDataGas(payload, BigInt(21000))

  /** EIP-7623's floor with an explicit base: `baseCost + (nonzero_bytes * 4 + zero_bytes) * 10`.
    *
    * Pre-Amsterdam only. Amsterdam replaces the whole floor, not just its base — EIP-7976's 64 gas per byte and
    * EIP-7981's access-list data cost on EIP-2780's decomposed base — with
    * [[com.chipprbots.ethereum.vm.AmsterdamGas.calldataFloorGas]].
    */
  def calcFloorDataGas(payload: ByteString, baseCost: BigInt): BigInt =
    val zeroBytes = payload.count(_ == 0)
    val nonZeroBytes = payload.length - zeroBytes
    val tokens = nonZeroBytes * 4 + zeroBytes
    baseCost + tokens * 10

  /** Whether EIP-7623's calldata floor applies to a block: ETH from the Prague timestamp, ETC from the Olympia block
    * (ECIP-1121).
    *
    * One definition for the two places that must agree: the floor [[BlockPreparator.executeTransaction]] charges, and
    * the validity rule `tx.gas >= floor` that `StdSignedTransactionValidator` enforces. A transaction the charge could
    * take above its gas limit is therefore always one the validator has already rejected.
    *
    * Do NOT test `number >= olympiaBlockNumber` alone: hive and the shipped ETH chain configs map London to
    * `olympiaBlockNumber`, which would apply the floor on ETH from London on.
    */
  def eip7623Active(blockNumber: BigInt, timestamp: Timestamp)(implicit blockchainConfig: BlockchainConfig): Boolean =
    blockchainConfig.isPragueTimestamp(timestamp) ||
      (blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETC &&
        blockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber)
