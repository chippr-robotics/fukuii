package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import cats.effect.FiberIO
import cats.effect.IO

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.ChainWeightReconcile.*
import com.chipprbots.ethereum.blockchain.sync.regular.ImportLock
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.ChainInsertLock
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** #1654: the one-time pass that makes every stored canonical weight the genesis weight plus the difficulties up to its
  * block, and the marker that says so.
  */
class ChainWeightReconcileSpec extends AnyFlatSpec with Matchers:

  private val Genesis: BlockHeader = Fixtures.Blocks.Genesis.header

  // Small batches and saves, so a few hundred blocks cross every boundary the pass has; no sleep between batches.
  private val Small: Settings = Settings(batchSize = 50, readFactor = 0, persistEvery = 50, waitRetry = 10.millis)

  // A SNAP pivot's estimate, carried by every block imported above it, and a restart calibration's jump above that.
  private val SnapEstimateOffset: BigInt = BigInt("3000000000000")
  private val CalibrationJump: BigInt = BigInt(-80000000)

  /** `length` headers above `parent`; `difficulty` gives each one its own (a PoS chain gives 0); `salt` makes another
    * branch from the same parent.
    */
  private def headers(
      parent: BlockHeader,
      length: Int,
      salt: Int = 0,
      difficulty: BigInt => BigInt = n => BigInt(131072) + n * 7
  ): Vector[BlockHeader] =
    (1 to length)
      .scanLeft(parent) { (prev, _) =>
        val n = prev.number.value + 1
        Genesis.copy(
          number = BlockNumber(n),
          parentHash = prev.hash,
          difficulty = Difficulty(difficulty(n) + salt),
          extraData = ByteString(salt.toByte)
        )
      }
      .tail
      .toVector

  /** Each block's exact weight: genesis's own difficulty plus every later one up to it. */
  private def exact(chain: Vector[BlockHeader]): Vector[BigInt] =
    chain.scanLeft(BigInt(0))(_ + _.difficulty.value).tail

  private class Chain extends EphemBlockchainTestSetup:
    lazy val appState: AppStateStorage = storagesInstance.storages.appStateStorage

    /** Headers with their number mapping, the given weights (none where `None`), and the last as the best block. */
    def put(chain: Vector[BlockHeader], weights: Vector[Option[BigInt]]): Unit =
      chain.zip(weights).foreach { (h, w) =>
        blockchainWriter.storeBlockHeader(h).commit()
        w.foreach(td => blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(td)).commit())
      }
      appState.putBestBlockInfo(BlockInfo(chain.last.hash.value, chain.last.number.value)).commit()

    def stored(chain: Vector[BlockHeader]): Vector[Option[BigInt]] =
      chain.map(h => blockchainReader.getChainWeightByHash(h.hash).map(_.totalDifficulty.value))

    /** Genesis and 400 blocks: exact to the SNAP pivot at 200, the pivot's estimate above it, a calibration jump at
      * 301.
      */
    def jumped(): Vector[BlockHeader] =
      val chain = Genesis +: headers(Genesis, 400)
      put(
        chain,
        exact(chain).zipWithIndex.map { (w, n) =>
          Some(
            if n > 300 then w + SnapEstimateOffset + CalibrationJump else if n > 200 then w + SnapEstimateOffset else w
          )
        }
      )
      chain

    def engine(s: Settings = Small): Engine = Engine(blockchainReader, blockchainWriter, appState, s)

    def pass(s: Settings = Small, lock: ImportLock = ImportLock(), epoch: Epoch = new Epoch): Outcome =
      ChainWeightReconcile.run(engine(s), lock, epoch, epoch.current, appState).timeout(30.seconds).unsafeRunSync()

  "ChainWeightReconcile" should "repair a jump above the SNAP pivot and a calibration jump above it, then set the marker" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    pass() match
      case Outcome.Done(scanned, written, runs, rewinds, _) =>
        scanned shouldBe 401
        written shouldBe 200
        runs shouldBe 2
        rewinds shouldBe 0
      case other => fail(s"expected Done, got $other")
    stored(chain) shouldBe exact(chain).map(Some(_))
    appState.hasChainWeightInvariant() shouldBe true
    appState.getChainWeightReconcileProgress() shouldBe None

  it should "write the repair in chunks of writeChunk, with neither lock held" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    val chunks = new AtomicInteger(0)
    val heldDuringRepair = new AtomicReference[Option[Boolean]](None)
    val settings: Settings = Small.copy(
      writeChunk = 30,
      afterChunk = () =>
        chunks.incrementAndGet()
        if heldDuringRepair.get.isEmpty then heldDuringRepair.set(Some(ChainInsertLock.heldExclusivelyByCurrentThread))
    )
    pass(settings) shouldBe a[Outcome.Done]
    chunks.get should be >= 7 // 200 weights, 30 at a time
    heldDuringRepair.get shouldBe Some(false)
    stored(chain) shouldBe exact(chain).map(Some(_))
    appState.hasChainWeightInvariant() shouldBe true

  it should "write a weight where none is stored" taggedAs (UnitTest, SyncTest) in new Chain:
    val chain: Vector[BlockHeader] = Genesis +: headers(Genesis, 120)
    put(chain, exact(chain).zipWithIndex.map((w, n) => if n >= 40 && n < 60 then None else Some(w)))
    pass() match
      case Outcome.Done(_, written, runs, _, _) =>
        written shouldBe 20
        runs shouldBe 1
      case other => fail(s"expected Done, got $other")
    stored(chain) shouldBe exact(chain).map(Some(_))

  it should "repair a PoS chain to its genesis weight: difficulty-0 blocks over a genesis of TD 1, and of TD 0" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    for genesisTd <- Seq(BigInt(1), BigInt(0)) do
      new Chain:
        val genesis: BlockHeader = Genesis.copy(difficulty = Difficulty(genesisTd))
        val chain: Vector[BlockHeader] = genesis +: headers(genesis, 120, difficulty = _ => BigInt(0))
        // What earlier releases could leave: the block-number proxy, a peer estimate, and nothing at all.
        put(
          chain,
          chain.indices.toVector.map { n =>
            if n == 0 then Some(genesisTd)
            else if n < 40 then Some(BigInt(n))
            else if n < 80 then Some(BigInt("17000018015853232"))
            else if n < 100 then None
            else Some(genesisTd)
          }
        )
        withClue(s"genesis TD $genesisTd: ") {
          pass() shouldBe a[Outcome.Done]
          stored(chain) shouldBe Vector.fill(chain.size)(Some(genesisTd))
          appState.hasChainWeightInvariant() shouldBe true
        }
  }

  it should "resume after a crash with an identical result" taggedAs (UnitTest, SyncTest) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    // Three batches, each saved, then the process dies: the next pass is a new engine reading the saved place.
    val first: Engine = engine()
    var place: Progress = first.load()
    (1 to 3).foreach { _ =>
      first.step(place) match
        case Step.Advanced(next) =>
          place = next
          first.save(place)
        case other => fail(s"expected Advanced, got $other")
    }
    appState.getChainWeightReconcileProgress().flatMap(decode).map(_.cursor.number) shouldBe Some(BigInt(149))
    val resumed: Outcome = pass()

    val reference = new Chain
    val referenceChain: Vector[BlockHeader] = reference.jumped()
    val uninterrupted: Outcome = reference.pass()

    (resumed, uninterrupted) match
      case (Outcome.Done(s1, w1, r1, _, _), Outcome.Done(s2, w2, r2, _, _)) =>
        (s1, w1, r1) shouldBe ((s2, w2, r2))
      case other => fail(s"expected two Done, got $other")
    stored(chain) shouldBe reference.stored(referenceChain)
    stored(chain) shouldBe exact(chain).map(Some(_))

  it should "leave no marker when it dies between repair chunks, and converge on the next run" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    val dying: Settings = Small.copy(writeChunk = 30, afterChunk = () => throw new RuntimeException("killed"))
    an[RuntimeException] should be thrownBy pass(dying)
    appState.hasChainWeightInvariant() shouldBe false
    stored(chain).count(_.isDefined) shouldBe chain.size
    (stored(chain) == exact(chain).map(Some(_))) shouldBe false // one chunk in, most of the repair still to do

    pass() shouldBe a[Outcome.Done]
    stored(chain) shouldBe exact(chain).map(Some(_))
    appState.hasChainWeightInvariant() shouldBe true

  it should "finish (the tail and the marker) holding the chain insert lock exclusively" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    val held = new AtomicReference[Option[Boolean]](None)
    pass(Small.copy(insideLock = () => held.set(Some(ChainInsertLock.heldExclusivelyByCurrentThread)))) shouldBe
      a[Outcome.Done]
    held.get shouldBe Some(true)
    stored(chain) shouldBe exact(chain).map(Some(_))

  it should "not deadlock against an import holding the import lock, and set its marker only after it ends" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    val lock: ImportLock = ImportLock()
    val importHolds = new CountDownLatch(1)
    val reconcileAsks = new CountDownLatch(1)
    val reconcileInside = new CountDownLatch(1)
    val askedWhileHeld = new AtomicReference[Option[Boolean]](None)
    val insideWhileHeld = new AtomicReference[Option[Boolean]](None)
    val markerWhileHeld = new AtomicReference[Option[Boolean]](None)
    // An import as BlockImporter runs one: the import lock outermost, the chain insert lock shared inside it.
    val anImport: IO[Unit] = lock.guard(IO.blocking(ChainInsertLock.shared {
      importHolds.countDown()
      askedWhileHeld.set(Some(reconcileAsks.await(10, TimeUnit.SECONDS)))
      // A bounded wait for something that must NOT happen: the finish entering its locked section. With the locks it
      // cannot until this import ends, so this always times out; without them it enters at once.
      insideWhileHeld.set(Some(reconcileInside.await(2, TimeUnit.SECONDS)))
      markerWhileHeld.set(Some(appState.hasChainWeightInvariant()))
    }))
    val importFiber: FiberIO[Unit] = anImport.start.unsafeRunSync()
    importHolds.await(10, TimeUnit.SECONDS) shouldBe true

    val outcome: Outcome = pass(
      Small.copy(beforeLock = IO(reconcileAsks.countDown()), insideLock = () => reconcileInside.countDown()),
      lock = lock
    )
    importFiber.joinWithNever.timeout(10.seconds).unsafeRunSync()

    outcome shouldBe a[Outcome.Done]
    askedWhileHeld.get shouldBe Some(true) // the pass reached the lock while the import held it...
    insideWhileHeld.get shouldBe Some(false) // ...did not enter its locked section...
    markerWhileHeld.get shouldBe Some(false) // ...and set no marker until the import ended
    stored(chain) shouldBe exact(chain).map(Some(_))
    appState.hasChainWeightInvariant() shouldBe true

  it should "check blocks mapped above the best block, and hold off a newPayload-style write until it is done" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    // A payload the consensus layer has not promoted yet: mapped above the best block, its weight derived from the
    // unrepaired head (EngineApiService stores the block, then the weight).
    val payload: BlockHeader = headers(chain.last, 1).head
    blockchainWriter.storeBlockHeader(payload).commit()
    val staleHead: BigInt = stored(chain).last.get
    blockchainWriter
      .storeChainWeight(payload.hash, ChainWeight.totalDifficultyOnly(staleHead + payload.difficulty.value))
      .commit()
    // Another payload lands during the finish: stored, and its weight written as EngineApiService.storeChainWeightFor
    // does, from its parent's under the chain insert lock (shared).
    val landing: BlockHeader = headers(payload, 1).head
    blockchainWriter.storeBlockHeader(landing).commit()
    val landed = new CountDownLatch(1)
    val landedDuringFinish = new AtomicReference[Option[Boolean]](None)
    val writer = new Thread(() =>
      ChainInsertLock.shared {
        val parentWeight = blockchainReader.getChainWeightByHash(payload.hash).get
        blockchainWriter.storeChainWeight(landing.hash, parentWeight.increase(landing)).commit()
      }
      landed.countDown()
    )
    val outcome: Outcome = pass(Small.copy(insideLock = () =>
      writer.start()
      landedDuringFinish.set(Some(landed.await(1, TimeUnit.SECONDS)))
    ))
    landed.await(10, TimeUnit.SECONDS) shouldBe true
    writer.join(10000)

    outcome shouldBe a[Outcome.Done]
    landedDuringFinish.get shouldBe Some(false)
    val all: Vector[BlockHeader] = chain :+ payload :+ landing
    stored(all) shouldBe exact(all).map(Some(_))
    appState.hasChainWeightInvariant() shouldBe true

  it should "go back to the highest place still canonical after a reorg below its cursor, and check the new branch" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val a: Vector[BlockHeader] = Genesis +: headers(Genesis, 300)
    put(a, exact(a).zipWithIndex.map((w, n) => Some(if n > 200 then w + SnapEstimateOffset else w)))
    val first: Engine = engine()
    var place: Progress = first.load()
    (1 to 5).foreach { _ =>
      first.step(place) match
        case Step.Advanced(next) => place = next
        case other               => fail(s"expected Advanced, got $other")
    }
    place.cursor.number shouldBe 249
    first.save(place)

    // A reorg at 151: blocks 151..320 of another branch become canonical, with weights that do not follow either.
    val b: Vector[BlockHeader] = a.take(151) ++ headers(a(150), 170, salt = 3)
    put(b.drop(151), exact(b).drop(151).map(w => Some(w + 99)))

    pass() match
      case Outcome.Done(_, _, _, rewinds, _) => rewinds should be >= 1
      case other                             => fail(s"expected Done, got $other")
    stored(b) shouldBe exact(b).map(Some(_))
    appState.hasChainWeightInvariant() shouldBe true

  it should "run once: with the marker set, a later start checks nothing" taggedAs (UnitTest, SyncTest) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    pass() shouldBe a[Outcome.Done]
    blockchainWriter.storeChainWeight(chain(250).hash, ChainWeight.totalDifficultyOnly(BigInt(1))).commit()
    pass() shouldBe Outcome.NotOwed
    blockchainReader.getChainWeightByHash(chain(250).hash).map(_.totalDifficulty.value) shouldBe Some(BigInt(1))

  it should "write nothing once superseded (a SNAP start, a seed or a calibration write came first)" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    val before: Vector[Option[BigInt]] = stored(chain)
    val epoch = new Epoch
    val started: Long = epoch.current
    epoch.supersede(())
    ChainWeightReconcile.run(engine(), ImportLock(), epoch, started, appState).unsafeRunSync() shouldBe
      Outcome.Superseded
    stored(chain) shouldBe before
    appState.hasChainWeightInvariant() shouldBe false

  it should "stop with an ERROR, the marker off, past maxRuns distinct differences" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = Genesis +: headers(Genesis, 120)
    // Every other block off by a different amount: one run each.
    put(chain, exact(chain).zipWithIndex.map((w, n) => Some(if n % 2 == 1 then w + n else w)))
    pass(Small.copy(maxRuns = 10)) shouldBe a[Outcome.TooManyRuns]
    appState.hasChainWeightInvariant() shouldBe false

  it should "wait at a height whose header is not stored yet, and go on once the backfill stores it" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = Genesis +: headers(Genesis, 120)
    val weights: Vector[Option[BigInt]] = exact(chain).map(Some(_))
    put(chain.take(61), weights.take(61))
    put(chain.drop(62), weights.drop(62)) // 61 is missing; 120 is the best block
    val e: Engine = engine()
    val afterFirst: Progress = e.step(e.load()) match
      case Step.Advanced(next) => next
      case other               => fail(s"expected Advanced, got $other")
    val waiting: Progress = e.step(afterFirst) match
      case Step.Waiting(next, at, _) =>
        at shouldBe 61
        next
      case other => fail(s"expected Waiting, got $other")
    waiting.cursor.number shouldBe 60

    put(Vector(chain(61)), Vector(weights(61)))
    appState.putBestBlockInfo(BlockInfo(chain.last.hash.value, chain.last.number.value)).commit()
    e.step(waiting) shouldBe a[Step.Advanced]

  "ChainWeightReconcile.Progress" should "survive its encoding" taggedAs (UnitTest, SyncTest) in {
    val base = Position(BigInt(199), ByteString(Array.fill(32)(7.toByte)), BigInt("123456789012345"))
    val p = Progress(
      cursor = Position(BigInt(249), ByteString(Array.fill(32)(9.toByte)), BigInt("999999999999999")),
      runs =
        Vector(Run(BigInt(0), BigInt(0), BeforeGenesis, Some(BigInt(-5))), Run(BigInt(200), BigInt(249), base, None)),
      recent = Vector(base, Position(BigInt(249), ByteString(Array.fill(32)(9.toByte)), BigInt("999999999999999"))),
      scanned = BigInt(250)
    )
    decode(encode(p)) shouldBe Some(p)
    decode(encode(Start)) shouldBe Some(Start)
    decode("2|something else") shouldBe None
  }

  "The chain-weight marker" should "be taken away by a checkpoint import" taggedAs (UnitTest, SyncTest) in new Chain:
    appState.markChainWeightInvariant().commit()
    appState.hasChainWeightInvariant() shouldBe true
    // CheckpointImporter's first write (CheckpointImporter.scala, before the first node and the archive's weight).
    appState.beginCheckpointImport(ByteString(Array.fill(32)(1.toByte)), 0L).commit()
    appState.hasChainWeightInvariant() shouldBe false

  it should "be set by the write that follows a new datadir's genesis" taggedAs (UnitTest, SyncTest) in new Chain:
    appState.hasChainWeightInvariant() shouldBe false
    appState.markDatadirCreated(countsExact = false).commit()
    appState.hasChainWeightInvariant() shouldBe true

  it should "not be set by a SNAP hand-off over a datadir an older release wrote, even on a pivot that follows its parent" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    // No marker, headers above genesis, a calibration jump at 301: what an upgraded datadir holds.
    val chain: Vector[BlockHeader] = jumped()
    atSnapStart(blockchainReader, appState) shouldBe false
    // The SNAP pivot follows from a stale parent: exact relative to it, and nothing below is.
    val pivot: BlockHeader = headers(chain.last, 1).head
    val staleParent: BigInt = stored(chain).last.get
    put(Vector(pivot), Vector(Some(staleParent + pivot.difficulty.value)))
    HeadChainWeight.classify(blockchainReader, pivot) shouldBe a[HeadChainWeight.Status.Exact]

    atSnapHandOff(blockchainReader, appState, pivot.number.value) shouldBe false
    appState.hasChainWeightInvariant() shouldBe false
    appState.isChainWeightSnapPending() shouldBe false

  it should "be set again by an exact hand-off of a SNAP that began with it standing, across a restart mid-SNAP" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = Genesis +: headers(Genesis, 100)
    put(chain, exact(chain).map(Some(_)))
    appState.markChainWeightInvariant().commit()

    atSnapStart(blockchainReader, appState) shouldBe true
    appState.hasChainWeightInvariant() shouldBe false
    atSnapStart(blockchainReader, appState) shouldBe true // the node restarted mid-SNAP

    atSnapHandOff(blockchainReader, appState, BigInt(100)) shouldBe true
    appState.hasChainWeightInvariant() shouldBe true
    appState.isChainWeightSnapPending() shouldBe false

  it should "count a datadir with no header above genesis as exact at a SNAP start" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    put(Vector(Genesis), Vector(Some(Genesis.difficulty.value)))
    atSnapStart(blockchainReader, appState) shouldBe true

  "ChainWeightSeed" should "refuse every seed on a chain that was PoS from genesis (terminal TD 0)" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = Genesis +: headers(Genesis, 10)
    put(chain, exact(chain).map(Some(_)))
    appState.markChainWeightInvariant().commit()
    val before: Vector[Option[BigInt]] = stored(chain)
    val spec: String = s"${com.chipprbots.ethereum.utils.Hex.toHexString(chain(5).hash.value.toArray)}:1"
    ChainWeightSeed(spec, blockchainReader, blockchainWriter, appState, posFromGenesis = true) match
      case Seq(ChainWeightSeed.Outcome.Refused(_, reason)) => reason should include("PoS from genesis")
      case other                                           => fail(s"expected one refusal, got $other")
    stored(chain) shouldBe before
    appState.hasChainWeightInvariant() shouldBe true
// scalastyle:on magic.number
