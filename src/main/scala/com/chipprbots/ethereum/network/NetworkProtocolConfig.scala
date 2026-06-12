package com.chipprbots.ethereum.network

import com.typesafe.config.{Config => TypesafeConfig}

/** Per-network protocol capability gating.
  *
  * Controls which devp2p protocol versions Fukuii advertises in the Hello handshake. The default values encode the
  * conservative baseline: ETH68, ETH69, and SNAP1 are universally deployed; ETH70, ETH71, and SNAP2 are opt-in
  * (disabled by default) because they are either absent from the ETC peer set or not yet in any production release.
  *
  * Parsed from `network.protocols.*` in the HOCON config. Per-network config files override individual flags; the
  * global `base/network.conf` provides the conservative defaults.
  *
  * Reference survey (2026-06-11):
  *   - eth68: universal — all production clients
  *   - eth69: all modern clients (EIP-7642); ETC baseline at Olympia
  *   - eth70: geth v1.17.3 + Besu 26.6.0 — ETH mainnet only, no ETC timeline
  *   - eth71: Besu 26.6.0 only; geth upstream but not yet in a release
  *   - snap1: universal SNAP baseline
  *   - snap2: explicitly commented out in geth ("not safe to advertise unconditionally yet")
  */
final case class NetworkProtocolConfig(
    eth68: Boolean = true,
    eth69: Boolean = true,
    eth70: Boolean = false,
    eth71: Boolean = false,
    snap1: Boolean = true,
    snap2: Boolean = false
)

object NetworkProtocolConfig {

  /** Parse from a `network.protocols` HOCON sub-config. All six keys must be present. */
  def fromConfig(c: TypesafeConfig): NetworkProtocolConfig =
    NetworkProtocolConfig(
      eth68 = c.getBoolean("eth68"),
      eth69 = c.getBoolean("eth69"),
      eth70 = c.getBoolean("eth70"),
      eth71 = c.getBoolean("eth71"),
      snap1 = c.getBoolean("snap1"),
      snap2 = c.getBoolean("snap2")
    )

  /** Conservative default — ETH68, ETH69, SNAP1 only. Safe to use directly in tests or when a full HOCON config is not
    * available.
    */
  val default: NetworkProtocolConfig = NetworkProtocolConfig()
}
