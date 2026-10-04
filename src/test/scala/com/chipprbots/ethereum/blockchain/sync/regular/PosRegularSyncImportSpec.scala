package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.jsonrpc.NewBlockImported
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationBeforeExecError
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Regular sync on a post-merge chain whose consensus layer is far ahead — the Platåberget soak failure (#1432).
  *
  * WHAT BROKE. A fresh post-merge node with `do-snap-sync = false` must fetch the execution chain over devp2p toward a
  * head the CL has designated but the node cannot reach (Lighthouse sends only forkchoiceUpdated during finalized
  * sync). Live, fukuii imported 0 → 200 and then looped forever:
  *
  * {{{
  *   Attempting to import blocks starting from 201 and ending with 250       (8 min of execution follows)
  *   Attempting to import blocks starting from 251 and ending with 252       (95 ms later, same importer)
  *   Unknown branch, going back to block nr 136 in order to resolve branches
  *   Attempting to import blocks starting from 135 and ending with 210 → Imported no blocks
  *   Attempting to import blocks starting from 211 and ending with 260 → Unknown branch …   (forever)
  * }}}
  *
  * Two defects, both driven here through the REAL BlockImporter, BranchResolution, ConsensusAdapter and ConsensusImpl
  * over real (ephemeral) storage; only block execution is stubbed and the fetcher is a probe that hands out batches:
  *
  *   1. The livelock. After ANY rewind the fetcher re-serves from below the head, so a batch re-presents canonical
  *      N-65..N before extending the head. The whole batch reached ConsensusImpl with a parent that is not the head,
  *      i.e. down the side-branch path, where equal post-merge weight and an unreachable CL head answer
  *      KeptCurrentBestBranch: nothing imported, the next batch is UnknownBranch, rewind, repeat. 2. The trigger. Two
  *      PickBlocks can be in flight when an import starts, and a picked batch was resolved on arrival even mid-import —
  *      against the head as it was before that import commits — so it read as UnknownBranch.
  *
  * The tests marked "#1432" fail on the unfixed importer: the first leaves the head at N, the second sees the rewind.
  * The rest are guards that pass either way: the head+1 shape that always worked, the side-branch refusal, the hive
  * CanonicalReOrg acceptance, and the ETC/Mordor/Gorgoroth wiring, which must not move at all.
  */
// scalastyle:off magic.number
class PosRegularSyncImportSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with MockFactory:

  "BlockImporter on a post-merge chain far behind the CL head" should
    "import a batch that starts at the head's child — the control, this shape always worked" taggedAs (
      UnitTest,
      SyncTest,
      ConsensusTest
    ) in new ImporterFixture():
      start()
      requestPick()
      servePick(ahead.take(50))

      fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout) // sent once the import is done
      blockchainReader.getBestBlockNumber shouldBe head.number.value + 50
      stop()

  it should "import the head's extension out of a post-rewind batch instead of looping forever (#1432)" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture():
    start()
    // Any rewind (branch resolution, header rejection, an import error) leaves the fetcher re-serving from below the
    // head: canonical N-65..N, then the head's extension N+1..N+10. Live: `135..210` with the head at 200.
    requestPick()
    servePick(canonical.takeRight(66) ++ ahead.take(10))

    val next = fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    // Unfixed: "Imported no blocks" and the head stays at N.
    blockchainReader.getBestBlockNumber shouldBe head.number.value + 10

    next.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.slice(10, 60)))
    // Unfixed: InvalidateBlocksFrom(N - 64) — the UnknownBranch rewind that re-serves the batch above, forever.
    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    blockchainReader.getBestBlockNumber shouldBe head.number.value + 60
    // The canonical prefix was never re-executed; each block of the extension ran exactly once.
    executedNumbers shouldBe ahead.map(_.number.value)
    stop()

  it should "not resolve a batch that arrives while the previous import is still executing (#1432)" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture():
    start()
    // Two idle SyncRetryTicks put two PickBlocks in flight; the fetcher answers both.
    requestPick()
    requestPick()
    val first = fetcher.expectMessageType[BlockFetcher.PickBlocks]
    val second = fetcher.expectMessageType[BlockFetcher.PickBlocks]

    val (started, release) = holdBatchStartingAt(head.number.value + 1)
    try
      first.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.take(50)))
      started.await(10, TimeUnit.SECONDS) shouldBe true
      second.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.slice(50, 52)))
      // Unfixed: resolved at once against head N, whose child N+51's parent is not yet stored → UnknownBranch →
      // InvalidateBlocksFrom(N - 64) arrives right here. Live: `201..250` executing, `251..252` → "going back to 136".
      fetcher.expectNoMessage(2.seconds)
    finally release.countDown()

    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout) // only after BOTH imports
    blockchainReader.getBestBlockNumber shouldBe head.number.value + 52
    executedNumbers shouldBe ahead.take(52).map(_.number.value)
    stop()

  it should "still REFUSE a side branch the CL did not designate, even behind a canonical prefix" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture():
    start()
    // The batch shape the fix targets — it starts 65 below the head — but it leaves the canonical chain at N-2 and so
    // displaces N-2..N: a side branch, and not one the CL head descends from.
    val keep = canonical.takeRight(66).takeWhile(_.number.value <= head.number.value - 3)
    val side = keep ++ BlockHelpers.generateChain(5, keep.last, amsterdamBlock)
    requestPick()
    servePick(side)

    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    executedNumbers shouldBe empty
    blockchainReader.getBestBlockNumber shouldBe head.number.value
    blockchainReader.getBestBlock.map(_.hash) shouldBe Some(head.hash)
    stop()

  it should "still follow a side branch the CL DID designate — the hive CanonicalReOrg shape" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture():
    start()
    val side = BlockHelpers.generateChain(5, canonical(canonical.size - 4), amsterdamBlock) // N-2'..N+2'
    // The CL's tip arrives through engine_newPayload, which stores it by hash only, one hop above the side branch.
    val clTip = BlockHelpers.generateChain(1, side.last, amsterdamBlock).head
    blockchainWriter.storeBlockByHashOnly(clTip).commit()
    clHead = Some(clTip.hash.value)
    requestPick()
    servePick(side)

    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    executedNumbers shouldBe side.map(_.number.value)
    blockchainReader.getBestBlock.map(_.hash) shouldBe Some(side.last.hash)
    stop()

  it should "pick again after a fork recovery raised mid-import while a batch was deferred" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture():
    start()
    requestPick()
    requestPick()
    val first = fetcher.expectMessageType[BlockFetcher.PickBlocks]
    val second = fetcher.expectMessageType[BlockFetcher.PickBlocks]

    val (started, release) = holdBatchStartingAt(head.number.value + 1)
    raiseForkRecoveryFromImport(head.number.value + 1)
    val finished = importsDone()
    try
      first.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.take(50)))
      started.await(10, TimeUnit.SECONDS) shouldBe true
      second.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.slice(50, 52))) // deferred: the import is running
    finally release.countDown()
    // The held import now raises StartForkRecovery from inside itself and then finishes, so its ImportDone reaches
    // resolvingFork. Only once that ImportDone has been sent does the resolver answer.
    awaitImportDoneSent(finished)
    importer ! BlockImporter.BranchResolverMsg(FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, resolverPeer))
    fetcher.expectMessageType[BlockFetcher.InvalidateBlocksFrom](importTimeout).fromBlock shouldBe lca + 1

    requestPick()
    // Unfixed: resolvingFork dropped that ImportDone, `importing` stayed true, and this pick — like every later one —
    // was ignored: regular sync wedged for good, with the deferred batch pinned.
    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    stop()

  it should "drop, not replay, batches deferred behind an import that failed and already rewound the fetcher" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture():
    start()
    requestPick()
    requestPick()
    val first = fetcher.expectMessageType[BlockFetcher.PickBlocks]
    val second = fetcher.expectMessageType[BlockFetcher.PickBlocks]

    failAt(ahead.head)
    val (started, release) = holdBatchStartingAt(head.number.value + 1)
    try
      first.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.take(50)))
      started.await(10, TimeUnit.SECONDS) shouldBe true
      second.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.slice(50, 52))) // deferred: the import is running
    finally release.countDown()

    // The failed import rewinds the fetcher to its own first block: exactly one InvalidateBlocksFrom …
    fetcher.expectMessageType[BlockFetcher.InvalidateBlocksFrom](importTimeout).fromBlock shouldBe head.number.value + 1
    // … and the next thing the fetcher hears is a fresh pick. Unfixed: the deferred batch was handed back, resolved as
    // UnknownBranch and rewound the fetcher a second, deeper time — InvalidateBlocksFrom(N - 64) arrived here.
    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    executedNumbers shouldBe empty
    blockchainReader.getBestBlockNumber shouldBe head.number.value
    stop()

  "BlockImporter on a PoW chain (the ETC/Mordor/Gorgoroth wiring)" should
    "handle the same post-rewind batch exactly as before: the whole batch, prefix included, re-executed" taggedAs (
      UnitTest,
      SyncTest,
      ConsensusTest
    ) in new ImporterFixture(postMerge = false):
      start()
      val batch = canonical.takeRight(66) ++ ahead.take(10)
      requestPick()
      servePick(batch)

      fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
      // Heavier by difficulty, so weight selects it and ConsensusImpl.reorganise re-executes every block of it.
      executedNumbers shouldBe batch.map(_.number.value)
      blockchainReader.getBestBlockNumber shouldBe head.number.value + 10
      stop()

  it should "still resolve a batch that arrives mid-import on arrival — serialisation is gated off here" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture(postMerge = false):
    // Pinned, not endorsed: the pre-existing ETC behaviour, left exactly as it was because ETC must not move. Lifting
    // the gate on BlockImporter's deferral is a separate change that needs a forge review.
    start()
    requestPick()
    requestPick()
    val first = fetcher.expectMessageType[BlockFetcher.PickBlocks]
    val second = fetcher.expectMessageType[BlockFetcher.PickBlocks]

    val (started, release) = holdBatchStartingAt(head.number.value + 1)
    try
      first.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.take(50)))
      started.await(10, TimeUnit.SECONDS) shouldBe true
      second.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.slice(50, 52)))
      fetcher
        .expectMessageType[BlockFetcher.InvalidateBlocksFrom](importTimeout)
        .fromBlock shouldBe head.number.value - 64
    finally release.countDown()
    stop()

  it should "pick again after a fork recovery raised mid-import — the stale importing flag is cleared here too" taggedAs (
    UnitTest,
    SyncTest,
    ConsensusTest
  ) in new ImporterFixture(postMerge = false):
    // A liveness fix on ETC as well (forge review): resolvingFork used to drop the ImportDone of the import that
    // raised StartForkRecovery, on every chain. The only ETC change is that flag — no consensus rule moves.
    start()
    requestPick()
    val first = fetcher.expectMessageType[BlockFetcher.PickBlocks]

    raiseForkRecoveryFromImport(head.number.value + 1)
    val finished = importsDone()
    first.replyTo ! BlockFetcher.PickedBlocks(fromWire(ahead.take(50)))
    awaitImportDoneSent(finished)
    importer ! BlockImporter.BranchResolverMsg(FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, resolverPeer))
    fetcher.expectMessageType[BlockFetcher.InvalidateBlocksFrom](importTimeout).fromBlock shouldBe lca + 1

    requestPick()
    // Unfixed: ignored, `importing` still true — wedged.
    fetcher.expectMessageType[BlockFetcher.PickBlocks](importTimeout)
    stop()

  // ---------------------------------------------------------------------------------------------------------------
  // Fixture
  // ---------------------------------------------------------------------------------------------------------------

  /** Canonical 1..100 executed with weights, head N = 100, and the next 60 blocks ready to be served.
    *
    * @param postMerge
    *   `true`: the production wiring on a terminal-total-difficulty chain with the Engine API on — difficulty-0 blocks,
    *   and BranchResolution and ConsensusImpl both reading the CL's designated head. That head starts where the live
    *   node's was: far above N, three headers stored by hash only (as engine_newPayload stores them) over a parent the
    *   node does not hold, so no ancestry walk can reach N. `false`: the ETC/Mordor/Gorgoroth wiring — PoW difficulty,
    *   no designated head for BranchResolution (SyncController builds none without TTD) and an UNBOUND LateBound for
    *   ConsensusImpl (NodeBuilder always passes one; only the Engine API on a TTD chain binds it).
    */
  class ImporterFixture(postMerge: Boolean = true) extends EphemBlockchainTestSetup with TestSyncConfig:
    // The cake's own classic system, should anything in it ask: the test kit's, so no stray ActorSystem is started.
    override lazy val classicSystem: org.apache.pekko.actor.ActorSystem = testKit.system.classicSystem

    override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
      syncRetryInterval = 1.hour, // no timer-driven picks: every batch is handed out by the test
      blocksBatchSize = 50,
      branchResolutionRequestSize = 64 // the production value: a rewind lands 64 below the head
    )

    /** Upper bound for anything that waits on an import: generous, because the stubbed execution still writes every
      * block to storage and a loaded CI box can take seconds over 76 of them.
      */
    val importTimeout: FiniteDuration = 30.seconds

    private val adjust: Block => Block = if postMerge then amsterdamBlock else powBlock

    private val genesisWeight = ChainWeight.totalDifficultyOnly(BlockHelpers.genesis.header.difficulty.value)
    blockchainWriter.save(BlockHelpers.genesis, Nil, genesisWeight, saveAsBestBlock = true)

    val canonical: List[Block] = BlockHelpers.generateChain(100, BlockHelpers.genesis, adjust)
    canonical.foldLeft(genesisWeight) { (w, b) =>
      val next = w.increase(b.header)
      blockchainWriter.save(b, Nil, next, saveAsBestBlock = true)
      next
    }
    val head: Block = canonical.last
    val ahead: List[Block] = BlockHelpers.generateChain(60, head, adjust)

    private val clParent: Block = BlockHelpers.genesis.copy(
      header = BlockHelpers.genesis.header.copy(number = BlockNumber(head.number.value + 278600))
    )
    private val clHeaders: List[Block] = BlockHelpers.generateChain(3, clParent, posBlock)
    clHeaders.foreach(b => blockchainWriter.storeBlockByHashOnly(b).commit())

    /** What `ForkChoiceManager.getRequestedHeadBlockHash` would return: the head of the latest forkchoiceUpdated. */
    @volatile var clHead: Option[ByteString] = Some(clHeaders.last.hash.value)
    private val designatedHead: DesignatedHead = DesignatedHead(() => clHead)

    // --- block execution: stubbed, but it writes exactly what BlockExecution writes for an executed block ---------
    private val executed = new ConcurrentLinkedQueue[Block]()
    def executedNumbers: List[BigInt] = executed.asScala.toList.map(_.number.value)

    @volatile private var hold: Option[(BigInt, CountDownLatch, CountDownLatch)] = None
    @volatile private var failing: Option[ByteString] = None
    @volatile private var forkRecoveryFrom: Option[BigInt] = None

    /** Make `block` fail validation: the blocks before it in its batch execute, it and the rest do not. */
    def failAt(block: Block): Unit = failing = Some(block.hash.value)

    /** Have the import of the batch that starts at `firstBlock` raise StartForkRecovery from INSIDE itself, the way
      * FORK-DETECT does (tryImportBlocks, on the import's own IO) — so that import's ImportDone is sent after it.
      */
    def raiseForkRecoveryFromImport(firstBlock: BigInt): Unit = forkRecoveryFrom = Some(firstBlock)

    /** Make the execution of the batch that starts at `firstBlock` block until released: an import still running. */
    def holdBatchStartingAt(firstBlock: BigInt): (CountDownLatch, CountDownLatch) =
      val started = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      hold = Some((firstBlock, started, release))
      (started, release)

    private val execution: BlockExecution = stub[BlockExecution]
    (execution
      .executeAndValidateBlocks(_: List[Block], _: ChainWeight, _: Boolean)(_: BlockchainConfig))
      .when(*, *, *, *)
      .anyNumberOfTimes()
      .onCall { (blocks, weight, _, _) =>
        hold.filter { case (first, _, _) => blocks.headOption.exists(_.number.value == first) }.foreach {
          case (_, started, release) =>
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val valid = blocks.takeWhile(b => !failing.contains(b.hash.value))
        val (_, data) = valid.foldLeft((weight, Vector.empty[BlockData])) { case ((w, acc), b) =>
          val next = w.increase(b.header)
          blockchainWriter.save(b, Nil, next, saveAsBestBlock = false)
          executed.add(b)
          (next, acc :+ BlockData(b, Nil, next))
        }
        val failure: Option[BlockExecutionError] =
          blocks
            .find(b => failing.contains(b.hash.value))
            .map(_ => ValidationBeforeExecError(BlockHeaderError.HeaderGasLimitError))
        forkRecoveryFrom.filter(first => blocks.headOption.exists(_.number.value == first)).foreach { first =>
          importer ! BlockImporter.StartForkRecovery(first)
        }
        (data.toList, failure)
      }

    // --- the production objects under test ----------------------------------------------------------------------
    private val branchResolution =
      new BranchResolution(blockchainReader, if postMerge then Some(designatedHead) else None)

    private val consensusAdapter = new ConsensusAdapter(
      new ConsensusImpl(
        blockchainReader,
        blockchainWriter,
        execution,
        invalidChainReporter = None,
        designatedHead = if postMerge then Some(designatedHead) else Some(new DesignatedHead.LateBound)
      ),
      blockchainReader,
      blockQueue,
      stub[BlockValidation],
      IORuntime.global
    )

    val fetcher: TestProbe[BlockFetcher.FetchCommand] = createTestProbe[BlockFetcher.FetchCommand]()
    private val supervisor = createTestProbe[RegularSync.Command]()

    val importer: ActorRef[BlockImporter.Command] = spawn(
      BlockImporter(
        fetcher.ref,
        consensusAdapter,
        blockchainReader,
        blockchainWriter,
        stub[StateStorage],
        stub[EvmCodeStorage],
        branchResolution,
        syncConfig,
        createTestProbe[OmmersPool.Command]().ref,
        createTestProbe[BlockBroadcasterActor.BroadcasterMsg]().ref,
        createTestProbe[PendingTransactionsManager.Command]().ref,
        createTestProbe[Topic.Command[NewBlockImported]]().ref,
        supervisor.ref,
        createTestProbe[PeerEventBusActor.Command]().ref,
        createTestProbe[NetworkPeerManagerActor.Command]().ref,
        blockchain,
        CacheBasedBlacklist.empty(100),
        this
      )
    )

    def start(): Unit =
      importer ! BlockImporter.Start
      fetcher.expectMessageType[BlockFetcher.Start].fromBlock shouldBe head.number.value

    /** What an idle SyncRetryTick does. */
    def requestPick(): Unit = importer ! BlockImporter.PickBlocks

    /** Answer the next pick request the importer sends the fetcher with `blocks`. */
    def servePick(blocks: List[Block]): Unit =
      fetcher.expectMessageType[BlockFetcher.PickBlocks].replyTo ! BlockFetcher.PickedBlocks(fromWire(blocks))

    /** As the fetcher hands blocks over: headers RLP-decoded off the wire, while the canonical ones the importer
      * compares them against come back out of storage (boopickle).
      */
    def fromWire(blocks: List[Block]): NonEmptyList[Block] =
      NonEmptyList.fromListUnsafe(blocks.map(b => b.copy(header = b.header.toBytes.toBlockHeader)))

    def stop(): Unit = testKit.stop(importer)

    /** Where an injected fork resolution lands: ten blocks below the head. */
    val lca: BigInt = head.number.value - 10

    lazy val resolverPeer: Peer = Peer(
      PeerId("fork-resolver-master"),
      new InetSocketAddress("127.0.0.1", 30303),
      createTestProbe[PeerActor.Command]().ref,
      incomingConnection = false
    )

    /** How many imports have finished. `importWith` records this timer only AFTER it has sent that import's ImportDone,
      * so once it moves past a reading, the ImportDone is in the importer's mailbox, ahead of anything the test sends
      * next. Suites run one at a time in the forked test JVM (build.sbt: testForkedParallel := false).
      */
    def importsDone(): Long = RegularSyncMetrics.DefaultBlockPropagationTimer.count()

    def awaitImportDoneSent(before: Long): Unit =
      fetcher.awaitAssert(importsDone() should be > before, importTimeout, 10.millis)

  /** A PoW block as it crosses the wire: BlockHelpers' 1-byte nonce widened to the 8 bytes the decoder requires. */
  private def powBlock(block: Block): Block =
    block.copy(header =
      block.header.copy(nonce = ByteString(java.nio.ByteBuffer.allocate(8).putLong(block.number.value.toLong).array()))
    )

  /** Post-merge shape: difficulty 0, and no ommers (a PoS block cannot carry them). */
  private def posBlock(block: Block): Block =
    block.copy(
      header = block.header.copy(difficulty = Difficulty.Zero),
      body = block.body.copy(uncleNodesList = Nil)
    )

  /** A post-merge block with Platåberget's header shape: all 23 RLP fields (HefPostAmsterdam), and the 8-byte zero
    * nonce EIP-3675 fixes — BlockHelpers makes a 1-byte one, which the strict wire decoder rightly rejects.
    */
  private def amsterdamBlock(block: Block): Block =
    val pos = posBlock(block)
    def b32(fill: Int): ByteString = ByteString(Array.fill[Byte](32)(fill.toByte))
    pos.copy(header =
      pos.header.copy(
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostAmsterdam(
          baseFee = BigInt(7),
          withdrawalsRoot = b32(0x11),
          blobGasUsed = BigInt(0),
          excessBlobGas = BigInt(0),
          parentBeaconBlockRoot = b32(0x22),
          requestsHash = b32(0x33),
          blockAccessListHash = b32(0x44),
          slotNumber = pos.header.number.value * 2
        )
      )
    )
