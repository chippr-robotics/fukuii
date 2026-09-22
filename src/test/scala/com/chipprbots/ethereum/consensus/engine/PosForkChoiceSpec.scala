package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import scala.collection.mutable

import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.*
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.ledger.NewBetterBranch
import com.chipprbots.ethereum.ledger.NoChainSwitch
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Post-merge fork choice on the p2p import path.
  *
  * WHAT THIS PINS, AND WHY. `ChainWeight.increase` adds `header.difficulty`, and every post-merge header carries
  * difficulty 0. So on a PoS chain every branch rooted at the same parent has IDENTICAL weight, and the two places that
  * decide a fork by comparing weights could never once accept a competing branch:
  *
  *   - `BranchResolution.compareBranch`: `newWeight > oldWeight` is false; the equal-weight escape hatch next to it
  *     requires `oldBlocks.isEmpty`, which is exactly the NON-reorg case. A real competing branch fell through to
  *     `NoChainSwitch`.
  *   - `ConsensusImpl.importToNewBranch`: `newBranchWeight(branch, parentWeight) > currentBestBlockWeight` is false for
  *     the same reason, so `KeptCurrentBestBranch`.
  *
  * A branch has to pass BOTH to be executed, so a CL-designated branch arriving over p2p was fetched and then dropped
  * without ever running — and the invalid block planted on it was never found, and the consensus layer was never told.
  * Measured on hive `engine` 6c8bc97: 24 `Invalid Missing Ancestor Syncing ReOrg … CanonicalReOrg=True` failures plus 4
  * `Withdrawals … Re-Org Sync` timeouts.
  *
  * The replacement rule is CL-driven: follow the branch the consensus layer designated, because on PoS the execution
  * layer does not choose. Roughly half the assertions below are NEGATIVE and pin the cases where we must still refuse —
  * no consensus layer (the ETC configuration), an unbound channel, and a consensus layer that designated some OTHER
  * branch. Those are the safety property: p2p gossip must never move a PoS node's head on its own.
  */
// scalastyle:off magic.number
class PosForkChoiceSpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience with MockFactory:

  // ---------------------------------------------------------------------------------------------------------------
  // 1. The shared predicate. Both weight sites delegate to this, so it is asserted once, here.
  // ---------------------------------------------------------------------------------------------------------------

  "leadsToDesignatedHead" should "accept the branch tip itself" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      val chain: List[Block] = storeChain(3)
      DesignatedHead.leadsToDesignatedHead(
        headAt(chain.last),
        blockchainReader,
        chain.last.hash.value
      ) shouldBe true

  it should "accept a tip the designated head descends from, one hop up" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      // The hive shape exactly: the CL hands us a tip whose parent is the last block we fetched.
      val chain: List[Block] = storeChain(3)
      DesignatedHead.leadsToDesignatedHead(
        headAt(chain.last),
        blockchainReader,
        chain(1).hash.value
      ) shouldBe true

  it should "accept a tip several hops below the designated head" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      val chain: List[Block] = storeChain(6)
      DesignatedHead.leadsToDesignatedHead(
        headAt(chain.last),
        blockchainReader,
        chain.head.hash.value
      ) shouldBe true

  it should "REFUSE when there is no consensus layer — the ETC/Mordor/Gorgoroth configuration" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new HeaderStore:
    val chain: List[Block] = storeChain(3)
    DesignatedHead.leadsToDesignatedHead(None, blockchainReader, chain.last.hash.value) shouldBe false

  it should "REFUSE when the channel exists but nothing bound it" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      val chain: List[Block] = storeChain(3)
      val unbound = new DesignatedHead.LateBound
      unbound.isBound shouldBe false
      DesignatedHead.leadsToDesignatedHead(Some(unbound), blockchainReader, chain.last.hash.value) shouldBe false

  it should "REFUSE when the consensus layer has not spoken yet" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      val chain: List[Block] = storeChain(3)
      val silent = DesignatedHead(() => None)
      DesignatedHead.leadsToDesignatedHead(Some(silent), blockchainReader, chain.last.hash.value) shouldBe false

  it should "REFUSE a tip the designated head does not descend from" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      // THE safety case: the CL named a head on some other branch, so this branch is not ours to follow.
      val chain: List[Block] = storeChain(3)
      val other: List[Block] = storeChain(3)
      DesignatedHead.leadsToDesignatedHead(
        headAt(chain.last),
        blockchainReader,
        other.last.hash.value
      ) shouldBe false

  it should "REFUSE when the ancestry walk hits a header we do not hold" taggedAs (UnitTest, ConsensusTest) in
    new HeaderStore:
      // Chain generated but NOT stored except for the head: the walk cannot link it to the tip, so we do not guess.
      val chain: List[Block] = BlockHelpers.generateChain(4, BlockHelpers.genesis)
      blockchainWriter.storeBlockHeader(chain.last.header).commit()
      DesignatedHead.leadsToDesignatedHead(
        headAt(chain.last),
        blockchainReader,
        chain.head.hash.value
      ) shouldBe false

  it should "REFUSE rather than walk forever when headers never run out" taggedAs (UnitTest, ConsensusTest) in {
    // Pins MaxAncestryWalk. A reader that answers with a header for ANY hash is an infinite virtual ancestry; the
    // only thing that can terminate the walk is the cap, and the only safe answer on hitting it is false.
    val neverEnding = stub[BlockchainReader]
    (neverEnding
      .getBlockHeaderByHash(_: BlockHash))
      .when(*)
      .returns(Some(BlockHelpers.genesis.header.copy(parentHash = BlockHash(BlockHelpers.randomHash()))))
    val head = DesignatedHead(() => Some(BlockHelpers.randomHash()))
    DesignatedHead.leadsToDesignatedHead(Some(head), neverEnding, BlockHelpers.randomHash()) shouldBe false
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 2. BranchResolution — gate one of two. This is also where "does step 1 alone fix anything" is answered: the
  //    ChainWeight write makes the lookup succeed, and the verdict is still NoChainSwitch.
  // ---------------------------------------------------------------------------------------------------------------

  "BranchResolution on a post-merge chain" should
    "still refuse a competing branch when only the ChainWeight write is in place" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new PosChainSetup:
      // Every block here HAS a stored weight — this fixture is the world after the EngineApiService ChainWeight
      // write, so `compareBranch` gets past the lookup that used to abort it with
      // "ChainWeight for N: … not found when resolving branch". It then computes newWeight == oldWeight (difficulty
      // is 0 everywhere), finds oldBlocks non-empty, and refuses. Weights alone flip nothing.
      val resolution = new BranchResolution(blockchainReader, designatedHead = None)
      resolution.resolveBranch(NonEmptyList.fromListUnsafe(sideBranch.map(_.header))) shouldBe NoChainSwitch

  it should "accept a competing branch the consensus layer designated" taggedAs (UnitTest, ConsensusTest) in
    new PosChainSetup:
      val resolution = new BranchResolution(blockchainReader, Some(clDesignatedSideTip))
      resolution.resolveBranch(NonEmptyList.fromListUnsafe(sideBranch.map(_.header))) match
        case NewBetterBranch(oldBranch) =>
          // The old canonical suffix from the fork point up, which the caller re-queues as pending txs / ommers.
          oldBranch.map(_.number) shouldBe canonicalSuffix.map(_.number)
        case other => fail(s"expected NewBetterBranch, got $other")

  it should "REFUSE a competing branch when the consensus layer designated the canonical head" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new PosChainSetup:
    // The safety property, at the gate that matters. A peer offering us a side branch must not move us while the CL
    // is still pointing at our own chain.
    val resolution = new BranchResolution(blockchainReader, Some(DesignatedHead(() => Some(canonicalTip.hash.value))))
    resolution.resolveBranch(NonEmptyList.fromListUnsafe(sideBranch.map(_.header))) shouldBe NoChainSwitch

  // ---------------------------------------------------------------------------------------------------------------
  // 3. ConsensusImpl.importToNewBranch — gate two of two.
  // ---------------------------------------------------------------------------------------------------------------

  "ConsensusImpl on a post-merge chain" should "keep the current branch with no consensus layer" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new PosChainSetup:
    // The ETC/Mordor/Gorgoroth configuration, and also the pre-change behaviour: equal weight, so no reorg.
    val posConsensus = consensusWith(designatedHead = None)
    whenReady(posConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(sideBranch)).unsafeToFuture()) { result =>
      result shouldBe KeptCurrentBestBranch
    }
    executedBlocks shouldBe empty

  it should "reorganise onto a branch the consensus layer designated" taggedAs (UnitTest, ConsensusTest) in
    new PosChainSetup:
      val posConsensus = consensusWith(Some(clDesignatedSideTip))
      whenReady(posConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(sideBranch)).unsafeToFuture()) { result =>
        result shouldBe a[SelectedNewBestBranch]
      }
      // And it actually EXECUTED the branch, which is the whole point — an unexecuted branch can never be judged.
      executedBlocks.toList.map(_.number) shouldBe sideBranch.map(_.number)

  it should "REFUSE to reorganise onto a branch the consensus layer did not designate" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new PosChainSetup:
    val posConsensus = consensusWith(Some(DesignatedHead(() => Some(canonicalTip.hash.value))))
    whenReady(posConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(sideBranch)).unsafeToFuture()) { result =>
      result shouldBe KeptCurrentBestBranch
    }
    executedBlocks shouldBe empty

  it should "find and report the invalid block on a designated branch" taggedAs (UnitTest, ConsensusTest) in
    new PosChainSetup:
      // The end of the hive CanonicalReOrg=True story. Once the branch is allowed to execute, the existing
      // invalid-chain channel does the rest: the failing block and everything queued behind it are reported, with
      // the failing block's parent as latestValidHash.
      failAt(sideBranch(1))
      val posConsensus = consensusWith(Some(clDesignatedSideTip))
      whenReady(posConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(sideBranch)).unsafeToFuture())(_ => ())

      val lvh: ByteString = sideBranch(0).hash.value
      reported.toList shouldBe sideBranch.drop(1).map(_.hash.value -> lvh)

  // ---------------------------------------------------------------------------------------------------------------
  // 4. The head SOURCE, driven through the real ForkChoiceManager rather than injected.
  //
  //    Sections 2 and 3 hand the gates a DesignatedHead that already names the side head. Production never did: both
  //    bindings read ForkChoiceManager.getHeadBlockHash, which only moves on EXECUTED heads, while the FCU naming an
  //    ACCEPTED side head goes through notifyBeaconHead and leaves it on the old canonical tip. Measured on hive
  //    `engine` 4854b7d20: 0 of 28 targets cleared, branch fetched (`headers=14 … range=[1-14]`) and silently dropped.
  //    These cases reproduce that sequence exactly: canonical head applied, side head ACCEPTED by hash only, FCU to it
  //    notify-only.
  // ---------------------------------------------------------------------------------------------------------------

  "The CL's designated head, routed through ForkChoiceManager" should
    "follow a side head the CL named notify-only, while the executed head stays put" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new NotifyOnlySideHead:
      // Executed-only invariant: notifyBeaconHead must not move the executed head.
      fcm.getHeadBlockHash shouldBe Some(canonicalTip.hash.value)
      fcm.getRequestedHeadBlockHash shouldBe Some(acceptedSideHead.hash.value)

      // The binding the fix installs: walks acceptedSideHead -> sideBranch.last and meets the tip.
      DesignatedHead.leadsToDesignatedHead(
        Some(DesignatedHead(() => fcm.getRequestedHeadBlockHash)),
        blockchainReader,
        sideBranch.last.hash.value
      ) shouldBe true

      // The binding on 4854b7d20. Pinned as FALSE because that is the measured defect: the walk starts from the old
      // canonical tip and can never meet a side-branch tip. If this ever flips, the executed-only invariant broke.
      DesignatedHead.leadsToDesignatedHead(
        Some(DesignatedHead(() => fcm.getHeadBlockHash)),
        blockchainReader,
        sideBranch.last.hash.value
      ) shouldBe false

  it should "let BranchResolution accept the side branch the CL named notify-only" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new NotifyOnlySideHead:
    val resolution = new BranchResolution(blockchainReader, Some(DesignatedHead(() => fcm.getRequestedHeadBlockHash)))
    resolution.resolveBranch(NonEmptyList.fromListUnsafe(sideBranch.map(_.header))) match
      case NewBetterBranch(oldBranch) => oldBranch.map(_.number) shouldBe canonicalSuffix.map(_.number)
      case other                      => fail(s"expected NewBetterBranch, got $other")

  it should "let ConsensusImpl execute the side branch and report the invalid block on it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new NotifyOnlySideHead:
    failAt(sideBranch(1))
    val posConsensus = consensusWith(Some(DesignatedHead(() => fcm.getRequestedHeadBlockHash)))
    whenReady(posConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(sideBranch)).unsafeToFuture())(_ => ())

    executedBlocks.toList.map(_.number) shouldBe List(sideBranch.head.number)
    val lvh: ByteString = sideBranch(0).hash.value
    reported.toList shouldBe sideBranch.drop(1).map(_.hash.value -> lvh)

  it should "still REFUSE the side branch once the CL names the canonical head again" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new NotifyOnlySideHead:
    // The requested head follows the CL in BOTH directions: a later executed FCU back to our own chain must restore
    // the refusal, or a stale side-head request would keep p2p gossip able to move us.
    fcm.applyForkChoiceState(ForkChoiceState(canonicalTip.hash.value, zero32, zero32)) shouldBe Right(())
    val resolution = new BranchResolution(blockchainReader, Some(DesignatedHead(() => fcm.getRequestedHeadBlockHash)))
    resolution.resolveBranch(NonEmptyList.fromListUnsafe(sideBranch.map(_.header))) shouldBe NoChainSwitch

  // ---------------------------------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------------------------------

  /** hive's sequence up to the moment the peer delivers the side branch: our canonical tip was applied by an executed
    * FCU; the CL then sent `engine_newPayload` for a side head whose parent we lack (stored by hash only, exactly what
    * `EngineApiService` does before answering ACCEPTED), and an FCU to it that took the notify-only SYNCING path.
    */
  class NotifyOnlySideHead extends PosChainSetup:
    val zero32: ByteString = ByteString(new Array[Byte](32))
    val fcm = new ForkChoiceManager(blockchainReader, blockchainWriter)
    fcm.applyForkChoiceState(ForkChoiceState(canonicalTip.hash.value, zero32, zero32)) shouldBe Right(())

    val acceptedSideHead: Block = BlockHelpers.generateChain(1, sideBranch.last, posBlock).head
    blockchainWriter.storeBlockByHashOnly(acceptedSideHead).commit()
    fcm.notifyBeaconHead(ForkChoiceState(acceptedSideHead.hash.value, zero32, zero32))

  /** Just a place to put headers so the ancestry walk has something to walk. */
  class HeaderStore extends EphemBlockchainTestSetup:
    def storeChain(n: Int): List[Block] =
      val chain = BlockHelpers.generateChain(n, BlockHelpers.genesis, posBlock)
      chain.foreach(b => blockchainWriter.storeBlockHeader(b.header).commit())
      chain

    def headAt(block: Block): Option[DesignatedHead] = Some(DesignatedHead(() => Some(block.hash.value)))

  /** A post-merge chain that has already forked: canonical 1..6, and a side branch 4..6 off canonical block 3.
    *
    * Equal height and — because every header carries `difficulty = 0` — equal weight, which is the situation no weight
    * comparison can resolve. Weights ARE stored for every block, so this fixture is the world AFTER the
    * EngineApiService ChainWeight write; anything still refused here is refused for fork-choice reasons, not for want
    * of a weight.
    */
  class PosChainSetup extends EphemBlockchainTestSetup:
    implicit val runtime: IORuntime = IORuntime.global

    val reported: mutable.ListBuffer[(ByteString, ByteString)] = mutable.ListBuffer.empty
    val executedBlocks: mutable.ListBuffer[Block] = mutable.ListBuffer.empty

    private val genesisWeight = ChainWeight.totalDifficultyOnly(BlockHelpers.genesis.header.difficulty.value)
    blockchainWriter.save(BlockHelpers.genesis, Nil, genesisWeight, saveAsBestBlock = true)

    /** canonical 1..6; the fork point is block 3. */
    val canonical: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis, posBlock)
    canonical.foldLeft(genesisWeight) { (w, b) =>
      val next = w.increase(b.header)
      blockchainWriter.save(b, Nil, next, saveAsBestBlock = true)
      next
    }
    val canonicalTip: Block = canonical.last
    val forkPoint: Block = canonical(2)

    /** The canonical blocks a reorg would displace: everything strictly above the fork point. */
    val canonicalSuffix: List[Block] = canonical.drop(3)

    /** side 4..6 off canonical block 3 — same height as canonical, therefore same weight. */
    val sideBranch: List[Block] = BlockHelpers.generateChain(3, forkPoint, posBlock)

    /** The CL has designated a head one hop above the side branch: exactly hive's shape, where the tip arrives through
      * `engine_newPayload` (stored header, unexecuted) while its ancestors are still being fetched.
      */
    val clTip: Block = BlockHelpers.generateChain(1, sideBranch.last, posBlock).head
    blockchainWriter.storeBlockHeader(clTip.header).commit()
    val clDesignatedSideTip: DesignatedHead = DesignatedHead(() => Some(clTip.hash.value))

    private var failingBlockHash: Option[ByteString] = None
    def failAt(block: Block): Unit = failingBlockHash = Some(block.hash.value)

    override lazy val blockExecution: BlockExecution = stub[BlockExecution]
    (blockExecution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight)(_: BlockchainConfig))
      .when(*, *, *)
      .anyNumberOfTimes()
      .onCall { (blocks, weight, _) =>
        val ok = blocks.takeWhile(b => !failingBlockHash.contains(b.hash.value))
        executedBlocks ++= ok
        val executed = ok.foldLeft((weight, List.empty[BlockData])) { case ((w, acc), b) =>
          val next = w.increase(b.header)
          blockchainWriter.save(b, Nil, next, saveAsBestBlock = false)
          (next, acc :+ BlockData(b, Nil, next))
        }
        val failure: Option[BlockExecutionError] =
          blocks
            .find(b => failingBlockHash.contains(b.hash.value))
            .map(_ => ValidationBeforeExecError(BlockHeaderError.HeaderGasLimitError))
        (executed._2, failure)
      }

    def consensusWith(designatedHead: Option[DesignatedHead]): ConsensusImpl =
      new ConsensusImpl(
        blockchainReader,
        blockchainWriter,
        blockExecution,
        Some { (hash, lvh) =>
          reported += (hash -> lvh); ()
        },
        designatedHead
      )

  /** Post-merge shape: difficulty 0, and no ommers (a PoS block cannot carry them). */
  private def posBlock(block: Block): Block =
    block.copy(
      header = block.header.copy(difficulty = Difficulty.Zero),
      body = block.body.copy(uncleNodesList = Nil)
    )
