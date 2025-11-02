package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.EthBlocksService._
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

class EthBlocksServiceSpec
    extends TestKit(ActorSystem("EthBlocksServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals {

  implicit val runtime: IORuntime = IORuntime.global

  "EthBlocksService" should "answer eth_blockNumber with the latest block number" in new TestSetup {
    val bestBlockNumber = 10
    blockchainWriter.saveBestKnownBlocks(ByteString.empty, bestBlockNumber)

    val response: BestBlockNumberResponse =
      ethBlocksService.bestBlockNumber(BestBlockNumberRequest()).unsafeRunSync().toOption.get
    response.bestBlockNumber shouldEqual bestBlockNumber
  }

  it should "answer eth_getBlockTransactionCountByHash with None when the requested block isn't in the blockchain" in new TestSetup {
    val request: TxCountByBlockHashRequest = TxCountByBlockHashRequest(blockToRequestHash)
    val response: TxCountByBlockHashResponse =
      ethBlocksService.getBlockTransactionCountByHash(request).unsafeRunSync().toOption.get
    response.txsQuantity shouldBe None
  }

  it should "answer eth_getBlockTransactionCountByHash with the block has no tx when the requested block is in the blockchain and has no tx" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest.copy(body = BlockBody(Nil, Nil))).commit()
    val request: TxCountByBlockHashRequest = TxCountByBlockHashRequest(blockToRequestHash)
    val response: TxCountByBlockHashResponse =
      ethBlocksService.getBlockTransactionCountByHash(request).unsafeRunSync().toOption.get
    response.txsQuantity shouldBe Some(0)
  }

  it should "answer eth_getBlockTransactionCountByHash correctly when the requested block is in the blockchain and has some tx" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    val request: TxCountByBlockHashRequest = TxCountByBlockHashRequest(blockToRequestHash)
    val response: TxCountByBlockHashResponse =
      ethBlocksService.getBlockTransactionCountByHash(request).unsafeRunSync().toOption.get
    response.txsQuantity shouldBe Some(blockToRequest.body.transactionList.size)
  }

  it should "answer eth_getBlockByHash with None when the requested block isn't in the blockchain" in new TestSetup {
    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(blockToRequestHash, fullTxs = true)
    val response: BlockByBlockHashResponse = ethBlocksService.getByBlockHash(request).unsafeRunSync().toOption.get
    response.blockResponse shouldBe None
  }

  it should "answer eth_getBlockByHash with the block response correctly when it's chain weight is in blockchain" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(blockToRequestHash, blockWeight))
      .commit()

    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(blockToRequestHash, fullTxs = true)
    val response: BlockByBlockHashResponse = ethBlocksService.getByBlockHash(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = true, weight = Some(blockWeight))
    )
    response.blockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe Some(blockWeight)
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)
  }

  it should "answer eth_getBlockByHash with the block response correctly when it's chain weight is not in blockchain" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()

    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(blockToRequestHash, fullTxs = true)
    val response: BlockByBlockHashResponse = ethBlocksService.getByBlockHash(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(BlockResponse(blockToRequest, fullTxs = true))
    response.blockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe None
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)
  }

  it should "answer eth_getBlockByHash with the block response correctly when the txs should be hashed" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(blockToRequestHash, blockWeight))
      .commit()

    val request: BlockByBlockHashRequest = BlockByBlockHashRequest(blockToRequestHash, fullTxs = true)
    val response: BlockByBlockHashResponse =
      ethBlocksService.getByBlockHash(request.copy(fullTxs = false)).unsafeRunSync().toOption.get

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = false, weight = Some(blockWeight))
    )
    response.blockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe Some(blockWeight)
    response.blockResponse.get.transactions.left.toOption shouldBe Some(blockToRequest.body.transactionList.map(_.hash))
  }

  it should "answer eth_getBlockByNumber with the correct block when the pending block is requested" in new TestSetup {
    (appStateStorage.getBestBlockNumber _: () => BigInt).expects().returns(blockToRequest.header.number)

    (() => blockGenerator.getPendingBlockAndState)
      .expects()
      .returns(Some(PendingBlockAndState(PendingBlock(blockToRequest, Nil), fakeWorld)))

    val request: BlockByNumberRequest = BlockByNumberRequest(BlockParam.Pending, fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get

    response.blockResponse.isDefined should be(true)
    val blockResponse = response.blockResponse.get

    blockResponse.hash shouldBe None
    blockResponse.nonce shouldBe None
    blockResponse.miner shouldBe None
    blockResponse.number shouldBe blockToRequest.header.number
  }

  it should "answer eth_getBlockByNumber with the latest block pending block is requested and there are no pending ones" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(blockToRequestHash, blockWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.header.number)

    (() => blockGenerator.getPendingBlockAndState).expects().returns(None)

    val request: BlockByNumberRequest = BlockByNumberRequest(BlockParam.Pending, fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get
    response.blockResponse.get.hash.get shouldEqual blockToRequest.header.hash
  }

  it should "answer eth_getBlockByNumber with None when the requested block isn't in the blockchain" in new TestSetup {
    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get
    response.blockResponse shouldBe None
  }

  it should "answer eth_getBlockByNumber with the block response correctly when it's chain weight is in blockchain" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(blockToRequestHash, blockWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = true, weight = Some(blockWeight))
    )
    response.blockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe Some(blockWeight)
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)
  }

  it should "answer eth_getBlockByNumber with the block response correctly when it's chain weight is not in blockchain" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse = ethBlocksService.getBlockByNumber(request).unsafeRunSync().toOption.get

    val stxResponses: Seq[TransactionResponse] = blockToRequest.body.transactionList.zipWithIndex.map {
      case (stx, txIndex) =>
        TransactionResponse(stx, Some(blockToRequest.header), Some(txIndex))
    }

    response.blockResponse shouldBe Some(BlockResponse(blockToRequest, fullTxs = true))
    response.blockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe None
    response.blockResponse.get.transactions.toOption shouldBe Some(stxResponses)
  }

  it should "answer eth_getBlockByNumber with the block response correctly when the txs should be hashed" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequest)
      .and(blockchainWriter.storeChainWeight(blockToRequestHash, blockWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val request: BlockByNumberRequest =
      BlockByNumberRequest(BlockParam.WithNumber(blockToRequestNumber), fullTxs = true)
    val response: BlockByNumberResponse =
      ethBlocksService.getBlockByNumber(request.copy(fullTxs = false)).unsafeRunSync().toOption.get

    response.blockResponse shouldBe Some(
      BlockResponse(blockToRequest, fullTxs = false, weight = Some(blockWeight))
    )
    response.blockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe Some(blockWeight)
    response.blockResponse.get.transactions.left.toOption shouldBe Some(blockToRequest.body.transactionList.map(_.hash))
  }

  it should "get transaction count by block number" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val response: ServiceResponse[GetBlockTransactionCountByNumberResponse] =
      ethBlocksService.getBlockTransactionCountByNumber(
        GetBlockTransactionCountByNumberRequest(BlockParam.WithNumber(blockToRequest.header.number))
      )

    response.unsafeRunSync() shouldEqual Right(
      GetBlockTransactionCountByNumberResponse(blockToRequest.body.transactionList.size)
    )
  }

  it should "get transaction count by latest block number" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.header.number)

    val response: ServiceResponse[GetBlockTransactionCountByNumberResponse] =
      ethBlocksService.getBlockTransactionCountByNumber(GetBlockTransactionCountByNumberRequest(BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(
      GetBlockTransactionCountByNumberResponse(blockToRequest.body.transactionList.size)
    )
  }

  it should "answer eth_getUncleByBlockHashAndIndex with None when the requested block isn't in the blockchain" in new TestSetup {
    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(blockToRequestHash, uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get
    response.uncleBlockResponse shouldBe None
  }

  it should "answer eth_getUncleByBlockHashAndIndex with None when there's no uncle" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(blockToRequestHash, uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe None
  }

  it should "answer eth_getUncleByBlockHashAndIndex with None when there's no uncle in the requested index" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequestWithUncles.hash, blockToRequestWithUncles.number)

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(blockToRequestHash, uncleIndexToRequest)
    val response1: UncleByBlockHashAndIndexResponse =
      ethBlocksService
        .getUncleByBlockHashAndIndex(request.copy(uncleIndex = 1))
        .unsafeRunSync()
        .toOption
        .get
    val response2: UncleByBlockHashAndIndexResponse =
      ethBlocksService
        .getUncleByBlockHashAndIndex(request.copy(uncleIndex = -1))
        .unsafeRunSync()
        .toOption
        .get

    response1.uncleBlockResponse shouldBe None
    response2.uncleBlockResponse shouldBe None
  }

  it should "answer eth_getUncleByBlockHashAndIndex correctly when the requested index has one but there's no chain weight for it" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(blockToRequestHash, uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, None, pendingBlock = false))
    response.uncleBlockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe None
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil
  }

  it should "anwer eth_getUncleByBlockHashAndIndex correctly when the requested index has one and there's chain weight for it" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequestWithUncles)
      .and(blockchainWriter.storeChainWeight(uncle.hash, uncleWeight))
      .commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockHashAndIndexRequest =
      UncleByBlockHashAndIndexRequest(blockToRequestHash, uncleIndexToRequest)
    val response: UncleByBlockHashAndIndexResponse =
      ethBlocksService.getUncleByBlockHashAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, Some(uncleWeight), pendingBlock = false))
    response.uncleBlockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe Some(uncleWeight)
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil
  }

  it should "answer eth_getUncleByBlockNumberAndIndex with None when the requested block isn't in the blockchain" in new TestSetup {
    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get
    response.uncleBlockResponse shouldBe None
  }

  it should "answer eth_getUncleByBlockNumberAndIndex with None when there's no uncle" in new TestSetup {

    blockchainWriter.storeBlock(blockToRequest).commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe None
  }

  it should "answer eth_getUncleByBlockNumberAndIndex with None when there's no uncle in the requested index" in new TestSetup {

    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response1: UncleByBlockNumberAndIndexResponse =
      ethBlocksService
        .getUncleByBlockNumberAndIndex(request.copy(uncleIndex = 1))
        .unsafeRunSync()
        .toOption
        .get
    val response2: UncleByBlockNumberAndIndexResponse =
      ethBlocksService
        .getUncleByBlockNumberAndIndex(request.copy(uncleIndex = -1))
        .unsafeRunSync()
        .toOption
        .get

    response1.uncleBlockResponse shouldBe None
    response2.uncleBlockResponse shouldBe None
  }

  it should "answer eth_getUncleByBlockNumberAndIndex correctly when the requested index has one but there's no chain weight for it" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequestWithUncles).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequestWithUncles.hash, blockToRequestWithUncles.number)

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, None, pendingBlock = false))
    response.uncleBlockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe None
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil
  }

  it should "answer eth_getUncleByBlockNumberAndIndex correctly when the requested index has one and there's chain weight for it" in new TestSetup {
    blockchainWriter
      .storeBlock(blockToRequestWithUncles)
      .and(blockchainWriter.storeChainWeight(uncle.hash, uncleWeight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequestWithUncles.hash, blockToRequestWithUncles.number)

    val uncleIndexToRequest = 0
    val request: UncleByBlockNumberAndIndexRequest =
      UncleByBlockNumberAndIndexRequest(BlockParam.WithNumber(blockToRequestNumber), uncleIndexToRequest)
    val response: UncleByBlockNumberAndIndexResponse =
      ethBlocksService.getUncleByBlockNumberAndIndex(request).unsafeRunSync().toOption.get

    response.uncleBlockResponse shouldBe Some(BlockResponse(uncle, Some(uncleWeight), pendingBlock = false))
    response.uncleBlockResponse.get.asInstanceOf[BlockResponse].chainWeight shouldBe Some(uncleWeight)
    response.uncleBlockResponse.get.transactions shouldBe Left(Nil)
    response.uncleBlockResponse.get.uncles shouldBe Nil
  }

  it should "get uncle count by block number" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()
    blockchainWriter.saveBestKnownBlocks(blockToRequest.hash, blockToRequest.number)

    val response: ServiceResponse[GetUncleCountByBlockNumberResponse] =
      ethBlocksService.getUncleCountByBlockNumber(GetUncleCountByBlockNumberRequest(BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(
      GetUncleCountByBlockNumberResponse(blockToRequest.body.uncleNodesList.size)
    )
  }

  it should "get uncle count by block hash" in new TestSetup {
    blockchainWriter.storeBlock(blockToRequest).commit()

    val response: ServiceResponse[GetUncleCountByBlockHashResponse] =
      ethBlocksService.getUncleCountByBlockHash(GetUncleCountByBlockHashRequest(blockToRequest.header.hash))

    response.unsafeRunSync() shouldEqual Right(
      GetUncleCountByBlockHashResponse(blockToRequest.body.uncleNodesList.size)
    )
  }

  class TestSetup() extends EphemBlockchainTestSetup {
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val appStateStorage: AppStateStorage = mock[AppStateStorage]

    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    lazy val ethBlocksService = new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue
    )

    val blockToRequest: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val blockToRequestNumber = blockToRequest.header.number
    val blockToRequestHash = blockToRequest.header.hash
    val blockWeight: ChainWeight = ChainWeight.totalDifficultyOnly(blockToRequest.header.difficulty)

    val uncle = Fixtures.Blocks.DaoForkBlock.header
    val uncleWeight: ChainWeight = ChainWeight.totalDifficultyOnly(uncle.difficulty)
    val blockToRequestWithUncles: Block = blockToRequest.copy(body = BlockBody(Nil, Seq(uncle)))

    val fakeWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
  }
}
