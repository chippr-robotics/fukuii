package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.{Cancellable, Scheduler}

import scala.collection.mutable

import com.chipprbots.ethereum.utils.Logger

class SyncProgressMonitor(_scheduler: Scheduler) extends Logger {

  import SNAPSyncController._

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

  // Chain download tracking (parallel header/body/receipt download)
  private var chainHeaders: BigInt = BigInt(0)
  private var chainBodies: BigInt = BigInt(0)
  private var chainReceipts: BigInt = BigInt(0)
  private var chainTarget: BigInt = BigInt(0)

  // Periodic logging
  private var lastLogTime: Long = System.currentTimeMillis()
  private var periodicLogTask: Option[Cancellable] = None

  // Metrics history for throughput averaging
  private val metricsWindow = 60 // 60 seconds window for averaging
  private val accountsHistory = mutable.Queue[(Long, Long)]() // (timestamp, count)
  private val bytecodesHistory = mutable.Queue[(Long, Long)]()
  private val slotsHistory = mutable.Queue[(Long, Long)]()
  private val nodesHistory = mutable.Queue[(Long, Long)]()

  /** Start periodic progress logging. */
  def startPeriodicLogging(): Unit = {
    val timer = new java.util.Timer("sync-progress-monitor", true)
    timer.scheduleAtFixedRate(new java.util.TimerTask {
      def run(): Unit =
        try logProgress()
        catch { case e: Exception => log.error(s"Progress monitor error: ${e.getMessage}", e) }
    }, 30000L, 30000L)
    periodicLogTask = Some(new Cancellable {
      def cancel(): Boolean = { timer.cancel(); true }
      def isCancelled: Boolean = false
    })
  }

  def stopPeriodicLogging(): Unit = {
    periodicLogTask.foreach(_.cancel())
    periodicLogTask = None
  }

  def setFinalizingTrie(value: Boolean): Unit = synchronized {
    finalizingTrie = value
    if (value) finalizeStartTimeMs = System.currentTimeMillis()
  }

  def setBytecodeComplete(): Unit = synchronized { bytecodesDone = true }

  def reset(): Unit = synchronized {
    currentPhaseState = Idle
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
    lastLogTime = System.currentTimeMillis()
    accountsHistory.clear()
    bytecodesHistory.clear()
    slotsHistory.clear()
    nodesHistory.clear()
  }

  def startPhase(phase: SyncPhase): Unit = {
    val previousPhase = currentPhaseState
    if (previousPhase != phase) {
      currentPhaseState = phase
      phaseStartTime = System.currentTimeMillis()
      log.info(s"SNAP Sync phase transition: $previousPhase -> $phase")
    }
    logProgress()
  }

  def complete(): Unit = {
    currentPhaseState = Completed
    stopPeriodicLogging()
    log.info("SNAP Sync completed!")
    logProgress()
  }

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

  def updateEstimates(accounts: Long = 0, bytecodes: Long = 0, slots: Long = 0): Unit = synchronized {
    if (accounts > 0) estimatedTotalAccounts = accounts
    if (bytecodes > 0) estimatedTotalBytecodes = bytecodes
    if (slots > 0) estimatedTotalSlots = slots
  }

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

  private def cleanupHistory(history: mutable.Queue[(Long, Long)], now: Long): Unit = {
    val cutoff = now - (metricsWindow * 1000)
    while (history.nonEmpty && history.head._1 < cutoff)
      history.dequeue()
  }

  private def calculateRecentThroughput(history: mutable.Queue[(Long, Long)]): Double = {
    if (history.size < 2) return 0.0
    val oldest = history.head
    val newest = history.last
    val timeDiff = (newest._1 - oldest._1) / 1000.0
    val countDiff = newest._2 - oldest._2
    if (timeDiff > 0) countDiff / timeDiff else 0.0
  }

  def calculateETA: Option[Long] = synchronized {
    currentPhaseState match {
      case AccountRangeSync if estimatedTotalAccounts > 0 =>
        val remaining = estimatedTotalAccounts - accountsSynced
        val throughput = calculateRecentThroughput(accountsHistory)
        if (throughput > 0 && remaining > 0) Some((remaining / throughput).toLong) else None

      case ByteCodeAndStorageSync if estimatedTotalSlots > 0 =>
        val remaining = estimatedTotalSlots - storageSlotsSynced
        val throughput = calculateRecentThroughput(slotsHistory)
        if (throughput > 0 && remaining > 0) Some((remaining / throughput).toLong) else None

      case _ => None
    }
  }

  private def formatETA(seconds: Long): String =
    if (seconds < 60) s"${seconds}s"
    else if (seconds < 3600) s"${seconds / 60}m ${seconds % 60}s"
    else s"${seconds / 3600}h ${(seconds % 3600) / 60}m"

  def logProgress(): Unit = synchronized {
    val progress = currentProgress
    val etaStr = calculateETA.map(eta => s", ETA: ${formatETA(eta)}").getOrElse("")
    SNAPSyncMetrics.measure(progress)
    log.info(s"SNAP Sync Progress: ${progress.formattedString}$etaStr")
    lastLogTime = System.currentTimeMillis()
  }

  def currentProgress: SyncProgress = synchronized {
    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
    val phaseElapsed = (System.currentTimeMillis() - phaseStartTime) / 1000.0

    val overallAccountsPerSec = if (elapsed > 0) accountsSynced / elapsed else 0
    val overallBytecodesPerSec = if (elapsed > 0) bytecodesDownloaded / elapsed else 0
    val overallSlotsPerSec = if (elapsed > 0) storageSlotsSynced / elapsed else 0
    val overallNodesPerSec = if (elapsed > 0) nodesHealed / elapsed else 0

    val recentAccountsPerSec = calculateRecentThroughput(accountsHistory)
    val recentBytecodesPerSec = calculateRecentThroughput(bytecodesHistory)
    val recentSlotsPerSec = calculateRecentThroughput(slotsHistory)
    val recentNodesPerSec = calculateRecentThroughput(nodesHistory)

    val phaseProgress = currentPhaseState match {
      case AccountRangeSync if estimatedTotalAccounts > 0 =>
        (accountsSynced.toDouble / estimatedTotalAccounts * 100).toInt
      case ByteCodeAndStorageSync if estimatedTotalSlots > 0 =>
        (storageSlotsSynced.toDouble / estimatedTotalSlots * 100).toInt
      case _ => 0
    }

    val finalizeElapsedSec =
      if (finalizingTrie) ((System.currentTimeMillis() - finalizeStartTimeMs) / 1000.0).toInt else 0

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
) {

  private def wormChasesBrainBar: String = {
    import SNAPSyncController._

    val stages = Vector[SyncPhase](
      AccountRangeSync, ByteCodeAndStorageSync, StateHealing,
      StateValidation, ChainDownloadCompletion, Completed
    )

    val stageIndex = stages.indexOf(phase) match {
      case -1 => 0
      case i  => i
    }

    val stageSize = if (stages.size <= 1) 1.0 else 1.0 / (stages.size - 1)
    val withinStage =
      if (phaseProgress > 0 && phaseProgress <= 100) (phaseProgress / 100.0) * stageSize else 0.0

    val globalProgress = math.max(0.0, math.min(1.0, stageIndex * stageSize + withinStage))

    val trackLen = 20
    val wormPos = math.max(0, math.min(trackLen, math.round(globalProgress * trackLen).toInt))
    val filled = "=" * math.max(0, wormPos)
    val remaining = "." * (trackLen - wormPos)
    val worm = "\ud83e\udeb1"
    val brain = "\ud83e\udde0"
    s"$worm[$filled$remaining]$brain"
  }

  private def formatCount(n: Long): String =
    if (n >= 1000000) f"${n / 1000000.0}%.1fM"
    else if (n >= 1000) f"${n / 1000.0}%.1fK"
    else n.toString

  override def toString: String =
    s"SNAP Sync Progress: phase=$phase, accounts=$accountsSynced (${accountsPerSec.toInt}/s), " +
      s"bytecodes=$bytecodesDownloaded (${bytecodesPerSec.toInt}/s), " +
      s"slots=$storageSlotsSynced (${slotsPerSec.toInt}/s), nodes=$nodesHealed (${nodesPerSec.toInt}/s), " +
      s"elapsed=${elapsedSeconds.toInt}s"

  private def chainStr: String =
    if (chainTarget > 0) {
      val pct = if (chainTarget > 0) (chainHeaders * 100 / chainTarget).toInt else 0
      s" | chain: h=${formatBigInt(chainHeaders)}/${formatBigInt(chainTarget)}($pct%) b=${formatBigInt(chainBodies)} r=${formatBigInt(chainReceipts)}"
    } else ""

  private def formatBigInt(n: BigInt): String =
    if (n >= 1000000) f"${(n.toDouble / 1000000)}%.1fM"
    else if (n >= 1000) f"${(n.toDouble / 1000)}%.1fK"
    else n.toString

  private def elapsedStr: String = {
    val s = elapsedSeconds.toInt
    if (s >= 3600) f"${s / 3600}h${(s % 3600) / 60}%02dm"
    else if (s >= 60) s"${s / 60}m${s % 60}s"
    else s"${s}s"
  }

  def formattedString: String = {
    val bar = wormChasesBrainBar
    val chain = chainStr
    val elapsed = elapsedStr

    phase match {
      case SNAPSyncController.AccountRangeSync if isFinalizingTrie =>
        s"$bar FINALIZING TRIE: flushing ${formatCount(accountsSynced)} accounts to disk (${finalizeElapsedSeconds}s)$chain | $elapsed"

      case SNAPSyncController.AccountRangeSync =>
        val progressStr = if (estimatedTotalAccounts > 0) s" ${phaseProgress}%" else ""
        val totalStr = if (estimatedTotalAccounts > 0) s"/~${formatCount(estimatedTotalAccounts)}" else ""
        s"$bar Accounts$progressStr: ${formatCount(accountsSynced)}$totalStr @ ${accountsPerSec.toInt}/s$chain | $elapsed"

      case SNAPSyncController.ByteCodeAndStorageSync =>
        val contractsStr =
          if (storageContractsTotal > 0) s" (${storageContractsCompleted}/${storageContractsTotal} contracts)" else ""
        val codeStr =
          if (bytecodeComplete) s"codes=${formatCount(bytecodesDownloaded)} \u2714"
          else s"codes=${formatCount(bytecodesDownloaded)} @ ${bytecodesPerSec.toInt}/s"
        s"$bar Code+Storage: $codeStr, slots=${formatCount(storageSlotsSynced)} @ ${slotsPerSec.toInt}/s$contractsStr$chain | $elapsed"

      case SNAPSyncController.StateHealing =>
        s"$bar Healing: ${formatCount(nodesHealed)} nodes @ ${nodesPerSec.toInt}/s$chain | $elapsed"

      case SNAPSyncController.StateValidation =>
        s"$bar Validating state trie...$chain | $elapsed"

      case SNAPSyncController.ChainDownloadCompletion =>
        val bodiesPct = if (chainTarget > 0) (chainBodies * 100 / chainTarget).toInt else 0
        val receiptsPct = if (chainTarget > 0) (chainReceipts * 100 / chainTarget).toInt else 0
        s"$bar State done, chain download (boosted): bodies=${formatBigInt(chainBodies)}/$bodiesPct% receipts=${formatBigInt(chainReceipts)}/$receiptsPct% | $elapsed"

      case _ =>
        s"$bar $phase$chain | $elapsed"
    }
  }
}
