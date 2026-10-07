package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.collection.mutable

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.DataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The extends-best import path against a REAL basic-pruning (reference-counted) state storage and the real
  * `BlockExecution` / `ConsensusImpl` plumbing; only the EVM is replaced by a deterministic writer that persists the
  * trie once per "transaction", exactly where `BlockPreparator` does.
  *
  * What it pins, byte for byte on the database contents:
  *   - a batch whose block k fails (missing node, or invalid after execution) adopts blocks 0..k-1, leaves NOTHING of
  *     block k, and the retry from k gives the database an import of each block once would give (no re-application, so
  *     no skewed reference counts, no pruned live node);
  *   - a kill at any write boundary is the same, because a block's state, header, receipts, weight and best-block
  *     pointer are one atomic write;
  *   - nothing is undone by block number, so a shorter-heavier reorg that shares a node with the block it replaces
  *     keeps that node, across a restart;
  *   - a block the database holds always has its state, and a block that failed holds nothing (what the Engine API's
  *     "already known" and "parent validated" shortcuts key on).
  */
class ImportBatchAtomicitySpec extends AnyFlatSpec with Matchers with MockFactory:

  private val history = 2
  private val keyCount = 40
  private val lastBlock = 12

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String): ByteString = ByteString(s"$tag-padding-to-force-a-hashed-leaf-node-xxxxxxxx")

  sealed trait Failure

  /** MissingNodeException after `txs` transactions persisted. */
  final case class MissingAfter(txs: Int) extends Failure

  /** Invalid (state root / gas) after the whole block executed. */
  case object InvalidAfterExecution extends Failure

  /** A data source whose writes can be made to die: a kill at a write boundary. */
  class KillableDataSource extends EphemDataSource(Map.empty):
    /** `Some(n)`: n more updates succeed, then every update throws. */
    var updatesUntilKill: Option[Int] = None
    var updates = 0
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      updatesUntilKill match
        case Some(0) => throw new RuntimeException("simulated kill")
        case Some(n) =>
          updatesUntilKill = Some(n - 1); updates += 1; super.update(dataSourceUpdates)
        case None => updates += 1; super.update(dataSourceUpdates)

  trait KillableDataSourceComponent extends DataSourceComponent:
    val dataSource: KillableDataSource = new KillableDataSource

  class Setup extends EphemBlockchainTestSetup:
    trait BasicPruningBuilder extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
      override val pruningMode: PruningMode = BasicPruning(history)

    val comp: KillableDataSourceComponent & BasicPruningBuilder & Storages.DefaultStorages =
      new KillableDataSourceComponent
        with BasicPruningBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider
    val ds: KillableDataSource = comp.dataSource
    val bs = comp.storages
    val reader: BlockchainReader = BlockchainReader(bs)
    val writer: BlockchainWriter = BlockchainWriter(bs)

    /** What the database "knows": state root per block hash, and what each block writes, per transaction. */
    val roots: mutable.Map[ByteString, ByteString] = mutable.Map.empty
    val plans: mutable.Map[ByteString, Seq[(Int, String)]] = mutable.Map.empty
    var pendingFailure: Option[(ByteString, Failure)] = None

    private def defaultPlan(n: BigInt): Seq[(Int, String)] =
      val parity = (n % 2).toInt
      Seq(0 -> s"p$parity", (1 + (n % 5).toInt) -> s"n$n", 0 -> s"p${1 - parity}")

    class SimExecution(chain: BlockchainImpl)
        extends BlockExecution(chain, reader, writer, bs.evmCodeStorage, null, null):
      override protected[ledger] def executeAndValidateStaged(
          block: Block,
          alreadyValidated: Boolean,
          staged: StagedBlockState
      )(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
        val plan = plans.getOrElse(block.hash.value, defaultPlan(block.number.value))
        var root = roots(block.header.parentHash.value)
        val failure = pendingFailure.collect { case (h, f) if h == block.hash.value => f }
        var result: Option[Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])]] =
          None
        plan.zipWithIndex.foreach { case ((slot, tag), i) =>
          if result.isEmpty then
            // one transaction: persist the trie, straight into the (staged) storage
            root = ByteString(
              MerklePatriciaTrie[ByteString, ByteString](root.toArray, staged.storage)
                .put(key(slot), value(tag))
                .getRootHash
            )
            failure.foreach {
              case MissingAfter(txs) if txs == i + 1 =>
                pendingFailure = None
                result = Some(Left(MPTError(new MissingNodeException(block.hash.value))))
              case _ => ()
            }
        }
        result.getOrElse {
          if failure.contains(InvalidAfterExecution) then
            pendingFailure = None
            Left(ValidationAfterExecError("state root mismatch"))
          else
            roots(block.hash.value) = root
            Right((Nil, Nil, None))
        }

    class Node(val history: Int = ImportBatchAtomicitySpec.this.history):
      // a fresh process: new in-memory state (prune cursor, caches) over the same database
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
      val execution = new SimExecution(chain)
      val consensus = new ConsensusImpl(reader, writer, execution, reorgState = chain)

      def importBatch(blocks: List[Block]): ConsensusResult =
        consensus.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

      def importEach(blocks: List[Block]): Unit = blocks.foreach { b =>
        importBatch(List(b)) shouldBe a[ExtendedCurrentBestBranch]
      }

    def dbContent: Map[Seq[Byte], Seq[Byte]] =
      ds.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }.toMap

    /** Genesis: a trie of `keyCount` slots at block 0, stored as the best block. */
    def initGenesis(): Unit =
      val storage = bs.stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value(s"g$i"))
      }
      roots(BlockHelpers.genesis.hash.value) = ByteString(trie.getRootHash)
      writer.save(
        BlockHelpers.genesis,
        Nil,
        ChainWeight.zero.increase(BlockHelpers.genesis.header),
        saveAsBestBlock = true
      )

    /** Reads every slot of the state at `root` from the database, the way a later block's execution would. */
    def readWholeState(root: ByteString): Map[Int, Option[ByteString]] =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, bs.stateStorage.getReadOnlyStorage)
      (0 until keyCount).map(i => i -> trie.get(key(i))).toMap

    /** Everything the Engine API's "already known => VALID" shortcut relies on: a stored block has its state. Only the
      * blocks within the pruning window are checked: older state is pruned by design.
      */
    def assertStoredBlocksHaveState(chain: List[Block]): Unit =
      val best = reader.getBestBlockNumber
      chain.filter(b => reader.getBlockByHash(b.hash).isDefined && best - b.number.value < history).foreach { b =>
        noException should be thrownBy readWholeState(roots(b.hash.value))
      }

    def assertNothingOf(block: Block): Unit =
      reader.getBlockByHash(block.hash) shouldBe None
      reader.getReceiptsByHash(block.hash) shouldBe None
      reader.getChainWeightByHash(block.hash) shouldBe None
      reader.getCanonicalHashByNumber(block.number.value) shouldBe None

  /** Names the step in a failure that is not an assertion (an exception from deep inside execution). */
  private def phase[A](label: String)(body: => A): A =
    try body
    catch
      case t: Throwable if !t.isInstanceOf[org.scalatest.exceptions.TestFailedException] =>
        throw new RuntimeException(s"$label: ${t.getMessage}", t)

  private val chain: List[Block] = BlockHelpers.generateChain(lastBlock, BlockHelpers.genesis)

  /** The database after importing blocks 1..`upTo` one at a time: the reference every variant must equal. */
  private def referenceContent(upTo: Int): Map[Seq[Byte], Seq[Byte]] =
    val ref = new Setup
    ref.initGenesis()
    new ref.Node().importEach(chain.take(upTo))
    ref.dbContent

  // (first block of the batch, batch length, index within the batch of the failing block)
  private val cases = for
    start <- Seq(4, 7)
    length <- Seq(4)
    failIndex <- Seq(0, 1, 3)
    failure <- Seq(MissingAfter(1), MissingAfter(2), MissingAfter(3), InvalidAfterExecution)
  yield (start, length, failIndex, failure)

  cases.foreach { case (start, length, failIndex, failure) =>
    val title = s"A batch whose block $failIndex fails ($failure, batch $start+$length)"
    title should
      "adopt the validated prefix, leave nothing of the failing block, and retry to the single-application DB" taggedAs (
        UnitTest,
        DatabaseTest,
        ConsensusTest
      ) in new Setup:
        initGenesis()
        val node = new Node()
        node.importEach(chain.take(start - 1))
        val batch = chain.slice(start - 1, start - 1 + length)
        val failing = batch(failIndex)
        pendingFailure = Some(failing.hash.value -> failure)

        val result = node.importBatch(batch)

        failure match
          case MissingAfter(_) =>
            result match
              case ConsensusErrorDueToMissingNode(Nil, _, imported) =>
                imported.map(_.block) shouldBe batch.take(failIndex)
              case other => fail(s"expected ConsensusErrorDueToMissingNode, got $other")
          case InvalidAfterExecution =>
            if failIndex == 0 then result shouldBe a[BranchExecutionFailure]
            else result shouldBe a[ExtendedCurrentBestBranchPartially]

        // adopted: best moved to the last validated block, and is exactly what importing that prefix leaves
        val adoptedUpTo = start - 1 + failIndex
        reader.getBestBlockNumber shouldBe BigInt(adoptedUpTo)
        dbContent shouldBe referenceContent(adoptedUpTo)
        // the failing block left nothing: no block data, and (the line above) not one state byte
        assertNothingOf(failing)
        assertStoredBlocksHaveState(chain)

        // retry from the failing block: the whole import ends in the database a single application gives
        node.importBatch(chain.drop(adoptedUpTo)) shouldBe a[ExtendedCurrentBestBranch]
        dbContent shouldBe referenceContent(lastBlock)
        noException should be thrownBy readWholeState(roots(chain.last.hash.value))
  }

  "A kill at a write boundary" should "leave the database as if the block was never started (before the commit)" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in new Setup:
    initGenesis()
    new Node().importEach(chain.take(6))
    ds.updatesUntilKill = Some(0) // the next write, which is block 7's one atomic commit, dies
    an[RuntimeException] should be thrownBy new Node().importBatch(List(chain(6)))
    ds.updatesUntilKill = None
    dbContent shouldBe referenceContent(6)
    assertNothingOf(chain(6))

    // restart, and carry on from the failed block
    val restarted = new Node()
    restarted.importBatch(chain.drop(6)) shouldBe a[ExtendedCurrentBestBranch]
    dbContent shouldBe referenceContent(lastBlock)

  it should "leave block, state and best pointer all applied (after the commit): never applied-but-not-adopted" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in new Setup:
    initGenesis()
    new Node().importEach(chain.take(6))
    ds.updatesUntilKill = Some(1) // block 7's commit lands; the prune that follows it dies
    an[RuntimeException] should be thrownBy new Node().importBatch(List(chain(6)))
    ds.updatesUntilKill = None

    reader.getBestBlockNumber shouldBe BigInt(7)
    reader.getBlockByHash(chain(6).hash) shouldBe defined
    noException should be thrownBy readWholeState(roots(chain(6).hash.value))

    // restart: nothing is applied twice. The only residue of the kill is the one prune that did not run: every key the
    // reference has still holds the reference's value, and the extras are that block's death row / snapshots / dead nodes.
    new Node().importBatch(chain.drop(7)) shouldBe a[ExtendedCurrentBestBranch]
    val reference = referenceContent(lastBlock)
    val actual = dbContent
    val differing = reference.filter { case (k, v) => actual.get(k).exists(_ != v) }
    withClue("keys the reference has, with a DIFFERENT value (a skewed count): ")(differing shouldBe empty)
    val missing = reference.keySet.diff(actual.keySet)
    // a restarted node's prune cursor starts one block in, so the skipped prune is not retried; the reference's own
    // deletions may be present in `actual` as extras but nothing live may be absent
    withClue("keys the reference has that are ABSENT here (a live node lost): ")(missing shouldBe empty)
    noException should be thrownBy readWholeState(roots(chain.last.hash.value))

  "A shorter-heavier reorg that shares a node with the block it replaces" should
    "keep that node, and every other key, across a restart" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in new Setup:
      // PR #1464 swept the replaced block's snapshots by number on restart and deleted the shared node. Nothing here is
      // undone by number, so the reorg and the restart remove no key at all.
      //
      // This test stops before the prune reaches dr2; the reference-count side of an abandoned block (its decrements
      // stay applied unless undone) is pinned by the two "abandoned block" tests below, which prune past it.
      initGenesis()
      val node = new Node()
      val oldChain = chain.take(3)
      // a3 writes slot 9 = SHARED; the replacing b2 writes the very same leaf
      plans(oldChain(2).hash.value) = Seq(9 -> "shared")
      phase("importing the old chain")(node.importEach(oldChain))
      val heavy = BlockHelpers.generateChain(
        1,
        oldChain(0),
        b => b.copy(header = b.header.copy(difficulty = Difficulty(BigInt(10).pow(12))))
      )
      val b2 = heavy.head
      plans(b2.hash.value) = Seq(9 -> "shared")

      val beforeReorg = dbContent
      phase("importing the reorg block")(node.importBatch(List(b2)) shouldBe a[SelectedNewBestBranch])
      reader.getBestBlock.map(_.hash) shouldBe Some(b2.hash)
      // (the canonical index legitimately drops height 3; the TRIE namespace must lose nothing)
      val nodeNamespace = 'n'.toByte
      withClue("trie keys the reorg removed: ")(
        beforeReorg.keySet.filter(_.head == nodeNamespace).diff(dbContent.keySet) shouldBe empty
      )

      // restart, then one block on top of the reorg block (it prunes block 1 only)
      val restarted = new Node()
      val c3 = BlockHelpers.generateChain(1, b2).head
      phase("importing the block after the reorg")(restarted.importEach(List(c3)))

      readWholeState(roots(c3.hash.value))(9) shouldBe Some(value("shared"))
      readWholeState(roots(b2.hash.value))(9) shouldBe Some(value("shared"))

  private val heavyAdjust: BigInt => Block => Block = d =>
    b => b.copy(header = b.header.copy(difficulty = Difficulty(d)))

  /** Blocks on top of `parent` that each write one slot nobody else touches (slot 20+n), so they never rewrite a node
    * of the sibling branches under test.
    */
  private def extendQuiet(setup: Setup, parent: Block, count: Int): List[Block] =
    val blocks = BlockHelpers.generateChain(count, parent)
    blocks.foreach(b => setup.plans(b.hash.value) = Seq((20 + b.number.value.toInt) -> s"q${b.number}"))
    blocks

  "An abandoned sibling block" should
    "not leave the node only it replaced pruned while the new canonical chain still uses it" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in new Setup:
      // a2 replaces the leaf of slot 3; the heavier sibling b2 (same parent) does not touch it, so slot 3's old leaf is
      // live in b2's state. Executing a2 left that leaf at zero references on dr2; the reorg to b2 used to keep it
      // there, and the prune of dr2 (`history` blocks later) deleted a node the canonical chain reads.
      initGenesis()
      val node = new Node()
      val a1 = chain.head
      val a2 = chain(1)
      plans(a1.hash.value) = Seq(1 -> "a1")
      plans(a2.hash.value) = Seq(3 -> "aOnly")
      phase("importing the old chain")(node.importEach(List(a1, a2)))
      val b2 = BlockHelpers.generateChain(1, a1, heavyAdjust(BigInt(10).pow(12))).head
      plans(b2.hash.value) = Seq(7 -> "bOnly")
      phase("reorganising to the sibling")(node.importBatch(List(b2)) shouldBe a[SelectedNewBestBranch])
      reader.getBestBlock.map(_.hash) shouldBe Some(b2.hash)

      // carry on from b2 well past the point where dr2 is pruned (history = 2)
      val tail = extendQuiet(this, b2, 5)
      phase("extending the new chain")(node.importEach(tail))

      val finalState = phase("reading the final state")(readWholeState(roots(tail.last.hash.value)))
      finalState(3) shouldBe Some(value("g3"))
      finalState(7) shouldBe Some(value("bOnly"))
      finalState(1) shouldBe Some(value("a1"))

  it should "be undone again, and its changes put back, when a new branch builds on it" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in new Setup:
    // a1 a2 | b2 (heavier) | a3 on top of a2 (heaviest): the node goes back to the a-branch WITHOUT executing a2 again,
    // so a2's changes (undone when b2 won) must be put back, and b2's taken out.
    initGenesis()
    val node = new Node()
    val a1 = chain.head
    val a2 = chain(1)
    plans(a1.hash.value) = Seq(1 -> "a1")
    plans(a2.hash.value) = Seq(3 -> "aOnly")
    node.importEach(List(a1, a2))
    val b2 = BlockHelpers.generateChain(1, a1, heavyAdjust(BigInt(10).pow(12))).head
    plans(b2.hash.value) = Seq(7 -> "bOnly")
    phase("reorganising to b2")(node.importBatch(List(b2)) shouldBe a[SelectedNewBestBranch])
    val a3 = BlockHelpers.generateChain(1, a2, heavyAdjust(BigInt(10).pow(13))).head
    plans(a3.hash.value) = Seq(8 -> "a3")
    phase("reorganising back through a2")(node.importBatch(List(a3)) shouldBe a[SelectedNewBestBranch])
    reader.getBestBlock.map(_.hash) shouldBe Some(a3.hash)

    val tail = extendQuiet(this, a3, 5)
    phase("extending the a chain")(node.importEach(tail))

    val finalState = phase("reading the final state")(readWholeState(roots(tail.last.hash.value)))
    finalState(3) shouldBe Some(value("aOnly"))
    finalState(8) shouldBe Some(value("a3"))
    finalState(7) shouldBe Some(value("g7"))
    finalState(1) shouldBe Some(value("a1"))

  "A failed p2p batch" should "never leave a block the Engine API would call known without its state" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in new Setup:
    initGenesis()
    val node = new Node()
    node.importEach(chain.take(3))
    val batch = chain.slice(3, 8)
    pendingFailure = Some(batch(2).hash.value -> MissingAfter(2))
    node.importBatch(batch) shouldBe a[ConsensusErrorDueToMissingNode]
    // newPayload answers VALID without executing for a block it already holds, and treats a parent with receipts as a
    // validated parent: so every held block must have state, and the failed block (and its descendants) must hold none
    // of block, receipts, weight or number mapping.
    assertStoredBlocksHaveState(chain)
    batch.drop(2).foreach(assertNothingOf)
