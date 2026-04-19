package com.chipprbots.ethereum

import com.typesafe.config.{Config => TypesafeConfig}

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
object ConfigValidator extends Logger {

  def validate(config: TypesafeConfig): List[String] = {
    val errors = List.newBuilder[String]

    validateSync(config, errors)
    checkPortClash(config, errors)

    errors.result()
  }

  private def validateSync(config: TypesafeConfig, errors: collection.mutable.Builder[String, List[String]]): Unit = {
    val doFastSync = config.getBoolean("sync.do-fast-sync")
    val doSnapSync = config.getBoolean("sync.do-snap-sync")
    if (doSnapSync && !doFastSync)
      errors += "SNAP sync (sync.do-snap-sync = true) requires fast sync to be enabled (sync.do-fast-sync = true)"
  }

  /** Besu reference: BesuCommand.checkPortClash() (line 2711) — Set-based duplicate detection. Collects all enabled
    * service ports into a Set; any port appearing more than once is a conflict. Checks P2P, JSON-RPC HTTP, JSON-RPC WS,
    * and Engine API ports.
    */
  private def checkPortClash(config: TypesafeConfig, errors: collection.mutable.Builder[String, List[String]]): Unit = {
    val seen = collection.mutable.Set.empty[Int]

    def addIfEnabled(port: Int, enabled: Boolean, label: String): Unit =
      if (enabled && port > 0) {
        if (!seen.add(port))
          errors += s"Port number '$port' has been specified multiple times. Please review the supplied configuration. ($label)"
      }

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
  }
}
