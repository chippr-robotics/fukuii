package com.chipprbots.ethereum

import com.chipprbots.ethereum.utils.Logger

/** Reports JVM and OS environment at node startup.
  *
  * Besu reference: BesuCommand.java ConfigurationOverviewBuilder — host section, lines 1488-1548. Logs: Java version +
  * VM name, max heap in MB, available CPU cores, OS name and architecture.
  */
object NodeStatusReporter extends Logger {

  def report(): Unit = {
    val javaVersion = System.getProperty("java.version", "unknown")
    val javaVm = System.getProperty("java.vm.name", "unknown")
    val maxHeapMb = Runtime.getRuntime.maxMemory() / (1024L * 1024L)
    val cpuCores = Runtime.getRuntime.availableProcessors()
    val osName = System.getProperty("os.name", "unknown")
    val osArch = System.getProperty("os.arch", "unknown")

    log.info("Java: {} ({})", javaVersion, javaVm)
    log.info("Maximum heap size: {} MB", maxHeapMb)
    log.info("CPU cores: {}", cpuCores)
    log.info("OS: {}/{}", osName, osArch)
  }
}
