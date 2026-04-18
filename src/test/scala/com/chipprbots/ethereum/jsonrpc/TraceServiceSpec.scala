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
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.TraceService._
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for TraceService.
  *
  * Besu reference: TraceTransaction.java, TraceBlock.java,
  *   TraceReplayTransaction.java, TraceReplayBlockTransactions.java,
  *   TraceCall.java, TraceCallMany.java
  *
  * core-geth reference: eth/tracers/api.go
  */
class TraceServiceSpec
    extends TestKit(ActorSystem("TraceServiceSpec"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with MockFactory
    with ScalaFutures
    with NormalPatience {

  implicit val runtime: IORuntime = IORuntime.global

  // ── traceTransaction ────────────────────────────────────────────────────────

  "TraceService.traceTransaction" should
    "return InvalidParams when transaction is not found" taggedAs (UnitTest, RPCTest) in new TestSetup {
      val unknownHash: ByteString = ByteString(Array.fill(32)(0xff.toByte))
      (txMappingStorage.get _).expects(unknownHash).returning(None)

      val result = service
        .traceTransaction(TraceTransactionRequest(unknownHash))
        .unsafeRunSync()

      result.isLeft shouldBe true
    }

  it should "return flat trace array for a valid transaction" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val txHash: ByteString = block.body.transactionList.head.hash
    val txIndex             = 0

    blockchainWriter.storeBlock(block).commit()
    storagesInstance.storages.blockHeadersStorage
      .put(block.header.parentHash, block.header.copy(number = block.header.number - 1))
      .commit()

    (txMappingStorage.get _).expects(txHash).returning(Some(TransactionLocation(block.header.hash, txIndex)))
    (mockLedger.advanceWorldToTx _).expects(*, *, *, *).returning(mockWorld)
    (mockLedger.simulateTransactionWithTracer _).expects(*, *, *, *).returning(null.asInstanceOf[com.chipprbots.ethereum.ledger.TxResult])

    val result = service
      .traceTransaction(TraceTransactionRequest(txHash))
      .unsafeRunSync()

    result.isRight shouldBe true
  }

  // ── traceBlock ───────────────────────────────────────────────────────────────

  "TraceService.traceBlock" should
    "return empty trace list for a block with no transactions" taggedAs (UnitTest, RPCTest) in new TestSetup {
      val emptyBlock = block.copy(body = block.body.copy(transactionList = Seq.empty))
      blockchainWriter.storeBlock(emptyBlock).commit()
      storagesInstance.storages.blockHeadersStorage
        .put(emptyBlock.header.parentHash, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
        .commit()

      val result = service
        .traceBlock(TraceBlockRequest(BlockParam.WithHash(emptyBlock.header.hash)))
        .unsafeRunSync()

      result shouldBe Right(TraceBlockResponse(Seq.empty))
    }

  // ── replayTransaction ────────────────────────────────────────────────────────

  "TraceService.replayTransaction" should
    "return InvalidParams when transaction is not found" taggedAs (UnitTest, RPCTest) in new TestSetup {
      val unknownHash: ByteString = ByteString(Array.fill(32)(0xee.toByte))
      (txMappingStorage.get _).expects(unknownHash).returning(None)

      val result = service
        .replayTransaction(TraceReplayTransactionRequest(unknownHash, TraceOptions(trace = true)))
        .unsafeRunSync()

      result.isLeft shouldBe true
    }

  it should "return a replay result with trace option enabled" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val txHash: ByteString = block.body.transactionList.head.hash
    val txIndex             = 0

    blockchainWriter.storeBlock(block).commit()
    storagesInstance.storages.blockHeadersStorage
      .put(block.header.parentHash, block.header.copy(number = block.header.number - 1))
      .commit()

    (txMappingStorage.get _).expects(txHash).returning(Some(TransactionLocation(block.header.hash, txIndex)))
    (mockLedger.advanceWorldToTx _).expects(*, *, *, *).returning(mockWorld)
    (mockLedger.simulateTransactionWithTracer _).expects(*, *, *, *).returning(null.asInstanceOf[com.chipprbots.ethereum.ledger.TxResult]).anyNumberOfTimes()

    val result = service
      .replayTransaction(TraceReplayTransactionRequest(txHash, TraceOptions(trace = true)))
      .unsafeRunSync()

    result.isRight shouldBe true
  }

  // ── replayBlockTransactions ──────────────────────────────────────────────────

  "TraceService.replayBlockTransactions" should
    "return empty list for a block with no transactions" taggedAs (UnitTest, RPCTest) in new TestSetup {
      val emptyBlock = block.copy(body = block.body.copy(transactionList = Seq.empty))
      blockchainWriter.storeBlock(emptyBlock).commit()
      storagesInstance.storages.blockHeadersStorage
        .put(emptyBlock.header.parentHash, emptyBlock.header.copy(number = emptyBlock.header.number - 1))
        .commit()
      blockchainWriter.saveBestKnownBlocks(emptyBlock.header.hash, emptyBlock.header.number)

      val result = service
        .replayBlockTransactions(
          TraceReplayBlockTransactionsRequest(
            BlockParam.WithNumber(emptyBlock.header.number),
            TraceOptions(trace = true)
          )
        )
        .unsafeRunSync()

      result shouldBe Right(TraceReplayBlockTransactionsResponse(Seq.empty))
    }

  // ── traceCall ────────────────────────────────────────────────────────────────

  "TraceService.traceCall" should
    "return a call trace result for the latest block" taggedAs (UnitTest, RPCTest) in new TestSetup {
      blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(block.header.difficulty), saveAsBestBlock = true)

      (mockLedger.simulateTransactionWithTracer _).expects(*, *, *, *).returning(null.asInstanceOf[com.chipprbots.ethereum.ledger.TxResult])

      val callTx = EthInfoService.CallTx(
        from = None,
        to = None,
        gas = None,
        gasPrice = 0,
        value = 0,
        data = ByteString.empty
      )
      val result = service
        .traceCall(TraceCallRequest(callTx, TraceOptions(trace = true), BlockParam.Latest))
        .unsafeRunSync()

      result.isRight shouldBe true
    }

  // ── TestSetup ────────────────────────────────────────────────────────────────

  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup {

    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)

    val mockLedger: StxLedger              = mock[StxLedger]
    val txMappingStorage: TransactionMappingStorage = mock[TransactionMappingStorage]
    val mockWorld = null.asInstanceOf[com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy]

    lazy val service: TraceService = new TraceService(
      blockchain,
      blockchainReader,
      mining,
      mockLedger,
      txMappingStorage
    )
  }
}
