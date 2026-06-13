---
name: fukuii-log-triage
description: >-
  Configure Fukuii logging and triage problems through logs — set the log level
  at runtime without a restart, switch JSON output, find the log files, and map
  common error patterns (peer drops, RocksDB, OOM, sync stalls) to causes. Use
  when investigating any node issue via logs, when asked to raise/lower
  verbosity, or to enable structured logging. Reading logs is read-only; changing
  the live log level is a reversible write under the guarded-write protocol.
---

# Fukuii log triage

Read `../CONVENTIONS.md` first. Reading logs is 🟢; `admin_changeLogLevel` is 🟡
(state the level and confirm — DEBUG/TRACE are verbose and can fill disk).

## When to use
- Diagnosing almost any node problem (logs are the primary signal).
- Temporarily raising verbosity to capture a transient issue, then lowering it.
- Turning on JSON logs for aggregation (Loki/ELK).

## Log locations & config
- Files: `~/.fukuii/<network>/logs/fukuii.log` (rotated; `*.log.zip`, 10 MB ×
  up to 50). Configured by `src/main/resources/logback.xml`.
- Levels: `TRACE < DEBUG < INFO (default) < WARN < ERROR`.
- Static config (`logging { logs-level, json-output, logs-dir, logs-file }`) or
  env `FUKUII_LOG_LEVEL` / `FUKUII_LOGGING_JSON_OUTPUT=true` — both need a restart.

## Procedure
1. **Set level at runtime** (🟡, no restart) — `admin_changeLogLevel("<LEVEL>")`.
   Use this to bump to DEBUG just long enough to capture the symptom, then return
   to INFO. Confirm before going to DEBUG/TRACE (volume + disk).
2. **Read & scan** (🟢) — tail the live file; grep for `ERROR`/`WARN`, stack
   traces, and the patterns below around the incident window.
3. **Classify** (🟢) — map to the table, then hand off.

## Pattern → cause → hand-off
| Log pattern | Likely cause | Hand off to |
| :-- | :-- | :-- |
| Repeated peer disconnect / handshake fail | P2P/connectivity | `fukuii-peer-management` |
| `AccountRange`/pivot stall, snap errors | ETC sync stall | `fukuii-sync-troubleshooting` |
| Engine API timeout / missing `engine_forkchoiceUpdated` | ETH/Sepolia: CL not connected or stalled | `fukuii-sync-troubleshooting` — verify CL is running |
| RocksDB corruption / IO error | disk/DB | `fukuii-disk-management`, then restore |
| `OutOfMemoryError`, GC thrash | heap/resources | tune JVM heap (`.jvmopts`); restart |
| `No space left on device` | disk full | `fukuii-disk-management` |
| TLS/cert errors on RPC | RPC TLS | `fukuii-tls-operations` |

## Deep reference
- `docs/runbooks/log-triage.md` (pattern catalogue, analysis tooling)
- `docs/operations/LOGGING.md`, `docs/operations/metrics-and-monitoring.md`

## Output
CONVENTIONS §4 block. Quote the **specific** log lines as evidence; record any
level change made and that it was reverted.
