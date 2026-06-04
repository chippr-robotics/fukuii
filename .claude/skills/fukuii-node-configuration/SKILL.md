---
name: fukuii-node-configuration
description: >-
  Safely edit a Fukuii node's HOCON configuration and choose an operating mode —
  network selection, datadir, RPC apis/ports, sync mode (SNAP vs fast), mining,
  and resource settings — with a back-up-then-restart discipline. Use when asked
  to change config, enable an RPC namespace, switch sync strategy, set the
  datadir, or pick an operating profile. Editing config and restarting is an
  irreversible/disruptive action under the guarded-write protocol.
---

# Fukuii node configuration

Read `../CONVENTIONS.md` first. Almost every config change needs a restart → 🔴
(back up config first; the node is unavailable during restart).

## When to use
- Enable/disable an RPC namespace (e.g. add `mcp` or `admin`).
- Switch sync mode, set the datadir, change ports, tune resources.
- Pick an operating profile for a role (RPC provider, miner, bootnode, …).

## Config model
- HOCON, layered: base fragments in `src/main/resources/conf/base/*.conf`
  (`network.conf`, `sync.conf`, `mining.conf`, `db.conf`, …) composed per network
  (`etc.conf`, `mordor.conf`, `sepolia.conf`, …). Operators override via their own
  `conf/fukuii.conf` and `-Dconfig.file=`.
- Templates worth reading: `conf/enterprise-template.conf`,
  `src/universal/custom-config-example.conf`.

## High-value keys (verified)
| Concern | Key(s) |
| :-- | :-- |
| RPC namespaces | `network.rpc.apis` (default `eth,web3,net,personal,fukuii,debug,qa,admin`; add `mcp` for MCP tools) |
| RPC bind/port | `network.rpc.http.{interface,port,mode}` (default `localhost:8546`, `http`) |
| Sync strategy | `sync.do-snap-sync`, `sync.do-fast-sync` |
| Rapid sync | `sync.checkpoint-sync-file` / `-url` (→ `fukuii-checkpoint-service`) |
| Mining | `mining.mining-enabled`, `mining.coinbase` (→ `fukuii-mining-operations`) |
| TLS | `network.rpc.http.mode` + nested `network.rpc.http.certificate { keystore-path, keystore-type, password-file }` (→ `fukuii-tls-operations`) |
| P2P | `network.server-address.port`, `network.discovery.port` (default `30303`) |

## Procedure
1. **Back up current config** (🟢) — copy `fukuii.conf` aside (`fukuii-backup-restore`).
2. **Make the smallest change** (🔴) — edit one concern at a time; keep overrides
   in the operator config, don't hand-edit shipped `base/*.conf`.
3. **Validate before restart** — sanity-check HOCON syntax; cross-check key paths
   against the `base/*.conf` source (typos silently fall back to defaults).
4. **Restart & verify** (🔴) — restart, then confirm the change took effect
   (e.g. `rpc_modules` for enabled apis, `eth_syncing` for sync mode) and run
   `fukuii-node-health-check`.
5. **Roll back** — if the node misbehaves, restore the backed-up config and restart.

## Deep reference
- `docs/runbooks/node-configuration.md`, `docs/runbooks/operating-modes.md`
- `src/main/resources/conf/base/` (authoritative key names/defaults)

## Output
CONVENTIONS §4 block. Evidence = the key(s) changed, old→new values, and the
post-restart verification result.
