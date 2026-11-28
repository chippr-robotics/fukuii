package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST.JString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.TestService.*
import com.chipprbots.ethereum.jsonrpc.mcp.GetTransactionTool
import com.chipprbots.ethereum.jsonrpc.mcp.TransactionByHashResource
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testmode.TestModeComponentsProvider
import com.chipprbots.ethereum.testmode.TestModeNode
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.ByteStringUtils

/** B-507: the MCP transaction tool and resource and `test_getLogHash` follow a tx lookup only to a canonical block (as
  * `eth_getTransactionByHash` does, B-495).
  */
class CanonicalTxLookupReadersSpec extends AnyFlatSpec with Matchers:

  private class Setup extends EphemBlockchainTestSetup:
    val block: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val txHash: ByteString = block.body.transactionList.head.hash.value
    val hashHex: String = ByteStringUtils.hash2string(txHash)
    val logEntry: TxLogEntry = TxLogEntry(Address(1), Seq(ByteString(1)), ByteString(2))
    val receipt: Receipt = LegacyReceipt(HashOutcome(ByteString("r")), 42, BloomFilter(ByteString.empty), Seq(logEntry))

    def storeCanonicalWithLookup(): Unit =
      blockchainWriter.storeBlock(block).commit()
      blockchainWriter.storeReceipts(block.header.hash, Seq(receipt)).commit()
      storagesInstance.storages.transactionMappingStorage
        .put(txHash, TransactionMappingStorage.TransactionLocation(block.header.hash.value, 0))
        .commit()

    /** A sibling at the same height takes the canonical number-to-hash entry; the lookup still names `block`. */
    def reorgAway(): Unit =
      val sibling = Block(block.header.copy(extraData = ByteString("sibling")), BlockBody(Nil, Nil))
      blockchainWriter.storeBlock(sibling).commit()
      blockchainReader.getBlockHeaderByNumber(block.header.number.value).map(_.hash) shouldBe Some(sibling.header.hash)

    lazy val mcp: McpDependencies = McpDependencies(
      peerManager = null,
      syncController = null,
      blockchainReader = blockchainReader,
      blockchainConfig = blockchainConfig,
      nodeStatusHolder = new AtomicReference(null),
      transactionMappingStorage = storagesInstance.storages.transactionMappingStorage,
      scheduler = null: Scheduler
    )

    lazy val testService = new TestService(
      null: BlockchainImpl,
      blockchainReader,
      null: BlockchainWriter,
      null: StateStorage,
      null: EvmCodeStorage,
      null: ActorRef[PendingTransactionsManager.Command],
      MiningConfigs.miningConfig,
      null: TestModeComponentsProvider,
      storagesInstance.storages.transactionMappingStorage,
      null: TestModeNode,
      null: Scheduler
    )(using IORuntime.global)

    def tool(): String =
      GetTransactionTool.execute(Some(JObjectHash(hashHex)), mcp).unsafeRunSync()(using IORuntime.global)
    def resource(): String = TransactionByHashResource.read(hashHex, mcp).unsafeRunSync()(using IORuntime.global)
    def logHash(): ByteString =
      testService.getLogHash(GetLogHashRequest(txHash)).unsafeRunSync()(using IORuntime.global).toOption.get.logHash

  private def JObjectHash(h: String) =
    org.json4s.JsonAST.JObject("hash" -> JString(h))

  private val emptyLogHash: ByteString = new Setup().testService
    .getLogHash(GetLogHashRequest(ByteString(1)))
    .unsafeRunSync()(using IORuntime.global)
    .toOption
    .get
    .logHash

  "get_transaction" should "find a transaction whose block is canonical" taggedAs (UnitTest, RPCTest) in {
    val s = new Setup
    s.storeCanonicalWithLookup()
    val out = s.tool()
    out should include("Transaction Found")
    out should include(ByteStringUtils.hash2string(s.block.header.hash.value))
  }

  it should "report not found when the lookup names a block that is no longer canonical" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val s = new Setup
    s.storeCanonicalWithLookup()
    s.reorgAway()
    s.tool() should include("Transaction not found")
  }

  "fukuii://tx/{hash}" should "find a transaction whose block is canonical" taggedAs (UnitTest, RPCTest) in {
    val s = new Setup
    s.storeCanonicalWithLookup()
    s.resource() should include("\"transactionIndex\": 0")
  }

  it should "report not found when the lookup names a block that is no longer canonical" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val s = new Setup
    s.storeCanonicalWithLookup()
    s.reorgAway()
    s.resource() should include("Transaction not found")
  }

  "test_getLogHash" should "hash the logs of a transaction whose block is canonical" taggedAs (UnitTest, RPCTest) in {
    val s = new Setup
    s.storeCanonicalWithLookup()
    s.logHash() should not be emptyLogHash
  }

  it should "answer the empty-log hash when the lookup names a block that is no longer canonical" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val s = new Setup
    s.storeCanonicalWithLookup()
    s.reorgAway()
    s.logHash() shouldBe emptyLogHash
  }
