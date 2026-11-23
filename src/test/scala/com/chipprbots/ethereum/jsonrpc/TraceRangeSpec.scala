package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.DebugTracingService.*
import com.chipprbots.ethereum.jsonrpc.TraceService.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.testing.Tags.*

/** B-229: debug_traceChain is capped as trace_filter is, and both run a block again on a fresh state view when the
  * state moved under one read of it, so that one stale read does not fail the range.
  */
class TraceRangeSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockFactory:

  implicit val runtime: IORuntime = IORuntime.global

  private def stale(): StaleStateViewException =
    new StaleStateViewException(BlockRef(ByteString.empty, 1, ByteString.empty), 2, 4, "the disk moved on")

  "debug_traceChain" should "answer InvalidParams for a range over the cap" taggedAs (UnitTest, RPCTest) in (new Setup:
    chain((MaxTraceChainRange + 1), withTxs = false)
    val result: Either[JsonRpcError, Seq[TraceChainBlockResult]] = debugService
      .traceChainBlockRange(BlockParam.WithNumber(0), BlockParam.WithNumber((MaxTraceChainRange + 1)), TraceConfig())
      .unsafeRunSync()
    result shouldBe Left(JsonRpcError.InvalidParams(s"Requested range exceeds max of $MaxTraceChainRange blocks"))
  )

  it should "trace a range of exactly the cap" taggedAs (UnitTest, RPCTest) in (new Setup:
    chain(MaxTraceChainRange, withTxs = false)
    val result: Either[JsonRpcError, Seq[TraceChainBlockResult]] = debugService
      .traceChainBlockRange(BlockParam.WithNumber(0), BlockParam.WithNumber(MaxTraceChainRange), TraceConfig())
      .unsafeRunSync()
    result.map(_.size) shouldBe Right(MaxTraceChainRange.toInt)
  )

  it should "run a block again when one read of its state was stale, and trace the whole range" taggedAs (
    UnitTest,
    RPCTest
  ) in (new Setup:
    chain(3, withTxs = true)
    val calls = new AtomicInteger
    stubLedger { header =>
      // the second block's state goes stale on its first read only
      if header.number.value == 2 && calls.incrementAndGet() == 1 then throw stale()
    }
    val result: Either[JsonRpcError, Seq[TraceChainBlockResult]] = debugService
      .traceChainBlockRange(BlockParam.WithNumber(0), BlockParam.WithNumber(3), TraceConfig())
      .unsafeRunSync()
    result.map(_.map(_.block)) shouldBe Right(Seq(BigInt(1), BigInt(2), BigInt(3)))
    calls.get shouldBe 2
  )

  it should "answer StateMoved when the state is stale on every attempt" taggedAs (UnitTest, RPCTest) in (new Setup:
    chain(3, withTxs = true)
    val calls = new AtomicInteger
    stubLedger { header =>
      if header.number.value == 2 then
        calls.incrementAndGet(); throw stale()
    }
    val result: Either[JsonRpcError, Seq[TraceChainBlockResult]] = debugService
      .traceChainBlockRange(BlockParam.WithNumber(0), BlockParam.WithNumber(3), TraceConfig())
      .unsafeRunSync()
    result shouldBe Left(JsonRpcError.StateMoved)
    calls.get shouldBe StateRead.Attempts
  )

  it should "answer StateNotHeld at once for a state that is not held" taggedAs (UnitTest, RPCTest) in (new Setup:
    chain(3, withTxs = true)
    val calls = new AtomicInteger
    stubLedger { header =>
      if header.number.value == 2 then
        calls.incrementAndGet(); throw new StateUnavailable("not held")
    }
    val result: Either[JsonRpcError, Seq[TraceChainBlockResult]] = debugService
      .traceChainBlockRange(BlockParam.WithNumber(0), BlockParam.WithNumber(3), TraceConfig())
      .unsafeRunSync()
    result shouldBe Left(JsonRpcError.StateNotHeld)
    calls.get shouldBe 1
  )

  "trace_filter" should "run a block again when one read of its state was stale, and trace the whole range" taggedAs (
    UnitTest,
    RPCTest
  ) in (new Setup:
    chain(3, withTxs = true)
    val calls = new AtomicInteger
    stubLedger { header =>
      if header.number.value == 2 && calls.incrementAndGet() == 1 then throw stale()
    }
    val result: Either[JsonRpcError, TraceFilterResponse] =
      traceService.traceFilter(TraceFilterRequest(BlockParam.WithNumber(1), BlockParam.WithNumber(3))).unsafeRunSync()
    result.isRight shouldBe true
    calls.get shouldBe 2
  )

  it should "answer StateMoved when the state is stale on every attempt" taggedAs (UnitTest, RPCTest) in (new Setup:
    chain(3, withTxs = true)
    stubLedger(header => if header.number.value == 2 then throw stale())
    val result: Either[JsonRpcError, TraceFilterResponse] =
      traceService.traceFilter(TraceFilterRequest(BlockParam.WithNumber(1), BlockParam.WithNumber(3))).unsafeRunSync()
    result shouldBe Left(JsonRpcError.StateMoved)
  )

  class Setup extends EphemBlockchainTestSetup:
    val mockLedger: StxLedger = mock[StxLedger]
    val txMappingStorage: TransactionMappingStorage = mock[TransactionMappingStorage]

    lazy val debugService: DebugTracingService =
      new DebugTracingService(blockchain, blockchainReader, mining, mockLedger, txMappingStorage)
    lazy val traceService: TraceService =
      new TraceService(blockchain, blockchainReader, mining, mockLedger, txMappingStorage)

    /** Blocks 0..last on one chain, the last the best; each (but 0) with one transaction when `withTxs`. */
    def chain(last: Long, withTxs: Boolean): Unit =
      val fixture = Fixtures.Blocks.Block3125369
      val body = fixture.body.copy(transactionList = if withTxs then fixture.body.transactionList.take(1) else Nil)
      var parent = fixture.header.copy(number = BlockNumber(0), parentHash = BlockHash.Zero)
      blockchainWriter.save(
        Block(parent, body.copy(transactionList = Nil)),
        Nil,
        ChainWeight.totalDifficultyOnly(1),
        saveAsBestBlock = true
      )
      (1L to last).foreach { n =>
        val header = fixture.header.copy(number = BlockNumber(n), parentHash = parent.hash)
        blockchainWriter.save(Block(header, body), Nil, ChainWeight.totalDifficultyOnly(n + 1), saveAsBestBlock = true)
        parent = header
      }

    /** The ledger reads the state of each block's parent through `onRead`, which may throw as a read of it would. */
    def stubLedger(onRead: BlockHeader => Unit): Unit =
      mockLedger.advanceWorldToTx.expects(*, *, *, *).anyNumberOfTimes().onCall { (header, _, _, _) =>
        onRead(header)
        null.asInstanceOf[InMemoryWorldStateProxy]
      }
      mockLedger.replayTransaction.expects(*, *, *, *).anyNumberOfTimes().returning(null.asInstanceOf[TxResult])
