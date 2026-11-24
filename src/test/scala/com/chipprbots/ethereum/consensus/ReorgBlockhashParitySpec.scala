package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.DuplicateBlock
import com.chipprbots.ethereum.blockchain.sync.regular.ImportedPartially
import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.engine.BranchHandOff
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock
import com.chipprbots.ethereum.db.storage.pathstate.HandedToRecovery
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.RefusedForThisHead
import com.chipprbots.ethereum.db.storage.pathstate.StateAnchor
import com.chipprbots.ethereum.db.storage.pathstate.StateBusy
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StateRecovery
import com.chipprbots.ethereum.ledger.StateRecovery.Sink
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** core-geth parity for BLOCKHASH and for the canonical index across reorganisations (ETC).
  *
  * core-geth resolves BLOCKHASH with `GetHashFn` (core/evm.go), which walks `ParentHash` back from the executing block
  * and never consults the canonical number→hash index. Its `writeBlockAndSetHead` moves the head only when
  * `forker.ReorgNeeded` says the new block is heavier, and `reorg()` keeps the canonical index exactly the head's
  * ancestry — rewriting it down to the common ancestor and deleting every entry above the new head.
  *
  * Every scenario here drives the REAL import path: `ConsensusAdapter` -> `ConsensusImpl` -> `BlockExecution` with the
  * real VM and the real after-execution check of gasUsed and stateRoot (`StdValidators.validateBlockAfterExecution`).
  * Only pre-execution header rules (PoW, difficulty, gas-limit bounds, ommers, receipts root) are stubbed, so that the
  * test can choose difficulties freely.
  *
  * Blocks are sealed by an independent ORACLE node whose canonical index holds exactly one chain, so on the oracle the
  * index lookup and the ancestry walk give the same answer — the core-geth answer. The node under test then has to
  * reproduce the oracle's state roots byte for byte.
  */
class ReorgBlockhashParitySpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience:
  import ReorgBlockhashParitySpec.*

  // The reorganisation on the hash layout, and on the unified state layout, where a branch is executed on pinned layers
  // and adopted by one move of the head (spec 018 C4e): the same answers, block for block.
  // And on the unified layout with a branch that holds more than the pinned layers may: the head is moved first and the
  // blocks are committed in place (C4e part 3), wholly (`1`: no block is kept under the pin) or from where the cap is
  // met (`MidCap`: the first blocks of a branch are kept, the rest are not).
  // And in the archive mode with a fork below what a view reaches (C4e part 4): no block can be executed on a view of
  // the fork, so the head is moved to it by the records and the branch committed in place.
  for (layout, guard, cap) <- Seq[(String, Boolean, StateConfig => StateConfig)](
      ("the hash layout", false, identity),
      ("the unified layout", true, identity),
      ("the unified layout, nothing kept under the pin", true, pinnedCap(1L)),
      ("the unified layout, the cap met within the branch", true, pinnedCap(MidCap)),
      ("the unified layout, an archive with the fork below its view reach", true, archiveOfShortViews)
    )
  do
    (s"A TD-winning reorg that fails mid-branch ($layout)" should
      "keep the heavier old head and index, so the next canonical block's BLOCKHASH matches core-geth").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard, cap):
      // Canonical h1..h15 at difficulty 1000. Side branch s6..s13 forks after h5: s6..s12 at 1000, s13 at 5000 and
      // consensus-invalid (tampered stateRoot). Whole branch: 12000 > 10000 for h6..h15, so it is selected and executed;
      // the executed prefix s6..s12 is 7000 < 10000, so core-geth's head never leaves h15.
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 15, difficulty = 1000)
      val h5: Block = oldChain(4)
      val h15: Block = oldChain(14)
      val h16: Block = oracle.extendCanonical(h15, 1, difficulty = 1000, readBlockhashOf = Some(8)).head
      val sidePrefix: List[Block] = oracle.extendSide(h5, 7, difficulty = 1000, tag = 0x51)
      val s13Invalid: Block = oracle.invalidChild(sidePrefix.last, difficulty = 5000)

      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe h15.hash

      // Branch selected by weight, prefix executed, s13 rejected on its state root.
      node.importAll(sidePrefix :+ s13Invalid) shouldBe a[BlockImportFailed]

      // core-geth: head stays h15 (TD(s12) < TD(h15)) and the canonical index is still h's chain.
      node.bestHash shouldBe h15.hash
      (6 to 12).foreach(n => node.indexed(n) shouldBe Some(oldChain(n - 1).hash))

      // The next canonical block arrives by NewBlock. It stores BLOCKHASH(8) at slot 16; the oracle sealed it with
      // h8's hash. A side-branch hash here is a state-root mismatch and a valid canonical block rejected.
      node.importOne(h16) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe h16.hash
      recorded(h16, slot = 16, oldChain(7).hash.value)
    )

    it should "move the head to the executed prefix when it is heavier, and drop the old index above it" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture(guard, cap):
      // Canonical h1..h15 at 1000. Side s6..s8 at 5000 each (15000 > 10000 for h6..h15), s9 invalid.
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 15, difficulty = 1000)
      val sidePrefix: List[Block] = oracle.extendSide(oldChain(4), 3, difficulty = 5000, tag = 0x52)
      val s9Invalid: Block = oracle.invalidChild(sidePrefix.last, difficulty = 5000)

      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      // B-291: the heavier prefix became the head before s9 was refused; the failure is still answered, with s9 named,
      // and the head's move with it (B-338): onto s6..s8, off h6..h15
      node.importAll(sidePrefix :+ s9Invalid) match
        case ImportedPartially(ChainReorganised(off, onto, _, branchBlocks), failingBlockHash, _) =>
          failingBlockHash shouldBe s9Invalid.hash.value
          onto shouldBe sidePrefix
          branchBlocks shouldBe sidePrefix.size
          off shouldBe oldChain.drop(5)
        case other => fail(s"not the partial reorganisation, with s9 named: $other")

      // core-geth: writeBlockAndSetHead reorgs as soon as a side block outweighs the head, so the head is s8 and reorg()
      // has deleted the canonical hashes of h9..h15.
      node.bestHash shouldBe sidePrefix.last.hash
      (6 to 8).foreach(n => node.indexed(n) shouldBe Some(sidePrefix(n - 6).hash))
      (9 to 15).foreach(n => node.indexed(n) shouldBe None)
      (1 to 5).foreach(n => node.indexed(n) shouldBe Some(oldChain(n - 1).hash))

    (s"A reorg back onto a previously orphaned block ($layout)" should
      "rewrite the canonical index below the branch parent, so BLOCKHASH and the old branch match core-geth").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard, cap):
      // The ordinary ETC uncle race, across a restart. Canonical a1..a10. b10, a heavier sibling of a10, arrives and
      // wins. The node restarts, losing the in-memory block queue that held the orphaned a10. Then a11 (child of a10)
      // arrives by NewBlock and outweighs b10. a10 is on disk with a chain weight, so the queue roots a11 directly on
      // it and ConsensusImpl reorganises with the one-block branch [a11]. core-geth's reorg() walks a11's ancestry
      // back to the common ancestor a9 and rewrites height 10 to a10.
      //
      // a11 and a12 both store BLOCKHASH(10). a11 is EXECUTED while the index still names b10 at height 10 (the index is
      // only settled after execution), so it needs the ancestry walk; a12 executes after the index is settled.
      val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 10, difficulty = 1000)
      val a9: Block = aChain(8)
      val a10: Block = aChain(9)
      val a11: Block = oracle.extendCanonical(a10, 1, difficulty = 1000, readBlockhashOf = Some(10)).head
      val a12: Block = oracle.extendCanonical(a11, 1, difficulty = 1000, readBlockhashOf = Some(10)).head
      val b10: Block = oracle.extendSide(a9, 1, difficulty = 1500, tag = 0x53).head

      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      node.importOne(b10) shouldBe a[ChainReorganised]
      node.bestHash shouldBe b10.hash
      node.blockQueue.clear() // restart: the queue is in memory only

      node.importOne(a11) match
        case ChainReorganised(oldBranch, newBranch, _, _) =>
          // Exactly the block that left the canonical chain — not the whole chain down to genesis.
          oldBranch.map(_.hash) shouldBe List(b10.hash)
          // On the unified layout a10, the side block the branch stands on, is canonical again with it and is told as
          // of the new branch, from the fork up, as go-ethereum's new chain is (B-297). The hash layout tells the
          // branch alone, as it always did.
          newBranch.map(_.hash) shouldBe (if guard then List(a10.hash, a11.hash) else List(a11.hash))
        case other => fail(s"expected ChainReorganised, got $other")
      node.bestHash shouldBe a11.hash
      node.indexed(10) shouldBe Some(a10.hash)
      recorded(a11, slot = 11, a10.hash.value)

      node.importOne(a12) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe a12.hash
      recorded(a12, slot = 12, a10.hash.value)
    )

  "A heavier branch that forks thousands of blocks below what a view reaches (the unified layout, an archive)" should
    "be adopted: the head goes to the fork by the records, in batches, and the branch is committed in place" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard = true, archiveOfShortViews):
      // The depth of the reorganisations ETC has seen (about 3,700 to 7,000 blocks in 2019 and 2020; T183).
      // Canonical h1..h4020. The side chain leaves it after h20, 4,000 blocks below the head, where views reach 2
      // and a move is written 3 blocks to a batch; with 4,001 blocks of the same difficulty it is the heavier.
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 4020, difficulty = 1000)
      val side: List[Block] = oracle.extendSide(oldChain(19), 4001, difficulty = 1000, tag = 0x71)

      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      node.importAll(side) match
        case ChainReorganised(oldBranch, newBranch, _, _) =>
          // the 4,000 blocks the head left, of which the 1,000 nearest the old head are told (B-296); of the 4,001 it
          // moved onto, the 1,000 nearest the new head (B-344)
          oldBranch.map(_.hash) shouldBe oldChain.drop(20).takeRight(ConsensusImpl.OldBranchToldAtMost).map(_.hash)
          newBranch.map(_.hash) shouldBe side.takeRight(ConsensusImpl.OldBranchToldAtMost).map(_.hash)
        case other => fail(s"expected ChainReorganised, got $other")
      node.bestHash shouldBe side.last.hash
      (1 to 20).foreach(n => node.indexed(n) shouldBe Some(oldChain(n - 1).hash))
      (21 to 4021).foreach(n => node.indexed(n) shouldBe Some(side(n - 21).hash))

      // and its state is the branch's: the next block of it is executed on it and becomes the head
      val next: Block = oracle.extendSide(side.last, 1, difficulty = 1000, tag = 0x72).head
      node.importOne(next) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe next.hash
    )

  // B-296: what a reorganisation reads and holds of the chain it moves the head off. Counted on the reader the
  // consensus reads through (not on the heap): the blocks of the old chain read whole, and their receipts.
  // On the unified layout the deep one is an archive's, whose records reach the fork (as above).
  for (layout, guard, deep) <- Seq[(String, Boolean, StateConfig => StateConfig)](
      ("the hash layout", false, identity),
      ("the unified layout", true, archiveOfShortViews)
    )
  do
    (s"A reorganisation thousands of blocks deep ($layout)" should
      "read only the blocks of the old chain it tells, the nearest the old head, and none of their receipts").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new CountingFixture(guard, cap = deep):
      // Canonical h1..h1520; the side chain leaves it after h20 with 1,501 blocks of the same difficulty: heavier.
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 1520, difficulty = 1000)
      val side: List[Block] = oracle.extendSide(oldChain(19), 1501, difficulty = 1000, tag = 0x81)
      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      val left: List[Block] = oldChain.drop(20)
      left.size shouldBe 1500
      val leftHashes: Set[BlockHash] = left.map(_.hash).toSet

      counting.reset()
      evaluate(consensus, side) match
        case SelectedNewBestBranch(oldBranch, newBranch, weights, _) =>
          oldBranch.map(_.hash) shouldBe left.takeRight(ConsensusImpl.OldBranchToldAtMost).map(_.hash)
          newBranch.map(_.hash) shouldBe side.takeRight(ConsensusImpl.OldBranchToldAtMost).map(_.hash)
          weights shouldBe newBranch.map(block => node.blockchainReader.getChainWeightByHash(block.hash).get)
        case other => fail(s"expected SelectedNewBestBranch, got $other")
      node.bestHash shouldBe side.last.hash

      // What was read whole of the old chain: the blocks told, each once, and the old head once more, when the branch
      // was weighed against it. Before B-296 it was all 1,500 of them, with their receipts.
      val bodiesOfTheOldChain: List[BlockHash] = counting.blocksRead.filter(leftHashes)
      bodiesOfTheOldChain.toSet shouldBe left.takeRight(ConsensusImpl.OldBranchToldAtMost).map(_.hash).toSet
      bodiesOfTheOldChain.size should be <= ConsensusImpl.OldBranchToldAtMost + 1
      counting.receiptsRead.filter(leftHashes) shouldBe empty
    )

    (s"A shallow reorganisation ($layout)" should
      "tell every block the head left, the new branch and its weights, as it always did").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new CountingFixture(guard):
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 15, difficulty = 1000)
      val side: List[Block] = oracle.extendSide(oldChain(4), 11, difficulty = 1000, tag = 0x82)
      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      // what the reorganisation is answered with through the importer's adapter, queue included
      node.importAll(side) match
        case ChainReorganised(oldBranch, newBranch, weights, _) =>
          oldBranch shouldBe oldChain.drop(5)
          newBranch shouldBe side
          weights shouldBe side.map(block => node.blockchainReader.getChainWeightByHash(block.hash).get)
        case other => fail(s"expected ChainReorganised, got $other")
      node.bestHash shouldBe side.last.hash
      // the blocks the head left are queued again, all ten of them
      oldChain.drop(5).foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)
    )

    (s"A reorganisation just past the bound ($layout)" should
      "tell the blocks within it, and all of them at the bound").taggedAs(UnitTest, ConsensusTest) in (
      new CountingFixture(guard, bound = 5):
        val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 12, difficulty = 1000)
        // h6..h10 left: five, the bound
        val atTheBound: List[Block] = oracle.extendSide(oldChain(4), 6, difficulty = 1000, tag = 0x83)
        node.importAll(oldChain.take(10)) shouldBe a[BlockImportedToTop]
        evaluate(consensus, atTheBound) match
          case SelectedNewBestBranch(oldBranch, _, _, _) => oldBranch shouldBe oldChain.slice(5, 10)
          case other                                     => fail(s"expected SelectedNewBestBranch, got $other")
        // and back: the side chain's h6'..h11' (six) left for a heavier branch of the canonical chain
        val back: List[Block] = oldChain.drop(5) ++ oracle.extendCanonical(oldChain.last, 1, difficulty = 1000)
        evaluate(consensus, back) match
          case SelectedNewBestBranch(oldBranch, newBranch, _, _) =>
            oldBranch shouldBe atTheBound.drop(1)
            // seven blocks became canonical: the five nearest the new head are told (B-344)
            newBranch.map(_.hash) shouldBe back.takeRight(5).map(_.hash)
          case other => fail(s"expected SelectedNewBestBranch, got $other")
    )

  // B-295 (T183's remainder; forge W3 of C4e part 4). On the unified layout a heavier branch is adopted by moving the
  // head in place: reverse batches from the old head down to the fork, then forward along the branch, each a synced
  // write that moves the anchor, the best block and the index together. A kill can land between any two writes. Here
  // it lands after every one of them in turn: the node is started again on what the dead one left, through the
  // reconciliation at start, and must stand on a valid head; offered the two chains again, in either order, it must
  // end on the heavier, with that block's state.
  for (layout, cap) <- Seq[(String, StateConfig => StateConfig)](
      ("executed on layers", movesOfThree),
      ("at the cap, reverse-then-commit", pinnedCap(1L).andThen(movesOfThree)),
      ("an archive with the fork below its view reach", archiveOfShortViews)
    )
  do
    s"A heavier branch on the unified layout ($layout) whose adoption is killed after any of its writes" should
      "leave a valid head at the restart, and end on the heavier chain when both are offered again" taggedAs (
        UnitTest,
        ConsensusTest
      ) in (new KilledReorg(cap):
        // the attempt not killed: how many writes it makes, and which of them move the anchor
        val whole: Attempt = attempt(None)
        whole.writes should be > 0
        whole.anchorMoves.size should be >= 2
        val restartedOn: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty[String]
        for
          after <- 0 to whole.writes
          oldChainFirst <- Seq(false, true)
        do
          val killed = attempt(Some(after))
          withClue(
            s"killed after write $after of ${whole.writes} (the anchor moved by ${whole.anchorMoves}), the old chain offered ${if oldChainFirst then "first" else "last"}: "
          ) {
            val node = new Node(guard = true, cap, Some(killed.datadir), restart = true)
            node.reconciled.get should (be(a[StateReconciliation.Outcome.Consistent])
              .or(be(a[StateReconciliation.Outcome.Reconciled])))
            val head = validHead(node, killed.datadir)
            restartedOn += where(head)
            if oldChainFirst then
              // a head that weighs no less than the old chain's tip stays: a tie keeps the head (by weight alone)
              val stays = weightOf(node, head.hash) >= weightOf(node, oldChain.last.hash)
              offer(node, oldChain)
              validHead(node, killed.datadir).hash shouldBe (if stays then head.hash else oldChain.last.hash)
            offer(node, branch)
            validHead(node, killed.datadir).hash shouldBe branch.last.hash
            // the old chain again: lighter, kept beside the head
            node.importAll(oldChain.drop(3)) shouldBe BlockEnqueued
            validHead(node, killed.datadir).hash shouldBe branch.last.hash
            // and the head's state is executed on: the branch's next block is its head
            node.importOne(next) shouldBe a[BlockImportedToTop]
            node.bestHash shouldBe next.hash
          }
        // the kill met the move at each of its points: before it, on the way down, at the fork, on the way up, after
        restartedOn shouldBe Set("the old head", "the old chain", "the fork", "the branch", "the branch's tip")
      )

  // B-292 (forge N2 of C4e part 3): reverse-then-commit at the cap meets a fault, in a node that runs on. Each write
  // that fails writes nothing, as a synced batch that did not land; the node must stand on a valid head after it, say
  // where that head is, and adopt the branch when it is offered again with nothing failing.
  "A heavier branch committed in place at the cap (reverse-then-commit)" should
    "be moved back from to the old head when a fault of the state meets it after some of its blocks were committed" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new FaultedReorg:
      datadir.failAt(branch(3), injected("the commit of block 7"))
      evaluate(branch) match
        case Right(ConsensusStateFault(fault, imported, queued)) =>
          fault.getMessage should include("the commit of block 7")
          imported shouldBe Nil
          queued.map(_.hash) shouldBe branch.map(_.hash)
        case other => fail(s"not a fault of this node: $other")
      // the head went to the fork, and b4..b6 were committed on it, before the fault
      anchoredSince.take(6) shouldBe Vector(oldChain(8), oldChain(5), fork, branch(0), branch(1), branch(2))
        .map(_.hash.value)
      settledOn(oldChain.last)
      adoptedAgain()
    )

  it should "be moved back from to the old head when a write of a commit fails, and the failure be thrown" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    datadir.failAt(branch(3), new java.io.IOException("injected: the disk refused the commit of block 7"))
    evaluate(branch) match
      case Left(thrown: java.io.IOException) => thrown.getMessage should include("the commit of block 7")
      case other                             => fail(s"the failure of the write is not thrown: $other")
    anchoredSince should contain(branch(2).hash.value)
    settledOn(oldChain.last)
    adoptedAgain()
  )

  // B-440: what regular sync is answered for the same failure. A write the disk refused stops the node (the user's
  // decision of 2026-11-24, go-ethereum's log.Crit): the adapter asks for the stop once, with what was thrown, counts
  // nothing, and answers a fault of this node with the branch queued again; the head is the old head, valid.
  it should "stop the node, through the import's adapter, when a write of a commit is refused" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    val refused = new java.io.IOException("injected: the disk refused the commit of block 7")
    datadir.failAt(branch(3), refused)
    val stopped: ArrayBuffer[Throwable] = ArrayBuffer.empty
    // the import's adapter over the consensus this rig commits at the cap with, and the node's queue
    val adapter = new ConsensusAdapter(
      consensus,
      node.blockchainReader,
      node.blockQueue,
      new com.chipprbots.ethereum.ledger.BlockValidation(node.mining, node.blockchainReader, node.blockQueue),
      node.runtime,
      onFatalStoreFailure = stopped += _
    )
    val answered: BlockImportResult =
      import node.blockchainConfig
      import node.runtime
      adapter.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeRunSync()
    answered match
      case BlockImportStateFault(fault, imported) =>
        fault.getMessage should include("the disk refused the commit of block 7")
        imported shouldBe Nil
      case other => fail(s"not a fault of this node: $other")
    stopped.toList.map(_ eq refused) shouldBe List(true)
    branch.foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)
    settledOn(oldChain.last)
  )

  // B-439: what was thrown is not the answer when the move back after it fails: the answer says where the head is
  it should "say where the head is when a write of a commit fails and the move back fails before it wrote" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    datadir.failAt(branch(3), new java.io.IOException("injected: the disk refused the commit of block 7"))
    datadir.failAt(fork, injected("the move back"), after = 1)
    val result: Either[Throwable, ConsensusResult] = evaluate(branch)
    settledOn(branch(2))
    // the blocks committed before the throw are read from the store: the attempt kept no account of them
    toldWhereTheHeadIs(result, branch(2))
    result.map { case ConsensusStateFault(fault, _, _) => fault.getMessage; case _ => "" }.getOrElse("") should
      include("the disk refused the commit of block 7")
    adoptedAgain()
  )

  it should "say where the head is when a write of a commit fails and the move back stops on the old chain" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    datadir.failAt(branch(3), new java.io.IOException("injected: the disk refused the commit of block 7"))
    datadir.failAt(oldChain(8), injected("the move back"), after = 1)
    val result: Either[Throwable, ConsensusResult] = evaluate(branch)
    settledOn(oldChain(5))
    toldWhereTheHeadIs(result, oldChain(5))
    adoptedAgain()
  )

  it should "say where the head is when the move back fails before it wrote, with the committed blocks as imported" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    datadir.failAt(branch(3), injected("the commit of block 7"))
    // the move back's first batch, to the fork: the fork's second anchoring since the attempt began
    datadir.failAt(fork, injected("the move back"), after = 1)
    val result: Either[Throwable, ConsensusResult] = evaluate(branch)
    settledOn(branch(2))
    toldWhereTheHeadIs(result, branch(2))
    adoptedAgain()
  )

  it should "leave a valid head on the old chain when the move back stops part of the way, and adopt the branch again" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    datadir.failAt(branch(3), injected("the commit of block 7"))
    // the move back's third batch, to h9: h9's second anchoring since the attempt began. The head is left at h6
    datadir.failAt(oldChain(8), injected("the move back"), after = 1)
    val result: Either[Throwable, ConsensusResult] = evaluate(branch)
    settledOn(oldChain(5))
    toldWhereTheHeadIs(result, oldChain(5))
    adoptedAgain()
  )

  it should "go back to the old head when the move to the fork stops part of the way" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    // the move's second batch, to h6: the head is at h9 when it fails
    datadir.failAt(oldChain(5), injected("the second batch of the move to the fork"))
    evaluate(branch) match
      case Right(ConsensusStateFault(fault, imported, queued)) =>
        fault.getMessage should include("the second batch of the move to the fork")
        imported shouldBe Nil
        queued.map(_.hash) shouldBe branch.map(_.hash)
      case other => fail(s"not a fault of this node: $other")
    anchoredSince.take(2) shouldBe Vector(oldChain(8), oldChain.last).map(_.hash.value)
    settledOn(oldChain.last)
    adoptedAgain()
  )

  it should "leave a valid head on the old chain when the move to the fork stops part of the way and the move back fails, and adopt the branch again" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new FaultedReorg:
    datadir.failAt(oldChain(5), injected("the second batch of the move to the fork"))
    datadir.failAt(oldChain.last, injected("the move back"))
    val result: Either[Throwable, ConsensusResult] = evaluate(branch)
    settledOn(oldChain(8))
    toldWhereTheHeadIs(result, oldChain(8))
    adoptedAgain()
  )

  // B-294 (spec 018 C4e part 4; forge): while the branch is committed in place, the head stands on blocks of its way
  // that it may not stay on. An import onto that head from another thread, and a branch that would reorganise it, are
  // refused, asking again, with nothing of the head written; once the adoption has ended they are judged, on the head
  // it ended on.
  it should "refuse, asking again, an import from another thread onto a block of its way, and judge it once it has ended" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new RacedReorg(at = 1):
    // on the head while the branch's second block is executed in place: its first
    val onTheWay: Block = oracle.extendSide(branch(0), 1, difficulty = 1000, tag = 0x83, miner = OtherMiner).head
    // two blocks from the fork, which outweigh that head
    val across: List[Block] = oracle.extendSide(fork, 2, difficulty = 1000, tag = 0x84)
    var imported: Option[BlockImportResult] = None
    var reorganised: Option[BlockImportResult] = None
    var said: List[ch.qos.logback.classic.spi.ILoggingEvent] = Nil
    meanwhile = () =>
      node.bestHash shouldBe branch(0).hash
      val untouched = datadir.storage
      val (_, events) = com.chipprbots.ethereum.testing.LogCapture.capture(classOf[ConsensusImpl]) {
        imported = Some(node.importOne(onTheWay))
        // nothing at all was written
        (datadir.storage eq untouched) shouldBe true
        reorganised = Some(node.importAll(across))
      }
      said = events
      node.bestHash shouldBe branch(0).hash
      state.anchor.map(_.hash) shouldBe Some(branch(0).hash.value)
    evaluate(branch) shouldBe a[SelectedNewBestBranch]
    ranMeanwhile shouldBe Some(scala.util.Success(()))
    // B-462: said at WARN as busy, never at ERROR as a fault of this node's state
    withClue(said.map(e => s"${e.getLevel} ${e.getFormattedMessage}").mkString("\n")) {
      said.filter(_.getLevel == ch.qos.logback.classic.Level.ERROR) shouldBe empty
      val busy = said.filter(_.getFormattedMessage.startsWith("[STATE-BUSY]"))
      busy should not be empty
      busy.map(_.getLevel).distinct shouldBe List(ch.qos.logback.classic.Level.WARN)
      said.exists(_.getFormattedMessage.startsWith("[STATE-FAULT]")) shouldBe false
    }
    // not judged, as any fault of this node's state: queued, and no peer blamed
    for (what, answer) <- Seq("the import" -> imported, "the reorganisation" -> reorganised) do
      withClue(s"$what: ") {
        answer match
          // B-462: as busy, which no escalation counts
          case Some(BlockImportStateFault(refused: StateBusy, Nil)) =>
            refused.getMessage should include("is moving the head")
          case other => fail(s"not refused, asking again: $other")
      }
    // the adoption went on as if they had not been: the branch is the head, whole
    validHead(node, datadir).hash shouldBe branch.last.hash
    state.headGeneration % 2 shouldBe 0
    // asked again, both are judged on that head: the block waits in the queue it was put back in, and the branch is
    // lighter and is kept beside the head; and the branch's next block is its head
    node.importOne(onTheWay) shouldBe DuplicateBlock
    node.importAll(across) shouldBe BlockEnqueued
    node.importOne(next) shouldBe a[BlockImportedToTop]
    node.bestHash shouldBe next.hash
  )

  // B-463 (forge): the head is settled, and a block is committed on it after the branch was weighed against it and
  // before the records are held. The adoption is refused as busy with nothing written, the branch is queued again with
  // no peer blamed, and the next offer weighs it against the head as it is: kept beside a heavier head, or adopted with
  // the block committed meanwhile told as of the chain it leaves. Before, the stale head was what the branch was weighed
  // against and what a move back led to, and the block committed meanwhile was left a side block.
  it should "refuse, as busy, an adoption whose head a block was committed on since it was weighed, and weigh it again against the head as it is" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // heavier than the branch with it (14000 against 13000), and lighter (12500)
    for (difficulty, heavier) <- Seq(BigInt(2000) -> true, BigInt(500) -> false) do
      withClue(s"a block of difficulty $difficulty committed meanwhile: ") {
        new AnchorRacedReorg(difficulty):
          val untouched = new java.util.concurrent.atomic.AtomicReference[AnyRef]()
          meanwhile = () =>
            node.importOne(onTheHead) shouldBe a[BlockImportedToTop]
            untouched.set(datadir.storage)
          val (first, said) = com.chipprbots.ethereum.testing.LogCapture.capture(classOf[ConsensusImpl])(offer())
          ranMeanwhile shouldBe true
          first match
            case BlockImportStateFault(refused: StateBusy, Nil) =>
              refused.reason should include(s"block ${oldChain.last.number.value} ")
              refused.reason should include(s"block ${onTheHead.number.value} ")
            case other => fail(s"not refused as busy: $other")
          // nothing of the adoption was written: the head is the block committed meanwhile, as it was left
          (datadir.storage eq untouched.get) shouldBe true
          state.headGeneration % 2 shouldBe 0
          validHead(node, datadir).hash shouldBe onTheHead.hash
          // queued again, and said at WARN as busy, never at ERROR
          branch.foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)
          said.filter(_.getLevel == ch.qos.logback.classic.Level.ERROR) shouldBe empty
          said.exists(e => e.getFormattedMessage.startsWith("[STATE-BUSY]")) shouldBe true
          // offered again: weighed against the head as it is
          val again: BlockImportResult = offer()
          if heavier then
            again shouldBe BlockEnqueued
            validHead(node, datadir).hash shouldBe onTheHead.hash
          else
            again match
              case ChainReorganised(oldBranch, newBranch, _, _) =>
                oldBranch.map(_.hash) shouldBe (oldChain.drop(3) :+ onTheHead).map(_.hash)
                newBranch.map(_.hash) shouldBe branch.map(_.hash)
              case other => fail(s"not adopted: $other")
            validHead(node, datadir).hash shouldBe branch.last.hash
            // the branch's next block is executed on its state. What it is answered with is not asserted here: the
            // queue still holds the branch the refusal queued again, which an adoption by a batch does not take out
            node.importOne(next) should (be(a[BlockImportedToTop]).or(be(a[ChainReorganised])))
            node.bestHash shouldBe next.hash
      }
  }
  // B-503: a batch the store was busy for is queued again (B-440), and regular sync fetches it again and adopts it
  // with `evaluateBranch`. The blocks it made the head must leave the queue, as a branch the queue resolved does: left
  // in it, the next block announced is resolved with them as its branch, and the node reorganises onto its own chain,
  // executes them again and tells a reorganisation whose old blocks are the new ones, then queues them again.
  for (layout, guard) <- Seq(("the hash layout", false), ("the unified layout", true)) do
    (s"A batch queued again after the store was busy, then made the head by a batch that extends it ($layout)" should
      "leave the queue, so that the next block is imported to the top and none is executed again").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new BusyOnce(guard):
      val chain: List[Block] = oracle.extendCanonical(oracle.genesis, 3, difficulty = 1000)
      val batch: List[Block] = oracle.extendCanonical(chain.last, 10, difficulty = 1000)
      val child: Block = oracle.extendCanonical(batch.last, 1, difficulty = 1000).head
      node.importAll(chain) shouldBe a[BlockImportedToTop]

      busyFor(1)
      inBatch(batch) match
        case BlockImportStateFault(_, imported) => imported shouldBe Nil
        case other                              => fail(s"not a store that was busy: $other")
      batch.foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)

      inBatch(batch) match
        case BlockImportedToTop(imported) => imported.map(_.block.hash) shouldBe batch.map(_.hash)
        case other                        => fail(s"the batch is not imported to the top: $other")

      evaluated.clear()
      announced(child) match
        case BlockImportedToTop(imported) => imported.map(_.block.hash) shouldBe List(child.hash)
        case other                        => fail(s"the announced block is not imported to the top: $other")
      // the consensus was handed the announced block alone: no block of the batch was executed again
      evaluated.toList shouldBe List(List(child.hash))
      node.bestHash shouldBe child.hash
      (child :: batch).foreach(block => node.blockQueue.isQueued(block.hash) shouldBe false)
    )

    (s"A side branch queued again after the store was busy, then made the head by a reorganisation ($layout)" should
      "leave the queue, the blocks it left queued again, and the next block be imported to the top").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new BusyOnce(guard):
      // canonical h1..h6; the side branch s3..s8 leaves it after h2, heavier (6,000 > 4,000)
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 6, difficulty = 1000)
      val side: List[Block] = oracle.extendSide(oldChain(1), 6, difficulty = 1000, tag = 0x91)
      val child: Block = oracle.extendSide(side.last, 1, difficulty = 1000, tag = 0x92).head
      node.importAll(oldChain) shouldBe a[BlockImportedToTop]

      busyFor(1)
      inBatch(side) shouldBe a[BlockImportStateFault]
      side.foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)

      inBatch(side) match
        case ChainReorganised(oldBranch, newBranch, _, _) =>
          oldBranch.map(_.hash) shouldBe oldChain.drop(2).map(_.hash)
          newBranch.map(_.hash) shouldBe side.map(_.hash)
        case other => fail(s"the side branch is not adopted: $other")

      evaluated.clear()
      announced(child) match
        case BlockImportedToTop(imported) => imported.map(_.block.hash) shouldBe List(child.hash)
        case other                        => fail(s"the announced block is not imported to the top: $other")
      evaluated.toList shouldBe List(List(child.hash))
      node.bestHash shouldBe child.hash
      (child :: side).foreach(block => node.blockQueue.isQueued(block.hash) shouldBe false)
      // as for any reorganisation, the blocks the head left are queued again
      oldChain.drop(2).foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)
    )

  "Chain-file import (hive consensus simulator path)" should
    "apply TD fork choice instead of making the last block in the file the head" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture:
      // ethereum/tests lotsOfLeafs / sideChainWithMoreTransactions / uncleBlockAtBlock3afterBlock4 shape: the file
      // lists the canonical chain, then a lighter side block, then (second file) a heavier side branch. core-geth's
      // `import` (InsertChain -> writeBlockAndSetHead) keeps the heaviest head; ChainImporter used to save every
      // block as best, so the file's last block won whatever its TD.
      val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 4, difficulty = 1000)
      val lighterSide: List[Block] = oracle.extendSide(aChain(1), 1, difficulty = 500, tag = 0x61) // b3 < a3
      val heavierSide: List[Block] = oracle.extendSide(aChain(1), 3, difficulty = 1000, tag = 0x62) // c3..c5 > a3..a4

      node.importFile(aChain ++ lighterSide)
      node.bestHash shouldBe aChain.last.hash
      node.indexed(3) shouldBe Some(aChain(2).hash)

      node.importFile(heavierSide)
      node.bestHash shouldBe heavierSide.last.hash
      (3 to 5).foreach(n => node.indexed(n) shouldBe Some(heavierSide(n - 3).hash))
    )

  it should "make the last valid block the head once the terminal total difficulty is reached (core-geth)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new PostMergeFixture:
    // ethereum/tests bcMultiChainTest/UncleFromSideChain_{Cancun,Prague} shape. Post-merge every header has
    // difficulty 0, so two chains always weigh the same and a TD rule can never choose. core-geth's
    // ForkChoice.ReorgNeeded returns true for any block at or past the TTD: on import, each valid block becomes the
    // head. The TD fork choice above kept the FIRST chain in the file instead (hive consensus 1143/0 -> 1143/2).
    val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 3, difficulty = 0)
    val bChain: List[Block] = oracle.extendSide(oracle.genesis, 3, difficulty = 0, tag = 0x63)

    node.importFile(aChain ++ bChain)
    node.bestHash shouldBe bChain.last.hash
    (1 to 3).foreach(n => node.indexed(n) shouldBe Some(bChain(n - 1).hash))

  // The consensus layer's branch on the unified layout (spec 018 C4e part 5; T159's first half as beacon re-cut it):
  // what regular sync's importer will ask of `ConsensusImpl` once it runs on that layout. After the merge every block
  // weighs the same, so a branch is followed only because it leads to the head the consensus layer named.
  "The consensus layer's branch (the unified layout)" should
    "become the head by one move when every block of it is valid, with the blocks the head left told" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new DesignatedFixture():
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      named.set(Some(bChain.last.hash.value))
      evaluate(bChain) match
        case SelectedNewBestBranch(oldBranch, newBranch, _, _) =>
          oldBranch.map(_.hash) shouldBe aChain.drop(2).map(_.hash)
          newBranch.map(_.hash) shouldBe bChain.map(_.hash)
        case other => fail(s"not adopted: $other")
      node.bestHash shouldBe bChain.last.hash
      (3 to 6).foreach(n => node.indexed(n) shouldBe Some(bChain(n - 3).hash))
      node.blockchainWriter.stateHeldInPlaceOf(bChain.last.header) shouldBe true
      reported shouldBe Nil
    )

  it should "leave the head where the consensus layer last had it when a block of the branch is invalid, and report that block" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture():
    node.importAll(aChain) shouldBe a[BlockImportedToTop]
    val invalid: Block = oracle.invalidChild(bChain(1), difficulty = 0)
    named.set(Some(invalid.hash.value))
    evaluate(bChain.take(2) :+ invalid) match
      case BranchExecutionFailure(_, failing, _) => failing shouldBe invalid.hash.value
      case other                                 => fail(s"not a failure of the branch: $other")
    // not the valid prefix: nothing has moved (and so on the hash layout since B-505)
    node.bestHash shouldBe aChain.last.hash
    (1 to 5).foreach(n => node.indexed(n) shouldBe Some(aChain(n - 1).hash))
    reported shouldBe List(invalid.hash.value -> bChain(1).hash.value)
    // what was executed is kept beside the disk for the next branch that stands on it
    node.blockchainWriter.stateHeldInPlaceOf(bChain(1).header) shouldBe true
  )

  it should "report an invalid block of the branch whatever head the consensus layer names by then" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture():
    node.importAll(aChain) shouldBe a[BlockImportedToTop]
    val invalid: Block = oracle.invalidChild(bChain(1), difficulty = 0)
    val reads = new java.util.concurrent.atomic.AtomicInteger(0)
    // the branch's head when it is selected, another once it has been executed
    override def namedNow(): Option[ByteString] =
      if reads.incrementAndGet() <= 1 then Some(invalid.hash.value) else Some(aChain.last.hash.value)
    evaluate(bChain.take(2) :+ invalid) match
      case BranchExecutionFailure(_, failing, _) => failing shouldBe invalid.hash.value
      case other                                 => fail(s"not a failure of the branch: $other")
    node.bestHash shouldBe aChain.last.hash
    reported shouldBe List(invalid.hash.value -> bChain(1).hash.value)
  )

  it should "not be committed in place when it is longer than the executed blocks kept: that is the fork choice's recovery's" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture(pinnedCap(1L)):
    node.importAll(aChain) shouldBe a[BlockImportedToTop]
    named.set(Some(bChain.last.hash.value))
    evaluate(bChain) match
      case ConsensusStateFault(fault, imported, _) =>
        fault.getMessage should include("the fork choice's recovery")
        imported shouldBe Nil
      case other => fail(s"judged: $other")
    node.bestHash shouldBe aChain.last.hash
    (1 to 5).foreach(n => node.indexed(n) shouldBe Some(aChain(n - 1).hash))
    reported shouldBe Nil
  )

  it should "not move the head when the consensus layer names another head while the branch is executed" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // the head is read when the branch is selected, twice when it is settled, and as the move is admitted
    for readsOfTheBranchsHead <- 1 to 3 do
      new DesignatedFixture():
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        val reads = new java.util.concurrent.atomic.AtomicInteger(0)
        override def namedNow(): Option[ByteString] =
          if reads.incrementAndGet() <= readsOfTheBranchsHead then Some(bChain.last.hash.value)
          else Some(aChain.last.hash.value)
        withClue(s"another head after $readsOfTheBranchsHead read(s): ") {
          // no fault of this node and no verdict on the branch: its blocks are valid and stay beside the head
          evaluate(bChain) shouldBe KeptCurrentBestBranch
          node.blockchainWriter.stateHeldInPlaceOf(bChain.last.header) shouldBe true
          reads.get() should be > readsOfTheBranchsHead
          node.bestHash shouldBe aChain.last.hash
          (1 to 5).foreach(n => node.indexed(n) shouldBe Some(aChain(n - 1).hash))
          reported shouldBe Nil
        }
  }

  it should "be refused before it is executed when it leaves the chain below the finalized block, and not queued or fetched again while the head stays" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture():
    node.importAll(aChain) shouldBe a[BlockImportedToTop]
    // the fork choice the node followed named block 4 finalized: the branch leaves the chain at block 2
    node.blockchainWriter.moveHeadInPlace(
      aChain.last.header,
      node.blockchainReader,
      finalized = Some(FinalizedBlock(4, aChain(3).hash.value))
    )
    named.set(Some(bChain.last.hash.value))
    // refused for this head (B-297): nothing to queue again, so the importer does not fetch it again
    evaluate(bChain) match
      case ConsensusStateFault(refused: RefusedForThisHead, imported, toEnqueue) =>
        refused.cause.getMessage should include("Finalized")
        (imported, toEnqueue) shouldBe (Nil, Nil)
      case other => fail(s"not refused for this head: $other")
    node.bestHash shouldBe aChain.last.hash
    node.blockchainWriter.stateHeldInPlaceOf(bChain.head.header) shouldBe false
    reported shouldBe Nil
    // stored by hash, with no weight: the next fork choice walks its chain down, and nothing reads it as executed
    bChain.foreach { block =>
      node.blockchainReader.getBlockByHash(block.hash) shouldBe Some(block)
      node.blockchainReader.getChainWeightByHash(block.hash) shouldBe None
    }
    // what hangs from it is refused with it, and so is what hangs from that
    val above: List[Block] = oracle.extendSide(bChain.last, 2, difficulty = 0, tag = 0x76)
    evaluate(above.take(1)) match
      case ConsensusStateFault(_: RefusedForThisHead, Nil, Nil) => ()
      case other                                                => fail(s"not refused with its parent: $other")
    consensus.refusedWithTheirParent(NonEmptyList.of(above(1))) shouldBe true
    // a move of the head ends it
    node.importAll(oracle.extendCanonical(aChain.last, 1, difficulty = 0)) shouldBe a[BlockImportedToTop]
    consensus.refusedWithTheirParent(NonEmptyList.of(above(1))) shouldBe false
  )

  it should "be handed, at the cap, to the fork choice's recovery, which commits it in place" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val handedOff = new java.util.concurrent.atomic.AtomicReference[Option[StateRecovery.Ticket]](None)
    val late = new BranchHandOff.LateBound
    new DesignatedFixture(pinnedCap(1L), Some(late)):
      val recovery = new StateRecovery(
        node.blockchainReader,
        node.blockchainWriter,
        node.blockExecution,
        node.storagesInstance.storages.pathStateStorage.get,
        maxBlocks = 2
      )(using node.blockchainConfig)
      val sink: Sink = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
        override def requestedHead: Option[ByteString] = namedNow()
      late.bind((tip, why) =>
        handedOff.set(Some(recovery.requestFor(tip, sink, why, StateRecovery.Authority.ForkChoice)))
        true
      )
      try
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        named.set(Some(bChain.last.hash.value))
        evaluate(bChain) match
          case ConsensusStateFault(_: HandedToRecovery, Nil, Nil) => ()
          case other                                              => fail(s"not handed off: $other")
        // nothing judged and nothing moved here; the branch is stored for the recovery
        reported shouldBe Nil
        bChain.foreach(block => node.blockchainReader.getBlockByHash(block.hash) shouldBe Some(block))
        val ticket = handedOff.get.getOrElse(fail("no recovery was asked for"))
        ticket.head shouldBe bChain.last.hash.value
        ticket.outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Adopted(4)
        node.bestHash shouldBe bChain.last.hash
        (3 to 6).foreach(n => node.indexed(n) shouldBe Some(bChain(n - 3).hash))
      finally recovery.close()
  }

  // B-316: in the archive mode the records lead further back than a view reaches. The consensus layer's branch that
  // leaves the chain below what a view reaches, its blocks stored by hash (as engine_newPayload stores those whose
  // parent's state is not held), is adopted by the fork choice's recovery as the import path adopts a heavier chain
  // that forks that deep: the head moved down to the fork by the records, with no view, and the branch committed in
  // place. A payload's recovery, which never moves the head, still walks only to a state a view reaches.
  it should "be adopted by the fork choice's recovery when it forks below what a view reaches, by the records" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture(archiveOfShortViews):
    val recovery = new StateRecovery(
      node.blockchainReader,
      node.blockchainWriter,
      node.blockExecution,
      node.storagesInstance.storages.pathStateStorage.get,
      maxBlocks = 2
    )(using node.blockchainConfig)
    val sink: Sink = new StateRecovery.Sink:
      def executed(
          block: Block,
          receipts: Seq[Receipt],
          accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
      ): Unit = ()
      def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
      override def requestedHead: Option[ByteString] = namedNow()
      override def followedHead: Option[ByteString] = Some(aChain.last.hash.value)
    try
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      // the fork, block 2, is three blocks below the head: behind what a view reaches (two), and the records lead there
      val state = node.storagesInstance.storages.pathStateStorage.get
      state.stateAvailable(BlockRef(aChain(1).hash.value, 2, aChain(1).header.stateRoot.value)) shouldBe false
      bChain.foreach(block => node.blockchainWriter.storeBlockByHashOnly(block).commit())
      named.set(Some(bChain.last.hash.value))
      // a payload's recovery moves no head: it does not get there, and nothing moves
      recovery
        .requestFor(bChain.last.hash.value, sink, "a payload")
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe a[StateRecovery.Outcome.TooLong]
      node.bestHash shouldBe aChain.last.hash
      // the fork choice's: down to block 2 by the records, and blocks 3' to 6' committed in place
      recovery
        .requestFor(bChain.last.hash.value, sink, "a fork choice", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Adopted(4)
      node.bestHash shouldBe bChain.last.hash
      (1 to 2).foreach(n => node.indexed(n) shouldBe Some(aChain(n - 1).hash))
      (3 to 6).foreach(n => node.indexed(n) shouldBe Some(bChain(n - 3).hash))
      state.anchor.map(_.hash) shouldBe Some(bChain.last.hash.value)
      reported shouldBe Nil
    finally recovery.close()
  )

  // B-480 (beacon): the head itself is never a stop by the records. A fork choice to an ancestor of the head that no
  // view reaches is not "already held": its walk goes on down, and finds no state
  it should "not take a head of the index below what a view reaches for one whose state is held" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture(archiveOfShortViews):
    val recovery = new StateRecovery(
      node.blockchainReader,
      node.blockchainWriter,
      node.blockExecution,
      node.storagesInstance.storages.pathStateStorage.get,
      maxBlocks = 2
    )(using node.blockchainConfig)
    val sink: Sink = new StateRecovery.Sink:
      def executed(
          block: Block,
          receipts: Seq[Receipt],
          accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
      ): Unit = ()
      def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
      override def requestedHead: Option[ByteString] = namedNow()
    try
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      val state = node.storagesInstance.storages.pathStateStorage.get
      // block 2: of the index, behind the anchor, three blocks below the head, where no view reaches
      state.stateAvailable(BlockRef(aChain(1).hash.value, 2, aChain(1).header.stateRoot.value)) shouldBe false
      named.set(Some(aChain(1).hash.value))
      recovery
        .requestFor(aChain(1).hash.value, sink, "a fork choice", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe a[StateRecovery.Outcome.TooLong]
      node.bestHash shouldBe aChain.last.hash
      state.anchor.map(_.hash) shouldBe Some(aChain.last.hash.value)
    finally recovery.close()
  )

  it should "be taken back up to the head followed, by the records, when its tip is invalid below what a view reaches" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture(archiveOfShortViews):
    val recovery = new StateRecovery(
      node.blockchainReader,
      node.blockchainWriter,
      node.blockExecution,
      node.storagesInstance.storages.pathStateStorage.get,
      maxBlocks = 2
    )(using node.blockchainConfig)
    val refused = new java.util.concurrent.ConcurrentLinkedQueue[ByteString]()
    val sink: Sink = new StateRecovery.Sink:
      def executed(
          block: Block,
          receipts: Seq[Receipt],
          accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
      ): Unit = ()
      def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit =
        refused.add(block.hash.value)
      override def requestedHead: Option[ByteString] = namedNow()
      override def followedHead: Option[ByteString] = Some(aChain.last.hash.value)
    try
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      // 6' names a state its execution does not leave
      val bad = bChain.last.copy(header = bChain.last.header.copy(stateRoot = TrieRoot.Empty))
      (bChain.init :+ bad).foreach(block => node.blockchainWriter.storeBlockByHashOnly(block).commit())
      named.set(Some(bad.hash.value))
      recovery
        .requestFor(bad.hash.value, sink, "a fork choice", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe a[StateRecovery.Outcome.Invalid]
      refused.toArray.toSeq shouldBe Seq(bad.hash.value)
      // back on block 5, the head followed, with its chain
      node.bestHash shouldBe aChain.last.hash
      (1 to 5).foreach(n => node.indexed(n) shouldBe Some(aChain(n - 1).hash))
      node.storagesInstance.storages.pathStateStorage.get.anchor.map(_.hash) shouldBe Some(aChain.last.hash.value)
    finally recovery.close()
  )

  // B-463 (forge): the fork choice's recovery reads the head it may move back to, then holds the records. A block
  // committed on that head in between (here, by an import the sink's first look at the head followed sets off) refuses
  // the hold, as busy, with nothing written; the recovery answers Superseded, and the next fork choice adopts the
  // branch from the head as it is.
  it should "be superseded, with nothing written, when a block is committed on the head between the recovery's read of it and its hold" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture(archiveOfShortViews):
    val recovery = new StateRecovery(
      node.blockchainReader,
      node.blockchainWriter,
      node.blockExecution,
      node.storagesInstance.storages.pathStateStorage.get,
      maxBlocks = 2
    )(using node.blockchainConfig)
    val state = node.storagesInstance.storages.pathStateStorage.get
    val onTheHead: Block = oracle.extendCanonical(aChain.last, 1, difficulty = 0).head
    // the store as the block committed meanwhile left it
    val untouched = new java.util.concurrent.atomic.AtomicReference[AnyRef]()
    @volatile var raced = false
    val sink: Sink = new StateRecovery.Sink:
      def executed(
          block: Block,
          receipts: Seq[Receipt],
          accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
      ): Unit = ()
      def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
      override def requestedHead: Option[ByteString] = namedNow()
      // asked by the adoption after it read the head it may move back to, before it holds the records
      override def followedHead: Option[ByteString] =
        if !raced then
          raced = true
          node.importOne(onTheHead) shouldBe a[BlockImportedToTop]
          untouched.set(node.storagesInstance.dataSource.storage)
        Some(onTheHead.hash.value)
    try
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      bChain.foreach(block => node.blockchainWriter.storeBlockByHashOnly(block).commit())
      named.set(Some(bChain.last.hash.value))
      recovery
        .requestFor(bChain.last.hash.value, sink, "a fork choice", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Superseded
      raced shouldBe true
      // nothing of the adoption was written: the head is the block committed meanwhile
      (node.storagesInstance.dataSource.storage eq untouched.get) shouldBe true
      node.bestHash shouldBe onTheHead.hash
      state.anchor.map(_.hash) shouldBe Some(onTheHead.hash.value)
      state.headGeneration % 2 shouldBe 0
      // the next fork choice: adopted from the head as it is
      recovery
        .requestFor(bChain.last.hash.value, sink, "a fork choice", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Adopted(4)
      node.bestHash shouldBe bChain.last.hash
      (3 to 6).foreach(n => node.indexed(n) shouldBe Some(bChain(n - 3).hash))
      reported shouldBe Nil
    finally recovery.close()
  )

  it should "set what hangs from it aside while the head stays, once it is handed off, and not hand off a fork below the views" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    for (config, handedOff) <- Seq[(StateConfig => StateConfig, Boolean)](
        (pinnedCap(1L), true),
        (archiveOfShortViews, false)
      )
    do
      val asked = new java.util.concurrent.ConcurrentLinkedQueue[ByteString]()
      val recording: BranchHandOff = (tip, _) => asked.add(tip)
      new DesignatedFixture(config, Some(recording)):
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        named.set(Some(bChain.last.hash.value))
        val above: Block = oracle.extendSide(bChain.last, 1, difficulty = 0, tag = 0x79).head
        withClue(s"handed off: $handedOff: ") {
          evaluate(bChain) match
            case ConsensusStateFault(_: HandedToRecovery, Nil, Nil) if handedOff =>
              asked.toArray.toSeq shouldBe Seq(bChain.last.hash.value)
              // nothing moved yet: the batch above the branch is set aside, not fetched from below
              consensus.refusedWithTheirParent(NonEmptyList.of(above)) shouldBe true
            // a fork below what a view reaches: the recovery's walk does not reach it (beacon). Queued again, as before
            case ConsensusStateFault(fault, Nil, queued) if !handedOff =>
              fault shouldNot be(a[HandedToRecovery])
              queued.map(_.hash) shouldBe bChain.map(_.hash)
              asked shouldBe empty
              consensus.refusedWithTheirParent(NonEmptyList.of(above)) shouldBe false
            case other => fail(s"not as expected: $other")
          node.bestHash shouldBe aChain.last.hash
        }
  }

  it should "not move the head back when a fork choice moved it to the branch's tip meanwhile" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedFixture():
    node.importAll(aChain) shouldBe a[BlockImportedToTop]
    val reads = new java.util.concurrent.atomic.AtomicInteger(0)
    // as the branch is settled, after its execution and before the import path's move: the fork choice's move to it
    override def namedNow(): Option[ByteString] =
      if reads.incrementAndGet() == 2 then
        node.blockchainWriter.moveHeadInPlace(bChain.last.header, node.blockchainReader)
      Some(bChain.last.hash.value)
    evaluate(bChain) match
      // the blocks are the head's chain; those the head left were that move's to tell
      case SelectedNewBestBranch(Nil, newBranch, weights, _) =>
        newBranch.map(_.hash) shouldBe bChain.map(_.hash)
        weights.size shouldBe bChain.size
      case other => fail(s"not told as the head's: $other")
    node.bestHash shouldBe bChain.last.hash
  )

  it should "not take the head off a block it moved to meanwhile" taggedAs (UnitTest, ConsensusTest) in (
    new DesignatedFixture():
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      val a6: Block = oracle.extendCanonical(aChain.last, 1, difficulty = 0).head
      val reads = new java.util.concurrent.atomic.AtomicInteger(0)
      // the head goes on to block 6 of its own chain while the branch is settled
      override def namedNow(): Option[ByteString] =
        if reads.incrementAndGet() == 2 then node.importAll(List(a6)) shouldBe a[BlockImportedToTop]
        Some(bChain.last.hash.value)
      evaluate(bChain) shouldBe KeptCurrentBestBranch
      node.bestHash shouldBe a6.hash
      node.blockchainWriter.stateHeldInPlaceOf(bChain.last.header) shouldBe true
  )

  it should "tell the side blocks it stands on as of the new branch" taggedAs (UnitTest, ConsensusTest) in (
    new DesignatedFixture():
      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      // blocks 3 and 4 of the branch, executed and kept beside the head: another head is named once they ran
      @volatile var first = true
      val reads = new java.util.concurrent.atomic.AtomicInteger(0)
      override def namedNow(): Option[ByteString] =
        if first then if reads.incrementAndGet() <= 1 then Some(bChain(1).hash.value) else Some(aChain.last.hash.value)
        else Some(bChain.last.hash.value)
      evaluate(bChain.take(2)) shouldBe KeptCurrentBestBranch
      first = false
      evaluate(bChain.drop(2)) match
        case SelectedNewBestBranch(oldBranch, newBranch, weights, branchBlocks) =>
          oldBranch.map(_.hash) shouldBe aChain.drop(2).map(_.hash)
          newBranch.map(_.hash) shouldBe bChain.map(_.hash)
          // the two evaluated now are the branch's own (new-head events); the two kept beside the head are side blocks
          branchBlocks shouldBe 2
          weights shouldBe bChain.map(block => node.blockchainReader.getChainWeightByHash(block.hash).get)
        case other => fail(s"not adopted: $other")
      node.bestHash shouldBe bChain.last.hash
  )

  for (layout, cap) <- Seq[(String, StateConfig => StateConfig)](
      ("on layers", identity),
      ("at the cap", pinnedCap(1L))
    )
  do
    s"A heavier branch on the unified layout ($layout)" should
      "tell the side blocks it stands on as of the new branch, with their weights" taggedAs (
        UnitTest,
        ConsensusTest
      ) in (new Fixture(guard = true, cap):
        // a1..a5; then b3..b5, heavier, which leaves a3..a5 beside the head; then a6, a7 on a5, heavier again
        val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 5, difficulty = 1000)
        val bChain: List[Block] = oracle.extendSide(aChain(1), 3, difficulty = 2000, tag = 0x77)
        val aMore: List[Block] = oracle.extendSide(aChain.last, 2, difficulty = 5000, tag = 0x78)
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        node.importAll(bChain) shouldBe a[ChainReorganised]
        node.bestHash shouldBe bChain.last.hash
        node.importAll(aMore) match
          case ChainReorganised(oldBranch, newBranch, weights, branchBlocks) =>
            oldBranch.map(_.hash) shouldBe bChain.map(_.hash)
            newBranch.map(_.hash) shouldBe (aChain.drop(2) ++ aMore).map(_.hash)
            // a6 and a7 were imported by this call; a3..a5 are stored side blocks it made canonical (B-344)
            branchBlocks shouldBe aMore.size
            weights shouldBe newBranch.map(block => node.blockchainReader.getChainWeightByHash(block.hash).get)
          case other => fail(s"not reorganised: $other")
        node.bestHash shouldBe aMore.last.hash
      )

    // B-344: what a reorganisation tells of the blocks it moved the head onto is bounded as the old branch is, the
    // nearest the new head (the side blocks below it are still checked stored whole before the move, as before).
    s"A heavier branch on the unified layout that stands on more side blocks than the bound ($layout)" should
      "tell the blocks nearest the new head only, with their weights" taggedAs (
        UnitTest,
        ConsensusTest
      ) in (new CountingFixture(guard = true, bound = 3, cap = cap):
        // a1..a10; then b3..b10, heavier, which leaves a3..a10 beside the head; then a11, a12 on a10, heavier again:
        // ten blocks become canonical, eight of them side blocks the branch stands on
        val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 10, difficulty = 1000)
        val bChain: List[Block] = oracle.extendSide(aChain(1), 8, difficulty = 2000, tag = 0x79)
        val aMore: List[Block] = oracle.extendSide(aChain.last, 2, difficulty = 50000, tag = 0x7a)
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        node.importAll(bChain) shouldBe a[ChainReorganised]
        node.bestHash shouldBe bChain.last.hash
        evaluate(consensus, aMore) match
          case SelectedNewBestBranch(oldBranch, newBranch, weights, branchBlocks) =>
            oldBranch.map(_.hash) shouldBe bChain.takeRight(3).map(_.hash)
            newBranch.map(_.hash) shouldBe List(aChain.last, aMore.head, aMore.last).map(_.hash)
            branchBlocks shouldBe aMore.size
            weights shouldBe newBranch.map(block => node.blockchainReader.getChainWeightByHash(block.hash).get)
          case other => fail(s"not reorganised: $other")
        node.bestHash shouldBe aMore.last.hash
        (3 to 10).foreach(n => node.indexed(n) shouldBe Some(aChain(n - 1).hash))
      )

  "A reorganisation told with a bound of 0 (the hash layout)" should
    "still tell its new head, and none of the old branch" taggedAs (UnitTest, ConsensusTest) in (
      new CountingFixture(guard = false, bound = 0):
        val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 5, difficulty = 1000)
        val side: List[Block] = oracle.extendSide(aChain(1), 4, difficulty = 1000, tag = 0x7b)
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        evaluate(consensus, side) match
          case SelectedNewBestBranch(oldBranch, newBranch, weights, branchBlocks) =>
            oldBranch shouldBe Nil
            newBranch.map(_.hash) shouldBe List(side.last.hash)
            branchBlocks shouldBe 1
            weights shouldBe List(node.blockchainReader.getChainWeightByHash(side.last.hash).get)
          case other => fail(s"not reorganised: $other")
        node.bestHash shouldBe side.last.hash
    )

  // B-343: the side blocks a branch stands on whose state a restart lost are read and executed again a chunk at a time,
  // under the pin and when committed in place at the cap. What is answered, the head, the index, the weights and the
  // state on disk must be what one run over all of them gives (a chunk no smaller than the side chain), for every depth
  // and every chunk, and when a side block in a middle chunk fails, is not stored whole, or its commit meets a fault.
  for (layout, cap) <- Seq[(String, StateConfig => StateConfig)](
      ("on layers", identity),
      ("at the cap", pinnedCap(1L)),
      ("the cap met among the side blocks", pinnedCap(MidCap))
    )
  do
    s"A heavier branch on the unified layout ($layout) that stands on side blocks a restart lost" should
      "be answered, and leave the head and the state, as one run over all of them does, whatever the chunk" taggedAs (
        UnitTest,
        ConsensusTest
      ) in {
        for lost <- Seq(3, 8, 13) do
          val chain = new LostSideChain(lost, cap)
          import chain.*
          val whole = evaluated(Unchunked)
          withClue(s"$lost side blocks, in one run: ") {
            whole.result should startWith("SelectedNewBestBranch")
            whole.head shouldBe aMore.last.hash
            whole.index shouldBe (aChain ++ aMore).map(block => Some(block.hash))
          }
          for chunk <- Seq(1, 2, 5) do
            withClue(s"$lost side blocks, chunks of $chunk: ")(evaluated(chunk) shouldBe whole)
      }

    it should "be answered alike whatever the chunk when a side block in a middle chunk fails as it is executed again" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new LostSideChain(8, cap):
      // a7, the fifth of the eight side blocks a3..a10: in the third chunk of two, the second of three
      val diverging: (Node, Faulty) => Unit = (node, _) =>
        node.storagesInstance.storages.blockBodiesStorage
          .put(aChain(6).hash.value, BlockBody(Nil, Seq(aChain(0).header)))
          .commit()
      val whole: Seen = evaluated(Unchunked, diverging)
      whole.result should include("block 7, which the branch stands on and was executed before, fails now")
      whole.head shouldBe bChain.last.hash
      for chunk <- Seq(1, 2, 3) do withClue(s"chunks of $chunk: ")(evaluated(chunk, diverging) shouldBe whole)
    )

    it should "be answered alike whatever the chunk when a side block in a middle chunk is not stored whole" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new LostSideChain(8, cap):
      val unstored: (Node, Faulty) => Unit =
        (node, _) => node.storagesInstance.storages.blockBodiesStorage.remove(aChain(6).hash.value).commit()
      val whole: Seen = evaluated(Unchunked, unstored)
      whole.result should include("block 7, which the branch stands on, is not stored whole")
      whole.head shouldBe bChain.last.hash
      for chunk <- Seq(1, 2, 3) do withClue(s"chunks of $chunk: ")(evaluated(chunk, unstored) shouldBe whole)
    )

  "A heavier branch committed in place at the cap that stands on side blocks a restart lost" should
    "be answered alike whatever the chunk when the commit of a side block in a middle chunk meets a fault" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new LostSideChain(8, pinnedCap(1L)):
      val faults: Seq[(String, (Node, Faulty) => Unit, Seen => Unit)] = Seq(
        (
          "a fault of the state: back to the old head",
          (_, datadir) => datadir.failAt(aChain(6), new StateUnavailable("injected: the commit of block 7")),
          seen =>
            seen.result should include("injected: the commit of block 7")
            seen.head shouldBe bChain.last.hash
        ),
        (
          "a write that throws: back to the old head, and thrown",
          (_, datadir) => datadir.failAt(aChain(6), new java.io.IOException("injected: the disk refused block 7")),
          seen =>
            seen.result should startWith("thrown java.io.IOException")
            seen.head shouldBe bChain.last.hash
        ),
        (
          "a fault, and a move back that fails before it wrote: where the head is left is said",
          (_, datadir) =>
            datadir.failAt(aChain(6), new StateUnavailable("injected: the commit of block 7"))
            // the move back from a6 to b7 is one batch: it fails, and the head stays on the side blocks committed
            datadir.failAt(bChain.last, new StateUnavailable("injected: the move back"))
          ,
          seen =>
            // B-461: a6 is a side block the branch stands on, committed before the fault, and not the chain the head
            // left (b3..b7): said as that, and told as the head, though no block of the branch is
            seen.result should include(
              "the head could not be moved back to block 7 and is at block 6, on the side blocks the branch [11-12] " +
                "stands on"
            )
            (seen.result should not).include("on the chain it left")
            seen.head shouldBe aChain(5).hash
            seen.headOn shouldBe aChain.slice(2, 6).map(_.hash)
            // B-460 N3, B-514: the answer names the chain the head left, b3..b7, and the side blocks it was left on,
            // so that the pool is reset onto a6 off b7
            seen.movedOff shouldBe bChain.map(_.hash)
            seen.movedOnto shouldBe aChain.slice(2, 6).map(_.hash)
        )
      )
      for (what, fault, expected) <- faults do
        val whole = evaluated(Unchunked, fault)
        withClue(s"$what, in one run: ")(expected(whole))
        for chunk <- Seq(1, 2, 3) do withClue(s"$what, chunks of $chunk: ")(evaluated(chunk, fault) shouldBe whole)
    )

  "The side blocks a heavier branch stands on, executed again under the pin" should
    "be read a chunk at a time, each chunk once the one before it was executed" taggedAs (UnitTest, ConsensusTest) in (
      new LostSideChain(8, identity):
        val lostHeaders: List[BlockHeader] = aChain.drop(2).map(_.header)
        val lostHashes: Set[BlockHash] = lostHeaders.map(_.hash).toSet
        // each read of a side block whole, with how many of the side blocks had their state held as it was read
        def reads(chunk: Int): List[(BlockHash, Int)] =
          val seen = new java.util.concurrent.ConcurrentLinkedQueue[(BlockHash, Int)]
          val result = evaluated(
            chunk,
            reader = node =>
              new ObservingReader(
                node.storagesInstance.storages,
                hash =>
                  if lostHashes(hash) then
                    val held = lostHeaders.count(node.blockchainWriter.stateHeldInPlaceOf)
                    seen.add(hash -> held): Unit
              )
          )
          result.head shouldBe aMore.last.hash
          val all = seen.asScala.toList
          // what reads them once all of them were executed (the move of the head) is not the run's
          all.drop(2 * lostHeaders.size).foreach(_._2 shouldBe lostHeaders.size)
          all.take(2 * lostHeaders.size)
        // each is checked stored whole first, with nothing executed; then read again to run
        val checked: List[(BlockHash, Int)] = lostHeaders.map(_.hash -> 0)
        reads(3) shouldBe checked ++ lostHeaders.zipWithIndex.map((header, i) => header.hash -> (i / 3) * 3)
        // in one run, every one is read before any is executed
        reads(Unchunked) shouldBe checked ++ checked
    )

  // B-343 (forge): of the side blocks executed again only the `oldBranchToldAtMost` nearest the branch are kept, and
  // what is told of a side block that is not kept is read back from the store (`sideParentsTold`). With a bound smaller
  // than the side chain the keeping is trimmed: what is told must still be the bound's nearest blocks, with their
  // weights, in chunks or in one run.
  for (layout, cap) <- Seq[(String, StateConfig => StateConfig)](
      ("on layers", identity),
      ("at the cap", pinnedCap(1L)),
      ("the cap met among the side blocks", pinnedCap(MidCap))
    )
  do
    s"A heavier branch on the unified layout ($layout) that stands on more lost side blocks than the told bound" should
      "tell the bound's nearest blocks, with their weights, whatever the chunk" taggedAs (
        UnitTest,
        ConsensusTest
      ) in {
        for bound <- Seq(2, 3, 5) do
          val chain = new LostSideChain(8, cap, bound)
          import chain.*
          val whole = evaluated(Unchunked)
          withClue(s"a bound of $bound, in one run: ") {
            whole.toldNew shouldBe (aChain.drop(2) ++ aMore).takeRight(bound).map(_.hash)
            whole.toldOld shouldBe bChain.takeRight(bound).map(_.hash)
            whole.head shouldBe aMore.last.hash
          }
          for chunk <- Seq(1, 3) do withClue(s"a bound of $bound, chunks of $chunk: ")(evaluated(chunk) shouldBe whole)
      }

  "A heavier branch on the unified layout whose told side blocks are partly held and partly executed again" should
    "tell the held ones as they are stored, and the others as they were executed, whatever the chunk" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new LostSideChain(8, identity, bound = 5):
      // a3..a8 held, a9 and a10 executed again: the five told are a8, read from the store, a9, a10, and the branch
      val whole: Seen = evaluated(Unchunked, held = 6)
      whole.toldNew shouldBe (aChain.drop(7) ++ aMore).map(_.hash)
      whole.head shouldBe aMore.last.hash
      evaluated(1, held = 6) shouldBe whole
    )

  /** A side chain a restart lost the state of (B-343): canonical a1..a(lost + 2); b3.., heavier, from a2, which leaves
    * a3..a(lost + 2) beside the head; the node is started again, which keeps no layer; then a heavier `aMore` on the a
    * tip is evaluated, standing on the `lost` side blocks, none of them held. With `held`, the `held` lowest of them
    * are held again first, by an attempt that stopped above them. `bound` is what is told at most.
    */
  class LostSideChain(lost: Int, cap: StateConfig => StateConfig, bound: Int = ConsensusImpl.OldBranchToldAtMost):
    val oracle = new Oracle
    val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, lost + 2, difficulty = 1000)
    private val bLength = lost / 2 + 1
    val bChain: List[Block] = oracle.extendSide(aChain(1), bLength, difficulty = 2000, tag = 0x91, miner = OtherMiner)
    val aMore: List[Block] = oracle.extendSide(aChain.last, 2, difficulty = 2000 * bLength, tag = 0x92)

    /** What an evaluation of `aMore` is seen to leave: its answer, the head, the anchor, the index above genesis, the
      * weight of every block, and the rows of the state on disk.
      */
    final case class Seen(
        result: String,
        toldNew: List[BlockHash],
        toldOld: List[BlockHash],
        head: BlockHash,
        anchor: Option[com.chipprbots.ethereum.db.storage.pathstate.BlockRef],
        index: Seq[Option[BlockHash]],
        weights: Seq[Option[ChainWeight]],
        rows: (Map[ByteString, ByteString], Map[ByteString, ByteString]),
        // the side blocks the answer tells as the head when a move back left it on them (B-461), oldest first
        headOn: List[BlockHash] = Nil,
        // what the adapter's answer names as the blocks the head left and the side blocks it was left on, which the
        // pool is reset by (B-460, B-514)
        movedOff: List[BlockHash] = Nil,
        movedOnto: List[BlockHash] = Nil
    )

    def evaluated(
        chunk: Int,
        prepare: (Node, Faulty) => Unit = (_, _) => (),
        reader: Node => BlockchainReader = _.blockchainReader,
        held: Int = 0
    ): Seen =
      val datadir = new Faulty
      val first = new Node(guard = true, cap, Some(datadir))
      first.importAll(aChain) shouldBe a[BlockImportedToTop]
      first.importAll(bChain) shouldBe a[ChainReorganised]
      val node = new Node(guard = true, cap, Some(datadir), restart = true)
      if held > 0 then
        // a first attempt, on another consensus, whose side block above the `held` lowest diverges: those below it were
        // executed again and are kept, by their layers, beside the head; then the block is stored whole again
        val bodies = node.storagesInstance.storages.blockBodiesStorage
        val diverging = aChain(2 + held)
        bodies.put(diverging.hash.value, BlockBody(Nil, Seq(aChain(0).header))).commit()
        val attempt = new ConsensusImpl(node.blockchainReader, node.blockchainWriter, node.blockExecution)
        locally {
          import node.blockchainConfig
          import node.runtime
          attempt.evaluateBranch(NonEmptyList.fromListUnsafe(aMore)).unsafeRunSync() shouldBe a[ConsensusStateFault]
        }
        bodies.put(diverging.hash.value, diverging.body).commit()
        aChain.slice(2, 2 + held).foreach(block => node.blockchainWriter.stateHeldInPlaceOf(block.header) shouldBe true)
      node.bestHash shouldBe bChain.last.hash
      aChain.drop(2 + held).foreach(block => node.blockchainWriter.stateHeldInPlaceOf(block.header) shouldBe false)
      prepare(node, datadir)
      val consensus = new ConsensusImpl(
        reader(node),
        node.blockchainWriter,
        node.blockExecution,
        reorgState = node.blockchain,
        oldBranchToldAtMost = bound,
        reorgChunkBlocks = chunk
      )
      val result =
        import node.blockchainConfig
        import node.runtime
        scala.util.Try(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(aMore)).unsafeRunSync()).toEither
      val all = aChain ++ bChain ++ aMore
      // the answer as the importer is given it: the result through an adapter, which names the blocks the head left
      val answered: Option[BlockImportResult] = result.toOption.map { evaluatedResult =>
        val answering = new Consensus:
          override def evaluateBranch(branch: NonEmptyList[Block])(implicit
              blockExecutionScheduler: IORuntime,
              blockchainConfig: BlockchainConfig
          ): cats.effect.IO[ConsensusResult] = cats.effect.IO.pure(evaluatedResult)
        import node.blockchainConfig
        import node.runtime
        new ConsensusAdapter(
          answering,
          node.blockchainReader,
          new com.chipprbots.ethereum.ledger.BlockQueue(node.blockchainReader, 100, 100),
          null,
          node.runtime
        ).evaluateBranch(NonEmptyList.fromListUnsafe(aMore)).unsafeRunSync()
      }
      val (toldNew, toldOld) = result match
        case Right(SelectedNewBestBranch(oldBranch, newBranch, weights, _)) =>
          weights shouldBe newBranch.map(block => node.blockchainReader.getChainWeightByHash(block.hash).get)
          (newBranch.map(_.hash), oldBranch.map(_.hash))
        case _ => (Nil, Nil)
      Seen(
        told(result),
        toldNew,
        toldOld,
        node.bestHash,
        node.storagesInstance.storages.appStateStorage.getStateAnchor(),
        (1 to all.map(_.number.value.toInt).max).map(node.indexed),
        all.map(block => node.blockchainReader.getChainWeightByHash(block.hash)),
        PathStateTestKit.allRows(new PathNodeStorage(datadir)),
        result.toOption.toList.flatMap(Consensus.headLeftOn).map(_.block.hash),
        answered.toList.flatMap(com.chipprbots.ethereum.blockchain.sync.regular.MovedOff.by).map(_.hash),
        answered.toList.flatMap(com.chipprbots.ethereum.blockchain.sync.regular.MovedOff.onto).map(_.hash)
      )

  "A branch of the head's own weight on a proof-of-work chain (the unified layout)" should
    "be kept beside the head: no consensus layer names a head there" taggedAs (UnitTest, ConsensusTest) in (
      new Fixture(guard = true):
        // as in production on ETC: the holder is there and nothing is bound to it
        val unbound = new DesignatedHead.LateBound
        val consensus = new ConsensusImpl(
          node.blockchainReader,
          node.blockchainWriter,
          node.blockExecution,
          None,
          Some(unbound)
        )
        val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 5, difficulty = 1000)
        val bChain: List[Block] = oracle.extendSide(aChain(1), 3, difficulty = 1000, tag = 0x75)
        node.importAll(aChain) shouldBe a[BlockImportedToTop]
        import node.blockchainConfig
        import node.runtime
        val kept: ConsensusResult = consensus.evaluateBranch(NonEmptyList.fromListUnsafe(bChain)).unsafeRunSync()
        kept shouldBe KeptCurrentBestBranch
        node.bestHash shouldBe aChain.last.hash
    )

  // B-088: the transaction lookups follow the head across a reorganisation, as core-geth's `reorg` keeps them
  // (core/blockchain.go: `types.HashDifference(deletedTxs, addedTxs)`, then `rawdb.DeleteTxLookupEntry`). A transaction
  // only the blocks the head left include has no lookup; one the new chain includes too is looked up in the new chain's
  // block. eth_getTransactionByHash follows a lookup without asking whether its block is canonical.
  for (layout, guard) <- Seq("the hash layout" -> false, "the unified layout" -> true) do
    (s"A heavier, shorter branch ($layout)" should
      "drop the lookups of the transactions only the old chain held, and point a shared one at its new block").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard):
      val common: List[Block] = oracle.extendCanonical(oracle.genesis, 3, difficulty = 1000)
      // a4 and a6 hold transactions; a6 is above the new head, a4 is replaced at its height
      val aChain: List[Block] =
        oracle.extendWith(common.last, List(Seq(Shared), Nil, Seq(OnlyA)), difficulty = 1000, tag = 0x61)
      val bChain: List[Block] =
        oracle.extendWith(common.last, List(Nil, Seq(Shared, OnlyB)), difficulty = 2000, tag = 0x62)
      node.importAll(common ++ aChain) shouldBe a[BlockImportedToTop]
      node.lookup(OnlyA) shouldBe Some(TransactionLocation(aChain(2).hash.value, 0))
      node.lookup(Shared) shouldBe Some(TransactionLocation(aChain.head.hash.value, 0))

      node.importAll(bChain) shouldBe a[ChainReorganised]
      node.bestHash shouldBe bChain.last.hash
      node.indexed(6) shouldBe None
      node.lookup(OnlyA) shouldBe None
      node.lookup(Shared) shouldBe Some(TransactionLocation(bChain(1).hash.value, 0))
      node.lookup(OnlyB) shouldBe Some(TransactionLocation(bChain(1).hash.value, 1))

      // and back: the old chain's blocks, now the side chain a heavier block stands on, are looked up again
      val a7: List[Block] = oracle.extendWith(aChain.last, List(Nil), difficulty = 2000, tag = 0x61)
      node.importAll(a7) shouldBe a[ChainReorganised]
      node.bestHash shouldBe a7.last.hash
      node.lookup(OnlyB) shouldBe None
      node.lookup(OnlyA) shouldBe Some(TransactionLocation(aChain(2).hash.value, 0))
      node.lookup(Shared) shouldBe Some(TransactionLocation(aChain.head.hash.value, 0))
    )

    it should "drop the lookups of an executed prefix that does not outweigh the head it keeps" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard):
      val common: List[Block] = oracle.extendCanonical(oracle.genesis, 3, difficulty = 1000)
      val aChain: List[Block] =
        oracle.extendWith(common.last, List(Seq(Shared), Nil, Nil), difficulty = 1000, tag = 0x61)
      // b4 alone (1000) does not outweigh a4..a6 (3000); with the invalid b5 (5000) the branch does
      val b4: List[Block] = oracle.extendWith(common.last, List(Seq(Shared, OnlyB)), difficulty = 1000, tag = 0x62)
      val b5Invalid: Block = oracle.invalidChild(b4.head, difficulty = 5000)
      node.importAll(common ++ aChain) shouldBe a[BlockImportedToTop]

      node.importAll(b4 :+ b5Invalid) shouldBe a[BlockImportFailed]
      node.bestHash shouldBe aChain.last.hash
      node.lookup(OnlyB) shouldBe None
      node.lookup(Shared) shouldBe Some(TransactionLocation(aChain.head.hash.value, 0))
    )

    (s"The consensus layer's branch ($layout)" should
      "drop the lookups of the transactions only the chain it left held, and the index above its head").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new DesignatedFixture(guard = guard):
      val common: List[Block] = oracle.extendCanonical(oracle.genesis, 2, difficulty = 0)
      val left: List[Block] =
        oracle.extendWith(common.last, List(Seq(Shared), Nil, Seq(OnlyA)), difficulty = 0, tag = 0x61)
      val designated: List[Block] =
        oracle.extendWith(common.last, List(Nil, Seq(Shared, OnlyB)), difficulty = 0, tag = 0x62)
      node.importAll(common ++ left) shouldBe a[BlockImportedToTop]
      named.set(Some(designated.last.hash.value))

      evaluate(designated) shouldBe a[SelectedNewBestBranch]
      node.bestHash shouldBe designated.last.hash
      (3 to 4).foreach(n => node.indexed(n) shouldBe Some(designated(n - 3).hash))
      node.indexed(5) shouldBe None
      node.lookup(OnlyA) shouldBe None
      node.lookup(Shared) shouldBe Some(TransactionLocation(designated(1).hash.value, 0))
      node.lookup(OnlyB) shouldBe Some(TransactionLocation(designated(1).hash.value, 1))
    )

  // B-505: on the hash layout the consensus layer's branch becomes the head only when the whole of it is valid and it
  // still leads to the head the consensus layer names, as on the unified layout (`settleOnLayers`) and in go-ethereum,
  // whose SetCanonical only runs on a head that was inserted whole. What of it was executed before an invalid block, a
  // fault of this node's state, or another head named meanwhile is kept beside the head: the index is put back, and
  // the transactions only those blocks include lose the lookups their commits wrote. `left` is blocks 3 and 4 on
  // `common`; `prefix` leaves `common` at the same block and is blocks 3 to 5, one above the old head.
  class DesignatedOnTheHashLayout extends DesignatedFixture(guard = false, hooked = true):
    val common: List[Block] = oracle.extendCanonical(oracle.genesis, 2, difficulty = 0)
    val left: List[Block] = oracle.extendWith(common.last, List(Seq(Shared), Seq(OnlyA)), difficulty = 0, tag = 0x61)
    val prefix: List[Block] =
      oracle.extendWith(common.last, List(Nil, Seq(Shared, OnlyB), Nil), difficulty = 0, tag = 0x62)
    node.importAll(common ++ left) shouldBe a[BlockImportedToTop]

    def keptTheOldHead(): Unit =
      node.bestHash shouldBe left.last.hash
      (1 to 2).foreach(n => node.indexed(n) shouldBe Some(common(n - 1).hash))
      (3 to 4).foreach(n => node.indexed(n) shouldBe Some(left(n - 3).hash))
      node.indexed(5) shouldBe None
      node.lookup(OnlyB) shouldBe None
      node.lookup(Shared) shouldBe Some(TransactionLocation(left.head.hash.value, 0))
      node.lookup(OnlyA) shouldBe Some(TransactionLocation(left(1).hash.value, 0))

  "The consensus layer's branch on the hash layout" should
    "leave the head and the index where they were when a block after a valid prefix is invalid, and report that block" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new DesignatedOnTheHashLayout:
      val invalid: Block = oracle.invalidChild(prefix.last, difficulty = 0)
      named.set(Some(invalid.hash.value))
      evaluate(prefix :+ invalid) match
        case BranchExecutionFailure(_, failing, _) => failing shouldBe invalid.hash.value
        case other                                 => fail(s"not a failure of the branch: $other")
      keptTheOldHead()
      reported shouldBe List(invalid.hash.value -> prefix.last.hash.value)
    )

  it should "leave the head and the index where they were when this node's state faults mid-branch, and judge nothing" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedOnTheHashLayout:
    val injected = new StateUnavailable("injected: the state of block 5")
    onExecute = block => if block.hash == prefix(2).hash then throw injected
    named.set(Some(prefix.last.hash.value))
    evaluate(prefix) match
      case ConsensusStateFault(fault, imported, toEnqueue) =>
        (fault should be).theSameInstanceAs(injected)
        imported shouldBe Nil
        toEnqueue shouldBe prefix.drop(2)
      case other => fail(s"not a fault of this node's state: $other")
    keptTheOldHead()
    reported shouldBe Nil
  )

  it should "not move the head when the consensus layer names another head while the branch is executed" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedOnTheHashLayout:
    named.set(Some(prefix.last.hash.value))
    onExecute = block => if block.hash == prefix.last.hash then named.set(Some(left.last.hash.value))
    evaluate(prefix) shouldBe KeptCurrentBestBranch
    keptTheOldHead()
    reported shouldBe Nil
  )

  it should "become the head when every block of it is valid and it still leads to the head named" taggedAs (
    UnitTest,
    ConsensusTest
  ) in (new DesignatedOnTheHashLayout:
    named.set(Some(prefix.last.hash.value))
    evaluate(prefix) match
      case SelectedNewBestBranch(oldBranch, newBranch, _, _) =>
        oldBranch.map(_.hash) shouldBe left.map(_.hash)
        newBranch.map(_.hash) shouldBe prefix.map(_.hash)
      case other => fail(s"not adopted: $other")
    node.bestHash shouldBe prefix.last.hash
    (3 to 5).foreach(n => node.indexed(n) shouldBe Some(prefix(n - 3).hash))
    node.lookup(OnlyA) shouldBe None
    node.lookup(Shared) shouldBe Some(TransactionLocation(prefix(1).hash.value, 0))
    node.lookup(OnlyB) shouldBe Some(TransactionLocation(prefix(1).hash.value, 1))
    reported shouldBe Nil
  )

  /** A node past the merge (a terminal total difficulty of 0), on the unified layout unless `guard` is false, with a
    * `ConsensusImpl` that has a head the consensus layer names and a reporter of invalid blocks, as the node builds one
    * with the Engine API on. `aChain` is blocks 1 to 5; `bChain` leaves it after block 2 and is blocks 3 to 6. With
    * `hooked`, its `consensus` executes through a `BlockExecution` that runs `onExecute` on each block it is about to
    * execute (B-505).
    */
  class DesignatedFixture(
      tune: StateConfig => StateConfig = identity,
      handOff: Option[BranchHandOff] = None,
      guard: Boolean = true,
      hooked: Boolean = false
  ):
    private val merged = (config: BlockchainConfig) => config.copy(terminalTotalDifficulty = Some(BigInt(0)))
    val oracle: Oracle = new Oracle:
      implicit override lazy val blockchainConfig: BlockchainConfig = merged(super.blockchainConfig)
    val node: Node = new Node(guard, tune):
      implicit override lazy val blockchainConfig: BlockchainConfig = merged(super.blockchainConfig)
    val named = new java.util.concurrent.atomic.AtomicReference[Option[ByteString]](None)
    def namedNow(): Option[ByteString] = named.get()
    var reported: List[(ByteString, ByteString)] = Nil
    private val reporter = new InvalidChainReporter:
      override def reportInvalid(blockHash: ByteString, latestValidHash: ByteString): Unit =
        reported :+= (blockHash -> latestValidHash)
    @volatile var onExecute: Block => Unit = _ => ()
    private val execution: BlockExecution =
      if !hooked then node.blockExecution
      else
        new BlockExecution(
          node.blockchain,
          node.blockchainReader,
          node.blockchainWriter,
          node.storagesInstance.storages.evmCodeStorage,
          node.mining.blockPreparator,
          node.blockValidation
        ):
          override def executeAndValidateStaged(block: Block, alreadyValidated: Boolean, staged: StagedBlockState)(
              implicit blockchainConfig: BlockchainConfig
          ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
            onExecute(block)
            super.executeAndValidateStaged(block, alreadyValidated, staged)
    val consensus = new ConsensusImpl(
      node.blockchainReader,
      node.blockchainWriter,
      execution,
      Some(reporter),
      Some(DesignatedHead(() => namedNow())),
      ReorgStateHandler.NoOp,
      handOff
    )
    val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 5, difficulty = 0)
    val bChain: List[Block] = oracle.extendSide(aChain(1), 4, difficulty = 0, tag = 0x74)
    def evaluate(branch: List[Block]): ConsensusResult =
      import node.blockchainConfig
      import node.runtime
      consensus.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeRunSync()

  /** [[KilledReorg]]'s two chains on a node that runs on, at the cap with nothing kept under the pin, so that the whole
    * branch is committed in place. An import of the branch through this fixture's `consensus` stops at the execution in
    * place of the branch's block `at`, runs `meanwhile` on another thread, and waits for it (B-294).
    */
  class RacedReorg(at: Int) extends KilledReorg(pinnedCap(1L)):
    val datadir = new EphemDataSource(Map.empty)
    val node = new Node(guard = true, pinnedCap(1L), Some(datadir))
    node.importAll(oldChain) shouldBe a[BlockImportedToTop]
    val state: PathStateStorage = node.storagesInstance.storages.pathStateStorage.get
    @volatile var meanwhile: () => Unit = () => ()
    // what `meanwhile` came to: an assertion of it that failed is said on the test's own thread
    @volatile var ranMeanwhile: Option[scala.util.Try[Unit]] = None
    private val execution = new BlockExecution(
      node.blockchain,
      node.blockchainReader,
      node.blockchainWriter,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      node.blockValidation
    ):
      override def executeAndValidateStaged(block: Block, alreadyValidated: Boolean, staged: StagedBlockState)(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
        // in place: the attempt is open, and the head is the block this one stands on
        if block.hash == branch(at).hash && state.headGeneration % 2 == 1 && ranMeanwhile.isEmpty then
          val result = new java.util.concurrent.atomic.AtomicReference[scala.util.Try[Unit]]()
          val thread = new Thread(() => result.set(scala.util.Try(meanwhile())))
          thread.start()
          thread.join()
          ranMeanwhile = Some(result.get)
        super.executeAndValidateStaged(block, alreadyValidated, staged)
    val consensus = new ConsensusImpl(
      node.blockchainReader,
      node.blockchainWriter,
      execution,
      reorgState = node.blockchain
    )

    def evaluate(blocks: List[Block]): ConsensusResult =
      import node.blockchainConfig
      import node.runtime
      consensus.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

  /** [[KilledReorg]]'s two chains at the cap with nothing kept under the pin, so that the branch is committed in place,
    * and a block `onTheHead` of `difficulty` on the old head h12 (B-463). The first hold of the records this fixture's
    * `consensus` asks for runs `meanwhile` first, on the same thread: after the branch was weighed against the head and
    * the head read, before the hold is opened. The branch is offered through an import adapter over that `consensus`
    * and the node's queue.
    */
  class AnchorRacedReorg(difficulty: BigInt) extends KilledReorg(pinnedCap(1L)):
    val onTheHead: Block =
      oracle.extendSide(oldChain.last, 1, difficulty = difficulty, tag = 0x85, miner = OtherMiner).head
    val datadir = new EphemDataSource(Map.empty)
    val node = new Node(guard = true, pinnedCap(1L), Some(datadir))
    node.importAll(oldChain) shouldBe a[BlockImportedToTop]
    val state: PathStateStorage = node.storagesInstance.storages.pathStateStorage.get
    @volatile var meanwhile: () => Unit = () => ()
    @volatile var ranMeanwhile = false
    private val storages = node.storagesInstance.storages
    private val writer = new BlockchainWriter(
      storages.blockHeadersStorage,
      storages.blockBodiesStorage,
      storages.blockNumberMappingStorage,
      storages.transactionMappingStorage,
      storages.receiptStorage,
      storages.chainWeightStorage,
      storages.appStateStorage,
      storages.blockAccessListStorage,
      pathState = storages.pathStateStorage
    ):
      override def withRecordsHeldAbove[A](fork: BlockHeader, expectedHead: BlockHeader)(body: => A): A =
        if !ranMeanwhile then
          ranMeanwhile = true
          meanwhile()
        super.withRecordsHeldAbove(fork, expectedHead)(body)
    val consensus = new ConsensusImpl(node.blockchainReader, writer, node.blockExecution, reorgState = node.blockchain)
    private val adapter = new ConsensusAdapter(
      consensus,
      node.blockchainReader,
      node.blockQueue,
      new com.chipprbots.ethereum.ledger.BlockValidation(node.mining, node.blockchainReader, node.blockQueue),
      node.runtime
    )

    /** The branch, offered through the import adapter over this fixture's `consensus`. */
    def offer(): BlockImportResult =
      import node.blockchainConfig
      import node.runtime
      adapter.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeRunSync()

  /** Canonical h1..h12 at difficulty 1000; the branch leaves it after h3, 9 blocks below the head, with 10 blocks at
    * 1000 sealed for another miner: heavier, and of another state at every height.
    */
  class KilledReorg(cap: StateConfig => StateConfig):
    val oracle = new Oracle
    val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 12, difficulty = 1000)
    val fork: Block = oldChain(2)
    val branch: List[Block] = oracle.extendSide(fork, 10, difficulty = 1000, tag = 0x81, miner = OtherMiner)
    val next: Block = oracle.extendSide(branch.last, 1, difficulty = 1000, tag = 0x82, miner = OtherMiner).head

    final case class Attempt(datadir: Killable, writes: Int, anchorMoves: Seq[Int])

    /** A node with the old chain, offered the branch and killed after `after` writes of that (never, with `None`). */
    def attempt(after: Option[Int]): Attempt =
      val datadir = new Killable
      val node = new Node(guard = true, cap, Some(datadir))
      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      datadir.arm(after.getOrElse(Int.MaxValue))
      val result = scala.util.Try(node.importAll(branch))
      if after.isEmpty then result.get shouldBe a[ChainReorganised]
      datadir.disarm()
      Attempt(datadir, datadir.writes, datadir.anchorMoves)

    /** The blocks of `chain` a peer offers: those above where it leaves the node's index. */
    def offer(node: Node, chain: List[Block]): Unit =
      val offered = chain.dropWhile(block => node.indexed(block.number.value.toInt).contains(block.hash))
      if offered.nonEmpty then
        node.importAll(offered) should (be(a[ChainReorganised]).or(be(a[BlockImportedToTop])).or(be(BlockEnqueued)))

    def weightOf(node: Node, hash: BlockHash): ChainWeight =
      node.blockchainReader.getChainWeightByHash(hash).getOrElse(fail(s"no weight for ${hash.toHexString}"))

    def where(head: BlockHeader): String =
      if head.hash == oldChain.last.hash then "the old head"
      else if head.hash == fork.hash then "the fork"
      else if head.hash == branch.last.hash then "the branch's tip"
      else if oldChain.exists(_.hash == head.hash) && head.number.value > fork.number.value then "the old chain"
      else if branch.exists(_.hash == head.hash) then "the branch"
      else s"block ${head.number.value}, of neither chain above the fork"

    /** The state's block is the best block, the index is its ancestry and holds nothing above it, its header is the
      * anchor's, and the disk holds its state row for row.
      */
    def validHead(node: Node, datadir: EphemDataSource): BlockHeader =
      val appState = node.storagesInstance.storages.appStateStorage
      val anchor = appState.getStateAnchor().getOrElse(fail("no anchor"))
      node.storagesInstance.storages.pathStateStorage.get.anchor shouldBe Some(anchor)
      val header = node.blockchainReader.getBlockHeaderByHash(BlockHash(anchor.hash)).getOrElse(fail("no header"))
      (header.number.value, header.stateRoot.value) shouldBe (anchor.number, anchor.stateRoot)
      (appState.getBestBlockInfo().hash, appState.getBestBlockInfo().number) shouldBe (anchor.hash, anchor.number)
      node.blockchainReader.getChainWeightByHash(header.hash) should not be empty
      val ancestry = Iterator
        .iterate(Option(header))(_.flatMap(h => node.blockchainReader.getBlockHeaderByHash(h.parentHash)))
        .takeWhile(_.exists(_.number.value > 0))
        .flatten
        .toVector
        .reverse
      ancestry.size shouldBe header.number.value.toInt
      (1 to header.number.value.toInt).map(node.indexed) shouldBe ancestry.map(h => Some(h.hash))
      (header.number.value.toInt + 1 to 14).foreach(n => node.indexed(n) shouldBe None)
      PathStateTestKit.allRows(new PathNodeStorage(datadir)) shouldBe stateOf(header)
      header

    /** The state of `header` on the oracle's hash store, as rows of the position store. */
    def stateOf(header: BlockHeader): (Map[ByteString, ByteString], Map[ByteString, ByteString]) =
      val nodes = oracle.storagesInstance.storages.nodeStorage
      val positions = RawTrieWalk.statePositions(
        header.stateRoot.value.toArray,
        hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the oracle lacks a node"))
      )
      (
        positions.getOrElse(None, Map.empty),
        positions
          .collect { case (Some(owner), trie) => trie.map((path, node) => (owner ++ path) -> node) }
          .flatten
          .toMap
      )

  /** [[KilledReorg]]'s two chains on a node that runs on, over a [[Faulty]] datadir (B-292): the old chain h1..h12 is
    * imported, and the branch b4..b13 is evaluated at the cap with nothing kept under the pin, so that the whole of it
    * is committed in place (C4e part 3). The move to the fork is three batches, to h9, h6 and h3; each block of the
    * branch is one commit; a move back from b6 is four batches, to h3, h6, h9 and h12.
    */
  class FaultedReorg extends KilledReorg(pinnedCap(1L).andThen(movesOfThree)):
    val datadir = new Faulty
    val node = new Node(guard = true, pinnedCap(1L).andThen(movesOfThree), Some(datadir))
    node.importAll(oldChain) shouldBe a[BlockImportedToTop]
    private val anchoredBefore = datadir.anchored.size

    /** The blocks the anchor was moved to since the old chain was imported, in order. */
    def anchoredSince: Vector[ByteString] = datadir.anchored.drop(anchoredBefore)
    val consensus = new ConsensusImpl(
      node.blockchainReader,
      node.blockchainWriter,
      node.blockExecution,
      reorgState = node.blockchain
    )

    def evaluate(blocks: List[Block]): Either[Throwable, ConsensusResult] =
      import node.blockchainConfig
      import node.runtime
      scala.util.Try(consensus.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()).toEither

    def injected(what: String): StateUnavailable = new StateUnavailable(s"injected: $what")

    def onTheHeadsChain(block: Block): Boolean = node.indexed(block.number.value.toInt).contains(block.hash)

    /** Every fault asked for was met, the attempt is over (the head's generation is even again), and the head is
      * `block`: the anchor, the best block, the index and the rows on disk all of it.
      */
    def settledOn(block: Block): Unit =
      datadir.unmet shouldBe 0
      node.storagesInstance.storages.pathStateStorage.get.headGeneration % 2 shouldBe 0
      validHead(node, datadir).hash shouldBe block.hash

    /** What a move back that failed is answered with (C4e part 3, the design's "a move back that fails is said"): a
      * fault of this node that names the block the head is at, with the blocks of the branch the head stands on as
      * imported, and every block of either chain above the fork that the head is not on queued again.
      */
    def toldWhereTheHeadIs(result: Either[Throwable, ConsensusResult], head: Block): Unit = result match
      case Right(ConsensusStateFault(fault, imported, queued)) =>
        fault.getMessage should include(s"at block ${head.number.value}")
        imported.map(_.block.hash) shouldBe branch.filter(onTheHeadsChain).map(_.hash)
        queued.map(_.hash) should contain allElementsOf
          (oldChain.drop(3) ++ branch).filterNot(onTheHeadsChain).map(_.hash)
      case other => fail(s"not a fault of this node: $other")

    /** Offered the branch again with nothing failing, the node adopts it, and executes its next block on its state. */
    def adoptedAgain(): Unit =
      offer(node, branch)
      validHead(node, datadir).hash shouldBe branch.last.hash
      node.importOne(next) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe next.hash

  /** A [[Fixture]] with an import's adapter over the node's queue and a consensus over its stores, through which the
    * store can be made busy (B-440, B-503): the next evaluations asked for throw RocksDB's `Busy` before any block is
    * executed, as a write the store refused for now would. Every branch the consensus is handed is recorded.
    */
  class BusyOnce(guard: Boolean) extends Fixture(guard):
    val evaluated: ArrayBuffer[List[BlockHash]] = ArrayBuffer.empty
    private var busyLeft: Int = 0
    private val consensus = new ConsensusImpl(
      node.blockchainReader,
      node.blockchainWriter,
      node.blockExecution,
      reorgState = node.blockchain
    )
    private val busyWhenAsked: Consensus = new Consensus:
      override def evaluateBranch(branch: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): cats.effect.IO[ConsensusResult] =
        cats.effect.IO.defer {
          evaluated += branch.toList.map(_.hash)
          if busyLeft > 0 then
            busyLeft -= 1
            val status = new org.rocksdb.Status(org.rocksdb.Status.Code.Busy, org.rocksdb.Status.SubCode.None, "busy")
            cats.effect.IO.raiseError(new org.rocksdb.RocksDBException("injected: the store is busy", status))
          else consensus.evaluateBranch(branch)
        }
    val adapter = new ConsensusAdapter(
      busyWhenAsked,
      node.blockchainReader,
      node.blockQueue,
      new com.chipprbots.ethereum.ledger.BlockValidation(node.mining, node.blockchainReader, node.blockQueue),
      node.runtime
    )

    /** The next `times` evaluations find the store busy. */
    def busyFor(times: Int): Unit = busyLeft = times

    /** As regular sync imports a fetched batch. */
    def inBatch(blocks: List[Block]): BlockImportResult =
      import node.blockchainConfig
      import node.runtime
      adapter.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    /** As regular sync imports a block a peer announced. */
    def announced(block: Block): BlockImportResult =
      import node.blockchainConfig
      import node.runtime
      adapter.evaluateBranchBlock(block).unsafeRunSync()

  class Fixture(guard: Boolean = false, cap: StateConfig => StateConfig = identity):
    val oracle = new Oracle
    val node = new Node(guard, cap)

    /** The block hash `block`'s execution recorded at `slot`, on the node under test: read from its state on the hash
      * layout; on the unified layout the recorder's storage is the oracle's, root for root (the state root the block
      * was accepted with commits to it either way).
      */
    def recorded(block: Block, slot: BigInt, expected: ByteString): Unit =
      if !guard then node.storedBlockhash(block, slot) shouldBe expected
      else
        node.blockchainReader.accountAt(block.header, BlockhashRecorder).map(_.storageRoot) shouldBe
          oracle.blockchainReader.accountAt(block.header, BlockhashRecorder).map(_.storageRoot)

  /** A [[Fixture]] with a second `ConsensusImpl` over the node's stores that reads through a [[CountingReader]], and
    * tells at most `bound` blocks of an old chain (B-296).
    */
  class CountingFixture(
      guard: Boolean,
      bound: Int = ConsensusImpl.OldBranchToldAtMost,
      cap: StateConfig => StateConfig = identity
  ) extends Fixture(guard, cap):
    val counting = new CountingReader(node.storagesInstance.storages)
    val consensus = new ConsensusImpl(
      counting,
      node.blockchainWriter,
      node.blockExecution,
      reorgState = node.blockchain,
      oldBranchToldAtMost = bound
    )
    def evaluate(consensus: ConsensusImpl, branch: List[Block]): ConsensusResult =
      import node.blockchainConfig
      import node.runtime
      consensus.evaluateBranch(NonEmptyList.fromListUnsafe(branch)).unsafeRunSync()

  /** Both hosts on a chain whose terminal total difficulty is 0, as hive configures post-merge fixtures. */
  class PostMergeFixture:
    val oracle: Oracle = new Oracle:
      implicit override lazy val blockchainConfig: BlockchainConfig =
        super.blockchainConfig.copy(terminalTotalDifficulty = Some(BigInt(0)))
    val node: Node = new Node:
      implicit override lazy val blockchainConfig: BlockchainConfig =
        super.blockchainConfig.copy(terminalTotalDifficulty = Some(BigInt(0)))

  /** Seals blocks. Canonical blocks are indexed (so its BLOCKHASH answers are the single-chain, core-geth answers);
    * side blocks are stored by hash only and carry no transactions.
    */
  class Oracle extends ChainHost:
    private var senderNonce: BigInt = 0

    def extendCanonical(
        parent: Block,
        count: Int,
        difficulty: BigInt,
        readBlockhashOf: Option[BigInt] = None
    ): List[Block] =
      (1 to count).toList.foldLeft(List.empty[Block]) { (acc, _) =>
        val p = acc.lastOption.getOrElse(parent)
        val txs = readBlockhashOf.toList.map(target => blockhashCall(target))
        val made = seal(p, txs, difficulty, tag = 0x00)
        blockchainWriter.storeBlock(made).commit()
        acc :+ made
      }

    def extendSide(parent: Block, count: Int, difficulty: BigInt, tag: Int, miner: Address = Miner): List[Block] =
      (1 to count).toList.foldLeft(List.empty[Block]) { (acc, _) =>
        val made = seal(acc.lastOption.getOrElse(parent), Nil, difficulty, tag, miner)
        blockchainWriter.storeBlockByHashOnly(made).commit()
        acc :+ made
      }

    /** A block on `parent` for each of `txs`, with those transactions, stored by hash only. */
    def extendWith(parent: Block, txs: List[Seq[SignedTransaction]], difficulty: BigInt, tag: Int): List[Block] =
      txs.foldLeft(List.empty[Block]) { (acc, blockTxs) =>
        val made = seal(acc.lastOption.getOrElse(parent), blockTxs, difficulty, tag)
        blockchainWriter.storeBlockByHashOnly(made).commit()
        acc :+ made
      }

    /** A correctly-linked child whose header commits to a state root no execution produces. */
    def invalidChild(parent: Block, difficulty: BigInt): Block =
      val valid = seal(parent, Nil, difficulty, tag = 0x7f)
      valid.copy(header = valid.header.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x11)))))

    private def blockhashCall(target: BigInt): SignedTransaction =
      val tx = LegacyTransaction(
        nonce = senderNonce,
        gasPrice = GasPrice(1),
        gasLimit = GasAmount(100000),
        receivingAddress = BlockhashRecorder,
        value = 0,
        payload = UInt256(target).bytes
      )
      senderNonce += 1
      SignedTransaction.sign(tx, SenderKeyPair, None)

    private def seal(
        parent: Block,
        txs: Seq[SignedTransaction],
        difficulty: BigInt,
        tag: Int,
        miner: Address = Miner
    ): Block =
      val template = parent.header.copy(
        parentHash = parent.hash,
        number = parent.number + 1,
        difficulty = Difficulty(difficulty),
        unixTimestamp = Timestamp(parent.header.unixTimestamp.toLong + 15),
        extraData = ByteString(Array[Byte](tag.toByte)),
        beneficiary = miner.bytes,
        gasLimit = GasAmount(1000000),
        gasUsed = GasAmount.Zero
      )
      val block = Block(template, BlockBody(txs, Nil))
      blockExecution.executeBlockNoValidation(block) match
        case Right((_, gasUsed, stateRoot)) =>
          block.copy(header = template.copy(stateRoot = TrieRoot(stateRoot), gasUsed = GasAmount(gasUsed)))
        case Left(error) => throw new IllegalStateException(s"oracle could not seal block ${template.number}: $error")

  /** The node under test. */
  class Node(
      guard: Boolean = false,
      cap: StateConfig => StateConfig = identity,
      datadir: Option[EphemDataSource] = None,
      restart: Boolean = false
  ) extends ChainHost(guard, cap, datadir, restart):
    def importAll(blocks: List[Block]): BlockImportResult =
      consensusAdapter.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    def importFile(blocks: List[Block]): (Int, Int, Int) =
      val file = java.nio.file.Files.createTempFile("chain", ".rlp")
      try
        java.nio.file.Files.write(file, blocks.flatMap(b => Block.BlockEnc(b).toBytes.toSeq).toArray)
        chainImporter.importChainFile(file.toString)
      finally java.nio.file.Files.delete(file)

    def importOne(block: Block): BlockImportResult =
      consensusAdapter.evaluateBranchBlock(block).unsafeRunSync()

    def bestHash: BlockHash = BlockHash(storagesInstance.storages.appStateStorage.getBestBlockInfo().hash)

    def indexed(number: Int): Option[BlockHash] = blockchainReader.getBlockHeaderByNumber(number).map(_.hash)

    /** Where the node looks `tx` up: what eth_getTransactionByHash follows. */
    def lookup(tx: SignedTransaction): Option[TransactionLocation] =
      storagesInstance.storages.transactionMappingStorage.get(tx.hash.value)

    def storedBlockhash(block: Block, slot: BigInt): ByteString =
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(block.number.value),
        (_: BigInt) => None,
        UInt256.Zero,
        block.header.stateRoot.value,
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      UInt256(world.getStorage(BlockhashRecorder).load(slot)).bytes

  /** One ephemeral database with the shared genesis state installed. Real VM, real BlockExecution, real ConsensusImpl.
    * On `datadir` when given; with `restart`, it is a node started again on it, and the reconciliation at start runs
    * before the genesis loader, as the node runs them (`StdNode`).
    */
  abstract class ChainHost(
      guard: Boolean = false,
      cap: StateConfig => StateConfig = identity,
      datadir: Option[EphemDataSource] = None,
      restart: Boolean = false
  ) extends EphemBlockchainTestSetup:
    implicit val runtime: IORuntime = IORuntime.global

    // first in the linearisation: the storages read the source as they are built
    private trait OnTheDatadir extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = datadir.getOrElse(EphemDataSource())

    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new OnTheDatadir
        with LocalPruningConfigBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard
        override protected def stateConfig: StateConfig =
          cap(StateConfig(com.chipprbots.ethereum.utils.Config.config))

    val reconciled: Option[StateReconciliation.Outcome] =
      Option.when(restart)(
        StateReconciliation.run(
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.pathStateStorage.get,
          blockchainReader,
          blockchainWriter,
          testMode = false
        )
      )

    // one genesis for every host, loaded as the node loads one: as rows on the unified layout
    val genesis: Block =
      GenesisDataLoader
        .over(blockchainReader, blockchainWriter, storagesInstance.storages)
        .loadGenesisData(
          GenesisData(
            nonce = ByteString(new Array[Byte](8)),
            mixHash = None,
            difficulty = "0x3e8",
            extraData = ByteString.empty,
            gasLimit = "0xf4240",
            coinbase = ByteString(new Array[Byte](20)),
            timestamp = "0xf4240",
            alloc = Map(
              Hex.toHexString(SenderAddress.bytes.toArray) ->
                GenesisAccount(None, UInt256(BigInt(10).pow(20)), None, None, None),
              Hex.toHexString(BlockhashRecorder.bytes.toArray) ->
                GenesisAccount(None, UInt256.Zero, Some(BlockhashRecorderCode), None, None)
            )
          )
        )
        .get
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, 0).get

object ReorgBlockhashParitySpec extends SecureRandomBuilder:

  /** A reader over the same stores that records which blocks were read whole, and whose receipts were read. */
  class CountingReader(s: BlockchainStorages)
      extends BlockchainReader(
        s.blockHeadersStorage,
        s.blockBodiesStorage,
        s.blockNumberMappingStorage,
        s.stateStorage,
        s.receiptStorage,
        s.appStateStorage,
        s.chainWeightStorage,
        s.blockAccessListStorage,
        s.pathStateStorage
      ):
    private val blocks = new java.util.concurrent.ConcurrentLinkedQueue[BlockHash]
    private val receipts = new java.util.concurrent.ConcurrentLinkedQueue[BlockHash]
    def reset(): Unit =
      blocks.clear()
      receipts.clear()
    def blocksRead: List[BlockHash] = blocks.asScala.toList
    def receiptsRead: List[BlockHash] = receipts.asScala.toList
    override def getBlockByHash(hash: BlockHash): Option[Block] =
      blocks.add(hash)
      super.getBlockByHash(hash)
    override def getReceiptsByHash(blockhash: BlockHash): Option[Seq[Receipt]] =
      receipts.add(blockhash)
      super.getReceiptsByHash(blockhash)

  /** A reader over the same stores that hands every hash a block is read whole by to `observe` first (B-343). */
  class ObservingReader(s: BlockchainStorages, observe: BlockHash => Unit)
      extends BlockchainReader(
        s.blockHeadersStorage,
        s.blockBodiesStorage,
        s.blockNumberMappingStorage,
        s.stateStorage,
        s.receiptStorage,
        s.appStateStorage,
        s.chainWeightStorage,
        s.blockAccessListStorage,
        s.pathStateStorage
      ):
    override def getBlockByHash(hash: BlockHash): Option[Block] =
      observe(hash)
      super.getBlockByHash(hash)

  /** A chunk no side chain here reaches: the side blocks executed again in one run (B-343). */
  val Unchunked: Int = Int.MaxValue

  /** An answer, or what was thrown, as text that two runs on two nodes of the same blocks can be compared by. */
  def told(result: Either[Throwable, ConsensusResult]): String = result match
    case Left(thrown)  => s"thrown ${thrown.getClass.getName}: ${thrown.getMessage}"
    case Right(answer) => answer.toString

  /** A cap on the pinned layers that a branch of these blocks meets after its first blocks. */
  val MidCap: Long = 3500L

  def pinnedCap(bytes: Long)(config: StateConfig): StateConfig =
    config.copy(overlay = config.overlay.copy(pinnedMaxBytes = bytes))

  /** Moves of the head written three blocks to a batch. */
  def movesOfThree(config: StateConfig): StateConfig = config.copy(maxBlocksPerMoveBatch = 3)

  /** An in-memory datadir that a kill stops: once armed, the writes it allows are made, and every write after them
    * fails, as none is made by a process that is gone. It counts the writes made since it was armed, and which of them
    * moved the anchor.
    */
  final class Killable extends EphemDataSource(Map.empty):
    @volatile private var allowed: Option[Int] = None
    private var made = 0
    private var moves = Vector.empty[Int]
    def arm(after: Int): Unit = synchronized { made = 0; moves = Vector.empty; allowed = Some(after) }
    def disarm(): Unit = synchronized { allowed = None }
    def writes: Int = synchronized(made)
    def anchorMoves: Seq[Int] = synchronized(moves)
    private def alive(): Unit = if allowed.exists(made >= _) then throw new java.io.IOException("the node was killed")
    override def update(updates: Seq[DataUpdate]): Unit = synchronized {
      alive()
      super.update(updates)
      made += 1
      if updates.exists(movesTheAnchor) then moves :+= made
    }
    override def deleteRange(namespace: IndexedSeq[Byte], fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      synchronized {
        alive()
        super.deleteRange(namespace, fromKey, toKeyExclusive)
        made += 1
      }

  /** An in-memory datadir that fails a write on demand, in a node that runs on (B-292): the write that would move the
    * anchor to a block, the how-many-th time it is asked for, fails with the fault given and writes nothing, as a
    * synced batch that did not land; every other write is made. It records, in order, every block a write it made moved
    * the anchor to.
    */
  final class Faulty extends EphemDataSource(Map.empty):
    final private case class Fault(at: ByteString, var skip: Int, fault: () => Throwable)
    private var faults = Vector.empty[Fault]
    private var moved = Vector.empty[ByteString]

    /** The `after + 1`-th write from now that moves the anchor to `block` fails with `fault`. */
    def failAt(block: Block, fault: => Throwable, after: Int = 0): Unit = synchronized {
      faults :+= Fault(block.hash.value, after, () => fault)
    }

    /** The faults asked for that no write has met yet. */
    def unmet: Int = synchronized(faults.size)

    /** The blocks the writes made moved the anchor to, in order. */
    def anchored: Vector[ByteString] = synchronized(moved)

    override def update(updates: Seq[DataUpdate]): Unit = synchronized {
      val to = updates.flatMap(anchorOf).lastOption
      to.foreach { hash =>
        faults.indexWhere(_.at == hash) match
          case -1 => ()
          case i if faults(i).skip > 0 =>
            faults(i).skip -= 1
          case i =>
            val failing = faults(i)
            faults = faults.patch(i, Nil, 1)
            throw failing.fault()
      }
      super.update(updates)
      to.foreach(hash => moved :+= hash)
    }

  private val AnchorKey: IndexedSeq[Byte] = AppStateStorage.Keys.StateAnchor.getBytes.toIndexedSeq

  private def movesTheAnchor(update: DataUpdate): Boolean = anchorOf(update).isDefined

  /** The block a write moves the anchor to, by its hash. */
  private def anchorOf(update: DataUpdate): Option[ByteString] =
    val value: Option[Array[Byte]] = update match
      case DataSourceUpdate(namespace, _, upserts) if namespace == Namespaces.AppStateNamespace =>
        upserts.collectFirst { case (key, value) if key == AnchorKey => value.toArray }
      case DataSourceUpdateOptimized(namespace, _, upserts) if namespace == Namespaces.AppStateNamespace =>
        upserts.collectFirst { case (key, value) if key.toIndexedSeq == AnchorKey => value }
      case _ => None
    value.map(bytes => StateAnchor.decode(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).hash)

  val OtherMiner: Address = Address(0x6f746872)

  /** An archive whose views reach two blocks behind the head, and whose moves are written three blocks to a batch. */
  def archiveOfShortViews(config: StateConfig): StateConfig =
    config.copy(
      pruning = config.pruning.copy(mode = StateConfig.Mode.Archive),
      archiveViewReach = 2,
      maxBlocksPerMoveBatch = 3
    )

  val SenderKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
  val SenderAddress: Address = Address(SenderKeyPair)
  val Miner: Address = Address(0x6d696e)

  /** A value transfer from the funded sender (B-088): `nonce` and `value` make it the transaction it is. */
  def transfer(nonce: BigInt, value: BigInt): SignedTransaction =
    SignedTransaction.sign(
      LegacyTransaction(
        nonce = nonce,
        gasPrice = GasPrice(1),
        gasLimit = GasAmount(21000),
        receivingAddress = Address(0x7265637674L),
        value = value,
        payload = ByteString.empty
      ),
      SenderKeyPair,
      None
    )

  /** In the old chain and the new; in the old chain only; in the new chain only. */
  val Shared: SignedTransaction = transfer(nonce = 0, value = 1)
  val OnlyA: SignedTransaction = transfer(nonce = 1, value = 2)
  val OnlyB: SignedTransaction = transfer(nonce = 1, value = 3)

  /** `PUSH1 0; CALLDATALOAD; BLOCKHASH; NUMBER; SSTORE; STOP` — storage[NUMBER] := BLOCKHASH(calldata[0..32]). */
  val BlockhashRecorder: Address = Address(0xb10c4a54L)
  val BlockhashRecorderCode: ByteString = ByteString(Hex.decode("600035404355" + "00"))
