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
  * '''Heights the index does not name''' (B-121). The header backfill writes a header with its number entry, so a
  * height with neither is one it has not reached yet. But a datadir written with headers and no number entries below
  * its pivot never gets them. At such a height the pass walks the parent links down from the best block to its cursor
  * ([[Bridge]]): first the difficulties, for the best block's exact weight, then down again, writing each weight that
  * differs. Its cursor is then the best block. Where the walk finds a header not stored (chain download off: none below
  * the pivot), it waits, and resumes the walk there at the next check. A header read by a hash is taken only if it
  * hashes to it. A walk that cannot go on (a header not stored, one stored under another block's hash, links that do
  * not lead to the cursor, a header gone between the two walks) waits as a height with no header does: it sleeps, and
  * each check counts. A wait that lasts `warnAfterPolls` checks is said with a WARN, again every `warnEveryPolls`
  * checks after, and the `waiting.polls` gauge counts the checks.
  *
  * '''Repair''', without any lock: each run is re-derived from its base and the headers, bottom-up, one chunk of
  * `writeChunk` weights at a time, paced like the scan. A block's exact weight depends only on its ancestry, so the
  * value written is right whether or not the block stays canonical, and writing it twice is harmless; a crash between
  * chunks costs only a re-run. A run that breaks (its next header does not link to the block below, as in a reorg that
  * flips and returns) sends the pass back to the scan below the break: what is left of the run is never taken as done.
  *
  * '''Catching up''', without any lock: the head is repaired last, and import meanwhile derives every new block from
  * the unrepaired head (during a consensus client's catch-up, tens of blocks a second). So the tail from the cursor
  * through the mapped heights is checked and rewritten the same way while it is `tailLockLimit` blocks or more, up to
  * `maxCatchUps` times.
  *
  * '''Finish''', under the import lock and then the chain insert lock held exclusively (the order every import path
  * keeps: [[ImportLock]] outermost): check the blocks imported since, and every block mapped above the best block (an
  * Engine API payload is mapped before the consensus layer promotes it), then write those that differ and the marker in
  * ONE batch. That batch is also atomic against everything that supersedes a pass ([[Epoch]]). The hold covers the few
  * blocks the catch-up left.
  *
  * '''A seeded pass.''' An operator's seed (`ChainWeightSeed`) anchors one base block whose parent has no weight, and
  * leaves its saved place there. The pass then runs from the base up and re-derives every block above it, off the
  * actor's thread, but it does not set the marker: the base is trusted, not derived from genesis. It ends
  * [[Outcome.Seeded]] with no saved place, and the next pass checks from genesis once the headers below are in.
  *
  * '''Payloads stored by hash only.''' A side payload (and on the unified layout every payload) has no number entry, so
  * the pass never sees it, and it keeps the weight its parent had when `newPayload` ran. The forkchoiceUpdated that
  * makes it canonical re-derives it from its parent's weight then (`ForkChoiceManager.rederiveWeights`, B-126), under
  * the chain insert lock (shared), so never between the finish's check and its marker.
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
    * `recent` earlier places to go back to after a reorg (oldest first), `scanned` the blocks checked so far. `seeded`:
    * the place started at an operator's seed, not at genesis.
    */
  final case class Progress(
      cursor: Position,
      runs: Vector[Run],
      recent: Vector[Position],
      scanned: BigInt,
      seeded: Boolean = false
  ):
    def diffBlocks: BigInt = runs.map(_.blocks).sum

  val Start: Progress = Progress(BeforeGenesis, Vector.empty, Vector.empty, BigInt(0))

  /** The place a seed leaves: its base, with the seeded weight. */
  def seededAt(base: Position): Progress = Progress(base, Vector.empty, Vector(base), BigInt(0), seeded = true)

  /** What one scan step did. */
  enum Step:
    case Advanced(progress: Progress)
    case AtHead(progress: Progress)

    /** Height `at` cannot be checked yet: no header (`reason` names it), or its header does not link to the one below.
      */
    case Waiting(progress: Progress, at: BigInt, reason: String)

    /** The block at `lost` is no longer canonical: back to the highest earlier place that is. */
    case Rewound(progress: Progress, lost: BigInt)

  /** A walk down the parent links from `top`, the best block when it began, past heights the index does not name:
    * `next` is the hash of the block at `nextNumber` to read next, and `sum` the difficulties of the blocks read, from
    * `top` down to the one above `next`.
    */
  final case class Bridge(top: Position, next: ByteString, nextNumber: BigInt, sum: BigInt)

  /** What one step of the walk down did. */
  enum Walked:
    case More(bridge: Bridge)

    /** The walk met the cursor: `top` with its exact weight. */
    case Linked(top: Position)

    /** The header of `bridge.next` (at `bridge.nextNumber`) is not stored. */
    case Stuck(bridge: Bridge)

    /** The walk reached the cursor's height on another block, or a header not at its height. */
    case Unlinked(at: BigInt)

    /** The header stored under the hash of the block at `at` is not that block's: it hashes to something else. */
    case Mismatched(at: BigInt)

  /** What one step of the second walk down, the one that writes, did. */
  enum Descended:
    case More(at: Position, written: Long)
    case Done(written: Long)

    /** At `at` a header it read on the first walk is gone, or not that block's (`why`), or the walk did not end on the
      * cursor. Nothing is written from that header or below it.
      */
    case Broken(at: BigInt, written: Long, why: String)

  /** Why a height cannot be checked: the index names nothing there. */
  val NoIndexEntry: String = "no canonical hash stored at this height"

  /** Where the repair is: run `run` of the progress, next height `next`, and the block below it with its exact weight.
    */
  final case class RepairPlace(run: Int, next: BigInt, prev: Position)

  /** What one repair step (one chunk) did. */
  enum Repaired:
    case More(place: RepairPlace, written: Long)
    case Done(written: Long)

    /** The run broke at `at`: its header does not link to the block below, or the height has no entry. */
    case Broken(at: BigInt, written: Long)

  /** The tail from the cursor through the contiguous, linked, mapped heights: `progress` has its cursor at the last of
    * them, `writes` are the weights that differ, `checked` how many blocks it read.
    */
  final case class Tail(progress: Progress, writes: Vector[(BlockHash, ChainWeight)], checked: Long)

  /** What the finish did. */
  enum Finished:
    /** The marker is set. `written` weights of the tail were rewritten in the same batch; `checked` blocks were read
      * under the locks.
      */
    case Done(progress: Progress, written: Long, checked: Long)

    /** A seeded pass's finish: the tail is written and the saved place goes, but no marker. */
    case Seeded(progress: Progress, written: Long, checked: Long)
    case Rewound(progress: Progress, lost: BigInt)

    /** A SNAP start, a seed or a calibration write came first: the batch was not written. */
    case Superseded

  enum Outcome:
    /** The marker stood: nothing to check. */
    case NotOwed
    case Done(scanned: BigInt, written: Long, runs: Int, rewinds: Int, elapsedMs: Long, lockedTail: Long)

    /** A seeded pass is done: every block above the seed re-derived, no marker, no saved place. */
    case Seeded(written: Long)
    case Superseded

    /** More distinct differences than `maxRuns`: not a shape an earlier release could write. Nothing is lost; the
      * marker stays off and an ERROR asks for a look.
      */
    case TooManyRuns(at: BigInt, runs: Int)

  final case class Settings(
      batchSize: Int = AuditWalk.MaxBatch,
      // Duty cycle of the scan and the repair: sleep this many times what a step took (1 is 50%;
      // AuditThrottle.delayAfter).
      readFactor: Int = 1,
      persistEvery: Long = 10_000L,
      logEvery: Long = 1_000_000L,
      keepRecent: Int = 16,
      writeChunk: Int = 10_000,
      maxRuns: Int = 10_000,
      // The finish takes the locks once the tail is under this many blocks, or after `maxCatchUps` unlocked rounds.
      tailLockLimit: Int = 1_000,
      maxCatchUps: Int = 5,
      waitRetry: FiniteDuration = 10.minutes,
      // A wait at one height said with a WARN once it has lasted this many checks (B-121).
      warnAfterPolls: Int = 6,
      // ...and again every this many checks after (144: a day at the default `waitRetry`).
      warnEveryPolls: Int = 144,
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
    s"2|${encodePosition(p.cursor)}|${p.recent.map(encodePosition).mkString(";")}|$runs|${p.scanned}|${p.seeded}"

  /** `None` for anything this version cannot read: the pass then starts again from genesis. Version 1 (dd0b3e419) had
    * no `seeded` field.
    */
  def decode(s: String): Option[Progress] =
    Try {
      def list(x: String): Vector[String] = if x.isEmpty then Vector.empty else x.split(";", -1).toVector
      def progress(cursor: String, recent: String, runs: String, scanned: String, seeded: Boolean): Progress =
        Progress(
          decodePosition(cursor),
          list(runs).map { r =>
            r.split(",", -1) match
              case Array(from, to, base, delta) =>
                Run(BigInt(from), BigInt(to), decodePosition(base), if delta == "x" then None else Some(BigInt(delta)))
              case _ => throw new IllegalArgumentException(s"bad run '$r'")
          },
          list(recent).map(decodePosition),
          BigInt(scanned),
          seeded
        )
      s.split("\\|", -1) match
        case Array("1", cursor, recent, runs, scanned) => progress(cursor, recent, runs, scanned, seeded = false)
        case Array("2", cursor, recent, runs, scanned, seeded) =>
          progress(cursor, recent, runs, scanned, seeded.toBoolean)
        case _ => throw new IllegalArgumentException("unknown version")
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

  /** The scan, the repair, the catch-up and the finish over storage, synchronous. [[run]] drives them. */
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

    /** Back to the highest earlier place still canonical and below `limit` (before genesis if none), dropping what was
      * found above it. Back before genesis, a seeded place is no longer seeded: the scan starts from genesis.
      */
    def rewind(p: Progress, limit: Option[BigInt] = None): Progress =
      val back = p.recent.reverseIterator
        .filter(pos => limit.forall(pos.number < _))
        .find(canonical)
        .getOrElse(BeforeGenesis)
      val runs =
        p.runs.filter(_.from <= back.number).map(r => if r.to > back.number then r.copy(to = back.number) else r)
      Progress(back, runs, p.recent.filter(_.number <= back.number), p.scanned, p.seeded && back != BeforeGenesis)

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
      if stop.isEmpty && hashes.size < numbers.size then stop = Some(numbers(hashes.size) -> NoIndexEntry)
      (p.copy(cursor = prev, runs = runs, scanned = p.scanned + i), stop)

    private def extend(runs: Vector[Run], n: BigInt, base: Position, delta: Option[BigInt]): Vector[Run] =
      runs.lastOption match
        case Some(last) if last.to == n - 1 && last.delta == delta => runs.init :+ last.copy(to = n)
        case _                                                     => runs :+ Run(n, n, base, delta)

    /** The start of a walk down the parent links from the best block, which the index names, above the cursor. */
    def bridgeStart(p: Progress): Option[Bridge] =
      val best = reader.getBestBlockNumber
      if best <= p.cursor.number then None
      else
        reader.getHashesByNumbersNoFill(Seq(best)).headOption.flatten.map { hash =>
          Bridge(Position(best, hash, BigInt(0)), hash, best, BigInt(0))
        }

    /** Whether `b` can go on: its top is still canonical. */
    def bridgeHolds(b: Bridge): Boolean = canonical(b.top)

    /** One step of the walk down: up to `budget` headers, summing their difficulties, until the cursor. */
    def walkDown(p: Progress, b: Bridge, budget: Int = settings.batchSize): Walked =
      var at = b
      var left = budget.max(1)
      var result: Option[Walked] = None
      while result.isEmpty do
        if at.nextNumber == p.cursor.number then
          result = Some(
            if p.cursor.number < 0 || at.next == p.cursor.hash then
              Walked.Linked(at.top.copy(td = p.cursor.td + at.sum))
            else Walked.Unlinked(at.nextNumber)
          )
        else if left == 0 then result = Some(Walked.More(at))
        else
          reader.getBlockHeadersByHashesNoFill(Seq(at.next)).headOption.flatten match
            case None                                       => result = Some(Walked.Stuck(at))
            case Some(h) if h.hash.value != at.next         => result = Some(Walked.Mismatched(at.nextNumber))
            case Some(h) if h.number.value != at.nextNumber => result = Some(Walked.Unlinked(at.nextNumber))
            case Some(h) =>
              at = at.copy(next = h.parentHash.value, nextNumber = at.nextNumber - 1, sum = at.sum + h.difficulty.value)
              left -= 1
      result.get

    /** One step of the second walk down, from `from` (its exact weight known) to the cursor: up to `batchSize` headers,
      * and the weights there that differ written in one update. Each value depends only on the block's ancestry; the
      * update is committed under the chain insert lock (shared), as import writes a weight, so it never lands inside an
      * operator chain import's block (exclusive).
      */
    def descendStep(p: Progress, from: Position): Descended =
      var at = from
      var pending = Vector.empty[(BlockHash, ChainWeight)]
      var broken: Option[String] = None
      var left = settings.batchSize.max(1)
      while broken.isEmpty && at.number > p.cursor.number && left > 0 && pending.size < settings.writeChunk do
        reader.getBlockHeadersByHashesNoFill(Seq(at.hash)).headOption.flatten match
          case None => broken = Some("its header is no longer stored")
          // a header is taken only if it hashes to the key it was read by: its parent link and difficulty are what the
          // weights below are derived from
          case Some(h) if h.hash.value != at.hash =>
            broken = Some("the header stored under its hash is another block's")
          case Some(h) if h.number.value != at.number =>
            broken = Some(s"the header stored under its hash is at height ${h.number.value}")
          case Some(h) =>
            if !reader
                .getChainWeightsByHashesNoFill(Seq(at.hash))
                .headOption
                .flatten
                .map(_.totalDifficulty.value)
                .contains(at.td)
            then pending :+= BlockHash(at.hash) -> ChainWeight.totalDifficultyOnly(at.td)
            at = Position(at.number - 1, h.parentHash.value, at.td - h.difficulty.value)
            left -= 1
      if pending.nonEmpty then
        ChainInsertLock.shared(writer.storeChainWeights(pending).commit())
        settings.afterChunk()
      val done = at.number <= p.cursor.number
      broken match
        case Some(why) => Descended.Broken(at.number, pending.size.toLong, why)
        case None if done && p.cursor.number >= 0 && (at.hash != p.cursor.hash || at.td != p.cursor.td) =>
          Descended.Broken(at.number, pending.size.toLong, "the walk did not end on the cursor")
        case None if done => Descended.Done(pending.size.toLong)
        case None         => Descended.More(at, pending.size.toLong)

    /** Where the repair of `p` starts, if it has runs. */
    def repairStart(p: Progress): Option[RepairPlace] =
      p.runs.headOption.map(r => RepairPlace(0, r.from, r.base))

    /** One repair step, with no lock: from `place`, re-derive the runs' weights along the parent links and write the
      * next chunk of up to `writeChunk` that differ. A height that does not link to the block below, or has no entry,
      * breaks the run: what is gathered so far is written, and the step reports where.
      */
    def repairStep(p: Progress, place: RepairPlace): Repaired =
      var pending = Vector.empty[(BlockHash, ChainWeight)]
      var at = place
      var broken: Option[BigInt] = None
      var more = true
      while more && broken.isEmpty && pending.size < settings.writeChunk do
        if at.run >= p.runs.size then more = false
        else
          val run = p.runs(at.run)
          if at.next > run.to then
            at = p.runs
              .lift(at.run + 1)
              .fold(RepairPlace(at.run + 1, at.next, at.prev))(r => RepairPlace(at.run + 1, r.from, r.base))
          else
            val numbers = (at.next to (at.next + settings.batchSize - 1).min(run.to)).toVector
            val hashes = reader.getHashesByNumbersNoFill(numbers).takeWhile(_.isDefined).flatten
            val headers = reader.getBlockHeadersByHashesNoFill(hashes)
            val weights = reader.getChainWeightsByHashesNoFill(hashes)
            var prev = at.prev
            var i = 0
            while broken.isEmpty && i < hashes.size && pending.size < settings.writeChunk do
              headers(i) match
                case Some(h) if numbers(i) == 0 || h.parentHash.value == prev.hash =>
                  val td = prev.td + h.difficulty.value
                  if !weights(i).map(_.totalDifficulty.value).contains(td) then
                    pending :+= BlockHash(hashes(i)) -> ChainWeight.totalDifficultyOnly(td)
                  prev = Position(numbers(i), hashes(i), td)
                  i += 1
                case _ => broken = Some(numbers(i))
            if broken.isEmpty && i == hashes.size && hashes.size < numbers.size then broken = Some(numbers(hashes.size))
            at = RepairPlace(at.run, prev.number + 1, prev)
      if pending.nonEmpty then
        writer.storeChainWeights(pending).commit()
        settings.afterChunk()
      broken match
        case Some(b)       => Repaired.Broken(b, pending.size.toLong)
        case None if !more => Repaired.Done(pending.size.toLong)
        case None          => Repaired.More(at, pending.size.toLong)

    /** The tail from the cursor through the contiguous, linked, mapped heights (past the best block: a payload the
      * consensus layer has not promoted yet is mapped already), and the weights there that differ. `None` once the
      * cursor is no longer canonical.
      */
    def tail(p: Progress): Option[Tail] =
      if !canonical(p.cursor) then None
      else
        var prev = p.cursor
        var writes = Vector.empty[(BlockHash, ChainWeight)]
        var checked = 0L
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
                  writes :+= BlockHash(hashes(i)) -> ChainWeight.totalDifficultyOnly(td)
                prev = Position(numbers(i), hashes(i), td)
                checked += 1
                i += 1
              case _ => more = false
          if hashes.size < numbers.size then more = false
        Some(Tail(p.copy(cursor = prev, scanned = p.scanned + checked), writes, checked))

    /** A catch-up round's writes, with no lock, in chunks of `writeChunk`. */
    def writeTail(writes: Vector[(BlockHash, ChainWeight)]): Unit =
      writes.grouped(settings.writeChunk.max(1)).foreach { chunk =>
        writer.storeChainWeights(chunk).commit()
        settings.afterChunk()
      }

    /** The finish. The caller holds the import lock and the chain insert lock exclusively. Checks the [[tail]], then
      * writes the weights that differ, the marker (not for a seeded pass) and the end of the saved place in one batch,
      * only while `e` is current.
      */
    def finishLocked(p: Progress, epoch: Epoch, e: Long): Finished =
      tail(p) match
        case None => Finished.Rewound(rewind(p), p.cursor.number)
        case Some(Tail(done, writes, checked)) =>
          val written = epoch.ifCurrent(e) {
            val batch = writer.storeChainWeights(writes).and(appState.removeChainWeightReconcileProgress())
            (if p.seeded then batch else batch.and(appState.markChainWeightInvariant())).commitSync()
          }
          if !written then Finished.Superseded
          else if p.seeded then Finished.Seeded(done, writes.size.toLong, checked)
          else Finished.Done(done, writes.size.toLong, checked)

  // --------------------------------------------------------------------------------------------------------- the runner

  /** The pass, start to end: nothing when the marker stands, else the scan from the saved place, the repair, the
    * catch-up and the finish under both locks. Waits (`waitRetry`) where a height cannot be checked yet. Ends
    * `Superseded` once `e` is no longer current. Every step is short (one batch or one chunk), so a cancel takes effect
    * at the next one.
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

    def timed[A](work: => A): IO[(A, Long)] = IO.blocking {
      val t0 = System.nanoTime()
      val result = work
      (result, System.nanoTime() - t0)
    }

    def pace(nanos: Long): IO[Unit] = IO.sleep(AuditThrottle.delayAfter(nanos, s.readFactor))

    final case class Totals(written: Long, runs: Int, rewinds: Int)

    // A wait at height `at`: how many checks it has lasted, and the walk down to take up again at the next (B-121).
    final case class Wait(at: BigInt, polls: Int, bridge: Option[Bridge])

    def loop(
        p: Progress,
        batch: Int,
        unsaved: Long,
        nextLog: BigInt,
        t: Totals,
        waiting: Option[Wait]
    ): IO[Outcome] =
      if !isCurrent then IO.pure(Outcome.Superseded)
      else
        timed(engine.step(p, batch)).flatMap {
          case (Step.Advanced(next), nanos) =>
            if next.runs.size > s.maxRuns then tooMany(next)
            else
              Metrics.scanning(next)
              val logged = progressLine(next, nextLog)
              val left = unsaved + (next.scanned - p.scanned).toLong
              val saved = if left >= s.persistEvery then save(next).as(0L) else IO.pure(left)
              val nextBatch = AuditThrottle.nextBatchSize(batch, nanos).min(s.batchSize)
              saved.flatMap(l => pace(nanos) >> loop(next, nextBatch, l, logged, t, None))
          case (Step.AtHead(atHead), _) =>
            Metrics.repairing(atHead)
            engine
              .repairStart(atHead)
              .fold(catchUp(atHead, 0, batch, nextLog, t))(start =>
                repair(atHead, start, batch, nextLog, t.copy(runs = t.runs + atHead.runs.size))
              )
          case (Step.Waiting(next, at, reason), _) =>
            val before = waiting.filter(_.at == at)
            if reason == NoIndexEntry then bridge(next, batch, at, reason, t, before)
            else waitAt(next, batch, at, reason, t, before, None)
          case (Step.Rewound(next, lost), _) => rewound(next, batch, lost, nextLog, t)
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
            s"${s.maxRuns}. No earlier release writes that shape, so nothing is repaired and the chain-weight marker " +
            "stays off; fork choice keeps the weights as they are. Please report this with the node's logs."
        )
        Outcome.TooManyRuns(p.cursor.number, p.runs.size)
      }

    def repair(p: Progress, place: RepairPlace, batch: Int, nextLog: BigInt, t: Totals): IO[Outcome] =
      if !isCurrent then IO.pure(Outcome.Superseded)
      else
        timed(engine.repairStep(p, place)).flatMap {
          case (Repaired.More(next, written), nanos) =>
            pace(nanos) >> repair(p, next, batch, nextLog, t.copy(written = t.written + written))
          case (Repaired.Done(written), _) =>
            p.runs.take(20).foreach { r =>
              val was = r.delta.fold("absent")(d => s"off by ${-d}")
              log.info(s"[CW-RECONCILE] repaired blocks ${r.from}..${r.to}: the stored weight was $was")
            }
            val repaired = p.copy(runs = Vector.empty)
            save(repaired) >> catchUp(repaired, 0, batch, nextLog, t.copy(written = t.written + written))
          case (Repaired.Broken(at, written), _) =>
            // Never take what is left of a run as done: back to the scan, below the break.
            val back = engine.rewind(p, Some(at))
            IO(
              log.warn(
                s"[CW-RECONCILE] the repair broke at block $at (its header does not link to the block below: the chain " +
                  s"moved under it); scanning again from block ${back.cursor.number + 1}"
              )
            ) >> loop(back, batch, 0L, nextLog, t.copy(written = t.written + written, rewinds = t.rewinds + 1), None)
        }

    def catchUp(p: Progress, round: Int, batch: Int, nextLog: BigInt, t: Totals): IO[Outcome] =
      if !isCurrent then IO.pure(Outcome.Superseded)
      else
        timed(engine.tail(p)).flatMap {
          case (None, _) => rewound(engine.rewind(p), batch, p.cursor.number, nextLog, t)
          case (Some(Tail(next, writes, checked)), nanos) if checked >= s.tailLockLimit && round < s.maxCatchUps =>
            IO(
              log.info(
                s"[CW-RECONCILE] catching up: $checked block(s) imported during the repair, ${writes.size} rewritten " +
                  s"before the final step (round ${round + 1})"
              )
            ) >> timed(engine.writeTail(writes)).flatMap { case (_, wrote) =>
              save(next) >> pace(nanos + wrote) >>
                catchUp(next, round + 1, batch, nextLog, t.copy(written = t.written + writes.size))
            }
          case (Some(_), _) => finish(p, batch, nextLog, t)
        }

    def finish(p: Progress, batch: Int, nextLog: BigInt, t: Totals): IO[Outcome] =
      s.beforeLock >> importLock
        .guardAs(LockHolder)(IO.blocking(ChainInsertLock.exclusive {
          s.insideLock()
          engine.finishLocked(p, epoch, e)
        }))
        .flatMap {
          case Finished.Done(done, tailWritten, checked) =>
            val elapsed = nowMs() - started
            val all = t.written + tailWritten
            Metrics.done(all)
            log.info(
              s"[CW-RECONCILE] done: block=${done.cursor.number} scanned=${done.scanned} runs=${t.runs} " +
                s"rewritten=$all (under the locks: $tailWritten of $checked checked) rewinds=${t.rewinds} " +
                s"elapsed=${elapsed / 1000L}s; every stored canonical weight is now the genesis weight plus the " +
                "difficulties up to its block"
            )
            IO.pure(Outcome.Done(done.scanned, all, t.runs, t.rewinds, elapsed, checked))
          case Finished.Seeded(done, tailWritten, _) =>
            val all = t.written + tailWritten
            Metrics.done(all)
            log.info(
              s"[CW-RECONCILE] seeded pass done: blocks up to ${done.cursor.number} re-derived from the operator's " +
                s"seed ($all rewritten). The marker stays off: the next pass checks every weight from genesis once the " +
                "headers below the seed are in"
            )
            IO.pure(Outcome.Seeded(all))
          case Finished.Rewound(next, lost) => rewound(next, batch, lost, nextLog, t)
          case Finished.Superseded          => IO.pure(Outcome.Superseded)
        }

    def rewound(p: Progress, batch: Int, lost: BigInt, nextLog: BigInt, t: Totals): IO[Outcome] =
      IO(
        log.warn(
          s"[CW-RECONCILE] block $lost left the canonical chain (a reorg); resuming at block ${p.cursor.number + 1}"
        )
      ) >> loop(p, batch, 0L, nextLog, t.copy(rewinds = t.rewinds + 1), None)

    /** A height the index does not name: the walk down the parent links from the best block, taken up where the last
      * check left it while its top is still canonical.
      */
    def bridge(p: Progress, batch: Int, at: BigInt, reason: String, t: Totals, before: Option[Wait]): IO[Outcome] =
      before.flatMap(_.bridge).filter(engine.bridgeHolds).orElse(engine.bridgeStart(p)) match
        case None    => waitAt(p, batch, at, reason, t, before, None)
        case Some(b) => walk(p, b, batch, at, reason, t, before)

    def walk(
        p: Progress,
        b: Bridge,
        batch: Int,
        at: BigInt,
        reason: String,
        t: Totals,
        before: Option[Wait]
    ): IO[Outcome] =
      if !isCurrent then IO.pure(Outcome.Superseded)
      else
        timed(engine.walkDown(p, b)).flatMap {
          case (Walked.More(next), nanos) => pace(nanos) >> walk(p, next, batch, at, reason, t, before)
          case (Walked.Linked(top), _) =>
            IO(
              log.info(
                s"[CW-RECONCILE] blocks ${p.cursor.number + 1}..${top.number}: the index names no block at $at; " +
                  s"checking them along the parent links down from block ${top.number}"
              )
            ) >> descend(p, top, top, batch, at, reason, t, before)
          case (Walked.Stuck(stuck), _) =>
            val why = s"$reason, and the walk down the parent links from block ${stuck.top.number} stops at block " +
              s"${stuck.nextNumber}, whose header is not stored"
            waitAt(p, batch, at, why, t, before, Some(stuck))
          case (Walked.Unlinked(n), _) =>
            waitAt(
              p,
              batch,
              at,
              s"$reason, and the parent links from the best block do not lead to it (at $n)",
              t,
              before,
              None
            )
          case (Walked.Mismatched(n), _) =>
            waitAt(
              p,
              batch,
              at,
              s"$reason, and the header stored under the hash of block $n on the walk down the parent links is another " +
                "block's (it hashes to something else)",
              t,
              before,
              None
            )
        }

    /** The second walk down, from `top` to the cursor; `at`, `reason` and `before` are the wait it began from. */
    def descend(
        p: Progress,
        top: Position,
        from: Position,
        batch: Int,
        at: BigInt,
        reason: String,
        t: Totals,
        before: Option[Wait]
    ): IO[Outcome] =
      if !isCurrent then IO.pure(Outcome.Superseded)
      else
        timed(engine.descendStep(p, from)).flatMap {
          case (Descended.More(next, written), nanos) =>
            pace(nanos) >> descend(p, top, next, batch, at, reason, t.copy(written = t.written + written), before)
          case (Descended.Done(written), _) =>
            // The cursor goes to `top` across the heights the walk checked. Where the index names a block there, the walk
            // took the one on the parent links instead, the ancestor of the best block; a stale entry there is not
            // scanned again, nor needed: the weights of the best block's ancestry are what the pass makes exact.
            val across = p.copy(
              cursor = top,
              scanned = p.scanned + (top.number - p.cursor.number),
              recent = (p.recent :+ top).takeRight(s.keepRecent)
            )
            save(across) >> loop(
              across,
              batch,
              0L,
              across.cursor.number + s.logEvery,
              t.copy(written = t.written + written),
              None
            )
          // A wait, not a retry at once: a header that is not what it should be stays so, and a walk of the whole chain
          // again without a pause would be a hot loop whose checks are never counted (vault)
          case (Descended.Broken(n, written, why), _) =>
            waitAt(
              p,
              batch,
              at,
              s"$reason, and the walk down that writes broke at block $n: $why",
              t.copy(written = t.written + written, rewinds = t.rewinds + 1),
              before,
              None
            )
        }

    def waitAt(
        p: Progress,
        batch: Int,
        at: BigInt,
        reason: String,
        t: Totals,
        before: Option[Wait],
        bridge: Option[Bridge]
    ): IO[Outcome] =
      val polls = before.fold(1)(_.polls + 1)
      Metrics.waiting(p, polls)
      val say =
        if polls == 1 then
          IO(
            log.info(
              s"[CW-RECONCILE] waiting at block $at: $reason. Checking again every ${s.waitRetry} (the header " +
                "backfill fills this)"
            )
          )
        else if polls >= s.warnAfterPolls && (polls - s.warnAfterPolls) % s.warnEveryPolls.max(1) == 0 then
          IO(
            log.warn(
              s"[CW-RECONCILE] still waiting at block $at: $reason, after $polls checks. The chain-weight marker stays " +
                "off until it is filled. The header backfill fills it; with chain download off " +
                "(sync.chain-download-enabled = false) nothing does: turn it on, or seed a trusted weight above the gap " +
                s"(-D${ChainWeightSeed.Property}). Fork choice keeps the weights as they are meanwhile"
            )
          )
        else IO(log.debug(s"[CW-RECONCILE] checking again at block $at (check $polls)"))
      save(p) >> say >> IO.sleep(s.waitRetry) >>
        loop(p, batch, 0L, p.cursor.number + s.logEvery, t, Some(Wait(at, polls, bridge)))

    IO.blocking(appState.hasChainWeightInvariant())
      .flatMap { marked =>
        if marked then IO.pure(Outcome.NotOwed)
        else
          IO.blocking(engine.load()).flatMap { p =>
            val how =
              if p.seeded then s"from the operator's seed at block ${p.cursor.number}"
              else if p.cursor == BeforeGenesis then "from genesis"
              else "resumed"
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
    // The checks a wait at one height has lasted (B-121); 0 when not waiting.
    private val WaitingPolls = new AtomicDouble(0d)
    metrics.registry.gauge("chainweight.reconcile.block.gauge", Block)
    metrics.registry.gauge("chainweight.reconcile.differing.blocks.gauge", Differing)
    metrics.registry.gauge("chainweight.reconcile.repaired.blocks.gauge", Repaired)
    metrics.registry.gauge("chainweight.reconcile.state.gauge", State)
    metrics.registry.gauge("chainweight.reconcile.waiting.polls.gauge", WaitingPolls)

    private def at(p: Progress, state: Double, polls: Int = 0): Unit =
      Block.set(p.cursor.number.toDouble)
      Differing.set(p.diffBlocks.toDouble)
      State.set(state)
      WaitingPolls.set(polls.toDouble)

    def scanning(p: Progress): Unit = at(p, 1d)
    def waiting(p: Progress, polls: Int): Unit = at(p, 2d, polls)
    def repairing(p: Progress): Unit = at(p, 3d)
    def done(written: Long): Unit = Repaired.set(written.toDouble)
    def idle(): Unit =
      State.set(0d)
      WaitingPolls.set(0d)

    /** The `waiting.polls` gauge's value. */
    def waitingPolls: Double = WaitingPolls.get()
