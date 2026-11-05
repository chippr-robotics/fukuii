# Recipe: ref-parity-audit

Pull all reference client upstream branches, compare Fukuii's implementation
against current upstream behavior, and produce a drift report. Read-only. No code
changes. Drift findings are recorded in SPRINT-QUEUE or CHASE-QUEUE (`DEFER` entries) for
follow-up via spec-conformance recipe instances.

**When to reach for it:** Schedule weekly via `/loop 1w .claude/looping/bin/run-loop.sh ref-parity-audit`
or run ad-hoc after an upstream reference client release. This is a poll recipe —
it has no finish line and maps to `/loop`, not `/goal`.

---

```yaml
id: ref-parity-audit
goal: NONE
maker: NONE
checker: [forge, beacon, herald, vault, conduit]
gates: [conformance]
refresh_refs: true
constraints:
  - read-only; no source code changes in this recipe
  - audit findings must be written to CHASE-QUEUE (`DEFER` entries) or SPRINT-QUEUE, not fixed inline
  - if drift is found, open a spec-conformance recipe instance to track and fix it
  - do not upgrade any dependency as part of this audit
budget:
  max_iterations: 1
  max_wallclock: 30m
stop_on: [budget_exhausted]
```

## LOOP_TEST_TARGET

```
NONE
```

(No test gate — read-only audit)

## How to Run

```sh
.claude/looping/bin/run-loop.sh ref-parity-audit
```

This will:
1. Check eligibility
2. Run `refresh-refs.sh` to pull all upstream branches (fast-forward only)
3. Run `conformance.sh` which produces a per-client freshness and SHA report
4. Invoke each checker for their domain:
   - forge: ETC consensus surfaces vs core-geth upstream
   - beacon: ETH consensus surfaces vs go-ethereum upstream
   - herald: wire protocol (ETH68/69/70, SNAP) vs go-ethereum upstream
   - vault: storage layer vs go-ethereum/besu upstream
   - conduit: JSON-RPC methods vs go-ethereum upstream

## DISCOVER Phase

Orchestrator reads the conformance report and routes to each checker:

- **forge**: compare `src/main/scala/com/chipprbots/ethereum/consensus/` against
  `core-geth/consensus/` upstream branch. Flag: new ECIPs, opcode changes, reward
  logic, fork dispatch.
- **beacon**: compare ETH timestamp-fork dispatch and Osaka opcodes against
  `go-ethereum/` upstream branch. Flag: new EIPs, state root changes, withdrawals.
- **herald**: compare `src/main/scala/com/chipprbots/ethereum/network/` P2P handlers
  against `go-ethereum/eth/` upstream branch. Flag: new ETH protocol versions, SNAP
  changes, handshake protocol changes.
- **vault**: compare `src/main/scala/com/chipprbots/ethereum/db/` against
  `go-ethereum/core/rawdb/` and `besu/` upstream branches. Flag: column family
  changes, schema migrations.
- **conduit**: compare `src/main/scala/com/chipprbots/ethereum/jsonrpc/` against
  `go-ethereum/internal/ethapi/` upstream. Flag: new eth_* methods, parameter changes.

## Output

Each checker produces a domain drift report in the ledger. The orchestrator
consolidates into a single finding list and writes to:

```
working-docs/CHASE-QUEUE.md        # for long-horizon items (type DEFER)
working-docs/SPRINT-QUEUE.md       # for items ready for next sprint
```

Per finding, record: surface, drift description, severity (CRITICAL/MEDIUM/LOW),
and which spec-conformance recipe instance to create.

## Scheduling via /loop

```
/loop 1w .claude/looping/bin/run-loop.sh ref-parity-audit
```

The loop runs once per week, pulls upstream, and reports. There is no CONFIRM:DONE
step — the loop always finishes after one iteration (max_iterations: 1).
