---
name: fukuii-cl-setup
description: >-
  Stand up a Consensus Layer (CL) client (Prysm / Lighthouse / Teku) alongside
  Fukuii for an ETH/Sepolia node so the CL can drive Execution Layer block
  import via the Engine API. Use when bringing up a post-Merge ETH/Sepolia node,
  connecting or replacing the CL, or when the operator asks "why won't my
  Sepolia node sync" / "do I need a beacon client". Covers the EL+CL pairing
  rationale, JWT secret sharing, CL connection flags, startup order, and the
  401 liveness check. ETH/Sepolia only — ETC/Mordor has no CL. For the EL-side
  Engine API config keys see fukuii-engine-api-setup; for handshake/auth faults
  see fukuii-engine-api-debug.
disable-model-invocation: true
user-invokable: true
---

# Fukuii Consensus Layer (CL) setup — ETH/Sepolia

Read `../CONVENTIONS.md` first. **ETC/Mordor operators: stop — this does not
apply.** ETC/Mordor is Proof-of-Work and manages its own sync with no CL.

This skill is the **operator-facing entry point** for pairing a CL with Fukuii.
The EL-side config (the `engine-api` block) is owned by
`fukuii-engine-api-setup`; this skill covers the CL side and the end-to-end
bring-up. Don't duplicate the EL config here — cross-link it.

## When to use
- Bringing up a new ETH/Sepolia node for the first time.
- Connecting, replacing, or upgrading the CL client.
- Operator asks "do I need a beacon node?" or "why is `eth_syncing` stuck?".

## Why post-Merge ETH needs both EL and CL
Since the Merge, an ETH/Sepolia node is **two processes**:

- **Execution Layer (EL = Fukuii)** — holds account/state, runs the EVM,
  validates transactions and execution payloads, serves JSON-RPC.
- **Consensus Layer (CL = Prysm / Lighthouse / Teku / Nimbus / Lodestar)** —
  runs the PoS beacon chain, chooses the head, and **drives** the EL through the
  Engine API (`engine_forkchoiceUpdated*`, `engine_newPayload*`).

Without a connected, synced CL the EL has nothing telling it which block is the
head, so `eth_syncing` never advances. The EL is **passive** post-Merge: it
follows the CL. This is the single most common "my Sepolia node won't sync"
cause.

## Procedure

1. **Generate the shared JWT secret** (🟢 — one-time per deployment). The same
   32-byte hex secret authenticates the EL↔CL Engine API channel:
   ```bash
   openssl rand -hex 32 > /path/to/jwt.hex
   chmod 600 /path/to/jwt.hex
   ```
   Both Fukuii and the CL must read this **same file, byte-for-byte**.

2. **Configure the EL (Fukuii)** (🔴 — restart required). In your operator
   config:
   ```hocon
   network.engine-api {
     enabled         = true
     interface       = "localhost"   # CL on the same host; see "Where the CL runs"
     port            = 8551
     jwt-secret-path = "/path/to/jwt.hex"
   }
   ```
   Full key reference and defaults: `fukuii-engine-api-setup`.

   **Where the CL runs.** Prefer the same host, or the same Docker network:
   - **Same host** — `interface = "localhost"`; the CL dials `http://localhost:8551`.
   - **Docker Compose** — `interface = "0.0.0.0"` *inside the container*, and do
     **not** publish 8551 on the host. The CL joins the same compose network and
     dials the service name, e.g. `http://fukuii-sepolia:8551` (see
     `ops/barad-dur/sepolia/docker-compose.yml`).
   - **CL on another host** — only over a private network or VPN, behind a
     firewall that admits the CL's address alone, or through a TLS-terminating
     proxy. The JWT authenticates each call but does not encrypt it, so never
     bind 8551 to a public interface or publish it as plain `0.0.0.0`.

3. **Start Fukuii first** and wait for the Engine API to bind on 8551
   (`fukuii sepolia`). The authrpc port must be listening **before** the CL
   tries to dial it — see startup order below.

4. **Start the CL** (🟡 — confirm before executing) pointing at the authrpc
   endpoint and the same JWT file:

   | CL | Flags |
   | :-- | :-- |
   | Prysm | `--execution-endpoint=http://localhost:8551 --jwt-secret=/path/to/jwt.hex` |
   | Lighthouse | `--execution-endpoint http://localhost:8551 --execution-jwt /path/to/jwt.hex` |
   | Teku | `--ee-endpoint=http://localhost:8551 --ee-jwt-secret-file=/path/to/jwt.hex` |
   | Nimbus | `--web3-url=http://localhost:8551 --jwt-secret=/path/to/jwt.hex` |
   | Lodestar | `--execution.urls=http://localhost:8551 --jwt-secret=/path/to/jwt.hex` |

   In Docker Compose, replace `localhost` with the EL's service name.
   On Sepolia, point the CL at a checkpoint-sync URL so it reaches the head fast
   (e.g. `https://sepolia.checkpoint.ethpandaops.io`) — see `fukuii-sepolia-sync`.

5. **Verify the EL is live and authenticated** (🟢):
   ```bash
   curl -s -o /dev/null -w "%{http_code}" http://localhost:8551/
   ```
   **`401` means the Engine API is up and correctly requiring JWT auth — this is
   the expected, healthy result, not an error.** A bare `curl` carries no JWT, so
   the server rejects it with 401. `000`/connection-refused = not bound (EL not
   started, wrong port, or `enabled = false`). In Docker Compose 8551 is not
   published, so run the check from the CL's container:
   `docker compose exec lighthouse curl -s -o /dev/null -w "%{http_code}" http://fukuii-sepolia:8551/`.

6. **Confirm the handshake** (🟢) — within seconds of the CL starting, Fukuii
   logs should show `engine_forkchoiceUpdated*` (CL driving head selection).
   Then `eth_syncing` begins advancing once the CL is itself synced.

## Startup order (matters)
1. Fukuii (EL) — wait for authrpc bind on 8551.
2. CL — it dials the EL immediately on start.

If you start the CL first, it logs **"execution layer not available"** /
"could not connect to execution client" until the EL binds. This is the most
common transient bring-up error — see the decision guide.

## Decision guide
| Symptom | Likely cause | Action |
| :-- | :-- | :-- |
| CL logs "execution layer not available" | EL not bound yet, wrong port, or `enabled=false` | Confirm EL started, authrpc on 8551, `curl … 8551 → 401` |
| `curl … :8551` → 401 | Healthy — JWT auth required | None; proceed to handshake check |
| `curl … :8551` → connection refused | Engine API not bound (or, in Docker, not published — by design) | Start Fukuii / fix port / set `enabled=true`; in Docker, curl from the CL container |
| Engine API up, but `engine_*` auth fails | JWT mismatch or clock skew | `fukuii-engine-api-debug` (case 1) |
| Handshake OK but `eth_syncing` stuck | CL itself not synced | Sync the CL (checkpoint sync); `fukuii-sepolia-sync` |

## Deep reference
- `fukuii-engine-api-setup` — EL-side `engine-api` config keys and defaults.
- `fukuii-engine-api-debug` — JWT/auth, forkchoice timeout, INVALID payload faults.
- `fukuii-sepolia-sync` — Sepolia sync phases, checkpoint sync, blobs.
- `ops/barad-dur/sepolia/fukuii-conf/sepolia.conf` — production deployment example.

## Output
CONVENTIONS §4 block. Evidence = the `curl … :8551` status code (expect 401),
the Fukuii log line showing `engine_forkchoiceUpdated`, and `eth_syncing` before
and after the CL connected. No JWT material in the output.
