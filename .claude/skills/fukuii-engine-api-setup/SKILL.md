---
name: fukuii-engine-api-setup
description: >-
  Configure Fukuii's Engine API authrpc endpoint so a Consensus Layer (CL)
  client can drive ETH/Sepolia block import — generate the shared JWT secret,
  enable the authrpc server, and verify the CL handshake. ETH/Sepolia only;
  ETC/Mordor does not use the Engine API. Use when setting up a new
  ETH/Sepolia node, connecting or replacing the CL, or troubleshooting
  eth_syncing not advancing when peers are healthy. Config changes require a
  restart — irreversible/disruptive under the guarded-write protocol.
disable-model-invocation: true
user-invokable: true
---

# Fukuii Engine API setup (ETH/Sepolia)

Read `../CONVENTIONS.md` first. **ETC/Mordor operators: stop here — this skill
does not apply.** ETC/Mordor manages sync independently with no CL required.

This skill is for ETH/Sepolia nodes only. The Engine API is the authenticated
channel through which the Consensus Layer (CL) drives the Execution Layer (EL).
Without a connected CL, `eth_syncing` will not advance on ETH/Sepolia.

## When to use
- Setting up a new ETH/Sepolia EL node for the first time.
- Connecting or replacing the CL client.
- `eth_syncing` is stuck despite healthy peers — suspect the CL is not connected.
- Rotating the JWT secret (security maintenance).

## Config model
Config keys in `network.engine-api` (see `src/main/resources/conf/base/network.conf`):

| Key | Default | Notes |
| :-- | :-- | :-- |
| `network.engine-api.enabled` | `false` | Must be `true` for ETH/Sepolia |
| `network.engine-api.interface` | `"localhost"` | Use `"0.0.0.0"` when CL is on another host/container |
| `network.engine-api.port` | `8551` | Standard authrpc port |
| `network.engine-api.jwt-secret-path` | (none — dev mode) | MUST be set in production |

If `jwt-secret-path` is omitted, Fukuii generates a random in-memory secret
(development mode only — incompatible with any real CL client).

## Procedure

1. **Generate the JWT secret** (🟢 — one-time per deployment) — the same 32-byte
   hex secret must be given to both Fukuii and the CL:
   ```bash
   openssl rand -hex 32 > /path/to/jwt.hex
   chmod 600 /path/to/jwt.hex
   ```
   Never regenerate without updating both sides simultaneously.

2. **Edit `fukuii.conf`** (🔴 — restart required):
   ```hocon
   network.engine-api {
     enabled = true
     interface = "localhost"      # Use "0.0.0.0" for Docker/cross-host
     port = 8551
     jwt-secret-path = "/path/to/jwt.hex"
   }
   ```
   Do not hand-edit `base/network.conf` — override in your operator config only.

3. **Restart Fukuii** (🔴) — the Engine API server starts at port 8551.
   Check logs for the Engine API listening message.

4. **Configure the CL** (🟡 — confirm before executing) — pass the authrpc
   endpoint and JWT secret to the CL client. Flag names vary by implementation:

   | CL | Flags |
   | :-- | :-- |
   | Lighthouse | `--execution-endpoint=http://localhost:8551 --jwt-secrets=/path/to/jwt.hex` |
   | Prysm | `--execution-endpoint=http://localhost:8551 --jwt-secret=/path/to/jwt.hex` |
   | Teku | `--ee-endpoint=http://localhost:8551 --ee-jwt-secret-file=/path/to/jwt.hex` |
   | Nimbus | `--web3-url=http://localhost:8551 --jwt-secret=/path/to/jwt.hex` |
   | Lodestar | `--execution.urls=http://localhost:8551 --jwt-secret=/path/to/jwt.hex` |

   Refer to each CL's own documentation for the current flags.

5. **Verify the handshake** (🟢) — watch Fukuii logs for either of these:
   - `engine_exchangeTransitionConfigurationV1` — initial capability exchange
   - `engine_forkchoiceUpdated` — CL driving block selection
   
   If both Fukuii and the CL are running and the JWT matches, these log lines
   should appear within seconds. Then `eth_syncing` should begin advancing.

## Decision guide
| Symptom | Likely cause | Action |
| :-- | :-- | :-- |
| `eth_syncing` stuck, peers OK, no Engine API logs | CL not connected | Check CL is running; verify endpoint URL and port |
| `engine_*` auth failures in logs | JWT mismatch | Confirm both sides use the same `jwt.hex` byte-for-byte |
| Engine API logs present but `eth_syncing` not advancing | CL itself is stalled/unsynced | Check CL health; CL must be synced to drive the EL |
| `eth_syncing` advances after `engine_forkchoiceUpdated` | Setup complete | Monitor `eth_blockNumber` for ongoing progress |

## Gotchas
- **JWT must match byte-for-byte** — regenerating on one side without updating the other breaks authentication immediately.
- **`interface = "localhost"` for same-host; `"0.0.0.0"` for Docker/cross-host** — wrong interface means the CL can't reach the EL.
- **Omitting `jwt-secret-path`** is development mode only — a random in-memory secret is generated and no real CL can connect.
- **CL must itself be synced** to drive the EL — if the CL is not at the chain tip, the EL will follow it (or not advance).
- This skill covers the EL (Fukuii) side only. For CL setup and operation, refer to the CL client's own documentation.

## Deep reference
- `src/main/resources/conf/base/network.conf` (Engine API key defaults and comments)
- `ops/barad-dur/sepolia/fukuii-conf/sepolia.conf` (production deployment example)
- `fukuii-node-configuration` — for the general config editing + restart discipline
- `fukuii-first-start` — overall ETH/Sepolia node startup sequence
- `fukuii-sync-troubleshooting` — if CL is connected but sync is still stuck

## Output
CONVENTIONS §4 block. Evidence = the Fukuii log lines showing Engine API
handshake (`engine_exchangeTransitionConfigurationV1` or
`engine_forkchoiceUpdated`), and `eth_syncing` result before and after.
