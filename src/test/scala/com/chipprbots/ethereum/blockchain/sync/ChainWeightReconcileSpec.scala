package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

  // Small batches and saves, so a few hundred blocks cross every boundary the pass has.
  private val Small: Settings = Settings(batchSize = 50, persistEvery = 50, waitRetry = 10.millis)

  // A SNAP pivot's estimate, carried by every block imported above it, and a restart calibration's jump above that.
  private val SnapEstimateOffset: BigInt = BigInt("3000000000000")
  private val CalibrationJump: BigInt = BigInt(-80000000)

  /** `length` headers above `parent`, each with its own difficulty; `salt` makes another branch from the same parent.
    */
  private def headers(parent: BlockHeader, length: Int, salt: Int = 0): Vector[BlockHeader] =
    (1 to length)
      .scanLeft(parent) { (prev, _) =>
        val n = prev.number.value + 1
        Genesis.copy(
          number = BlockNumber(n),
          parentHash = prev.hash,
          difficulty = Difficulty(BigInt(131072) + n * 7 + salt)
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

    def pass(s: Settings = Small, lock: ImportLock = ImportLock(), current: () => Boolean = () => true): Outcome =
      ChainWeightReconcile.run(engine(s), lock, current, appState).timeout(30.seconds).unsafeRunSync()

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

  it should "write the same values in chunks, all under one hold of the locks, when there are more than writeChunk" taggedAs (
    UnitTest,
    SyncTest
  ) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    pass(Small.copy(writeChunk = 30)) shouldBe a[Outcome.Done]
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

  it should "apply its repair holding the chain insert lock exclusively" taggedAs (UnitTest, SyncTest) in new Chain:
    val chain: Vector[BlockHeader] = jumped()
    val held = new AtomicReference[Option[Boolean]](None)
    pass(Small.copy(insideLock = () => held.set(Some(ChainInsertLock.heldExclusivelyByCurrentThread)))) shouldBe
      a[Outcome.Done]
    held.get shouldBe Some(true)
    stored(chain) shouldBe exact(chain).map(Some(_))

  it should "not deadlock against an import holding the import lock, and repair only after it ends" taggedAs (
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
      // A bounded wait for something that must NOT happen: the repair entering its locked section. With the locks it
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
    markerWhileHeld.get shouldBe Some(false) // ...and repaired nothing until the import ended
    stored(chain) shouldBe exact(chain).map(Some(_))
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
    pass(current = () => false) shouldBe Outcome.Superseded
    stored(chain) shouldBe before
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
// scalastyle:on magic.number
