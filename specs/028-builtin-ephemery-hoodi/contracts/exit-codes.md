# Contract: exit codes and operator-facing messages (spec 028)

## Process exit codes (`fukuii ephemery`, `fukuii cli ephemery-fetch`)

The codes follow sysexits(3). fukuii already uses 0, 1, 2 and 78 (`JvmFlagGuard.RefuseExitCode`). 75 and 69 are new.

| Code | Name | When | A restart… |
|------|------|------|------------|
| 0 | – | normal stop, or a successful `ephemery-fetch` | – |
| **75** | `EX_TEMPFAIL` | **a running node reached its iteration's reset (FR-016)** | refreshes onto the next iteration |
| **69** | `EX_UNAVAILABLE` | at startup, the iteration has ended and the next cannot be had now: `NotYetPublished`, `Unreachable` or `WindowMismatch` | succeeds once the release is out or the network is back |
| **78** | `EX_CONFIG` | permanent: `CannotRepresent`, `Inconsistent`, `HashMismatch`, `Republished`, `Ended` (refresh off or local source exhausted), or `BadSource` | does not help; the operator must act |
| 1 | – | the existing configuration and startup errors, unchanged | – |
| 2 | – | a usage error in `ephemery-fetch` (as `CheckpointCli`) | – |

**Recommended supervisor** (from the runbook, sized in research.md R12):

```ini
# systemd
Restart=on-failure
RestartSec=120
RestartPreventExitStatus=78
```

Docker's `--restart on-failure` (no retry cap) restarts on 78 too, and has no equivalent of
`RestartPreventExitStatus`. The runbook says so.
- **Do not cap the retries.** A late release took 3 h 42 m, about 75 restarts (research.md R12), so a cap such as
  `on-failure:5` would leave the node down.
- **Under Docker, watch for 78** in the logs or the exit status instead. Kubernetes' `CrashLoopBackOff` backoff is
  fine as it is.

## Log lines (stable wording; tests match on the leading text)

**Startup report** (INFO, one line, US1-3, managed iteration):

```text
Ephemery ephemery-165 (chain id 39438165, genesis 0x9485…5ecc) from bundled|cache|fetched; resets 2026-11-19T19:00:00Z (local clock 2026-11-12T…Z); datadir <root>/ephemery-165; CL dir <path> | CL dir not present: run `fukuii cli ephemery-fetch --release ephemery-165`
```

**Operator directory** (INFO; a WARN is added when it has ended):

```text
Ephemery: using the prepared chain directory <dir> (custom-chains-dir); fukuii will not fetch or exit at its reset
WARN Ephemery: the prepared iteration in <dir> ended at <UTC>; re-run scripts/ephemery/fetch-genesis.sh, or unset custom-chains-dir to use the built-in refresh
```

**Old datadirs** (INFO, at most one line):

```text
Ephemery: earlier iteration datadirs kept: ephemery-163 (1.2 GiB), ephemery-164 (3.4 GiB); fukuii never deletes them
```

**At the reset** (WARN, FR-016):

```text
Ephemery iteration ephemery-165 ended at 2026-11-19T19:00:00Z; exiting with code 75 so a supervisor restart picks up ephemery-166
```

**With `exit-at-reset = false`:**

```text
Ephemery iteration ephemery-165 ended at 2026-11-19T19:00:00Z; restart fukuii to move to ephemery-166 (exit-at-reset = false)
```

**Refusals** (ERROR, then the exit code above). Each message names the cause and the next step (SC-004). For example:

```text
Ephemery: ephemery-165 ended at 2026-11-19T19:00:00Z and ephemery-166 is not published yet (checked github.com 3 times in 60 s); exiting 69, a restart retries
Ephemery: ephemery-165 ended at … and fetching is disabled (fukuii.ephemery.refresh = false); supply ephemery-166 with `fukuii cli ephemery-fetch --from-dir DIR` or enable refresh; exiting 78
Ephemery: ephemery-166 declares bpo3Time, which this fukuii cannot represent; a newer fukuii is needed (exiting 78)
Ephemery: ephemery-166 genesis computes to 0x…, but the release publishes 0x…; refusing (exiting 78)
```

## Not-configured error (FR-023, B-225)

The error is unreachable for `ephemery` and `hoodi`. For any other unknown network:

```text
blockchain network '<name>' is not configured (known: …). A custom network is loaded from <custom-chains-dir>/<name>-chain.conf; that directory is <resolved path> (it does not exist | it has no <name>-chain.conf).
```

`EphemeryGenesisSpec:101-106` and `Config.scala:349-358` change with it.
