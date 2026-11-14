package com.chipprbots.ethereum.console

import java.time.Duration

import com.chipprbots.ethereum.console.TuiText.formatBytes
import com.chipprbots.ethereum.console.TuiText.formatHashrate
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort

/** A part of the node that has a health bar (spec 029 FR-045). A part with no alert rule has no bar: a bar is the room
  * left before a rule's threshold, and the pool and the CPU have no rule to leave room before.
  */
enum Subsystem(val label: String):
  case P2p extends Subsystem("P2P")
  case Chain extends Subsystem("Chain")
  case ClLink extends Subsystem("CL link")
  case Mining extends Subsystem("Mining")
  case Rpc extends Subsystem("RPC")
  case Database extends Subsystem("DB")
  case Disk extends Subsystem("Disk")
  case Memory extends Subsystem("Memory")

/** What a check says: all well, the alert it raises, or that it cannot be told now. */
enum CheckState(val label: String):
  case Ok extends CheckState("ok")
  case Warn extends CheckState("WARN")
  case Error extends CheckState("ERROR")
  case Unknown extends CheckState("not known")

/** One alert rule, restated as data (research §8.3): what it reads, where it begins, and how much room is left.
  *
  * @param name
  *   what the rule watches, in a few words
  * @param value
  *   what it reads now, in words
  * @param threshold
  *   where the alert begins, in words
  * @param headroom
  *   the room left before the threshold: 1 at the healthy end, 0 at the threshold; None where the rule has no such
  *   measure now. A rule whose alert is raised has none left
  * @param alert
  *   the alert the rule raises now, with the text the alert row shows
  * @param known
  *   false when what the rule reads could not be read, or has not been watched long enough to say
  */
final case class HealthCheck(
    subsystem: Subsystem,
    name: String,
    value: String,
    threshold: String,
    headroom: Option[Double] = None,
    alert: Option[Alert] = None,
    known: Boolean = true
):

  /** The state is the alert's and nothing else's (FR-045): a check is in warning exactly when the alert row says so.
    */
  def state: CheckState = alert match
    case Some(Alert(Severity.Error, _)) => CheckState.Error
    case Some(_)                        => CheckState.Warn
    case None                           => if known then CheckState.Ok else CheckState.Unknown

  /** The room left: none once the alert is raised, whatever the measure reads. */
  def room: Option[Double] = if alert.isDefined then Some(0.0) else headroom.map(_.max(0.0).min(1.0))

  /** What `TuiState.healthSince` knows the check by. */
  def key: String = s"${subsystem.label}/$name"

/** A subsystem's bar: its checks, the one that sets the bar, and the room that one leaves. */
final case class HealthBar(subsystem: Subsystem, checks: Seq[HealthCheck]):

  /** The worst of its checks: an error, else a warning, else well if any check can tell, else not known. */
  def state: CheckState =
    val states = checks.map(_.state)
    if states.contains(CheckState.Error) then CheckState.Error
    else if states.contains(CheckState.Warn) then CheckState.Warn
    else if states.contains(CheckState.Ok) then CheckState.Ok
    else CheckState.Unknown

  /** The check the bar is labelled with: the worst alert raised, else the check with the least room, else the first.
    * Among equals the first in the alert row's order, so the label does not move between two checks at the same level.
    */
  def limiting: Option[HealthCheck] =
    checks
      .find(_.state == CheckState.Error)
      .orElse(checks.find(_.state == CheckState.Warn))
      .orElse(checks.flatMap(c => c.room.map(c -> _)).minByOption(_._2).map(_._1))
      .orElse(checks.headOption)

  /** The least room any of its checks leaves; None when none of them measures any now. */
  def headroom: Option[Double] = checks.flatMap(_.room).minOption

/** The health model (spec 029 FR-045, SC-011): every alert rule as a [[HealthCheck]]. The alert row is these checks'
  * alerts ([[Alerts.of]]) and the bars are these checks grouped, so a bar and the alert row cannot disagree.
  *
  * Every duration is measured on the snapshot's clock (`TuiState.now`), as the alert row's always were.
  */
object Health:

  /** The heap and the collector are judged over this much of the System view's history, and not before it has been
    * watched: the heap of a healthy JVM touches its limit between two collections, and one pause is a large share of
    * one second.
    */
  val Window: Duration = Duration.ofSeconds(60)

  /** The least of [[Window]] the history must span before the heap and the collector are judged, the fewest readings it
    * must hold, and the oldest its newest may be.
    */
  val MinWindow: Duration = Duration.ofSeconds(50)
  val MinPoints: Int = 4
  val MaxLastAge: Duration = Duration.ofSeconds(20)

  /** A reading of the database older than this says nothing of whether it takes writes now. */
  val StorageStaleAfter: Duration = Duration.ofSeconds(30)

  private def percent(share: Double): String = s"${(share * 100).round}%"

  /** The room left of a time limit: 1 with none of it used, 0 once it is used up. */
  private def left(used: Duration, limit: Duration): Double =
    if limit.isZero || limit.isNegative then 0.0
    else (1.0 - used.toMillis.toDouble / limit.toMillis).max(0.0).min(1.0)

  private def short(d: Duration): String = formatShort(d.getSeconds.max(0L))

  /** `minute` for the window once it is whole, and its length until then. */
  private def window(span: Duration): String = if span.getSeconds >= 59 then "minute" else short(span)

  /** Every check that applies to this node now, in the alert row's order (research §2.3, then spec 029's four). */
  def checks(state: TuiState): Seq[HealthCheck] =
    val snapshot = state.snapshot
    val now = state.now
    val limits = state.thresholds
    val gib = AlertThresholds.GiB
    // The alert's own condition, as spec 027 wrote it: a node not read yet raises nothing.
    val peersKnown = snapshot.forall(_.peers.value.isDefined)
    // What a check may say it knows: nothing, before the node has been read.
    val peersRead = snapshot.exists(_.peers.value.isDefined)

    val zeroPeers =
      val silent = state.zeroPeersSince.map(Duration.between(_, now))
      val raised = silent.filter(s => peersKnown && state.peerCount == 0 && s.compareTo(limits.zeroPeersAfter) > 0)
      HealthCheck(
        Subsystem.P2p,
        "peers connected",
        if !peersRead then "the peer manager has not said"
        else if state.peerCount > 0 then s"${state.peerCount} connected"
        else silent.fold("none")(s => s"none for ${short(s)}"),
        s"error after ${short(limits.zeroPeersAfter)} with none",
        headroom =
          if state.peerCount > 0 then Some(1.0)
          else silent.map(left(_, limits.zeroPeersAfter)),
        alert = raised.map(s => Alert(Severity.Error, s"0 peers for ${formatShort(s.getSeconds)}")),
        known = peersRead
      )

    def answering(subsystem: Subsystem, name: String, source: Option[Sourced[?]]): HealthCheck =
      val raised = source.collect { case Sourced.Unresponsive(reason) =>
        Alert(Severity.Error, s"$name Unresponsive ($reason)")
      }
      HealthCheck(
        subsystem,
        name,
        source match
          case None                               => "not asked yet"
          case Some(Sourced.Unresponsive(reason)) => s"not answering ($reason)"
          // It missed the last poll, which is not yet silence: said, and not called answering.
          case Some(Sourced.Stale(_, since)) => s"last answered ${short(Duration.between(since, now))} ago"
          case Some(_)                       => "answering",
        "error when it stops answering",
        headroom = Option.when(source.isDefined)(1.0),
        alert = raised,
        known = source.isDefined
      )
    val peerManager = answering(Subsystem.P2p, "peer manager", snapshot.map(_.peers))
    val syncController = answering(Subsystem.Chain, "sync controller", snapshot.map(_.sync))

    val stalled =
      val unchanged = state.lastAdvanceAt.map(Duration.between(_, now))
      HealthCheck(
        Subsystem.Chain,
        "head advancing",
        if state.blocksRemaining <= 0 then "no block remains to import"
        else
          s"head ${formatNumber(state.currentBlock)} unchanged for ${unchanged.fold("an unknown time")(short)}, " +
            s"${formatNumber(state.blocksRemaining)} remain"
        ,
        s"warn after ${short(limits.stallAfter)} unchanged with blocks remaining",
        headroom = if state.blocksRemaining <= 0 then Some(1.0) else unchanged.map(left(_, limits.stallAfter)),
        alert = Option.when(state.stalled)(
          Alert(
            Severity.Warn,
            s"sync stalled: head ${formatNumber(state.currentBlock)} unchanged for " +
              formatShort(state.lastAdvanceAt.fold(0L)(at => Duration.between(at, now).getSeconds))
          )
        ),
        // Blocks remain and nobody has seen the head move or stand: nothing is known of it yet.
        known = snapshot.isDefined && (state.blocksRemaining <= 0 || unchanged.isDefined)
      )

    // Only while SNAP counts as running (SyncSample.snapRunning): an aborted run's last sample is not a warning.
    val snapSample = snapshot.flatMap(_.sync.value).filter(_.snapRunning).flatMap(_.snapSampledAt).map { at =>
      val age = Duration.between(at, now)
      HealthCheck(
        Subsystem.Chain,
        "SNAP sample",
        s"${short(age)} old",
        s"warn when older than ${short(limits.snapSampleMaxAge)}",
        headroom = Some(left(age, limits.snapSampleMaxAge)),
        alert = Option.when(age.compareTo(limits.snapSampleMaxAge) > 0)(
          Alert(Severity.Warn, s"SNAP sample is ${formatShort(age.getSeconds)} old (the monitor samples every 30s)")
        )
      )
    }

    val reorg = snapshot.flatMap(_.local.chain).map { chain =>
      val recent = chain.reorgs.filter(r => Duration.between(r.at, now).compareTo(Alerts.ReorgAlertFor) <= 0)
      val deepest = recent.map(_.depth).maxOption
      // The newest reorg at least as deep as the threshold, while it is recent.
      val raised = chain.reorgs
        .findLast(_.depth >= limits.reorgDepth)
        .filter(r => Duration.between(r.at, now).compareTo(Alerts.ReorgAlertFor) <= 0)
        .map { r =>
          val depth = s"${r.depth}${if r.depthIsLowerBound then " or more" else ""}"
          val ago = formatShort(Duration.between(r.at, now).getSeconds.max(0L))
          Alert(Severity.Warn, s"reorg $depth blocks deep $ago ago, to ${formatNumber(r.newTip.number)}")
        }
      HealthCheck(
        Subsystem.Chain,
        "reorg depth",
        deepest.fold(s"none in the last ${short(Alerts.ReorgAlertFor)}")(d =>
          s"deepest $d block${if d == 1 then "" else "s"} in the last ${short(Alerts.ReorgAlertFor)}"
        ),
        s"warn at ${limits.reorgDepth} blocks deep",
        headroom = Some(deepest.fold(1.0)(d => 1.0 - d.toDouble / limits.reorgDepth.max(1))),
        alert = raised
      )
    }

    // The datadir's path is on the Overview's Node row; here it would push the next condition off an 80-column row.
    val disk = snapshot.flatMap(_.local.diskFree).filter(_ >= 0).map { free =>
      val total = snapshot.flatMap(_.local.diskTotal).filter(_ > limits.diskWarnBytes)
      HealthCheck(
        Subsystem.Disk,
        "free space",
        s"${formatBytes(free)} free" + total.fold("")(t => s" of ${formatBytes(t)}"),
        s"warn below ${limits.diskWarnBytes / gib}G, error below ${limits.diskErrorBytes / gib}G",
        headroom = total.map(t => (free - limits.diskWarnBytes).toDouble / (t - limits.diskWarnBytes)),
        alert =
          if free < limits.diskErrorBytes then
            Some(Alert(Severity.Error, s"disk ${formatBytes(free)} free (error < ${limits.diskErrorBytes / gib}G)"))
          else if free < limits.diskWarnBytes then
            Some(Alert(Severity.Warn, s"disk ${formatBytes(free)} free (warn < ${limits.diskWarnBytes / gib}G)"))
          else None
      )
    }

    // No peer at all is the 0-peers rule's, after its grace; this one is for a node that has some, but few.
    val fewPeers = Option.when(state.maxPeers > 0) {
      val warnAt = state.maxPeers * limits.lowPeersFraction
      HealthCheck(
        Subsystem.P2p,
        "peer count",
        if !peersRead then "the peer manager has not said" else s"${state.peerCount} of ${state.maxPeers}",
        s"warn below ${percent(limits.lowPeersFraction)} of the limit",
        // With none connected the rule above is the one that counts.
        headroom = Option.when(state.peerCount > 0 && state.maxPeers > warnAt)(
          (state.peerCount - warnAt) / (state.maxPeers - warnAt)
        ),
        alert = Option.when(state.peerCount > 0 && state.peerCount < warnAt && peersKnown)(
          Alert(Severity.Warn, s"peers ${state.peerCount}/${state.maxPeers} below ${percent(limits.lowPeersFraction)}")
        ),
        known = peersRead
      )
    }

    Seq(zeroPeers, peerManager, syncController) ++ clChecks(state) ++
      Seq(Some(stalled), snapSample, reorg, disk, fewPeers).flatten ++ miningCheck(state) ++
      memoryChecks(state) ++ rpcCheck(state) ++ databaseCheck(state)

  /** ETH (spec 027 T110), in §2.3's order. The consensus layer is silent when its last forkchoiceUpdated is older than
    * `cl-silent-after`: an error once it has been heard from, a warning if it never has and the node has been up for
    * `cl-first-call-grace` (it may simply have none yet; a JWT secret that does not match is one that calls and is
    * refused, so the count of those is in the text).
    */
  private def clChecks(state: TuiState): Seq[HealthCheck] =
    val now = state.now
    val limits = state.thresholds
    val uptime = Duration.ofSeconds(state.uptimeSeconds)
    state.snapshot.flatMap(_.local.cl).toSeq.flatMap { c =>
      val silentAlert = c.forkchoiceUpdated.flatMap { call =>
        val age = call.ageAt(now)
        Option.when(age.compareTo(limits.clSilentAfter) > 0)(
          Alert(
            Severity.Error,
            s"CL disconnected: no forkchoiceUpdated for ${formatShort(age.getSeconds)} (last ${call.status})"
          )
        )
      }
      val silent = HealthCheck(
        Subsystem.ClLink,
        "forkchoiceUpdated",
        c.forkchoiceUpdated.fold("none yet")(call => s"${short(call.ageAt(now))} ago (${call.status})"),
        s"error after ${short(limits.clSilentAfter)} without one",
        headroom = c.forkchoiceUpdated.map(call => left(call.ageAt(now), limits.clSilentAfter)),
        alert = silentAlert,
        known = c.forkchoiceUpdated.isDefined
      )
      val missing = HealthCheck(
        Subsystem.ClLink,
        "first forkchoiceUpdated",
        if c.forkchoiceUpdated.isDefined then "received" else s"none in ${short(uptime)} of running",
        s"warn after ${short(limits.clFirstCallGrace)} of running without one",
        headroom = Some(if c.forkchoiceUpdated.isDefined then 1.0 else left(uptime, limits.clFirstCallGrace)),
        alert = Option.when(c.forkchoiceUpdated.isEmpty && uptime.compareTo(limits.clFirstCallGrace) > 0) {
          val refused =
            if c.authFailures > 0 then s" (${c.authFailures} JWT failure${if c.authFailures == 1 then "" else "s"})"
            else ""
          Alert(Severity.Warn, s"no CL yet: no forkchoiceUpdated in ${formatShort(uptime.getSeconds)}$refused")
        }
      )
      // Only while the consensus layer is calling at all (a silent one has its own row), and only while a peer is ahead
      // of the node's head. A payload the node already held is answered VALID without a record (EngineApiService), so
      // "no newPayload" alone is not the consensus layer's silence: a node at its head that was fed its blocks some other
      // way would raise it for nothing. A payload never recorded counts from when the consensus layer was first seen, or
      // from the node's start if that is later: one that attaches to a node that has been up for an hour has not been
      // silent for an hour.
      val calling = c.forkchoiceUpdated.isDefined && silentAlert.isEmpty
      val since = state.clSeenSince.filter(_.isAfter(state.startTime)).getOrElse(state.startTime)
      val age = c.newPayload.fold(Duration.between(since, now))(_.ageAt(now))
      val ahead = peersAhead(state)
      val noPayload = HealthCheck(
        Subsystem.ClLink,
        "newPayload",
        if !calling then "the consensus client is not calling"
        else
          c.newPayload.fold(s"none in ${short(age)}")(_ => s"${short(age)} ago") +
            (if ahead then ", peers are ahead" else ", no peer is ahead")
        ,
        s"warn after ${short(limits.newPayloadSilentAfter)} without one while peers are ahead",
        // With no peer ahead there is nothing the consensus client owes: the rule cannot be raised.
        headroom = Option.when(calling)(if ahead then left(age, limits.newPayloadSilentAfter) else 1.0),
        alert = Option.when(calling && age.compareTo(limits.newPayloadSilentAfter) > 0 && ahead)(
          Alert(Severity.Warn, s"no newPayload for ${formatShort(age.getSeconds)} while peers are ahead")
        ),
        known = calling
      )
      Seq(silent, missing, noPayload)
    }

  /** ETC (spec 027 T111). The clock starts when the DAG is loaded (`TuiState.hashrateZeroSince`), and it is the
    * internal miner's: `mining-enabled` off is no miner to report a hashrate, whatever external miners do.
    */
  private def miningCheck(state: TuiState): Option[HealthCheck] =
    val mining = state.snapshot.flatMap(_.local.mining)
    // The clock outlives a poll that could not read the metrics, and so does the alert.
    Option.when(state.nodeSettings.miningEnabled && (mining.isDefined || state.hashrateZeroSince.isDefined)) {
      val limits = state.thresholds
      val silent = state.hashrateZeroSince.map(Duration.between(_, state.now))
      HealthCheck(
        Subsystem.Mining,
        "hashrate",
        silent match
          case Some(s) => s"none reported for ${short(s)}"
          case None =>
            mining.fold("not read")(m =>
              if m.dag.exists(_.loaded) then formatHashrate(m.hashrate) else "the DAG is not loaded yet"
            )
        ,
        s"warn after ${short(limits.hashrateZeroAfter)} at none with the DAG loaded",
        headroom = Some(silent.fold(1.0)(left(_, limits.hashrateZeroAfter))),
        alert = silent
          .filter(_.compareTo(limits.hashrateZeroAfter) > 0)
          .map(s => Alert(Severity.Warn, s"mining on, no hashrate reported for ${formatShort(s.getSeconds)}"))
      )
    }

  /** The heap and the collector (spec 029), over the last minute of the System view's history: the least the heap fell
    * to, and the collector's share of the time, from its total at the minute's two ends. Neither is judged unless the
    * history covers the minute ([[covered]]): not under a minute of watching, not across a gap the reader left.
    */
  private def memoryChecks(state: TuiState): Seq[HealthCheck] =
    val limits = state.thresholds
    val now = state.now
    (for
      local <- state.snapshot.map(_.local)
      jvm <- local.jvm
      system <- local.system
    yield
      // A point lands every five seconds: the minute's first is up to five seconds older than the minute.
      val recent =
        system.history.filter(p => Duration.between(p.at, now).compareTo(Window.plus(SystemReader.Step)) <= 0)
      val watched = covered(recent.map(_.at), now)
      val unwatched = "too few readings in the last minute to judge"
      val max = jvm.heapMax
      val lowest =
        Option.when(watched && max > 0)(recent.flatMap(_.heapUsed)).flatMap(_.minOption).map(_.toDouble / max)
      val heap = HealthCheck(
        Subsystem.Memory,
        "heap",
        lowest.fold(
          if max > 0 then s"${percent(jvm.heapUsed.toDouble / max)} of ${formatBytes(max)} now, $unwatched"
          else "the JVM gives no heap limit"
        )(low => s"never under ${percent(low)} of ${formatBytes(max)} in the last minute"),
        s"warn when above ${percent(limits.heapWarnFraction)} for a minute",
        headroom = lowest.map(low => (limits.heapWarnFraction - low) / limits.heapWarnFraction),
        alert = lowest
          .filter(_ >= limits.heapWarnFraction)
          .map(low =>
            Alert(
              Severity.Warn,
              s"heap above ${percent(low)} of ${formatBytes(max)} for a minute (warn at ${percent(limits.heapWarnFraction)})"
            )
          ),
        known = lowest.isDefined
      )
      // The pauses between the minute's first reading and its last, over the time between them: a reading that came
      // late counts for the time it covers, and one long pause is its share of the minute and no more.
      val share = for
        first <- recent.headOption
        last <- recent.lastOption
        if watched
        before <- first.gcTotalMillis
        after <- last.gcTotalMillis
        wall = Duration.between(first.at, last.at).toMillis
        if wall > 0 && after >= before
      yield ((after - before).toDouble / wall).min(1.0)
      val gc = HealthCheck(
        Subsystem.Memory,
        "garbage collection",
        share.fold(unwatched)(s => s"${percent(s)} of the last minute in pauses"),
        s"warn at ${percent(limits.gcShareWarn)} of the time",
        headroom = share.map(s => (limits.gcShareWarn - s) / limits.gcShareWarn),
        alert = share
          .filter(_ >= limits.gcShareWarn)
          .map(s =>
            Alert(Severity.Warn, s"GC pauses ${percent(s)} of the last minute (warn at ${percent(limits.gcShareWarn)})")
          ),
        known = share.isDefined
      )
      Seq(heap, gc)
    ).getOrElse(Nil)

  /** Whether readings at `times`, oldest first, cover the last minute: at least [[MinPoints]] of them, the first at
    * least [[MinWindow]] before the last, and the last no older than [[MaxLastAge]]. A dashboard that polls slower than
    * every fifteen seconds never has that many, and its heap and collector are not judged.
    */
  private[console] def covered(times: Seq[java.time.Instant], now: java.time.Instant): Boolean =
    times.size >= MinPoints && (for
      first <- times.headOption
      last <- times.lastOption
    yield Duration.between(first, last).compareTo(MinWindow) >= 0 &&
      Duration.between(last, now).compareTo(MaxLastAge) <= 0).getOrElse(false)

  /** The share of the last minute's JSON-RPC calls that failed (spec 029), once there are enough to take a share of.
    */
  private def rpcCheck(state: TuiState): Option[HealthCheck] =
    state.snapshot.flatMap(_.local.rpc).map { rpc =>
      val limits = state.thresholds
      val enough = rpc.recent.filter(_.calls >= AlertThresholds.RpcMinCalls)
      val rate = enough.flatMap(_.errorRate)
      HealthCheck(
        Subsystem.Rpc,
        "failed calls",
        rpc.recent.fold("watched under ten seconds")(w =>
          s"${formatNumber(w.errors)} of ${formatNumber(w.calls)} in the last ${window(w.span)}" +
            (if w.calls < AlertThresholds.RpcMinCalls then s", too few to judge" else "")
        ),
        s"warn at ${percent(limits.rpcErrorRateWarn)} of at least ${AlertThresholds.RpcMinCalls} calls",
        // With too few calls to take a share of, the rule cannot be raised.
        headroom = rpc.recent.map(_ => rate.fold(1.0)(r => (limits.rpcErrorRateWarn - r) / limits.rpcErrorRateWarn)),
        alert = enough.zip(rate).collect {
          case (w, r) if r >= limits.rpcErrorRateWarn =>
            Alert(
              Severity.Warn,
              s"RPC ${percent(r)} of ${formatNumber(w.calls)} calls failed in the last ${window(w.span)}"
            )
        },
        known = rpc.recent.isDefined
      )
    }

  /** Whether the database takes writes (spec 029). A database that slows writes says so and raises nothing: it is
    * catching up, and still writing.
    *
    * The reading gives way to any write, and a database that refuses writes holds its writers: the reading that found
    * it refusing may be the last for as long as it refuses. So an old reading that said "refused" keeps the alert, with
    * its age; an old one that said "accepted" says nothing of now.
    */
  private def databaseCheck(state: TuiState): Option[HealthCheck] =
    state.snapshot.flatMap(_.local.storage).map { storage =>
      val limits = state.thresholds
      val age = Duration.between(storage.at, state.now)
      val fresh = age.compareTo(StorageStaleAfter) <= 0
      val refused = storage.writeStopped.contains(true)
      val known = refused || (fresh && storage.writeStopped.isDefined)
      HealthCheck(
        Subsystem.Database,
        "writes",
        if refused && fresh then "refused, flush and compaction behind"
        else if refused then s"refused at the last reading, ${short(age)} ago; not read since"
        else if !fresh then s"not read for ${short(age)}: the database was busy at every read"
        else
          storage.writeStopped match
            case Some(_) =>
              storage.delayedWriteRate.filter(_ > 0).fold("accepted")(r => s"accepted, held to ${formatBytes(r)}/s")
            case None => "the database did not say"
        ,
        if limits.writeStall then "warn when the database refuses writes"
        else "no alert (fukuii.tui.alerts.write-stall is off)",
        headroom = Option.when(known)(if refused then 0.0 else 1.0),
        alert = Option.when(limits.writeStall && refused)(
          Alert(
            Severity.Warn,
            if fresh then "database refuses writes until flush and compaction catch up"
            else s"database refused writes ${short(age)} ago and has not been read since"
          )
        ),
        known = known
      )
    }

  /** A peer, or the sync's own target, is ahead of the node's head: the best block any handshaked peer reports, or the
    * highest block the sync knows of.
    */
  private def peersAhead(state: TuiState): Boolean =
    val peerBest =
      state.snapshot.flatMap(_.details).flatMap(_.value).fold(0L)(_.values.map(_.bestBlock).maxOption.getOrElse(0L))
    state.bestBlock.max(peerBest) > state.currentBlock

  /** A bar for each subsystem that has a check now, in the subsystems' order; none before the node has been read: a bar
    * drawn from nothing would be invented health.
    */
  def bars(state: TuiState): Seq[HealthBar] =
    if state.snapshot.isEmpty then Nil
    else
      val all = state.healthChecks
      Subsystem.values.toSeq.flatMap { s =>
        val own = all.filter(_.subsystem == s)
        Option.when(own.nonEmpty)(HealthBar(s, own))
      }

  /** Since when each check has been in its state: `before` moved on by the checks as they are now. A check that is new
    * or that changed state has been in it since `state.now`. It holds the checks there are now and no other.
    */
  def since(state: TuiState, before: Since): Since =
    state.healthChecks.map { c =>
      c.key -> before.get(c.key).filter(_._1 == c.state).getOrElse(c.state -> state.now)
    }.toMap

  /** Each check's state and since when, by [[HealthCheck.key]]. */
  type Since = Map[String, (CheckState, java.time.Instant)]
