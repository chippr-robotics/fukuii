# Cirith Ungol Validation Guide

This guide documents how to validate the Cirith Ungol ETC mainnet testbed in both FastSync and SNAP modes, collect supporting evidence, and triage issues.

## Goals

1. **Prove connectivity** – Fukuii establishes healthy peers on Ethereum Classic mainnet.
2. **Verify sync progress** – Block height increases over time via FastSync and SNAP.
3. **Capture artifacts** – Logs and JSON-RPC snapshots are archived for later analysis.
4. **Highlight issues** – Timeouts, capability mismatches, or stalled syncs are surfaced quickly.

## Prerequisites

- Docker Engine + Docker Compose plugin
- `jq` and `curl` installed locally (both required by the helper scripts)
- Repository checked out with access to `ops/tools/fukuii-cli.sh`

## Test Matrix

| Mode  | Purpose                              | Success Indicators |
|-------|--------------------------------------|--------------------|
| Fast  | Primary ETC sync path                | `FastSync` log entries, pivot selection, block height advancing |
| SNAP  | Regression coverage of SNAP runtime  | `SNAP` log entries, request/response flow, graceful timeout handling |

## 1. Start the Testbed

```bash
cd ops
./tools/fukuii-cli.sh cirith-ungol start fast   # or snap
```

The CLI renders `conf/modes/etc-<mode>.conf` into `conf/generated/runtime.conf` before starting `docker-compose`. You can still use `ops/cirith-ungol/start.sh`, but it now delegates to the CLI for consistency.

## 2. Run the Smoketest Harness

```bash
cd ops
./tools/fukuii-cli.sh cirith-ungol smoketest fast
```

The smoketest will:

1. Start (or reuse) the containers in the requested mode.
2. Wait for the healthcheck to report `healthy`.
3. Query `eth_syncing`, `net_peerCount`, and `eth_blockNumber` twice.
4. Fail if peer count is `0` or block height does not advance within ~60s.
5. Save artifacts under `ops/cirith-ungol/smoketest-artifacts/<timestamp>-<mode>/`.

Success criteria:

- `smoketest` exits `0`.
- `summary.txt` shows `Peers (baseline) >= 1` and `Block after > Block before`.
- `sync-lines.log` contains FastSync or SNAP-specific log entries depending on mode.

## 3. Monitor Manually (optional but recommended)

```bash
cd ops/cirith-ungol
docker compose logs -f fukuii | grep -Ei "FastSync|SNAP|pivot"
```

Key patterns to confirm:

- `FastSync: Selected pivot block #...` (Fast mode)
- `SNAP Sync Progress` / `GetAccountRange` requests (SNAP mode)
- `PEER_HANDSHAKE_SUCCESS` with ETC peers advertising `eth/63+` capability

## 4. Collect Logs + RPC Snapshots

```bash
cd ops
./tools/fukuii-cli.sh cirith-ungol collect-logs ./captured-logs/fast-run
```

Artifacts include:

- Full container logs (`fukuii-cirith-ungol.log`)
- `docker compose ps` output
- Container `inspect` metadata
- RPC dumps: `eth_syncing`, `net_peerCount`, `eth_blockNumber`
- `sync-highlights.log` with FastSync/SNAP lines

## 5. Validation Checklist

| Item | Fast | SNAP |
|------|------|------|
| `net_peerCount` > 0 | ✅ | ✅ |
| Block height increases within 1 minute | ✅ | ✅ |
| `FastSync` log lines visible | ✅ | n/a |
| `SNAP` request/response log lines visible | n/a | ✅ |
| `smoketest` artifacts saved | ✅ | ✅ |
| `collect-logs` bundle created | ✅ | ✅ |

## 6. Troubleshooting

- **Peer count stays 0**: ensure outbound internet access from host, verify firewall rules, and rerun `collect-logs` for evidence.
- **Block height stagnant**: run `docker compose logs fukuii | grep -i timeout` to look for stalled peers; consider switching modes (Fast ↔ SNAP) to isolate issues.
- **SNAP peers unavailable**: expect repeated timeout logs on ETC mainnet. Document with `collect-logs` and fall back to FastSync for production usage.
- **Healthcheck failing**: inspect `docker compose logs fukuii` for startup exceptions and confirm `conf/generated/runtime.conf` exists (rendered by the CLI).

## 7. Cleanup

```bash
cd ops
./tools/fukuii-cli.sh cirith-ungol stop
# or remove volumes entirely
./tools/fukuii-cli.sh cirith-ungol clean
```

## Appendix

- Smoketest artifacts: `ops/cirith-ungol/smoketest-artifacts/`
- Log bundles: `ops/cirith-ungol/captured-logs/`
- Mode profiles: `ops/cirith-ungol/conf/modes/etc-fast.conf`, `etc-snap.conf`
