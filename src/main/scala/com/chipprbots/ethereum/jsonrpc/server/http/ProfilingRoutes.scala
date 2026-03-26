package com.chipprbots.ethereum.jsonrpc.server.http

import java.lang.management.ManagementFactory

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import com.chipprbots.ethereum.utils.Logger

/** JVM profiling HTTP endpoints, equivalent to go-ethereum's `--pprof` (`:6060/debug/pprof/`).
  *
  * Exposed at `/debug/pprof/` when profiling is enabled. All endpoints return plain text.
  * Health endpoints remain unauthenticated; profiling endpoints sit behind the same JWT gate
  * as RPC when JWT is enabled.
  *
  * Endpoints:
  *   - GET /debug/pprof/           — index listing available profiles
  *   - GET /debug/pprof/heap       — JVM heap memory info (MemoryMXBean)
  *   - GET /debug/pprof/threads    — all thread stack traces (like goroutine dump)
  *   - GET /debug/pprof/gc         — GC collector statistics
  *   - GET /debug/pprof/vm         — JVM runtime info (version, uptime, classpath)
  *   - GET /debug/pprof/pools      — memory pool usage (Eden, Old Gen, Metaspace, etc.)
  */
object ProfilingRoutes extends Logger {

  val route: Route =
    pathPrefix("debug" / "pprof") {
      pathEndOrSingleSlash {
        get { handleIndex() }
      } ~ path("heap") {
        get { handleHeap() }
      } ~ path("threads") {
        get { handleThreads() }
      } ~ path("gc") {
        get { handleGc() }
      } ~ path("vm") {
        get { handleVmInfo() }
      } ~ path("pools") {
        get { handleMemoryPools() }
      }
    }

  private def textResponse(body: String) =
    complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, body)))

  private def handleIndex() = textResponse(
    """Fukuii JVM Profiling (pprof-equivalent)
      |
      |Available profiles:
      |  /debug/pprof/heap       JVM heap memory (MemoryMXBean)
      |  /debug/pprof/threads    All thread stack traces
      |  /debug/pprof/gc         GC collector statistics
      |  /debug/pprof/vm         JVM runtime info
      |  /debug/pprof/pools      Memory pool usage
      |""".stripMargin
  )

  private def handleHeap() = {
    val mem = ManagementFactory.getMemoryMXBean
    val heap = mem.getHeapMemoryUsage
    val nonHeap = mem.getNonHeapMemoryUsage
    val runtime = ManagementFactory.getRuntimeMXBean

    val sb = new StringBuilder
    sb.append("# Heap Memory\n")
    sb.append(f"heap.used:      ${heap.getUsed / 1048576}%,d MB\n")
    sb.append(f"heap.committed: ${heap.getCommitted / 1048576}%,d MB\n")
    sb.append(f"heap.max:       ${heap.getMax / 1048576}%,d MB\n")
    sb.append(f"heap.init:      ${heap.getInit / 1048576}%,d MB\n")
    sb.append(f"heap.usage:     ${if (heap.getMax > 0) heap.getUsed * 100.0 / heap.getMax else 0}%.1f%%\n")
    sb.append("\n# Non-Heap Memory\n")
    sb.append(f"nonheap.used:      ${nonHeap.getUsed / 1048576}%,d MB\n")
    sb.append(f"nonheap.committed: ${nonHeap.getCommitted / 1048576}%,d MB\n")
    sb.append(f"\n# Runtime\n")
    sb.append(f"uptime: ${runtime.getUptime / 1000}%,d seconds\n")
    sb.append(f"pending.finalization: ${mem.getObjectPendingFinalizationCount}%d\n")
    textResponse(sb.toString)
  }

  private def handleThreads() = {
    val threadMxBean = ManagementFactory.getThreadMXBean
    val infos = threadMxBean.dumpAllThreads(true, true)
    val sb = new StringBuilder
    sb.append(s"# Thread Dump (${infos.length} threads)\n\n")

    infos.sortBy(_.getThreadName).foreach { info =>
      sb.append(s"${info.getThreadName} [${info.getThreadState}]")
      if (info.getLockName != null) sb.append(s" waiting on ${info.getLockName}")
      sb.append("\n")
      info.getStackTrace.take(50).foreach { frame =>
        sb.append(s"  at ${frame.getClassName}.${frame.getMethodName}")
        if (frame.getFileName != null) sb.append(s"(${frame.getFileName}:${frame.getLineNumber})")
        sb.append("\n")
      }
      sb.append("\n")
    }

    sb.append(s"\n# Summary\n")
    sb.append(s"total.threads:  ${infos.length}\n")
    sb.append(s"daemon.threads: ${infos.count(_.isDaemon)}\n")
    sb.append(s"peak.threads:   ${threadMxBean.getPeakThreadCount}\n")
    sb.append(s"deadlocked:     ${Option(threadMxBean.findDeadlockedThreads()).map(_.length).getOrElse(0)}\n")

    textResponse(sb.toString)
  }

  private def handleGc() = {
    import scala.jdk.CollectionConverters._
    val gcBeans = ManagementFactory.getGarbageCollectorMXBeans.asScala
    val sb = new StringBuilder
    sb.append("# GC Statistics\n\n")

    gcBeans.foreach { gc =>
      sb.append(s"## ${gc.getName}\n")
      sb.append(s"  collections: ${gc.getCollectionCount}\n")
      sb.append(s"  time:        ${gc.getCollectionTime} ms\n")
      if (gc.getCollectionCount > 0)
        sb.append(f"  avg.time:    ${gc.getCollectionTime.toDouble / gc.getCollectionCount}%.1f ms\n")
      sb.append("\n")
    }

    val totalCollections = gcBeans.map(_.getCollectionCount).sum
    val totalTime = gcBeans.map(_.getCollectionTime).sum
    sb.append(s"# Totals\n")
    sb.append(s"total.collections: $totalCollections\n")
    sb.append(s"total.gc.time:     $totalTime ms\n")

    textResponse(sb.toString)
  }

  private def handleVmInfo() = {
    val runtime = ManagementFactory.getRuntimeMXBean
    val os = ManagementFactory.getOperatingSystemMXBean
    val sb = new StringBuilder
    sb.append("# JVM Info\n")
    sb.append(s"vm.name:    ${runtime.getVmName}\n")
    sb.append(s"vm.version: ${runtime.getVmVersion}\n")
    sb.append(s"vm.vendor:  ${runtime.getVmVendor}\n")
    sb.append(s"spec:       ${runtime.getSpecName} ${runtime.getSpecVersion}\n")
    sb.append(s"uptime:     ${runtime.getUptime / 1000} seconds\n")
    sb.append(s"start.time: ${java.time.Instant.ofEpochMilli(runtime.getStartTime)}\n")
    sb.append(s"\n# OS\n")
    sb.append(s"os.name:    ${os.getName}\n")
    sb.append(s"os.arch:    ${os.getArch}\n")
    sb.append(s"os.version: ${os.getVersion}\n")
    sb.append(s"cpus:       ${os.getAvailableProcessors}\n")
    sb.append(s"load.avg:   ${os.getSystemLoadAverage}\n")
    sb.append(s"\n# Input Arguments\n")
    import scala.jdk.CollectionConverters._
    runtime.getInputArguments.asScala.foreach(arg => sb.append(s"  $arg\n"))

    textResponse(sb.toString)
  }

  private def handleMemoryPools() = {
    import scala.jdk.CollectionConverters._
    val pools = ManagementFactory.getMemoryPoolMXBeans.asScala
    val sb = new StringBuilder
    sb.append("# Memory Pools\n\n")

    pools.foreach { pool =>
      val usage = pool.getUsage
      if (usage != null) {
        sb.append(s"## ${pool.getName} [${pool.getType}]\n")
        sb.append(f"  used:      ${usage.getUsed / 1048576}%,d MB\n")
        sb.append(f"  committed: ${usage.getCommitted / 1048576}%,d MB\n")
        if (usage.getMax > 0)
          sb.append(f"  max:       ${usage.getMax / 1048576}%,d MB (${usage.getUsed * 100.0 / usage.getMax}%.1f%% used)\n")
        val peak = pool.getPeakUsage
        if (peak != null)
          sb.append(f"  peak:      ${peak.getUsed / 1048576}%,d MB\n")
        sb.append("\n")
      }
    }

    textResponse(sb.toString)
  }
}
