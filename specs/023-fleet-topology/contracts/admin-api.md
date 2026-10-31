# Contract: admin HTTP surface (FR-004, FR-005, FR-009)

**Listener**: `fukuii.admin.interface:fukuii.admin.port`, default `localhost:8560`, plain HTTP, one per process.
Served by `admin.AdminHttpServer` (Pekko HTTP), started in `BaseNode.start()` before the JSON-RPC servers and stopped
last in `shutdown` so a stopping process can still be observed. **It never serves chain JSON-RPC** (FR-009): a `POST /`
with a JSON-RPC body answers `404`.

**Content type**: `application/json; charset=UTF-8` for every response body. Errors use one envelope:

```json
{ "error": { "code": "<string code>", "message": "<human text>" } }
```

| Error code (string) | HTTP | When |
| --- | --- | --- |
| `not_supported_by_role` | 400 | `fence`/`unfence` on a role other than `ingest` |
| `invalid_transition` | 409 | the signal is not allowed from the current lifecycle state (data-model §2) |
| `timeout` | 504 | a synchronous signal did not complete inside its bound (`fence` waits for the Engine API close and the marker sync; bound = `fukuii.admin.drain-timeout`) |
| `not_found` | 404 | unknown path or method |

## Paths

### `GET /health`

Always `200` while the process can answer, whatever its readiness (readiness is the `ready` field, so one probe
gives Orbita the whole picture). Body: the health shape of [data-model.md §3](../data-model.md) exactly.

```
curl -s localhost:8560/health
→ 200 { "role": "replica", "chainKey": "mordor", "chainId": 63, "head": {...}, "finalized": {"number": 0, "hash": "0x…"},
        "sourceLag": 3, "ready": true, "fenced": false, "lifecycle": "running",
        "retentionWindow": { "fromBlock": 1200000, "toBlock": 1206400 }, "version": "…", "timestamp": … }
```

### `GET /ready`

Status-code form of readiness for probes that cannot parse JSON: `200` with `{"ready":true}` when `ready` is true,
`503` with the full health body when false. Semantics identical to the `ready` field.

### `POST /lifecycle/drain`

Role: all. From `Running` → `Draining`: readiness turns false immediately; the JSON-RPC HTTP/WS/IPC servers answer
new requests with HTTP `503` (`{"jsonrpc":"2.0","error":{"code":-32000,"message":"node is draining"},"id":null}`)
while in-flight requests complete; on `ingest`, `SyncProtocol.Pause` is sent (the block being imported finishes, no
new import starts). Returns `202` immediately with the health body (`lifecycle: "draining"`). Idempotent from
`Draining` (`202`). From `Fenced`: allowed (`202`, state stays `Fenced` but readiness was already false and RPC
starts answering 503). From `Stopping`: `409`.

Optional body: `{ "timeoutSeconds": 20 }` overrides `fukuii.admin.drain-timeout` for this drain (used by `stop`).

### `POST /lifecycle/fence`

Role: **`ingest` only** (`400 not_supported_by_role` otherwise). From `Running` or `Draining` → `Fenced`. Synchronous;
the response is sent after step 6 below or `504 timeout`:

1. in-memory state → `Fenced` (transactions refused with -32021 from this instant; forwarded ones too);
2. `SyncProtocol.Pause` to `SyncController` (in-flight import completes; nothing new starts);
3. every registered fenceable hook runs in registration order (025's exporter persists its export position at the
   last committed block; 025 FR-004) — a hook failure aborts the fence with `500` and the state returns to the
   previous one **without** writing the marker;
4. ETH-family chains: the Engine API listener is closed (`EngineApiHttpServer.stop()`), so the CL's next request
   fails loudly and Orbita can repoint it; ETC: no Engine API, nothing to close;
5. `fleet.fenced = <epoch ms>` written in one synced batch (vault-reviewed);
6. health reports `fenced: true`, `lifecycle: "fenced"`, `ready: false`.

Returns `200` with the health body. Idempotent from `Fenced` (`200`). After a restart the process comes up in
`Fenced` (marker present): no sync, no Engine API listener, no export, admin surface up.

### `POST /lifecycle/unfence`

Role: `ingest` only. From `Fenced` → `Running`: marker removed (synced batch); Engine API listener rebuilt and bound
(ETH family); `SyncProtocol.Resume`; fenceable hooks' `onUnfence` run (025's exporter resumes from its position).
Returns `200` with the health body. From any other state: `409 invalid_transition`.

Orbita decides when to unfence (DC-3/DC-4); fukuii only guarantees that nothing is imported or exported between
`fence` and `unfence`, across restarts.

### `POST /lifecycle/stop`

Role: all. Equivalent to SIGTERM: `drain` (bounded by `fukuii.admin.drain-timeout`, or the body's
`timeoutSeconds`), then the existing shutdown sequence (`BaseNode.shutdown`, `StdNode.scala:319-362`) and process
exit code 0. Returns `202` immediately with the health body (`lifecycle: "stopping"`); the connection may close before
a client re-polls. Idempotent.

### `SIGTERM`

Mapped to `drain` then `stop` with the configured timeouts (FR-009), through the JVM shutdown hook that
`ShutdownHookBuilder` already registers (`NodeBuilder.scala:1247-1249`). `SIGKILL` is not handled; a fenced process
killed mid-fence before step 5 restarts unfenced (research D-5 explains why the marker is written last).

## Method summary

| Method | Path | Roles | Sync | Success | Errors |
| --- | --- | --- | --- | --- | --- |
| GET | `/health` | all | — | 200 health body | — |
| GET | `/ready` | all | — | 200 `{"ready":true}` / 503 health body | — |
| POST | `/lifecycle/drain` | all | async | 202 health body | 409 |
| POST | `/lifecycle/fence` | ingest | sync | 200 health body | 400, 409, 500 (hook failed), 504 |
| POST | `/lifecycle/unfence` | ingest | sync | 200 health body | 400, 409, 504 |
| POST | `/lifecycle/stop` | all | async | 202 health body | — |
| any | anything else | — | — | — | 404 |

## Security

- Binds `localhost` by default; `ConfigValidator` warns when `fukuii.admin.interface` is not a loopback address and
  the runbook forbids exposing the port. Orbita reaches it through the container loopback (sidecar or `docker exec`)
  in the first release.
- No authentication on loopback. If the interface is ever made non-loopback, authentication (JWT as the Engine API
  uses, or mTLS) must be added first; this is a documented precondition, not a TODO.
- The admin port never serves chain RPC, `/metrics` (that stays on the metrics port) or GraphQL.
- Rate limiting is unnecessary on loopback; every handler is O(1) reads plus one `GetStatus` ask.
