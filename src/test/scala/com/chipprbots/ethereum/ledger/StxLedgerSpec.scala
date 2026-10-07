package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.*

class StxLedgerSpec extends AnyFlatSpec with Matchers with Logger:

  "StxLedger" should "correctly estimate minimum gasLimit to run transaction which throws" taggedAs (
    UnitTest,
    StateTest
  ) in new ScenarioSetup:

    /** Transaction requires gasLimit equal to 121825, but actual gas used due to refund is equal 42907. Our
      * simulateTransaction properly estimates gas usage to 42907, but requires at least 121825 gas to make that
      * simulation
      *
      * After some investigation it seems that semantics required from estimateGas is that it should return minimal gas
      * required to sendTransaction, not minimal gas used by transaction. (it is implemented that way in parity and
      * geth)
      */

    val tx: LegacyTransaction = LegacyTransaction(0, GasPrice.Zero, lastBlockGasLimit, existingAddress, 0, sendData)
    val fakeSignature: ECDSASignature = ECDSASignature(0, 0, 0)
    val stx: SignedTransaction = SignedTransaction(tx, fakeSignature)
    val stxFromAddress: SignedTransactionWithSender = SignedTransactionWithSender(stx, fromAddress)

    val simulationResult: TxResult =
      stxLedger.simulateTransaction(stxFromAddress, genesisHeader, None)
    val executionResult: TxResult =
      mining.blockPreparator.executeTransaction(stx, fromAddress, genesisHeader, worldWithAccount)
    val estimationResult: BigInt =
      stxLedger.binarySearchGasEstimation(stxFromAddress, genesisHeader, None)

    // Check that gasUsed from simulation and execution are equal
    simulationResult.gasUsed shouldEqual executionResult.gasUsed

    // Check that estimation result is equal to expected minimum
    estimationResult shouldEqual minGasLimitRequiredForFailingTransaction

    // Execute transaction with gasLimit lesser by one that estimated minimum
    val errorExecResult: TxResult = mining.blockPreparator.executeTransaction(
      stx.copy(tx = Transaction.withGasLimit(GasAmount(estimationResult - 1))(stx.tx)),
      fromAddress,
      genesisHeader,
      worldWithAccount
    )

    // Check if running with gasLimit < estimatedMinimum return error
    errorExecResult.vmError shouldBe defined

  /** Pins hive `graphql` simulator fixture `04_eth_estimateGas_contractDeploy` (`estimateGas` on a contract-creation
    * `CallData`, block 32 of the simulator's own `testBlockchain.blocks` — pure Frontier: that fixture's
    * `testGenesis.json` sets `homesteadBlock` through `londonBlock` all to 33, so block 32 predates every fork).
    * expected `0x1b551` = 111953; fukuii returned `0xa959` = 43353, short by exactly 68600 = 343 (the deployed runtime
    * code's byte length) * 200 (`G_codedeposit`) — the code-deposit cost was silently omitted from the estimate.
    *
    * Root cause: `VM.saveNewContract` (VM.scala), pre-Homestead branch. Frontier's `exceptionalFailedCodeDeposit \=
    * false` makes a CREATE that cannot pay its code deposit a SUCCESS — gas kept, state kept, no code stored — so the
    * result carried `error = None` and nothing distinguished it from a creation that actually deployed. That let
    * `binarySearchGasEstimation` treat "ran the init code, produced runtime bytes, but couldn't afford to store them"
    * as an acceptable answer, so the search converged on the minimum gas to merely RUN the init code, never the minimum
    * to actually deploy it.
    *
    * Fix: `ProgramResult.codeDepositShortfall` — a plain boolean, deliberately NOT a `ProgramError`, set on that branch
    * and read ONLY by `binarySearchGasEstimation`. `error` stays `None`, which is what every consensus consumer keys
    * on, so block execution is unchanged by construction.
    *
    * The obvious-looking alternative — reporting a `ProgramError` here, as go-ethereum's `create()` appears to do — is
    * a consensus trap, and was briefly shipped and reverted. geth sets `ErrCodeStoreOutOfGas` inside `create()` but
    * `core/vm/instructions.go` `opCreate` then discards it ("if the ruleset is frontier we must ignore this error and
    * pretend the operation was successful"), so it never reaches the caller's stack value or the top-level `vmerr`.
    * fukuii's `CreateOp` has no such discard: a `Some` there makes a nested CREATE push 0 instead of the new address
    * and throw away the init code's state, and makes `calcTotalGasToRefund` drop the gas-refund counter. See
    * `FrontierCreateCodeDepositSpec` and the refund test below, which pin both halves.
    */
  it should "estimate the code-deposit cost for a pre-Homestead CREATE that can't afford to store its code" taggedAs (
    UnitTest,
    StateTest
  ) in new HiveGraphQLScenarioSetup:
    val initCode: ByteString = ByteString(
      Hex.decode(
        "608060405234801561001057600080fd5b50610157806100206000396000f30060806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680633bdab8bf146100515780639ae97baa14610068575b600080fd5b34801561005d57600080fd5b5061006661007f565b005b34801561007457600080fd5b5061007d6100b9565b005b7fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60016040518082815260200191505060405180910390a1565b7fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60026040518082815260200191505060405180910390a17fa53887c1eed04528e23301f55ad49a91634ef5021aa83a97d07fd16ed71c039a60036040518082815260200191505060405180910390a15600a165627a7a7230582010ddaa52e73a98c06dbcd22b234b97206c1d7ed64a7c048e10c2043a3d2309cb0029"
      )
    )
    val tx: LegacyTransaction = LegacyTransaction(0, GasPrice.Zero, lastBlockGasLimit, None, 0, initCode)
    val fakeSignature: ECDSASignature = ECDSASignature(0, 0, 0)
    val stx: SignedTransaction = SignedTransaction(tx, fakeSignature)
    val stxFromAddress: SignedTransactionWithSender = SignedTransactionWithSender(stx, fromAddress)

    val estimationResult: BigInt =
      stxLedger.binarySearchGasEstimation(stxFromAddress, block32Header, None)

    // THE pin: geth's measured answer for this exact fixture request.
    estimationResult shouldEqual BigInt(111953)
    estimationResult shouldEqual BigInt("1b551", 16)

    // Negative control, pinning the exact symptom: the deposit cost (343-byte runtime * 200 gas/byte) must be
    // included, not silently dropped.
    val runtimeCodeSize = 343
    val codeDepositCost = runtimeCodeSize * 200
    (estimationResult - codeDepositCost) shouldEqual BigInt(43353)

  /** Companion to the estimation pin above, guarding the OTHER half of the same VM branch: what a pre-Homestead
    * code-deposit shortfall must do to a transaction that actually lands in a block.
    *
    * Frontier's `exceptionalFailedCodeDeposit = false` makes this case a SUCCESS — gas kept, state kept, no code stored
    * — so `ProgramResult.error` stays `None`, and `BlockPreparator.calcTotalGasToRefund` therefore takes its `case
    * None` arm and CREDITS the accumulated gas-refund counter. go-ethereum agrees by construction: `create()` skips
    * `RevertToSnapshot` on this path, so the journaled refund counter survives into `refundGas`.
    *
    * Signalling the shortfall as a `ProgramError` instead breaks exactly this: `calcTotalGasToRefund` dispatches on
    * `error.map(_.useWholeGas)`, and a `Some(false)` drops `min(gasUsed / 2, gasRefund)` silently. The sender is
    * overcharged, the receipt's `cumulativeGasUsed` moves, the header's `gasUsed` moves, and the block hash moves — on
    * ETC mainnet blocks 0 - 1,149,999. Nothing else in the suite covers it.
    */
  it should "credit the gas refund for a pre-Homestead CREATE that cannot pay its code deposit" taggedAs (
    UnitTest,
    StateTest
  ) in new HiveGraphQLScenarioSetup:
    // SSTORE(0, 1) then SSTORE(0, 0): sets a slot and clears it, accruing R_sclear = 15,000 (Frontier). Then
    // RETURN 1,000 zero bytes, whose 200,000 code deposit the 60,000-gas transaction cannot possibly afford.
    val refundingInitCode: ByteString = ByteString(
      Hex.decode(
        "6001600055" + // PUSH1 1, PUSH1 0, SSTORE   -> slot 0 = 1 (G_sset 20,000)
          "6000600055" + // PUSH1 0, PUSH1 0, SSTORE -> slot 0 = 0 (G_sreset 5,000, R_sclear +15,000)
          "6103e86000f3" // PUSH2 0x03e8, PUSH1 0, RETURN -> 1,000 bytes of runtime code
      )
    )

    val refundTx: LegacyTransaction =
      LegacyTransaction(0, GasPrice.Zero, GasAmount(60000), None, 0, refundingInitCode)
    val refundStx: SignedTransaction = SignedTransaction(refundTx, ECDSASignature(0, 0, 0))

    val vmResult: PR =
      mining.blockPreparator.runVM(refundStx, fromAddress, block32Header, worldWithAccount)

    // The branch under test was actually reached, and reached as a SUCCESS.
    vmResult.error shouldBe None
    vmResult.codeDepositShortfall shouldBe true
    vmResult.gasRefund shouldEqual BigInt(15000)
    vmResult.gasRemaining should be > BigInt(0)

    val totalRefunded: BigInt =
      mining.blockPreparator.calcTotalGasToRefund(refundStx, vmResult, block32Header.number.value)

    // The refund counter is credited on top of the unspent gas. Expressed relationally rather than as a magic
    // constant: signalling this case as a ProgramError collapses `totalRefunded` to exactly `gasRemaining`.
    val gasUsedBeforeRefund: BigInt = refundTx.gasLimit.value - vmResult.gasRemaining
    val expectedRefundCredit: BigInt = (gasUsedBeforeRefund / 2).min(vmResult.gasRefund)
    expectedRefundCredit shouldEqual BigInt(15000)
    totalRefunded shouldEqual vmResult.gasRemaining + expectedRefundCredit
    totalRefunded should be > vmResult.gasRemaining

  it should "correctly estimate gasLimit for value transfer transaction" taggedAs (
    UnitTest,
    StateTest
  ) in new ScenarioSetup:
    val transferValue = 2

    val tx: LegacyTransaction =
      LegacyTransaction(
        0,
        GasPrice.Zero,
        lastBlockGasLimit,
        existingEmptyAccountAddres,
        transferValue,
        ByteString.empty
      )
    val fakeSignature: ECDSASignature = ECDSASignature(0, 0, 0)
    val stx: SignedTransaction = SignedTransaction(tx, fakeSignature)

    val executionResult: TxResult =
      mining.blockPreparator.executeTransaction(stx, fromAddress, genesisHeader, worldWithAccount)
    val estimationResult: BigInt =
      stxLedger.binarySearchGasEstimation(SignedTransactionWithSender(stx, fromAddress), genesisHeader, None)

    estimationResult shouldEqual executionResult.gasUsed

  it should "correctly simulate transaction on pending block when supplied prepared world" taggedAs (
    UnitTest,
    StateTest
  ) in new ScenarioSetup:
    val transferValue = 2

    val tx: LegacyTransaction =
      LegacyTransaction(
        0,
        GasPrice.Zero,
        lastBlockGasLimit,
        existingEmptyAccountAddres,
        transferValue,
        ByteString.empty
      )
    val fakeSignature: ECDSASignature = ECDSASignature(0, 0, 0)
    val stxFromAddress: SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction(tx, fakeSignature), fromAddress)

    val newBlock: Block =
      genesisBlock.copy(header = block.header.copy(number = BlockNumber(1), parentHash = BlockHash(genesisHash)))

    val preparedBlock: PreparedBlock =
      mining.blockPreparator.prepareBlock(
        storagesInstance.storages.evmCodeStorage,
        newBlock,
        genesisBlock.header,
        None
      )
    val preparedWorld: InMemoryWorldStateProxy = preparedBlock.updatedWorld
    val header: BlockHeader =
      preparedBlock.block.header.copy(number = BlockNumber(1), stateRoot = TrieRoot(preparedBlock.stateRootHash))

    /** All operations in `ledger.prepareBlock` are performed on ReadOnlyWorldStateProxy so there are no updates in
      * underlying storages, but StateRootHash returned by it `expect` this updates to be in storages. It leads to
      * MPTexception.RootNotFound
      */

    assertThrows[MPTException](stxLedger.simulateTransaction(stxFromAddress, header, None))

    /** Solution is to return this ReadOnlyWorldStateProxy from `ledger.prepareBlock` along side with preparedBlock and
      * perform simulateTransaction on this world.
      */
    val result: TxResult =
      stxLedger.simulateTransaction(stxFromAddress, header, Some(preparedWorld))

    result.vmError shouldBe None

  // migrated from old LedgerSpec
  "binaryChop" should "properly find minimal required gas limit to execute transaction" in new BinarySimulationChopSetup:
    testGasValues.foreach { minimumRequiredGas =>
      StxLedger.binaryChop[TxError](minimalGas, maximalGas)(
        mockTransaction(minimumRequiredGas)
      ) shouldEqual minimumRequiredGas
    }

// scalastyle:off magic.number line.size.limit
trait ScenarioSetup extends EphemBlockchainTestSetup:

  implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Empty.copy(
      eip155BlockNumber = 0,
      eip161BlockNumber = 0,
      frontierBlockNumber = 0,
      homesteadBlockNumber = 0,
      difficultyBombPauseBlockNumber = 0,
      difficultyBombContinueBlockNumber = 0,
      eip150BlockNumber = 0,
      eip160BlockNumber = 0,
      eip106BlockNumber = 0,
      byzantiumBlockNumber = 0,
      constantinopleBlockNumber = 0,
      istanbulBlockNumber = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 0,
      phoenixBlockNumber = 0,
      petersburgBlockNumber = 0
    ),
    chainId = ChainId(0x03),
    networkId = 1,
    maxCodeSize = None,
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    accountStartNonce = UInt256.Zero,
    monetaryPolicyConfig = MonetaryPolicyConfig(5, 0, 0, 0),
    daoForkConfig = None,
    gasTieBreaker = false,
    ethCompatibleStorage = true,
    bootstrapNodes = Set()
  )

  override lazy val stxLedger =
    new StxLedger(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      this
    )

  val emptyWorld: InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

  val existingAddress: Address = Address(10)
  val existingAccount: Account = Account(nonce = UInt256.Zero, balance = UInt256(10))

  val existingEmptyAccountAddres: Address = Address(20)
  val existingEmptyAccount: Account = Account.empty()

  /** Failing code which mess up with gas estimation contract FunkyGasPattern { string public field; function
    * SetField(string value) { // This check will screw gas estimation! Good, good! if (msg.gas < 100000) { throw; }
    * field = value; } }
    *
    * @note
    *   Example from https://github.com/ethereum/go-ethereum/pull/3587
    */
  val failingCode: ByteString = ByteString(
    Hex.decode(
      "60606040526000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff16806323fcf32a146100495780634f28bf0e146100a0575b610000565b346100005761009e600480803590602001908201803590602001908080601f01602080910402602001604051908101604052809392919081815260200183838082843782019150505050505091905050610136565b005b34610000576100ad6101eb565b60405180806020018281038252838181518152602001915080519060200190808383600083146100fc575b8051825260208311156100fc576020820191506020810190506020830392506100d8565b505050905090810190601f1680156101285780820380516001836020036101000a031916815260200191505b509250505060405180910390f35b620186a05a101561014657610000565b8060009080519060200190828054600181600116156101000203166002900490600052602060002090601f016020900481019282601f1061019257805160ff19168380011785556101c0565b828001600101855582156101c0579182015b828111156101bf5782518255916020019190600101906101a4565b5b5090506101e591905b808211156101e15760008160009055506001016101c9565b5090565b50505b50565b60008054600181600116156101000203166002900480601f0160208091040260200160405190810160405280929190818152602001828054600181600116156101000203166002900480156102815780601f1061025657610100808354040283529160200191610281565b820191906000526020600020905b81548152906001019060200180831161026457829003601f168201915b5050505050815600a165627a7a7230582075b8ec2ccf191572d3bfdd93dee7d107668265f37d7918e48132b1ba16df17320029"
    )
  )

  val sendData: ByteString = ByteString(
    Hex.decode(
      "23fcf32a0000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000000564756e6e6f000000000000000000000000000000000000000000000000000000"
    )
  )

  val fromBalance: UInt256 = UInt256(10)
  val fromAddress: Address = Address(0)
  val startAccount: Account = Account.empty().increaseBalance(fromBalance)

  val worldWithAccount: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
    emptyWorld
      .saveAccount(fromAddress, startAccount)
      .saveAccount(existingEmptyAccountAddres, existingEmptyAccount)
      .saveAccount(existingAddress, existingAccount)
      .saveCode(existingAddress, failingCode)
  )

  val someGenesisBlock: Array[Byte] =
    Hex.decode(
      "f901fcf901f7a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347948888f1f195afa192cfee860698584c030f4c9db1a07dba07d6b448a186e9612e5f737d1c909dce473e53199901a302c00646d523c1a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421b90100000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000008302000080832fefd8808454c98c8142a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421880102030405060708c0c0"
    )

  val minGasLimitRequiredForFailingTransaction: BigInt = 121825

  val block: Block = someGenesisBlock.toBlock
  val genesisBlock: Block =
    block.copy(header =
      block.header.copy(stateRoot = TrieRoot(worldWithAccount.stateRootHash), gasLimit = GasAmount(1000000))
    )
  val genesisHash: ByteString = genesisBlock.header.hash.value
  val genesisHeader: BlockHeader = genesisBlock.header
  val genesisWeight: ChainWeight = ChainWeight.zero.increase(genesisHeader)
  val lastBlockGasLimit: GasAmount = genesisBlock.header.gasLimit

  blockchainWriter
    .storeBlock(genesisBlock)
    .and(blockchainWriter.storeReceipts(BlockHash(genesisHash), Nil))
    .and(blockchainWriter.storeChainWeight(BlockHash(genesisHash), genesisWeight))
    .commit()

/** Reproduces the fork state of hive's OWN `ethereum/graphql` simulator fixture chain — NOT the shared `execution-apis`
  * chain that `rpc-compat` uses (that was the initial, WRONG assumption investigating this defect; see the
  * false-premise note in `04_eth_estimateGas_contractDeploy` in ForkIdHiveRpcCompatSpec-adjacent history).
  * `simulators/ethereum/graphql/init/testGenesis.json` (ethereum/hive, current as of 2026-08-31) declares `chainId: 1`
  * and sets EVERY fork field — `homesteadBlock` through `londonBlock` — to `33`. The simulator's own
  * `testBlockchain.blocks` (35 blocks) confirms this: block 33 is the first block whose header carries a `baseFee`
  * field (1 gwei) and an 17-field RLP shape; blocks 0-32 are 15-field, no-baseFee headers. So block 32, which the
  * `04_eth_estimateGas_contractDeploy` fixture queries, runs under PURE FRONTIER rules — no Homestead, no EIP-158/161,
  * no Byzantium, nothing. `ForkBlockNumbers.Empty` (frontier=0, everything else at the `Long.MaxValue` sentinel) is
  * exactly that.
  */
trait HiveGraphQLScenarioSetup extends ScenarioSetup:
  implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Empty,
    chainId = ChainId(1),
    networkId = 1,
    maxCodeSize = None,
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    accountStartNonce = UInt256.Zero,
    monetaryPolicyConfig = MonetaryPolicyConfig(5, 0, 0, 0),
    daoForkConfig = None,
    gasTieBreaker = false,
    ethCompatibleStorage = true,
    bootstrapNodes = Set()
  )

  /** Block 32 header: pure Frontier, no baseFee field at all (matches the real header shape — `HefEmpty` is correct
    * here, not a zeroed `HefPostOlympia`, because pre-London headers don't carry the field). Timestamp 1444660029 is
    * the fixture's own block-32 timestamp (irrelevant to gas cost here since no timestamp-gated fork exists this early
    * anyway, but kept faithful to the source chain for anyone re-deriving other numbers from this fixture later).
    */
  val block32Header: BlockHeader = genesisHeader.copy(
    number = BlockNumber(32),
    unixTimestamp = Timestamp(1444660029L),
    extraFields = BlockHeader.HeaderExtraFields.HefEmpty
  )
