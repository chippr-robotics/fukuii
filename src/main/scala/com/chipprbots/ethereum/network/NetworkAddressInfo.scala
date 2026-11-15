package com.chipprbots.ethereum.network

import java.util.concurrent.atomic.AtomicReference

/** What the node found out about its own address (spec 029, `net`): the last external-address detection, the step that
  * found it and when. A plain holder, made at start: the server actor's detection writes it, the dashboard reads it. It
  * keeps the last result only; a node whose advertised address is configured never detects, so [[get]] stays empty.
  */
final class NetworkAddressInfo:
  import NetworkAddressInfo.*

  private val state = new AtomicReference[Snapshot](Snapshot.empty)

  def get: Snapshot = state.get

  /** Records the outcome of one detection run: `found` is None when every step permitted by `mode` failed. */
  def recordDetection(mode: DetectionMode, found: Option[DetectedAddress], atMillis: Long): Unit =
    state.set(Snapshot(Some(mode), found, Some(atMillis)))

object NetworkAddressInfo:

  /** The last detection run: with which mode, what it found (None: nothing) and when (epoch milliseconds). The UPnP
    * port-mapping state is not reported (it is only logged by [[PortForwarder]]).
    */
  final case class Snapshot(mode: Option[DetectionMode], found: Option[DetectedAddress], atMillis: Option[Long])

  object Snapshot:
    val empty: Snapshot = Snapshot(None, None, None)
