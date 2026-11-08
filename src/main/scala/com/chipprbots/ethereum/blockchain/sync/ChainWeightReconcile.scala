package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.concurrent.duration.*
import scala.util.Try

import com.google.common.util.concurrent.AtomicDouble
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.regular.ImportLock
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditThrottle
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditWalk
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.ChainInsertLock
import com.chipprbots.ethereum.metrics.MetricsContainer
import com.chipprbots.ethereum.utils.Hex

/** One pass over the canonical chain that makes every stored weight the genesis weight plus the difficulties up to its
  * block, the value import writes (parent + difficulty), and then sets the chain-weight marker (#1654).
  *
  * Fork choice compares stored weights (`ConsensusImpl.importToNewBranch`, `BlockQueue`). A weight that does not follow
  * from its parent's is a jump, and every fork branching below it is weighed across it. Earlier releases wrote such
  * jumps: a SNAP pivot finalised with a peer estimate, whose exact value the header backfill later wrote under every
  * block that inherited the estimate, and a calibration that rewrote the head on each restart (0.7.0 to 0.10.x).
  * `HeadChainWeight` sees only the link between the head and its parent, so neither shows there. core-geth never has
  * one: `HeaderChain.WriteHeaders` writes parent TD + difficulty for every header from genesis and refuses a header
  * whose parent has none.
  *
  * '''Scan''', without any lock: forward from genesis, a batch of heights at a time (number to hash, header, stored
  * weight; each one `multiGet` that does not fill the block cache). Batches are at most [[AuditWalk.MaxBatch]] and
  * sized and spaced by [[AuditThrottle]]: each holds the data source's read lock, and a queued writer waits behind it.
  * The scan carries the exact weight forward and records where the stored one differs, as [[Run]]s of equal difference,
  * so a jump costs one entry however many blocks sit above it. Its place is saved every `persistEvery` blocks. A height
  * with no header waits and retries; a reorg below the place goes back to the highest earlier place still canonical.
  *
  * '''Repair''', without any lock: each run is re-derived from its base and the headers, bottom-up, and written in
  * chunks. A block's exact weight depends only on its ancestry, so the value written is right whether or not the block
  * stays canonical, and writing it twice is harmless; a crash between chunks costs only a re-run. Import goes on.
  *
  * '''Finish''', under the import lock and then the chain insert lock held exclusively (the order every import path
  * keeps: [[ImportLock]] outermost): check the blocks imported since the scan ended, and every block mapped above the
  * best block (an Engine API payload is mapped before the consensus layer promotes it), then write those that differ
  * and the marker in ONE batch. That batch is also atomic against everything that supersedes a pass ([[Epoch]]). The
  * hold covers only the tail, so it is short.
  */
object ChainWeightReconcile:

  /** Under the `SyncController` logger, which the shipped `logback.xml` keeps at INFO (as
    * `BackgroundAudit.LoggerName`).
    */
  val LoggerName: String = "com.chipprbots.ethereum.blockchain.sync.SyncController.ChainWeightReconcile"

  /** What the import lock reports while the finish holds it (`ImportLock.heldBy`). */
  val LockHolder: String = "the chain-weight repair's final step (#1654)"

  /** A canonical block with its exact weight. */
  final case class Position(number: BigInt, hash: ByteString, td: BigInt)

  /** The place before genesis. Genesis's exact weight is 0 plus its own difficulty, which is what the genesis load
    * stores.
    */
  val BeforeGenesis: Position = Position(BigInt(-1), ByteString.empty, BigInt(0))

  /** Canonical blocks `from..to` whose stored weight is off by the same `delta` (exact minus stored; `None` where no
    * weight is stored). `base` is the canonical block below `from` with its exact weight: the repair re-derives every
    * weight of the run from it.
    */
  final case class Run(from: BigInt, to: BigInt, base: Position, delta: Option[BigInt]):
    def blocks: BigInt = to - from + 1

  /** A scan's place: `cursor` is the last block checked, `runs` what it found up to there and has not repaired yet,
    * `recent` earlier places to go back to after a reorg (oldest first), `scanned` the blocks checked so far.
    */
  final case class Progress(cursor: Position, runs: Vector[Run], recent: Vector[Position], scanned: BigInt):
    def diffBlocks: BigInt = runs.map(_.blocks).sum

  val Start: Progress = Progress(BeforeGenesis, Vector.empty, Vector.empty, BigInt(0))

  /** What one scan step did. */
  enum Step:
    case Advanced(progress: Progress)
    case AtHead(progress: Progress)

    /** Height `at` cannot be checked yet: no header (`reason` names it), or its header does not link to the one below.
      */
    case Waiting(progress: Progress, at: BigInt, reason: String)

    /** The block at `lost` is no longer canonical: back to the highest earlier place that is. */
    case Rewound(progress: Progress, lost: BigInt)

  /** What the repair did. `progress` has no runs left; `repaired` are the runs it wrote. */
  enum Repaired:
    case Done(progress: Progress, written: Long, repaired: Vector[Run])
    case Superseded

  /** What the finish did. */
  enum Finished:
    /** The marker is set. `written` weights of the tail were rewritten in the same batch. */
    case Done(progress: Progress, written: Long)
    case Rewound(progress: Progress, lost: BigInt)

    /** A SNAP start, a seed or a calibration write came first: the batch was not written. */
    case Superseded

  enum Outcome:
    /** The marker stood: nothing to check. */
    case NotOwed
    case Done(scanned: BigInt, written: Long, runs: Int, rewinds: Int, elapsedMs: Long)
    case Superseded

    /** More distinct differences than `maxRuns`: not a shape an earlier release could write. Nothing is lost; the
      * marker stays off and an ERROR asks for a look.
      */
    case TooManyRuns(at: BigInt, runs: Int)

  final case class Settings(
      batchSize: Int = AuditWalk.MaxBatch,
      // Duty cycle of the scan: sleep this many times what a batch took (1 is 50%; AuditThrottle.delayAfter).
      readFactor: Int = 1,
      persistEvery: Long = 10_000L,
      logEvery: Long = 1_000_000L,
      keepRecent: Int = 16,
      writeChunk: Int = 10_000,
      maxRuns: Int = 10_000,
      waitRetry: FiniteDuration = 10.minutes,
      // Test seams: just before the import lock is requested; inside both locks, before the tail is checked; after
      // each repair chunk is committed.
      beforeLock: IO[Unit] = IO.unit,
      insideLock: () => Unit = () => (),
      afterChunk: () => Unit = () => ()
  )

  /** What supersedes a pass, and the monitor that makes a pass's saves and its final batch atomic against it. A SNAP
    * start, a seed and a calibration write run under [[supersede]]; a pass writes only through [[ifCurrent]] with the
    * value it started with. So a pass never sets the marker, nor saves a place, over what superseded it, and nothing
    * that supersedes waits behind a repair: the monitor is held only for one small commit at a time.
    */
  final class Epoch:
    private val value = new AtomicLong(0L)

    def current: Long = value.get()

    def isCurrent(e: Long): Boolean = value.get() == e

    def supersede[A](andThen: => A): A = synchronized {
      value.incrementAndGet()
      andThen
    }

    def ifCurrent(e: Long)(write: => Unit): Boolean = synchronized {
      if value.get() == e then
        write
        true
      else false
    }

  // ---------------------------------------------------------------------------------------------- encoding of Progress

  private def encodePosition(p: Position): String = s"${p.number}:${Hex.toHexString(p.hash.toArray)}:${p.td}"

  private def decodePosition(s: String): Position =
    s.split(":", -1) match
      case Array(n, h, td) => Position(BigInt(n), ByteString(Hex.decode(h)), BigInt(td))
      case _               => throw new IllegalArgumentException(s"bad position '$s'")

  def encode(p: Progress): String =
    val runs = p.runs
      .map(r => s"${r.from},${r.to},${encodePosition(r.base)},${r.delta.map(_.toString).getOrElse("x")}")
      .mkString(";")
    s"1|${encodePosition(p.cursor)}|${p.recent.map(encodePosition).mkString(";")}|$runs|${p.scanned}"

  /** `None` for anything this version did not write: the pass then starts again from genesis. */
  def decode(s: String): Option[Progress] =
    Try {
      s.split("\\|", -1) match
        case Array("1", cursor, recent, runs, scanned) =>
          def list(x: String): Vector[String] = if x.isEmpty then Vector.empty else x.split(";", -1).toVector
          Progress(
            decodePosition(cursor),
            list(runs).map { r =>
              r.split(",", -1) match
                case Array(from, to, base, delta) =>
                  Run(
                    BigInt(from),
                    BigInt(to),
                    decodePosition(base),
                    if delta == "x" then None else Some(BigInt(delta))
                  )
                case _ => throw new IllegalArgumentException(s"bad run '$r'")
            },
            list(recent).map(decodePosition),
            BigInt(scanned)
          )
        case _ => throw new IllegalArgumentException("unknown progress version")
    }.toOption

  // ------------------------------------------------------------------------------------------- the marker around SNAP

  /** A SNAP start, inside [[Epoch.supersede]]. The pivot carries an estimate until the headers reach it, so the marker
    * goes. Whether the hand-off may set it again is remembered here, in the same write, across restarts mid-SNAP: only
    * when the marker stood now (or stood at an earlier start of this SNAP), or the datadir has no header above genesis.
    * Otherwise a datadir written by an older release could hold stale weights the SNAP download does not rewrite, and a
    * pivot that follows from a stale parent would set the marker over them (vault M2). Returns whether it may.
    */
  def atSnapStart(reader: BlockchainReader, appState: AppStateStorage): Boolean =
    val stood = appState.hasChainWeightInvariant()
    val fresh = reader.getBestBlockNumber == 0 && reader.getBlockHeaderByNumber(1).isEmpty
    if stood || fresh then appState.clearChainWeightInvariant().and(appState.markChainWeightSnapPending()).commitSync()
    appState.isChainWeightSnapPending()

  /** A SNAP hand-off. The marker is set only if this SNAP began on an exact chain ([[atSnapStart]]) and the pivot's
    * weight follows from its parent's (REAL_DB_TD or DERIVED_FROM_PARENT at finalisation): the header download stored
    * every header from the old exact top with its parent's weight plus its difficulty. Returns whether it set the
    * marker.
    */
  def atSnapHandOff(reader: BlockchainReader, appState: AppStateStorage, pivot: BigInt): Boolean =
    val exact = reader.getBlockHeaderByNumber(pivot).map(HeadChainWeight.classify(reader, _)) match
      case Some(HeadChainWeight.Status.Exact(_)) => true
      case _                                     => false
    val set = exact && appState.isChainWeightSnapPending()
    val clear = appState.clearChainWeightSnapPending()
    (if set then clear.and(appState.markChainWeightInvariant()) else clear).commitSync()
    set

  // --------------------------------------------------------------------------------------------------------- the engine

  /** The scan, the repair and the finish over storage, synchronous. [[run]] drives them. */
  final class Engine(
      reader: BlockchainReader,
      writer: BlockchainWriter,
      appState: AppStateStorage,
      val settings: Settings = Settings()
  ):

    def load(): Progress = appState.getChainWeightReconcileProgress().flatMap(decode).getOrElse(Start)

    def save(p: Progress): Unit = appState.putChainWeightReconcileProgress(encode(p)).commit()

    private def canonical(p: Position): Boolean =
      p.number < 0 || reader.getHashesByNumbersNoFill(Seq(p.number)).headOption.flatten.contains(p.hash)

    /** Back to the highest earlier place still canonical (before genesis if none), dropping what was found above it. */
    def rewind(p: Progress): Progress =
      val back = p.recent.reverseIterator.find(canonical).getOrElse(BeforeGenesis)
      val runs =
        p.runs.filter(_.from <= back.number).map(r => if r.to > back.number then r.copy(to = back.number) else r)
      Progress(back, runs, p.recent.filter(_.number <= back.number), p.scanned)

    /** One scan batch: up to `batch` heights above the cursor, never past the best block. */
    def step(p: Progress, batch: Int = settings.batchSize): Step =
      if !canonical(p.cursor) then Step.Rewound(rewind(p), p.cursor.number)
      else
        val best = reader.getBestBlockNumber
        if p.cursor.number >= best then Step.AtHead(p)
        else
          val (next, stop) = check(p, p.cursor.number + 1, (p.cursor.number + batch.max(1)).min(best))
          stop match
            case Some((at, reason)) => Step.Waiting(next, at, reason)
            case None => Step.Advanced(next.copy(recent = (next.recent :+ next.cursor).takeRight(settings.keepRecent)))

    /** Check heights `from..to`: where it stopped, and why, if before `to`. */
    private def check(p: Progress, from: BigInt, to: BigInt): (Progress, Option[(BigInt, String)]) =
      val numbers = (from to to).toVector
      val hashes = reader.getHashesByNumbersNoFill(numbers).takeWhile(_.isDefined).flatten
      val headers = reader.getBlockHeadersByHashesNoFill(hashes)
      val weights = reader.getChainWeightsByHashesNoFill(hashes)
      var prev = p.cursor
      var runs = p.runs
      var i = 0
      var stop: Option[(BigInt, String)] = None
      while stop.isEmpty && i < hashes.size do
        val n = numbers(i)
        headers(i) match
          case None => stop = Some(n -> "no header stored for the canonical hash")
          case Some(h) if n > 0 && h.parentHash.value != prev.hash =>
            stop = Some(n -> "its header does not link to the canonical block below")
          case Some(h) =>
            val td = prev.td + h.difficulty.value
            val stored = weights(i).map(_.totalDifficulty.value)
            if !stored.contains(td) then runs = extend(runs, n, prev, stored.map(td - _))
            prev = Position(n, hashes(i), td)
            i += 1
      if stop.isEmpty && hashes.size < numbers.size then
        stop = Some(numbers(hashes.size) -> "no canonical hash stored at this height")
      (p.copy(cursor = prev, runs = runs, scanned = p.scanned + i), stop)

    private def extend(runs: Vector[Run], n: BigInt, base: Position, delta: Option[BigInt]): Vector[Run] =
      runs.lastOption match
        case Some(last) if last.to == n - 1 && last.delta == delta => runs.init :+ last.copy(to = n)
        case _                                                     => runs :+ Run(n, n, base, delta)

    /** The repair, with no lock: every run re-derived from its base along the parent links, bottom-up, in chunks of
      * `writeChunk`. A height whose header does not link to the one below ends its run there (the index moved under
      * it); the finish's re-check, or the next pass, finds what is left. Stops between chunks once superseded.
      */
    def repair(p: Progress, isCurrent: () => Boolean): Repaired =
      var pending = Vector.empty[(BlockHash, ChainWeight)]
      var written = 0L
      var superseded = false
      def flush(): Unit =
        if pending.nonEmpty then
          writer.storeChainWeights(pending).commit()
          written += pending.size
          pending = Vector.empty
          settings.afterChunk()
          superseded = !isCurrent()
      val runs = p.runs.iterator
      while !superseded && runs.hasNext do
        val run = runs.next()
        var prev = run.base
        var n = run.from
        var linked = true
        while !superseded && linked && n <= run.to do
          val numbers = (n to (n + settings.batchSize - 1).min(run.to)).toVector
          val hashes = reader.getHashesByNumbersNoFill(numbers).takeWhile(_.isDefined).flatten
          val headers = reader.getBlockHeadersByHashesNoFill(hashes)
          val weights = reader.getChainWeightsByHashesNoFill(hashes)
          var i = 0
          while !superseded && linked && i < hashes.size do
            headers(i) match
              case Some(h) if numbers(i) == 0 || h.parentHash.value == prev.hash =>
                val td = prev.td + h.difficulty.value
                if !weights(i).map(_.totalDifficulty.value).contains(td) then
                  pending :+= BlockHash(hashes(i)) -> ChainWeight.totalDifficultyOnly(td)
                  if pending.size >= settings.writeChunk then flush()
                prev = Position(numbers(i), hashes(i), td)
                i += 1
              case _ => linked = false
          if hashes.size < numbers.size then linked = false
          n = numbers.last + 1
      if !superseded then flush()
      if superseded then Repaired.Superseded else Repaired.Done(p.copy(runs = Vector.empty), written, p.runs)

    /** The finish. The caller holds the import lock and the chain insert lock exclusively. Checks every block from the
      * cursor up the contiguous, linked, mapped heights (past the best block: a payload the consensus layer has not
      * promoted yet is mapped already), then writes those that differ, the marker and the end of the saved place in one
      * batch, only while `e` is current.
      */
    def finishLocked(p: Progress, epoch: Epoch, e: Long): Finished =
      if !canonical(p.cursor) then Finished.Rewound(rewind(p), p.cursor.number)
      else
        var prev = p.cursor
        var tail = Vector.empty[(BlockHash, ChainWeight)]
        var more = true
        while more do
          val numbers = (prev.number + 1 to prev.number + settings.batchSize).toVector
          val hashes = reader.getHashesByNumbersNoFill(numbers).takeWhile(_.isDefined).flatten
          val headers = reader.getBlockHeadersByHashesNoFill(hashes)
          val weights = reader.getChainWeightsByHashesNoFill(hashes)
          var i = 0
          while more && i < hashes.size do
            headers(i) match
              case Some(h) if numbers(i) == 0 || h.parentHash.value == prev.hash =>
                val td = prev.td + h.difficulty.value
                if !weights(i).map(_.totalDifficulty.value).contains(td) then
                  tail :+= BlockHash(hashes(i)) -> ChainWeight.totalDifficultyOnly(td)
                prev = Position(numbers(i), hashes(i), td)
                i += 1
              case _ => more = false
          if hashes.size < numbers.size then more = false
        val written = epoch.ifCurrent(e) {
          writer
            .storeChainWeights(tail)
            .and(appState.markChainWeightInvariant())
            .and(appState.removeChainWeightReconcileProgress())
            .commitSync()
        }
        if written then Finished.Done(p.copy(cursor = prev), tail.size.toLong) else Finished.Superseded

  // --------------------------------------------------------------------------------------------------------- the runner

  /** The pass, start to end: nothing when the marker stands, else the scan from the saved place, the repair, and the
    * finish under both locks. Waits (`waitRetry`) where a height cannot be checked yet. Ends `Superseded` once `e` is
    * no longer current. Every step is short (one batch or one chunk), so a cancel takes effect at the next one.
    */
  def run(
      engine: Engine,
      importLock: ImportLock,
      epoch: Epoch,
      e: Long,
      appState: AppStateStorage,
      log: Logger = LoggerFactory.getLogger(LoggerName),
      nowMs: () => Long = () => System.currentTimeMillis()
  ): IO[Outcome] =
    val s = engine.settings
    val started = nowMs()
    def isCurrent: Boolean = epoch.isCurrent(e)

    def save(p: Progress): IO[Unit] = IO.blocking(epoch.ifCurrent(e)(engine.save(p))).void

    final case class Totals(written: Long, runs: Int, rewinds: Int)

    def loop(
        p: Progress,
        batch: Int,
        unsaved: Long,
        nextLog: BigInt,
        t: Totals,
        waitingAt: Option[BigInt]
    ): IO[Outcome] =
      if !isCurrent then IO.pure(Outcome.Superseded)
      else
        IO.blocking {
          val t0 = System.nanoTime()
          val step = engine.step(p, batch)
          (step, System.nanoTime() - t0)
        }.flatMap {
          case (Step.Advanced(next), nanos) =>
            if next.runs.size > s.maxRuns then tooMany(next)
            else
              Metrics.scanning(next)
              val logged = progressLine(next, nextLog)
              val left = unsaved + (next.scanned - p.scanned).toLong
              val saved = if left >= s.persistEvery then save(next).as(0L) else IO.pure(left)
              val nextBatch = AuditThrottle.nextBatchSize(batch, nanos).min(s.batchSize)
              saved.flatMap(l =>
                IO.sleep(AuditThrottle.delayAfter(nanos, s.readFactor)) >> loop(next, nextBatch, l, logged, t, None)
              )
          case (Step.AtHead(atHead), _)            => repairAndFinish(atHead, batch, nextLog, t)
          case (Step.Waiting(next, at, reason), _) => waitAt(next, batch, at, reason, t, waitingAt)
          case (Step.Rewound(next, lost), _)       => rewound(next, batch, lost, nextLog, t)
        }

    def progressLine(p: Progress, nextLog: BigInt): BigInt =
      if p.cursor.number >= nextLog then
        val secs = ((nowMs() - started) / 1000L).max(1L)
        log.info(
          s"[CW-RECONCILE] progress: block=${p.cursor.number} scanned=${p.scanned} differing=${p.diffBlocks} " +
            s"runs=${p.runs.size} rate=${p.scanned / BigInt(secs)}/s elapsed=${secs}s"
        )
        p.cursor.number + s.logEvery
      else nextLog

    def tooMany(p: Progress): IO[Outcome] =
      save(p) >> IO {
        log.error(
          s"[CW-RECONCILE] stopped at block ${p.cursor.number}: ${p.runs.size} distinct weight differences, over " +
            s"${s.maxRuns}. No earlier release writes that shape, so nothing is repaired and the chain-weight marker stays " +
            "off; fork choice keeps the weights as they are. Please report this with the node's logs."
        )
        Outcome.TooManyRuns(p.cursor.number, p.runs.size)
      }

    def repairAndFinish(p: Progress, batch: Int, nextLog: BigInt, t: Totals): IO[Outcome] =
      Metrics.repairing(p)
      IO.blocking(engine.repair(p, () => isCurrent)).flatMap {
        case Repaired.Superseded => IO.pure(Outcome.Superseded)
        case Repaired.Done(repaired, written, runs) =>
          val totals = t.copy(written = t.written + written, runs = t.runs + runs.size)
          runs.take(20).foreach { r =>
            val was = r.delta.fold("absent")(d => s"off by ${-d}")
            log.info(s"[CW-RECONCILE] repaired blocks ${r.from}..${r.to}: the stored weight was $was")
          }
          save(repaired) >> s.beforeLock >> importLock
            .guardAs(LockHolder)(IO.blocking(ChainInsertLock.exclusive {
              s.insideLock()
              engine.finishLocked(repaired, epoch, e)
            }))
            .flatMap {
              case Finished.Done(done, tailWritten) =>
                val elapsed = nowMs() - started
                val all = totals.written + tailWritten
                Metrics.done(all)
                log.info(
                  s"[CW-RECONCILE] done: block=${done.cursor.number} scanned=${done.scanned} runs=${totals.runs} " +
                    s"rewritten=$all (tail $tailWritten) rewinds=${totals.rewinds} elapsed=${elapsed / 1000L}s; every " +
                    "stored canonical weight is now the genesis weight plus the difficulties up to its block"
                )
                IO.pure(Outcome.Done(done.scanned, all, totals.runs, totals.rewinds, elapsed))
              case Finished.Rewound(next, lost) => rewound(next, batch, lost, nextLog, totals)
              case Finished.Superseded          => IO.pure(Outcome.Superseded)
            }
      }

    def rewound(p: Progress, batch: Int, lost: BigInt, nextLog: BigInt, t: Totals): IO[Outcome] =
      IO(
        log.warn(
          s"[CW-RECONCILE] block $lost left the canonical chain (a reorg); resuming at block ${p.cursor.number + 1}"
        )
      ) >> loop(p, batch, 0L, nextLog, t.copy(rewinds = t.rewinds + 1), None)

    def waitAt(p: Progress, batch: Int, at: BigInt, reason: String, t: Totals, waitingAt: Option[BigInt]): IO[Outcome] =
      Metrics.waiting(p)
      val say =
        if waitingAt.contains(at) then IO.unit
        else
          IO(
            log.info(
              s"[CW-RECONCILE] waiting at block $at: $reason. Checking again every ${s.waitRetry} (the header " +
                "backfill fills this)"
            )
          )
      save(p) >> say >> IO.sleep(s.waitRetry) >> loop(p, batch, 0L, p.cursor.number + s.logEvery, t, Some(at))

    IO.blocking(appState.hasChainWeightInvariant())
      .flatMap { marked =>
        if marked then IO.pure(Outcome.NotOwed)
        else
          IO.blocking(engine.load()).flatMap { p =>
            val how = if p.cursor == BeforeGenesis then "from genesis" else "resumed"
            IO(
              log.info(
                s"[CW-RECONCILE] starting at block ${p.cursor.number + 1} ($how): checking every stored canonical " +
                  "weight against genesis plus the difficulties, in the background; import is held only for the final " +
                  "tail check"
              )
            ) >> loop(p, AuditThrottle.MinBatch.max(1), 0L, p.cursor.number + s.logEvery, Totals(0L, 0, 0), None)
          }
      }
      .guarantee(IO(Metrics.idle()))

  /** Gauges: where the pass is, what it has found, and what it last repaired. */
  object Metrics extends MetricsContainer:
    private val Block = new AtomicDouble(0d)
    private val Differing = new AtomicDouble(0d)
    private val Repaired = new AtomicDouble(0d)
    // 0 idle or done, 1 scanning, 2 waiting for headers, 3 repairing and finishing.
    private val State = new AtomicDouble(0d)
    metrics.registry.gauge("chainweight.reconcile.block.gauge", Block)
    metrics.registry.gauge("chainweight.reconcile.differing.blocks.gauge", Differing)
    metrics.registry.gauge("chainweight.reconcile.repaired.blocks.gauge", Repaired)
    metrics.registry.gauge("chainweight.reconcile.state.gauge", State)

    private def at(p: Progress, state: Double): Unit =
      Block.set(p.cursor.number.toDouble)
      Differing.set(p.diffBlocks.toDouble)
      State.set(state)

    def scanning(p: Progress): Unit = at(p, 1d)
    def waiting(p: Progress): Unit = at(p, 2d)
    def repairing(p: Progress): Unit = at(p, 3d)
    def done(written: Long): Unit = Repaired.set(written.toDouble)
    def idle(): Unit = State.set(0d)
