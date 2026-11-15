package com.chipprbots.ethereum.console.ops

/** The actions the dashboard offers (spec 029 FR-054): contracts/actions.md, row for row. A spec says what an action
  * is; what carries it out is an [[ActionHandler]] the node builds from the services it holds.
  */
object Actions:

  import RiskClass.*

  val Config: ActionSpec = ActionSpec(
    "config",
    "config [filter]",
    "the effective configuration, secrets hidden",
    Observe,
    "nothing to undo"
  )
  val Tunables: ActionSpec = ActionSpec(
    "tunables",
    "tunables",
    "what changes without a restart, as it is now",
    Observe,
    "nothing to undo"
  )
  val Deps: ActionSpec = ActionSpec(
    "deps",
    "deps",
    "each subsystem, what it needs, its health",
    Observe,
    "nothing to undo"
  )
  val TestRpc: ActionSpec = ActionSpec(
    "test-rpc",
    "test-rpc",
    "calls each RPC endpoint here: answer and time",
    Observe,
    "nothing to undo"
  )
  val Net: ActionSpec = ActionSpec(
    "net",
    "net",
    "listening and external addresses, inbound peers",
    Observe,
    "nothing to undo"
  )
  val Art: ActionSpec = ActionSpec("art", "art", "every sprite", Observe, "nothing to undo")
  val Lookup: ActionSpec = ActionSpec(
    "lookup",
    "block <number | hash | latest> | tx <hash> | receipt <hash> | account <address>",
    "a block, a transaction, a receipt or an account",
    Observe,
    "nothing to undo"
  )

  val LogLevel: ActionSpec = ActionSpec(
    "loglevel",
    "loglevel <level> [logger] | loglevel reset",
    "a logger's level, until the node restarts",
    Adjust,
    "loglevel reset returns every changed logger to its configured level"
  )
  val MaxPeers: ActionSpec = ActionSpec(
    "maxpeers",
    "maxpeers <n>",
    "the limit on outgoing peers, until restart",
    Adjust,
    "set the old value"
  )
  val PeerTrust: ActionSpec = ActionSpec(
    "peer trust",
    "peer trust <id>",
    "a peer is trusted: exempt from pruning",
    Adjust,
    "peer untrust"
  )
  val PeerUntrust: ActionSpec = ActionSpec(
    "peer untrust",
    "peer untrust <id>",
    "a peer stops being trusted",
    Adjust,
    "peer trust"
  )
  val Threshold: ActionSpec = ActionSpec(
    "threshold",
    "threshold <name> <value> | threshold reset",
    "where an alert begins, for this session",
    Adjust,
    "threshold reset"
  )

  val PeerAdd: ActionSpec = ActionSpec(
    "peer add",
    "peer add <enode>",
    "the node dials a peer and keeps dialling it",
    Disrupt,
    "peer drop"
  )
  val PeerDrop: ActionSpec = ActionSpec(
    "peer drop",
    "peer drop <id>",
    "a peer is disconnected; it may reconnect",
    Disrupt,
    "it may reconnect by itself; peer add"
  )
  val PeerBlock: ActionSpec = ActionSpec(
    "peer block",
    "peer block <address>",
    "an address is refused until restart",
    Disrupt,
    "peer unblock"
  )
  val PeerUnblock: ActionSpec = ActionSpec(
    "peer unblock",
    "peer unblock <address>",
    "an address stops being refused",
    Disrupt,
    "peer block"
  )
  val MiningStart: ActionSpec = ActionSpec(
    "mining start",
    "mining start",
    "the node's own miner starts (ETC)",
    Disrupt,
    "mining stop"
  )

  /** A Halt, not the Disrupt the contract first gave it: a miner stopped here cannot be started again while the node
    * runs (B-279), so mining stops until someone restarts the node from outside.
    */
  val MiningStop: ActionSpec = ActionSpec(
    "mining stop",
    "mining stop",
    "the node's own miner stops until restart (ETC)",
    Halt,
    "restart the node"
  )
  val Coinbase: ActionSpec = ActionSpec(
    "coinbase",
    "coinbase <address>",
    "the address mined blocks pay from now on (ETC)",
    Disrupt,
    "set the old address"
  )
  val RpcStop: ActionSpec = ActionSpec(
    "rpc stop",
    "rpc stop <http | ws>",
    "a JSON-RPC server stops; its connections drop",
    Disrupt,
    "rpc start"
  )
  val RpcStart: ActionSpec = ActionSpec(
    "rpc start",
    "rpc start <http | ws>",
    "a server that was stopped here listens again",
    Disrupt,
    "rpc stop"
  )
  val DbCheck: ActionSpec = ActionSpec(
    "dbcheck",
    "dbcheck",
    "the start's database check, run now; it reports",
    Disrupt,
    "nothing to undo"
  )
  val DetectIp: ActionSpec = ActionSpec(
    "detect-ip",
    "detect-ip",
    "asks outside services for this node's address",
    Disrupt,
    "nothing to undo"
  )
  val Bundle: ActionSpec = ActionSpec(
    "bundle",
    "bundle",
    "writes a support bundle under the datadir",
    Disrupt,
    "delete the file"
  )

  val Quit: ActionSpec = ActionSpec(
    "quit",
    "quit",
    "the node process ends",
    Halt,
    "start the node again, from outside the dashboard"
  )

  /** The word a Halt asks for. */
  val HaltWord: String = "stop"

  /** Every action, in the order the Ops view lists them: by class, then as the contract has them. */
  val all: Vector[ActionSpec] = Vector(
    Config,
    Tunables,
    Deps,
    TestRpc,
    Net,
    Art,
    Lookup,
    LogLevel,
    MaxPeers,
    PeerTrust,
    PeerUntrust,
    Threshold,
    PeerAdd,
    PeerDrop,
    PeerBlock,
    PeerUnblock,
    MiningStart,
    MiningStop,
    Coinbase,
    RpcStop,
    RpcStart,
    DbCheck,
    DetectIp,
    Bundle,
    Quit
  )

  def named(name: String): Option[ActionSpec] = all.find(_.name == name)

  /** What the brief asked for and this release does not offer, with the reason (FR-056). */
  val NotOffered: Vector[(String, String)] = Vector(
    "Start the node, restart the node" -> "the dashboard runs inside the node's process",
    "Restart the Engine API server, peer discovery or sync" ->
      "none can be stopped and started again without ending the process",
    "Reload configuration from file" -> "no key is read again after the start; `tunables` lists what changes while it runs",
    "Create a snapshot" -> "this client has no snapshots (spec 013 has no code)",
    "Compact the database" ->
      ("it holds the database lock for its whole run, so the node would import nothing until it ended, which on a " +
        "large database may take hours and cannot be stopped (B-281)"),
    "Change the pruning mode or the state scheme" -> "needs a restart, and for the scheme a resync"
  )
