package com.chipprbots.ethereum

import com.typesafe.config.Config as TypesafeConfig

import com.chipprbots.ethereum.utils.Logger

/** Validates critical configuration at startup, logging warnings for misconfigurations.
  *
  * Besu reference: BesuCommand.java
  *   - validateOptions() (line 1576): validateP2POptions(), validateMiningParams(), validateRpcOptionsParams()
  *   - checkPortClash() (line 2711): Set-based duplicate port detection across all enabled services
  *
  * Takes the fukuii-namespaced TypesafeConfig (i.e. Config.config). Returns fatal error messages; non-fatal issues are
  * logged as warnings. Callers should abort startup if the returned list is non-empty.
  */
object ConfigValidator extends Logger:

  /** Keys under `sync` that only fast sync read. Fast sync was removed, so a configuration that still sets one of them
    * is not an error: the key is ignored and [[removedKeyWarnings]] reports it.
    */
  val RemovedFastSyncKeys: List[String] = List(
    "do-fast-sync",
    "fast-sync-restart-cooloff",
    "max-snap-fast-cycle-transitions",
    "start-retry-interval",
    "sync-switch-delay",
    "critical-blacklist-duration",
    "persist-state-snapshot-interval",
    "max-concurrent-requests",
    "nodes-per-request",
    "min-peers-to-choose-pivot-block",
    "peers-to-choose-pivot-block-margin",
    "pivot-block-offset",
    "pivot-block-max-total-selection-attempts",
    "pivot-block-reschedule-interval",
    "max-pivot-block-age",
    "max-pivot-block-failures-count",
    "max-target-difference",
    "maximum-target-update-failures",
    "fastsync-block-chain-only-peers-pool",
    "fastsync-throttle",
    "fast-sync-block-validation-k",
    "fast-sync-block-validation-n",
    "fast-sync-block-validation-x",
    "fast-sync-max-batch-retries",
    "state-sync-bloom-filter-size",
    "state-sync-persist-batch-size"
  )

  /** Returns the fatal errors in `config`. Warnings, including [[removedKeyWarnings]], are logged and do not stop
    * startup.
    */
  def validate(config: TypesafeConfig): List[String] =
    removedKeyWarnings(config).foreach(warning => log.warn(warning))

    val errors = List.newBuilder[String]
    checkPortClash(config, errors)
    errors.result()

  /** One warning for each removed fast-sync key that `config` (a `fukuii` block) still sets. */
  def removedKeyWarnings(config: TypesafeConfig): List[String] =
    RemovedFastSyncKeys.filter(key => config.hasPath(s"sync.$key")).map {
      case "do-fast-sync" => doFastSyncWarning(config)
      case key            => s"fukuii.sync.$key is ignored: fast sync, the only reader of this key, was removed."
    }

  /** `do-fast-sync = false` was the way to ask for a sync from genesis (archive nodes, for one). With SNAP on, that
    * request is no longer honoured, so say which sync this node will run and how to get the old behaviour.
    */
  private def doFastSyncWarning(config: TypesafeConfig): String =
    val snapOn = config.hasPath("sync.do-snap-sync") && config.getBoolean("sync.do-snap-sync")
    val consequence =
      if snapOn then
        "This node will use SNAP sync (fukuii.sync.do-snap-sync = true); set fukuii.sync.do-snap-sync = false " +
          "to import every block from genesis instead."
      else "This node imports every block (fukuii.sync.do-snap-sync = false)."
    s"fukuii.sync.do-fast-sync is ignored: fast sync was removed. $consequence"

  /** Besu reference: BesuCommand.checkPortClash() (line 2711) — Set-based duplicate detection. Collects all enabled
    * service ports into a Set; any port appearing more than once is a conflict. Checks P2P, JSON-RPC HTTP, JSON-RPC WS,
    * and Engine API ports.
    */
  private def checkPortClash(config: TypesafeConfig, errors: collection.mutable.Builder[String, List[String]]): Unit =
    val seen = collection.mutable.Set.empty[Int]

    def addIfEnabled(port: Int, enabled: Boolean, label: String): Unit =
      if enabled && port > 0 then
        if !seen.add(port) then
          errors += s"Port number '$port' has been specified multiple times. Please review the supplied configuration. ($label)"

    def getBool(path: String, default: Boolean): Boolean =
      scala.util.Try(config.getBoolean(path)).getOrElse(default)

    def getInt(path: String, default: Int): Int =
      scala.util.Try(config.getInt(path)).getOrElse(default)

    val p2pPort = config.getConfig("network.server-address").getInt("port")
    val httpEnabled = getBool("network.rpc.http.enabled", default = false)
    val httpPort = getInt("network.rpc.http.port", default = 0)
    val wsEnabled = getBool("network.rpc.ws.enabled", default = false)
    val wsPort = getInt("network.rpc.ws.port", default = 0)
    // Engine API lives at network.engine-api (a sibling of network.rpc, not
    // a child of it) — matches NodeBuilder.engineApiConfig and the key that
    // hive/fukuii/fukuii.sh sets via `-Dfukuii.network.engine-api.*`. The
    // previous `network.rpc.engine.*` path did not exist in any config file,
    // so startup threw ConfigException$Missing and every hive test errored.
    val engineEnabled = getBool("network.engine-api.enabled", default = false)
    val enginePort = getInt("network.engine-api.port", default = 0)

    addIfEnabled(p2pPort, enabled = true, "P2P")
    addIfEnabled(httpPort, httpEnabled, "JSON-RPC HTTP")
    addIfEnabled(wsPort, wsEnabled, "JSON-RPC WS")
    addIfEnabled(enginePort, engineEnabled, "Engine API")
