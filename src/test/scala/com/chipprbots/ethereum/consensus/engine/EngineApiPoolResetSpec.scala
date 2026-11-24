package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.AddUncheckedTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.RemoveTransactions
import com.chipprbots.ethereum.transactions.TxPoolReset

/** B-253: a forkchoiceUpdated that moves the head resets the pool from the head it was last told of, as go-ethereum's
  * `LegacyPool.reset` does on the chain head event: every block moved onto leaves the pool, and the txs of the blocks
  * moved off that the new chain does not include come back. Before, only the head block's txs left the pool and none
  * came back. On the hash store: the pool is told after the head is set, by code both layouts share, and
  * [[com.chipprbots.ethereum.transactions.TxPoolResetSpec]] walks the chains as the unified layout's moves leave them.
  */
// scalastyle:off magic.number
class EngineApiPoolResetSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private trait Setup extends EphemBlockchainTestSetup:
    val pool: TestProbe[Command] = createTestProbe[PendingTransactionsManager.Command]()
    lazy val forkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)
    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      mkBlockExecution(validators), // never executes here: every block is stored with its receipts
      forkChoiceManager,
      Some(pool.ref)
    )(using blockchainConfig, testKit.scheduler)

    val genesis: Block = BlockHelpers.genesis
    blockchainWriter.storeBlock(genesis).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

    // the old chain, three blocks; the new chain, two, whose first includes a tx of the old chain's second
    val old: List[Block] = BlockHelpers.generateChain(3, genesis)
    val shared: SignedTransaction = old(1).body.transactionList.head
    val newChain: List[Block] =
      val first = BlockHelpers.generateBlock(genesis)
      val withShared = BlockHelpers.withTransactions(first, shared :: first.body.transactionList.toList)
      withShared :: BlockHelpers.generateChain(1, withShared)
    // executed blocks, stored by hash: the fork choice makes them canonical
    (old ++ newChain).foreach { block =>
      blockchainWriter.storeBlockByHashOnly(block).commit()
      blockchainWriter.storeReceipts(block.hash, Nil).commit()
    }

    def headTo(block: Block): Unit =
      val answer = engineApi
        .forkchoiceUpdated(
          ForkChoiceState(block.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash),
          None
        )
        .unsafeRunSync()
      answer.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      blockchainReader.getBestBlockNumber shouldBe block.number.value

    def removed(blocks: Block*): Unit =
      blocks.foreach(b => pool.expectMessage(RemoveTransactions(b.body.transactionList)))

  "forkchoiceUpdated" should "drop the txs of every block the head moves up onto, not only the head's" taggedAs UnitTest in new Setup:
    headTo(old.head)
    removed(old.head)
    headTo(old.last)
    removed(old(1), old(2))
    pool.expectNoMessage(200.millis)

  it should "take back the txs of the blocks a reorganisation moves off that the new chain does not include" taggedAs UnitTest in new Setup:
    headTo(old.last)
    removed(old.last)
    headTo(newChain.last)
    pool.expectMessage(AddUncheckedTransactions(old.flatMap(_.body.transactionList).filterNot(_ == shared)))
    removed(newChain*)
    pool.expectNoMessage(200.millis)

  it should "take nothing back when the heads are more than 64 blocks apart (go-ethereum's deep reorg)" taggedAs UnitTest in new Setup:
    val longer: List[Block] = BlockHelpers.generateChain(TxPoolReset.MaxReorgDepth + 4, newChain.last)
    longer.foreach { block =>
      blockchainWriter.storeBlockByHashOnly(block).commit()
      blockchainWriter.storeReceipts(block.hash, Nil).commit()
    }
    headTo(old.last)
    removed(old.last)
    headTo(longer.last)
    removed((newChain ++ longer).takeRight(TxPoolReset.MaxReorgDepth)*)
    pool.expectNoMessage(200.millis)

  it should "tell the pool nothing for a fork choice that names the head it already has" taggedAs UnitTest in new Setup:
    headTo(old.last)
    removed(old.last)
    headTo(old.last)
    pool.expectNoMessage(200.millis)
