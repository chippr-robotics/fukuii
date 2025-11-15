package com.chipprbots.ethereum.ethtest

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.db.cache.{AppCaches, LruCache}
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.storage._
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.pruning.{ArchivePruning, PruningMode}
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.{BlockExecution, BlockQueue, BlockValidation, InMemoryWorldStateProxy}
import com.chipprbots.ethereum.txExecTest.ScenarioSetup
import com.chipprbots.ethereum.utils.{BlockchainConfig, Config}

/** Helper for executing blocks with the test infrastructure */
class EthereumTestHelper(using bc: BlockchainConfig) extends ScenarioSetup {
  
  override implicit lazy val blockchainConfig: BlockchainConfig = bc
  
  // Create storages with initial state
  override protected val testBlockchainStorages: BlockchainStorages = 
    createEmptyStorages()
    
  private def createEmptyStorages(): BlockchainStorages = {
    new BlockchainStorages with AppCaches with EphemDataSourceComponent {
      override val receiptStorage: ReceiptStorage = new ReceiptStorage(this.dataSource)
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
            Config.InMemoryPruningNodeCacheConfig,
            Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
          )
        )
    }
  }
  
  /** Execute blocks and return final world state */
  def executeBlocks(
      blocks: Seq[TestBlock],
      initialWorld: InMemoryWorldStateProxy
  ): Either[String, InMemoryWorldStateProxy] = {
    try {
      if (blocks.isEmpty) {
        return Right(initialWorld)
      }
      
      // Get the first block to determine parent block number
      val firstTestBlock = blocks.head
      val firstBlockNumber = parseBigInt(firstTestBlock.blockHeader.number)
      val parentBlockNumber = firstBlockNumber - 1
      
      // Create a genesis/parent block with the initial state root
      val genesisHeader = createParentBlockHeader(
        blockNumber = parentBlockNumber,
        stateRoot = initialWorld.stateRootHash,
        testBlock = firstTestBlock
      )
      
      // Store the genesis/parent block
      val genesisBlock = Block(genesisHeader, BlockBody(Seq.empty, Seq.empty))
      testBlockchainStorages.blockHeadersStorage.put(genesisHeader.hash, genesisHeader).commit()
      testBlockchainStorages.blockBodiesStorage.put(genesisHeader.hash, genesisBlock.body).commit()
      testBlockchainStorages.blockNumberMappingStorage.put(genesisHeader.number, genesisHeader.hash).commit()
      
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
        
        result match {
          case Right(receiptList) =>
            // Store the executed block
            testBlockchainStorages.blockHeadersStorage.put(block.header.hash, block.header).commit()
            testBlockchainStorages.blockBodiesStorage.put(block.header.hash, block.body).commit()
            testBlockchainStorages.blockNumberMappingStorage.put(block.header.number, block.header.hash).commit()
            testBlockchainStorages.receiptStorage.put(block.header.hash, receiptList).commit()
            
          case Left(execError) =>
            throw new RuntimeException(s"Block execution failed: $execError")
        }
      }
      
      // Extract the final world state from the blockchain after execution
      val lastBlock = blocks.last
      val lastBlockNumber = parseBigInt(lastBlock.blockHeader.number)
      val lastStateRoot = ByteString(parseHex(lastBlock.blockHeader.stateRoot))
      
      val finalWorld = InMemoryWorldStateProxy(
        evmCodeStorage = testBlockchainStorages.evmCodeStorage,
        mptStorage = blockchain.getBackingMptStorage(lastBlockNumber),
        getBlockHashByNumber = (num: BigInt) => 
          testBlockchainStorages.blockNumberMappingStorage.get(num).flatMap(hash =>
            testBlockchainStorages.blockHeadersStorage.get(hash).map(_.hash)
          ),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = lastStateRoot,
        noEmptyAccounts = false,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )
      
      Right(finalWorld)
    } catch {
      case e: Exception => Left(s"Failed to execute blocks: ${e.getMessage}\n${e.getStackTrace.take(10).mkString("\n")}")
    }
  }
  
  private def createParentBlockHeader(
      blockNumber: BigInt,
      stateRoot: ByteString,
      testBlock: TestBlock
  ): BlockHeader = {
    BlockHeader(
      parentHash = ByteString(Array.fill(32)(0.toByte)),
      ommersHash = ByteString(parseHex("0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
      beneficiary = ByteString(Array.fill(20)(0.toByte)),
      stateRoot = stateRoot,
      transactionsRoot = ByteString(parseHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      receiptsRoot = ByteString(parseHex("0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
      logsBloom = ByteString(Array.fill(256)(0.toByte)),
      difficulty = BigInt(0),
      number = blockNumber,
      gasLimit = parseBigInt(testBlock.blockHeader.gasLimit),
      gasUsed = BigInt(0),
      unixTimestamp = parseBigInt(testBlock.blockHeader.timestamp).toLong - 1,
      extraData = ByteString.empty,
      mixHash = ByteString(Array.fill(32)(0.toByte)),
      nonce = ByteString(Array.fill(8)(0.toByte))
    )
  }
  
  private def convertTestBlockToBlock(testBlock: TestBlock): Block = {
    val header = TestConverter.toBlockHeader(testBlock.blockHeader)
    val transactions = testBlock.transactions.map(TestConverter.toTransaction)
    val uncles = testBlock.uncleHeaders.map(TestConverter.toBlockHeader)
    
    Block(header, BlockBody(transactions, uncles))
  }
  
  private def parseHex(hex: String): Array[Byte] = {
    val cleaned = if (hex.startsWith("0x")) hex.substring(2) else hex
    if (cleaned.isEmpty) Array.empty[Byte]
    else org.bouncycastle.util.encoders.Hex.decode(cleaned)
  }
  
  private def parseBigInt(value: String): BigInt = {
    if (value.startsWith("0x")) {
      BigInt(value.substring(2), 16)
    } else {
      BigInt(value)
    }
  }
}
