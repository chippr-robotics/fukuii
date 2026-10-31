# Contract: role matrix (FR-002, FR-008, DC-9)

One process, one chain, one role. `RoleProfile(role)` (`nodebuilder/RoleProfile.scala`) encodes this table;
`StartPlanSpec` asserts it; `RoleMethodPolicySpec` asserts the namespace rows. Anything a role does not serve answers
JSON-RPC `-32020 NotServedByRole` (data-model §6), never `-32601` and never a wrong answer.

## Listeners bound

| Listener (default port) | `ingest` | `replica` | `archive` | `sim` |
| --- | --- | --- | --- | --- |
| P2P TCP (`network.server-address.port`, 30303) | **yes** | no | no | no |
| Discovery UDP (`network.discovery.port`, 30303) | yes, if `discovery-enabled` | no | no | no |
| Port forwarding (UPnP/NAT-PMP) | yes, if `automatic-port-forwarding` | no | no | no |
| JSON-RPC HTTP (`network.rpc.http`, 8546 base / 8545 profiles) | yes, if enabled (single-node profile: on) | yes | yes | yes |
| JSON-RPC WS / IPC | yes, if enabled | yes, if enabled | yes, if enabled | yes, if enabled |
| Engine API (`network.engine-api.port`, 8551) | yes, if enabled **and** not fenced (ETH family) | **never** | **never** | **never** |
| Admin (`admin.port`, 8560) | **yes** | **yes** | **yes** | **yes** |
| Metrics (`metrics.port`, 13798) | yes, if enabled | yes, if enabled | yes, if enabled | yes, if enabled |

Start plan (`BaseNode.start()` steps that run; order preserved from `StdNode.scala:55-117`):

| Step | `ingest` | `replica` / `archive` | `sim` |
| --- | --- | --- | --- |
| metrics, `fixDatabase`, `repairBestBlockMapping`, `loadGenesisData`, `repairStoredBodies`, `importChainData` | yes | yes (followers hold a store; `importChainData` is a no-op without the key) | **no store**: metrics only; no datadir repairs (sim opens no RocksDB except a cache dir) |
| `startServer`, `loadStaticNodes`, `startPortForwarding`, `startPeerManager`, `startDiscoveryManager` | yes | **no** | **no** |
| `bindInvalidChainReporter`, `bindDesignatedHead` | yes (gated on `engineApiConfig.enabled`, which is `configured && role == Ingest`) | no (gate false) | no |
| admin server | yes (before RPC servers) | yes | yes |
| `startEngineApiServer` | yes (ETH family, enabled, not fenced) | no | no |
| `startJsonRpcHttpServer`, WS, IPC | yes | yes | yes |
| `startSyncController` | yes (not fenced) | **no** in 023; 026 starts the follower apply loop here (`SyncMode.Follow`, 017 row 3) | no |
| `startMining` | yes, if `mining.mining-enabled` (single-node profile option) | **never** (`mining-enabled = true` with this role is a fatal config error) | never |
| DB consistency checks, TUI | yes | yes | consistency checks no; TUI yes |

`shutdown` runs only the reverse of the steps that ran.

## RPC namespaces and methods served

Legend: **S** served locally; **F** forwarded to the chain's `ingest` (FR-010); **–** `-32020`. "within window" means
026 DC-3 applies outside the retention window (the follower's own error passes through unchanged, spec edge case 5).

| Namespace / method group | `ingest` | `replica` | `archive` | `sim` |
| --- | --- | --- | --- | --- |
| `web3_*`, `net_version`, `rpc_modules` | S | S | S | S |
| `net_peerCount`, `net_listening` | S | S (answers `0x0` / `false` truthfully) | S (same) | S (same) |
| `eth_chainId`, `eth_blockNumber`, `eth_syncing`, `eth_gasPrice`, `eth_feeHistory`, `eth_blobBaseFee` | S | S | S | S (from the pinned follower head) |
| `eth_getBlockBy*`, `eth_getTransaction*`, `eth_getBlockReceipts`, `eth_getTransactionReceipt`, `eth_getLogs`, `eth_getUncle*` | S | S within window | S any block | – (route to a follower) |
| `eth_getBalance`, `eth_getCode`, `eth_getStorageAt`, `eth_getTransactionCount`, `eth_getProof` | S | S within window (block-pinned, DC-6) | S any block | – |
| `eth_call`, `eth_estimateGas`, `eth_createAccessList`, `eth_simulateV1` | S (single-node profile; on 018 R1 executors once R1 lands) | S within window (point reads; heavy work is `sim`'s) | S | **S** on 018 R1 executors; state from a follower at a pinned block |
| `debug_trace*`, `trace_*` | S (single-node profile) | – | S (017 row 1: archive serves any N) | **S** on 018 R1 executors |
| `debug_executionWitness` (017) | capture-on option only | – | S (017 row 1) | – |
| `eth_sendRawTransaction`, `eth_sendTransaction` (when `personal` enabled) | **S** — the single admission point (`TxAdmission`); refused with -32021 while fenced | **F** (-32020 with no endpoint; -32022 over the cap) | **F** | **F** |
| `eth_subscribe` (`newHeads`, `logs`, `newPendingTransactions`) | S | S (`newHeads`, `logs` within window) | S | – |
| `eth_mining`, `eth_hashrate`, `eth_getWork`, `eth_submitWork`, `eth_coinbase`, `miner_*` | S (ETC single-node profile with mining on; otherwise today's "not mining" answers) | – | – | – |
| `txpool_*` | S | – | – | – |
| `admin_addPeer`, `admin_removePeer`, `admin_peers`, `admin_nodeInfo` (P2P) | S | – | – | – |
| `admin_*` non-P2P (log level etc.) | S | S | S | S |
| `personal_*`, `qa_*`, `fukuii_*` | S, as configured | S, as configured (no mining-related ones) | same | same |
| `engine_*` | on the Engine API port only, ETH family, not fenced | never bound | never bound | never bound |
| `testing_*` | as configured (devnets only) | – | – | – |
| `mcp_*`, GraphQL | S, as configured | S, as configured | S | S |

Precedence: `RoleMethodPolicy` runs before the `enabledApis` dispatch (`JsonRpcBaseController.scala:50-61`). A method
that is both not configured and not served by the role answers -32020 (role error first), so a client learns the
structural reason.

## Sync mode

| `ingest` | `replica` | `archive` | `sim` |
| --- | --- | --- | --- |
| today's `SyncController`: SNAP or Full per `sync.do-snap-sync`; Engine-API-driven on ETH family; checkpoint sync on ETC (unchanged) | `Follow` (026 FR-002 apply loop; never SNAP, 026 DC-5); 023 alone does not start `SyncController` | `Follow`, retains every block (026 FR-005; 018 pruning archive mode) | none; reads a follower over block-pinned RPC (DC-5, OQ-4) |

## Readiness (`ready` in the health shape)

| Role | `ready` is true when all hold |
| --- | --- |
| `ingest` | lifecycle `Running` ∧ store open with a best block ∧ head advanced within `rpc.health.no-update-duration-threshold` (today's `updateStatus` check, `NodeJsonRpcHealthChecker.scala:69-76`) ∧ (ETC family: ≥ 1 peer; ETH family: Engine API bound ∧ a `forkchoiceUpdated` seen, **or** ≥ 1 peer during pre-merge/devnet operation) ∧ `sourceLag ≤ admin.ready-lag-blocks` |
| `replica` / `archive` | lifecycle `Running` ∧ store open ∧ `sourceLag ≤ admin.ready-lag-blocks` (026 refines with its window) — **never** depends on peers (FR-004, SC-005) |
| `sim` | lifecycle `Running` ∧ the configured follower answers `GET /health` with `ready: true` ∧ `sourceLag ≤ admin.ready-lag-blocks` — never depends on peers |

Readiness is false in `Draining`, `Fenced` and `Stopping` for every role.

## Transaction handling (FR-010)

| Role | Local admission | Forwarding | While fenced |
| --- | --- | --- | --- |
| `ingest` | yes: txpool (`TxAdmission`), gossip to peers, Engine API payload building | n/a | refuses local and forwarded submissions with -32021; stops gossiping |
| `replica` / `archive` / `sim` | **never**; no txpool, no gossip | `TxForwarder` → `tx-forward.endpoint` (`eth_sendRawTransaction`), result or error returned unchanged; token bucket `max-per-second`; `null` endpoint ⇒ -32020 | the ingest's -32021 is passed through |

Per-client quotas, priority and gas budgets are Orbita's (OQ-1); fukuii keeps the FR-003 caps and the per-process
forwarding cap.

## Mining

| `ingest` | `replica` | `archive` | `sim` |
| --- | --- | --- | --- |
| ETC PoW mining is a **single-node-profile option** (`mining.mining-enabled`); no fleet deployment mines (FR-002) | never (fatal config error if enabled) | never | never |

## Health `sourceLag` source

| Role | `sourceLag` |
| --- | --- |
| `ingest` | best known peer/CL height − head (`SyncProtocol.Status.Syncing.progress.target − current`; `0` when `SyncDone`; `null` when `NotSyncing` with no peer) |
| `replica` / `archive` | record-stream head − applied head (026) ; `null` until 026 registers its source |
| `sim` | follower head − pinned head; `null` before the first pin |
