package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.concurrent.duration.*
import scala.util.Try

import com.google.common.util.concurrent.AtomicDouble
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.regular.ImportLock
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
  * '''Phase 1''', without any lock: a forward scan from genesis, a batch of heights at a time (number to hash, header,
  * stored weight, each one `multiGet` that does not fill the block cache). It carries the exact weight forward and
  * records where the stored one differs, as [[Run]]s of equal difference, so a jump costs one entry however many blocks
  * sit above it. Every `persistEvery` blocks its place is saved, so a stop resumes there. A height with no header (the
  * backfill has not reached it) or whose header does not link to the one below waits and retries. A reorg that moves
  * the canonical chain below the place goes back to the highest earlier place still canonical.
  *
  * '''Phase 2''', under the import lock and then the chain insert lock held exclusively (the lock order every import
  * path keeps: [[ImportLock]] outermost, see its doc): scan the blocks imported since, re-derive every weight in the
  * runs from its run's base and the headers, and write each one that differs. Every write and the marker go in ONE
  * batch up to `writeChunk` weights; more go in chunks of that size, all under the same hold of both locks, so no
  * import or block commit sees part of the repair. A crash between chunks leaves no marker, and the next pass
  * re-derives the same values (it writes only a weight that differs). Nothing else waits on this pass: import goes on
  * during phase 1.
  */
object ChainWeightReconcile:

  /** Under the `SyncController` logger, which the shipped `logback.xml` keeps at INFO (as
    * `BackgroundAudit.LoggerName`).
    */
  val LoggerName: String = "com.chipprbots.ethereum.blockchain.sync.SyncController.ChainWeightReconcile"

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

  /** A scan's place: `cursor` is the last block checked, `runs` what it found up to there, `recent` earlier places to
    * go back to after a reorg (oldest first), `scanned` the blocks checked so far.
    */
  final case class Progress(cursor: Position, runs: Vector[Run], recent: Vector[Position], scanned: BigInt):
    def diffBlocks: BigInt = runs.map(_.blocks).sum

  val Start: Progress = Progress(BeforeGenesis, Vector.empty, Vector.empty, BigInt(0))

  /** What one phase-1 step did. */
  enum Step:
    case Advanced(progress: Progress)
    case AtHead(progress: Progress)

    /** Height `at` cannot be checked yet: no header (`reason` names it), or its header does not link to the one below.
      */
    case Waiting(progress: Progress, at: BigInt, reason: String)

    /** The block at `lost` is no longer canonical: back to the highest earlier place that is. */
    case Rewound(progress: Progress, lost: BigInt)

  /** What phase 2 did. */
  enum Applied:
    /** The marker is set. `written` weights were rewritten. */
    case Done(progress: Progress, written: Long)
    case Waiting(progress: Progress, at: BigInt, reason: String)
    case Rewound(progress: Progress, lost: BigInt)

    /** A SNAP start, a seed or a calibration write came first: this pass writes nothing more and ends. */
    case Superseded

  enum Outcome:
    /** The marker stood: nothing to check. */
    case NotOwed
    case Done(scanned: BigInt, written: Long, runs: Int, rewinds: Int, elapsedMs: Long)
    case Superseded

  final case class Settings(
      batchSize: Int = 1000,
      persistEvery: Long = 10_000L,
      logEvery: Long = 1_000_000L,
      keepRecent: Int = 16,
      writeChunk: Int = 100_000,
      waitRetry: FiniteDuration = 10.minutes,
      // Test seams: run just before the import lock is requested, and inside both locks before any write.
      beforeLock: IO[Unit] = IO.unit,
      insideLock: () => Unit = () => ()
  )

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

  // --------------------------------------------------------------------------------------------------------- the engine

  /** The two phases over storage, synchronous. [[run]] drives them. */
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

    /** One batch of phase 1: up to `batchSize` heights above the cursor, never past the best block. */
    def step(p: Progress): Step =
      if !canonical(p.cursor) then Step.Rewound(rewind(p), p.cursor.number)
      else
        val best = reader.getBestBlockNumber
        if p.cursor.number >= best then Step.AtHead(p)
        else check(p, p.cursor.number + 1, (p.cursor.number + settings.batchSize).min(best))

    private def check(p: Progress, from: BigInt, to: BigInt): Step =
      val numbers = (from to to).toVector
      val hashes = reader.getHashesByNumbersNoFill(numbers).takeWhile(_.isDefined).flatten
      val headers = reader.getBlockHeadersByHashesNoFill(hashes)
      val weights = reader.getChainWeightsByHashesNoFill(hashes)
      var prev = p.cursor
      var runs = p.runs
      var i = 0
      var waiting: Option[(BigInt, String)] = None
      while waiting.isEmpty && i < hashes.size do
        val n = numbers(i)
        headers(i) match
          case None => waiting = Some(n -> "no header stored for the canonical hash")
          case Some(h) if n > 0 && h.parentHash.value != prev.hash =>
            waiting = Some(n -> "its header does not link to the canonical block below")
          case Some(h) =>
            val td = prev.td + h.difficulty.value
            val stored = weights(i).map(_.totalDifficulty.value)
            if !stored.contains(td) then runs = extend(runs, n, prev, stored.map(td - _))
            prev = Position(n, hashes(i), td)
            i += 1
      if waiting.isEmpty && hashes.size < numbers.size then
        waiting = Some(numbers(hashes.size) -> "no canonical hash stored at this height")
      val next = p.copy(cursor = prev, runs = runs, scanned = p.scanned + i)
      waiting match
        case Some((at, reason)) => Step.Waiting(next, at, reason)
        case None => Step.Advanced(next.copy(recent = (next.recent :+ prev).takeRight(settings.keepRecent)))

    private def extend(runs: Vector[Run], n: BigInt, base: Position, delta: Option[BigInt]): Vector[Run] =
      runs.lastOption match
        case Some(last) if last.to == n - 1 && last.delta == delta => runs.init :+ last.copy(to = n)
        case _                                                     => runs :+ Run(n, n, base, delta)

    /** Phase 2. The caller holds the import lock and the chain insert lock exclusively. `isCurrent` is false once a
      * SNAP start, a seed or a calibration write has superseded this pass (they bump it under the chain insert lock, so
      * it cannot change while this runs).
      */
    def applyLocked(start: Progress, isCurrent: () => Boolean): Applied =
      if !isCurrent() then Applied.Superseded
      else
        var p = start
        var result: Option[Applied] = None
        while result.isEmpty do
          step(p) match
            case Step.Advanced(next)            => p = next
            case Step.AtHead(atHead)            => result = Some(write(atHead))
            case Step.Waiting(next, at, reason) => result = Some(Applied.Waiting(next, at, reason))
            case Step.Rewound(next, lost)       => result = Some(Applied.Rewound(next, lost))
        result.getOrElse(Applied.Superseded)

    private def write(p: Progress): Applied =
      p.runs.find(r => !canonical(r.base)) match
        case Some(r) => Applied.Rewound(rewind(p), r.base.number)
        case None =>
          var pending = Vector.empty[(BlockHash, ChainWeight)]
          var written = 0L
          var failure: Option[Applied] = None
          val runs = p.runs.iterator
          while failure.isEmpty && runs.hasNext do
            val run = runs.next()
            var prev = run.base
            var n = run.from
            while failure.isEmpty && n <= run.to do
              val numbers = (n to (n + settings.batchSize - 1).min(run.to)).toVector
              val hashes = reader.getHashesByNumbersNoFill(numbers)
              if hashes.exists(_.isEmpty) then failure = Some(Applied.Rewound(rewind(p), n))
              else
                val hs = hashes.flatten
                val headers = reader.getBlockHeadersByHashesNoFill(hs)
                val weights = reader.getChainWeightsByHashesNoFill(hs)
                var i = 0
                while failure.isEmpty && i < hs.size do
                  headers(i) match
                    case Some(h) if numbers(i) == 0 || h.parentHash.value == prev.hash =>
                      val td = prev.td + h.difficulty.value
                      if !weights(i).map(_.totalDifficulty.value).contains(td) then
                        pending :+= BlockHash(hs(i)) -> ChainWeight.totalDifficultyOnly(td)
                      prev = Position(numbers(i), hs(i), td)
                      i += 1
                    case _ => failure = Some(Applied.Rewound(rewind(p), numbers(i)))
                if pending.size >= settings.writeChunk then
                  writer.storeChainWeights(pending).commit()
                  written += pending.size
                  pending = Vector.empty
                n = numbers.last + 1
          failure.getOrElse {
            writer
              .storeChainWeights(pending)
              .and(appState.markChainWeightInvariant())
              .and(appState.removeChainWeightReconcileProgress())
              .commitSync()
            Applied.Done(p, written + pending.size)
          }

  // --------------------------------------------------------------------------------------------------------- the runner

  /** The pass, start to end: nothing when the marker stands, else phase 1 from the saved place and phase 2 under both
    * locks. Waits (`waitRetry`) where a height cannot be checked yet. Ends `Superseded` once `isCurrent` turns false.
    */
  def run(
      engine: Engine,
      importLock: ImportLock,
      isCurrent: () => Boolean,
      appState: AppStateStorage,
      log: Logger = LoggerFactory.getLogger(LoggerName),
      nowMs: () => Long = () => System.currentTimeMillis()
  ): IO[Outcome] =
    val s = engine.settings
    val started = nowMs()

    def save(p: Progress): IO[Unit] = IO.blocking(if isCurrent() then engine.save(p))

    def locked(p: Progress): IO[Applied] =
      s.beforeLock >> importLock.guard(
        IO.blocking(ChainInsertLock.exclusive {
          s.insideLock()
          engine.applyLocked(p, isCurrent)
        })
      )

    def loop(p: Progress, sinceSave: Long, nextLog: BigInt, rewinds: Int, waitingAt: Option[BigInt]): IO[Outcome] =
      if !isCurrent() then IO.pure(Outcome.Superseded)
      else
        IO.blocking(engine.step(p)).flatMap {
          case Step.Advanced(next) =>
            val unsaved = sinceSave + (next.scanned - p.scanned).toLong
            Metrics.scanning(next)
            val logged =
              if next.cursor.number >= nextLog then
                val secs = ((nowMs() - started) / 1000L).max(1L)
                log.info(
                  s"[CW-RECONCILE] progress: block=${next.cursor.number} scanned=${next.scanned} " +
                    s"differing=${next.diffBlocks} runs=${next.runs.size} rate=${next.scanned / BigInt(secs)}/s " +
                    s"elapsed=${secs}s"
                )
                next.cursor.number + s.logEvery
              else nextLog
            (if unsaved >= s.persistEvery then save(next).as(0L) else IO.pure(unsaved))
              .flatMap(left => IO.cede >> loop(next, left, logged, rewinds, None))

          case Step.AtHead(atHead) =>
            Metrics.applying(atHead)
            save(atHead) >> locked(atHead).flatMap {
              case Applied.Done(done, written) =>
                val elapsed = nowMs() - started
                Metrics.done(written)
                log.info(
                  s"[CW-RECONCILE] done: block=${done.cursor.number} scanned=${done.scanned} " +
                    s"differing=${done.diffBlocks} runs=${done.runs.size} rewritten=$written rewinds=$rewinds " +
                    s"elapsed=${elapsed / 1000L}s; every stored canonical weight is now the genesis weight plus the " +
                    "difficulties up to its block"
                )
                done.runs.take(20).foreach { r =>
                  val was = r.delta.fold("absent")(d => s"off by ${-d}")
                  log.info(s"[CW-RECONCILE] repaired blocks ${r.from}..${r.to}: the stored weight was $was")
                }
                IO.pure(Outcome.Done(done.scanned, written, done.runs.size, rewinds, elapsed))
              case Applied.Waiting(next, at, reason) => waitAt(next, at, reason, rewinds, waitingAt)
              case Applied.Rewound(next, lost)       => rewound(next, lost, nextLog, rewinds)
              case Applied.Superseded                => IO.pure(Outcome.Superseded)
            }

          case Step.Waiting(next, at, reason) => waitAt(next, at, reason, rewinds, waitingAt)
          case Step.Rewound(next, lost)       => rewound(next, lost, nextLog, rewinds)
        }

    def rewound(p: Progress, lost: BigInt, nextLog: BigInt, rewinds: Int): IO[Outcome] =
      IO(
        log.warn(
          s"[CW-RECONCILE] block $lost left the canonical chain (a reorg); resuming at block ${p.cursor.number + 1}"
        )
      ) >> loop(p, 0L, nextLog, rewinds + 1, None)

    def waitAt(p: Progress, at: BigInt, reason: String, rewinds: Int, waitingAt: Option[BigInt]): IO[Outcome] =
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
      save(p) >> say >> IO.sleep(s.waitRetry) >> loop(p, 0L, p.cursor.number + s.logEvery, rewinds, Some(at))

    IO.blocking(appState.hasChainWeightInvariant())
      .flatMap { marked =>
        if marked then IO.pure(Outcome.NotOwed)
        else
          IO.blocking(engine.load()).flatMap { p =>
            val how = if p.cursor == BeforeGenesis then "from genesis" else "resumed"
            IO(
              log.info(
                s"[CW-RECONCILE] starting at block ${p.cursor.number + 1} ($how): checking every stored canonical weight " +
                  "against genesis plus the difficulties, in the background; import is held only for the final repair"
              )
            ) >> loop(p, 0L, p.cursor.number + s.logEvery, 0, None)
          }
      }
      .guarantee(IO(Metrics.idle()))

  /** Gauges: where the pass is, what it has found, and what it last repaired. */
  object Metrics extends MetricsContainer:
    private val Block = new AtomicDouble(0d)
    private val Differing = new AtomicDouble(0d)
    private val Repaired = new AtomicDouble(0d)
    // 0 idle or done, 1 scanning, 2 waiting for headers, 3 repairing under the locks.
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
    def applying(p: Progress): Unit = at(p, 3d)
    def done(written: Long): Unit = Repaired.set(written.toDouble)
    def idle(): Unit = State.set(0d)
