# Metrics Reference

This page is the operator-facing reference for every Prometheus metric Fukuii exposes,
what each one means, and where it is visualized. For the narrative monitoring guide
(alert rules, Kamon, logging) see [SNAP Sync Monitoring](monitoring-snap-sync.md).

## Endpoint and configuration

Metrics are served by an embedded Prometheus HTTP server:

```hocon
fukuii.metrics {
  # Disabled by default — enable it for any monitored deployment.
  enabled = true

  # Default port is 13798; production compose files (Barad-dûr) override this to 9095.
  port = 13798
}
```

Scrape `http://<node>:<port>/metrics` (inside the Barad-dûr containers: `:9095/metrics`).
All application metrics carry the `app_` prefix. Conventions to know when reading the tables:

- **Counters** are monotonic and exported with a `_total` suffix; rate them in PromQL.
- **Timers** (Micrometer) export three series: `<name>_seconds_count`, `<name>_seconds_sum`,
  `<name>_seconds_max`.
- **Gauges** are point-in-time values set by the emitting subsystem.
- JVM and process metrics (`jvm_*`, `process_*`) are auto-registered alongside the app metrics.

> **Multi-instance caveat (Bug 29):** all instances of a multi-chain process write into one
> shared registry, so every `/metrics` endpoint serves identical content. Run one container
> per chain for per-chain observability.

**Dashboards referenced below** ("Surfaced in" column):

- **SNAP Sync** — `ops/grafana/Sync/fukuii-snap-sync.json` (rows: Phase & Pivot, Backpressure,
  Progress, Peer Pool Health, Request Health, State Healing)
- **Node Health** — `ops/grafana/ETC Node/fukuii-node-health.json`
- A dash (—) means the metric is exported but not on either of those two dashboards.

Some registered metrics have no emitter wired yet; these are marked **Reserved** and always
read 0. They are listed so operators don't mistake a permanent 0 for a healthy signal.

## SNAP sync phase and pivot

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_snapsync_phase_current_gauge` | gauge | Current SNAP sync phase (see value table below); published by the sync progress monitor on every progress tick. | SNAP Sync: Phase & Pivot; Node Health: SNAP phase |
| `app_snapsync_phase_time_seconds_gauge` | gauge | Seconds spent in the current phase. | SNAP Sync: Phase & Pivot (Time in Phase) |
| `app_snapsync_totaltime_minutes_gauge` | gauge | Minutes since SNAP sync started. | SNAP Sync: Phase & Pivot (Total Sync Time) |
| `app_snapsync_pivot_block_number_gauge` | gauge | Pivot block number selected by the SNAP sync controller. | SNAP Sync: Phase & Pivot (Pivot Block) |
| `app_snapsync_pivot_refreshed_total` | counter | In-place pivot refreshes since sync start (controller rolls the download root when peers stop serving the old one). | SNAP Sync: Phase & Pivot (Pivot Refreshes) |

### `phase_current` values

| Value | Phase | Notes |
|-------|-------|-------|
| 0 | Idle | Not started |
| 1 | AccountRangeSync | Downloading account ranges with Merkle proofs |
| 3 | ByteCodeAndStorageSync | Bytecode + storage download (combined phase; legacy values 2 and 4 are reserved and never emitted) |
| 5 | StateHealing | BFS frontier rebuild + missing-trie-node healing |
| 6 | StateValidation | Verifying state completeness |
| 7 | ChainDownloadCompletion | Downloading remaining chain to the head |
| 8 | Completed | SNAP sync finished |
| 9 | Dormant | Critical-failure backoff: data preserved, waiting (3–20 min exponential) for peers to re-index snapshots |

## Account range download

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_snapsync_accounts_synced_gauge` | gauge | Cumulative accounts written by the account-range phase. | SNAP Sync: Progress (Accounts Progress, Accounts Synced) |
| `app_snapsync_accounts_estimated_total_gauge` | gauge | Estimated total accounts at the pivot; denominator of the progress % panel. | SNAP Sync: Progress |
| `app_snapsync_accounts_throughput_overall_gauge` | gauge | Accounts/s averaged since sync start. | — |
| `app_snapsync_accounts_throughput_recent_gauge` | gauge | Accounts/s over the last 60 s. | SNAP Sync: Progress (Throughput) |
| `app_snapsync_accounts_active_peers_gauge` | gauge | Peers the account coordinator can dispatch to right now (known − stateless − snapless − cooling-down). | SNAP Sync: Peer Pool Health |
| `app_snapsync_accounts_requests_total` | counter | Reserved — account-range request counter; no emitter wired (always 0). | — |
| `app_snapsync_accounts_requests_failed_total` | counter | Reserved — failed account-range requests; no emitter wired (always 0). | SNAP Sync: Request Health |
| `app_snapsync_accounts_download_timer` | timer | Reserved — account-range download latency; no emitter wired (always 0). | SNAP Sync: Request Health (Request Latency) |

## Bytecode download

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_snapsync_bytecodes_downloaded_gauge` | gauge | Cumulative contract bytecodes downloaded. | SNAP Sync: Progress (Bytecodes Progress) |
| `app_snapsync_bytecodes_estimated_total_gauge` | gauge | Estimated total bytecodes; progress % denominator. | SNAP Sync: Progress |
| `app_snapsync_bytecodes_throughput_overall_gauge` | gauge | Bytecodes/s since sync start. | — |
| `app_snapsync_bytecodes_throughput_recent_gauge` | gauge | Bytecodes/s over the last 60 s. | SNAP Sync: Progress (Throughput) |
| `app_snapsync_bytecode_queue_depth_gauge` | gauge | Bytecode coordinator pending-task queue depth. | SNAP Sync: Backpressure |
| `app_snapsync_bytecode_backpressure_gauge` | gauge | 1 while the bytecode coordinator has backpressure engaged, 0 when released. | SNAP Sync: Backpressure |
| `app_snapsync_bytecode_active_peers_gauge` | gauge | Reserved — bytecode-coordinator active peers; no emitter wired (always 0). | — |
| `app_snapsync_bytecodes_requests_total` | counter | Reserved — bytecode request counter; no emitter wired (always 0). | — |
| `app_snapsync_bytecodes_requests_failed_total` | counter | Reserved — failed bytecode requests; no emitter wired (always 0). | SNAP Sync: Request Health |
| `app_snapsync_bytecodes_download_timer` | timer | Reserved — bytecode download latency; no emitter wired (always 0). | SNAP Sync: Request Health (Request Latency) |

## Storage range download

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_snapsync_storage_slots_synced_gauge` | gauge | Cumulative storage slots downloaded. | SNAP Sync: Progress (Storage Slots Progress) |
| `app_snapsync_storage_slots_estimated_total_gauge` | gauge | Estimated total storage slots; progress % denominator. | SNAP Sync: Progress |
| `app_snapsync_storage_throughput_overall_gauge` | gauge | Slots/s since sync start. | — |
| `app_snapsync_storage_throughput_recent_gauge` | gauge | Slots/s over the last 60 s. | SNAP Sync: Progress (Throughput) |
| `app_snapsync_storage_queue_depth_gauge` | gauge | Storage coordinator pending-task queue depth. | SNAP Sync: Backpressure |
| `app_snapsync_storage_backpressure_gauge` | gauge | 1 while the storage coordinator has backpressure engaged, 0 when released. | SNAP Sync: Backpressure |
| `app_snapsync_storage_pending_tries_size_gauge` | gauge | Per-account streaming storage tries held in memory; each is bounded to ~8 MiB, so this × 8 MiB bounds the storage-phase heap footprint. | — |
| `app_snapsync_storage_active_peers_gauge` | gauge | Reserved — storage-coordinator active peers; no emitter wired (always 0). | — |
| `app_snapsync_storage_requests_total` | counter | Reserved — storage request counter; no emitter wired (always 0). | — |
| `app_snapsync_storage_requests_failed_total` | counter | Reserved — failed storage requests; no emitter wired (always 0). | SNAP Sync: Request Health |
| `app_snapsync_storage_download_timer` | timer | Reserved — storage download latency; no emitter wired (always 0). | SNAP Sync: Request Health (Request Latency) |

## State healing

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_snapsync_healing_nodes_healed_gauge` | gauge | Cumulative trie nodes fetched and written by the healing phase, published on every progress tick. | SNAP Sync: Progress, State Healing (Nodes Healed) |
| `app_snapsync_healing_frontier_pending_gauge` | gauge | Healing frontier backlog: missing nodes queued in the healing coordinator and awaiting fetch. | SNAP Sync: State Healing (Frontier Backlog) |
| `app_snapsync_healing_active_requests_gauge` | gauge | In-flight `GetTrieNodes` healing requests held by the healing coordinator. | SNAP Sync: State Healing (Active Heal Requests) |
| `app_snapsync_healing_rebuild_visited_gauge` | gauge | Nodes visited so far by the current frontier-rebuild BFS walk over locally stored state (`[HEAL-BFS]` log lines); updated every 100 k visits and at each BFS level boundary. Resets when a new walk starts; stays flat when healing resumes from a complete persisted frontier. | SNAP Sync: State Healing (BFS Visited, BFS Walk Progress, BFS Progress & Rate) |
| `app_snapsync_healing_throughput_overall_gauge` | gauge | Healed nodes/s since healing started. | SNAP Sync: State Healing (Healing Throughput) |
| `app_snapsync_healing_throughput_recent_gauge` | gauge | Healed nodes/s over the last 60 s. | SNAP Sync: Progress (Throughput), State Healing |
| `app_snapsync_healing_requests_total` | counter | Reserved — healing request counter; no emitter wired (always 0). | SNAP Sync: State Healing (Healing Request Rate) |
| `app_snapsync_healing_requests_failed_total` | counter | Reserved — failed healing requests; no emitter wired (always 0). | SNAP Sync: Request Health, State Healing |
| `app_snapsync_healing_timer` | timer | Reserved — healing batch latency; no emitter wired (always 0). | SNAP Sync: Request Health (Request Latency) |
| `app_snapsync_validation_missing_nodes_gauge` | gauge | Reserved — missing nodes found by state validation; no emitter wired (always 0). | SNAP Sync: Request Health (Data Integrity) |

> ### Reading the healing walk
>
> After a restart mid-healing, the coordinator rebuilds its frontier with a level-order BFS
> over the locally stored state trie. `healing_rebuild_visited` climbs monotonically during
> **one** walk and is reset to a fresh count whenever a new walk starts — treat it as
> per-walk progress, not a lifetime counter. The dashboard's "BFS Walk Progress" panel
> divides it by a **~119 M node estimate** for ETC mainnet state, so the percentage is a
> rough yardstick, not an exact ETA. While the walk runs, `nodes_healed` and
> `frontier_pending` pulse together: each completed BFS level delivers a batch of
> newly discovered missing nodes (backlog rises), heal requests then drain it
> (`active_requests` shows the in-flight `GetTrieNodes`), and `nodes_healed` steps up.
> If the walk is skipped entirely (`[HEAL-RESTART] Resumed ... from a complete persisted
> snapshot` in the logs), `rebuild_visited` stays flat — that is normal.

## SNAP peers, requests, and data integrity

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_snapsync_peers_capable_gauge` | gauge | Handshaked peers that negotiated the SNAP/1 capability, tracked by the SNAP sync controller. | SNAP Sync: Peer Pool Health |
| `app_snapsync_peers_blacklisted_total` | counter | Every blacklist event, regardless of reason group — incremented by the shared blacklist on each add (duplicate of the network counters, kept so the SNAP dashboard can stay within `snapsync_*`). | SNAP Sync: Peer Pool Health (Demotion + Eviction Rate) |
| `app_snapsync_peers_snapless_confirmed_total` | counter | Peers demoted as snapless by the account coordinator after 3 strikes (advertise SNAP but never serve it). | SNAP Sync: Peer Pool Health |
| `app_snapsync_peers_stateless_confirmed_total` | counter | Peers demoted as stateless by the account/storage coordinators after 3 strikes (no longer hold the pivot's state). | SNAP Sync: Peer Pool Health |
| `app_network_lagging_peer_evicted_total` | counter | Peers evicted by the network peer manager's lagging-peer check (best block too far behind). | SNAP Sync: Peer Pool Health |
| `app_snapsync_requests_timeouts_total` | counter | Reserved — SNAP request timeouts; no emitter wired (always 0). | SNAP Sync: Request Health; Node Health: Errors |
| `app_snapsync_requests_retries_total` | counter | Reserved — SNAP request retries; no emitter wired (always 0). | SNAP Sync: Request Health |
| `app_snapsync_proofs_invalid_total` | counter | Reserved — responses failing Merkle-proof verification; no emitter wired (always 0). | SNAP Sync: Request Health (Data Integrity) |
| `app_snapsync_responses_malformed_total` | counter | Reserved — undecodable SNAP responses; no emitter wired (always 0). | SNAP Sync: Request Health (Data Integrity) |
| `app_snapsync_validation_failures_total` | counter | Reserved — state-validation failures; no emitter wired (always 0). | SNAP Sync: Request Health; Node Health: Errors |
| `app_snapsync_errors_total` | counter | Reserved — general SNAP sync errors; no emitter wired (always 0). | SNAP Sync: Request Health; Node Health: Errors |

## Network (`app_network_*`)

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_network_peers_incoming_handshaked_gauge` | gauge | Currently handshaked inbound peers. | Node Health: Handshaked Peers/Peers; SNAP Sync: Peer Pool |
| `app_network_peers_outgoing_handshaked_gauge` | gauge | Currently handshaked outbound peers. | Node Health: Handshaked Peers/Peers; SNAP Sync: Peer Pool |
| `app_network_peers_pending_gauge` | gauge | Connections in progress (not yet handshaked), from the peer manager's periodic stats. | Node Health: Peers |
| `app_network_tried_peers_gauge` | gauge | Distinct discovered nodes this process has attempted to connect to. | — |
| `app_network_discovery_foundPeers_gauge` | gauge | Nodes currently known to discovery (DHT + DNS). | Node Health: Peers |
| `app_network_peers_blacklisted_gauge` | gauge | Current blacklist size (live entries in the blacklist cache). | Node Health: Peers; SNAP Sync: Peer Pool Health |
| `app_network_peers_blacklisted_fastSyncGroup_counter_total` | counter | Blacklist events attributed to fast-sync reasons. | — |
| `app_network_peers_blacklisted_regularSyncGroup_counter_total` | counter | Blacklist events attributed to regular-sync reasons. | Node Health: Errors |
| `app_network_peers_blacklisted_p2pGroup_counter_total` | counter | Blacklist events attributed to P2P/subprotocol reasons (the dominant group in practice). | — |
| `app_network_messages_sent_counter_total` | counter | Wire-protocol messages sent across all peers. | Node Health: Network message rate |
| `app_network_messages_received_counter_total` | counter | Wire-protocol messages received across all peers. | Node Health: Network message rate |
| `app_network_peer_info` | gauge (const 1) | One labelled series per handshaked peer — labels `peer`, `remote_address`, `client`, `client_name`, `capability` (e.g. `eth/68`), `network_id`, `direction`, `snap`. Series is removed on disconnect, so cardinality is bounded by live peer count; aggregate with `count by (client_name)(...)`. | Network Nodes dashboard (`ops/grafana/Network/fukuii-network-nodes.json`) |
| `app_network_peer_best_block` | gauge | Peer's advertised head block at handshake (labels: `peer`); refreshed by the periodic best-block re-probe. | — |
| `app_network_lagging_peer_evicted_total` | counter | (Listed under SNAP peers above — emitted by the network peer manager.) | SNAP Sync: Peer Pool Health |

## JSON-RPC (`app_json_rpc_*`)

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_json_rpc_methods_success_counter_total` | counter | RPC calls that returned a successful response. | Troubleshooting dashboard |
| `app_json_rpc_methods_error_counter_total` | counter | RPC calls that returned a JSON-RPC error object. | Troubleshooting dashboard |
| `app_json_rpc_methods_exception_counter_total` | counter | RPC calls that threw an unhandled exception in the controller. | Troubleshooting dashboard |
| `app_json_rpc_notfound_calls_counter_total` | counter | Calls to methods that don't exist (method-not-found). | — |
| `app_json_rpc_healthcheck_error_counter_total` | counter | Failed internal healthcheck evaluations. | — |
| `app_json_rpc_methods_timer_seconds` | timer | Per-method RPC latency, labelled `method=` (e.g. `eth_blockNumber`); one `_count`/`_sum`/`_max` triple per method. | Troubleshooting dashboard |

(The "Troubleshooting dashboard" is `ops/grafana/ETC Node/fukuii-node-troubleshooting-dashboard.json`.)

## Logging, JVM, and process

| Metric | Type | Meaning | Surfaced in |
|--------|------|---------|-------------|
| `app_logback_events_total` | counter | Log events by `level=` (`error`/`warn`/`info`/`debug`/`trace`), bound from Logback at metrics startup. Rate the `error` series for a cheap fault signal. | — |
| `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` | gauge | JVM memory by `area=` (heap/nonheap) and pool, from the bundled JVM instrumentation. | Node Health: JVM heap¹ |
| `jvm_gc_collection_seconds` | summary | GC pause time and count per collector; `rate(..._sum[1m])` ≈ fraction of wall time in GC. | Node Health: GC time |
| `jvm_threads_current`, `jvm_threads_deadlocked` | gauge | Live and deadlocked JVM thread counts. | Node Health: JVM threads |
| `process_cpu_seconds_total`, `process_resident_memory_bytes`, `process_open_fds`, ... | counter/gauge | Standard process exporter metrics. | — |

¹ The Node Health JVM-heap panel queries the older `jvm_memory_bytes_used` names; the
current exporter emits `jvm_memory_used_bytes` — update the panel if heap shows "No data".

## Families that register once their subsystem starts

The gauges above are what a node mid-SNAP-sync serves. The following `app_*` families are
defined in code but only appear on `/metrics` after their emitting subsystem first runs:

| Family | Emitting subsystem | Examples | Surfaced in |
|--------|--------------------|----------|-------------|
| `app_regularsync_*` | Regular (full) sync block import | `block_current_number_gauge`, `block_bestKnown_number_gauge`, `blocks_imported_total`, `reorg_total`, `reorg_last_depth_gauge`, `blocks_propagation_timer{blocktype=}` | Node Health: Current Block, Blocks Behind, Block height, Import rate |
| `app_sync_block_*` | Per-imported-block stats | `number`, `gasUsed`/`gasLimit`, `transactions`, `uncles`, `difficulty`, `timeBetweenParent_seconds` | Node Health: Block time, Transactions/Gas per block |
| `app_chain_mess_*` | ECBP-1100 (MESS) reorg arbitration | `rejected_total`, `accepted_total`, `gravity_gauge` | — |
| `app_fastsync_*` | Legacy fast sync (fallback mode) | `block_pivotBlock_number_gauge`, `state_downloadedNodes_gauge`, download timers | Node Health: Best Known Block, Block height |
| `app_mining_*` | Internal Ethash miner | `minedblocks_evaluation_timer` | — |
