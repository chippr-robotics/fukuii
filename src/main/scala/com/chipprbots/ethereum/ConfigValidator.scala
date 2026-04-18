package com.chipprbots.ethereum

import com.typesafe.config.{Config => TypesafeConfig}

import com.chipprbots.ethereum.utils.Logger

/** Validates critical configuration at startup, logging warnings for misconfigurations.
  *
  * Besu reference: BesuCommand.java validateOptions() (line 1576) — calls validateP2POptions(),
  * validateMiningParams(), validateRpcOptionsParams(), ensureValidPeerBoundParams().
  *
  * Takes the fukuii-namespaced TypesafeConfig (i.e. Config.config).
  * Returns fatal error messages; non-fatal issues are logged as warnings.
  * Callers should abort startup if the returned list is non-empty.
  */
object ConfigValidator extends Logger {

  def validate(config: TypesafeConfig): List[String] = {
    val errors = List.newBuilder[String]

    validateSync(config, errors)
    validatePorts(config, errors)
    validatePeerBounds(config)

    errors.result()
  }

  private def validateSync(config: TypesafeConfig, errors: collection.mutable.Builder[String, List[String]]): Unit = {
    val doFastSync = config.getBoolean("sync.do-fast-sync")
    val doSnapSync = config.getBoolean("sync.do-snap-sync")
    if (doSnapSync && !doFastSync)
      errors += "SNAP sync (sync.do-snap-sync = true) requires fast sync to be enabled (sync.do-fast-sync = true)"
  }

  private def validatePorts(config: TypesafeConfig, errors: collection.mutable.Builder[String, List[String]]): Unit = {
    val p2pPort     = config.getConfig("network.server-address").getInt("port")
    val httpEnabled = config.getBoolean("network.rpc.http.enabled")
    val httpPort    = config.getInt("network.rpc.http.port")
    val wsEnabled   = config.getBoolean("network.rpc.ws.enabled")
    val wsPort      = config.getInt("network.rpc.ws.port")

    if (httpEnabled && httpPort == p2pPort)
      errors += s"Port conflict: JSON-RPC HTTP port ($httpPort) collides with P2P port ($p2pPort)"
    if (wsEnabled && wsPort == p2pPort)
      errors += s"Port conflict: JSON-RPC WS port ($wsPort) collides with P2P port ($p2pPort)"
    if (httpEnabled && wsEnabled && httpPort == wsPort)
      errors += s"Port conflict: JSON-RPC HTTP port and WS port are both $httpPort"
  }

  private def validatePeerBounds(config: TypesafeConfig): Unit = {
    val minOut = config.getInt("network.peer.min-outgoing-peers")
    val maxOut = config.getInt("network.peer.max-outgoing-peers")
    if (minOut > maxOut)
      log.warn(
        "min-outgoing-peers ({}) > max-outgoing-peers ({}) — using max-outgoing-peers as the effective minimum",
        minOut,
        maxOut
      )
  }
}
