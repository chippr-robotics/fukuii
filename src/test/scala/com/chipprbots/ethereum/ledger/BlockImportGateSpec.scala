package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The Engine API's `newPayload` (`executeAndValidateBlockFull`) and the regular-sync importer
  * (`executeAndValidateBlocks`) racing on the SAME block hash, against a real reference-counted state storage: exactly
  * one execution and one commit, and both callers get the correct result.
  *
  * Deterministic: the first execution parks on a latch; the second caller is started only after the first is inside,
  * and the test waits (by thread state, never by sleeping) until it is either parked behind the first (the gate) or has
  * finished (no gate), before releasing the first.
  */
class BlockImportGateSpec extends AnyFlatSpec with Matchers:

  private val history = 4
  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String): ByteString = ByteString(s"$tag-padding-to-force-a-hashed-leaf-node-xxxxxxxx")

  class Setup extends EphemBlockchainTestSetup:
    trait BasicPruningBuilder extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
      override val pruningMode: PruningMode = BasicPruning(history)

    val ds: EphemDataSource = EphemDataSource()
    trait EphemDataSourceComponent extends com.chipprbots.ethereum.db.components.DataSourceComponent:
      val dataSource: EphemDataSource = ds

    val comp: EphemDataSourceComponent & BasicPruningBuilder & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with BasicPruningBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider
    val bs = comp.storages
    val reader: BlockchainReader = BlockchainReader(bs)
    val writer: BlockchainWriter = BlockchainWriter(bs)
    val stateStorage = new ReferenceCountedStateStorage(bs.nodeStorage, history)
    val chain: BlockchainImpl = new BlockchainImpl(
      bs.blockHeadersStorage,
      bs.blockBodiesStorage,
      bs.blockNumberMappingStorage,
      bs.receiptStorage,
      bs.chainWeightStorage,
      bs.transactionMappingStorage,
      bs.appStateStorage,
      stateStorage,
      reader
    )

    val genesisRoot: ByteString =
      val trie = (0 until 20).foldLeft(MerklePatriciaTrie[ByteString, ByteString](stateStorage.getBackingStorage(0))) {
        (t, i) => t.put(key(i), value(s"g$i"))
      }
      ByteString(trie.getRootHash)
    writer.save(
      BlockHelpers.genesis,
      Nil,
      ChainWeight.zero.increase(BlockHelpers.genesis.header),
      saveAsBestBlock = true
    )
    val block: Block = BlockHelpers.generateChain(1, BlockHelpers.genesis).head

    val executions = new AtomicInteger(0)

    /** The first execution parks on `release` after announcing itself on `firstInside`; others run straight through. */
    val firstInside = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    class GatedExecution extends BlockExecution(chain, reader, writer, bs.evmCodeStorage, null, null):
      override protected[ledger] def executeAndValidateStaged(
          b: Block,
          alreadyValidated: Boolean,
          staged: StagedBlockState
      )(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
        val nth = executions.incrementAndGet()
        // replaces a slot: the old leaf's count goes down, the new one's up, in this block's own writes
        MerklePatriciaTrie[ByteString, ByteString](genesisRoot.toArray, staged.storage).put(key(0), value("b1"))
        if nth == 1 then
          firstInside.countDown()
          release.await(30, TimeUnit.SECONDS) shouldBe true
        Right((Nil, Seq(ByteString("requests")), None))

    val execution = new GatedExecution

    def dbContent: Map[Seq[Byte], Seq[Byte]] =
      ds.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }.toMap

    def refCounts: Seq[Int] = com.chipprbots.ethereum.db.storage.RefCountInspector.refCounts(ds)

  private def parkedOrFinished(t: Thread): Boolean =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while System.nanoTime() < deadline && t.getState != Thread.State.WAITING && t.getState != Thread.State.TERMINATED
    do Thread.`yield`()
    t.getState == Thread.State.WAITING || t.getState == Thread.State.TERMINATED

  private def race(
      first: Setup => Any,
      second: Setup => Any
  ): (Setup, Any, Any) =
    val s = new Setup
    @volatile var r1: Any = null
    @volatile var r2: Any = null
    val t1 = new Thread(() => r1 = first(s))
    val t2 = new Thread(() => r2 = second(s))
    t1.start()
    s.firstInside.await(20, TimeUnit.SECONDS) shouldBe true
    t2.start()
    parkedOrFinished(t2) shouldBe true
    s.release.countDown()
    t1.join(30000)
    t2.join(30000)
    t1.isAlive shouldBe false
    t2.isAlive shouldBe false
    (s, r1, r2)

  private def engine(s: Setup)(implicit c: BlockchainConfig) =
    s.execution.executeAndValidateBlockFull(s.block, alreadyValidated = true)
  private def importer(s: Setup)(implicit c: BlockchainConfig) =
    s.execution.executeAndValidateBlocks(List(s.block), ChainWeight.zero.increase(BlockHelpers.genesis.header))

  private def reference(run: Setup => Any): Setup =
    val s = new Setup
    s.release.countDown()
    run(s)
    s

  "Block execution" should "run and commit once when Engine newPayload and the importer race on the same hash (engine first)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    // (the outer Setup only supplies the implicit BlockchainConfig)
    given BlockchainConfig = blockchainConfig
    val (s, r1, r2) = race(engine(_), importer(_))
    s.executions.get() shouldBe 1
    val single = reference(engine(_))
    s.refCounts.min shouldBe 0
    s.refCounts.max shouldBe 1
    // the same state a single execution leaves (the importer additionally stores its block records)
    s.refCounts.sorted shouldBe single.refCounts.sorted
    val Right((receipts, requests, _)) = r1: @unchecked
    val (blocks, error) = r2.asInstanceOf[(List[BlockData], Option[BlockExecutionError])]
    error shouldBe None
    blocks.map(_.block.hash) shouldBe List(s.block.hash)
    // the importer got the engine's result (receipts and requests are not recomputed)
    blocks.head.receipts shouldBe receipts
    requests shouldBe Seq(ByteString("requests"))
    s.reader.getBlockByHash(s.block.hash) shouldBe defined

  it should "run and commit once when the importer is first and Engine newPayload arrives while it executes" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    given BlockchainConfig = blockchainConfig
    val (s, r1, r2) = race(importer(_), engine(_))
    s.executions.get() shouldBe 1
    s.refCounts.min shouldBe 0
    s.refCounts.max shouldBe 1
    r1.asInstanceOf[(List[BlockData], Option[BlockExecutionError])]._2 shouldBe None
    // the engine caller still gets the requests the one execution derived
    val Right((_, requests, _)) = r2: @unchecked
    requests shouldBe Seq(ByteString("requests"))

  it should "execute again after the block's state was undone (reorg, #1471): the gate reuses nothing stale" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    given BlockchainConfig = blockchainConfig
    release.countDown()
    execution.executeAndValidateBlockFull(block, alreadyValidated = true).isRight shouldBe true
    executions.get() shouldBe 1
    // a second attempt while the block is still applied reuses it
    execution.executeAndValidateBlockFull(block, alreadyValidated = true).isRight shouldBe true
    executions.get() shouldBe 1
    chain.abandonBlockStates(Seq((block.header.number.value, block.header.hash.value)))
    execution.executeAndValidateBlockFull(block, alreadyValidated = true).isRight shouldBe true
    executions.get() shouldBe 2
    refCounts.min shouldBe 0
    refCounts.max shouldBe 1
