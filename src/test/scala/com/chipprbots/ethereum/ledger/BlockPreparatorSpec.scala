package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString
import org.apache.pekko.util.ByteString.empty as bEmpty

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor2
import org.scalatest.prop.TableFor4
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionError.TransactionSignatureError
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValid
import com.chipprbots.ethereum.consensus.validators.SignedTransactionValidator
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.InvalidJump
import com.chipprbots.ethereum.vm.InvalidOpCode
import com.chipprbots.ethereum.vm.OutOfGas
import com.chipprbots.ethereum.vm.ProgramError
import com.chipprbots.ethereum.vm.RevertOccurs
import com.chipprbots.ethereum.vm.StackOverflow
import com.chipprbots.ethereum.vm.StackUnderflow

// scalastyle:off magic.number
class BlockPreparatorSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks:

  "BlockPreparator" should {

    "correctly change the nonce" when {
      "executing a tx that results in contract creation" taggedAs (UnitTest, StateTest) in new TestSetup:

        val tx: LegacyTransaction =
          defaultTx.copy(
            gasPrice = defaultGasPrice,
            gasLimit = GasAmount(defaultGasLimit.toBigInt),
            receivingAddress = None,
            payload = ByteString.empty
          )

        val stx: SignedTransactionWithSender = SignedTransactionWithSender(
          SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value)),
          Address(originKeyPair)
        )

        val header: BlockHeader = defaultBlockHeader.copy(beneficiary = minerAddress.bytes)

        val postTxWorld: InMemoryWorldStateProxy =
          mining.blockPreparator
            .executeTransaction(stx.tx, stx.senderAddress, header, worldWithMinerAndOriginAccounts)
            .worldState

        postTxWorld.getGuaranteedAccount(originAddress).nonce shouldBe UInt256(initialOriginNonce + 1)

      "executing a tx that results in a message call" taggedAs (UnitTest, StateTest) in new TestSetup:

        val tx: LegacyTransaction = defaultTx.copy(
          gasPrice = defaultGasPrice,
          gasLimit = GasAmount(defaultGasLimit.toBigInt),
          receivingAddress = Some(originAddress),
          payload = ByteString.empty
        )

        val stx: SignedTransactionWithSender = SignedTransactionWithSender(
          SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value)),
          Address(originKeyPair)
        )

        val header: BlockHeader = defaultBlockHeader.copy(beneficiary = minerAddress.bytes)

        val postTxWorld: InMemoryWorldStateProxy =
          mining.blockPreparator
            .executeTransaction(stx.tx, stx.senderAddress, header, worldWithMinerAndOriginAccounts)
            .worldState

        postTxWorld.getGuaranteedAccount(originAddress).nonce shouldBe UInt256(initialOriginNonce + 1)
    }

    "properly assign stateRootHash" when {
      "before byzantium block (exclusive)" taggedAs (UnitTest, StateTest) in new TestSetup:

        val tx: LegacyTransaction = defaultTx.copy(
          gasPrice = defaultGasPrice,
          gasLimit = GasAmount(defaultGasLimit.toBigInt),
          receivingAddress = None,
          payload = ByteString.empty
        )
        val stx: SignedTransactionWithSender = SignedTransactionWithSender(
          SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value)),
          Address(originKeyPair)
        )
        val header: BlockHeader =
          defaultBlockHeader.copy(number = BlockNumber(blockchainConfig.forkBlockNumbers.byzantiumBlockNumber - 1))

        val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
          mining.blockPreparator.executeTransactions(Seq(stx.tx), initialWorld, header)

        result shouldBe a[Right[?, BlockResult]]
        result.map { br =>
          br.receipts.last.postTransactionStateHash shouldBe a[HashOutcome]
        }

      "after byzantium block (inclusive) if operation is a success" taggedAs (UnitTest, StateTest) in new TestSetup:

        val tx: LegacyTransaction = defaultTx.copy(
          gasPrice = defaultGasPrice,
          gasLimit = GasAmount(defaultGasLimit.toBigInt),
          receivingAddress = None,
          payload = ByteString.empty
        )
        val stx: SignedTransaction = SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value))
        val header: BlockHeader =
          defaultBlockHeader.copy(
            beneficiary = minerAddress.bytes,
            number = BlockNumber(blockchainConfig.forkBlockNumbers.byzantiumBlockNumber)
          )

        val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
          mining.blockPreparator.executeTransactions(Seq(stx), initialWorld, header)

        result shouldBe a[Right[?, BlockResult]]
        result.map(_.receipts.last.postTransactionStateHash shouldBe SuccessOutcome)

      "after byzantium block (inclusive) if operation is a failure" taggedAs (UnitTest, StateTest) in new TestSetup:

        val defaultsLogs: Seq[TxLogEntry] = Seq(defaultLog)

        lazy val mockVM =
          new MockVM(createResult(_, defaultGasLimit, defaultGasLimit, 0, Some(RevertOccurs), bEmpty, defaultsLogs))

        val testMining: Mining = newTestMining(vm = mockVM)

        val tx: LegacyTransaction = defaultTx.copy(
          gasPrice = GasPrice(defaultGasLimit.toBigInt),
          gasLimit = GasAmount(defaultGasLimit.toBigInt),
          receivingAddress = None,
          payload = ByteString.empty
        )
        val stx: SignedTransactionWithSender = SignedTransactionWithSender(
          SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value)),
          Address(originKeyPair)
        )
        val header: BlockHeader =
          defaultBlockHeader.copy(
            beneficiary = minerAddress.bytes,
            number = BlockNumber(blockchainConfig.forkBlockNumbers.byzantiumBlockNumber)
          )

        val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
          testMining.blockPreparator.executeTransactions(Seq(stx.tx), initialWorld, header)

        result shouldBe a[Right[?, BlockResult]]
        result.map(_.receipts.last.postTransactionStateHash shouldBe FailureOutcome)
    }

    "correctly calculate the total gas refund to be returned to the sender and paying for gas to the miner" taggedAs (
      UnitTest,
      StateTest
    ) in new TestSetup:

      val table: TableFor4[BigInt, BigInt, Option[ProgramError], BigInt] =
        Table[BigInt, BigInt, Option[ProgramError], BigInt](
          ("execGasUsed", "refundsFromVM", "maybeError", "gasUsed"),
          (25000, 20000, None, 25000 - 12500),
          (25000, 10000, None, 25000 - 10000),
          (125000, 10000, Some(OutOfGas), defaultGasLimit),
          (125000, 100000, Some(OutOfGas), defaultGasLimit),
          (125000, 100000, Some(RevertOccurs), 125000)
        )

      forAll(table) { (execGasUsed, gasRefundFromVM, error, gasUsed) =>
        val balanceDelta = UInt256(gasUsed * defaultGasPrice.value)

        val tx = defaultTx.copy(gasPrice = defaultGasPrice, gasLimit = GasAmount(defaultGasLimit.toBigInt))

        val stx = SignedTransactionWithSender(
          SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value)),
          Address(originKeyPair)
        )

        val header = defaultBlockHeader.copy(beneficiary = minerAddress.bytes)

        val mockVM = new MockVM(c =>
          createResult(
            context = c,
            gasUsed = execGasUsed,
            gasLimit = defaultGasLimit.toBigInt,
            gasRefund = gasRefundFromVM,
            error = error
          )
        )

        val execResult = mining
          .withVM(mockVM)
          .blockPreparator
          .executeTransaction(stx.tx, stx.senderAddress, header, worldWithMinerAndOriginAccounts)

        val postTxWorld = execResult.worldState

        execResult.gasUsed shouldEqual gasUsed
        postTxWorld.getBalance(originAddress) shouldEqual (initialOriginBalance - balanceDelta)
        postTxWorld.getBalance(minerAddress) shouldEqual (initialMinerBalance + balanceDelta)
      }

  }

  "clear logs only if vm execution results in an error" taggedAs (UnitTest, StateTest) in new TestSetup:

    val defaultsLogs: Seq[TxLogEntry] = Seq(defaultLog)

    val table: TableFor2[Option[ProgramError], Int] = Table[Option[ProgramError], Int](
      ("Execution Error", "Logs size"),
      (Some(InvalidOpCode(1)), 0),
      (Some(OutOfGas), 0),
      (Some(InvalidJump(23)), 0),
      (Some(StackOverflow), 0),
      (Some(StackUnderflow), 0),
      (None, defaultsLogs.size)
    )

    forAll(table) { (maybeError, logsSize) =>
      val initialOriginBalance: UInt256 = 1000000

      val initialOriginNonce = defaultTx.nonce

      val initialWorld = emptyWorld
        .saveAccount(originAddress, Account(nonce = UInt256(initialOriginNonce), balance = initialOriginBalance))

      val stx = SignedTransactionWithSender(
        SignedTransaction.sign(defaultTx, originKeyPair, Some(blockchainConfig.chainId.value)),
        Address(originKeyPair)
      )

      val mockVM = new MockVM(createResult(_, defaultGasLimit, defaultGasLimit, 0, maybeError, bEmpty, defaultsLogs))

      val testMining = newTestMining(vm = mockVM)

      val txResult =
        testMining.blockPreparator.executeTransaction(stx.tx, stx.senderAddress, defaultBlockHeader, initialWorld)

      txResult.logs.size shouldBe logsSize
    }

  "create sender account if it does not exists" taggedAs (UnitTest, StateTest) in new TestSetup:

    val inputData: ByteString = ByteString("the payload")

    val newAccountKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
    val newAccountAddress: Address =
      Address(kec256(newAccountKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail))

    override lazy val vm: VMImpl = new MockVM((pc: PC) =>
      createResult(pc, defaultGasLimit, defaultGasLimit, 0, None, returnData = ByteString("contract code"))
    )

    val tx: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice.Zero, receivingAddress = None, payload = inputData)
    val stx: SignedTransaction = SignedTransaction.sign(tx, newAccountKeyPair, Some(blockchainConfig.chainId.value))

    val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
      mining.blockPreparator.executeTransactions(
        Seq(stx),
        initialWorld,
        defaultBlockHeader
      )

    result shouldBe a[Right[?, BlockResult]]
    result.map(br => br.worldState.getAccount(newAccountAddress)) shouldBe Right(Some(Account(nonce = 1)))

  "remember executed transaction in case of many failures in the middle" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    val newAccountKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
    Address(kec256(newAccountKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail))

    override lazy val vm: VMImpl =
      new MockVM((pc: PC) => createResult(pc, defaultGasLimit, defaultGasLimit, 0, None, returnData = ByteString.empty))

    override lazy val validators: MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
      override val signedTransactionValidator: SignedTransactionValidator =
        new SignedTransactionValidator:
          def validate(
              stx: SignedTransaction,
              senderAccount: Account,
              blockHeader: BlockHeader,
              upfrontGasCost: UInt256,
              accumGasUsed: BigInt
          )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
            if stx.tx.receivingAddress.contains(Address(42)) then Right(SignedTransactionValid)
            else Left(TransactionSignatureError)

    val tx1: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice(42), receivingAddress = Some(Address(42)))
    val tx2: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice(43), receivingAddress = Some(Address(43)))
    val tx3: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice(43), receivingAddress = Some(Address(43)))
    val tx4: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice(42), receivingAddress = Some(Address(42)))
    val stx1: SignedTransaction = SignedTransaction.sign(tx1, newAccountKeyPair, Some(blockchainConfig.chainId.value))
    val stx2: SignedTransaction = SignedTransaction.sign(tx2, newAccountKeyPair, Some(blockchainConfig.chainId.value))
    val stx3: SignedTransaction = SignedTransaction.sign(tx3, newAccountKeyPair, Some(blockchainConfig.chainId.value))
    val stx4: SignedTransaction = SignedTransaction.sign(tx4, newAccountKeyPair, Some(blockchainConfig.chainId.value))

    val result: (BlockResult, Seq[SignedTransaction]) = mining.blockPreparator.executePreparedTransactions(
      Seq(stx1, stx2, stx3, stx4),
      initialWorld,
      defaultBlockHeader
    )

    val (blockResult, executedTxs) = result
    executedTxs shouldBe Seq(stx1, stx4)
    // Each restart after a failed tx resumes from the receipts and gas before it (#1675 made that accumulator a Vector):
    // one receipt per executed tx, in order, and the failed tx2 and tx3 add no gas. tx1 and tx4 are the same tx.
    val receipts = blockResult.receipts
    receipts shouldBe a[Vector[?]]
    receipts.size shouldBe 2
    val perTx = receipts.head.cumulativeGasUsed
    perTx should be > BigInt(0)
    receipts.map(_.cumulativeGasUsed) shouldBe Seq(perTx, 2 * perTx)
    blockResult.executionGasUsed shouldBe 2 * perTx // pre-Amsterdam, the execution counter is the receipt sum
    blockResult.stateGasUsed shouldBe 0

  "produce empty block if all txs fail" taggedAs (UnitTest, StateTest) in new TestSetup:
    val newAccountKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
    Address(kec256(newAccountKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail))

    override lazy val vm =
      new MockVM((pc: PC) => createResult(pc, defaultGasLimit, defaultGasLimit, 0, None, returnData = ByteString.empty))

    override lazy val validators: Mocks.MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
      override val signedTransactionValidator: SignedTransactionValidator =
        new SignedTransactionValidator:
          def validate(
              stx: SignedTransaction,
              senderAccount: Account,
              blockHeader: BlockHeader,
              upfrontGasCost: UInt256,
              accumGasUsed: BigInt
          )(implicit blockchainConfig: BlockchainConfig): Either[SignedTransactionError, SignedTransactionValid] =
            Left(TransactionSignatureError)

    val tx1: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice(42), receivingAddress = Some(Address(42)))
    val tx2: LegacyTransaction = defaultTx.copy(gasPrice = GasPrice(42), receivingAddress = Some(Address(42)))
    val stx1: SignedTransaction = SignedTransaction.sign(tx1, newAccountKeyPair, Some(blockchainConfig.chainId.value))
    val stx2: SignedTransaction = SignedTransaction.sign(tx2, newAccountKeyPair, Some(blockchainConfig.chainId.value))

    val result: (BlockResult, Seq[SignedTransaction]) =
      mining.blockPreparator.executePreparedTransactions(Seq(stx1, stx2), initialWorld, defaultBlockHeader)

    result match
      case (_, executedTxs) => executedTxs shouldBe Seq.empty

  // #1675: the receipts accumulator used to be a List grown with `:+`, which copies every earlier receipt — O(txs²) per
  // block — and the Amsterdam access-list index read its `.size` per tx. It is a Vector (append and size O(1)); what
  // it holds is unchanged: one receipt per executed tx, in block order.
  "accumulate the block's receipts in a Vector, one per tx, in tx order" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    val txCount = 20
    val stxs: Seq[SignedTransaction] = (0 until txCount).map { i =>
      val tx = defaultTx.copy(nonce = defaultTx.nonce + i, gasLimit = GasAmount(21000))
      SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value))
    }

    val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
      mining.blockPreparator.executeTransactions(stxs, initialWorld, defaultBlockHeader)

    result shouldBe a[Right[?, BlockResult]]
    val receipts = result.toOption.get.receipts
    receipts shouldBe a[Vector[?]]
    receipts.size shouldBe txCount
    // Cumulative gas rises with every tx: the receipts are in execution order, each appended once.
    receipts.map(_.cumulativeGasUsed).sliding(2).foreach { case Seq(a, b) => b should be > a; case _ => () }

  // migrated from old LedgerSpec
  "properly assign stateRootHash before byzantium block (exclusive)" in new TestSetup:

    val tx: LegacyTransaction = defaultTx.copy(
      gasPrice = defaultGasPrice,
      gasLimit = GasAmount(defaultGasLimit.toBigInt),
      receivingAddress = None,
      payload = ByteString.empty
    )
    val stx: SignedTransaction = SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value))
    val header: BlockHeader =
      defaultBlockHeader.copy(number = BlockNumber(blockchainConfig.forkBlockNumbers.byzantiumBlockNumber - 1))

    val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
      mining.blockPreparator.executeTransactions(Seq(stx), initialWorld, header)

    result shouldBe a[Right[?, BlockResult]]
    result.map { br =>
      br.receipts.last.postTransactionStateHash shouldBe a[HashOutcome]
    }

  "properly assign stateRootHash after byzantium block (inclusive) if operation is a success" in new TestSetup:

    val tx: LegacyTransaction = defaultTx.copy(
      gasPrice = defaultGasPrice,
      gasLimit = GasAmount(defaultGasLimit.toBigInt),
      receivingAddress = None,
      payload = ByteString.empty
    )
    val stx: SignedTransaction = SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value))
    val header: BlockHeader =
      defaultBlockHeader.copy(
        beneficiary = minerAddress.bytes,
        number = BlockNumber(blockchainConfig.forkBlockNumbers.byzantiumBlockNumber)
      )

    val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
      mining.blockPreparator.executeTransactions(Seq(stx), initialWorld, header)

    result shouldBe a[Right[?, BlockResult]]
    result.map(_.receipts.last.postTransactionStateHash shouldBe SuccessOutcome)

  "properly assign stateRootHash after byzantium block (inclusive) if operation is a failure" in new TestSetup:

    val defaultsLogs: Seq[TxLogEntry] = Seq(defaultLog)

    lazy val mockVM =
      new MockVM(createResult(_, defaultGasLimit, defaultGasLimit, 0, Some(RevertOccurs), bEmpty, defaultsLogs))

    val testMining: Mining = newTestMining(vm = mockVM)

    val tx: LegacyTransaction = defaultTx.copy(
      gasPrice = GasPrice(defaultGasLimit.toBigInt),
      gasLimit = GasAmount(defaultGasLimit.toBigInt),
      receivingAddress = None,
      payload = ByteString.empty
    )
    val stx: SignedTransaction = SignedTransaction.sign(tx, originKeyPair, Some(blockchainConfig.chainId.value))
    val header: BlockHeader =
      defaultBlockHeader.copy(
        beneficiary = minerAddress.bytes,
        number = BlockNumber(blockchainConfig.forkBlockNumbers.byzantiumBlockNumber)
      )

    val result: Either[BlockExecutionError.TxsExecutionError, BlockResult] =
      testMining.blockPreparator.executeTransactions(Seq(stx), initialWorld, header)

    result shouldBe a[Right[?, BlockResult]]
    result.map(_.receipts.last.postTransactionStateHash shouldBe FailureOutcome)

  "deductBlobGas" should {
    // Verifies that deductBlobGas routes through BlobGasUtils.getBlobGasPrice, which covers
    // EIP-7892 BPO1/BPO2 fractions. The old local computeBlobBaseFee only handled Cancun/Prague
    // and produced incorrect amounts post-BPO on Sepolia blocks.
    "burn the correct blob gas cost matching BlobGasUtils for a Prague block" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new TestSetup:
      implicit val pragueConfig: BlockchainConfig = blockchainConfig.copy(
        forkTimestamps = blockchainConfig.forkTimestamps.copy(
          cancunTimestamp = Some(0L),
          pragueTimestamp = Some(0L)
        )
      )

      val excessBlobGas = BigInt(1000000)
      val blockTs = 100L
      val numBlobs = 2
      val blobTx = BlobTransaction(
        chainId = pragueConfig.chainId.value,
        nonce = 1,
        maxPriorityFeePerGas = 1,
        maxFeePerGas = 1000,
        gasLimit = GasAmount(21000),
        receivingAddress = Some(receiverAddress),
        value = 0,
        payload = ByteString.empty,
        accessList = Nil,
        maxFeePerBlobGas = 1000,
        blobVersionedHashes = List.fill(numBlobs)(BlobVersionedHash(ByteString(Array.fill(32)(0.toByte))))
      )
      val stx = SignedTransaction(blobTx, ECDSASignature(0, 0, 0))
      val header = defaultBlockHeader.copy(
        unixTimestamp = Timestamp(blockTs),
        extraFields = HefPostCancun(
          baseFee = BigInt(1_000_000_000L),
          withdrawalsRoot = ByteString(new Array[Byte](32)),
          blobGasUsed = BigInt(0),
          excessBlobGas = excessBlobGas,
          parentBeaconBlockRoot = ByteString(new Array[Byte](32))
        )
      )

      val senderBalance = UInt256(BigInt(1000000000L))
      val world = emptyWorld.saveAccount(originAddress, Account(balance = senderBalance))

      val resultWorld = prep.deductBlobGas(stx, originAddress, header, world)

      val expectedFee = BlobGasUtils.getBlobGasPrice(excessBlobGas, Timestamp(blockTs), pragueConfig)
      val expectedBurned = expectedFee * BlobGasUtils.GAS_PER_BLOB * numBlobs
      val actualBalance = resultWorld.getGuaranteedAccount(originAddress).balance

      actualBalance shouldBe UInt256(senderBalance.toBigInt - expectedBurned)
  }
