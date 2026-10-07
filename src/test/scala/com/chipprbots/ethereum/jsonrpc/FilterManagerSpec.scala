package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.blocks.PendingBlock
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.FilterManager.BlockFilterChanges
import com.chipprbots.ethereum.jsonrpc.FilterManager.BlockFilterLogs
import com.chipprbots.ethereum.jsonrpc.FilterManager.FilterChanges
import com.chipprbots.ethereum.jsonrpc.FilterManager.FilterLogs
import com.chipprbots.ethereum.jsonrpc.FilterManager.LogFilterChanges
import com.chipprbots.ethereum.jsonrpc.FilterManager.LogFilterLogs
import com.chipprbots.ethereum.jsonrpc.FilterManager.NewFilterResponse
import com.chipprbots.ethereum.jsonrpc.FilterManager.PendingTransactionFilterLogs
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.BloomFilter as LedgerBloomFilter
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.utils.FilterConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

class FilterManagerSpec
    extends ScalaTestWithActorTestKit(ManualTime.config)
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with NormalPatience
    with org.scalamock.scalatest.MockFactory:

  val manualTime: ManualTime = ManualTime()

  "FilterManager" should "handle log filter logs and changes" taggedAs (UnitTest, RPCTest) in new TestSetup:

    val address: Address = Address("0x1234")
    val topics: Seq[Seq[ByteString]] = Seq(Seq(), Seq(ByteString(Hex.decode("4567"))))

    (() => blockchainReader.getBestBlockNumber).expects().returning(3)

    val createProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NewFilterResponse] =
      testKit.createTestProbe[NewFilterResponse]()
    filterManager ! FilterManager.NewLogFilter(
      Some(BlockParam.WithNumber(1)),
      Some(BlockParam.Latest),
      Some(Seq(address)),
      topics,
      createProbe.ref
    )
    val createResp: NewFilterResponse = createProbe.expectMessageType[NewFilterResponse]

    val logs1: Seq[TxLogEntry] = Seq(TxLogEntry(Address("0x4567"), Nil, ByteString()))
    val bh1: BlockHeader =
      blockHeader.copy(number = BlockNumber(1), logsBloom = BloomFilter(LedgerBloomFilter.create(logs1)))

    val logs2: Seq[TxLogEntry] = Seq(
      TxLogEntry(
        Address("0x1234"),
        Seq(ByteString("can be any"), ByteString(Hex.decode("4567"))),
        ByteString(Hex.decode("99aaff"))
      )
    )
    val bh2: BlockHeader =
      blockHeader.copy(number = BlockNumber(2), logsBloom = BloomFilter(LedgerBloomFilter.create(logs2)))

    val bh3: BlockHeader =
      blockHeader.copy(number = BlockNumber(3), logsBloom = BloomFilter(LedgerBloomFilter.create(Nil)))

    (() => blockchainReader.getBestBlockNumber).expects().returning(3).twice()
    blockchainReader.getBlockHeaderByNumber.expects(bh1.number.value).returning(Some(bh1))
    blockchainReader.getBlockHeaderByNumber.expects(bh2.number.value).returning(Some(bh2))
    blockchainReader.getBlockHeaderByNumber.expects(bh3.number.value).returning(Some(bh3))

    val bb2: BlockBody = BlockBody(
      transactionList = Seq(
        SignedTransaction(
          tx = LegacyTransaction(
            nonce = 0,
            gasPrice = GasPrice(123),
            gasLimit = GasAmount(123),
            receivingAddress = Address("0x1234"),
            value = 0,
            payload = ByteString()
          ),
          signature = ECDSASignature(0, 0, 27)
        )
      ),
      uncleNodesList = Nil
    )

    blockchainReader.getBlockBodyByHash.expects(bh2.hash).returning(Some(bb2))
    blockchainReader.getReceiptsByHash
      .expects(bh2.hash)
      .returning(
        Some(
          Seq(
            LegacyReceipt.withHashOutcome(
              postTransactionStateHash = ByteString(),
              cumulativeGasUsed = 0,
              logsBloomFilter = BloomFilter(LedgerBloomFilter.create(logs2)),
              logs = logs2
            )
          )
        )
      )

    val logsProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe.ref)
    val logsResp: LogFilterLogs = logsProbe.expectMessageType[LogFilterLogs]

    logsResp.logs.size shouldBe 1
    logsResp.logs.head shouldBe FilterManager.TxLog(
      logIndex = 0,
      transactionIndex = 0,
      transactionHash = bb2.transactionList.head.hash.value,
      blockHash = bh2.hash.value,
      blockNumber = bh2.number.value,
      address = Address(0x1234),
      data = ByteString(Hex.decode("99aaff")),
      topics = logs2.head.logTopics,
      blockTimestamp = Some(BigInt(bh2.unixTimestamp.toLong))
    )

    // same best block, no new logs
    (() => blockchainReader.getBestBlockNumber).expects().returning(3).twice()

    val changesProbe1: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterChanges] =
      testKit.createTestProbe[FilterChanges]()
    filterManager ! FilterManager.GetFilterChanges(createResp.id, changesProbe1.ref)
    val changesResp1: LogFilterChanges = changesProbe1.expectMessageType[LogFilterChanges]

    changesResp1.logs.size shouldBe 0

    // new block with new logs
    (() => blockchainReader.getBestBlockNumber).expects().returning(4).twice()

    val log4_1: TxLogEntry = TxLogEntry(
      Address("0x1234"),
      Seq(ByteString("can be any"), ByteString(Hex.decode("4567"))),
      ByteString(Hex.decode("99aaff"))
    )
    val log4_2: TxLogEntry = TxLogEntry(
      Address("0x123456"),
      Seq(ByteString("can be any"), ByteString(Hex.decode("4567"))),
      ByteString(Hex.decode("99aaff"))
    ) // address doesn't match

    val bh4: BlockHeader =
      blockHeader.copy(number = BlockNumber(4), logsBloom = BloomFilter(LedgerBloomFilter.create(Seq(log4_1, log4_2))))

    blockchainReader.getBlockHeaderByNumber.expects(BigInt(4)).returning(Some(bh4))

    val bb4: BlockBody = BlockBody(
      transactionList = Seq(
        SignedTransaction(
          tx = LegacyTransaction(
            nonce = 0,
            gasPrice = GasPrice(123),
            gasLimit = GasAmount(123),
            receivingAddress = Address("0x1234"),
            value = 0,
            payload = ByteString()
          ),
          signature = ECDSASignature(0, 0, 27)
        ),
        SignedTransaction(
          tx = LegacyTransaction(
            nonce = 0,
            gasPrice = GasPrice(123),
            gasLimit = GasAmount(123),
            receivingAddress = Address("0x123456"),
            value = 0,
            payload = ByteString()
          ),
          signature = ECDSASignature(0, 0, 27)
        )
      ),
      uncleNodesList = Nil
    )

    blockchainReader.getBlockBodyByHash.expects(bh4.hash).returning(Some(bb4))
    blockchainReader.getReceiptsByHash
      .expects(bh4.hash)
      .returning(
        Some(
          Seq(
            LegacyReceipt.withHashOutcome(
              postTransactionStateHash = ByteString(),
              cumulativeGasUsed = 0,
              logsBloomFilter = BloomFilter(LedgerBloomFilter.create(Seq(log4_1))),
              logs = Seq(log4_1)
            ),
            LegacyReceipt.withHashOutcome(
              postTransactionStateHash = ByteString(),
              cumulativeGasUsed = 0,
              logsBloomFilter = BloomFilter(LedgerBloomFilter.create(Seq(log4_2))),
              logs = Seq(log4_2)
            )
          )
        )
      )

    val changesProbe2: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterChanges] =
      testKit.createTestProbe[FilterChanges]()
    filterManager ! FilterManager.GetFilterChanges(createResp.id, changesProbe2.ref)
    val changesResp2: LogFilterChanges = changesProbe2.expectMessageType[LogFilterChanges]

    changesResp2.logs.size shouldBe 1

  it should "render blockTimestamp as an unsigned BigInt for timestamps at or above 2^63" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:

    val address: Address = Address("0x1234")
    val topics: Seq[Seq[ByteString]] = Seq(Seq(), Seq(ByteString(Hex.decode("4567"))))

    (() => blockchainReader.getBestBlockNumber).expects().returning(2)

    val createProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NewFilterResponse] =
      testKit.createTestProbe[NewFilterResponse]()
    filterManager ! FilterManager.NewLogFilter(
      Some(BlockParam.WithNumber(2)),
      Some(BlockParam.WithNumber(2)),
      Some(Seq(address)),
      topics,
      createProbe.ref
    )
    val createResp: NewFilterResponse = createProbe.expectMessageType[NewFilterResponse]

    val logsExtreme: Seq[TxLogEntry] = Seq(
      TxLogEntry(
        Address("0x1234"),
        Seq(ByteString("can be any"), ByteString(Hex.decode("4567"))),
        ByteString(Hex.decode("99aaff"))
      )
    )
    // 2^64 - 2: the uint64 bit pattern that reads as -2 through a signed Long. Must render as
    // the unsigned decimal 18446744073709551614, never as a negative BigInt.
    val bhExtreme: BlockHeader = blockHeader.copy(
      number = BlockNumber(2),
      logsBloom = BloomFilter(LedgerBloomFilter.create(logsExtreme)),
      unixTimestamp = Timestamp(-2L)
    )

    (() => blockchainReader.getBestBlockNumber).expects().returning(2).twice()
    blockchainReader.getBlockHeaderByNumber.expects(bhExtreme.number.value).returning(Some(bhExtreme))

    val bbExtreme: BlockBody = BlockBody(
      transactionList = Seq(
        SignedTransaction(
          tx = LegacyTransaction(
            nonce = 0,
            gasPrice = GasPrice(123),
            gasLimit = GasAmount(123),
            receivingAddress = Address("0x1234"),
            value = 0,
            payload = ByteString()
          ),
          signature = ECDSASignature(0, 0, 27)
        )
      ),
      uncleNodesList = Nil
    )

    blockchainReader.getBlockBodyByHash.expects(bhExtreme.hash).returning(Some(bbExtreme))
    blockchainReader.getReceiptsByHash
      .expects(bhExtreme.hash)
      .returning(
        Some(
          Seq(
            LegacyReceipt.withHashOutcome(
              postTransactionStateHash = ByteString(),
              cumulativeGasUsed = 0,
              logsBloomFilter = BloomFilter(LedgerBloomFilter.create(logsExtreme)),
              logs = logsExtreme
            )
          )
        )
      )

    val logsProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe.ref)
    val logsResp: LogFilterLogs = logsProbe.expectMessageType[LogFilterLogs]

    logsResp.logs.size shouldBe 1
    logsResp.logs.head.blockTimestamp shouldBe Some(BigInt("18446744073709551614"))

  it should "handle pending block filter" taggedAs (UnitTest, RPCTest) in new TestSetup:

    val address: Address = Address("0x1234")
    val topics: Seq[Seq[ByteString]] = Seq(Seq(), Seq(ByteString(Hex.decode("4567"))))

    (() => blockchainReader.getBestBlockNumber).expects().returning(3)

    val createProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NewFilterResponse] =
      testKit.createTestProbe[NewFilterResponse]()
    filterManager ! FilterManager.NewLogFilter(
      Some(BlockParam.WithNumber(1)),
      Some(BlockParam.Pending),
      Some(Seq(address)),
      topics,
      createProbe.ref
    )
    val createResp: NewFilterResponse = createProbe.expectMessageType[NewFilterResponse]

    val logs: Seq[TxLogEntry] = Seq(
      TxLogEntry(
        Address("0x1234"),
        Seq(ByteString("can be any"), ByteString(Hex.decode("4567"))),
        ByteString(Hex.decode("99aaff"))
      )
    )
    val bh: BlockHeader =
      blockHeader.copy(number = BlockNumber(1), logsBloom = BloomFilter(LedgerBloomFilter.create(logs)))

    (() => blockchainReader.getBestBlockNumber).expects().returning(1).anyNumberOfTimes()
    blockchainReader.getBlockHeaderByNumber.expects(bh.number.value).returning(Some(bh))
    val bb: BlockBody = BlockBody(
      transactionList = Seq(
        SignedTransaction(
          tx = LegacyTransaction(
            nonce = 0,
            gasPrice = GasPrice(123),
            gasLimit = GasAmount(123),
            receivingAddress = Address("0x1234"),
            value = 0,
            payload = ByteString()
          ),
          signature = ECDSASignature(0, 0, 27)
        )
      ),
      uncleNodesList = Nil
    )

    blockchainReader.getBlockBodyByHash.expects(bh.hash).returning(Some(bb))
    blockchainReader.getReceiptsByHash
      .expects(bh.hash)
      .returning(
        Some(
          Seq(
            LegacyReceipt.withHashOutcome(
              postTransactionStateHash = ByteString(),
              cumulativeGasUsed = 0,
              logsBloomFilter = BloomFilter(LedgerBloomFilter.create(logs)),
              logs = logs
            )
          )
        )
      )

    val logs2: Seq[TxLogEntry] = Seq(
      TxLogEntry(
        Address("0x1234"),
        Seq(ByteString("another log"), ByteString(Hex.decode("4567"))),
        ByteString(Hex.decode("99aaff"))
      )
    )
    val bh2: BlockHeader =
      blockHeader.copy(number = BlockNumber(2), logsBloom = BloomFilter(LedgerBloomFilter.create(logs2)))
    val blockTransactions2: Seq[SignedTransaction] = Seq(
      SignedTransaction(
        tx = LegacyTransaction(
          nonce = 0,
          gasPrice = GasPrice(321),
          gasLimit = GasAmount(321),
          receivingAddress = Address("0x1234"),
          value = 0,
          payload = ByteString()
        ),
        signature = ECDSASignature(0, 0, 27)
      )
    )
    val block2: Block = Block(bh2, BlockBody(blockTransactions2, Nil))
    (() => blockGenerator.getPendingBlock)
      .expects()
      .returning(
        Some(
          PendingBlock(
            block2,
            Seq(
              LegacyReceipt.withHashOutcome(
                postTransactionStateHash = ByteString(),
                cumulativeGasUsed = 0,
                logsBloomFilter = BloomFilter(LedgerBloomFilter.create(logs2)),
                logs = logs2
              )
            )
          )
        )
      )

    val logsProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe.ref)
    val logsResp: LogFilterLogs = logsProbe.expectMessageType[LogFilterLogs]

    logsResp.logs.size shouldBe 2
    logsResp.logs.head shouldBe FilterManager.TxLog(
      logIndex = 0,
      transactionIndex = 0,
      transactionHash = bb.transactionList.head.hash.value,
      blockHash = bh.hash.value,
      blockNumber = bh.number.value,
      address = Address(0x1234),
      data = ByteString(Hex.decode("99aaff")),
      topics = logs.head.logTopics,
      blockTimestamp = Some(BigInt(bh.unixTimestamp.toLong))
    )

    logsResp.logs(1) shouldBe FilterManager.TxLog(
      logIndex = 0,
      transactionIndex = 0,
      transactionHash = block2.body.transactionList.head.hash.value,
      blockHash = block2.header.hash.value,
      blockNumber = block2.header.number.value,
      address = Address(0x1234),
      data = ByteString(Hex.decode("99aaff")),
      topics = logs2.head.logTopics,
      blockTimestamp = Some(BigInt(block2.header.unixTimestamp.toLong))
    )

  it should "handle block filter" taggedAs (UnitTest, RPCTest) in new TestSetup:

    (() => blockchainReader.getBestBlockNumber).expects().returning(3).twice()

    val createProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NewFilterResponse] =
      testKit.createTestProbe[NewFilterResponse]()
    filterManager ! FilterManager.NewBlockFilter(createProbe.ref)
    val createResp: NewFilterResponse = createProbe.expectMessageType[NewFilterResponse]

    (() => blockchainReader.getBestBlockNumber).expects().returning(3)

    val logsProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe.ref)
    val getLogsRes: BlockFilterLogs = logsProbe.expectMessageType[BlockFilterLogs]

    getLogsRes.blockHashes.size shouldBe 0

    (() => blockchainReader.getBestBlockNumber).expects().returning(6)

    val bh4: BlockHeader = blockHeader.copy(number = BlockNumber(4))
    val bh5: BlockHeader = blockHeader.copy(number = BlockNumber(5))
    val bh6: BlockHeader = blockHeader.copy(number = BlockNumber(6))

    blockchainReader.getBlockHeaderByNumber.expects(BigInt(4)).returning(Some(bh4))
    blockchainReader.getBlockHeaderByNumber.expects(BigInt(5)).returning(Some(bh5))
    blockchainReader.getBlockHeaderByNumber.expects(BigInt(6)).returning(Some(bh6))

    val changesProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterChanges] =
      testKit.createTestProbe[FilterChanges]()
    filterManager ! FilterManager.GetFilterChanges(createResp.id, changesProbe.ref)
    val getChangesRes: BlockFilterChanges = changesProbe.expectMessageType[BlockFilterChanges]

    getChangesRes.blockHashes shouldBe Seq(bh4.hash, bh5.hash, bh6.hash)

  it should "handle pending transactions filter" taggedAs (UnitTest, RPCTest) in new TestSetup:

    (() => blockchainReader.getBestBlockNumber).expects().returning(3).twice()

    val createProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NewFilterResponse] =
      testKit.createTestProbe[NewFilterResponse]()
    filterManager ! FilterManager.NewPendingTransactionFilter(createProbe.ref)
    val createResp: NewFilterResponse = createProbe.expectMessageType[NewFilterResponse]

    val tx: LegacyTransaction = LegacyTransaction(
      nonce = 0,
      gasPrice = GasPrice(123),
      gasLimit = GasAmount(123),
      receivingAddress = Address("0x1234"),
      value = 0,
      payload = ByteString()
    )

    val stx: SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, None), Address(keyPair))
    val pendingTxs: Seq[SignedTransactionWithSender] = Seq(stx)

    (() => keyStore.listAccounts).expects().returning(Right(List(stx.senderAddress)))

    val logsProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe.ref)

    ptmProbe.expectMsgPF() { case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
      replyTo ! PendingTransactionsManager.PendingTransactionsResponse(pendingTxs.map(PendingTransaction(_, 0)))
    }

    val getLogsRes: PendingTransactionFilterLogs = logsProbe.expectMessageType[PendingTransactionFilterLogs]
    getLogsRes.txHashes shouldBe pendingTxs.map(_.tx.hash)

  it should "timeout unused filter" taggedAs (UnitTest, RPCTest) in new TestSetup:

    (() => blockchainReader.getBestBlockNumber).expects().returning(3).twice()

    val createProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[NewFilterResponse] =
      testKit.createTestProbe[NewFilterResponse]()
    filterManager ! FilterManager.NewPendingTransactionFilter(createProbe.ref)
    val createResp: NewFilterResponse = createProbe.expectMessageType[NewFilterResponse]

    val tx: LegacyTransaction = LegacyTransaction(
      nonce = 0,
      gasPrice = GasPrice(123),
      gasLimit = GasAmount(123),
      receivingAddress = Address("0x1234"),
      value = 0,
      payload = ByteString()
    )

    val stx: SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, None), Address(keyPair))
    val pendingTxs: Seq[SignedTransactionWithSender] = Seq(stx)

    (() => keyStore.listAccounts).expects().returning(Right(List(stx.senderAddress)))

    val logsProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe.ref)

    ptmProbe.expectMsgPF() { case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
      replyTo ! PendingTransactionsManager.PendingTransactionsResponse(pendingTxs.map(PendingTransaction(_, 0)))
    }

    // the filter should work
    val getLogsRes: PendingTransactionFilterLogs = logsProbe.expectMessageType[PendingTransactionFilterLogs]
    getLogsRes.txHashes shouldBe pendingTxs.map(_.tx.hash)

    manualTime.timePasses(26.seconds) // Exceeds longTimeout (25s) — filter should be evicted

    // the filter should no longer exist
    val logsProbe2: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FilterLogs] =
      testKit.createTestProbe[FilterLogs]()
    filterManager ! FilterManager.GetFilterLogs(createResp.id, logsProbe2.ref)

    ptmProbe.expectNoMessage()

    logsProbe2.expectMessage(LogFilterLogs(Nil))

  class TestSetup extends SecureRandomBuilder:

    val filterConfig: FilterConfig = new FilterConfig:
      override val filterTimeout: FiniteDuration = Timeouts.longTimeout
      override val filterManagerQueryTimeout: FiniteDuration = Timeouts.longTimeout

    val txPoolConfig: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 30
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.longTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.normalTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout

    val keyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)

    val blockchainReader: BlockchainReader = mock[BlockchainReader]
    val blockchain: BlockchainImpl = mock[BlockchainImpl]
    val keyStore: KeyStore = mock[KeyStore]
    val blockGenerator: BlockGenerator = mock[BlockGenerator]
    val ptmProbe: TestProbe = TestProbe()(testKit.system.classicSystem)

    val filterManager: ActorRef[FilterManager.Command] = testKit.spawn(
      FilterManager(
        blockchainReader,
        blockGenerator,
        keyStore,
        ptmProbe.ref.toTyped[PendingTransactionsManager.Command],
        filterConfig,
        txPoolConfig
      )
    )

    val blockHeader: BlockHeader = BlockHeader(
      parentHash =
        BlockHash(ByteString(Hex.decode("fd07e36cfaf327801e5696134b36678f6a89fb1e8f017f2411a29d0ae810ab8b"))),
      ommersHash =
        BlockHash(ByteString(Hex.decode("7766c4251396a6833ccbe4be86fbda3a200dccbe6a15d80ae3de5378b1540e04"))),
      beneficiary = ByteString(Hex.decode("1b7047b4338acf65be94c1a3e8c5c9338ad7d67c")),
      stateRoot = TrieRoot(ByteString(Hex.decode("52ce0ff43d7df2cf39f8cb8832f94d2280ebe856d84d8feb7b2281d3c5cfb990"))),
      transactionsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(
        ByteString(
          Hex.decode(
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
          )
        )
      ),
      difficulty = Difficulty(BigInt("17864037202")),
      number = BlockNumber(1),
      gasLimit = GasAmount(5000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1438270431),
      extraData = ByteString(Hex.decode("426974636f696e2069732054484520426c6f636b636861696e2e")),
      mixHash = BlockHash(ByteString(Hex.decode("c6d695926546d3d679199303a6d1fc983fe3f09f44396619a24c4271830a7b95"))),
      nonce = ByteString(Hex.decode("62bc3dca012c1b27"))
    )
