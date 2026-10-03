package com.chipprbots.ethereum.ethtest

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.cache.AppCaches
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.storage.*
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.txExecTest.ScenarioSetup
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** Helper for executing blocks with the test infrastructure */
class EthereumTestHelper(using bc: BlockchainConfig) extends ScenarioSetup:

  implicit override lazy val blockchainConfig: BlockchainConfig = bc

  // Create storages with initial state
  override protected val testBlockchainStorages: BlockchainStorages =
    createEmptyStorages()

  private def createEmptyStorages(): BlockchainStorages =
    new BlockchainStorages with AppCaches with EphemDataSourceComponent:
      override val receiptStorage: ReceiptStorage = new ReceiptStorage(this.dataSource)
      override val blockAccessListStorage: BlockAccessListStorage = new BlockAccessListStorage(this.dataSource)
      override val evmCodeStorage: EvmCodeStorage = new EvmCodeStorage(this.dataSource)
      override val blockHeadersStorage: BlockHeadersStorage = new BlockHeadersStorage(this.dataSource)
      override val blockNumberMappingStorage: BlockNumberMappingStorage = new BlockNumberMappingStorage(this.dataSource)
      override val blockBodiesStorage: BlockBodiesStorage = new BlockBodiesStorage(this.dataSource)
      override val chainWeightStorage: ChainWeightStorage = new ChainWeightStorage(this.dataSource)
      override val transactionMappingStorage: TransactionMappingStorage = new TransactionMappingStorage(this.dataSource)
      override val appStateStorage: AppStateStorage = new AppStateStorage(this.dataSource)

      val nodeStorage: NodeStorage = new NodeStorage(this.dataSource)
      val pruningMode: PruningMode = ArchivePruning
      override val stateStorage: StateStorage =
        StateStorage(
          pruningMode,
          nodeStorage,
          new LruCache[NodeHash, HeapEntry](
            Config.inMemoryPruningNodeCacheConfig,
            Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
          )
        )

  /** Setup initial state and execute blocks using the same storage instance
    *
    * This ensures the initial state is persisted in the blockchain's storage before block execution begins, avoiding
    * the "Root node not found" error.
    */
  def setupAndExecuteTest(
      preState: Map[String, AccountState],
      blocks: Seq[TestBlock],
      genesisBlockHeader: Option[TestBlockHeader]
  ): Either[String, InMemoryWorldStateProxy] =
    try
      if blocks.isEmpty then
        return Right(
          InMemoryWorldStateProxy(
            evmCodeStorage = testBlockchainStorages.evmCodeStorage,
            mptStorage = testBlockchainStorages.stateStorage.getReadOnlyStorage,
            getBlockHashByNumber = (_: BigInt) => None,
            accountStartNonce = blockchainConfig.accountStartNonce,
            stateRootHash = Account.EmptyStorageRootHash.value,
            noEmptyAccounts = false,
            ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
          )
        )

      // Get the first block's number to determine which storage to use
      val _ = parseBigInt(blocks.head.blockHeader.number)

      // Step 1: Setup initial state using the backing storage for block 0
      val mptStorage = blockchain.getBackingMptStorage(0)
      var world = InMemoryWorldStateProxy(
        evmCodeStorage = testBlockchainStorages.evmCodeStorage,
        mptStorage = mptStorage,
        getBlockHashByNumber = (_: BigInt) => None,
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = Account.EmptyStorageRootHash.value,
        noEmptyAccounts = false,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )

      // Set up each account from pre-state
      preState.foreach { case (addressHex, accountState) =>
        val address = Address(ByteString(parseHex(addressHex)))
        val balance = UInt256(parseBigInt(accountState.balance))
        val nonce = UInt256(parseBigInt(accountState.nonce))
        val code = ByteString(parseHex(accountState.code))

        // Create account
        val account = Account(
          nonce = nonce,
          balance = balance,
          storageRoot = Account.EmptyStorageRootHash,
          codeHash = Account.EmptyCodeHash
        )

        // Save account
        world = world.saveAccount(address, account)

        // Save code if present
        if code.nonEmpty then world = world.saveCode(address, code)

        // Save storage if present
        accountState.storage.foreach { case (keyHex, valueHex) =>
          val key = parseBigInt(keyHex)
          val value = parseBigInt(valueHex)
          val storage = world.getStorage(address)
          val newStorage = storage.store(key, value)
          world = world.saveStorage(address, newStorage)
        }
      }

      // Persist the initial state into the blockchain's storage
      val persistedWorld = InMemoryWorldStateProxy.persistState(world)

      // Step 2: Execute blocks using the same storage
      executeBlocksWithInitialState(blocks, persistedWorld, genesisBlockHeader)
    catch
      case e: Exception =>
        Left(s"Failed to setup and execute test: ${e.getMessage}\n${e.getStackTrace.take(10).mkString("\n")}")

  /** Execute blocks and return final world state */
  private def executeBlocksWithInitialState(
      blocks: Seq[TestBlock],
      initialWorld: InMemoryWorldStateProxy,
      genesisBlockHeader: Option[TestBlockHeader]
  ): Either[String, InMemoryWorldStateProxy] =
    try
      if blocks.isEmpty then return Right(initialWorld)

      // Create the parent block (genesis) either from the test or synthesize one
      val genesisHeader = genesisBlockHeader match
        case Some(testGenesis) =>
          // Use the provided genesis block header from the test
          TestConverter.toBlockHeader(testGenesis)
        case None =>
          // Synthesize a genesis block for tests that don't provide one
          val firstTestBlock = blocks.head
          val firstBlockNumber = parseBigInt(firstTestBlock.blockHeader.number)
          val parentBlockNumber: BigInt = if firstBlockNumber > 0 then firstBlockNumber - 1 else BigInt(0)
          createParentBlockHeader(
            blockNumber = parentBlockNumber,
            stateRoot = initialWorld.stateRootHash,
            testBlock = firstTestBlock
          )

      // Store the genesis/parent block
      val genesisBlock = Block(genesisHeader, BlockBody(Seq.empty, Seq.empty))
      testBlockchainStorages.blockHeadersStorage.put(genesisHeader.hash.value, genesisHeader).commit()
      testBlockchainStorages.blockBodiesStorage.put(genesisHeader.hash.value, genesisBlock.body).commit()
      testBlockchainStorages.blockNumberMappingStorage
        .put(genesisHeader.number.value, genesisHeader.hash.value)
        .commit()

      // Also need to store chain weight for the genesis block
      testBlockchainStorages.chainWeightStorage.put(genesisHeader.hash.value, ChainWeight.zero).commit()

      // Create BlockExecution using the test infrastructure
      val syncConfig = Config.SyncConfig(Config.config)
      val blockQueue = BlockQueue(blockchainReader, syncConfig)
      val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
      val blockExecution = new BlockExecution(
        blockchain,
        blockchainReader,
        blockchainWriter,
        testBlockchainStorages.evmCodeStorage,
        mining.blockPreparator,
        blockValidation
      )

      // Execute each test block sequentially
      blocks.foreach { testBlock =>
        val block = convertTestBlockToBlock(testBlock)

        // Execute the block (use given instance explicitly)
        val result = blockExecution.executeAndValidateBlock(block)(using bc)

        result match
          case Right(receiptList) =>
            // Store the executed block
            testBlockchainStorages.blockHeadersStorage.put(block.header.hash.value, block.header).commit()
            testBlockchainStorages.blockBodiesStorage.put(block.header.hash.value, block.body).commit()
            testBlockchainStorages.blockNumberMappingStorage
              .put(block.header.number.value, block.header.hash.value)
              .commit()
            testBlockchainStorages.receiptStorage.put(block.header.hash.value, receiptList).commit()

            // Update chain weight
            val parentWeight = testBlockchainStorages.chainWeightStorage
              .get(block.header.parentHash.value)
              .getOrElse(ChainWeight.zero)
            val newWeight = parentWeight.increase(block.header)
            testBlockchainStorages.chainWeightStorage.put(block.header.hash.value, newWeight).commit()

          case Left(execError) =>
            throw new RuntimeException(s"Block execution failed: $execError")
      }

      // Extract the final world state from the blockchain after execution
      val lastBlock = blocks.last
      val lastBlockNumber = parseBigInt(lastBlock.blockHeader.number)
      val lastStateRoot = ByteString(parseHex(lastBlock.blockHeader.stateRoot))

      val finalWorld = InMemoryWorldStateProxy(
        evmCodeStorage = testBlockchainStorages.evmCodeStorage,
        mptStorage = blockchain.getBackingMptStorage(lastBlockNumber),
        getBlockHashByNumber = (num: BigInt) =>
          testBlockchainStorages.blockNumberMappingStorage
            .get(num)
            .flatMap(hash => testBlockchainStorages.blockHeadersStorage.get(hash).map(_.hash.value)),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = lastStateRoot,
        noEmptyAccounts = false,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )

      Right(finalWorld)
    catch
      case e: Exception =>
        Left(s"Failed to execute blocks: ${e.getMessage}\n${e.getStackTrace.take(10).mkString("\n")}")

  private def createParentBlockHeader(
      blockNumber: BigInt,
      stateRoot: ByteString,
      testBlock: TestBlock
  ): BlockHeader =
    BlockHeader(
      parentHash = BlockHash(ByteString(Array.fill(32)(0.toByte))),
      ommersHash =
        BlockHash(ByteString(parseHex("0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
      beneficiary = ByteString(Array.fill(20)(0.toByte)),
      stateRoot = TrieRoot(stateRoot),
      transactionsRoot =
        TrieRoot(ByteString(parseHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(parseHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(ByteString(Array.fill(256)(0.toByte))),
      difficulty = Difficulty(BigInt(0)),
      number = BlockNumber(blockNumber),
      gasLimit = GasAmount(parseBigInt(testBlock.blockHeader.gasLimit)),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(parseBigInt(testBlock.blockHeader.timestamp).toLong - 1),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(Array.fill(32)(0.toByte))),
      nonce = ByteString(Array.fill(8)(0.toByte))
    )

  private def convertTestBlockToBlock(testBlock: TestBlock): Block =
    val header = TestConverter.toBlockHeader(testBlock.blockHeader)
    val transactions = testBlock.transactions.map(TestConverter.toTransaction)
    val uncles = testBlock.uncleHeaders.map(TestConverter.toBlockHeader)
    // EIP-4895: thread the withdrawals array through so BlockExecution credits each
    // withdrawal (amount Gwei) before computing the post-state root. Present (possibly
    // empty) for Shanghai+ headers; absent for pre-Shanghai blocks.
    val withdrawals = testBlock.withdrawals.map(_.map(TestConverter.toWithdrawal))

    Block(header, BlockBody(transactions, uncles, withdrawals))

  private def parseHex(hex: String): Array[Byte] =
    val cleaned = if hex.startsWith("0x") then hex.substring(2) else hex
    if cleaned.isEmpty then Array.empty[Byte]
    else org.bouncycastle.util.encoders.Hex.decode(cleaned)

  private def parseBigInt(value: String): BigInt =
    if value.startsWith("0x") then BigInt(value.substring(2), 16)
    else BigInt(value)
