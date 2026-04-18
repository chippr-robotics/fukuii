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

    val p2pPort = config.getConfig("network.server-address").getInt("port")
    val httpEnabled = config.getBoolean("network.rpc.http.enabled")
    val httpPort = config.getInt("network.rpc.http.port")
    val wsEnabled = config.getBoolean("network.rpc.ws.enabled")
    val wsPort = config.getInt("network.rpc.ws.port")
    val engineEnabled = config.getBoolean("network.rpc.engine.enabled")
    val enginePort = config.getInt("network.rpc.engine.port")

    addIfEnabled(p2pPort, enabled = true, "P2P")
    addIfEnabled(httpPort, httpEnabled, "JSON-RPC HTTP")
    addIfEnabled(wsPort, wsEnabled, "JSON-RPC WS")
    addIfEnabled(enginePort, engineEnabled, "Engine API")
  }
}
