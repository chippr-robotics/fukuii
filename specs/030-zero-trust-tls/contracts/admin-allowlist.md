# Contract: `admin_` node-allowlist methods (spec 030 FR-056–FR-059, slice 10)

Namespace: `admin`. Not in any default `apis` set for HTTP or WS; in the IPC default (IPC's default already includes
`admin`). Reachable over HTTP/WS only where the transport's `apis` or the caller's identity rule grants `admin`
(FR-059). A caller without `admin` gets `-32601`, like every other `admin_` method.

Modelled on Besu's `perm_addNodesToAllowlist`, `perm_removeNodesFromAllowlist` and `perm_getNodesAllowlist`; names
moved into `admin` (research D-11).

## `admin_addNodesToAllowlist`

- **Params**: `[ [ "enode://<128 hex>@<host>:<port>" | "<128 hex>", … ] ]` — one array, 1 to 256 entries.
- **Result**: `true`.
- **Effect**: all-or-nothing. Under the store lock: re-read the file if it changed since the last load; add every
  entry; write the file atomically (FR-057); swap the in-memory list; entries with an address become dial
  candidates (FR-058). Logged at INFO: `Allowlist: added <n> node(s) [<id prefix>…] by <rule name | transport>`.

## `admin_removeNodesFromAllowlist`

- **Params**: as above.
- **Result**: `true`.
- **Effect**: all-or-nothing, same sequence; then every connected peer no longer listed is disconnected (FR-058).

## `admin_getNodesAllowlist`

- **Params**: `[]`.
- **Result**: `{ "enabled": bool, "file": string | null, "nodes": [ { "id": "<128 hex>", "enode": string | null,
  "sources": ["config" | "file" | "static" | "bootnode"] } ] }`, sorted by id.

## Errors (code `-32000` unless noted; message names the first offending entry)

| Case | Message |
| --- | --- |
| allowlist disabled | `node allowlist is disabled (fukuii.network.peer.node-allowlist.enabled = false)` |
| no file | `node allowlist has no file; runtime edits need fukuii.network.peer.node-allowlist.file` |
| unparseable entry | `-32602` `invalid enode or node id: <entry>` |
| empty or > 256 entries | `-32602` `expected 1 to 256 entries` |
| duplicate in the request | `-32602` `duplicate entry: <id prefix>` |
| add: already listed from the file | `already on the allowlist: <id prefix>` |
| remove: not listed | `not on the allowlist: <id prefix>` |
| remove: only from config, static nodes or bootnodes | `<id prefix> comes from <source> and cannot be removed at runtime` |
| file changed on disk and no longer parses | `allowlist file <path> does not parse (<reason>); fix it before editing` |
| write or rename failed | `could not write <path>: <reason>; nothing changed` |

## FR-052 errors on existing methods (slice 9)

`admin_addPeer`, `admin_addTrustedPeer` and `net_connectToPeer` with an unlisted node, while the allowlist is on:
`-32000` `node <id prefix> is not on the allowlist (fukuii.network.peer.node-allowlist)`.
