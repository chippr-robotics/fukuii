package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.Scheduler

import scala.collection.mutable

import com.chipprbots.ethereum.utils.Logger

class SyncProgressMonitor(@annotation.unused _scheduler: Scheduler) extends Logger:

  import SNAPSyncController.*
  import SNAPSyncController.SyncPhase.*

  private var currentPhaseState: SyncPhase = Idle
  private var bytecodesDone: Boolean = false
  private var accountsSynced: Long = 0
  private var bytecodesDownloaded: Long = 0
  private var storageSlotsSynced: Long = 0
  private var nodesHealed: Long = 0
  private val startTime: Long = System.currentTimeMillis()
  private var phaseStartTime: Long = System.currentTimeMillis()

  // Whether the account range download is complete and the trie is being flushed to disk
  private var finalizingTrie: Boolean = false
  private var finalizeStartTimeMs: Long = 0

  // Estimated totals for ETA calculation (updated during sync)
  private var estimatedTotalAccounts: Long = 0
  private var estimatedTotalBytecodes: Long = 0
  private var estimatedTotalSlots: Long = 0

  // Storage contract completion tracking
  private var storageContractsCompleted: Int = 0
  private var storageContractsTotal: Int = 0

  // B-044: the highest `phaseProgress` (percent) computed in the current phase. The estimates behind the percentage are
  // pushed in rounds while the counters rise between them, so the ratio can fall at a round (a larger estimate, a
  // sampling step) even when the work did not go back. The percentage, and the Overall worm drawn from it, are held at
  // this mark within a phase. It is cleared when the phase changes and by `reset()`: a restart really does start again,
  // and a new phase is measured on its own.
  private var phaseProgressMark: Int = 0

  // Chain download tracking (parallel header/body/receipt download)
  private var chainHeaders: BigInt = BigInt(0)
  private var chainBodies: BigInt = BigInt(0)
  private var chainReceipts: BigInt = BigInt(0)
  private var chainTarget: BigInt = BigInt(0)

  // Periodic logging
  private var periodicLogTask: Option[Cancellable] = None

  // Metrics history for throughput averaging
  private val metricsWindow = 60 // 60 seconds window for averaging
  private val accountsHistory = mutable.Queue[(Long, Long)]() // (timestamp, count)
  private val bytecodesHistory = mutable.Queue[(Long, Long)]()
  private val slotsHistory = mutable.Queue[(Long, Long)]()
  private val nodesHistory = mutable.Queue[(Long, Long)]()

  /** Start periodic progress logging. */
  def startPeriodicLogging(): Unit =
    val timer = new java.util.Timer("sync-progress-monitor", true)
    timer.scheduleAtFixedRate(
      new java.util.TimerTask:
        def run(): Unit =
          try logProgress()
          catch case e: Exception => log.error(s"Progress monitor error: ${e.getMessage}", e)
      ,
      30000L,
      30000L
    )
    periodicLogTask = Some(new Cancellable:
      def cancel(): Boolean =
        timer.cancel(); true
      def isCancelled: Boolean = false
    )

  def stopPeriodicLogging(): Unit =
    periodicLogTask.foreach(_.cancel())
    periodicLogTask = None

  def setFinalizingTrie(value: Boolean): Unit = synchronized {
    finalizingTrie = value
    if value then finalizeStartTimeMs = System.currentTimeMillis()
  }

  def setBytecodeComplete(): Unit = synchronized { bytecodesDone = true }

  def reset(): Unit = synchronized {
    // A restart zeroes every counter, so the percentage and the worms start again from nothing. Say what they drop,
    // with the percentage as it stands now, not the mark, which only moves when something computes it.
    log.info(
      s"SNAP progress reset by a restart (was: phase=$currentPhaseState, accounts=$accountsSynced, " +
        s"bytecodes=$bytecodesDownloaded, slots=$storageSlotsSynced, nodes healed=$nodesHealed, " +
        s"phase progress=${currentProgress.phaseProgress}%); counters and estimates restart from zero, and the accounts " +
        s"percentage returns, at the keyspace the ranges cover, once " +
        s"${actors.AccountRangeCoordinator.KeyspaceEstimateMinAccounts} accounts have been downloaded again"
    )
    currentPhaseState = Idle
    phaseProgressMark = 0
    accountsSynced = 0
    bytecodesDownloaded = 0
    storageSlotsSynced = 0
    nodesHealed = 0
    finalizingTrie = false
    estimatedTotalAccounts = 0
    estimatedTotalBytecodes = 0
    estimatedTotalSlots = 0
    storageContractsCompleted = 0
    storageContractsTotal = 0
    chainHeaders = BigInt(0)
    chainBodies = BigInt(0)
    chainReceipts = BigInt(0)
    chainTarget = BigInt(0)
    phaseStartTime = System.currentTimeMillis()
    accountsHistory.clear()
    bytecodesHistory.clear()
    slotsHistory.clear()
    nodesHistory.clear()
  }

  def startPhase(phase: SyncPhase): Unit =
    val previousPhase = synchronized {
      val previous = currentPhaseState
      if previous != phase then
        currentPhaseState = phase
        phaseStartTime = System.currentTimeMillis()
        phaseProgressMark = 0
      previous
    }
    if previousPhase != phase then log.info(s"SNAP Sync phase transition: $previousPhase -> $phase")
    logProgress()

  def complete(): Unit =
    synchronized {
      currentPhaseState = Completed
      phaseProgressMark = 0
    }
    stopPeriodicLogging()
    log.info("SNAP Sync completed!")
    logProgress()

  def incrementAccountsSynced(count: Long): Unit = synchronized {
    accountsSynced += count
    val now = System.currentTimeMillis()
    accountsHistory.enqueue((now, accountsSynced))
    cleanupHistory(accountsHistory, now)
  }

  def incrementBytecodesDownloaded(count: Long): Unit = synchronized {
    bytecodesDownloaded += count
    val now = System.currentTimeMillis()
    bytecodesHistory.enqueue((now, bytecodesDownloaded))
    cleanupHistory(bytecodesHistory, now)
  }

  def incrementStorageSlotsSynced(count: Long): Unit = synchronized {
    storageSlotsSynced += count
    val now = System.currentTimeMillis()
    slotsHistory.enqueue((now, storageSlotsSynced))
    cleanupHistory(slotsHistory, now)
  }

  def incrementNodesHealed(count: Long): Unit = synchronized {
    nodesHealed += count
    val now = System.currentTimeMillis()
    nodesHistory.enqueue((now, nodesHealed))
    cleanupHistory(nodesHistory, now)
  }

  /** Replace the estimated totals (a zero leaves a total as it is). The totals are kept as given; the reported
    * percentage is held by `phaseProgressMark`, not by holding the estimates back.
    */
  def updateEstimates(accounts: Long = 0, bytecodes: Long = 0, slots: Long = 0): Unit = synchronized {
    if accounts > 0 then estimatedTotalAccounts = accounts
    if bytecodes > 0 then estimatedTotalBytecodes = bytecodes
    if slots > 0 then estimatedTotalSlots = slots
  }

  def getAccountsSynced: Long = synchronized(accountsSynced)

  def getStorageSlotsSynced: Long = synchronized(storageSlotsSynced)

  def updateStorageContracts(completed: Int, total: Int): Unit = synchronized {
    storageContractsCompleted = completed
    storageContractsTotal = total
  }

  def updateChainProgress(headers: BigInt, bodies: BigInt, receipts: BigInt, target: BigInt): Unit = synchronized {
    chainHeaders = headers
    chainBodies = bodies
    chainReceipts = receipts
    chainTarget = target
  }

  private def cleanupHistory(history: mutable.Queue[(Long, Long)], now: Long): Unit =
    val cutoff = now - (metricsWindow * 1000)
    while history.nonEmpty && history.head._1 < cutoff do history.dequeue()

  private def calculateRecentThroughput(history: mutable.Queue[(Long, Long)]): Double =
    if history.size < 2 then 0.0
    else
      val oldest = history.head
      val newest = history.last
      val timeDiff = (newest._1 - oldest._1) / 1000.0
      val countDiff = newest._2 - oldest._2
      if timeDiff > 0 then countDiff / timeDiff else 0.0

  def calculateETA: Option[Long] = synchronized {
    currentPhaseState match
      case AccountRangeSync if estimatedTotalAccounts > 0 =>
        val remaining = estimatedTotalAccounts - accountsSynced
        val throughput = calculateRecentThroughput(accountsHistory)
        if throughput > 0 && remaining > 0 then Some((remaining / throughput).toLong) else None

      case ByteCodeAndStorageSync if estimatedTotalSlots > 0 =>
        val remaining = estimatedTotalSlots - storageSlotsSynced
        val throughput = calculateRecentThroughput(slotsHistory)
        if throughput > 0 && remaining > 0 then Some((remaining / throughput).toLong) else None

      case _ => None
  }

  private def formatETA(seconds: Long): String =
    if seconds < 60 then s"${seconds}s"
    else if seconds < 3600 then s"${seconds / 60}m ${seconds % 60}s"
    else s"${seconds / 3600}h ${(seconds % 3600) / 60}m"

  def logProgress(): Unit = synchronized {
    val progress = currentProgress
    val etaStr = calculateETA.map(eta => s", ETA: ${formatETA(eta)}").getOrElse("")
    SNAPSyncMetrics.measure(progress)
    log.info(s"SNAP Sync Progress: ${progress.formattedString}$etaStr")
    log.info(progress.wormBlock)
  }

  def currentProgress: SyncProgress = synchronized {
    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val phaseElapsed = (System.currentTimeMillis() - phaseStartTime) / 1000.0

    val overallAccountsPerSec = if elapsed > 0 then accountsSynced / elapsed else 0
    val overallBytecodesPerSec = if elapsed > 0 then bytecodesDownloaded / elapsed else 0
    val overallSlotsPerSec = if elapsed > 0 then storageSlotsSynced / elapsed else 0
    val overallNodesPerSec = if elapsed > 0 then nodesHealed / elapsed else 0

    val recentAccountsPerSec = calculateRecentThroughput(accountsHistory)
    val recentBytecodesPerSec = calculateRecentThroughput(bytecodesHistory)
    val recentSlotsPerSec = calculateRecentThroughput(slotsHistory)
    val recentNodesPerSec = calculateRecentThroughput(nodesHistory)

    val rawPhaseProgress = currentPhaseState match
      case AccountRangeSync if estimatedTotalAccounts > 0 =>
        (accountsSynced.toDouble / estimatedTotalAccounts * 100).toInt
      // The share of contracts done: the slot estimate is only a push of `slots / completed * total`, so
      // slots / estimate equals this at a push and runs ahead of it between pushes (a large contract in flight), and
      // the slot estimate is read by others (the ETA, `eth_syncing`, the gauge) as the estimate of the total.
      case ByteCodeAndStorageSync if storageContractsTotal > 0 =>
        (storageContractsCompleted.toDouble / storageContractsTotal * 100).toInt
      case _ => 0

    // Held at the highest value computed in this phase, and never past 100 (B-044). `currentProgress` is the one place
    // that computes the percentage, and it advances the mark for every caller, the controller's own reads included, not
    // only for what is displayed: a transient high value any caller saw is held too.
    val phaseProgress = math.min(100, math.max(rawPhaseProgress, phaseProgressMark))
    phaseProgressMark = phaseProgress

    val finalizeElapsedSec =
      if finalizingTrie then ((System.currentTimeMillis() - finalizeStartTimeMs) / 1000.0).toInt else 0

    SyncProgress(
      phase = currentPhaseState,
      accountsSynced = accountsSynced,
      bytecodesDownloaded = bytecodesDownloaded,
      storageSlotsSynced = storageSlotsSynced,
      nodesHealed = nodesHealed,
      elapsedSeconds = elapsed,
      phaseElapsedSeconds = phaseElapsed,
      accountsPerSec = overallAccountsPerSec,
      bytecodesPerSec = overallBytecodesPerSec,
      slotsPerSec = overallSlotsPerSec,
      nodesPerSec = overallNodesPerSec,
      recentAccountsPerSec = recentAccountsPerSec,
      recentBytecodesPerSec = recentBytecodesPerSec,
      recentSlotsPerSec = recentSlotsPerSec,
      recentNodesPerSec = recentNodesPerSec,
      phaseProgress = phaseProgress,
      estimatedTotalAccounts = estimatedTotalAccounts,
      estimatedTotalBytecodes = estimatedTotalBytecodes,
      estimatedTotalSlots = estimatedTotalSlots,
      startTime = startTime,
      phaseStartTime = phaseStartTime,
      isFinalizingTrie = finalizingTrie,
      finalizeElapsedSeconds = finalizeElapsedSec,
      storageContractsCompleted = storageContractsCompleted,
      storageContractsTotal = storageContractsTotal,
      chainHeaders = chainHeaders,
      chainBodies = chainBodies,
      chainReceipts = chainReceipts,
      chainTarget = chainTarget,
      bytecodeComplete = bytecodesDone
    )
  }

case class SyncProgress(
    phase: SNAPSyncController.SyncPhase,
    accountsSynced: Long,
    bytecodesDownloaded: Long,
    storageSlotsSynced: Long,
    nodesHealed: Long,
    elapsedSeconds: Double,
    phaseElapsedSeconds: Double,
    accountsPerSec: Double,
    bytecodesPerSec: Double,
    slotsPerSec: Double,
    nodesPerSec: Double,
    recentAccountsPerSec: Double,
    recentBytecodesPerSec: Double,
    recentSlotsPerSec: Double,
    recentNodesPerSec: Double,
    phaseProgress: Int,
    estimatedTotalAccounts: Long,
    estimatedTotalBytecodes: Long,
    estimatedTotalSlots: Long,
    startTime: Long,
    phaseStartTime: Long,
    isFinalizingTrie: Boolean = false,
    finalizeElapsedSeconds: Int = 0,
    storageContractsCompleted: Int = 0,
    storageContractsTotal: Int = 0,
    chainHeaders: BigInt = BigInt(0),
    chainBodies: BigInt = BigInt(0),
    chainReceipts: BigInt = BigInt(0),
    chainTarget: BigInt = BigInt(0),
    bytecodeComplete: Boolean = false
):

  /** Progress over the whole SNAP run, 0 to 1: the fraction the Overall worm draws, in the log ([[wormBlock]]) and in
    * the `--tui` dashboard (spec 027), so the two never disagree.
    */
  def globalProgress: Double =
    import SNAPSyncController.*
    import SNAPSyncController.SyncPhase.*

    val stages = Vector[SyncPhase](
      AccountRangeSync,
      ByteCodeAndStorageSync,
      StateHealing,
      StateValidation,
      ChainDownloadCompletion,
      Completed
    )

    val stageIndex = stages.indexOf(phase) match
      case -1 => 0
      case i  => i

    val stageSize = if stages.size <= 1 then 1.0 else 1.0 / (stages.size - 1)
    // Past 100 the stage is full, not empty: an out-of-range percentage must not drop the worm to the stage floor.
    val withinStage = if phaseProgress > 0 then (math.min(phaseProgress, 100) / 100.0) * stageSize else 0.0

    math.max(0.0, math.min(1.0, stageIndex * stageSize + withinStage))

  private def formatCount(n: Long): String =
    if n >= 1000000 then f"${n / 1000000.0}%.1fM"
    else if n >= 1000 then f"${n / 1000.0}%.1fK"
    else n.toString

  override def toString: String =
    s"SNAP Sync Progress: phase=$phase, accounts=$accountsSynced (${accountsPerSec.toInt}/s), " +
      s"bytecodes=$bytecodesDownloaded (${bytecodesPerSec.toInt}/s), " +
      s"slots=$storageSlotsSynced (${slotsPerSec.toInt}/s), nodes=$nodesHealed (${nodesPerSec.toInt}/s), " +
      s"elapsed=${elapsedSeconds.toInt}s"

  private def chainStr: String =
    if chainTarget > 0 then
      val pct = if chainTarget > 0 then (chainHeaders * 100 / chainTarget).toInt else 0
      s" | chain: h=${formatBigInt(chainHeaders)}/${formatBigInt(chainTarget)}($pct%) b=${formatBigInt(chainBodies)} r=${formatBigInt(chainReceipts)}"
    else ""

  private def formatBigInt(n: BigInt): String =
    if n >= 1000000 then f"${(n.toDouble / 1000000)}%.1fM"
    else if n >= 1000 then f"${(n.toDouble / 1000)}%.1fK"
    else n.toString

  private def elapsedStr: String =
    val s = elapsedSeconds.toInt
    if s >= 3600 then f"${s / 3600}h${(s % 3600) / 60}%02dm"
    else if s >= 60 then s"${s / 60}m${s % 60}s"
    else s"${s}s"

  def formattedString: String =
    import SNAPSyncController.SyncPhase.*
    val chain = chainStr
    val elapsed = elapsedStr

    phase match
      case AccountRangeSync if isFinalizingTrie =>
        s"FINALIZING TRIE: flushing ${formatCount(accountsSynced)} accounts to disk (${finalizeElapsedSeconds}s)$chain | $elapsed"

      case AccountRangeSync =>
        val progressStr = if estimatedTotalAccounts > 0 then s" ${phaseProgress}%" else ""
        val totalStr = if estimatedTotalAccounts > 0 then s"/~${formatCount(estimatedTotalAccounts)}" else ""
        s"Accounts$progressStr: ${formatCount(accountsSynced)}$totalStr @ ${accountsPerSec.toInt}/s$chain | $elapsed"

      case ByteCodeAndStorageSync =>
        val contractsStr =
          if storageContractsTotal > 0 then s" (${storageContractsCompleted}/${storageContractsTotal} contracts)"
          else ""
        val codeStr =
          if bytecodeComplete then s"codes=${formatCount(bytecodesDownloaded)} \u2714"
          else s"codes=${formatCount(bytecodesDownloaded)} @ ${bytecodesPerSec.toInt}/s"
        s"Code+Storage: $codeStr, slots=${formatCount(storageSlotsSynced)} @ ${slotsPerSec.toInt}/s$contractsStr$chain | $elapsed"

      case StateHealing =>
        s"Healing: ${formatCount(nodesHealed)} nodes @ ${nodesPerSec.toInt}/s$chain | $elapsed"

      case StateValidation =>
        s"Validating state trie...$chain | $elapsed"

      case ChainDownloadCompletion =>
        val bodiesPct = if chainTarget > 0 then (chainBodies * 100 / chainTarget).toInt else 0
        val receiptsPct = if chainTarget > 0 then (chainReceipts * 100 / chainTarget).toInt else 0
        s"State done, chain download (boosted): bodies=${formatBigInt(chainBodies)}/$bodiesPct% receipts=${formatBigInt(chainReceipts)}/$receiptsPct% | $elapsed"

      case _ =>
        s"$phase$chain | $elapsed"

  def wormBlock: String =
    import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar
    import com.chipprbots.ethereum.blockchain.sync.WormToBrainBar.WormState.*
    import SNAPSyncController.SyncPhase.*

    val overallBar = s"${WormToBrainBar.renderKnown(globalProgress)} \u2014 SNAP Overall"

    val contractsStr =
      if storageContractsTotal > 0 then s" (${storageContractsCompleted}/${storageContractsTotal} contracts)"
      else ""

    // In its own phase a row draws the held percentage the log line shows, so a line and its worm never disagree.
    val accountsFraction =
      if phase == AccountRangeSync then phaseProgress / 100.0
      else if estimatedTotalAccounts > 0 then accountsSynced.toDouble / estimatedTotalAccounts
      else 0.0
    val storageFraction =
      if phase == ByteCodeAndStorageSync then phaseProgress / 100.0
      else if estimatedTotalSlots > 0 then storageSlotsSynced.toDouble / estimatedTotalSlots
      else 0.0

    val accountsBar =
      val (bar, detail) =
        if estimatedTotalAccounts > 0 then
          (
            WormToBrainBar.renderKnown(accountsFraction),
            s" \u2014 ${formatCount(accountsSynced)} / ~${formatCount(estimatedTotalAccounts)} @ ${accountsPerSec.toInt}/s"
          )
        else if accountsSynced > 0 then
          (
            WormToBrainBar.renderUnknown(Active),
            s" \u2014 ${formatCount(accountsSynced)} accounts @ ${accountsPerSec.toInt}/s"
          )
        else (WormToBrainBar.renderUnknown(Queued), "")
      s"  Accounts   $bar$detail"

    // In its own phase the bar is known from the first contract report (the line's percentage needs no slot estimate);
    // before and after it, from the slot estimate.
    val storageKnown =
      if phase == ByteCodeAndStorageSync then storageContractsTotal > 0 else estimatedTotalSlots > 0

    val storageBar =
      val (bar, detail) =
        if storageKnown then
          (
            WormToBrainBar.renderKnown(storageFraction),
            s" \u2014 ${formatCount(storageSlotsSynced)} slots$contractsStr"
          )
        else if storageSlotsSynced > 0 then
          (WormToBrainBar.renderUnknown(Active), s" \u2014 ${formatCount(storageSlotsSynced)} slots$contractsStr")
        else (WormToBrainBar.renderUnknown(Queued), "")
      s"  Storage    $bar$detail"

    val bytecodesBar =
      val (bar, detail) =
        if bytecodeComplete then (WormToBrainBar.renderUnknown(Complete), "")
        else if bytecodesDownloaded > 0 && estimatedTotalBytecodes > 0 then
          (
            WormToBrainBar.renderKnown(bytecodesDownloaded.toDouble / estimatedTotalBytecodes),
            s" \u2014 ${formatCount(bytecodesDownloaded)} bytecodes"
          )
        else if bytecodesDownloaded > 0 then
          (WormToBrainBar.renderUnknown(Active), s" \u2014 ${formatCount(bytecodesDownloaded)} bytecodes")
        else (WormToBrainBar.renderUnknown(Queued), "")
      s"  Bytecodes  $bar$detail"

    val healingBar =
      val (bar, detail) =
        if phase == Completed then (WormToBrainBar.renderUnknown(Complete), "")
        else if nodesHealed > 0 then
          (WormToBrainBar.renderUnknown(Active), s" \u2014 ${formatCount(nodesHealed)} nodes @ ${nodesPerSec.toInt}/s")
        else (WormToBrainBar.renderUnknown(Queued), "")
      s"  Healing    $bar$detail"

    s"$overallBar\n$accountsBar\n$storageBar\n$bytecodesBar\n$healingBar"
