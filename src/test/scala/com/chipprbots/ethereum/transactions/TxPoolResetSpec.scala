package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
import com.chipprbots.ethereum.transactions.TxPoolReset.Reset

/** B-253: the pool is reset on a move of the head as go-ethereum's `LegacyPool.reset` resets it. */
// scalastyle:off magic.number
class TxPoolResetSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private val genesis: Block = BlockHelpers.genesis

  private def txs(blocks: Seq[Block]): Seq[Seq[SignedTransaction]] = blocks.map(_.body.transactionList)

  /** A fork at genesis: three blocks of the old chain, two of the new, and a tx of the old chain's second block that
    * the new chain's first block includes as well.
    */
  private trait Fork:
    val old: List[Block] = BlockHelpers.generateChain(3, genesis)
    val shared: SignedTransaction = old(1).body.transactionList.head
    private val newFirst: Block =
      val block = BlockHelpers.generateBlock(genesis)
      BlockHelpers.withTransactions(block, shared :: block.body.transactionList.toList)
    val added: List[Block] = newFirst :: BlockHelpers.generateChain(1, newFirst)
    // what neither chain shares: the old chain's own
    val lost: Seq[SignedTransaction] = old.flatMap(_.body.transactionList).filterNot(_ == shared)

    private lazy val stored: Map[BlockHash, Block] = (genesis :: old ++ added).map(b => b.hash -> b).toMap
    def headerOf(hash: BlockHash): Option[BlockHeader] = stored.get(hash).map(_.header)
    def bodyOf(hash: BlockHash): Option[BlockBody] = stored.get(hash).map(_.body)

  "TxPoolReset.ofBranches" should "drop the new chain's txs and take back the old chain's that it does not include" taggedAs UnitTest in new Fork:
    val reset: Reset = TxPoolReset.ofBranches(added, old)
    reset.included shouldBe txs(added)
    (reset.reinject should contain).theSameElementsInOrderAs(lost)
    reset.reinject should not contain shared

  it should "take nothing back when the heads are more than 64 blocks apart (go-ethereum's deep reorg)" taggedAs UnitTest in new Fork:
    val longer: List[Block] = added ++ BlockHelpers.generateChain(TxPoolReset.MaxReorgDepth + 3, added.last)
    // heads at 67 and 3: 64 apart is still taken back, as go-ethereum takes it; at 69, 66 apart, not
    (TxPoolReset.ofBranches(longer.dropRight(2), old).reinject should contain).theSameElementsInOrderAs(lost)
    val reset: Reset = TxPoolReset.ofBranches(longer, old)
    reset.reinject shouldBe empty
    reset.included shouldBe txs(longer)

  it should "take nothing back when nothing was moved off" taggedAs UnitTest in new Fork:
    TxPoolReset.ofBranches(added, Nil) shouldBe TxPoolReset.Reset(txs(added), Nil)

  "TxPoolReset.between" should "walk both chains to their common ancestor" taggedAs UnitTest in new Fork:
    val reset: Reset = TxPoolReset.between(Some(old.last.header), added.last.header, headerOf, bodyOf)
    reset.included shouldBe txs(added)
    (reset.reinject should contain).theSameElementsInOrderAs(lost)

  it should "drop the head's txs alone when it is a child of the pool's head, or the first head told" taggedAs UnitTest in new Fork:
    TxPoolReset.between(Some(old(1).header), old(2).header, headerOf, bodyOf) shouldBe
      TxPoolReset.Reset(txs(List(old(2))), Nil)
    TxPoolReset.between(None, old(2).header, headerOf, bodyOf) shouldBe TxPoolReset.Reset(txs(List(old(2))), Nil)
    TxPoolReset.between(Some(old(2).header), old(2).header, headerOf, bodyOf) shouldBe TxPoolReset.Reset.empty

  it should "drop every block moved up onto, and take nothing back, on a move up the chain" taggedAs UnitTest in new Fork:
    TxPoolReset.between(Some(genesis.header), old.last.header, headerOf, bodyOf) shouldBe
      TxPoolReset.Reset(txs(old), Nil)

  it should "take back the blocks moved down off, on a move down to an ancestor" taggedAs UnitTest in new Fork:
    TxPoolReset.between(Some(old.last.header), old.head.header, headerOf, bodyOf) shouldBe
      TxPoolReset.Reset(Nil, old.drop(1).flatMap(_.body.transactionList))

  it should "take nothing back, and drop the new head's newest 64 blocks, when the heads are more than 64 apart" taggedAs UnitTest in new Fork:
    val longer: List[Block] = added ++ BlockHelpers.generateChain(TxPoolReset.MaxReorgDepth + 3, added.last)
    val chain: Map[BlockHash, Block] = (genesis :: old ++ longer).map(b => b.hash -> b).toMap
    val reset: Reset =
      TxPoolReset.between(
        Some(old.last.header),
        longer.last.header,
        chain.get(_).map(_.header),
        chain.get(_).map(_.body)
      )
    reset.reinject shouldBe empty
    reset.included shouldBe txs(longer.takeRight(TxPoolReset.MaxReorgDepth))

  it should "take nothing back when two chains of equal height do not meet within 64 blocks" taggedAs UnitTest in new Fork:
    val oldLong: List[Block] = BlockHelpers.generateChain(TxPoolReset.MaxReorgDepth + 1, genesis)
    val newLong: List[Block] = BlockHelpers.generateChain(TxPoolReset.MaxReorgDepth + 1, genesis)
    val chain: Map[BlockHash, Block] = (genesis :: oldLong ++ newLong).map(b => b.hash -> b).toMap
    def from(o: List[Block], n: List[Block]): Reset =
      TxPoolReset.between(Some(o.last.header), n.last.header, chain.get(_).map(_.header), chain.get(_).map(_.body))
    val apart: Reset = from(oldLong, newLong)
    apart.reinject shouldBe empty
    apart.included shouldBe txs(newLong.takeRight(TxPoolReset.MaxReorgDepth))
    // 64 blocks each still meet, at genesis
    val (old64, new64) = (oldLong.take(TxPoolReset.MaxReorgDepth), newLong.take(TxPoolReset.MaxReorgDepth))
    from(old64, new64) shouldBe TxPoolReset.Reset(txs(new64), old64.flatMap(_.body.transactionList))

  it should "take nothing back when a block of the old chain is not stored (go-ethereum's unrooted chain)" taggedAs UnitTest in new Fork:
    val reset: Reset = TxPoolReset.between(
      Some(old.last.header),
      added.last.header,
      headerOf,
      hash => if hash == old(1).hash then None else bodyOf(hash)
    )
    reset.reinject shouldBe empty
    reset.included shouldBe txs(added)

  "TxPoolReset.tell" should "send the reinjected txs in one add before a removal per block included" taggedAs UnitTest in new Fork:
    val pool: TestProbe[Command] = createTestProbe[PendingTransactionsManager.Command]()
    TxPoolReset.tell(pool.ref, TxPoolReset.ofBranches(added, old))
    pool.expectMessage(PendingTransactionsManager.AddUncheckedTransactions(lost))
    added.foreach(b => pool.expectMessage(PendingTransactionsManager.RemoveTransactions(b.body.transactionList)))
    pool.expectNoMessage(100.millis)

  it should "send no add when nothing is reinjected" taggedAs UnitTest in new Fork:
    val pool: TestProbe[Command] = createTestProbe[PendingTransactionsManager.Command]()
    TxPoolReset.tell(pool.ref, TxPoolReset.ofBranches(added, Nil))
    added.foreach(b => pool.expectMessage(PendingTransactionsManager.RemoveTransactions(b.body.transactionList)))
    pool.expectNoMessage(100.millis)
