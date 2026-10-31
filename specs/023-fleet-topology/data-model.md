# Data model: fleet topology (spec 023)

**Date**: 2026-10-31. Everything here is additive to the node; no existing on-disk format, column family or RPC
response changes. Field names are shared with specs 024–026 and Orbita and must be used exactly.

## 1. `Role` (enum)

`src/main/scala/com/chipprbots/ethereum/utils/Role.scala`

| Case | Wire form (`key`) | `isFollower` | Writes canonical state | Summary |
| --- | --- | --- | --- | --- |
| `Ingest` | `ingest` | no | **yes** (single writer, DC-3) | syncs, imports, serves RPC in the single-node profile, exports records (025) |
| `Replica` | `replica` | yes | no | follows `ingest` by records, serves block-pinned reads inside a retention window (026) |
| `Archive` | `archive` | yes | no | like `replica`, retains every block (026) |
| `Sim` | `sim` | no | no | executes `eth_call`, `eth_simulateV1`, `debug_trace*` over a follower's state; no store beyond caches |

```scala
enum Role(val key: String, val isFollower: Boolean):
  case Ingest  extends Role("ingest", false)
  case Replica extends Role("replica", true)
  case Archive extends Role("archive", true)
  case Sim     extends Role("sim", false)

object Role:
  def fromString(s: String): Either[String, Role] =
    values.find(_.key == s.trim.toLowerCase).toRight(s"unknown role '$s'; expected one of ${values.map(_.key).mkString(", ")}")
```

`InstanceConfig` exposes `val chainKey: String` and `val role: Role`; `RoleProfile(role)`
(`nodebuilder/RoleProfile.scala`)
derives the start plan and the served-method policy (contracts/role-matrix.md).

## 2. Lifecycle state

`src/main/scala/com/chipprbots/ethereum/admin/LifecycleController.scala`

| State | Entered by | `ready` can be true | Accepts new RPC work | Imports | Leaves by |
| --- | --- | --- | --- | --- | --- |
| `Running` | start (no `fleet.fenced` marker) | yes | yes | yes (ingest) | `drain`, `fence`, `stop` |
| `Draining` | `drain`, or SIGTERM (first half) | **no** | no (HTTP 503 on the RPC port; admin still answers) | in-flight block finishes, no new import | `stop`, or drain timeout → `Stopping` when the drain came from SIGTERM; otherwise stays `Draining` until `stop` |
| `Fenced` | `fence` (ingest only), or start with `fleet.fenced` present | no | reads yes; transactions refused (-32021); Engine API listener closed | **no** | `unfence` → `Running`; `stop` |
| `Stopping` | `stop`, drain timeout after SIGTERM | no | no | no | process exit |

Transitions not in the table answer `409 invalid_transition` on the admin API. `fence`/`unfence` on a role other than
`ingest` answer `400 not_supported_by_role`. The in-memory state is an `AtomicReference[LifecycleState]`; `Fenced` is
the only state with a durable twin (§4).

## 3. Health shape (`GET /health` on the admin port)

JSON object; all fields present unless the row says the field is conditional. Integers are JSON numbers (every value
fits in 53 bits: `chainId` up to 7091047534 for Platåberget; block numbers < 2^53). Hashes are 0x-prefixed lowercase
hex, 32 bytes. `null` is used where a value is not yet known; a field is never omitted because of state, only because
of role.

| Field | Type | Present for | Meaning / source |
| --- | --- | --- | --- |
| `role` | string: `ingest` \| `replica` \| `archive` \| `sim` | all | `InstanceConfig.role.key` |
| `chainKey` | string | all | `InstanceConfig.chainKey` |
| `chainId` | integer | all | `BlockchainConfig.chainId` of the chain (not `networkId`) |
| `head` | object `{ "number": integer, "hash": string }` | all | ingest/followers: best block (`BlockchainReader.getBestBlock`); sim: the follower head it last pinned, or `null` object fields before the first pin |
| `finalized` | object `{ "number": integer, "hash": string }` | all | ETH family: `ForkChoiceManager`'s finalized block (null fields until the first `forkchoiceUpdated`); ETC family: **block 0** (D-6: only genesis is final) |
| `sourceLag` | integer or `null` | all | blocks behind the source: ingest = best known peer height − head (null before any peer status); followers = exporter head on the record stream − applied head (026); sim = follower head − pinned head |
| `ready` | boolean | all | per-role readiness (contracts/role-matrix.md); always `false` in `Draining`, `Fenced`, `Stopping` |
| `fenced` | boolean | all | `true` iff lifecycle state is `Fenced` (equivalently `fleet.fenced` present) |
| `lifecycle` | string: `running` \| `draining` \| `fenced` \| `stopping` | all | §2 state; informative, additive to `ready`/`fenced` |
| `exportLag` | integer or `null` | **role = ingest only** | 025 FR-006: head − export position; `null` until 025's exporter registers |
| `exportQueueDepth` | integer or `null` | ingest only | 025 FR-006 |
| `exportBufferBytes` | integer or `null` | ingest only | 025 FR-006 |
| `exportLastError` | string or `null` | ingest only | 025 FR-006; `null` when none |
| `retentionWindow` | object `{ "fromBlock": integer, "toBlock": integer }` or `null` | **followers only** (`replica`, `archive`) | 026 DC-3; `archive` reports `fromBlock = 0`; `null` until 026's source registers |
| `version` | string | all | `InstanceConfig.clientVersion` |
| `timestamp` | integer (epoch ms) | all | when the snapshot was taken |

Example (ingest, Sepolia, running):

```json
{
  "role": "ingest", "chainKey": "sepolia", "chainId": 11155111,
  "head": { "number": 9123456, "hash": "0x6b…e1" },
  "finalized": { "number": 9123392, "hash": "0x2c…9a" },
  "sourceLag": 0, "ready": true, "fenced": false, "lifecycle": "running",
  "exportLag": null, "exportQueueDepth": null, "exportBufferBytes": null, "exportLastError": null,
  "version": "fukuii/v0.12.0/linux-amd64/openjdk-25", "timestamp": 1793490000000
}
```

The same figures are exported as Prometheus gauges with tags `role`, `chain` (`FleetMetrics`, prefix `app_`):
`app_fleet_head_number`, `app_fleet_finalized_number`, `app_fleet_source_lag` (−1 when unknown), `app_fleet_ready`
(0/1), `app_fleet_fenced` (0/1), `app_fleet_lifecycle_state` (0 running, 1 draining, 2 fenced, 3 stopping).

A JSON Schema for validation lives in the harness at `ops/fleet-harness/schema/health.schema.json` (task T076).

## 4. AppState keys (durable, `AppStateStorage.Keys`)

Namespace `Namespaces.AppStateNamespace`, string keys, string values, as every key in
`db/storage/AppStateStorage.scala:621-655`. Dotted names follow 022's `snap.heal.*` convention. **Every write below is
one `updateSync` batch** (022 `data-model.md`, "Invariant marker").

| Key | Value | Written when | Read when | Removed when |
| --- | --- | --- | --- | --- |
| `fleet.chainKey` | the `chainKey` string | first start on a datadir that has none (same batch as the genesis first-write path `GenesisDataLoader` uses for `snap.heal.datadirCreated`, `NodeBuilder.scala:1283-1289`) | every start, before any listener binds; a different value is a named fatal error `DatadirOwnedByOtherChain` | never |
| `fleet.role` | the role `key` | same batch as `fleet.chainKey` | every start; a different value is a named fatal error `DatadirOwnedByOtherRole` (a replica datadir is not an ingest datadir and vice versa) | never |
| `fleet.fenced` | epoch millis of the fence, decimal string | `fence` on ingest, **after** `SyncProtocol.Pause`, the fenceable hooks and the Engine API close (research D-5) | every start (ingest starts in `Fenced` when present; followers/sim ignore it) and by `GET /health` | `unfence` |

`AppStateStorage` API (task T043, T062):

```scala
def fleetIdentity(): Option[(String, String)]                 // (chainKey, roleKey)
def recordFleetIdentity(chainKey: String, roleKey: String): DataSourceBatchUpdate
def isFenced(): Boolean
def fencedAt(): Option[Long]
def setFenced(nowMillis: Long): DataSourceBatchUpdate
def clearFenced(): DataSourceBatchUpdate
```

Backfill for pre-023 datadirs: on first start after upgrade, `fleet.chainKey`/`fleet.role` are absent; the node
records `(chainKey, role)` from its own `InstanceConfig` (the operator's declared identity). This is the only
"migration"; nothing else on disk changes.

## 5. Configuration keys (HOCON, under `fukuii`)

`src/main/resources/conf/base/fukuii.conf` (all new keys have defaults; every existing profile loads unchanged).

| Key | Type | Default | Set by | Notes |
| --- | --- | --- | --- | --- |
| `fukuii.chain` | string | `${fukuii.blockchains.network}` | `--chain <key>` → `-Dfukuii.chain` | the `chainKey`; until 024 lands it must be one of today's `knownNetworks`; 024 makes it any ChainSpec key |
| `fukuii.role` | string | `"ingest"` | `--role <role>` → `-Dfukuii.role` | parsed by `Role.fromString`; invalid → exit 2 |
| `fukuii.datadir` | path | `${user.home}"/.fukuii/"${fukuii.chain}"/"${fukuii.role}` (was `…/${fukuii.blockchains.network}`, `fukuii.conf:6`) | profile or operator | legacy layout detector (research D-4); every derived path follows |
| `fukuii.admin.enabled` | boolean | `true` | — | `false` only for tests/tools; the harness and Orbita require it |
| `fukuii.admin.interface` | string | `"localhost"` | — | validator **warns** when not loopback (constitution VI) |
| `fukuii.admin.port` | int | `8560` | — | validator fails on clash with RPC HTTP/WS, Engine API, metrics, P2P ports (`ConfigValidator.checkPortClash`, `ConfigValidator.scala:83`) |
| `fukuii.admin.drain-timeout` | duration | `30.seconds` | — | bound for in-flight work on `drain`; must be < the orchestrator's SIGTERM grace; `shutdown-timeout` (15 s, `fukuii.conf:17`) still bounds the stop half |
| `fukuii.admin.ready-lag-blocks` | int | `64` | — | follower/sim readiness bound (026 adjusts the default from its window W) |
| `fukuii.tx-forward.endpoint` | string or `null` | `null` | per deployment | the chain's ingest JSON-RPC URL (e.g. `http://sepolia-ingest:8545`); `null` on a non-ingest role ⇒ `eth_sendRawTransaction` answers -32020 |
| `fukuii.tx-forward.max-per-second` | int | `50` | — | token-bucket refill rate per process; empty bucket ⇒ -32022 |
| `fukuii.tx-forward.timeout` | duration | `5.seconds` | — | per forwarded request |

Removed keys (S1): the root block `fukuii-runtime.instances` — presence is a **fatal** configuration error:
`fukuii-runtime.instances is no longer supported: run one process per chain and role (fukuii <network> is
--chain <network> --role ingest); see docs/runbooks/operating-modes.md`.

## 6. JSON-RPC error codes (additive)

`src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcError.scala`

| Code | Name | Message | When |
| --- | --- | --- | --- |
| `-32020` | `NotServedByRole` | `method <m> is not served by role <r>` ; `data: { "role": "<r>", "method": "<m>" }` | `RoleMethodPolicy` refuses the method for this role; also `eth_sendRawTransaction` on a non-ingest role with no `tx-forward.endpoint` |
| `-32021` | `IngestFenced` | `ingest is fenced; transactions are not accepted` | any transaction submission (local or forwarded) while `Fenced` |
| `-32022` | `ForwardRateCapExceeded` | `transaction forwarding rate cap exceeded` ; `data: { "maxPerSecond": n }` | `TxForwarder` bucket empty |

Unchanged: `-32601 MethodNotFound` keeps meaning "no such method / namespace not enabled". The 018 R1 executor
rejection error (full queue, timeout) is defined by 018 and surfaces unchanged from `sim`.

## 7. Harness result record

`ops/fleet-harness/results/<scenario>-<yyyymmdd>-<shortsha>.json`

```json
{
  "scenario": "sc003", "tree": "<git sha>", "image": "<docker image id>", "host": "<hostname>",
  "startedAt": "<iso>", "durationSeconds": 900,
  "baseline": { "etc": { "importP99Ms": 0, "readP99Ms": 0, "bandMs": 0 }, "eth": { … } },
  "measured": { "etc": { "importP99Ms": 0, "readP99Ms": 0 }, "eth": { … } },
  "rejections": { "-32020": 0, "-32022": 0, "<018-r1-code>": 0 },
  "verdict": "PASS" | "FAIL", "notes": ""
}
```
