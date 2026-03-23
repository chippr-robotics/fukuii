package com.chipprbots.ethereum.consensus.blocks

import java.time.Instant

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators._
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain.SignedTransaction.FirstByteOfAddress
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.utils._
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.testing.Tags._

class BlockGeneratorSpec extends AnyFlatSpec with Matchers with Logger {
  implicit val testContext: IORuntime = IORuntime.global

  "BlockGenerator" should "generate correct block with empty transactions" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    val pendingBlock: PendingBlock =
      blockGenerator.generateBlock(bestBlock.get, Nil, Address(testAddress), blockGenerator.emptyX, None).pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("eb49a2da108d63de"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("a91c44e62d17005c4b22f6ed116f485ea30d8b63f2429745816093b304eb4f73"))
    val miningTimestamp = 1508751768

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )
    validators.blockHeaderValidator.validate(
      fullBlock.header,
      blockchainReader.getBlockHeaderByHash
    ) shouldBe Right(BlockHeaderValid)
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "generate correct block with transactions" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(bestBlock.get, Seq(signedTransaction), Address(testAddress), blockGenerator.emptyX, None)
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("4139b957dae0488d"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("dc25764fb562d778e5d1320f4c3ba4b09021a2603a0816235e16071e11f342ea"))
    val miningTimestamp = 1508752265

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )
    validators.blockHeaderValidator.validate(
      fullBlock.header,
      blockchainReader.getBlockHeaderByHash
    ) shouldBe Right(BlockHeaderValid)
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "be possible to simulate transaction, on world returned with pending block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(bestBlock.get, Seq(signedTransaction), Address(testAddress), blockGenerator.emptyX, None)
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("4139b957dae0488d"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("dc25764fb562d778e5d1320f4c3ba4b09021a2603a0816235e16071e11f342ea"))
    val miningTimestamp = 1508752265

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )

    // Import Block, to create some existing state
    consensusAdapter.evaluateBranchBlock(fullBlock).unsafeRunSync()

    // Create new pending block, with updated stateRootHash
    val pendBlockAndState: PendingBlockAndState = blockGenerator.generateBlock(
      blockchainReader.getBestBlock().get,
      Seq(signedTransaction),
      Address(testAddress),
      blockGenerator.emptyX,
      None
    )

    // Try to simulate transaction, on world with updated stateRootHash, but not updated storages
    assertThrows[MPTException] {
      stxLedger.simulateTransaction(signedTransactionWithAddress, pendBlockAndState.pendingBlock.block.header, None)
    }

    // Try to simulate transaction, on world with all changes stored in caches
    val simulationResult: TxResult = stxLedger.simulateTransaction(
      signedTransactionWithAddress,
      pendBlockAndState.pendingBlock.block.header,
      Some(pendBlockAndState.worldState)
    )

    // Check if transaction was valid
    simulationResult.vmError shouldBe None
  }

  it should "filter out failing transactions" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(
          bestBlock.get,
          Seq(signedTransaction, duplicatedSignedTransaction),
          Address(testAddress),
          blockGenerator.emptyX,
          None
        )
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("12cb47f9208d1e81"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("908471b57f2d3e70649f9ce0c9c318d61146d3ce19f70d2f94309f135b87b64a"))
    val miningTimestamp = 1508752389

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )
    validators.blockHeaderValidator.validate(
      fullBlock.header,
      blockchainReader.getBlockHeaderByHash
    ) shouldBe Right(BlockHeaderValid)

    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(signedTransaction)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "filter out transactions exceeding block gas limit and include correct transactions" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    val txWitGasTooBigGasLimit: SignedTransaction = SignedTransaction
      .sign(
        transaction.copy(gasLimit = BigInt(2).pow(100000), nonce = signedTransaction.tx.nonce + 1),
        keyPair,
        Some(BigInt(0x3d))
      )

    val transactions: Seq[SignedTransaction] =
      Seq(txWitGasTooBigGasLimit, signedTransaction, duplicatedSignedTransaction)
    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(bestBlock.get, transactions, Address(testAddress), blockGenerator.emptyX, None)
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("38026e10fb18b458"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("806f26f0efb12a0c0c16e587984227186c46f25fc4e76698a68996183edf2cf1"))
    val miningTimestamp = 1508752492

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )

    validators.blockHeaderValidator.validate(
      fullBlock.header,
      blockchainReader.getBlockHeaderByHash
    ) shouldBe Right(BlockHeaderValid)
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(signedTransaction)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "generate block before eip155 and filter out chain specific tx" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      chainId = 0x3d,
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      monetaryPolicyConfig =
        MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
      // unused
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      gasTieBreaker = false,
      ethCompatibleStorage = true,
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        frontierBlockNumber = 0,
        homesteadBlockNumber = 1150000,
        difficultyBombPauseBlockNumber = 3000000,
        difficultyBombContinueBlockNumber = 5000000,
        difficultyBombRemovalBlockNumber = 5900000
      )
    )

    override lazy val blockExecution =
      new BlockExecution(
        blockchain,
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        blockValidation
      )

    val generalTx: SignedTransaction = SignedTransaction.sign(transaction, keyPair, None)
    val specificTx: SignedTransaction =
      SignedTransaction.sign(transaction.copy(nonce = transaction.nonce + 1), keyPair, Some(BigInt(0x3d)))

    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(bestBlock.get, Seq(generalTx, specificTx), Address(testAddress), blockGenerator.emptyX, None)
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("48381cb0cd40936a"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("dacd96cf5dbc662fa113c73319fcdc7d6e7053571432345b936fd221c1e18d42"))
    val miningTimestamp = 1499952002

    val fullBlock: Block =
      pendingBlock.block.copy(
        header = pendingBlock.block.header.copy(
          nonce = minedNonce,
          mixHash = minedMixHash,
          unixTimestamp = miningTimestamp,
          gasLimit = generatedBlockGasLimit
        )
      )
    validators.blockHeaderValidator.validate(
      fullBlock.header,
      blockchainReader.getBlockHeaderByHash
    ) shouldBe Right(BlockHeaderValid)
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(generalTx)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "generate correct block with (without empty accounts) after EIP-161" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        frontierBlockNumber = 0,
        homesteadBlockNumber = 1150000,
        difficultyBombPauseBlockNumber = 3000000,
        difficultyBombContinueBlockNumber = 5000000,
        difficultyBombRemovalBlockNumber = 5900000,
        eip161BlockNumber = 0
      ),
      chainId = 0x3d,
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      monetaryPolicyConfig =
        MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
      // unused
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      gasTieBreaker = false,
      ethCompatibleStorage = true
    )

    override lazy val blockExecution =
      new BlockExecution(
        blockchain,
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        blockValidation
      )

    val transaction1: LegacyTransaction = LegacyTransaction(
      nonce = 0,
      gasPrice = 1,
      gasLimit = 1000000,
      receivingAddress = None,
      value = 0,
      payload = ByteString.empty
    )
    val generalTx: SignedTransaction = SignedTransaction.sign(transaction1, keyPair, None)

    val generatedBlock: PendingBlock =
      blockGenerator
        .generateBlock(bestBlock.get, Seq(generalTx), Address(testAddress), blockGenerator.emptyX, None)
        .pendingBlock

    blockExecution.executeAndValidateBlock(generatedBlock.block, true) shouldBe a[Right[_, Seq[Receipt]]]
  }

  it should "generate block after eip155 and allow both chain specific and general transactions" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    val generalTx: SignedTransaction =
      SignedTransaction.sign(transaction.copy(nonce = transaction.nonce + 1), keyPair, None)

    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(
          bestBlock.get,
          Seq(generalTx, signedTransaction),
          Address(testAddress),
          blockGenerator.emptyX,
          None
        )
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("39bd50fcbde30b18"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("c77dae7cef6c685896ed6b8026466a2e6338b8bc5f182e2dd7a64cf7da9c7d1b"))
    val miningTimestamp = 1499951223

    val fullBlock: Block =
      pendingBlock.block.copy(
        header = pendingBlock.block.header.copy(
          nonce = minedNonce,
          mixHash = minedMixHash,
          unixTimestamp = miningTimestamp,
          gasLimit = generatedBlockGasLimit
        )
      )
    validators.blockHeaderValidator.validate(fullBlock.header, blockchainReader.getBlockHeaderByHash) shouldBe Right(
      BlockHeaderValid
    )
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(signedTransaction, generalTx)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "include consecutive transactions from single sender" taggedAs (UnitTest, ConsensusTest) in new TestSetup {
    val nextTransaction: SignedTransaction =
      SignedTransaction.sign(transaction.copy(nonce = signedTransaction.tx.nonce + 1), keyPair, Some(BigInt(0x3d)))

    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(
          bestBlock.get,
          Seq(nextTransaction, signedTransaction),
          Address(testAddress),
          blockGenerator.emptyX,
          None
        )
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("8f88ec20f1be482f"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("247a206abc088487edc1697fcaceb33ad87b55666e438129b7048bb08c8ed88f"))
    val miningTimestamp = 1499721182

    val fullBlock: Block =
      pendingBlock.block.copy(
        header = pendingBlock.block.header.copy(
          nonce = minedNonce,
          mixHash = minedMixHash,
          unixTimestamp = miningTimestamp,
          gasLimit = generatedBlockGasLimit
        )
      )
    validators.blockHeaderValidator.validate(fullBlock.header, blockchainReader.getBlockHeaderByHash) shouldBe Right(
      BlockHeaderValid
    )
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(signedTransaction, nextTransaction)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "filter out failing transaction from the middle of tx list" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    val nextTransaction: SignedTransaction =
      SignedTransaction.sign(transaction.copy(nonce = signedTransaction.tx.nonce + 1), keyPair, Some(BigInt(0x3d)))

    val privateKeyWithNoEthere: BigInt =
      BigInt(1, Hex.decode("584a31be275195585603ddd05a53d16fae9deafba67213b6060cec9f16e44cae"))

    val failingTransaction: LegacyTransaction = LegacyTransaction(
      nonce = 0,
      gasPrice = 1,
      gasLimit = txGasLimit,
      receivingAddress = Address(testAddress),
      value = txTransfer,
      payload = ByteString.empty
    )
    val signedFailingTransaction: SignedTransaction =
      SignedTransaction.sign(failingTransaction, keyPairFromPrvKey(privateKeyWithNoEthere), Some(BigInt(0x3d)))

    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(
          bestBlock.get,
          Seq(nextTransaction, signedFailingTransaction, signedTransaction),
          Address(testAddress),
          blockGenerator.emptyX,
          None
        )
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("8f88ec20f1be482f"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("247a206abc088487edc1697fcaceb33ad87b55666e438129b7048bb08c8ed88f"))
    val miningTimestamp = 1499721182

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )
    validators.blockHeaderValidator.validate(fullBlock.header, blockchainReader.getBlockHeaderByHash) shouldBe Right(
      BlockHeaderValid
    )
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(signedTransaction, nextTransaction)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  it should "include transaction with higher gas price if nonce is the same" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup {
    val txWitSameNonceButLowerGasPrice: SignedTransaction = SignedTransaction
      .sign(transaction.copy(gasPrice = signedTransaction.tx.gasPrice - 1), keyPair, Some(BigInt(0x3d)))

    val pendingBlock: PendingBlock =
      blockGenerator
        .generateBlock(
          bestBlock.get,
          Seq(txWitSameNonceButLowerGasPrice, signedTransaction),
          Address(testAddress),
          blockGenerator.emptyX,
          None
        )
        .pendingBlock

    // mined with fukuii + ethminer
    val minedNonce: ByteString = ByteString(Hex.decode("14d7000ac544b38e"))
    val minedMixHash: ByteString =
      ByteString(Hex.decode("270f6b2618c5bef6a188397927129c803e5fd41c85492835486832f6825a8d78"))
    val miningTimestamp = 1508752698

    val fullBlock: Block = pendingBlock.block.copy(
      header = pendingBlock.block.header.copy(
        nonce = minedNonce,
        mixHash = minedMixHash,
        unixTimestamp = miningTimestamp,
        gasLimit = generatedBlockGasLimit
      )
    )
    validators.blockHeaderValidator.validate(fullBlock.header, blockchainReader.getBlockHeaderByHash) shouldBe Right(
      BlockHeaderValid
    )
    blockExecution.executeAndValidateBlock(fullBlock) shouldBe a[Right[_, Seq[Receipt]]]
    fullBlock.body.transactionList shouldBe Seq(signedTransaction)
    fullBlock.header.extraData shouldBe headerExtraData
  }

  trait TestSetup extends EphemBlockchainTestSetup {

    val testAddress = 42
    val privateKey: BigInt = BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b"))
    lazy val keyPair: AsymmetricCipherKeyPair = keyPairFromPrvKey(privateKey)
    lazy val pubKey: Array[Byte] = keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail
    lazy val address: Address = Address(crypto.kec256(pubKey).drop(FirstByteOfAddress))

    val txGasLimit = 21000
    val txTransfer = 9000
    val transaction: LegacyTransaction = LegacyTransaction(
      nonce = 0,
      gasPrice = 1,
      gasLimit = txGasLimit,
      receivingAddress = Address(testAddress),
      value = txTransfer,
      payload = ByteString.empty
    )

    val typedTransaction: TypedTransaction = TransactionWithAccessList(
      chainId = 61, // ethereum classic mainnet
      nonce = 0,
      gasPrice = 1,
      gasLimit = txGasLimit,
      receivingAddress = Address(testAddress),
      value = txTransfer,
      payload = ByteString.empty,
      accessList = Nil
    )

    lazy val signedTransaction: SignedTransaction =
      SignedTransaction.sign(transaction, keyPair, Some(BigInt(0x3d)))
    lazy val duplicatedSignedTransaction: SignedTransaction =
      SignedTransaction.sign(transaction.copy(gasLimit = 2), keyPair, Some(BigInt(0x3d)))

    lazy val signedTypedTransaction: SignedTransaction =
      SignedTransaction.sign(typedTransaction, keyPair, Some(BigInt(0x3d)))

    lazy val signedTransactionWithAddress: SignedTransactionWithSender =
      SignedTransactionWithSender(signedTransaction, Address(keyPair))

    lazy val signedTypedTransactionWithAddress: SignedTransactionWithSender =
      SignedTransactionWithSender(signedTypedTransaction, Address(keyPair))

    val baseBlockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        homesteadBlockNumber = 1150000,
        eip155BlockNumber = 0,
        difficultyBombPauseBlockNumber = 3000000,
        difficultyBombContinueBlockNumber = 5000000,
        difficultyBombRemovalBlockNumber = 5900000
      ),
      chainId = 0x3d,
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      monetaryPolicyConfig =
        MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
      // unused
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      gasTieBreaker = false,
      ethCompatibleStorage = true
    )
    implicit override lazy val blockchainConfig: BlockchainConfig = baseBlockchainConfig

    val genesisDataLoader =
      new GenesisDataLoader(
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.stateStorage
      )
    genesisDataLoader.loadGenesisData()

    val bestBlock: Option[Block] = blockchainReader.getBestBlock()

    lazy val blockTimestampProvider = new FakeBlockTimestampProvider

    val blockCacheSize: Int = 30
    val headerExtraData: ByteString = ByteString("mined with etc scala")

    override lazy val validators: ValidatorsExecutor = powValidators

    override lazy val miningConfig: MiningConfig =
      buildMiningConfig().copy(headerExtraData = headerExtraData, blockCacheSize = blockCacheSize)

    lazy val blockGenerator: TestBlockGenerator =
      mining.blockGenerator.withBlockTimestampProvider(blockTimestampProvider)

    override lazy val blockValidation =
      new BlockValidation(mining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
    override lazy val blockExecution =
      new BlockExecution(
        blockchain,
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        blockValidation
      )

    val generatedBlockGasLimit = 16733003
  }
}

class FakeBlockTimestampProvider extends BlockTimestampProvider {
  private var timestamp = Instant.now.getEpochSecond

  def advance(seconds: Long): Unit = timestamp += seconds

  override def getEpochSecond: Long = timestamp
}
