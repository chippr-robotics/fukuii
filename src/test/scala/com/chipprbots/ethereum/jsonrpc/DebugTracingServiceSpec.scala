package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.DebugTracingService._
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.ExecutionTracer

/** Unit tests for DebugTracingService.
  *
  * Besu reference: DebugTraceTransaction.java, DebugTraceBlock.java,
  *   DebugTraceBlockByNumber.java, DebugTraceCall.java
  *
  * core-geth reference: eth/tracers/api.go
  */
class DebugTracingServiceSpec
    extends TestKit(ActorSystem("DebugTracingServiceSpec"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience {

  implicit val runtime: IORuntime = IORuntime.global

  // ── traceTransaction ────────────────────────────────────────────────────────

  "DebugTracingService.traceTransaction" should
    "return InvalidParams when transaction is not found in mapping storage" taggedAs (UnitTest, RPCTest) in
    new TestSetup {
      val unknownHash: ByteString = ByteString(Array.fill(32)(0xff.toByte))
      (txMappingStorage.get _).expects(unknownHash).returning(None)

      val result = service
        .traceTransaction(TraceTransactionRequest(unknownHash))
        .unsafeRunSync()

      result.isLeft shouldBe true
    }

  it should "return InvalidParams when block hash is not in storage" taggedAs (UnitTest, RPCTest) in
    new TestSetup {
      val txHash: ByteString = block.body.transactionList.head.hash
      val missingBlockHash   = ByteString(Array.fill(32)(0xee.toByte))
      (txMappingStorage.get _).expects(txHash).returning(Some(TransactionLocation(missingBlockHash, 0)))

      val result = service
        .traceTransaction(TraceTransactionRequest(txHash))
        .unsafeRunSync()

      result.isLeft shouldBe true
    }

  it should "return a trace result for a valid transaction" taggedAs (UnitTest, RPCTest) in
    new TestSetup {
      val txHash: ByteString = block.body.transactionList.head.hash
      val txIndex             = 0

      blockchainWriter.storeBlock(block).commit()
      // Inject a parent header at the exact parentHash the block references
      storagesInstance.storages.blockHeadersStorage
        .put(block.header.parentHash, block.header.copy(number = block.header.number - 1))
        .commit()

      (txMappingStorage.get _).expects(txHash).returning(Some(TransactionLocation(block.header.hash, txIndex)))
      (mockLedger.advanceWorldToTx _).expects(*, *, *, *).returning(mockWorld)
      (mockLedger.simulateTransactionWithTracer(_: SignedTransactionWithSender, _: BlockHeader, _: Option[InMemoryWorldStateProxy], _: ExecutionTracer)).expects(*, *, *, *).returning(null.asInstanceOf[TxResult])

      val result = service
        .traceTransaction(TraceTransactionRequest(txHash))
        .unsafeRunSync()

      result.isRight shouldBe true
    }

  // ── traceBlockByHash ─────────────────────────────────────────────────────────

  "DebugTracingService.traceBlockByHash" should
    "return InvalidParams when block is not found" taggedAs (UnitTest, RPCTest) in new TestSetup {
      val unknownHash: ByteString = ByteString(Array.fill(32)(0xdd.toByte))

      val result = service
        .traceBlockByHash(TraceBlockByHashRequest(unknownHash))
        .unsafeRunSync()

      result.isLeft shouldBe true
    }

  it should "return an empty trace list for a block with no transactions" taggedAs (UnitTest, RPCTest) in
    new TestSetup {
      val emptyBlock = block.copy(body = block.body.copy(transactionList = Seq.empty))
      blockchainWriter.storeBlock(emptyBlock).commit()
      storagesInstance.storages.blockHeadersStorage
        .put(emptyBlock.header.parentHash, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
        .commit()

      val result = service
        .traceBlockByHash(TraceBlockByHashRequest(emptyBlock.header.hash))
        .unsafeRunSync()

      result shouldBe Right(TraceBlockByHashResponse(Seq.empty))
    }

  // ── traceBlockByNumber ───────────────────────────────────────────────────────

  "DebugTracingService.traceBlockByNumber" should
    "return an empty trace list for a block with no transactions" taggedAs (UnitTest, RPCTest) in
    new TestSetup {
      val emptyBlock = block.copy(body = block.body.copy(transactionList = Seq.empty))
      blockchainWriter.save(emptyBlock, Nil, ChainWeight.totalDifficultyOnly(emptyBlock.header.difficulty), saveAsBestBlock = true)
      storagesInstance.storages.blockHeadersStorage
        .put(emptyBlock.header.parentHash, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
        .commit()

      val result = service
        .traceBlockByNumber(TraceBlockByNumberRequest(BlockParam.WithNumber(emptyBlock.header.number)))
        .unsafeRunSync()

      result shouldBe Right(TraceBlockByNumberResponse(Seq.empty))
    }

  // ── traceCall ────────────────────────────────────────────────────────────────

  "DebugTracingService.traceCall" should
    "return a call trace result for the latest block" taggedAs (UnitTest, RPCTest) in new TestSetup {
      blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(block.header.difficulty), saveAsBestBlock = true)

      (mockLedger.simulateTransactionWithTracer(_: SignedTransactionWithSender, _: BlockHeader, _: Option[InMemoryWorldStateProxy], _: ExecutionTracer)).expects(*, *, *, *).returning(null.asInstanceOf[TxResult])

      val callTx = EthInfoService.CallTx(
        from = None,
        to = None,
        gas = None,
        gasPrice = 0,
        value = 0,
        data = ByteString.empty
      )
      val result = service
        .traceCall(TraceCallRequest(callTx, BlockParam.Latest))
        .unsafeRunSync()

      result.isRight shouldBe true
    }

  // ── TestSetup ────────────────────────────────────────────────────────────────

  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup {

    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)

    val mockLedger: StxLedger              = mock[StxLedger]
    val txMappingStorage: TransactionMappingStorage = mock[TransactionMappingStorage]
    val mockWorld = null.asInstanceOf[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy]

    lazy val service: DebugTracingService = new DebugTracingService(
      blockchain,
      blockchainReader,
      mining,
      mockLedger,
      txMappingStorage
    )
  }
}
