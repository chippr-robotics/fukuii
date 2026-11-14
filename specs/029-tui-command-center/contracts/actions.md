# Contract: operator actions

Every action the first release offers: its class, what its preview states, the one function that carries it out, and
how it is undone. The classes and their confirmations are in spec FR-047 to FR-058 and research §7.3.

Rules that hold for all of them:

- The function in "Carried out by" is the only implementation. Where it is an existing admin method's service
  function, the TUI calls that function in process (spec FR-052). No RPC namespace is needed or enabled.
- The preview is built from live state when the dialog opens and rebuilt if that state changes before the operator
  confirms (spec FR-050).
- Every attempt writes one INFO line from the logger `fukuii.tui.ops`:
  `action=<name> params=<…> outcome=<confirmed|cancelled|refused|failed: reason> by=tui` (spec FR-051). A parameter
  that is a secret is never a parameter of any action here.
- With `fukuii.tui.read-only = true` only the Observe rows run (spec FR-053).
- "New" in the last column means code this spec adds outside `console/`; the owner is in the plan.

## Observe

| Action | Shows | Carried out by |
| --- | --- | --- |
| `config` | The effective configuration as `key = value` with its origin, filterable; secrets hidden (spec FR-058) | New in `console/`: render of the resolved `Config`, through `Redaction` |
| `tunables` | Log levels, peer limit, trusted peers, blocked addresses, mining state and coinbase, alert thresholds: the settings that change without a restart, with their current values | Reads only |
| `deps` | Each subsystem, what it depends on, its health bar and limiting check | `HealthModel` |
| `test-rpc` | For each enabled transport: the address, the answer to `web3_clientVersion` (HTTP, WebSocket, IPC), and for the Engine API that an unauthenticated request is refused; the time each took | New in `console/`: a client on the node's own listeners |
| `net` | Listening address and port, the external address detected at start and how, UPnP state, inbound peer count | Reads `ExternalIPDetector`'s result (new: kept, not only logged) |
| `art` | Every sprite, pose and frame | `ArtStore` |
| lookups | Block, transaction, receipt, account | `EthBlocksService`, `EthTxService`, `EthUserService` |

## Adjust

| Action | Preview states | Carried out by | Undo |
| --- | --- | --- | --- |
| `loglevel` | The logger, its level now and after | `AdminService.changeLogLevel` (`jsonrpc/AdminService.scala:300`); the current levels from a new read beside it | `loglevel reset` returns every changed logger to its configured level |
| `maxpeers` | The limit now and after, and the connected count | `PeerManagerActor.SetMaxPeersCmd` via `AdminService.maxPeers` (`:440`) | Set the old value |
| `peer trust` / `untrust` | The peer's id, client, address; trusted now and after | `AdminService.addTrustedPeer` / `removeTrustedPeer` (`:401`, `:419`) | The opposite action |
| `threshold` | The threshold now and after; the alerts that would fire or clear at once; the configuration line that makes it permanent | `console/` only: the session's `AlertThresholds` | `threshold reset` |

## Disrupt

| Action | Preview states | Carried out by | Undo |
| --- | --- | --- | --- |
| `peer add` | The enode; that the node will dial it; the current and maximum peer counts | `AdminService.addPeer` (`:245`) | `peer drop` |
| `peer drop` | The peer's id, client, address, age and score; that it may reconnect | `AdminService.removePeer` (`:264`) | It may reconnect by itself; `peer add` |
| `peer block` | The address; the connected peers at that address that will be dropped; that the block lasts until restart | `AdminService.blockIP` (`:382`) | `peer unblock` |
| `peer unblock` | The address | `AdminService.unblockIP` | `peer block` |
| `mining start` / `stop` (ETC) | Mining now and after; the coinbase; the hash rate that stops | `EthMiningService.startMiner` / `stopMiner` (`jsonrpc/EthMiningService.scala:223`, `:232`) | The opposite action |
| `coinbase` (ETC) | The address now and after; that blocks mined from now pay the new one | The service function behind `miner_setEtherbase` | Set the old address |
| `rpc stop http` / `ws` | The address and port; the namespaces served; the open connections that drop; that readiness probes on that port will fail | New: a kept binding and `stop` on the HTTP and WebSocket servers (`jsonrpc/server/http/`) | `rpc start` |
| `rpc start http` / `ws` | The address and port it will bind; the namespaces; a warning if an admin namespace would be reachable from another host | New: the same servers' `start` | `rpc stop` |
| `dbcheck` | What it reads; that it is the check run at start; that on finding an inconsistency it reports it and stops nothing | New: an on-demand entry to `StorageConsistencyChecker` that returns its result instead of ending the node | Nothing to undo |
| `compact` | The database's size and pending compaction; that writers wait while it runs and import slows; that it cannot be interrupted | New: a whole-database compaction on `RocksDbDataSource` | Cannot be undone; changes no data |
| `detect-ip` | The services it contacts, by host name, and that they will see this node's address | `ExternalIPDetector.detect` (`network/ExternalIPDetector.scala:94`) | Nothing to undo |
| `bundle` | The path; every file it will write and its size; that it holds peer addresses and the node id; that it goes nowhere | New in `console/`: `SupportBundle` | Delete the file |

A support bundle holds: build information; the effective configuration, redacted; the log ring; the alert history;
the last snapshot of every panel as text; database properties; a thread dump; garbage-collector and memory figures.
It holds no key, no keystore, no JWT secret, no state and no chain data.

## Halt

| Action | Preview states | Carried out by | Undo |
| --- | --- | --- | --- |
| `quit` | That the node process ends. On an ETH node with a consensus client connected: that the consensus client loses its execution client and a validator using this node misses duties until it is back. On an ETC node that is mining: that mining stops. The connected peers and the open RPC transports that close. That the TUI cannot start the node again. | The existing quit path (`TuiUpdater.restoreThenQuit`, `StdNode.shutdown`) | Start the node from a shell |

The word to type is `stop`.

## Not offered, and why

Shown in the Ops view (spec FR-056).

| Requested | Reason |
| --- | --- |
| Start the node, restart the node | The TUI runs inside the node's process (D-1) |
| Restart the Engine API server, peer discovery or sync | None can be stopped and started again without ending the process (research §7.2) |
| Reload configuration from file | No key is re-read after start; `tunables` lists what changes at run time |
| Create a snapshot | Spec 013 has no code |
| Change the pruning mode or state scheme | Needs a restart, and for the scheme a resync |
