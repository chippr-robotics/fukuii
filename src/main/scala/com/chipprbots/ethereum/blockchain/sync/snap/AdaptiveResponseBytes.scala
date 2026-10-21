package com.chipprbots.ethereum.blockchain.sync.snap

import scala.collection.mutable

import org.slf4j.Logger

import com.chipprbots.ethereum.network.Peer

/** The per-peer adaptive `responseBytes` window shared by AccountRangeCoordinator, StorageRangeCoordinator and
  * TrieNodeHealingCoordinator (spec 016 C-X, T076; research.md R12).
  *
  * Each peer's target starts at `initial`, grows by 1.25x of the requested size when a response fills at least 90% of
  * the request, halves on a failure, and is clamped to [`min`, 2 MiB] (Geth's snap handler limit). Before C-X the three
  * coordinators carried byte-identical copies of this logic that differed only in the log label and in where `initial`
  * and `min` come from (ARC and SRC: configuration; TNHC: 524288/51200), so all three are parameters here.
  * `ResponseWindowGoldenSpec` (C-X0) pins the behaviour per coordinator. ByteCodeCoordinator's window differs (keyed by
  * `PeerId`, an extra floor on growth) and is not shared (NG-5).
  *
  * The window owns no state: `targets` is the coordinator's own per-peer map (it keeps clearing it on a pivot change),
  * and `log` is the coordinator's logger, so the DEBUG line keeps its logger and text. Actor-thread only, like the map.
  *
  * @param label
  *   the word in the DEBUG line: `account`, `storage` or `healing`
  * @param initial
  *   a peer's first target
  * @param min
  *   the floor a failure cannot go below
  * @param targets
  *   the coordinator's per-peer target map, keyed by peer id
  * @param log
  *   the coordinator's logger
  */
final class AdaptiveResponseBytes(
    label: String,
    initial: BigInt,
    min: BigInt,
    targets: mutable.Map[String, BigInt],
    log: Logger
):
  import AdaptiveResponseBytes.*

  /** The peer's current target, seeding it with `initial` on first use, within [min, max]. */
  def targetFor(peer: Peer): BigInt =
    targets
      .getOrElseUpdate(peer.id.value, initial)
      .max(min)
      .min(MaxResponseBytes)

  /** Grow the peer's target from the requested size when the response filled at least 90% of it. */
  def onSuccess(peer: Peer, requested: BigInt, received: BigInt): Unit =
    if requested > 0 && received * 10 >= requested * 9 && requested < MaxResponseBytes then
      val next = (requested.toDouble * IncreaseFactor).toLong
      targets.update(peer.id.value, BigInt(next).min(MaxResponseBytes))

  /** Halve the peer's target, not below `min`, and log the change at DEBUG. */
  def onFailure(peer: Peer, reason: String): Unit =
    val cur = targetFor(peer)
    val next = (cur.toDouble * DecreaseFactor).toLong
    targets.update(peer.id.value, BigInt(next).max(min))
    log.debug(
      s"Reducing $label responseBytes target for peer ${peer.id.value}: $cur -> ${targets(peer.id.value)} ($reason)"
    )

object AdaptiveResponseBytes:
  /** 2 MiB ceiling (Geth's snap handler limit). */
  val MaxResponseBytes: BigInt = 2 * 1024 * 1024

  /** Scale up when a response fills 90% or more of the request. */
  val IncreaseFactor: Double = 1.25

  /** Scale down on a failure or an empty response. */
  val DecreaseFactor: Double = 0.5
