package com.chipprbots.ethereum.console

/** A phase of the node's start (spec 029 FR-020), in the order `StdNode.start` runs them. The node reports each one
  * when it has completed, and the splash marks it done then: the checklist is the node's own progress, never a script.
  *
  * The labels hold for both chain families: what only one of them starts (the Engine API, the miner) is not named.
  */
enum StartupPhase(val label: String):
  /** The metrics, the database's repairs, the genesis block, an imported chain file. */
  case Storage extends StartupPhase("storage and genesis")

  /** The TCP server, static nodes, port forwarding, the peer manager, discovery. */
  case Network extends StartupPhase("peer-to-peer network")

  /** Every RPC server the configuration turns on: HTTP, WebSocket, IPC, the Engine API. */
  case Api extends StartupPhase("API servers")

  /** The sync controller, and the miner where there is one. */
  case Sync extends StartupPhase("sync")

  /** The database consistency check, and the status probe the dashboard reads. */
  case Checks extends StartupPhase("consistency checks")

object StartupPhase:

  /** The share of the phases in `done`, from 0 to 1: the splash's progress bar. */
  def share(done: Set[StartupPhase]): Double = done.size.toDouble / values.length

  /** The phase the node is in: the first not done; none once all are. */
  def current(done: Set[StartupPhase]): Option[StartupPhase] = values.find(p => !done.contains(p))
