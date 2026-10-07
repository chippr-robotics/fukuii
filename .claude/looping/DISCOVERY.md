# Looping Subsystem — Discovery

Populated automatically during Phase 0. Records real paths, commands, and open
assumptions so every loop recipe starts from verified facts rather than guesses.

---

## Build Commands

| Command | What it does | Approx time |
|---------|--------------|-------------|
| `sbt compile-all` | All 6 modules + test sources, all configs | ~30s |
| `sbt testEssential` | Unit tests; excludes SlowTest, IntegrationTest, SyncTest, DisabledTest, FlakyTest | ~24 min (3,595-3,601 tests) |
| `sbt testStandard` | Unit + integration; excludes BenchmarkTest, EthereumTest, SyncTest, DisabledTest, FlakyTest | ~30 min |
| `sbt testComprehensive` | Full ethereum/tests compliance vectors | ~3 hr |
| `sbt scalafmtAll` | Format all modules in place | ~20s |
| `sbt scalafmtCheck` | Verify format without writing | ~20s |
| `sbt scalafixAll` | Run all scalafix rules | ~30s |
| `sbt formatAll` | scalafixAll + scalafmtAll (pre-PR only; aborts on pre-existing violations) | ~50s |
| `./scripts/fukuii-test essential` | Tier 1 via wrapper with persistent logging | ~24 min |
| `./scripts/fukuii-test standard` | Tier 2 via wrapper | ~30 min |
| `./scripts/fukuii-test only "*<Spec>*"` | Targeted single-spec run | ~10-60s |
| `./scripts/fukuii-test quick` | crypto submodule only | ~20s |

**SyncTest exclusion:** RegularSyncSpec, FastSyncSpec, SyncControllerSpec,
BlockchainHostActorSpec, SyncStateDownloaderStateSpec — all tagged `SyncTest` and
excluded from all tiers because they time out during `fishForSpecificMessage` under
load. Gate scripts must never include them.

## Module Structure (sbt, 6 lazy val projects)

- `bytes` — byte manipulation utilities
- `crypto` — cryptographic operations; depends on bytes
- `rlp` — RLP encoding/decoding; depends on bytes
- `scalanet` — vendored IOHK scalanet (network primitives)
- `scalanetDiscovery` — DNS/peer discovery; depends on scalanet
- `node` (root `.`) — main EVM client; depends on all above; extra configs: Benchmark, Evm, Rpc

## Warning Ratchet State

| Warning category | Status | Notes |
|-----------------|--------|-------|
| E198 (unused symbols) | Ratcheted to build error | `@nowarn("id=E198:...")` at 134 sites |
| cat=unchecked | Ratcheted to build error | — |
| cat=deprecation | Gated on json4s upgrade | 68 sites; do NOT ratchet until json4s is upgraded |
| cat=feature | Intentionally suppressed | Pekko `adhocExtensions`; permanent suppression |

**Baseline:** `sbt compile-all` → 0 errors, 134 warnings (all Pekko Classic E165 from un-migrated actors)

Dev override: `fukuiiDev=true sbt compile-all` removes `-Xfatal-warnings` for local iteration.

## Lint and Format Tooling

- **scalafmt 3.8.3** — `.scalafmt.conf`; dialect: scala3 with `convertToNewSyntax = true`
- **scalafix** — `.scalafix.conf`; rules: DisableSyntax, ExplicitResultTypes,
  NoAutoTupling, NoValInForComprehension, OrganizeImports, ProcedureSyntax, RemoveUnused
- **Scala 3.3.8 LTS** — `-Xfatal-warnings` active in production builds

## Reference Repository Paths

### Spec repos (`.claude/repo-references/`)
- `ECIPs/` — ECIP specifications; remote: upstream, branch: master
- `EIPs/` — EIP specifications; remote: origin, branch: master
- `hive/` — ethereum/hive test harness; remote: upstream, branch: upstream
- `pekko/`, `pekko-http/`, `pekko-connectors/`, `pekko-management/` — Pekko actor framework
- `scala3/` — Scala 3 reference implementation
- `rocksdb/` — Facebook RocksDB (for storage layer research)
- `circe/`, `json4s/`, `sangria/`, `scalafix/`, `scalamock/` — library references

### Reference EVM clients (`/media/dev/2tb/dev/reference-clients-evm/`)

Branch discipline: every client has an `upstream` branch (read-only fast-forward mirror of
canonical upstream remote) and optionally a `main` branch (our ETC overlay). The loop
subsystem only ever updates `upstream` branches. Conformance checks always diff against
`upstream`, never `main`.

| Client | Path | ETC overlay on main? | Upstream branch |
|--------|------|---------------------|-----------------|
| besu | `besu/` | yes | `upstream` |
| core-geth | `core-geth/` | yes | `upstream` |
| nethermind | `nethermind/` | yes | `upstream` |
| go-ethereum | `go-ethereum/` | no (ETC overlay planned post-fukuii stabilization) | `upstream` |
| reth | `reth/` | no | `upstream` |
| erigon | `erigon/` | no | `upstream` |
| hive | `hive/` | no | `upstream` |

## Existing Automation Scripts

| Script | Location | Purpose |
|--------|----------|---------|
| `fukuii-test` | `./scripts/fukuii-test essential` | Test tier wrapper with persistent logging |
| `fukuii-run-tick` | `./scripts/fukuii-run-tick` | Monitoring state collection (used in cron) |
| `fukuii-monitor` | `./scripts/fukuii-monitor` | High-level node status check |
| `fukuii-inject-loop` | `./scripts/fukuii-inject-loop` | Trie-node injection loop (model for automation pattern) |

## Relevant Existing Protocols

All in `.claude/agent-protocols/`:
- `warning-ratchet.md` — 4-step ratchet pattern
- `testing-protocol.md` — test cadence (compile per file, testOnly after logic, testEssential once at end)
- `risk-stratified-commit.md` — bucket A/B/C commit discipline
- `pre-migration-checklist.md` — 13-point actor pre-flight audit
- `pekko-typed-api.md` — P1-P13 Typed API preferences
- `migration-handoff.md` — continuation file format for interrupted sessions
- `consensus-change-protocol.md` — hard stop + specialist routing before consensus changes
- `inline-cleanup.md` — scope discipline for opportunistic fixes

## Open Assumptions

1. `sbt scalafmtCheck` is assumed as the format-check task name; verify with
   `sbt tasks | grep fmt` before the first format gate run.
2. `conformance.sh` initial implementation produces a structural diff, not a semantic
   verdict — human review is still needed to triage drift reports.
3. `budget-check.sh` token counting is approximate (based on ledger line counts as a
   proxy); true token counts require API instrumentation.
