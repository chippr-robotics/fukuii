package com.chipprbots.ethereum.network

import com.typesafe.config.Config as TypesafeConfig

import com.chipprbots.ethereum.network.p2p.messages.Capability

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
):

  /** The capabilities this configuration advertises in the Hello handshake.
    *
    * @param snapServed
    *   false while the node cannot serve SNAP for the roots a syncing peer asks about. snap/1 and snap/2 are then left
    *   out, whatever the flags say: a capability is an offer to serve, and a peer that keeps getting empty answers
    *   marks this node's SNAP as unusable. It is mutual, so a node that does not offer snap cannot sync by it either.
    */
  def capabilities(snapServed: Boolean = true): List[Capability] =
    List(
      Option.when(eth68)(Capability.ETH68),
      Option.when(eth69)(Capability.ETH69),
      Option.when(eth70)(Capability.ETH70),
      Option.when(eth71)(Capability.ETH71),
      Option.when(eth72)(Capability.ETH72),
      Option.when(snap1 && snapServed)(Capability.SNAP1),
      Option.when(snap2 && snapServed)(Capability.SNAP2)
    ).flatten

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
