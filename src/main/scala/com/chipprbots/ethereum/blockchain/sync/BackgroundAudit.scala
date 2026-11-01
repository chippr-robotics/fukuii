package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher
import com.chipprbots.ethereum.blockchain.sync.regular.StateNodeFetcher
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditBadEntries
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCodeFilter
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCounters
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCursors
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditFrame
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditHead
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditHole
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditPosition
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditReader
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditThrottle
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditWalk
import com.chipprbots.ethereum.blockchain.sync.snap.heal.NodeFault
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** The full-trie walk, off the critical path (spec 022 T053, FR-013; research R-5f). After SNAP or a checkpoint hands a
  * node to regular sync, this looks at everything a current root reaches: every node's `keccak(bytes) = key`, every
  * account's storage trie, every account's code. What it finds missing it logs with its position and fetches, one at a
  * time, while import carries on.
  *
  * It stands apart from import on purpose. Import's on-demand fetch holds import until it returns, its one fetch slot
  * abandons the fetch in flight for another hash, and an exhausted reply there counts towards a SNAP re-sync. So this
  * has its own [[StateNodeFetcher]] and its own [[PeersClient]] over a private blacklist, spawned at its first hole,
  * and it touches neither `BlockImporter`'s counter nor its adapters (decision 2).
  *
  * '''What a clean pass means.''' Every position was read as the state stood when the walk reached it, and nothing was
  * absent or bad: a node a later root reaches and this walk did not is import's writing, or the next pass's. It is not
  * a recompute of any one root (decision 11). It says nothing of a node no current root reaches. A pass that ran to its
  * end sets both recovery flags, clean or not, since those mean a pass ran (decision 14); only a clean one moves the
  * anchor.
  *
  * '''Threads.''' Reads run in Futures on the blocking dispatcher, one batch at a time, and their results come back as
  * messages carrying the pass's generation. Every write happens on this actor (spawned on the blocking dispatcher too,
  * since a repair can wait on `commitLock`), so a Future that outlives a stop writes nothing (decision 9).
  */
object BackgroundAudit:

  /** Under the `SyncController` logger, the nearest entry the shipped `logback.xml` keeps at INFO: `logging.logs-level`
    * is ERROR and `blockchain.sync` has no entry of its own. `BackgroundAuditSpec` reads the shipped file to pin this.
    */
  val LoggerName: String = "com.chipprbots.ethereum.blockchain.sync.SyncController.BackgroundAudit"

  /** Under `fukuii.sync`: the key goes into `sync.conf` in slice 6, since a fix branch changes no resources. */
  val EnabledPath: String = "snap-sync.background-audit.enabled"

  def enabledIn(syncConfig: com.typesafe.config.Config): Boolean =
    !syncConfig.hasPath(EnabledPath) || syncConfig.getBoolean(EnabledPath)

  /** Whether this node's configuration has the audit on. Off only by an explicit `false`. */
  def enabledInNodeConfig: Boolean =
    enabledIn(com.chipprbots.ethereum.utils.Config.config.getConfig("sync"))

  /** Whether a regular-sync start runs the audit. */
  enum Gate:
    case Run
    case Disabled

    /** `pruning.mode = inmemory`: the trie store is the node cache, and the walk at every completion stays the repair.
      */
    case CannotRepair

    /** Nothing owed: a clean pass covered this pivot and no flag is down, or this node finished no SNAP or checkpoint.
      */
    case NotOwed

  def gate(enabled: Boolean, state: StateStorage, appState: AppStateStorage): Gate =
    if !enabled then Gate.Disabled
    else if !state.canRepairNodes then Gate.CannotRepair
    else if !appState.isSnapAuditOwed() then Gate.NotOwed
    else Gate.Run

  /** The numbers. `readFactor` 3 sleeps three times what a batch took, a duty cycle of 25% (decision 6). */
  final case class Settings(
      batchSize: Int = AuditWalk.MaxBatch,
      readFactor: Int = 3,
      maxQueuedHoles: Int = 4096,
      codeFilterEntries: Int = 100_000,
      pausePoll: FiniteDuration = 12.seconds,
      progressEvery: FiniteDuration = 1.minute,
      persistEvery: FiniteDuration = 3.minutes,
      passBackoff: FiniteDuration = 1.hour,
      fetchTimeout: FiniteDuration = 15.minutes,
      refreshHoleAfterBlocks: Long = 32
  )

  /** The private fetch path: a `FetchStateNode` goes in, and a reply goes to the ref it names. */
  type FetcherFactory = ActorContext[Command] => ActorRef[StateNodeFetcher.StateNodeFetcherCommand]

  final case class Deps(
      appState: AppStateStorage,
      progress: SnapSyncProgressStorage,
      state: StateStorage,
      code: EvmCodeStorage,
      reader: AuditReader,
      fetcher: FetcherFactory,
      nowMillis: () => Long = () => System.currentTimeMillis(),
      readEc: Option[ExecutionContext] = None,
      settings: Settings = Settings()
  )

  sealed trait Command

  /** What the actor has got to. */
  final case class GetStatus(replyTo: ActorRef[Status]) extends Command
  final case class Status(
      counters: AuditCounters,
      stack: Int,
      queuedHoles: Int,
      fetching: Option[ByteString],
      paused: Boolean,
      rootAbsent: Boolean,
      reading: Boolean,
      waitingForNextPass: Boolean
  )

  private case object Tick extends Command
  private case object ProgressTick extends Command
  private case object PersistTick extends Command
  private case object NextPass extends Command
  final private case class BatchDone(generation: Long, frames: Vector[AuditFrame], outcome: Try[BatchOutcome])
      extends Command
  final private case class HoleRefreshed(generation: Long, hole: AuditHole, outcome: Try[AuditWalk.Settled])
      extends Command
  final private case class FetchReply(response: BlockFetcher.FetchResponse) extends Command
  final private case class FetchTimedOut(hash: ByteString) extends Command

  private enum BatchOutcome:
    case NoHead
    case Paused(head: AuditHead, ageSeconds: Long)
    case Walked(head: AuditHead, step: AuditWalk.Step, readNanos: Long)

  private val NextStepTimer = "next-step"
  private val FetchTimer = "fetch-timeout"

  /** The node's own stores and its peer actors. */
  def forNode(
      blockchainReader: BlockchainReader,
      appState: AppStateStorage,
      state: StateStorage,
      code: EvmCodeStorage,
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: ActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig
  ): Behavior[Command] =
    apply(
      Deps(
        appState,
        new SnapSyncProgressStorage(appState.dataSource),
        state,
        code,
        new StorageReader(blockchainReader, appState, state, code),
        privateFetcher(networkPeerManager, peerEventBus, syncConfig, CacheBasedBlacklist.empty(PrivateBlacklistSize))
      )
    )

  /** What the walk reads: the hash store on both schemes (what import reads, and Path publishes into at finalisation),
    * a read-only view with nothing in its buffer, and the best block by its hash, as `getBestBlock` has it.
    */
  final class StorageReader(
      blockchainReader: BlockchainReader,
      appState: AppStateStorage,
      state: StateStorage,
      code: EvmCodeStorage
  ) extends AuditReader:
    private val store = state.getReadOnlyStorage
    override def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = store.rawNodes(hashes)
    override def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = code.multiGetNoFill(hashes)
    override def head(): Option[AuditHead] =
      val best = appState.getBestBlockInfo()
      val byHash = if best.hash.nonEmpty then blockchainReader.getBlockHeaderByHash(BlockHash(best.hash)) else None
      byHash
        .orElse(blockchainReader.getBlockHeaderByNumber(best.number))
        .map(h => AuditHead(h.number.value, h.stateRoot.value, h.unixTimestamp.toLong))

  /** Decision 2: a bad peer here is this audit's business, and its blacklist goes when it does. */
  private[sync] def privateFetcher(
      networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
      peerEventBus: ActorRef[PeerEventBusActor.Command],
      syncConfig: SyncConfig,
      blacklist: Blacklist
  ): FetcherFactory = ctx =>
    val sync = DispatcherSelector.fromConfig("sync-dispatcher")
    val backoff = SupervisorStrategy.restartWithBackoff(500.millis, 10.seconds, 0.2)
    val peersClient = ctx.spawn(
      Behaviors
        .supervise(PeersClient.behavior(networkPeerManager, peerEventBus, blacklist, syncConfig))
        .onFailure[Throwable](backoff),
      "audit-peers-client",
      sync
    )
    ctx.spawn(
      Behaviors
        .supervise(StateNodeFetcher(peersClient, syncConfig, ctx.system.ignoreRef))
        .onFailure[Throwable](backoff),
      "audit-state-node-fetcher",
      sync
    )

  private val PrivateBlacklistSize: Int = 1000

  def apply(deps: Deps): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new Impl(ctx, timers, deps).start()
      }
    }

  final private class Impl(ctx: ActorContext[Command], timers: TimerScheduler[Command], deps: Deps):
    import deps.settings

    private val log: Logger = LoggerFactory.getLogger(LoggerName)
    private val readEc: ExecutionContext =
      deps.readEc.getOrElse(ctx.system.dispatchers.lookup(DispatcherSelector.blocking()))
    private val fetchReplies: ActorRef[BlockFetcher.FetchResponse] = ctx.messageAdapter(FetchReply(_))
    private val pruneHorizon: Option[Long] = deps.state.pruningHistoryBlocks.map(h => math.max(1L, h / 2L))

    private var fetcher: Option[ActorRef[StateNodeFetcher.StateNodeFetcherCommand]] = None

    // The pass. Top of the stack is the end of the deque: the next position in key order.
    private var anchor: String = ""
    private var generation: Long = 0L
    private val stack = mutable.ArrayDeque.empty[AuditFrame]
    private var reading: Option[Vector[AuditFrame]] = None
    private val holes = mutable.Queue.empty[AuditHole]
    private val holeHashes = mutable.Set.empty[ByteString]
    private var fetching: Option[AuditHole] = None
    private var pass: Long = 0L
    private var badEntries: AuditBadEntries =
      deps.progress.readAuditBadEntries().flatMap(AuditBadEntries.decode).getOrElse(AuditBadEntries())
    private val servedBad = mutable.Set.empty[ByteString]
    private var counters: AuditCounters = AuditCounters()
    private var codeFilter: AuditCodeFilter = new AuditCodeFilter(settings.codeFilterEntries)
    private var latestHead: Option[AuditHead] = None
    private var paused: Boolean = false
    private var rootAbsent: Boolean = false
    private var passStartedMillis: Long = 0L
    private var elapsedBefore: Long = 0L
    private var saidUnservable: Boolean = false
    private var waitingForNextPass: Boolean = false
    private var persistedDirty: Boolean = false
    private var changedSincePersist: Boolean = false

    def start(): Behavior[Command] =
      timers.startTimerWithFixedDelay(ProgressTick, settings.progressEvery)
      timers.startTimerWithFixedDelay(PersistTick, settings.persistEvery)
      startPass(resume = true)
      running

    private def running: Behavior[Command] =
      Behaviors
        .receiveMessage[Command] {
          case Tick =>
            onTick()
          case BatchDone(gen, frames, outcome) if gen == generation =>
            onBatchDone(frames, outcome)
          case _: BatchDone =>
            Behaviors.same // of a pass that has ended
          case HoleRefreshed(gen, hole, outcome) if gen == generation =>
            onHoleRefreshed(hole, outcome)
          case _: HoleRefreshed =>
            Behaviors.same
          case FetchReply(BlockFetcher.FetchedStateNode(data)) =>
            onFetchReply(data.values)
          case FetchReply(_) =>
            Behaviors.same
          case FetchTimedOut(hash) =>
            if fetching.exists(_.hash == hash) then onFetchReply(Seq.empty) else Behaviors.same
          case ProgressTick =>
            logProgress()
            Behaviors.same
          case PersistTick =>
            persistIfChanged()
            Behaviors.same
          case NextPass =>
            waitingForNextPass = false
            startPass(resume = false)
            Behaviors.same
          case GetStatus(replyTo) =>
            replyTo ! Status(
              counters,
              stack.size,
              holes.size,
              fetching.map(_.hash),
              paused,
              rootAbsent,
              reading.isDefined,
              waitingForNextPass
            )
            Behaviors.same
        }
        .receiveSignal { case (_, PostStop | PreRestart) =>
          generation += 1 // anything still running reports to nobody (decision 9)
          if !waitingForNextPass && anchor.nonEmpty then
            log.info(
              s"[HEAL-AUDIT] Stopped mid-pass, with its place saved within the last ${settings.persistEvery}: $summary"
            )
          Behaviors.same
        }

    // ---- a pass ------------------------------------------------------------------------------------------------------

    private def startPass(resume: Boolean): Unit =
      generation += 1
      anchor = deps.appState.snapAuditAnchor()
      stack.clear()
      holes.clear()
      holeHashes.clear()
      fetching = None
      servedBad.clear()
      codeFilter = new AuditCodeFilter(settings.codeFilterEntries)
      saidUnservable = false
      paused = false
      rootAbsent = false
      passStartedMillis = deps.nowMillis()

      val saved =
        if resume then deps.progress.readAuditCursors().flatMap(AuditCursors.decode).filter(_.anchor == anchor)
        else None
      saved match
        case Some(place) =>
          pass = place.pass
          counters = place.counters
          elapsedBefore = place.counters.elapsedMillis
          // The saved order is walk order, first to be read first, and the top of the stack is the deque's end.
          place.positions.reverseIterator.foreach(p => stack.append(AuditFrame(p, None)))
          persistedDirty = !counters.clean
          log.info(
            s"[HEAL-AUDIT] Pass resumed for anchor $anchor at ${place.positions.size} saved position(s), each resolved " +
              s"again from the current root: ${counters.summary}"
          )
        case None =>
          pass = badEntries.lastPass + 1
          badEntries = badEntries.copy(lastPass = pass)
          deps.progress.writeAuditBadEntries(badEntries.encode)
          counters = AuditCounters()
          elapsedBefore = 0L
          stack.append(AuditFrame(AuditPosition.AccountRoot, None))
          persistedDirty = false
          log.info(s"[HEAL-AUDIT] Pass $pass started for anchor $anchor")
      // At once, so a restart from here on is this pass and not another: decision 15 counts passes.
      persist()
      ctx.self ! Tick

    private def onTick(): Behavior[Command] =
      dispatchFetch()
      if waitingForNextPass || reading.isDefined then Behaviors.same
      else if stack.nonEmpty then
        startBatch()
        Behaviors.same
      else finishIfDone()

    private def finishIfDone(): Behavior[Command] =
      if stack.nonEmpty || reading.isDefined || fetching.isDefined || holes.nonEmpty || waitingForNextPass then
        Behaviors.same
      else
        val done = withElapsed
        // A pass ran, clean or not, which is what these flags have always said (decision 14). Only the anchor says clean.
        val flags = deps.appState.recoveryDone()
        deps.progress.clearAuditCursors()
        if done.clean then
          flags.and(deps.appState.putSnapAuditCleanAnchor(anchor)).commit()
          log.info(s"[HEAL-AUDIT] Pass $pass ended clean for anchor $anchor: ${done.summary}")
          waitingForNextPass = true // nothing to say at the stop: this pass is over
          Behaviors.stopped
        else
          flags.commit()
          log.warn(
            s"[HEAL-AUDIT] Pass $pass ended, not clean: another pass in ${settings.passBackoff}, for anchor $anchor: " +
              done.summary
          )
          counters = done
          waitingForNextPass = true
          generation += 1
          timers.startSingleTimer(NextPass, settings.passBackoff)
          Behaviors.same

    private def withElapsed: AuditCounters =
      counters.copy(elapsedMillis = elapsedBefore + (deps.nowMillis() - passStartedMillis))

    // ---- reading -----------------------------------------------------------------------------------------------------

    private def startBatch(): Unit =
      val count = math.min(settings.batchSize, stack.size)
      val frames = Vector.fill(count)(stack.removeLast())
      reading = Some(frames)
      val gen = generation
      val wasPaused = paused
      val filter = codeFilter
      val reader = deps.reader
      val now = deps.nowMillis
      val horizon = pruneHorizon
      val read = Future {
        val started = System.nanoTime()
        reader.head() match
          case None => BatchOutcome.NoHead
          case Some(head) =>
            val age = now() / 1000 - head.timestampSeconds
            if AuditThrottle.paused(wasPaused, age) then BatchOutcome.Paused(head, age)
            else
              val step = AuditWalk.walkBatch(frames, head, reader, horizon, filter)
              BatchOutcome.Walked(head, step, System.nanoTime() - started)
      }(readEc)
      ctx.pipeToSelf(read)(outcome => BatchDone(gen, frames, outcome))

    private def putBack(frames: Vector[AuditFrame]): Unit =
      frames.reverseIterator.foreach(stack.append)

    private def schedule(delay: FiniteDuration): Unit =
      if delay <= Duration.Zero then ctx.self ! Tick
      else timers.startSingleTimer(NextStepTimer, Tick, delay)

    private def onBatchDone(frames: Vector[AuditFrame], outcome: Try[BatchOutcome]): Behavior[Command] =
      reading = None
      outcome match
        case Failure(e) =>
          // Nothing is known about these frames, so they stay. A store that keeps failing says so here every poll.
          putBack(frames)
          log.error(s"[HEAL-AUDIT] A read failed; trying again in ${settings.pausePoll}", e)
          schedule(settings.pausePoll)
          Behaviors.same
        case Success(BatchOutcome.NoHead) =>
          putBack(frames)
          schedule(settings.pausePoll)
          Behaviors.same
        case Success(BatchOutcome.Paused(head, age)) =>
          putBack(frames)
          latestHead = Some(head)
          if !paused then
            paused = true
            log.info(s"[HEAL-AUDIT] Paused: best block ${head.number} is ${age}s old, so import is catching up")
          schedule(settings.pausePoll)
          Behaviors.same
        case Success(BatchOutcome.Walked(head, step, readNanos)) =>
          latestHead = Some(head)
          if paused then
            paused = false
            log.info(s"[HEAL-AUDIT] Resumed at best block ${head.number}")
          applyStep(step)
          dispatchFetch()
          if step.rootAbsent then
            if !rootAbsent then
              rootAbsent = true
              log.warn(
                s"[HEAL-AUDIT] The state root of best block ${head.number} is absent. That is not a hole: waiting for " +
                  "fork recovery to rewind"
              )
            schedule(settings.pausePoll)
            Behaviors.same
          else
            rootAbsent = false
            if stack.isEmpty then finishIfDone()
            else
              schedule(AuditThrottle.delayAfter(readNanos, settings.readFactor))
              Behaviors.same

    private def applyStep(step: AuditWalk.Step): Unit =
      step.pushes.reverseIterator.foreach(stack.append)
      step.codeRead.foreach(codeFilter.markIfNew)
      counters = counters.copy(
        nodes = counters.nodes + step.nodes,
        accounts = counters.accounts + step.accounts,
        moved = counters.moved + step.moved,
        unfollowable = counters.unfollowable + step.unfollowable.size
      )
      changedSincePersist = true
      step.unfollowable.foreach { why =>
        log.error(s"[HEAL-AUDIT] Cannot follow $why: what is below it goes unseen, and this pass is not clean")
      }
      step.holes.foreach(noteHole)
      // A pass that found something must not end clean after a restart, so that much goes out at once.
      if !counters.clean && !persistedDirty then persist()

    private def noteHole(hole: AuditHole): Unit =
      if hole.fault == NodeFault.Undecodable && servedBad.contains(hole.hash) then
        // Bytes that hash right and do not decode: serving them again would go round for good. Nothing can mend this.
        counters = counters.copy(unfollowable = counters.unfollowable + 1)
        log.error(
          s"[HEAL-AUDIT] ${hole.position.describe} holds bytes that hash to ${AuditWalk.hexOf(hole.hash)} and are no " +
            "trie node, and peers serve the same. What is below it goes unseen, and this pass is not clean"
        )
      else if !holeHashes.contains(hole.hash) && !fetching.exists(_.hash == hole.hash) then
        val what = if hole.isCode then "Code of the account at" else "Node at"
        counters =
          if hole.fault.isBad then counters.copy(badNodes = counters.badNodes + 1)
          else counters.copy(holes = counters.holes + 1)
        log.warn(
          s"[HEAL-AUDIT] $what ${hole.position.describe} (${AuditWalk.hexOf(hole.hash)}) ${hole.fault.describe}, under " +
            s"root ${AuditWalk.hexOf(hole.root)} of block ${hole.rootNumber}. On a node that never lost state this may " +
            "be #1692. Import is not held for it."
        )
        if holes.size >= settings.maxQueuedHoles then
          counters = counters.copy(droppedAtCap = counters.droppedAtCap + 1)
          if counters.droppedAtCap == 1 then
            log.warn(
              s"[HEAL-AUDIT] Over ${settings.maxQueuedHoles} holes wait for a fetch: those past that are counted and " +
                "left for the next pass"
            )
        else
          holes.enqueue(hole)
          holeHashes += hole.hash

    // ---- fetching ----------------------------------------------------------------------------------------------------

    private def dispatchFetch(): Unit =
      if fetching.isEmpty && holes.nonEmpty then
        val hole = holes.dequeue()
        holeHashes -= hole.hash
        fetching = Some(hole)
        changedSincePersist = true
        val tooOld = !hole.isCode && latestHead.exists(_.number - hole.rootNumber > settings.refreshHoleAfterBlocks)
        if tooOld then refresh(hole) else sendFetch(hole)

    /** A hole that waited its turn may no longer be there, and peers serve only recent roots. */
    private def refresh(hole: AuditHole): Unit =
      val gen = generation
      val reader = deps.reader
      val horizon = pruneHorizon
      val head = latestHead.get
      val work = Future {
        AuditWalk.settle(hole.position, head, h => reader.nodes(Seq(h)).head, () => reader.head(), horizon)
      }(readEc)
      ctx.pipeToSelf(work)(outcome => HoleRefreshed(gen, hole, outcome))

    private def onHoleRefreshed(hole: AuditHole, outcome: Try[AuditWalk.Settled]): Behavior[Command] =
      fetching = None
      outcome match
        case Failure(e) =>
          log.error(s"[HEAL-AUDIT] A read failed while looking at ${hole.position.describe} again", e)
          counters = counters.copy(unresolved = counters.unresolved + 1)
        case Success(AuditWalk.Settled.Hole(fresh)) =>
          fetching = Some(fresh)
          sendFetch(fresh)
        case Success(AuditWalk.Settled.Present(hash)) =>
          stack.append(AuditFrame(hole.position, Some(hash)))
        case Success(AuditWalk.Settled.Moved(hash, position)) =>
          counters = counters.copy(moved = counters.moved + 1)
          stack.append(AuditFrame(position, Some(hash)))
        case Success(AuditWalk.Settled.Gone) =>
          counters = counters.copy(moved = counters.moved + 1)
        case Success(AuditWalk.Settled.RootAbsent | AuditWalk.Settled.Retry) =>
          // Nothing known: back to the walk, which has its own wait for this.
          stack.append(AuditFrame(hole.position, None))
      afterFetchEnded()

    private def sendFetch(hole: AuditHole): Unit =
      val target = fetcher.getOrElse {
        val spawned = deps.fetcher(ctx)
        fetcher = Some(spawned)
        spawned
      }
      timers.startSingleTimer(FetchTimer, FetchTimedOut(hole.hash), settings.fetchTimeout)
      // No fallback root: the request stays pinned to the root this position was resolved under (decision 2).
      target ! (
        if hole.isCode then StateNodeFetcher.FetchStateNode(hole.hash, fetchReplies, isByteCode = true)
        else
          StateNodeFetcher.FetchStateNode(
            hole.hash,
            fetchReplies,
            stateRoot = Some(hole.root),
            paths = Some(Seq(hole.position.pathset))
          )
      )

    private def onFetchReply(values: Seq[ByteString]): Behavior[Command] =
      fetching match
        case None => Behaviors.same // a reply after its timeout
        case Some(hole) if values.isEmpty =>
          timers.cancel(FetchTimer)
          fetching = None
          counters = counters.copy(unresolved = counters.unresolved + 1)
          changedSincePersist = true
          if hole.fault.isBad then onBadEntryUnserved(hole)
          else
            log.warn(
              s"[HEAL-AUDIT] Peers could not serve ${hole.position.describe} (${AuditWalk.hexOf(hole.hash)}). This pass " +
                "is not clean, and the next looks again. Import's retry budget is not touched"
            )
          afterFetchEnded()
        case Some(hole) =>
          values.headOption match
            case Some(bytes) if ByteString(kec256(bytes.toArray)) == hole.hash =>
              timers.cancel(FetchTimer)
              repair(hole, bytes)
              fetching = None
              changedSincePersist = true
              afterFetchEnded()
            case _ => Behaviors.same // late, for a fetch this one replaced: the timer still holds this one

    /** On the actor, not in a Future: decision 1. A store that cannot write throws, and the supervisor takes it from
      * the saved place.
      */
    private def repair(hole: AuditHole, bytes: ByteString): Unit =
      val outcome =
        if hole.isCode then deps.code.repairCode(hole.hash, bytes)
        else deps.state.repairNode(hole.hash, bytes.toArray, latestHead.fold(hole.rootNumber)(_.number))
      outcome match
        case RepairOutcome.Written =>
          counters = counters.copy(repaired = counters.repaired + 1)
          log.info(s"[HEAL-AUDIT] Repaired ${hole.position.describe} (${AuditWalk.hexOf(hole.hash)})")
        case RepairOutcome.AlreadyValid =>
          log.info(s"[HEAL-AUDIT] ${hole.position.describe} was written while its fetch ran")
        case RepairOutcome.Rejected => // the fetcher and the check above have hashed these bytes: unreachable
          counters = counters.copy(unresolved = counters.unresolved + 1)
      if hole.fault.isBad then servedBad += hole.hash
      clearBadEntry(hole.hash)
      // Whatever was missing below it comes next.
      if !hole.isCode then stack.append(AuditFrame(hole.position, Some(hole.hash)))

    private def clearBadEntry(hash: ByteString): Unit =
      if badEntries.entries.contains(hash) then
        badEntries = badEntries.cleared(hash)
        deps.progress.writeAuditBadEntries(badEntries.encode)

    /** Decision 15. A bad entry no peer could serve is counted, once per pass, and at the third pass it goes, if a read
      * now still finds it bad and the current root still needs it there. Both are asked inside the delete, under the
      * commit lock, which covers block commits and not an on-demand write, so the delete reads again after the look. On
      * the actor, as every write here is.
      */
    private def onBadEntryUnserved(hole: AuditHole): Unit =
      val (book, count) =
        badEntries.exhausted(hole.hash, pass, deps.nowMillis(), settings.passBackoff.toMillis)
      if book != badEntries then
        badEntries = book
        deps.progress.writeAuditBadEntries(badEntries.encode)
      val where = s"${hole.position.describe} (${AuditWalk.hexOf(hole.hash)})"
      count match
        case None =>
          counters = counters.copy(badOverBound = counters.badOverBound + 1)
          log.error(
            s"[HEAL-AUDIT] Peers could not serve $where, which ${hole.fault.describe}. Over " +
              s"${AuditBadEntries.MaxEntries} such entries are on record, so this one is not, and will not be deleted"
          )
        case Some(passes) if passes < AuditBadEntries.DeleteAfterPasses =>
          if !saidUnservable then
            saidUnservable = true
            log.error(
              s"[HEAL-AUDIT] Peers could not serve $where, which ${hole.fault.describe}: pass $passes of " +
                s"${AuditBadEntries.DeleteAfterPasses} before it is deleted. Nothing below it is trusted, and this pass " +
                "is not clean"
            )
        case Some(_) => deleteBadEntry(hole, where)

    private def deleteBadEntry(hole: AuditHole, where: String): Unit =
      val reader = deps.reader
      val read: ByteString => RawNodeRead = hash => reader.nodes(Seq(hash)).head
      val reached = () => reader.head().exists(head => AuditWalk.stillNeeds(hole, head.stateRoot, read))
      val outcome =
        if hole.isCode then deps.code.deleteBadCode(hole.hash, reached)
        else deps.state.deleteBadNode(hole.hash, reached)
      clearBadEntry(hole.hash)
      outcome match
        case BadEntryDelete.Deleted =>
          counters = counters.copy(deleted = counters.deleted + 1)
          log.error(
            s"[HEAL-AUDIT] Deleted the entry at $where, which ${hole.fault.describe}, after " +
              s"${AuditBadEntries.DeleteAfterPasses} passes in which no peer served it. Absence throws at execution, so " +
              "import's on-demand fetch takes it from here, and exhausting that leads to a SNAP re-sync. The next pass " +
              "treats it as a hole"
          )
        case BadEntryDelete.NoLongerBad =>
          log.info(s"[HEAL-AUDIT] $where reads as good now: nothing deleted")
          if !hole.isCode then stack.append(AuditFrame(hole.position, Some(hole.hash)))
        case BadEntryDelete.PassesKeccakElsewhere =>
          // Never a node that passes keccak. A peer serving it rewrites it in this store's format, and until then there
          // is nothing to follow.
          counters = counters.copy(unfollowable = counters.unfollowable + 1)
          log.error(
            s"[HEAL-AUDIT] $where holds bytes that hash to its key in a format this store cannot read, another pruning " +
              "mode's perhaps, so it stays. What is below it goes unseen, and this pass is not clean"
          )
        case BadEntryDelete.NotReached =>
          log.info(s"[HEAL-AUDIT] The current root no longer needs $where: nothing deleted")

    private def afterFetchEnded(): Behavior[Command] =
      dispatchFetch()
      if reading.isDefined || waitingForNextPass then Behaviors.same
      else if stack.isEmpty then finishIfDone()
      else
        if !timers.isTimerActive(NextStepTimer) then schedule(Duration.Zero)
        Behaviors.same

    // ---- what it says and keeps ---------------------------------------------------------------------------------------

    private def summary: String =
      s"${withElapsed.summary} stack=${stack.size} queuedHoles=${holes.size}"

    private def logProgress(): Unit =
      if !waitingForNextPass then
        val state = if paused then " (paused)" else if rootAbsent then " (waiting for fork recovery)" else ""
        log.info(s"[HEAL-AUDIT] Progress$state: $summary")

    private def persistIfChanged(): Unit =
      if !waitingForNextPass && changedSincePersist then persist()

    /** Walk order. Holes come first, so a resume looks at them first; code is not positional, and the next pass finds
      * it by reading the leaf.
      */
    private def persist(): Unit =
      val waiting = (fetching.iterator ++ holes.iterator).filterNot(_.isCode).map(_.position)
      val positions =
        waiting.toVector ++ reading.getOrElse(Vector.empty).map(_.position) ++ stack.reverseIterator.map(_.position)
      val saved = withElapsed
      deps.progress.writeAuditCursors(AuditCursors.encode(AuditCursors.Saved(anchor, pass, saved, positions)))
      persistedDirty = !saved.clean
      changedSincePersist = false
