package com.chipprbots.ethereum.utils

/** Pre-flight validation of configuration combinations that would cause confusing failures at runtime.
  *
  * Called once at startup before subsystems are initialized. Logs warnings for non-fatal misconfigurations and throws
  * for fatal ones.
  */
object ConfigValidator extends Logger {

  sealed trait Severity
  case object Warn extends Severity
  case object Error extends Severity

  case class Issue(severity: Severity, message: String)

  /** Validate configuration and log all issues. Throws on fatal errors. */
  def validate(): Unit = {
    val issues = collectIssues()

    issues.foreach {
      case Issue(Warn, msg)  => log.warn(s"[ConfigValidator] $msg")
      case Issue(Error, msg) => log.error(s"[ConfigValidator] $msg")
    }

    val errors = issues.collect { case i @ Issue(Error, _) => i }
    if (errors.nonEmpty) {
      throw new IllegalStateException(
        s"Configuration validation failed with ${errors.size} error(s):\n${errors.map(_.message).mkString("\n  - ", "\n  - ", "")}"
      )
    }

    if (issues.isEmpty) {
      log.info("[ConfigValidator] All configuration checks passed")
    }
  }

  private def collectIssues(): Seq[Issue] = {
    val syncIssues = validateSync()
    val rpcIssues = validateRpc()
    val memoryIssues = validateMemory()
    syncIssues ++ rpcIssues ++ memoryIssues
  }

  private def validateSync(): Seq[Issue] = {
    val syncConfig = Config.SyncConfig(Config.config)
    val issues = scala.collection.mutable.ArrayBuffer.empty[Issue]

    // SNAP sync requires fast sync as fallback
    if (syncConfig.doSnapSync && !syncConfig.doFastSync) {
      issues += Issue(
        Warn,
        "do-snap-sync=true but do-fast-sync=false. SNAP sync requires fast sync as fallback. " +
          "Node will start regular sync only (block-by-block from genesis)."
      )
    }

    // Insufficient pivot peers
    if (syncConfig.doFastSync && syncConfig.minPeersToChoosePivotBlock < 1) {
      issues += Issue(
        Error,
        s"min-peers-to-choose-pivot-block=${syncConfig.minPeersToChoosePivotBlock} must be >= 1. " +
          "Cannot select a pivot block with zero peers."
      )
    }

    // Very low pivot peer threshold
    if (syncConfig.doFastSync && syncConfig.minPeersToChoosePivotBlock == 1) {
      issues += Issue(
        Warn,
        "min-peers-to-choose-pivot-block=1. Single-peer pivot selection risks syncing to a minority fork. " +
          "Recommended: 3+ peers for production."
      )
    }

    issues.toSeq
  }

  private def validateRpc(): Seq[Issue] = {
    val issues = scala.collection.mutable.ArrayBuffer.empty[Issue]

    try {
      val rpcConfig = Config.config.getConfig("network.rpc")

      // HTTP port validation
      if (rpcConfig.hasPath("http.enabled") && rpcConfig.getBoolean("http.enabled")) {
        val httpPort = rpcConfig.getInt("http.port")
        if (httpPort < 1 || httpPort > 65535) {
          issues += Issue(Error, s"RPC HTTP port $httpPort is outside valid range [1, 65535]")
        } else if (httpPort < 1024) {
          issues += Issue(Warn, s"RPC HTTP port $httpPort requires elevated privileges (< 1024)")
        }

        // Security warning for 0.0.0.0 binding
        val httpInterface = rpcConfig.getString("http.interface")
        if (httpInterface == "0.0.0.0") {
          issues += Issue(
            Warn,
            "RPC HTTP interface is 0.0.0.0 (all interfaces). " +
              "This exposes the RPC API to the network. Use 127.0.0.1 for local-only access."
          )
        }
      }

      // WS port validation
      if (rpcConfig.hasPath("ws.enabled") && rpcConfig.getBoolean("ws.enabled")) {
        val wsPort = rpcConfig.getInt("ws.port")
        if (wsPort < 1 || wsPort > 65535) {
          issues += Issue(Error, s"RPC WebSocket port $wsPort is outside valid range [1, 65535]")
        }

        // HTTP-WS port collision
        if (rpcConfig.hasPath("http.enabled") && rpcConfig.getBoolean("http.enabled")) {
          val httpPort = rpcConfig.getInt("http.port")
          if (httpPort == wsPort) {
            issues += Issue(
              Error,
              s"RPC HTTP port ($httpPort) and WebSocket port ($wsPort) are the same. They must be different."
            )
          }
        }
      }
    } catch {
      case _: com.typesafe.config.ConfigException =>
      // Config section missing — RPC not configured, nothing to validate
    }

    issues.toSeq
  }

  private def validateMemory(): Seq[Issue] = {
    val issues = scala.collection.mutable.ArrayBuffer.empty[Issue]

    val maxMemMB = Runtime.getRuntime.maxMemory() / (1024 * 1024)
    val syncConfig = Config.SyncConfig(Config.config)

    if (syncConfig.doSnapSync && maxMemMB < 3072) {
      issues += Issue(
        Warn,
        f"SNAP sync with heap < 3 GB ($maxMemMB%,d MB) risks OOM during state download. " +
          "Recommended: -Xmx4g or higher."
      )
    }

    if (maxMemMB < 1024) {
      issues += Issue(
        Warn,
        f"JVM heap is only $maxMemMB%,d MB. Minimum recommended: 2 GB (-Xmx2g) for any sync mode."
      )
    }

    issues.toSeq
  }
}
