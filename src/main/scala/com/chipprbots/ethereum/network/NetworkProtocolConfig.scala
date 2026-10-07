package com.chipprbots.ethereum.network

import com.typesafe.config.Config as TypesafeConfig

/** Per-network protocol capability gating.
  *
  * Controls which devp2p protocol versions Fukuii advertises in the Hello handshake. The defaults are the conservative
  * baseline, ETH68 + ETH69 + SNAP1; newer versions are opt-in per network (`network.protocols` in the network's config,
  * e.g. `conf/hive.conf`, or `-Dfukuii.network.protocols.<flag>=...`).
  *
  * `Capability.negotiate` picks the highest version BOTH sides advertise, so offering more is harmless against peers
  * that only speak eth/69 + snap/1 (core-geth, Besu-ETC). It is not harmless between two fukuii nodes on a chain
  * without block access lists: they would settle on snap/2, which drops GetTrieNodes, and trie healing needs it. That
  * is why the newer versions stay off by default rather than being advertised everywhere.
  *
  * Reference survey (2026-09-23; all versions implemented — see MessageDecoders.scala):
  *   - eth68/eth69: universal — all production clients; ETC baseline at Olympia
  *   - eth70 (EIP-7975 receipts), eth71 (EIP-8159 BALs), eth72 (EIP-8070 cells): geth master, ETH-family only
  *   - snap1: universal SNAP baseline
  *   - snap2 (EIP-8189): geth master; drops GetTrieNodes/TrieNodes, adds GetAccessLists/AccessLists; geth itself does
  *     not advertise it unconditionally
  */
final case class NetworkProtocolConfig(
    eth68: Boolean = true,
    eth69: Boolean = true,
    eth70: Boolean = false,
    eth71: Boolean = false,
    eth72: Boolean = false,
    snap1: Boolean = true,
    snap2: Boolean = false
)

object NetworkProtocolConfig:

  /** Parse from a `network.protocols` HOCON sub-config. eth72 is optional and defaults to off, so a config written
    * before it existed keeps loading; every other key must be present.
    */
  def fromConfig(c: TypesafeConfig): NetworkProtocolConfig =
    NetworkProtocolConfig(
      eth68 = c.getBoolean("eth68"),
      eth69 = c.getBoolean("eth69"),
      eth70 = c.getBoolean("eth70"),
      eth71 = c.getBoolean("eth71"),
      eth72 = c.hasPath("eth72") && c.getBoolean("eth72"),
      snap1 = c.getBoolean("snap1"),
      snap2 = c.getBoolean("snap2")
    )

  /** The conservative baseline (ETH68, ETH69, SNAP1), for tests or when a full HOCON config is not available. */
  val default: NetworkProtocolConfig = NetworkProtocolConfig()
