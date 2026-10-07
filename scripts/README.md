# `scripts/`

Operational and maintenance helpers. Nothing here is on the merge path — the
checks that gate a merge are declared in
[`.github/gates.yml`](../.github/gates.yml) and summarised in
[`docs/STATUS.md`](../docs/STATUS.md).

## CI

| Script | Purpose |
|---|---|
| `ci/check_gate_integrity.py` | The **Gate Integrity** meta-check (required status check). Validates the gate matrix: undeclared checks, required-but-unfailable gates, expired or incomplete waivers, unmapped constitution principles, dead README badges, over-claiming docs, and a stale status document. Runs offline in seconds. |
| `ci/generate_status.py` | Generates `docs/STATUS.md` from `.github/gates.yml`. Run it after editing the matrix and commit the result; Gate Integrity fails if it is stale. `--check` exits non-zero when out of date. |
| `ci/test_gate_integrity.py` | Self-test for the meta-check — mutates a sandboxed copy of the repo and asserts the check goes red for each condition it advertises. |

See `specs/008-ci-gate-integrity/` for the design and issues
[#1402](https://github.com/chippr-robotics/fukuii/issues/1402),
[#1403](https://github.com/chippr-robotics/fukuii/issues/1403),
[#1404](https://github.com/chippr-robotics/fukuii/issues/1404).

## Bootnodes and peers

| Script | Purpose |
|---|---|
| `refresh-bootnodes-dns.sh` | Refresh ETC bootnodes via DNS resolution. **Run manually.** Resilient alternative to the retired `api.etcnodes.org` path. |
| `inject_peers_from_coregeth.py` | Inject peers harvested from a core-geth node. Invoked by `ops/tools/fukuii-cli.sh`. |
| `decode-enr.py` | Decode an ENR record for inspection. |
| `mordor_good_enodes.txt` | Known-good Mordor enodes, used for manual peer seeding. |

> **Retired:** `update-bootnodes.sh` and the weekly `nightly-bootnode-update`
> job that drove it were removed. The job opened a pull request every week for
> more than three months and **not one was ever merged** (#1353 through #1405,
> 15 consecutive PRs). Each one also triggered the full Hive matrix, so a job
> nobody acted on was consuming most of the project's CI budget and filling the
> workflow history with runs that had nothing to do with any code change. Use
> `refresh-bootnodes-dns.sh` manually when bootnodes need refreshing.

## Build and test

| Script | Purpose |
|---|---|
| `fukuii-test` | Wrapper for targeted or full `sbt` test runs. See `CLAUDE.md` for the test cadence. |
| `inject_version.py` | Inject the version from `version.sbt` into build artifacts. |
| `validate_openapi.py` | Validate the JSON-RPC OpenAPI specification. |
