package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import org.bouncycastle.crypto.params.ECPublicKeyParameters

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.TraceService._
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.ledger.TxTraceData
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm._

/** Tests for TraceService — trace_block, trace_transaction, trace_filter,
  * trace_replayBlockTransactions, trace_replayTransaction, trace_get.
  *
  * Uses mock BlockPreparator.executeTransactionWithTrace to avoid full EVM execution
  * while exercising all of TraceService's response formatting, filtering, and error handling.
  */
class TraceServiceSpec
    extends TestKit(ActorSystem("TraceServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory {

  implicit val runtime: IORuntime = IORuntime.global

  // ===== traceBlock =====

  "TraceService" should "return traces for a block with one transaction" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val result = traceService.traceBlock(TraceBlockRequest(BlockParam.WithNumber(1))).unsafeRunSync()
    result shouldBe Symbol("right")
    val response = result.toOption.get
    response.traces should have size 1
    response.traces.head.traces should not be empty
    response.traces.head.traces.head.traceType shouldBe "call"
  }

  it should "return empty traces for a block with no transactions" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(emptyBlock, parentBlock)

    val result = traceService.traceBlock(TraceBlockRequest(BlockParam.WithNumber(2))).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces shouldBe empty
  }

  it should "return error for non-existent block" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = traceService.traceBlock(TraceBlockRequest(BlockParam.WithNumber(999))).unsafeRunSync()
    result shouldBe Symbol("left")
    result.left.toOption.get shouldBe JsonRpcError.InvalidParams("Block not found")
  }

  it should "group traces by transaction correctly for multi-tx block" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithTwoTxs, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService.traceBlock(TraceBlockRequest(BlockParam.WithNumber(3))).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces should have size 2
  }

  it should "handle Pending block param by returning error" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = traceService.traceBlock(TraceBlockRequest(BlockParam.Pending)).unsafeRunSync()
    result shouldBe Symbol("left")
  }

  // ===== traceTransaction =====

  it should "return traces for a known transaction hash" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val result = traceService.traceTransaction(TraceTransactionRequest(txHash)).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces should not be empty
  }

  it should "return error for unknown transaction hash" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val unknownHash = ByteString(Hex.decode("ff" * 32))

    val result = traceService.traceTransaction(TraceTransactionRequest(unknownHash)).unsafeRunSync()
    result shouldBe Symbol("left")
  }

  it should "include internal traces when tx has CALL operations" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    setupMockTraceForTx(blockWithOneTx, 0, traceDataWithInternalTx)

    val result = traceService.traceTransaction(TraceTransactionRequest(txHash)).unsafeRunSync()
    result shouldBe Symbol("right")
    val traces = result.toOption.get.traces
    traces.size should be > 1 // root + internal
    traces.last.traceAddress shouldBe Seq(0)
  }

  // ===== traceFilter =====

  it should "return all traces in block range with no address filter" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val result = traceService
      .traceFilter(TraceFilterRequest(
        fromBlock = Some(BlockParam.WithNumber(1)),
        toBlock = Some(BlockParam.WithNumber(1)),
        fromAddress = None,
        toAddress = None,
        after = None,
        count = None
      ))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces should not be empty
  }

  it should "filter traces by fromAddress returning empty for non-matching" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val nonExistentAddr = Address(ByteString(Hex.decode("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")))
    val result = traceService
      .traceFilter(TraceFilterRequest(
        fromBlock = Some(BlockParam.WithNumber(1)),
        toBlock = Some(BlockParam.WithNumber(1)),
        fromAddress = Some(Seq(nonExistentAddr)),
        toAddress = None,
        after = None,
        count = None
      ))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces shouldBe empty
  }

  it should "filter traces by toAddress returning empty for non-matching" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val nonExistentAddr = Address(ByteString(Hex.decode("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")))
    val result = traceService
      .traceFilter(TraceFilterRequest(
        fromBlock = Some(BlockParam.WithNumber(1)),
        toBlock = Some(BlockParam.WithNumber(1)),
        fromAddress = None,
        toAddress = Some(Seq(nonExistentAddr)),
        after = None,
        count = None
      ))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces shouldBe empty
  }

  it should "respect pagination (after/count) in traceFilter" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForTx(blockWithOneTx, 0, traceDataWithInternalTx)

    val result = traceService
      .traceFilter(TraceFilterRequest(
        fromBlock = Some(BlockParam.WithNumber(1)),
        toBlock = Some(BlockParam.WithNumber(1)),
        fromAddress = None,
        toAddress = None,
        after = Some(0),
        count = Some(1)
      ))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces should have size 1
  }

  it should "cap trace_filter range at 1000 blocks" taggedAs (UnitTest, RPCTest) in new TestSetup {
    // Request range of 5000 blocks — capped to 1000 internally.
    // Since no blocks stored, should return empty without error.
    val result = traceService
      .traceFilter(TraceFilterRequest(
        fromBlock = Some(BlockParam.WithNumber(0)),
        toBlock = Some(BlockParam.WithNumber(5000)),
        fromAddress = None,
        toAddress = None,
        after = None,
        count = None
      ))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.traces shouldBe empty
  }

  // ===== traceReplayBlock =====

  it should "return ReplayResult per transaction for traceReplayBlock" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithTwoTxs, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(3), Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.results should have size 2
    result.toOption.get.results.foreach { r =>
      r.trace should not be empty
      r.stateDiff shouldBe None
      r.vmTrace shouldBe None
    }
  }

  it should "return error for missing block in traceReplayBlock" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(999), Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("left")
  }

  // ===== traceReplayTransaction =====

  it should "return ReplayResult for specific tx in traceReplayTransaction" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val result = traceService
      .traceReplayTransaction(TraceReplayTransactionRequest(txHash, Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    val replay = result.toOption.get.result
    replay.transactionHash shouldBe txHash
    replay.trace should not be empty
  }

  it should "return error for missing tx in traceReplayTransaction" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val unknownHash = ByteString(Hex.decode("ff" * 32))

    val result = traceService
      .traceReplayTransaction(TraceReplayTransactionRequest(unknownHash, Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("left")
  }

  // ===== traceTypes gating =====

  it should "return empty trace when traceTypes does not include 'trace'" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(1), Seq("stateDiff")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.results.foreach(_.trace shouldBe empty)
  }

  it should "return stateDiff = None when traceTypes does not include 'stateDiff'" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(1), Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.results.foreach(_.stateDiff shouldBe None)
  }

  it should "return vmTrace = None when traceTypes does not include 'vmTrace'" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(1), Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.results.foreach(_.vmTrace shouldBe None)
  }

  it should "populate stateDiff when traceTypes includes 'stateDiff'" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(1), Seq("stateDiff")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.results.foreach(_.stateDiff shouldBe defined)
  }

  it should "populate vmTrace when traceTypes includes 'vmTrace'" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    setupMockTraceForAnyTx(simpleTxTraceData)

    val result = traceService
      .traceReplayBlock(TraceReplayBlockRequest(BlockParam.WithNumber(1), Seq("vmTrace")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.results.foreach(_.vmTrace shouldBe defined)
  }

  // ===== revertReason =====

  it should "populate revertReason on root trace when tx reverts with Error(string)" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)

    // ABI-encoded data matching parseRevertReason's expected layout:
    // selector (4) + offset=0 (32) + length (32) + utf8 data (padded to 32)
    val message     = "transfer failed"
    val msgBytes    = message.getBytes("UTF-8")
    val padLen      = if (msgBytes.length % 32 == 0) 0 else 32 - (msgBytes.length % 32)
    val revertData  = ByteString(
      Array(0x08.toByte, 0xc3.toByte, 0x79.toByte, 0xa0.toByte) ++  // Error(string) selector
      Array.fill(32)(0.toByte) ++                                      // offset = 0
      Array.fill(31)(0.toByte) ++ Array(msgBytes.length.toByte) ++    // length
      msgBytes ++ Array.fill(padLen)(0.toByte)                         // string data padded
    )
    val revertTraceData = TxTraceData(
      txResult = TxResult(
        worldState = stubWorld,
        gasUsed = 21000,
        logs = Nil,
        vmReturnData = revertData,
        vmError = Some(com.chipprbots.ethereum.vm.RevertOccurs)
      ),
      internalTxs = Nil
    )
    setupMockTraceForAnyTx(revertTraceData)

    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    val result = traceService
      .traceReplayTransaction(TraceReplayTransactionRequest(txHash, Seq("trace")))
      .unsafeRunSync()
    result shouldBe Symbol("right")
    val replay = result.toOption.get.result
    replay.revertReason shouldBe defined
    replay.revertReason.get shouldBe message
  }

  // ===== root trace sender/to/gas =====

  it should "populate root trace sender from signed transaction" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val result = traceService.traceTransaction(TraceTransactionRequest(txHash)).unsafeRunSync()
    result shouldBe Symbol("right")
    val rootTrace = result.toOption.get.traces.head
    rootTrace.action.from shouldBe senderAddress
    rootTrace.action.to shouldBe Some(recipientAddress)
    rootTrace.action.gas shouldBe testTx1.tx.gasLimit
  }

  // ===== traceGet =====

  it should "return specific trace by index in traceGet" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    setupMockTraceForTx(blockWithOneTx, 0, traceDataWithInternalTx)

    val result = traceService.traceGet(TraceGetRequest(txHash, Seq(0))).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.trace shouldBe defined
    result.toOption.get.trace.get.traceAddress shouldBe Seq(0)
  }

  it should "return None for non-existent trace index in traceGet" taggedAs (UnitTest, RPCTest) in new TestSetup {
    storeBlockWithParent(blockWithOneTx, parentBlock)
    val txHash = blockWithOneTx.body.transactionList.head.hash
    storeTxMapping(txHash, blockWithOneTx.header.hash, 0)
    setupMockTraceForTx(blockWithOneTx, 0, simpleTxTraceData)

    val result = traceService.traceGet(TraceGetRequest(txHash, Seq(99))).unsafeRunSync()
    result shouldBe Symbol("right")
    result.toOption.get.trace shouldBe None
  }

  // ===== Test Setup =====

  class TestSetup extends EphemBlockchainTestSetup with SecureRandomBuilder {
    val mockBlockPreparator: BlockPreparator = mock[BlockPreparator]

    // --- Keypair and addresses ---

    val senderKeyPair = generateKeyPair(secureRandom)
    val senderAddress: Address = Address(
      kec256(senderKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(false).tail)
    )
    val recipientAddress: Address = Address(ByteString(Hex.decode("abcdef0123" * 4)))

    // --- State trie with sender account ---

    val emptyWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      blockchainConfig.accountStartNonce,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

    // Persist a world with the sender account so replayTransactions can find it
    val worldWithSender: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      emptyWorld.saveAccount(senderAddress, Account(nonce = UInt256.Zero, balance = UInt256(BigInt("10000000000000000000"))))
    )
    val persistedStateRoot: ByteString = ByteString(worldWithSender.stateRootHash)

    // --- Signed transactions ---

    val testTx1: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(
        nonce = 0,
        gasPrice = BigInt("20000000000"),
        gasLimit = 21000,
        receivingAddress = recipientAddress,
        value = BigInt("1000000000000000000"),
        payload = ByteString.empty
      ),
      senderKeyPair,
      Some(blockchainConfig.chainId)
    )

    val testTx2: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(
        nonce = 1,
        gasPrice = BigInt("20000000000"),
        gasLimit = 21000,
        receivingAddress = recipientAddress,
        value = BigInt("2000000000000000000"),
        payload = ByteString.empty
      ),
      senderKeyPair,
      Some(blockchainConfig.chainId)
    )

    // --- Block headers use the persisted state root ---

    val emptyOmmersHash: ByteString = ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))
    val emptyTrieHash: ByteString = ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))
    val zeros32: ByteString = ByteString(Hex.decode("00" * 32))
    val zeros8: ByteString = ByteString(Hex.decode("00" * 8))
    val zeros20: ByteString = ByteString(Hex.decode("00" * 20))
    val logsBloom256: ByteString = ByteString(Hex.decode("00" * 256))

    val parentBlock: Block = Block(
      header = BlockHeader(
        parentHash = zeros32,
        ommersHash = emptyOmmersHash,
        beneficiary = zeros20,
        stateRoot = persistedStateRoot,
        transactionsRoot = emptyTrieHash,
        receiptsRoot = emptyTrieHash,
        logsBloom = logsBloom256,
        difficulty = 1000,
        number = 0,
        gasLimit = 8000000,
        gasUsed = 0,
        unixTimestamp = 1000,
        extraData = ByteString.empty,
        mixHash = zeros32,
        nonce = zeros8
      ),
      body = BlockBody.empty
    )

    val blockWithOneTx: Block = Block(
      header = BlockHeader(
        parentHash = parentBlock.header.hash,
        ommersHash = emptyOmmersHash,
        beneficiary = zeros20,
        stateRoot = persistedStateRoot,
        transactionsRoot = zeros32,
        receiptsRoot = zeros32,
        logsBloom = logsBloom256,
        difficulty = 1000,
        number = 1,
        gasLimit = 8000000,
        gasUsed = 21000,
        unixTimestamp = 1001,
        extraData = ByteString.empty,
        mixHash = zeros32,
        nonce = zeros8
      ),
      body = BlockBody(Seq(testTx1), Nil)
    )

    val blockWithTwoTxs: Block = Block(
      header = BlockHeader(
        parentHash = parentBlock.header.hash,
        ommersHash = emptyOmmersHash,
        beneficiary = zeros20,
        stateRoot = persistedStateRoot,
        transactionsRoot = zeros32,
        receiptsRoot = zeros32,
        logsBloom = logsBloom256,
        difficulty = 1000,
        number = 3,
        gasLimit = 8000000,
        gasUsed = 42000,
        unixTimestamp = 1003,
        extraData = ByteString.empty,
        mixHash = zeros32,
        nonce = zeros8
      ),
      body = BlockBody(Seq(testTx1, testTx2), Nil)
    )

    val emptyBlock: Block = Block(
      header = BlockHeader(
        parentHash = parentBlock.header.hash,
        ommersHash = emptyOmmersHash,
        beneficiary = zeros20,
        stateRoot = persistedStateRoot,
        transactionsRoot = emptyTrieHash,
        receiptsRoot = emptyTrieHash,
        logsBloom = logsBloom256,
        difficulty = 1000,
        number = 2,
        gasLimit = 8000000,
        gasUsed = 0,
        unixTimestamp = 1002,
        extraData = ByteString.empty,
        mixHash = zeros32,
        nonce = zeros8
      ),
      body = BlockBody.empty
    )

    // Stub world for trace results — returned by mock to chain replays
    val stubWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      blockchainConfig.accountStartNonce,
      persistedStateRoot,
      noEmptyAccounts = false,
      ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
    )

    val traceService = new TraceService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mockBlockPreparator,
      storagesInstance.storages.transactionMappingStorage
    )

    // --- Trace data ---

    val simpleTxTraceData: TxTraceData = TxTraceData(
      txResult = TxResult(
        worldState = stubWorld,
        gasUsed = 21000,
        logs = Nil,
        vmReturnData = ByteString.empty,
        vmError = None
      ),
      internalTxs = Nil
    )

    val internalTx: InternalTransaction = InternalTransaction(
      opcode = CALL,
      from = senderAddress,
      to = Some(recipientAddress),
      gasLimit = 10000,
      data = ByteString.empty,
      value = 100
    )

    val traceDataWithInternalTx: TxTraceData = TxTraceData(
      txResult = TxResult(
        worldState = stubWorld,
        gasUsed = 42000,
        logs = Nil,
        vmReturnData = ByteString.empty,
        vmError = None
      ),
      internalTxs = Seq(internalTx)
    )

    // --- Helpers ---

    def storeBlockWithParent(block: Block, parent: Block): Unit = {
      blockchainWriter.storeBlock(parent).commit()
      blockchainWriter.save(parent, Nil, ChainWeight.totalDifficultyOnly(parent.header.difficulty), true)
      blockchainWriter.storeBlock(block).commit()
      blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(block.header.difficulty + parent.header.difficulty), true)
    }

    def storeTxMapping(txHash: ByteString, blockHash: ByteString, txIndex: Int): Unit = {
      storagesInstance.storages.transactionMappingStorage.put(txHash.toIndexedSeq, TransactionLocation(blockHash, txIndex)).commit()
    }

    def setupMockTraceForTx(block: Block, txIndex: Int, result: TxTraceData): Unit = {
      (mockBlockPreparator
        .executeTransactionWithTrace(_: SignedTransaction, _: Address, _: BlockHeader, _: InMemoryWorldStateProxy)(
          _: BlockchainConfig
        ))
        .expects(*, *, *, *, *)
        .returning(result)
        .anyNumberOfTimes()
    }

    def setupMockTraceForAnyTx(result: TxTraceData): Unit = {
      (mockBlockPreparator
        .executeTransactionWithTrace(_: SignedTransaction, _: Address, _: BlockHeader, _: InMemoryWorldStateProxy)(
          _: BlockchainConfig
        ))
        .expects(*, *, *, *, *)
        .returning(result)
        .anyNumberOfTimes()
    }
  }
}
