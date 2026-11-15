# Contract: operator actions

Every action the first release offers: its class, what its preview states, the one function that carries it out, and
how it is undone. The classes and their confirmations are in spec FR-047 to FR-058 and research §7.3.

Rules that hold for all of them:

- The function in "Carried out by" is the only implementation. Where it is an existing admin method's service
  function, the TUI calls that function in process (spec FR-052). No RPC namespace is needed or enabled.
- The preview is built from live state when the dialog opens and rebuilt if that state changes before the operator
  confirms (spec FR-050).
- Every attempt writes one INFO line from the logger `fukuii.tui.ops`:
  `action=<name> params=<…> outcome=<confirmed|cancelled|refused: reason|failed: reason> by=tui` (spec FR-051). An
  action that runs for long (`dbcheck`, `detect-ip`, `bundle`) also writes `outcome=started` when it is confirmed, so
  that a node that ends while it runs leaves a record that it began. A parameter that is a secret is never a parameter
  of any action here.
- With `fukuii.tui.read-only = true` only the Observe rows run (spec FR-053).
- "New" in the last column means code this spec adds outside `console/`; the owner is in the plan.
- Each row's first cell is the action's name in the registry (`console/ops/Actions.scala`). `ActionsContractSpec`
  reads this file and fails when a row and the registry differ in name or class.

## Observe

| Action | Shows | Carried out by |
| --- | --- | --- |
| `config` | The effective configuration under `fukuii` as `key = value`, filterable; secrets hidden (spec FR-058) | New in `console/`: `ConfigView`, a render of the resolved `Config` through `Redaction` |
| `tunables` | Log levels, the outgoing peer limit, trusted and kept peers, blocked addresses, the miner and coinbase (ETC), the RPC servers, alert thresholds: what changes without a restart, as it is now, and the command that changes each | Reads only |
| `deps` | Each subsystem, what it depends on, its health bar and limiting check | `Health` |
| `test-rpc` | For each enabled transport: the address, the answer to `web3_clientVersion` (HTTP, HTTPS, WebSocket, IPC), and for the Engine API that an unauthenticated request is refused; the time each took | New in `console/`: `EndpointProbe`, a client on the node's own listeners |
| `net` | The listening address and port, the address the node advertises, the external address last detected, by which step and how long ago, how detection is configured, and the inbound peer count | Reads `NetworkAddressInfo` (new: what `ExternalIPDetector` found is kept, not only logged) |
| `art` | Every sprite, pose and frame | `ArtStore` |
| `lookup` | Block, transaction, receipt, account | The node's stores, as the JSON-RPC block, transaction and account methods read them |

## Adjust

| Action | Preview states | Carried out by | Undo |
| --- | --- | --- | --- |
| `loglevel` | The logger, the level it logs at now, the level set on it, and the level after. A name no logger has is refused, with the nearest names | `AdminService.changeLogLevel` (which is `AdminService.setLogLevel`); the levels from `AdminService.logLevel` and `changedLogLevels` beside it | `loglevel reset` returns every changed logger to the level it had before its first change |
| `maxpeers` | The outgoing limit now and after and the connected count; that no connection is closed and the incoming limit does not change; a warning when the new limit is under the count the node tries to keep | `PeerManagerActor.SetMaxPeersCmd` via `AdminService.maxPeers` | Set the old value |
| `peer trust` | The peer's id, client and address; that a trusted peer is exempt from pruning. Refused for a peer that dialled this node: there is no enode to keep | `AdminService.addTrustedPeer` | `peer untrust` |
| `peer untrust` | The peer's id; that it can be pruned again | `AdminService.removeTrustedPeer` | `peer trust` |
| `threshold` | The threshold now and after; the alerts that would fire or clear at once; the configuration line that makes it permanent | `console/` only: the session's `AlertThresholds` | `threshold reset` |

## Disrupt

| Action | Preview states | Carried out by | Undo |
| --- | --- | --- | --- |
| `peer add` | The enode; that the node will dial it and keep dialling it; the current and maximum peer counts | `AdminService.addPeer` | `peer drop` |
| `peer drop` | The peer's id, client, address, age and score; that it may reconnect | `AdminService.removePeer` | It may reconnect by itself; `peer add` |
| `peer block` | The address; the connected peers at that address that will be dropped; that the block lasts until restart | `AdminService.blockIP`, which this spec makes block (B-277): the address goes on the operator's list, the peers at it are disconnected, and `OperatorBlockingBlacklist` refuses it on dial and on accept | `peer unblock` |
| `peer unblock` | The address | `AdminService.unblockIP` | `peer block` |
| `mining start` | Always refused, with the reason: a miner that was off at the start cannot be made, and a miner that was stopped cannot start again in this process (B-278, B-279) | Nothing | Nothing to undo |
| `coinbase` | The address now and after; that blocks mined from now pay the new one | `EthMiningService.setEtherbase` | Set the old address |
| `rpc stop` | The address and port; the namespaces served; the open connections that drop; that readiness probes on that port will fail | New: `ServerLifecycle.stop` on the HTTP and WebSocket servers (`jsonrpc/server/http/`) | `rpc start` |
| `rpc start` | The address and port it will bind; the namespaces; a warning if a sensitive namespace would be reachable from another host. Refused for a transport that was not enabled at the start | New: `ServerLifecycle.start` | `rpc stop` |
| `dbcheck` | What it reads; that it is the check run at start; that on finding an inconsistency it reports it and stops nothing; that it says nothing on a node that took its state by SNAP or takes its blocks from a consensus client | New: `StorageConsistencyChecker.scan`, which returns its result instead of ending the node | Nothing to undo |
| `detect-ip` | The services it contacts, by host name, and that they will see this node's address; that it reports and changes nothing the node advertises | `ExternalIPDetector.detect` | Nothing to undo |
| `bundle` | The path; every file it will write and its size; that it holds peer addresses and the node id; that it goes nowhere | New in `console/`: `SupportBundle` | Delete the file |

A support bundle holds: build information; the effective configuration, redacted; the log ring; the alerts and each
health check with its state and since when; every view as text; the database's figures; a thread dump;
garbage-collector and memory figures. It holds no key, no keystore, no JWT secret, no state and no chain data. It is
written for the node's user alone, under `<datadir>/support/`.

## Halt

| Action | Preview states | Carried out by | Undo |
| --- | --- | --- | --- |
| `mining stop` | The hash rate that stops and the coinbase; that the miner cannot be started again until the node restarts | `EthMiningService.stopMiner` | Restart the node |
| `quit` | That the node process ends. On an ETH node with a consensus client connected: that the consensus client loses its execution client and a validator using this node misses duties until it is back. On an ETC node that is mining: that mining stops. The connected peers and the open RPC transports that close. That the TUI cannot start the node again. | The existing quit path (`TuiUpdater.restoreThenQuit`, `StdNode.shutdown`) | Start the node from a shell |

The word to type is `stop`.

`mining stop` is a Halt and not a Disrupt because nothing in the process undoes it: the miner's coordinator ends
when it is told to stop, and `miner_start` does not make another (B-279).

## Not offered, and why

Shown in the Ops view (spec FR-056).

| Requested | Reason |
| --- | --- |
| Start the node, restart the node | The TUI runs inside the node's process (D-1) |
| Restart the Engine API server, peer discovery or sync | None can be stopped and started again without ending the process (research §7.2) |
| Reload configuration from file | No key is re-read after start; `tunables` lists what changes at run time |
| Create a snapshot | Spec 013 has no code |
| Compact the database | A whole-database compaction holds the database's lock for its whole run: the node would import nothing until it ended, which on a large database may take hours and cannot be stopped. Offering it needs a change to how the data source locks (B-281). This departs from spec FR-054, which lists it |
| Change the pruning mode or the state scheme | Needs a restart, and for the scheme a resync |
