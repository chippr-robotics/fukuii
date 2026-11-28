package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporterRewindAccess
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.consensus.blocks.BlockTimestampProvider
import com.chipprbots.ethereum.consensus.blocks.TestBlockGenerator
import com.chipprbots.ethereum.consensus.mess.MESSConfig
import com.chipprbots.ethereum.consensus.pow.blocks.OmmersSeqEnc
import com.chipprbots.ethereum.consensus.pow.miners.SettledHeadBuild
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.ledger.NewBetterBranch
import com.chipprbots.ethereum.ledger.NoChainSwitch
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C6, T237 (forge): the ETC proof-of-work reorganisation matrix, in process, on the unified state
  * layout and on the hash layout side by side.
  *
  * Every case is played on two nodes, one on each layout, offered the same blocks in the same order. The blocks are
  * sealed by an oracle on the hash store that executes each one (so a block's state root, gas used and rewards are what
  * execution gives) and stores it. Each block carries a call that writes `BLOCKHASH(parent)` into a storage slot, so
  * every branch has storage of its own, and some carry ommers, paid by the import. Each case then holds:
  *
  *   - the two nodes answer alike, end on the same head, with the same index of blocks by number, and the same balances
  *     at the head;
  *   - on the unified layout, the anchor is the head and the disk is the head's state row for row (the oracle's trie at
  *     that root, walked into positions), and no changeset record of any block is a tombstone.
  *
  * The live legs of T237 (Mordor, the private ETC-config net) are run on hosts, not here.
  */
class EtcPowReorgMatrixSpec extends AnyFlatSpec with Matchers:
  import EtcPowReorgMatrixSpec.*

  "A heavier fork of depth 1" should "be adopted on both layouts, to the same state" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val s10: List[Block] = oracle.side(h(8), 1, difficulty = 2000, tag = 0x11)
    importAll(s10) shouldBe BothReorganised
    headIs(s10.last, canonical = h.take(9) ++ s10)
    val next: List[Block] = oracle.side(s10.last, 1, difficulty = 1000, tag = 0x12)
    importOne(next.head) shouldBe BothImported
    headIs(next.head, canonical = h.take(9) ++ s10 ++ next)

  "A heavier fork of depth 2" should "be adopted on both layouts, to the same state" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    // s9 s10 against h9 h10: 2100 > 2000
    val s: List[Block] = oracle.side(h(7), 2, difficulty = 1050, tag = 0x21)
    importAll(s) shouldBe BothReorganised
    headIs(s.last, canonical = h.take(8) ++ s)
    // and back: h11 on h10 makes the old chain the heavier again (3000 > 2100), by one block announced alone
    val h11: List[Block] = oracle.side(h.last, 1, difficulty = 1000, tag = 0x22)
    importOne(h11.head) shouldBe BothReorganised
    headIs(h11.head, canonical = h ++ h11)

  // `history`: how far below the head the unified layout keeps changeset records (no finality on proof of work:
  // `retention.without-finality`, here 6). A fork at that depth is adopted; one block deeper is refused there, retryably,
  // and is the escalation's (C5a); the hash layout of an archive takes it.
  "A heavier fork of depth `history`" should "be adopted on both layouts, and one block deeper refused retryably on the unified layout" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix(History):
    val h: List[Block] = oracle.extend(oracle.genesis, 20, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val atHistory: List[Block] = oracle.side(h(13), HistoryBlocks + 1, difficulty = 1000, tag = 0x31)
    atHistory.head.number.value shouldBe 20 - HistoryBlocks + 1
    importAll(atHistory) shouldBe BothReorganised
    headIs(atHistory.last, canonical = h.take(14) ++ atHistory)

    // one block deeper than the records reach, from the new head (block 21): the fork after block 14 is 7 below it
    val deeper: List[Block] = oracle.side(h(13), HistoryBlocks + 2, difficulty = 1000, tag = 0x32)
    val (onHashes, onPositions) = both(_.importAll(deeper))
    onHashes shouldBe a[ChainReorganised]
    onPositions shouldBe a[BlockImportStateFault]
    unified.bestHash shouldBe atHistory.last.hash
    unifiedHolds(atHistory.last)

  // REORG-PARTIAL: the branch as a whole outweighs the head, its executed prefix does not, and its last block is not
  // valid. The old head stays; the prefix is executed, kept beside it, and queued again; the invalid block is not. A
  // valid block on the prefix that makes it the heavier is then adopted, by itself, from the queue.
  "A heavier branch whose last block is invalid (REORG-PARTIAL)" should "keep the old head, queue the executed side blocks again, and adopt them once a valid block makes them the heavier" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 15, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val prefix: List[Block] = oracle.side(h(4), 7, difficulty = 1000, tag = 0x41)
    val invalid: Block = oracle.invalidChild(prefix.last, difficulty = 5000)
    val (onHashes, onPositions) = both(_.importAll(prefix :+ invalid))
    onHashes shouldBe a[BlockImportFailed]
    onPositions shouldBe a[BlockImportFailed]
    headIs(h.last, canonical = h)
    nodes.foreach { node =>
      withClue(s"${node.layout}: ") {
        prefix.foreach(block => node.blockQueue.isQueued(block.hash) shouldBe true)
        node.blockQueue.isQueued(invalid.hash) shouldBe false
      }
    }
    val valid: List[Block] = oracle.side(prefix.last, 1, difficulty = 5000, tag = 0x42)
    importOne(valid.head) shouldBe BothReorganised
    headIs(valid.head, canonical = h.take(5) ++ prefix ++ valid)

  // Ommers that name blocks of the chain the head has just left: paid by the import, on both layouts alike.
  "A heavier branch whose blocks carry ommers of the abandoned branch" should "be adopted, its ommers paid alike on both layouts" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val s9: List[Block] = oracle.side(h(7), 1, difficulty = 1000, tag = 0x51)
    val s10: List[Block] = oracle.side(s9.last, 1, difficulty = 1000, tag = 0x52, ommers = Seq(h(8).header))
    val s11: List[Block] = oracle.side(s10.last, 1, difficulty = 1000, tag = 0x53, ommers = Seq(h(9).header))
    val branch: List[Block] = s9 ++ s10 ++ s11
    importAll(branch) shouldBe BothReorganised
    headIs(s11.last, canonical = h.take(8) ++ branch)
    // the miners of the abandoned blocks are paid as ommers: h9's and h10's
    Seq(h(8), h(9)).foreach(o => paidAlike(s11.last, Address(o.header.beneficiary)))

  // SYNC-FORK: on the hash layout the fork recovery rewinds the index to the common block and the branch is imported on
  // it; on the unified layout nothing is rewound (the writer refuses), and the branch, fetched from the common block, is
  // weighed and adopted by the reorganisation. Both end on the branch, with its state.
  "The SYNC-FORK rewind" should "be refused on the unified layout, where the fetched branch is adopted by the reorganisation, to the hash layout's state" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 6, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val lca: Block = h(2)
    BlockImporterRewindAccess.rewind(
      hashes.blockchainReader,
      hashes.blockchainWriter,
      hashes.blockchain,
      lca.number.value,
      lca.hash,
      h.last.number.value
    )
    hashes.blockchainReader.getBestBlockNumber shouldBe lca.number.value
    an[IllegalStateException] should be thrownBy BlockImporterRewindAccess.rewind(
      unified.blockchainReader,
      unified.blockchainWriter,
      unified.blockchain,
      lca.number.value,
      lca.hash,
      h.last.number.value
    )
    unified.bestHash shouldBe h.last.hash
    unifiedHolds(h.last)

    val branch: List[Block] = oracle.side(lca, 4, difficulty = 1000, tag = 0x61)
    val (onHashes, onPositions) = both(_.importAll(branch))
    onHashes shouldBe a[BlockImportedToTop]
    onPositions shouldBe a[ChainReorganised]
    headIs(branch.last, canonical = h.take(3) ++ branch)

  "An import batch of many blocks" should "adopt each in turn, with a record of each and no tombstone" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 40, difficulty = 1000)
    importAll(h.take(1)) shouldBe BothImported
    val (onHashes, onPositions) = both(_.importAll(h.drop(1)))
    Seq(onHashes, onPositions).foreach {
      case BlockImportedToTop(imported) => imported.map(_.block.hash) shouldBe h.drop(1).map(_.hash)
      case other                        => fail(s"not imported: $other")
    }
    headIs(h.last, canonical = h)
    h.foreach(block =>
      unifiedRecords.get(block.hash.value).map(_.map(_.number)) shouldBe Some(Right(block.number.value))
    )

  // MinedBlockImport (BlockImporter's `MinedBlock` arm) imports a block this node sealed through
  // `ConsensusAdapter.evaluateBranchBlock`, as here. With ommers: the block the generator prepares is the same on both
  // layouts, and its import computes the root it carries.
  "A mined block with ommers" should "be prepared alike on both layouts and imported to the root it carries" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 6, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val ommers: Seq[BlockHeader] =
      Seq(oracle.side(h(4), 1, 1000, 0x71).head.header, oracle.side(h(3), 1, 1000, 0x72).head.header)
    val mined: Seq[Block] = nodes.map(node => node.mine(node.head, ommers))
    mined.map(_.hash).distinct should have size 1
    mined.head.body.uncleNodesList shouldBe ommers
    hashes.importOne(mined.head) shouldBe a[BlockImportedToTop]
    unified.importOne(mined(1)) shouldBe a[BlockImportedToTop]
    headIs(mined.head, canonical = h :+ mined.head, oracleHolds = false)
    unifiedHoldsItsOwn(mined.head)
    ommers.foreach(o => paidAlike(mined.head, Address(o.beneficiary)))

  // A proposer run on a parent the head leaves during the build (`SettledHeadBuild`, B-293). On the unified layout the
  // build is not kept and is made again on the new head; on the hash layout the generation never moves and the block
  // is kept on the old parent: a side block like any other, judged as one. Both nodes are then offered both blocks: they
  // end on the same head, with its state.
  "A proposer run on a stale parent" should "build again on the new head on the unified layout, and leave both layouts on the same head" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 8, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val heavier: List[Block] = oracle.side(h(6), 2, difficulty = 1500, tag = 0x81)
    val built: Seq[(Block, Block)] = nodes.map { node =>
      var moved = false
      val build = new SettledHeadBuild(node.blockchainReader, waitMillis = 5000, maxBuilds = SettledHeadBuild.Builds)
      build { parent =>
        IO.blocking {
          if !moved then
            moved = true
            node.importAll(heavier) shouldBe a[ChainReorganised]
          node.mine(parent, Nil)
        }
      }.unsafeRunSync()(using IORuntime.global) match
        case Right((parent, block)) => (parent, block)
        case Left(why)              => fail(s"${node.layout}: nothing built: $why")
    }
    val (hashParent, onOldParent) = built.head
    val (unifiedParent, onNewHead) = built(1)
    hashParent.hash shouldBe h.last.hash
    onOldParent.header.parentHash shouldBe h.last.hash
    unifiedParent.hash shouldBe heavier.last.hash
    onNewHead.header.parentHash shouldBe heavier.last.hash
    // the block the unified layout built again is the one the hash layout builds on that head
    hashes.mine(heavier.last, Nil).hash shouldBe onNewHead.hash

    importOne(onNewHead) shouldBe BothImported
    // the stale block, on the old head, is the lighter branch now on both
    both(_.importOne(onOldParent)) shouldBe (BlockEnqueued, BlockEnqueued)
    headIs(onNewHead, canonical = h.take(7) ++ heavier :+ onNewHead, oracleHolds = false)

  // T240's MESS case: Olympia re-opens ECBP-1100 (`reactivationBlock`). On real storage the common block's header is
  // there, and the verdict is the rule's, on both layouts: a heavier branch 30 blocks deep that does not meet the
  // antigravity is refused; with MESS off it is taken.
  "A deep heavier branch with MESS re-opened by Olympia" should "be refused by MESS alike on both layouts, and taken with MESS off" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 40, difficulty = 1000)
    importAll(h) shouldBe BothImported
    // 30 blocks above block 10 against h11..h40 (30000): 29 at 1000 and one at 1600, 30600. Age 450 s: antigravity
    // 131/128, so it wants 30000 * 131 = 3,930,000 and gets 30600 * 128 = 3,916,800
    val first29: List[Block] = oracle.side(h(9), 29, 1000, 0x91)
    val side: List[Block] = first29 ++ oracle.side(first29.last, 1, 1600, 0x92)
    val headers: NonEmptyList[BlockHeader] = NonEmptyList.fromListUnsafe(side.map(_.header))
    nodes.foreach { node =>
      withClue(s"${node.layout}: ") {
        val resolution = new BranchResolution(node.blockchainReader)
        resolution.resolveBranch(headers) shouldBe NewBetterBranch(h.drop(10))
        resolution.messConfig = Some(MESSConfig(enabled = true, reactivationBlock = Some(BigInt(1))))
        resolution.resolveBranch(headers) shouldBe NoChainSwitch
        // a block more meets it: 31600 * 128 = 4,044,800
        val more = oracle.side(side.last, 1, 1000, 0x93)
        resolution.resolveBranch(NonEmptyList.fromListUnsafe((side ++ more).map(_.header))) shouldBe
          NewBetterBranch(h.drop(10))
      }
    }

  // B-284: core-geth's tie rule, `ForkChoice.ReorgNeeded` (core/forkchoice.go). On equal total difficulty the lower
  // block wins; at the same height the node's own block is kept or taken, and otherwise a coin decides. The strictly
  // heavier cases above run with a coin that fails them if it is tossed: the rule decides them without it.
  "A fork of the head's weight that ends lower" should "be adopted on both layouts, to the same state, and be the branch the headers are resolved to" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    // s9 on h8 at 2000: h9 h10's weight, one block lower
    val s9: List[Block] = oracle.side(h(7), 1, difficulty = 2000, tag = 0xa1)
    val headers: NonEmptyList[BlockHeader] = NonEmptyList.fromListUnsafe(s9.map(_.header))
    nodes.foreach { node =>
      withClue(s"${node.layout}: ") {
        new BranchResolution(node.blockchainReader, powForkChoice = node.powForkChoice).resolveBranch(headers) shouldBe
          NewBetterBranch(h.drop(8))
        // as before B-284: strictly heavier only
        new BranchResolution(node.blockchainReader, powForkChoice = PowForkChoice.HeavierOnly)
          .resolveBranch(headers) shouldBe NoChainSwitch
        // where a consensus layer is followed, unchanged: not weighed by the tie rule
        new BranchResolution(
          node.blockchainReader,
          Some(com.chipprbots.ethereum.consensus.engine.DesignatedHead(() => None)),
          powForkChoice = node.powForkChoice
        ).resolveBranch(headers) shouldBe NoChainSwitch
      }
    }
    importAll(s9) shouldBe BothReorganised
    headIs(s9.last, canonical = h.take(8) ++ s9)
    val next: List[Block] = oracle.side(s9.last, 1, difficulty = 1000, tag = 0xa2)
    importOne(next.head) shouldBe BothImported
    headIs(next.head, canonical = h.take(8) ++ s9 ++ next)

  "A branch not taken that grows to the head's weight and ends lower" should "be taken when it is weighed again, on both layouts" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    // s8 on h7 at 500: lighter, not taken; with s9 at 2500, h8 h9 h10's weight and one block lower
    val s8: List[Block] = oracle.side(h(6), 1, difficulty = 500, tag = 0xa9)
    val s9: List[Block] = oracle.side(s8.last, 1, difficulty = 2500, tag = 0xaa)
    nodes.foreach { node =>
      withClue(s"${node.layout}: ") {
        def resolution(choice: PowForkChoice) = new BranchResolution(node.blockchainReader, powForkChoice = choice)
        val notTaken = resolution(node.powForkChoice).notTaken(NonEmptyList.fromListUnsafe(s8.map(_.header))).get
        resolution(node.powForkChoice)
          .extended(notTaken, NonEmptyList.fromListUnsafe(s9.map(_.header)))
          .map(_._2) shouldBe
          Some(true)
        // as before B-284: strictly heavier only
        resolution(PowForkChoice.HeavierOnly)
          .extended(notTaken, NonEmptyList.fromListUnsafe(s9.map(_.header)))
          .map(_._2) shouldBe Some(false)
      }
    }

  "A fork of the head's weight that ends higher" should "be kept beside the head on both layouts, with no coin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    // s9 s10 s11 on h8: 500 + 500 + 1000, h9 h10's weight, one block higher
    val s: List[Block] = oracle.side(h(7), 2, difficulty = 500, tag = 0xa3)
    val s11: List[Block] = oracle.side(s.last, 1, difficulty = 1000, tag = 0xa4)
    both(_.importAll(s ++ s11)) shouldBe (BlockEnqueued, BlockEnqueued)
    headIs(h.last, canonical = h)

  "A fork of the head's weight and height" should "go the coin's way on both layouts: taken below one half, kept from one half" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    for (landing, taken) <- Seq(0.0 -> true, 0.4999 -> true, 0.5 -> false, 0.99 -> false) do
      withClue(s"the coin at $landing: ") {
        val coins = scala.collection.mutable.ArrayBuffer.empty[PowForkChoiceSpec.Coins]
        def tossingOnce(): PowForkChoice =
          val coin = new PowForkChoiceSpec.Coins(landing)
          coins += coin
          new PowForkChoice(_ => false, coin)
        new Matrix(forkChoice = () => tossingOnce()):
          val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
          importAll(h) shouldBe BothImported
          // s10 on h9 at 1000: h10's weight and height
          val s10: List[Block] = oracle.side(h(8), 1, difficulty = 1000, tag = 0xa5)
          if taken then
            importAll(s10) shouldBe BothReorganised
            headIs(s10.last, canonical = h.take(9) ++ s10)
          else
            both(_.importAll(s10)) shouldBe (BlockEnqueued, BlockEnqueued)
            headIs(h.last, canonical = h)
          // once on each node
          coins.map(_.tossed).toList shouldBe List(1, 1)
      }
  }

  "A fork of the head's weight and height" should "keep the head when it is this node's own, and take the other block when that is, with no coin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    // the canonical chain is sealed by `Miner`, a side block by `OtherMiner`
    def ownedBy(miner: Address): () => PowForkChoice =
      () => new PowForkChoice(header => Address(header.beneficiary) == miner, PowForkChoiceSpec.NoCoin)
    new Matrix(forkChoice = ownedBy(Miner)):
      val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
      importAll(h) shouldBe BothImported
      val s10: List[Block] = oracle.side(h(8), 1, difficulty = 1000, tag = 0xa6)
      both(_.importAll(s10)) shouldBe (BlockEnqueued, BlockEnqueued)
      headIs(h.last, canonical = h)
    new Matrix(forkChoice = ownedBy(OtherMiner)):
      val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
      importAll(h) shouldBe BothImported
      val s10: List[Block] = oracle.side(h(8), 1, difficulty = 1000, tag = 0xa7)
      importAll(s10) shouldBe BothReorganised
      headIs(s10.last, canonical = h.take(9) ++ s10)
  }

  "A fork of the head's weight that ends lower, on a chain that declares a terminal total difficulty" should "be kept beside the head on both layouts, as before B-284" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Matrix():
    val h: List[Block] = oracle.extend(oracle.genesis, 10, difficulty = 1000)
    importAll(h) shouldBe BothImported
    val s9: List[Block] = oracle.side(h(7), 1, difficulty = 2000, tag = 0xa8)
    // a terminal total difficulty not reached: the chain is not past its merge, and no tie is broken (the ETH family)
    val ethFamily: BlockchainConfig = hashes.blockchainConfig.copy(terminalTotalDifficulty = Some(BigInt(10).pow(30)))
    val results: Seq[BlockImportResult] = nodes.map { node =>
      node.offered ++= s9
      node.consensusAdapter
        .evaluateBranch(NonEmptyList.fromListUnsafe(s9))(using IORuntime.global, ethFamily)
        .unsafeRunSync()(using IORuntime.global)
    }
    results shouldBe Seq(BlockEnqueued, BlockEnqueued)
    headIs(h.last, canonical = h)

  /** One case of the matrix: an oracle, and a node on each layout.
    *
    * @param forkChoice
    *   each node's proof-of-work fork choice (B-284), one per node: by default no block is the node's own and the coin
    *   fails the case if a tie is ever left to it
    */
  class Matrix(
      tune: StateConfig => StateConfig = identity,
      forkChoice: () => PowForkChoice = () => new PowForkChoice(_ => false, PowForkChoiceSpec.NoCoin)
  ):
    val oracle = new Oracle
    val hashes = new Node("the hash layout", guard = false, tune, forkChoice())
    val unified = new Node("the unified layout", guard = true, tune, forkChoice())
    val nodes: Seq[Node] = Seq(hashes, unified)
    val unifiedRecords = new ChangesetStorage(unified.source)

    def both(f: Node => BlockImportResult): (BlockImportResult, BlockImportResult) = (f(hashes), f(unified))

    /** Both import `blocks` as one batch: the same kind of answer. */
    def importAll(blocks: List[Block]): Outcome = outcome(both(_.importAll(blocks)))
    def importOne(block: Block): Outcome = outcome(both(_.importOne(block)))

    private def outcome(results: (BlockImportResult, BlockImportResult)): Outcome = results match
      case (_: BlockImportedToTop, _: BlockImportedToTop) => BothImported
      case (_: ChainReorganised, _: ChainReorganised)     => BothReorganised
      case other                                          => Differ(other)

    /** Both nodes are on `head`, with `canonical` (above genesis) as their index and the same balances at the head; on
      * the unified layout the anchor is the head, the disk its state, and no record a tombstone.
      */
    def headIs(head: Block, canonical: List[Block], oracleHolds: Boolean = true): Unit =
      nodes.foreach { node =>
        withClue(s"${node.layout}: ") {
          node.bestHash shouldBe head.hash
          canonical.foreach(block => node.indexed(block.number.value) shouldBe Some(block.hash))
          node.indexed(head.number.value + 1) shouldBe None
        }
      }
      (Seq(Miner, OtherMiner, SenderAddress, BlockhashRecorder) ++ canonical.map(b =>
        Address(b.header.beneficiary)
      )).distinct
        .foreach(paidAlike(head, _))
      if oracleHolds then unifiedHolds(head) else unifiedHoldsItsOwn(head)
      unified.storagesInstance.storages.pathStateStorage.get.anchor shouldBe Some(refOf(head.header))
      tombstones shouldBe 0
      info(s"head ${head.number}: root ${head.header.stateRoot.toHexString} on both layouts, tombstones $tombstones")

    def paidAlike(head: Block, who: Address): Unit =
      withClue(s"$who at block ${head.number}: ") {
        unified.blockchainReader
          .accountAt(head.header, who) shouldBe hashes.blockchainReader.accountAt(head.header, who)
      }

    /** The unified layout's disk is `head`'s state, as the oracle holds it. */
    def unifiedHolds(head: Block): Unit =
      PathStateTestKit.allRows(new PathNodeStorage(unified.source)) shouldBe stateOf(oracle, head.header)

    /** The same, from the hash layout's node: for a block the oracle did not seal. */
    def unifiedHoldsItsOwn(head: Block): Unit =
      PathStateTestKit.allRows(new PathNodeStorage(unified.source)) shouldBe stateOf(hashes, head.header)

    /** The changeset records of the blocks offered to the unified layout that are tombstones. */
    def tombstones: Int =
      unified.offered.toSeq.flatMap(block => unifiedRecords.get(block.hash.value)).count {
        case Right(diff) => diff.incomplete
        case Left(why)   => fail(s"a record that does not decode: $why")
      }

object EtcPowReorgMatrixSpec:

  sealed trait Outcome
  case object BothImported extends Outcome
  case object BothReorganised extends Outcome
  final case class Differ(results: (BlockImportResult, BlockImportResult)) extends Outcome

  /** Records kept 6 blocks below the head, with no finality (proof of work), and a history of 4. */
  val HistoryBlocks: Int = 6
  def History(config: StateConfig): StateConfig =
    config.copy(
      pruning = config.pruning.copy(mode = StateConfig.Mode.Basic, history = 4),
      retention = config.retention.copy(withoutFinality = HistoryBlocks, maxBlocks = HistoryBlocks)
    )

  val SenderKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
  val SenderAddress: Address = Address(SenderKeyPair)
  val Miner: Address = Address(0x6d696e)
  val OtherMiner: Address = Address(0x6f746872)

  /** `PUSH1 0; CALLDATALOAD; BLOCKHASH; NUMBER; SSTORE; STOP`: storage[NUMBER] := BLOCKHASH(calldata[0..32]). */
  val BlockhashRecorder: Address = Address(0xb10c4a54L)
  val BlockhashRecorderCode: ByteString = ByteString(Hex.decode("600035404355" + "00"))

  def refOf(header: BlockHeader): BlockRef = BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  /** Every block has one transaction from the sender, so its nonce in a block is the parent's number. It records the
    * parent's hash: each branch has storage of its own.
    */
  def recordParent(parent: Block): SignedTransaction =
    SignedTransaction.sign(
      LegacyTransaction(
        nonce = parent.number.value,
        gasPrice = GasPrice(1),
        gasLimit = GasAmount(100000),
        receivingAddress = BlockhashRecorder,
        value = 0,
        payload = UInt256(parent.number.value).bytes
      ),
      SenderKeyPair,
      None
    )

  /** `header`'s state on `host`'s hash store, as rows of the position store. */
  def stateOf(host: Host, header: BlockHeader): (Map[ByteString, ByteString], Map[ByteString, ByteString]) =
    val nodes = host.storagesInstance.storages.nodeStorage
    val positions = RawTrieWalk.statePositions(
      header.stateRoot.value.toArray,
      hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
    )
    (
      positions.getOrElse(None, Map.empty),
      positions.collect { case (Some(owner), trie) => trie.map((path, node) => (owner ++ path) -> node) }.flatten.toMap
    )

  final private class Clock extends BlockTimestampProvider:
    @volatile var now: Long = 0L
    override def getEpochSecond: Long = now

  /** An in-memory node with the genesis every host shares, loaded as a node loads one. */
  abstract class Host(
      guard: Boolean,
      tune: StateConfig => StateConfig,
      val source: EphemDataSource = EphemDataSource()
  ) extends EphemBlockchainTestSetup:
    implicit val runtime: IORuntime = IORuntime.global

    private trait OnTheDatadir extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source

    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new OnTheDatadir
        with LocalPruningConfigBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard
        override protected def stateConfig: StateConfig =
          tune(StateConfig(com.chipprbots.ethereum.utils.Config.config))

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

  /** Seals blocks by executing them on its hash store. The first chain from genesis is indexed; every other block is
    * stored by its hash only.
    */
  final class Oracle extends Host(false, identity):
    def extend(parent: Block, count: Int, difficulty: BigInt): List[Block] =
      chain(parent, count, difficulty, 0x00, Miner, Nil)(block => blockchainWriter.storeBlock(block).commit())

    def side(
        parent: Block,
        count: Int,
        difficulty: BigInt,
        tag: Int,
        miner: Address = OtherMiner,
        ommers: Seq[BlockHeader] = Nil
    ): List[Block] =
      chain(parent, count, difficulty, tag, miner, ommers)(block =>
        blockchainWriter.storeBlockByHashOnly(block).commit()
      )

    /** A child of `parent` whose header commits to a state root no execution gives. */
    def invalidChild(parent: Block, difficulty: BigInt): Block =
      val valid = seal(parent, difficulty, 0x7f, OtherMiner, Nil)
      valid.copy(header = valid.header.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x11)))))

    private def chain(
        parent: Block,
        count: Int,
        difficulty: BigInt,
        tag: Int,
        miner: Address,
        ommers: Seq[BlockHeader]
    )(
        store: Block => Unit
    ): List[Block] =
      (1 to count).toList.foldLeft(List.empty[Block]) { (made, n) =>
        val block = seal(made.lastOption.getOrElse(parent), difficulty, tag, miner, if n == 1 then ommers else Nil)
        store(block)
        made :+ block
      }

    private def seal(parent: Block, difficulty: BigInt, tag: Int, miner: Address, ommers: Seq[BlockHeader]): Block =
      val template = parent.header.copy(
        parentHash = parent.hash,
        ommersHash = BlockHash(ByteString(kec256(ommers.toBytes: Array[Byte]))),
        number = parent.number + 1,
        difficulty = Difficulty(difficulty),
        unixTimestamp = Timestamp(parent.header.unixTimestamp.toLong + 15),
        extraData = ByteString(Array[Byte](tag.toByte)),
        beneficiary = miner.bytes,
        gasLimit = GasAmount(1000000),
        gasUsed = GasAmount.Zero
      )
      val block = Block(template, BlockBody(Seq(recordParent(parent)), ommers))
      blockExecution.executeBlockNoValidation(block) match
        case Right((_, gasUsed, stateRoot)) =>
          block.copy(header = template.copy(stateRoot = TrieRoot(stateRoot), gasUsed = GasAmount(gasUsed)))
        case Left(error) =>
          throw new IllegalStateException(s"the oracle could not seal block ${template.number}: $error")

  /** The node under test, on one layout. Real VM, real `BlockExecution`, `ConsensusImpl` and `ConsensusAdapter`. */
  final class Node(
      val layout: String,
      guard: Boolean,
      tune: StateConfig => StateConfig,
      forkChoice: PowForkChoice
  ) extends Host(guard, tune):
    private val clock = new Clock
    override lazy val powForkChoice: PowForkChoice = forkChoice
    private lazy val generator: TestBlockGenerator = mining.blockGenerator.withBlockTimestampProvider(clock)

    /** Every block offered to this node: the records of these are the ones looked at. */
    @volatile var offered: Set[Block] = Set.empty

    def importAll(blocks: List[Block]): BlockImportResult =
      offered ++= blocks
      consensusAdapter.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    /** As BlockImporter's `MinedBlock` arm imports a block this node sealed. */
    def importOne(block: Block): BlockImportResult =
      offered += block
      consensusAdapter.evaluateBranchBlock(block).unsafeRunSync()

    def head: Block = blockchainReader.getBestBlock.get
    def bestHash: BlockHash = BlockHash(storagesInstance.storages.appStateStorage.getBestBlockInfo().hash)
    def indexed(number: BigInt): Option[BlockHash] = blockchainReader.getBlockHeaderByNumber(number).map(_.hash)

    /** The block this node's generator prepares on `parent`, with the sender's next call and `ommers`. */
    def mine(parent: Block, ommers: Seq[BlockHeader]): Block =
      clock.now = parent.header.unixTimestamp.toLong + 15
      generator
        .generateBlock(parent, Seq(recordParent(parent)), Miner, ommers.asInstanceOf[generator.X], None)
        .pendingBlock
        .block
