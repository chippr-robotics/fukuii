package com.chipprbots.ethereum.nodebuilder

import java.io.File
import java.lang.management.ManagementFactory

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.Props
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.network.PeerManagerActor

/** Periodic node status reporter. Logs a compact summary every 5 minutes covering
  * chain progress, peer connectivity, JVM resources, and disk usage.
  *
  * All data sources are synchronous (ManagementFactory, file system) or cached
  * (peer status cache, blockchain reader) — no heavy computation.
  */
class NodeStatusReporter(
    blockchainReader: BlockchainReader,
    syncController: ActorRef,
    peerManager: ActorRef,
    dbPath: String
) extends Actor
    with ActorLogging {

  import NodeStatusReporter._

  private val startTime = System.currentTimeMillis()

  override def preStart(): Unit = {
    context.system.scheduler.scheduleWithFixedDelay(
      ReportInterval,
      ReportInterval,
      self,
      Tick
    )(context.dispatcher)
  }

  override def receive: Receive = { case Tick =>
    reportStatus()
  }

  private def reportStatus(): Unit = {
    val uptimeMs = System.currentTimeMillis() - startTime
    val uptimeStr = formatUptime(uptimeMs)

    // Chain
    val bestBlock = blockchainReader.getBestBlockNumber()

    // JVM heap
    val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    val heapUsedMB = heap.getUsed / 1048576
    val heapMaxMB = if (heap.getMax > 0) heap.getMax / 1048576 else heap.getCommitted / 1048576
    val heapPct = if (heap.getMax > 0) heap.getUsed * 100.0 / heap.getMax else 0.0

    // GC
    val gcBeans = ManagementFactory.getGarbageCollectorMXBeans.asScala
    val totalCollections = gcBeans.map(_.getCollectionCount).sum
    val totalGcTimeMs = gcBeans.map(_.getCollectionTime).sum

    // Disk (in GB)
    val dbSizeGB = directorySize(new File(dbPath)) / (1024.0 * 1024.0 * 1024.0)

    // OS load
    val os = ManagementFactory.getOperatingSystemMXBean
    val loadAvg = os.getSystemLoadAverage
    val cpus = os.getAvailableProcessors

    // Threads
    val threadCount = ManagementFactory.getThreadMXBean.getThreadCount

    // Sync status (async, best-effort)
    implicit val timeout: Timeout = Timeout(2.seconds)
    import context.dispatcher

    val syncFuture = (syncController ? SyncProtocol.GetStatus).mapTo[SyncProtocol.Status]
    val peerFuture = (peerManager ? PeerManagerActor.GetPeers).mapTo[PeerManagerActor.Peers]

    // Collect both futures, then log
    for {
      syncStatus <- syncFuture.recover { case _ => null }
      peers <- peerFuture.recover { case _ => null }
    } {
      val syncStr = if (syncStatus != null) formatSyncStatus(syncStatus) else "unknown"
      val peerStr = if (peers != null) s"${peers.peers.size} connected" else "unknown"

      log.info(
        s"Node status (uptime $uptimeStr):\n" +
          s"  Chain:     #$bestBlock | sync: $syncStr\n" +
          s"  Peers:     $peerStr\n" +
          s"  JVM:       heap $heapUsedMB/$heapMaxMB MB (${"%.1f".format(heapPct)}%) | GC: $totalCollections collections, ${"%.1f".format(totalGcTimeMs / 1000.0)}s total\n" +
          s"  Disk:      rocksdb ${"%.1f".format(dbSizeGB)} GB\n" +
          s"  Load:      ${"%.1f".format(loadAvg)} ($cpus cores) | threads: $threadCount"
      )
    }
  }

  private def formatUptime(ms: Long): String = {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    if (hours > 0) s"${hours}h ${minutes}m" else s"${minutes}m"
  }

  private def formatSyncStatus(status: SyncProtocol.Status): String = status match {
    case SyncProtocol.Status.Syncing(_, blocksProgress, stateProgress) =>
      val blockStr = s"block ${blocksProgress.current} / ${blocksProgress.target}"
      val stateStr = stateProgress.filter(_.nonEmpty).map(p => s", state ${p.current}/${p.target}").getOrElse("")
      s"syncing ($blockStr$stateStr)"
    case SyncProtocol.Status.NotSyncing =>
      "not syncing"
    case SyncProtocol.Status.SyncDone =>
      "sync done"
  }

  private def directorySize(dir: File): Long = {
    if (!dir.exists || !dir.isDirectory) return 0L
    var size = 0L
    val files = dir.listFiles()
    if (files != null) {
      for (f <- files) {
        if (f.isFile) size += f.length()
        else if (f.isDirectory) size += directorySize(f)
      }
    }
    size
  }
}

object NodeStatusReporter {
  val ReportInterval: FiniteDuration = 5.minutes

  case object Tick

  def props(
      blockchainReader: BlockchainReader,
      syncController: ActorRef,
      peerManager: ActorRef,
      dbPath: String
  ): Props = Props(new NodeStatusReporter(blockchainReader, syncController, peerManager, dbPath))
}
