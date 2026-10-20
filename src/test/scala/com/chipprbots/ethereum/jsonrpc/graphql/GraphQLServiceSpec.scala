package com.chipprbots.ethereum.jsonrpc.graphql

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import io.circe.ACursor
import io.circe.Json
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.PendingBlockAndState
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.EthBlocksService
import com.chipprbots.ethereum.jsonrpc.EthFilterService
import com.chipprbots.ethereum.jsonrpc.EthInfoService
import com.chipprbots.ethereum.jsonrpc.EthTxService
import com.chipprbots.ethereum.jsonrpc.EthUserService
import com.chipprbots.ethereum.jsonrpc.FilterManager
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability

class GraphQLServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with MockFactory:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(200, Millis))
  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic
  implicit val ec: ExecutionContext = classicActorSystem.dispatcher

  "GraphQLService" should "answer { chainID } with the configured chain id as 0x-hex" in new GraphQLTestSetup:
    val (status, body) = service.execute("{ chainID }", None, None).unsafeRunSync()
    status shouldBe 200
    val chainHex: String = body.hcursor.downField("data").downField("chainID").as[String].toOption.get
    chainHex should startWith("0x")
    // Any valid non-negative hex is acceptable here — the fixture's chain id depends on the test chain.

  it should "answer { block { number hash } } for the latest block" in new GraphQLTestSetup:
    blockchainWriter.storeBlock(block).and(blockchainWriter.storeChainWeight(block.header.hash, weight)).commit()
    blockchainWriter.saveBestKnownBlocks(block.hash, block.number.value)

    val (status, body) = service.execute("{ block { number hash } }", None, None).unsafeRunSync()
    status shouldBe 200
    val data: ACursor = body.hcursor.downField("data").downField("block")
    data.downField("number").as[String].toOption.get shouldBe "0x" + block.header.number.value.toString(16)
    val gotHash: String = data.downField("hash").as[String].toOption.get
    gotHash shouldBe "0x" + block.header.hash.toArray.map("%02x".format(_)).mkString

  it should "return null for an unknown transaction" in new GraphQLTestSetup:
    val unknown: String = "0x" + ("00" * 32)
    val query: String = s"""{ transaction(hash: \"$unknown\") { hash } }"""
    val (status, body) = service.execute(query, None, None).unsafeRunSync()
    status shouldBe 200
    body.hcursor.downField("data").downField("transaction").focus.get shouldBe Json.Null

  it should "reject a syntactically invalid query with HTTP 400" in new GraphQLTestSetup:
    val (status, body) = service.execute("{ not valid graphql", None, None).unsafeRunSync()
    status shouldBe 400
    val errs: List[Json] = body.hcursor.downField("errors").as[List[Json]].toOption.get
    errs should not be empty

  it should "reject queries exceeding the configured depth" in new GraphQLTestSetup(maxDepth = 3):
    blockchainWriter.storeBlock(block).and(blockchainWriter.storeChainWeight(block.header.hash, weight)).commit()
    blockchainWriter.saveBestKnownBlocks(block.hash, block.number.value)

    val deep =
      "{ block { parent { parent { parent { parent { number } } } } } }"
    val (status, _) = service.execute(deep, None, None).unsafeRunSync()
    status shouldBe 400

  // go-ethereum graphql.go Account.TransactionCount: under the pending block number, the pool nonce (GetPoolNonce) —
  // the state nonce advanced over the sender's consecutive pooled txs, a nonce-gapped one not counted.
  it should "answer { pending { account { transactionCount } } } with the pool's next nonce" in new GraphQLTestSetup:
    import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
    // a head over the empty state: the sender's state nonce is the start nonce, 0
    val emptyStateBlock: Block = block.copy(header =
      block.header.copy(stateRoot = TrieRoot(ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash)))
    )
    blockchainWriter
      .storeBlock(emptyStateBlock)
      .and(blockchainWriter.storeChainWeight(emptyStateBlock.header.hash, weight))
      .commit()
    blockchainWriter.saveBestKnownBlocks(emptyStateBlock.hash, emptyStateBlock.number.value)
    val key = com.chipprbots.ethereum.crypto.keyPairFromPrvKey(BigInt(0xa11ce))
    def pooled(nonce: Int): PendingTransaction =
      val stx = SignedTransaction.sign(
        LegacyTransaction(nonce, GasPrice(10), GasAmount(21_000), Some(Address(1)), 0, ByteString.empty),
        key,
        None
      )
      PendingTransaction(SignedTransactionWithSender(stx, Address(key)), System.currentTimeMillis)

    val query = s"""{ pending { account(address: "${Address(key).toString}") { transactionCount } } }"""
    val result = service.execute(query, None, None).unsafeToFuture()
    pendingTxProbe.expectMsgPF() { case req: GetPendingTransactionsReq =>
      req.replyTo ! PendingTransactionsResponse(Seq(pooled(1), pooled(3), pooled(0)))
    }
    val (status, body) = result.futureValue
    status shouldBe 200
    body.hcursor
      .downField("data")
      .downField("pending")
      .downField("account")
      .downField("transactionCount")
      .as[String]
      .toOption shouldBe Some("0x2")

  it should "serve an introspection query" in new GraphQLTestSetup:
    val introspection =
      """{ __schema { queryType { name } mutationType { name } types { name } } }"""
    val (status, body) = service.execute(introspection, None, None).unsafeRunSync()
    status shouldBe 200
    body.hcursor
      .downField("data")
      .downField("__schema")
      .downField("queryType")
      .downField("name")
      .as[String]
      .toOption
      .get shouldBe "Query"
    body.hcursor
      .downField("data")
      .downField("__schema")
      .downField("mutationType")
      .downField("name")
      .as[String]
      .toOption
      .get shouldBe "Mutation"

  // -------------------------------------------------------------------------
  abstract class GraphQLTestSetup(val maxDepth: Int = GraphQLSchema.MaxQueryDepth) extends EphemBlockchainTestSetup:

    // Mining — needed by resolveBlock(Pending) path. The tests here don't exercise the
    // Pending branch, so the mock's default return is sufficient.
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val appStateStorage: AppStateStorage = mock[AppStateStorage]
    val transactionMappingStorage: TransactionMappingStorage =
      storagesInstance.storages.transactionMappingStorage
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    val keyStore: KeyStore = mock[KeyStore]
    val syncProbe: TestProbe = TestProbe()
    val pendingTxProbe: TestProbe = TestProbe()
    val filterManager: org.apache.pekko.actor.typed.ActorRef[FilterManager.Command] =
      testKit.spawn(Behaviors.ignore[FilterManager.Command])

    lazy val ethBlocksService = new EthBlocksService(
      blockchain,
      blockchainReader,
      mining,
      blockQueue
    )
    lazy val ethTxService = new EthTxService(
      blockchain,
      blockchainReader,
      mining,
      pendingTxProbe.ref.toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
      1.second,
      transactionMappingStorage,
      system.scheduler
    )
    lazy val ethInfoService = new EthInfoService(
      blockchain,
      blockchainReader,
      blockchainConfig,
      mining,
      stxLedger,
      keyStore,
      syncProbe.ref.toTyped[com.chipprbots.ethereum.blockchain.sync.SyncController.Command],
      Capability.ETH66,
      org.apache.pekko.util.Timeout(2.seconds),
      system.scheduler
    )
    lazy val ethUserService = new EthUserService(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      this
    )
    lazy val ethFilterService = new EthFilterService(
      filterManager,
      new com.chipprbots.ethereum.utils.FilterConfig:
        override val filterTimeout: FiniteDuration = 10.seconds
        override val filterManagerQueryTimeout: FiniteDuration = 2.seconds
      ,
      blockchainReader
    )

    val ctx: GraphQLContext = GraphQLContext(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      blockchainConfig,
      ethBlocksService,
      ethTxService,
      ethInfoService,
      ethUserService,
      ethFilterService
    )
    val service = new GraphQLService(ctx, maxQueryDepth = maxDepth, executionTimeout = 10.seconds)

    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val weight: ChainWeight = ChainWeight.totalDifficultyOnly(block.header.difficulty.value)
