# Quickstart: validating spec 023 (fleet topology)

Runnable scenarios, one per user story, plus the harness. Every command assumes the repository root and a built
assembly (`sbt assembly`) or the local image `chipprbots/fukuii:local` (`docker build -t chipprbots/fukuii:local .`).
Report every run as `VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN`.

## 0. Prerequisites

```bash
sbt compile-all                                   # 0 errors
./scripts/fukuii-test only "*RoleSpec* *StartPlanSpec* *LifecycleControllerSpec* *HealthReporterSpec* *AdminHttpServerSpec* *RoleMethodPolicySpec* *TxForwarderSpec*"
./scripts/fukuii-test essential                   # once per slice end; compare with the baseline recorded by T002
```

## 1. User Story 1 — the single node is unchanged (SC-001)

**Retirement check** (after S1):

```bash
grep -rn "fukuii-runtime\|FukuiiRuntime\|ChainInstance\|instanceId\|multi-instance" src/ ops/ docs/ \
  --include=*.scala --include=*.conf --include=*.yml --include=*.md
# Expected: only the KEEP rows of research.md §2 (CHANGELOG.md:18, docs/deployment/kong-security.md:377,
# ops/barad-dur/README.md:12,161,214, specs/018-storage-engine-consolidation/research.md).
ls src/main/scala/com/chipprbots/ethereum/runtime 2>&1 | grep -q "No such file"     # directory gone
./scripts/fukuii-test only "*ProfileLoadSpec*"    # every conf/*.conf profile parses into an InstanceConfig
```

**AS1 — a 0.10.17 datadir starts with `fukuii <network>`** (after S2; the datadir moved once, per the migration note):

```bash
# One-time migration of a legacy layout (documented in docs/runbooks/operating-modes.md):
mv ~/.fukuii/sepolia ~/.fukuii/sepolia.ingest.tmp && mkdir -p ~/.fukuii/sepolia && mv ~/.fukuii/sepolia.ingest.tmp ~/.fukuii/sepolia/ingest
./target/universal/stage/bin/fukuii sepolia        # == --chain sepolia --role ingest, RPC on, P2P on
curl -s localhost:8560/health | jq '{role, chainKey, chainId, lifecycle}'
# Expected: {"role":"ingest","chainKey":"sepolia","chainId":11155111,"lifecycle":"running"}; log shows
# "ActorSystem fukuii_sepolia"; datadir ~/.fukuii/sepolia/ingest; RPC on 8545 serves eth_blockNumber.
```

**Legacy layout detector** (the fail-loud path, before moving the datadir):

```bash
./target/universal/stage/bin/fukuii sepolia; echo "exit=$?"
# Expected when ~/.fukuii/sepolia/rocksdb exists and ~/.fukuii/sepolia/ingest does not:
#   ERROR LegacyDatadirLayout: found a datadir at ~/.fukuii/sepolia (pre-0.12 layout) ... move it to
#   ~/.fukuii/sepolia/ingest or set fukuii.datadir explicitly.   exit=2
```

**AS2 — the retired runtime fails by name**:

```bash
./target/universal/stage/bin/fukuii runtime; echo "exit=$?"
# Expected: "fukuii runtime was removed: run one process per chain and role ..." exit=2
java -Dconfig.file=/tmp/multi.conf -jar target/scala-3.9.0/fukuii-assembly.jar sepolia; echo "exit=$?"
# with /tmp/multi.conf containing `fukuii-runtime.instances { etc {} }`:
# Expected: "Configuration error: fukuii-runtime.instances is no longer supported ..." exit=1
```

**CLI equivalences** (unit level): `./scripts/fukuii-test only "*AppSpec* *LauncherIntegrationSpec*"` — asserts
`fukuii sepolia` ≡ `fukuii --chain sepolia --role ingest`, `--role bogus` exits 2, `--chain=mordor` form accepted.

## 2. Starting one chain in each role locally (FR-001, FR-008)

Four processes, one chain (`mordor`), distinct ports, default datadirs `~/.fukuii/mordor/<role>`:

```bash
F=./target/universal/stage/bin/fukuii
$F mordor &                                                     # ingest: RPC 8545, P2P 30303, admin 8560
$F --chain mordor --role replica \
   -Dfukuii.network.rpc.http.port=8645 -Dfukuii.admin.port=8660 \
   -Dfukuii.tx-forward.endpoint=http://localhost:8545 &
$F --chain mordor --role archive \
   -Dfukuii.network.rpc.http.port=8745 -Dfukuii.admin.port=8760 \
   -Dfukuii.tx-forward.endpoint=http://localhost:8545 &
$F --chain mordor --role sim \
   -Dfukuii.network.rpc.http.port=8845 -Dfukuii.admin.port=8860 \
   -Dfukuii.sim.follower=http://localhost:8645 -Dfukuii.tx-forward.endpoint=http://localhost:8545 &

ss -ltnp | grep -E ':(30303|8551)\b'           # Expected: only the ingest PID holds 30303; 8551 absent on Mordor (no Engine API)
for p in 8560 8660 8760 8860; do curl -s localhost:$p/health | jq -c '{role, ready, fenced}'; done
# Expected: four lines, one per role; follower `ready` does not require peers (SC-005).

curl -s -X POST localhost:8645 -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"admin_addPeer","params":["enode://00@127.0.0.1:1"]}'
# Expected: {"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"method admin_addPeer is not served by role replica", ...}}
```

(Followers in 023 alone hold whatever store they were seeded with and do not sync; 026 adds the apply loop.)

## 3. User Story 5 — Orbita reads any process the same way (SC-005)

```bash
cd ops/fleet-harness && python3 -I harness.py up --wait-ready 600
python3 -I harness.py sc005
# Expected: every container's GET /health validates against schema/health.schema.json; `ingest` bodies carry the
# four exporter fields (null until 025); followers carry retentionWindow (null until 026); followers report
# ready=true with net_peerCount == 0x0; results/sc005-<date>-<sha>.json verdict PASS.
curl -s localhost:18560/health | jq .          # eth-ingest (host port mapping in docker-compose.yml)
curl -s -o /dev/null -w '%{http_code}\n' localhost:18660/ready    # eth-replica → 200
```

Metric twins:

```bash
curl -s localhost:13798/metrics | grep '^app_fleet_'   # on any role with metrics.enabled = true
```

## 4. User Story 4 — the ingest is replaced (SC-004)

```bash
cd ops/fleet-harness && python3 -I harness.py sc004
```

What `sc004` does and what to expect:

1. `eth-ingest` (primary) is running; `eth-ingest-standby` exists with a datadir seeded by copying the primary's
   volume while both are stopped (harness step), same `--chain eth-devnet --role ingest`, different ports.
2. `POST localhost:18560/lifecycle/fence` → `200`, body `fenced: true`, `lifecycle: "fenced"`, `ready: false`.
   The Engine API port of the primary refuses connections afterwards (ETH family). The primary's `head.number` does
   not change for the rest of the run (writes nothing after the fence).
3. The standby is started and takes block production (`testing_*`); its head advances.
4. The primary is restarted (`docker restart`) **without** an unfence: `GET /health` → `fenced: true` on the first
   answer; no sync, no Engine API listener; `eth_sendRawTransaction` to it → `-32021`.
5. `POST /lifecycle/unfence` on the primary → `200`, `fenced: false`; the Engine API port is bound again.
6. Record-stream gap check: runs 025 SC-003's checker when 025 has landed; until then the result carries
   `"notes": "record-stream checker pending spec 025"`.

Manual equivalent on a single node (US4 AS2 only):

```bash
curl -s -X POST localhost:8560/lifecycle/fence | jq '{fenced, lifecycle}'   # {"fenced":true,"lifecycle":"fenced"}
kill -TERM <pid>; $F sepolia &                                              # restart without unfence
curl -s localhost:8560/health | jq .fenced                                  # true
curl -s -X POST localhost:8560/lifecycle/unfence | jq .fenced               # false
```

Drain and SIGTERM:

```bash
curl -s -X POST localhost:8560/lifecycle/drain -w '%{http_code}\n'   # 202; then RPC answers 503, /health ready=false
kill -TERM <pid>   # log: "drain: waiting up to 30 s for in-flight work" then the usual shutdown lines; exit 0
```

## 5. GT-3 noise baseline (before judging SC-002 / SC-003)

```bash
cd ops/fleet-harness
python3 -I harness.py baseline --runs 3 --blocks 300
# Writes results/gt3-baseline-<date>-<sha>.json with, per chain: importP99Ms × 3, readP99Ms × 3, bandMs.
# Copy the band table into specs/023-fleet-topology/research.md §"Phase 0 (plan) — decisions" D-8 (task T026).
```

## 6. User Story 3 — one chain fails, the other does not notice (SC-003)

```bash
python3 -I harness.py sc003 --kill etc            # kills every etc-* container under load; measures eth-ingest import p99
python3 -I harness.py sc003 --oom etc             # OOM variant: read-burst at etc-replica until its mem_limit kills it
# Expected: eth importP99Ms and eth-replica readP99Ms within baseline ± band; verdict PASS in results/sc003-*.json.
```

## 7. User Story 2 — a simulation burst does not move import latency (SC-002; phase D, after 018 R1)

```bash
python3 -I harness.py sc002 --sim-clients 64 --duration 600
# Expected: eth-ingest importP99Ms within baseline ± band; rejections carry the 018 R1 executor error (full queue /
# timeout) — never an unbounded queue; eth_call answers from eth-sim equal eth-replica's at the same pinned block
# (byte-identical results asserted on a sample of 100 calls).
```

## 8. SC-006 — forwarded transaction load (decision input for OQ-5)

```bash
python3 -I harness.py sc006 --followers etc-replica,etc-archive --sim etc-sim --tps 200 --duration 600
# Records etc-ingest admission p99 (`app_txpool_admission_duration_seconds`) and import p99; counts -32022 from the
# forwarders. No pass/fail: the figures are summarised in research.md and decide whether spec 027 (`relay`) opens.
```

## 9. Tear-down

```bash
python3 -I harness.py down --volumes
```

## Role table (FR-002)

The authoritative per-role table is [contracts/role-matrix.md](./contracts/role-matrix.md); the operator-facing copy
lives in `docs/runbooks/operating-modes.md` §"Roles and the fleet profile" (task T044) and must be kept identical.
