package com.chipprbots.ethereum.network

import java.util.concurrent.atomic.AtomicReference

/** What became of the UPnP port mapping (B-287): whether one was asked for, how many ports, and what the gateway did. A
  * plain holder made at start: [[PortForwarder]] writes it, the TUI's `net` report reads it.
  */
final class PortMappingState:
  import PortMappingState.*

  private val state = new AtomicReference[Snapshot](Snapshot.NotAsked)
  @volatile private var granted: () => Int = () => 0

  def get: Snapshot = state.get match
    case asked: Snapshot.Asked => asked.copy(granted = granted())
    case other                 => other

  /** Mapping `requested` port mappings was asked of any gateway found; `grantedCount` reads how many it gave. */
  def asked(requested: Int, grantedCount: () => Int): Unit =
    granted = grantedCount
    state.set(Snapshot.Asked(requested, granted = 0, lastFailure = None))

  /** UPnP could not start at all: no gateway can be asked. */
  def unavailable(why: String): Unit = state.set(Snapshot.Unavailable(why))

  /** A gateway refused a mapping, or could not be asked for one. */
  def failed(why: String): Unit =
    val _ = state.updateAndGet {
      case asked: Snapshot.Asked => asked.copy(lastFailure = Some(why))
      case other                 => other
    }

object PortMappingState:
  enum Snapshot:
    /** Automatic port forwarding is off (`fukuii.network.automatic-port-forwarding`). */
    case NotAsked

    /** UPnP did not start: the reason. */
    case Unavailable(why: String)

    /** `requested` mappings asked of each gateway found; `granted` of them given so far; the gateway's last refusal. */
    case Asked(requested: Int, granted: Int, lastFailure: Option[String])

  /** One line for the `net` report. */
  def describe(snapshot: Snapshot): String = snapshot match
    case Snapshot.NotAsked         => "off: no port mapping asked for (automatic-port-forwarding = false)"
    case Snapshot.Unavailable(why) => s"asked for, but UPnP did not start: $why"
    case Snapshot.Asked(requested, granted, failure) =>
      val gave =
        if granted >= requested then s"the gateway mapped all $requested port(s)"
        else if granted > 0 then s"the gateway mapped $granted of $requested port(s)"
        else if failure.isDefined then s"no gateway mapped any of the $requested port(s)"
        else s"$requested port mapping(s) asked for; no gateway has mapped one yet"
      gave + failure.fold("")(why => s" (last refusal: $why)")
