package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostShanghai
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

// scalastyle:off magic.number
/** The Engine API half of the reorg reference-count fix (#76 follow-up F1), against a REAL basic-pruning
  * (reference-counted) state storage and the real `EngineApiService` / `ForkChoiceManager` / `ConsensusImpl`; only the
  * EVM is replaced by a deterministic writer that persists the trie once per "transaction".
  *
  * Invariant under test: a block's reference-count record is applied exactly while the block is canonical. Each case
  * ends by pruning well past the height of the competing blocks (a regular-sync import prunes; `engine_newPayload` does
  * not) and then reading the WHOLE canonical state: a node left at zero references by a block that is not canonical
  * would be pruned and the read would fail with MissingNode.
  *
  * Every case has a control that runs the same sequence with `ReorgStateHandler.NoOp` and must fail, so the cases are
  * known to exercise the defect and not merely pass.
  */
class EngineReorgRefCountSpec extends AnyFlatSpec with Matchers:

  implicit private val ioRuntime: IORuntime = IORuntime.global

  private val history = 2
  private val keyCount = 40

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String): ByteString = ByteString(s"$tag-padding-to-force-a-hashed-leaf-node-xxxxxxxx")

  /** `undoEnabled = false` wires `ReorgStateHandler.NoOp` into the Engine API service and fork choice manager. */
  private class Fixture(undoEnabled: Boolean) extends EphemBlockchainTestSetup:
    trait BasicPruningBuilder extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
      override val pruningMode: PruningMode = BasicPruning(history)

    val comp
        : BasicPruningBuilder & com.chipprbots.ethereum.db.components.DataSourceComponent & Storages.DefaultStorages =
      new com.chipprbots.ethereum.db.components.EphemDataSourceComponent
        with BasicPruningBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider
    val bs = comp.storages
    val reader: BlockchainReader = BlockchainReader(bs)
    val writer: BlockchainWriter = BlockchainWriter(bs)

    val roots: mutable.Map[ByteString, ByteString] = mutable.Map.empty
    val plans: mutable.Map[ByteString, Seq[(Int, String)]] = mutable.Map.empty

    class SimExecution(chain: BlockchainImpl)
        extends BlockExecution(chain, reader, writer, bs.evmCodeStorage, null, null):
      override protected[ledger] def executeAndValidateStaged(
          block: Block,
          alreadyValidated: Boolean,
          staged: StagedBlockState
      )(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
        var root = roots(block.header.parentHash.value)
        plans.getOrElse(block.hash.value, Nil).foreach { case (slot, tag) =>
          root = ByteString(
            MerklePatriciaTrie[ByteString, ByteString](root.toArray, staged.storage)
              .put(key(slot), value(tag))
              .getRootHash
          )
        }
        roots(block.hash.value) = root
        Right((Nil, Nil, None))

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
    val handler: ReorgStateHandler = if undoEnabled then chain else ReorgStateHandler.NoOp
    val fcm = new ForkChoiceManager(reader, writer, handler)
    val service = new EngineApiService(reader, writer, execution, fcm, None, reorgState = handler)(
      blockchainConfig,
      null
    )

    /** p2p import side: prunes on every saved block, as regular sync does. */
    def consensus(consensusLayerHead: Boolean = false): ConsensusImpl =
      new ConsensusImpl(
        reader,
        writer,
        execution,
        designatedHead =
          Option.when(consensusLayerHead)(DesignatedHead(() => Some(ByteString(Array.fill(32)(1.toByte))))),
        reorgState = handler
      )

    val genesis: Block =
      val g = BlockHelpers.genesis
      g.copy(header =
        g.header.copy(
          difficulty = Difficulty.Zero,
          extraFields = HefPostShanghai(baseFee = BigInt(1000000000), withdrawalsRoot = BlockHeader.EmptyMpt)
        )
      )

    def initGenesis(): Unit =
      val storage = bs.stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value(s"g$i"))
      }
      roots(genesis.hash.value) = ByteString(trie.getRootHash)
      writer.save(genesis, Nil, ChainWeight.zero.increase(genesis.header), saveAsBestBlock = true)

    /** A post-merge block on `parent`; `salt` makes siblings differ. Header fields are exactly what `payloadToBlock`
      * rebuilds from the payload, so the payload's block hash is the header's own.
      */
    def mkBlock(parent: Block, salt: Int, writes: Seq[(Int, String)]): Block =
      val header = parent.header.copy(
        parentHash = parent.hash,
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = Address(salt).bytes,
        stateRoot = TrieRoot(ByteString(Array.fill(32)(salt.toByte))),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        difficulty = Difficulty.Zero,
        number = BlockNumber(parent.number.value + 1),
        gasUsed = GasAmount(0),
        unixTimestamp = parent.header.unixTimestamp + 12,
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(Array.fill(32)(salt.toByte))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostShanghai(
          baseFee = BaseFeeCalculator.calcBaseFee(parent.header, blockchainConfig),
          withdrawalsRoot = BlockHeader.EmptyMpt
        )
      )
      val block = Block(header, BlockBody(Nil, Nil, Some(Nil)))
      plans(block.hash.value) = writes
      block

    private def payloadOf(b: Block): ExecutionPayload =
      val h = b.header
      ExecutionPayload(
        parentHash = h.parentHash.value,
        feeRecipient = Address(h.beneficiary),
        stateRoot = h.stateRoot.value,
        receiptsRoot = h.receiptsRoot.value,
        logsBloom = h.logsBloom.value,
        prevRandao = h.mixHash.value,
        blockNumber = h.number.value,
        gasLimit = h.gasLimit.value,
        gasUsed = h.gasUsed.value,
        timestamp = h.unixTimestamp.toLong,
        extraData = h.extraData,
        baseFeePerGas = h.baseFee.get,
        blockHash = h.hash.value,
        transactions = Nil,
        withdrawals = Some(Nil)
      )

    def newPayload(b: Block): PayloadStatus =
      val status = service.newPayload(payloadOf(b)).unsafeRunSync()
      withClue(s"newPayload #${b.number}: ${status.validationError}")(status.status shouldBe PayloadStatus.Valid)
      status.status

    def forkchoice(b: Block): Unit =
      fcm.applyForkChoiceState(ForkChoiceState(b.hash.value, b.hash.value, genesis.hash.value)) shouldBe Right(())
      reader.getBestBlock.map(_.hash) shouldBe Some(b.hash)

    /** `count` quiet blocks (each writes a slot nobody else touches) imported the regular-sync way, which prunes. */
    def extendQuiet(parent: Block, count: Int, consensusLayerHead: Boolean = false): Block =
      val node = consensus(consensusLayerHead)
      (1 to count).foldLeft(parent) { (p, i) =>
        val n = mkBlock(p, 100 + i, Seq((20 + p.number.value.toInt + 1) -> s"q${p.number + 1}"))
        node.evaluateBranch(NonEmptyList.one(n)).unsafeRunSync()
        reader.getBestBlock.map(_.hash) shouldBe Some(n.hash)
        n
      }

    def readWholeState(root: ByteString): Map[Int, Option[ByteString]] =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, bs.stateStorage.getReadOnlyStorage)
      (0 until keyCount).map(i => i -> trie.get(key(i))).toMap

  // ---- scenarios, shared by the case and its control -------------------------------------------------------------

  /** a2 changes slot 3; the sibling b2 changes slot 7 and is made canonical by forkchoiceUpdated. */
  private def headMovesToSibling(f: Fixture): Map[Int, Option[ByteString]] =
    import f.*
    initGenesis()
    val a1 = mkBlock(genesis, 1, Seq(1 -> "a1"))
    val a2 = mkBlock(a1, 2, Seq(3 -> "aOnly"))
    newPayload(a1); forkchoice(a1)
    newPayload(a2); forkchoice(a2)
    val b2 = mkBlock(a1, 3, Seq(7 -> "bOnly")) // sibling of a2: executed as a side payload
    newPayload(b2)
    forkchoice(b2) // the head moves to the sibling
    val tip = extendQuiet(b2, 5)
    readWholeState(roots(tip.hash.value))

  /** s2 (slot 3) is a side payload nobody ever makes canonical; the canonical chain is a1, a2 (slot 7) and onward. */
  private def sidePayloadNeverAdopted(f: Fixture): Map[Int, Option[ByteString]] =
    import f.*
    initGenesis()
    val a1 = mkBlock(genesis, 1, Seq(1 -> "a1"))
    val a2 = mkBlock(a1, 2, Seq(7 -> "aOnly"))
    newPayload(a1); forkchoice(a1)
    newPayload(a2); forkchoice(a2)
    val s2 = mkBlock(a1, 3, Seq(3 -> "sOnly"))
    newPayload(s2) // executed, never canonical
    val tip = extendQuiet(a2, 5)
    readWholeState(roots(tip.hash.value))

  /** a2 canonical, forkchoiceUpdated to the sibling b2, then back to a2. */
  private def headMovesAndBack(f: Fixture): Map[Int, Option[ByteString]] =
    import f.*
    initGenesis()
    val a1 = mkBlock(genesis, 1, Seq(1 -> "a1"))
    val a2 = mkBlock(a1, 2, Seq(3 -> "aOnly"))
    newPayload(a1); forkchoice(a1)
    newPayload(a2); forkchoice(a2)
    val b2 = mkBlock(a1, 3, Seq(7 -> "bOnly"))
    newPayload(b2)
    forkchoice(b2) // A -> B
    forkchoice(a2) // B -> A, no re-execution: a2's changes are put back, b2's taken out
    val tip = extendQuiet(a2, 5)
    readWholeState(roots(tip.hash.value))

  /** An Engine payload extended the head (index entry written ahead of the best block); a different block imported at
    * that height by regular sync replaces it.
    */
  private def payloadDisplacedByImport(f: Fixture): Map[Int, Option[ByteString]] =
    import f.*
    initGenesis()
    val a1 = mkBlock(genesis, 1, Seq(1 -> "a1"))
    val a2 = mkBlock(a1, 2, Seq(7 -> "a2"))
    newPayload(a1); forkchoice(a1)
    newPayload(a2); forkchoice(a2)
    val s3 = mkBlock(a2, 3, Seq(3 -> "sOnly")) // extends the head: index entry written, best pointer not moved
    newPayload(s3)
    reader.getCanonicalHashByNumber(3) shouldBe Some(s3.hash)
    val t3 = mkBlock(a2, 4, Seq(9 -> "t3"))
    val node = consensus(consensusLayerHead = true)
    node.evaluateBranch(NonEmptyList.one(t3)).unsafeRunSync()
    reader.getBestBlock.map(_.hash) shouldBe Some(t3.hash)
    val tip = extendQuiet(t3, 5, consensusLayerHead = true)
    readWholeState(roots(tip.hash.value))

  private def assertWholeState(state: Map[Int, Option[ByteString]], expect: Map[Int, String]): Unit =
    state.foreach { case (slot, v) =>
      v shouldBe Some(value(expect.getOrElse(slot, s"g$slot")))
    }

  private def failsReadingState(body: => Any): Unit =
    val thrown = captureFailure(body)
    withClue(s"expected the pruned node to surface as MissingNode, got $thrown: ") {
      def causes(t: Throwable): List[Throwable] = if t == null then Nil else t :: causes(t.getCause)
      causes(thrown).exists(_.isInstanceOf[MerklePatriciaTrie.MissingNodeException]) shouldBe true
    }

  private def captureFailure(body: => Any): Throwable =
    try
      body
      fail("expected the read of the canonical state to fail")
    catch case t: Throwable if !t.isInstanceOf[org.scalatest.exceptions.TestFailedException] => t

  "An Engine API head move to a sibling" should
    "leave the canonical state readable after pruning past the abandoned block" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in {
      assertWholeState(
        headMovesToSibling(new Fixture(undoEnabled = true)),
        Map(1 -> "a1", 3 -> "g3", 7 -> "bOnly") ++ (23 to 27).map(i => i -> s"q${i - 20}")
      )
    }

  it should "lose a node the abandoned block alone replaced when nothing undoes it (control)" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in {
    failsReadingState(headMovesToSibling(new Fixture(undoEnabled = false)))
  }

  "A side payload that is executed and never adopted" should
    "leave the canonical state readable after pruning" taggedAs (UnitTest, DatabaseTest, ConsensusTest) in {
      assertWholeState(
        sidePayloadNeverAdopted(new Fixture(undoEnabled = true)),
        Map(1 -> "a1", 3 -> "g3", 7 -> "aOnly") ++ (23 to 27).map(i => i -> s"q${i - 20}")
      )
    }

  it should "lose a node only it replaced when nothing undoes it (control)" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in {
    failsReadingState(sidePayloadNeverAdopted(new Fixture(undoEnabled = false)))
  }

  "forkchoiceUpdated A -> B -> A" should
    "put the first block's changes back and keep every node of the final chain" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in {
      assertWholeState(
        headMovesAndBack(new Fixture(undoEnabled = true)),
        Map(1 -> "a1", 3 -> "aOnly", 7 -> "g7") ++ (23 to 27).map(i => i -> s"q${i - 20}")
      )
    }

  it should "lose the node b2 alone replaced when nothing undoes it (control)" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in {
    failsReadingState(headMovesAndBack(new Fixture(undoEnabled = false)))
  }

  "An Engine payload that extended the head and is displaced by a regular-sync import" should
    "leave the canonical state readable after pruning" taggedAs (UnitTest, DatabaseTest, ConsensusTest) in {
      assertWholeState(
        payloadDisplacedByImport(new Fixture(undoEnabled = true)),
        Map(1 -> "a1", 3 -> "g3", 7 -> "a2", 9 -> "t3") ++ (24 to 28).map(i => i -> s"q${i - 20}")
      )
    }

  it should "lose a node only the displaced payload replaced when nothing undoes it (control)" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in {
    failsReadingState(payloadDisplacedByImport(new Fixture(undoEnabled = false)))
  }
