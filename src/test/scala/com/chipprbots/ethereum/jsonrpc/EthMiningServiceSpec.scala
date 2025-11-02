package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.consensus.pow.blocks.RestrictedPoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.difficulty.EthashDifficultyCalculator
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.getEncodedWithoutNonce
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.EthMiningService._
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config
import scala.concurrent.Future
import scala.concurrent.Future

class EthMiningServiceSpec
    extends TestKit(ActorSystem("EthMiningServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with NormalPatience
    with org.scalamock.scalatest.MockFactory {

  implicit val runtime: IORuntime = IORuntime.global

  "MiningServiceSpec" should "return if node is mining base on getWork" in new TestSetup {

    ethMiningService.getMining(GetMiningRequest()).unsafeRunSync() shouldEqual Right(GetMiningResponse(false))

    (blockGenerator
      .generateBlock(
        _: Block,
        _: Seq[SignedTransaction],
        _: Address,
        _: Seq[BlockHeader],
        _: Option[InMemoryWorldStateProxy]
      )(_: BlockchainConfig))
      .expects(parentBlock, *, *, *, *, *)
      .returning(PendingBlockAndState(PendingBlock(block, Nil), fakeWorld))
    blockchainWriter.save(parentBlock, Nil, ChainWeight.totalDifficultyOnly(parentBlock.header.difficulty), true)

    // Start the getWork call asynchronously
    val workFuture: Future[Either[JsonRpcError, GetWorkResponse]] = ethMiningService.getWork(GetWorkRequest()).unsafeToFuture()

    // Handle the actor messages
    pendingTransactionsManager.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    pendingTransactionsManager.reply(PendingTransactionsManager.PendingTransactionsResponse(Nil))
    ommersPool.expectMsg(OmmersPool.GetOmmers(parentBlock.hash))
    ommersPool.reply(OmmersPool.Ommers(Nil))

    // Wait for the result
    import scala.concurrent.Await
    import scala.concurrent.duration._
    Await.result(workFuture, 10.seconds)

    val response: ServiceResponse[GetMiningResponse] = ethMiningService.getMining(GetMiningRequest())

    response.unsafeRunSync() shouldEqual Right(GetMiningResponse(true))
  }

  it should "return if node is mining base on submitWork" in new TestSetup {

    ethMiningService.getMining(GetMiningRequest()).unsafeRunSync() shouldEqual Right(GetMiningResponse(false))

    (blockGenerator.getPrepared _).expects(*).returning(Some(PendingBlock(block, Nil)))
    ethMiningService
      .submitWork(
        SubmitWorkRequest(ByteString("nonce"), ByteString(Hex.decode("01" * 32)), ByteString(Hex.decode("01" * 32)))
      )
      .unsafeRunSync()

    val response: ServiceResponse[GetMiningResponse] = ethMiningService.getMining(GetMiningRequest())

    response.unsafeRunSync() shouldEqual Right(GetMiningResponse(true))
  }

  it should "return if node is mining base on submitHashRate" in new TestSetup {

    ethMiningService.getMining(GetMiningRequest()).unsafeRunSync() shouldEqual Right(GetMiningResponse(false))
    ethMiningService.submitHashRate(SubmitHashRateRequest(42, ByteString("id")))

    val response: ServiceResponse[GetMiningResponse] = ethMiningService.getMining(GetMiningRequest())

    response.unsafeRunSync() shouldEqual Right(GetMiningResponse(true))
  }

  it should "return if node is mining after time out" in new TestSetup {

    (blockGenerator
      .generateBlock(
        _: Block,
        _: Seq[SignedTransaction],
        _: Address,
        _: Seq[BlockHeader],
        _: Option[InMemoryWorldStateProxy]
      )(_: BlockchainConfig))
      .expects(parentBlock, *, *, *, *, *)
      .returning(PendingBlockAndState(PendingBlock(block, Nil), fakeWorld))
    blockchainWriter.save(parentBlock, Nil, ChainWeight.totalDifficultyOnly(parentBlock.header.difficulty), true)

    // Start the getWork call asynchronously
    val workFuture: Future[Either[JsonRpcError, GetWorkResponse]] = ethMiningService.getWork(GetWorkRequest()).unsafeToFuture()

    // Handle the actor messages
    pendingTransactionsManager.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    pendingTransactionsManager.reply(PendingTransactionsManager.PendingTransactionsResponse(Nil))
    ommersPool.expectMsg(OmmersPool.GetOmmers(parentBlock.hash))
    ommersPool.reply(OmmersPool.Ommers(Nil))

    // Wait for the result
    import scala.concurrent.Await
    import scala.concurrent.duration._
    Await.result(workFuture, 10.seconds)

    Thread.sleep(minerActiveTimeout.toMillis)

    val response: ServiceResponse[GetMiningResponse] = ethMiningService.getMining(GetMiningRequest())

    response.unsafeRunSync() shouldEqual Right(GetMiningResponse(false))
  }

  it should "return requested work" in new TestSetup {

    (blockGenerator
      .generateBlock(
        _: Block,
        _: Seq[SignedTransaction],
        _: Address,
        _: Seq[BlockHeader],
        _: Option[InMemoryWorldStateProxy]
      )(_: BlockchainConfig))
      .expects(parentBlock, Nil, *, *, *, *)
      .returning(PendingBlockAndState(PendingBlock(block, Nil), fakeWorld))
    blockchainWriter.save(parentBlock, Nil, ChainWeight.totalDifficultyOnly(parentBlock.header.difficulty), true)

    val response: Either[JsonRpcError, GetWorkResponse] = ethMiningService.getWork(GetWorkRequest()).unsafeRunSync()
    pendingTransactionsManager.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    pendingTransactionsManager.reply(PendingTransactionsManager.PendingTransactionsResponse(Nil))

    ommersPool.expectMsg(OmmersPool.GetOmmers(parentBlock.hash))
    ommersPool.reply(OmmersPool.Ommers(Nil))

    response shouldEqual Right(GetWorkResponse(powHash, seedHash, target))
  }

  it should "generate and submit work when generating block for mining with restricted ethash generator" in new TestSetup {
    val testMining: TestMining = buildTestMining()
    override lazy val restrictedGenerator = new RestrictedPoWBlockGeneratorImpl(
      evmCodeStorage = storagesInstance.storages.evmCodeStorage,
      validators = MockValidatorsAlwaysSucceed,
      blockchainReader = blockchainReader,
      miningConfig = miningConfig,
      blockPreparator = testMining.blockPreparator,
      EthashDifficultyCalculator,
      minerKey
    )
    override lazy val mining: TestMining = testMining.withBlockGenerator(restrictedGenerator)

    blockchainWriter.save(parentBlock, Nil, ChainWeight.totalDifficultyOnly(parentBlock.header.difficulty), true)

    val response: Either[JsonRpcError, GetWorkResponse] = ethMiningService.getWork(GetWorkRequest()).unsafeRunSync()
    pendingTransactionsManager.expectMsg(PendingTransactionsManager.GetPendingTransactions)
    pendingTransactionsManager.reply(PendingTransactionsManager.PendingTransactionsResponse(Nil))

    ommersPool.expectMsg(OmmersPool.GetOmmers(parentBlock.hash))
    ommersPool.reply(OmmersPool.Ommers(Nil))

    assert(response.isRight)
    val responseData = response.toOption.get

    val submitRequest: SubmitWorkRequest =
      SubmitWorkRequest(ByteString("nonce"), responseData.powHeaderHash, ByteString(Hex.decode("01" * 32)))
    val response1: Either[JsonRpcError, SubmitWorkResponse] = ethMiningService.submitWork(submitRequest).unsafeRunSync()
    response1 shouldEqual Right(SubmitWorkResponse(true))
  }

  it should "accept submitted correct PoW" in new TestSetup {

    val headerHash: ByteString = ByteString(Hex.decode("01" * 32))

    (blockGenerator.getPrepared _).expects(headerHash).returning(Some(PendingBlock(block, Nil)))

    val req: SubmitWorkRequest = SubmitWorkRequest(ByteString("nonce"), headerHash, ByteString(Hex.decode("01" * 32)))

    val response: ServiceResponse[SubmitWorkResponse] = ethMiningService.submitWork(req)
    response.unsafeRunSync() shouldEqual Right(SubmitWorkResponse(true))
  }

  it should "reject submitted correct PoW when header is no longer in cache" in new TestSetup {

    val headerHash: ByteString = ByteString(Hex.decode("01" * 32))

    (blockGenerator.getPrepared _).expects(headerHash).returning(None)

    val req: SubmitWorkRequest = SubmitWorkRequest(ByteString("nonce"), headerHash, ByteString(Hex.decode("01" * 32)))

    val response: ServiceResponse[SubmitWorkResponse] = ethMiningService.submitWork(req)
    response.unsafeRunSync() shouldEqual Right(SubmitWorkResponse(false))
  }

  it should "return correct coinbase" in new TestSetup {

    val response: ServiceResponse[GetCoinbaseResponse] = ethMiningService.getCoinbase(GetCoinbaseRequest())
    response.unsafeRunSync() shouldEqual Right(GetCoinbaseResponse(miningConfig.coinbase))
  }

  it should "accept and report hashrate" in new TestSetup {

    val rate: BigInt = 42
    val id: ByteString = ByteString("id")

    ethMiningService.submitHashRate(SubmitHashRateRequest(12, id)).unsafeRunSync() shouldEqual Right(
      SubmitHashRateResponse(true)
    )
    ethMiningService.submitHashRate(SubmitHashRateRequest(rate, id)).unsafeRunSync() shouldEqual Right(
      SubmitHashRateResponse(true)
    )

    val response: ServiceResponse[GetHashRateResponse] = ethMiningService.getHashRate(GetHashRateRequest())
    response.unsafeRunSync() shouldEqual Right(GetHashRateResponse(rate))
  }

  it should "combine hashrates from many miners and remove timed out rates" in new TestSetup {

    val rate: BigInt = 42
    val id1: ByteString = ByteString("id1")
    val id2: ByteString = ByteString("id2")

    ethMiningService.submitHashRate(SubmitHashRateRequest(rate, id1)).unsafeRunSync() shouldEqual Right(
      SubmitHashRateResponse(true)
    )
    Thread.sleep(minerActiveTimeout.toMillis / 2)
    ethMiningService.submitHashRate(SubmitHashRateRequest(rate, id2)).unsafeRunSync() shouldEqual Right(
      SubmitHashRateResponse(true)
    )

    val response1: ServiceResponse[GetHashRateResponse] = ethMiningService.getHashRate(GetHashRateRequest())
    response1.unsafeRunSync() shouldEqual Right(GetHashRateResponse(rate * 2))

    Thread.sleep(minerActiveTimeout.toMillis / 2)
    val response2: ServiceResponse[GetHashRateResponse] = ethMiningService.getHashRate(GetHashRateRequest())
    response2.unsafeRunSync() shouldEqual Right(GetHashRateResponse(rate))
  }

  // NOTE TestSetup uses Ethash consensus; check `consensusConfig`.
  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup with ApisBuilder {
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val syncingController: TestProbe = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()
    val ommersPool: TestProbe = TestProbe()

    val minerActiveTimeout: FiniteDuration = 5.seconds
    val getTransactionFromPoolTimeout: FiniteDuration = 5.seconds

    lazy val minerKey: AsymmetricCipherKeyPair = crypto.keyPairFromPrvKey(
      ByteStringUtils.string2hash("00f7500a7178548b8a4488f78477660b548c9363e16b584c21e0208b3f1e0dc61f")
    )

    lazy val restrictedGenerator = new RestrictedPoWBlockGeneratorImpl(
      evmCodeStorage = storagesInstance.storages.evmCodeStorage,
      validators = MockValidatorsAlwaysSucceed,
      blockchainReader = blockchainReader,
      miningConfig = miningConfig,
      blockPreparator = mining.blockPreparator,
      EthashDifficultyCalculator,
      minerKey
    )

    val jsonRpcConfig: JsonRpcConfig = JsonRpcConfig(Config.config, available)

    lazy val ethMiningService = new EthMiningService(
      blockchainReader,
      mining,
      jsonRpcConfig,
      ommersPool.ref,
      syncingController.ref,
      pendingTransactionsManager.ref,
      getTransactionFromPoolTimeout,
      this
    )

    val difficulty = 131072
    val parentBlock: Block = Block(
      header = BlockHeader(
        parentHash = ByteString.empty,
        ommersHash = ByteString.empty,
        beneficiary = ByteString.empty,
        stateRoot = ByteString(MerklePatriciaTrie.EmptyRootHash),
        transactionsRoot = ByteString.empty,
        receiptsRoot = ByteString.empty,
        logsBloom = ByteString.empty,
        difficulty = difficulty,
        number = 0,
        gasLimit = 16733003,
        gasUsed = 0,
        unixTimestamp = 1494604900,
        extraData = ByteString.empty,
        mixHash = ByteString.empty,
        nonce = ByteString.empty
      ),
      body = BlockBody.empty
    )
    val block: Block = Block(
      header = BlockHeader(
        parentHash = parentBlock.header.hash,
        ommersHash = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")),
        beneficiary = ByteString(Hex.decode("000000000000000000000000000000000000002a")),
        stateRoot = ByteString(Hex.decode("2627314387b135a548040d3ca99dbf308265a3f9bd9246bee3e34d12ea9ff0dc")),
        transactionsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        receiptsRoot = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")),
        logsBloom = ByteString(Hex.decode("00" * 256)),
        difficulty = difficulty,
        number = 1,
        gasLimit = 16733003,
        gasUsed = 0,
        unixTimestamp = 1494604913,
        extraData = ByteString(Hex.decode("6d696e6564207769746820657463207363616c61")),
        mixHash = ByteString.empty,
        nonce = ByteString.empty
      ),
      body = BlockBody.empty
    )
    val seedHash: ByteString = ByteString(Hex.decode("00" * 32))
    val powHash: ByteString = ByteString(kec256(getEncodedWithoutNonce(block.header)))
    val target: ByteString = ByteString((BigInt(2).pow(256) / difficulty).toByteArray)

    val fakeWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getReadOnlyMptStorage(),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
  }
}
