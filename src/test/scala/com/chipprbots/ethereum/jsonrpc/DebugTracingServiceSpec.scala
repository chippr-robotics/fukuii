package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.DebugTracingService.*
import com.chipprbots.ethereum.jsonrpc.EthInfoService.CallTx
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.ExecutionTracer

/** Unit tests for DebugTracingService.
  *
  * Besu reference: DebugTraceTransaction.java, DebugTraceBlock.java, DebugTraceBlockByNumber.java, DebugTraceCall.java
  *
  * core-geth reference: eth/tracers/api.go
  */
class DebugTracingServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience:

  implicit val runtime: IORuntime = IORuntime.global

  // ── traceTransaction ────────────────────────────────────────────────────────

  // "not found" is a server/application condition, code -32000, NOT a malformed-parameter
  // condition (-32602). The parameter was well-formed; the resource simply does not exist.
  // execution-apis pins -32000 for every one of these in its debug_trace* fixtures, and hive
  // diffs on the code alone (it strips the message), so the code is the whole assertion.
  // These tests previously asserted only isLeft, which is why the wrong code went unnoticed.
  private val ServerErrorCode = -32000

  "DebugTracingService.traceTransaction" should
    "return a -32000 server error when transaction is not found in mapping storage" taggedAs
    (UnitTest, RPCTest) in
    new TestSetup:
      val unknownHash: ByteString = ByteString(Array.fill(32)(0xff.toByte))
      txMappingStorage.get.expects(unknownHash).returning(None)

      val result: Either[JsonRpcError, TraceTransactionResponse] = service
        .traceTransaction(TraceTransactionRequest(unknownHash))
        .unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected Left")).code shouldBe ServerErrorCode

  it should "return a -32000 server error when block hash is not in storage" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val txHash: ByteString = block.body.transactionList.head.hash.value
      val missingBlockHash: ByteString = ByteString(Array.fill(32)(0xee.toByte))
      txMappingStorage.get.expects(txHash).returning(Some(TransactionLocation(missingBlockHash, 0)))

      val result: Either[JsonRpcError, TraceTransactionResponse] = service
        .traceTransaction(TraceTransactionRequest(txHash))
        .unsafeRunSync()

      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected Left")).code shouldBe ServerErrorCode

  it should "return a trace result for a valid transaction" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val txHash: ByteString = block.body.transactionList.head.hash.value
      val txIndex = 0

      blockchainWriter.storeBlock(block).commit()
      // Inject a parent header at the exact parentHash the block references
      storagesInstance.storages.blockHeadersStorage
        .put(block.header.parentHash.value, block.header.copy(number = block.header.number - 1))
        .commit()

      txMappingStorage.get.expects(txHash).returning(Some(TransactionLocation(block.header.hash.value, txIndex)))
      mockLedger.advanceWorldToTx.expects(*, *, *, *).returning(mockWorld)
      (mockLedger
        .simulateTransactionWithTracer(
          _: SignedTransactionWithSender,
          _: BlockHeader,
          _: Option[InMemoryWorldStateProxy],
          _: ExecutionTracer
        ))
        .expects(*, *, *, *)
        .returning(null.asInstanceOf[TxResult])

      val result: Either[JsonRpcError, TraceTransactionResponse] = service
        .traceTransaction(TraceTransactionRequest(txHash))
        .unsafeRunSync()

      result.isRight shouldBe true

  // ── traceBlockByHash ─────────────────────────────────────────────────────────

  "DebugTracingService.traceBlockByHash" should
    "return InvalidParams when block is not found" taggedAs (UnitTest, RPCTest) in new TestSetup:
      val unknownHash: ByteString = ByteString(Array.fill(32)(0xdd.toByte))

      val result: Either[JsonRpcError, TraceBlockByHashResponse] = service
        .traceBlockByHash(TraceBlockByHashRequest(unknownHash))
        .unsafeRunSync()

      result.isLeft shouldBe true

  it should "return an empty trace list for a block with no transactions" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val emptyBlock: Block = block.copy(body = block.body.copy(transactionList = Seq.empty))
      blockchainWriter.storeBlock(emptyBlock).commit()
      storagesInstance.storages.blockHeadersStorage
        .put(emptyBlock.header.parentHash.value, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
        .commit()

      val result: Either[JsonRpcError, TraceBlockByHashResponse] = service
        .traceBlockByHash(TraceBlockByHashRequest(emptyBlock.header.hash.value))
        .unsafeRunSync()

      result shouldBe Right(TraceBlockByHashResponse(Seq.empty))

  // ── traceBlockByNumber ───────────────────────────────────────────────────────

  "DebugTracingService.traceBlockByNumber" should
    "return an empty trace list for a block with no transactions" taggedAs (UnitTest, RPCTest) in
    new TestSetup:
      val emptyBlock: Block = block.copy(body = block.body.copy(transactionList = Seq.empty))
      blockchainWriter.save(
        emptyBlock,
        Nil,
        ChainWeight.totalDifficultyOnly(emptyBlock.header.difficulty.value),
        saveAsBestBlock = true
      )
      storagesInstance.storages.blockHeadersStorage
        .put(emptyBlock.header.parentHash.value, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
        .commit()

      val result: Either[JsonRpcError, TraceBlockByNumberResponse] = service
        .traceBlockByNumber(TraceBlockByNumberRequest(BlockParam.WithNumber(emptyBlock.header.number.value)))
        .unsafeRunSync()

      result shouldBe Right(TraceBlockByNumberResponse(Seq.empty))

  // ── traceCall ────────────────────────────────────────────────────────────────

  "DebugTracingService.traceCall" should
    "return a call trace result for the latest block" taggedAs (UnitTest, RPCTest) in new TestSetup:
      blockchainWriter.save(
        block,
        Nil,
        ChainWeight.totalDifficultyOnly(block.header.difficulty.value),
        saveAsBestBlock = true
      )

      (mockLedger
        .simulateTransactionWithTracer(
          _: SignedTransactionWithSender,
          _: BlockHeader,
          _: Option[InMemoryWorldStateProxy],
          _: ExecutionTracer
        ))
        .expects(*, *, *, *)
        .returning(null.asInstanceOf[TxResult])

      val callTx: CallTx = EthInfoService.CallTx(
        from = None,
        to = None,
        gas = None,
        gasPrice = 0,
        value = 0,
        data = ByteString.empty
      )
      val result: Either[JsonRpcError, TraceCallResponse] = service
        .traceCall(TraceCallRequest(callTx, BlockParam.Latest))
        .unsafeRunSync()

      result.isRight shouldBe true

  // ── intermediateRoots ────────────────────────────────────────────────────────

  "DebugTracingService.intermediateRoots" should
    "return InvalidParams when block is not found" taggedAs (UnitTest, RPCTest) in new TestSetup:
      import com.chipprbots.ethereum.jsonrpc.DebugTracingService.IntermediateRootsRequest
      val result: Either[JsonRpcError, IntermediateRootsResponse] = service
        .intermediateRoots(IntermediateRootsRequest(block.header.hash.value))
        .unsafeRunSync()
      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected Left")).message should include("Block not found")
      result.swap.getOrElse(fail("Expected Left")).code shouldBe ServerErrorCode

  it should "return empty list for a block with no transactions" taggedAs (UnitTest, RPCTest) in new TestSetup:
    import com.chipprbots.ethereum.jsonrpc.DebugTracingService.{IntermediateRootsRequest, IntermediateRootsResponse}
    val emptyBlock: Block = block.copy(body = block.body.copy(transactionList = Seq.empty))
    blockchainWriter.storeBlock(emptyBlock).commit()
    storagesInstance.storages.blockHeadersStorage
      .put(emptyBlock.header.parentHash.value, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
      .commit()

    val result: Either[JsonRpcError, IntermediateRootsResponse] = service
      .intermediateRoots(IntermediateRootsRequest(emptyBlock.header.hash.value))
      .unsafeRunSync()

    result shouldBe Right(IntermediateRootsResponse(Seq.empty))

  // ── TestSetup ────────────────────────────────────────────────────────────────

  class TestSetup() extends EphemBlockchainTestSetup:

    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)

    val mockLedger: StxLedger = mock[StxLedger]
    val txMappingStorage: TransactionMappingStorage = mock[TransactionMappingStorage]
    val mockWorld: InMemoryWorldStateProxy = null.asInstanceOf[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy]

    lazy val service: DebugTracingService = new DebugTracingService(
      blockchain,
      blockchainReader,
      mining,
      mockLedger,
      txMappingStorage
    )
