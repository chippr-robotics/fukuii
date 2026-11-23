package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.*
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.EthBlocksService.*
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.jsonrpc.EthUserService.*
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofResponse
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.*

/** B-411: a block whose header, number index and state are stored but whose body is not (after a checkpoint import, or
  * a SNAP hand-off whose pivot body is not backfilled). The methods geth serves from header and state
  * (`StateAndHeaderByNumber`) answer from the state; the ones that return block content still find no block.
  */
class StateReadByHeaderSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with OptionValues
    with MockFactory
    with TypeCheckedTripleEquals:

  implicit val runtime: IORuntime = IORuntime.global
  implicit private val classicActorSystem: ActorSystem = system.toClassic

  "a block with a header and state but no body" should "answer eth_getBalance, by every kind of block parameter" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    def balanceAt(block: BlockParam): Either[JsonRpcError, GetBalanceResponse] =
      ethUserService.getBalance(GetBalanceRequest(address, block)).unsafeRunSync()
    balanceAt(BlockParam.Latest) shouldEqual Right(GetBalanceResponse(123))
    balanceAt(BlockParam.WithNumber(header.number.value)) shouldEqual Right(GetBalanceResponse(123))
    balanceAt(BlockParam.WithHash(header.hash.value)) shouldEqual Right(GetBalanceResponse(123))
    balanceAt(BlockParam.Safe) shouldEqual Left(JsonRpcError.InvalidParams("safe block not available"))

  it should "answer eth_getBalance for pending as for the head when the generator has no pending block" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    (() => blockGenerator.getPendingBlockAndState).expects().returns(None).anyNumberOfTimes()
    ethUserService.getBalance(GetBalanceRequest(address, BlockParam.Pending)).unsafeRunSync() shouldEqual
      Right(GetBalanceResponse(123))

  it should "answer eth_getCode, eth_getStorageAt, eth_getTransactionCount and the storage root and values" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    ethUserService.getCode(GetCodeRequest(address, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Right(GetCodeResponse(code))
    ethUserService.getStorageAt(GetStorageAtRequest(address, 333, BlockParam.Latest)).unsafeRunSync().map { r =>
      UInt256(r.value)
    } shouldEqual Right(UInt256(123))
    ethUserService
      .getTransactionCount(GetTransactionCountRequest(address, BlockParam.Latest))
      .unsafeRunSync() shouldEqual
      Right(GetTransactionCountResponse(7))
    ethUserService.getStorageRoot(GetStorageRootRequest(address, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Right(GetStorageRootResponse(ByteString(storageMpt.getRootHash)))
    ethUserService
      .getStorageValues(
        GetStorageValuesRequest(Seq(StorageValuesEntry(address.toString, address, Seq(BigInt(333)))), BlockParam.Latest)
      )
      .unsafeRunSync()
      .map(_.values(address.toString).map(v => UInt256(v))) shouldEqual Right(Seq(UInt256(123)))

  it should "answer eth_getProof" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val response: Either[JsonRpcError, GetProofResponse] =
      ethProofService.getProof(GetProofRequest(address, Seq(StorageProofKey(333)), BlockParam.Latest)).unsafeRunSync()
    response.map(_.proofAccount.balance) shouldEqual Right(BigInt(123))
    val byNumber: Either[JsonRpcError, GetProofResponse] = ethProofService
      .getProof(GetProofRequest(address, Nil, BlockParam.WithNumber(header.number.value)))
      .unsafeRunSync()
    byNumber.map(_.proofAccount.nonce.toBigInt) shouldEqual Right(BigInt(7))

  it should "run eth_call and eth_estimateGas" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val worldStateProxy: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString.empty,
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    val txResult: TxResult = TxResult(worldStateProxy, 123, Nil, ByteString("return_value"), None)
    stxLedger.simulateCall
      .expects(*, *, *, *, *)
      .returning(Right(StxLedger.CallOutcome(txResult, codeDepositShortfall = false)))
    stxLedger.estimateCallGas.expects(*, *, *, *).returning(Right(BigInt(123)))

    val tx: TxArgs = TxArgs(
      from = Some(Address("0xda714fe079751fa7a1ad80b76571ea6ec52a446c")),
      to = Some(Address("0xabbb6bebfa05aa13e908eaa492bd7a8343760477")),
      gas = Some(GasAmount(1)),
      gasPrice = Some(GasPrice(2)),
      value = Some(3),
      data = Some(ByteString(""))
    )
    ethInfoService.call(CallRequest(tx, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Right(CallResponse(ByteString("return_value")))
    ethInfoService.estimateGas(CallRequest(tx, BlockParam.Latest)).unsafeRunSync() shouldEqual
      Right(EstimateGasResponse(123))

  it should "still have no block for the methods that return block or transaction content" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    ethBlocksService
      .getBlockByNumber(BlockByNumberRequest(BlockParam.WithNumber(header.number.value), fullTxs = false))
      .unsafeRunSync() shouldEqual Right(BlockByNumberResponse(None))
    ethBlocksService
      .getBlockByNumber(BlockByNumberRequest(BlockParam.Latest, fullTxs = false))
      .unsafeRunSync() shouldEqual Right(BlockByNumberResponse(None))
    ethBlocksService
      .getBlockTransactionCountByHash(TxCountByBlockHashRequest(header.hash.value))
      .unsafeRunSync()
      .map(_.txsQuantity) shouldEqual Right(None)
    ethBlocksService
      .getBlockTransactionCountByNumber(GetBlockTransactionCountByNumberRequest(BlockParam.Latest))
      .unsafeRunSync()
      .isLeft shouldBe true

  it should "answer eth_getHeaderByNumber and eth_getRawHeader from the header (B-412)" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    for param <- Seq(
        BlockParam.Latest,
        BlockParam.WithNumber(header.number.value),
        BlockParam.WithHash(header.hash.value)
      )
    do
      ethBlocksService
        .getHeaderByNumber(HeaderByNumberRequest(param))
        .unsafeRunSync()
        .map(_.headerResponse.map(_.number)) shouldEqual Right(Some(header.number.value))
      ethBlocksService.getRawHeader(GetRawHeaderRequest(param)).unsafeRunSync().map(_.rawHeader.isDefined) shouldEqual
        Right(true)
    ethBlocksService
      .getHeaderByNumber(HeaderByNumberRequest(BlockParam.WithNumber(header.number.value + 1)))
      .unsafeRunSync()
      .map(_.headerResponse) shouldEqual Right(None)

  it should "answer eth_feeHistory without percentiles, and fail with them while the body is missing (B-412)" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val noRewards: FeeHistoryRequest = FeeHistoryRequest(1, BlockParam.Latest, None)
    val response: FeeHistoryResponse = ethBlocksService.feeHistory(noRewards).unsafeRunSync().toOption.value
    response.oldestBlock shouldEqual header.number.value
    response.gasUsedRatio shouldEqual Seq(header.gasUsed.value.toDouble / header.gasLimit.value.toDouble)
    response.reward shouldEqual None

    val withRewards: FeeHistoryRequest = FeeHistoryRequest(1, BlockParam.Latest, Some(Seq(50.0)))
    ethBlocksService.feeHistory(withRewards).unsafeRunSync().isLeft shouldBe true
    // with the body stored the same request answers
    blockchainWriter.storeBlockBody(header.hash, body).commit()
    ethBlocksService.feeHistory(withRewards).unsafeRunSync().map(_.reward.map(_.size)) shouldEqual Right(Some(1))

  it should "resolve by header what resolveBlock cannot, and agree with it when the body is there" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val (bc, br, mn) = (blockchain, blockchainReader, mining)
    val resolver: ResolveBlock = new ResolveBlock:
      def blockchain: Blockchain = bc
      def blockchainReader: BlockchainReader = br
      def mining: com.chipprbots.ethereum.consensus.mining.Mining = mn
    resolver.resolveBlock(BlockParam.WithNumber(header.number.value)).isLeft shouldBe true
    resolver.resolveHeader(BlockParam.WithNumber(header.number.value)).map(_.header) shouldEqual Right(header)
    resolver.resolveHeader(BlockParam.Earliest).isLeft shouldBe header.number.value != 0
    resolver.resolveHeader(BlockParam.WithNumber(header.number.value + 1)).isLeft shouldBe true
    blockchainWriter.storeBlockBody(header.hash, body).commit()
    resolver.resolveBlock(BlockParam.WithNumber(header.number.value)).map(_.block.header) shouldEqual Right(header)

  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup:
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val keyStore: KeyStore = mock[KeyStore]
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    stxLedger.callWorld.expects(*, *).returning(null).anyNumberOfTimes()
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))
    val code: ByteString = ByteString("code code code")
    val codeHash: ByteString = ByteString(crypto.kec256(code.toArray[Byte]))
    storagesInstance.storages.evmCodeStorage.put(codeHash, code).commit()

    import MerklePatriciaTrie.defaultByteArraySerializable

    val storageMpt: MerklePatriciaTrie[BigInt, BigInt] =
      EthereumUInt256Mpt
        .storageMpt(
          ByteString(MerklePatriciaTrie.EmptyRootHash),
          storagesInstance.storages.stateStorage.getBackingStorage(0)
        )
        .put(UInt256(333), UInt256(123))

    val stateMpt: MerklePatriciaTrie[Array[Byte], Account] =
      MerklePatriciaTrie[Array[Byte], Account](storagesInstance.storages.stateStorage.getBackingStorage(0))
        .put(
          crypto.kec256(address.bytes.toArray[Byte]),
          Account(7, UInt256(123), TrieRoot(ByteString(storageMpt.getRootHash)), CodeHash(codeHash))
        )

    val fullBlock: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val header: BlockHeader = fullBlock.header.copy(stateRoot = TrieRoot(ByteString(stateMpt.getRootHash)))
    val body: BlockBody = fullBlock.body

    // the header and the number index and the best block, and no body
    blockchainWriter.storeBlockHeader(header).commit()
    blockchainWriter.saveBestKnownBlocks(header.hash, header.number.value)
    blockchainReader.getBlockBodyByHash(header.hash) shouldBe None

    lazy val ethUserService = new EthUserService(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      this
    )
    lazy val ethProofService =
      new EthProofService(blockchain, blockchainReader, blockGenerator, blockchainConfig.ethCompatibleStorage)
    lazy val ethInfoService = new EthInfoService(
      blockchain,
      blockchainReader,
      blockchainConfig,
      mining,
      stxLedger,
      keyStore,
      TestProbe().ref.toTyped[SyncController.Command],
      Capability.ETH63,
      Timeouts.shortTimeout,
      system.toTyped.scheduler
    )
    lazy val ethBlocksService = new EthBlocksService(blockchain, blockchainReader, mining, blockQueue)
