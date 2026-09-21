<!-- GENERATED FILE — DO NOT EDIT.
     Source: .github/gates.yml
     Regenerate: python3 scripts/ci/generate_status.py
     The Gate Integrity check fails if this file is stale. -->

# Fukuii Verification Status

This page is generated from [`.github/gates.yml`](https://github.com/chippr-robotics/fukuii/blob/main/.github/gates.yml),
the single source of truth for what Fukuii's CI actually enforces.

**How to read it.** A suite marked *required* must be green for a change to merge —
that is a claim backed by a check. A suite marked *informational* runs and reports, but
cannot block a merge; its results are data, **not a compliance claim**. A suite marked
*quarantined* is known broken and is excluded from every claim on this site.

We publish this because the alternative — a wall of green badges with no gates behind
them — is how v0.8.0 shipped with Hive sync-server red and `consume-rlp` failing ~67% of
its cases. See issues
[#1402](https://github.com/chippr-robotics/fukuii/issues/1402),
[#1403](https://github.com/chippr-robotics/fukuii/issues/1403),
[#1404](https://github.com/chippr-robotics/fukuii/issues/1404).

## Canonical facts

| | |
|---|---|
| Binary | `fukuii` |
| Container image | `ghcr.io/chippr-robotics/fukuii` |
| Mirror | `docker.io/chipprbots/fukuii` |
| Version source | `version.sbt` (semver) |

**Lineage.** Fukuii is a derivative work of IOHK's Mantis client, maintained by Chippr Robotics LLC under Apache-2.0. See NOTICE. Mantis is a trademark of IOHK, used here only to describe this fork's origin.

**`fukuii-project/fukuii-cli`.** An independent, ground-up rewrite with no common ancestor and no shared source with this repository. It is NOT this release train and NOT a fork of this tree. See issue #1399 for the divergence assessment and the clean-room constraint.

## Required checks

These must be green to merge. Every claim this project makes rests on this list and
nothing else.

| Check | Covers | Owner |
|---|---|---|
| `ci-test-build` | scalafmtCheckAll, compile-all, Tier-1 testEssential, Tier-2 testStandard with coverage (develop + PRs into main), KPI baselines, ethereum/tests integration job, assembly build. | realcodywburns |
| `docker-build` | Container images build and push for mainnet/mordor/bootnode variants. | realcodywburns |
| `gate-integrity` | This matrix itself — undeclared checks, required-but-unfailable gates, expired/incomplete waivers, unmapped constitution principles, stale generated status doc, dead badges, and over-claiming documentation. FR-029: the meta-check is itself required, or it guarantees nothing. | realcodywburns |

## Informational — runs, reports, does not block

**These are not compliance claims.** Each carries a tracking issue and a date by which it
must become required or be formally re-scoped in a reviewed PR.

| Check | Covers | Promote by | Issue | Note |
|---|---|---|---|---|
| `hive-sync` | Cross-client sync interop. fukuii as sync CLIENT (fukuii syncs from go-ethereum/nethermind) and as sync SERVER (go-ethereum/nethermind sync from fukuii). Gated on the `fukuii` subset only, so upstream geth<->nethermind failures do not pollute this signal. | 2026-12-31 | #1402 | The only suite with a real subset gate today. #1402's first required-slice item. Both waivers below must be retired before promotion — the sync-SERVER direction is precisely what #1402 says shipped red in v0.8.0. |
| `hive-smoke-genesis` | Client starts from a hive-supplied genesis and reports a chain head. | 2026-11-30 | #1402 | Cheapest suite and the natural first promotion: if this cannot be made required, nothing can. Last green on main 2026-04-30; red since. |
| `hive-smoke-network` | Client accepts peers and forms a network under hive's harness. | 2026-11-30 | #1402 |  |
| `hive-consume-rlp` | EELS consume-rlp — block import from RLP across the fork schedule, including the ETC forks. #1402's third required-slice item. | 2026-12-31 | #1402 | ~67% failing at v0.8.0. Not claimed as verified anywhere until promoted. |
| `hive-engine` | Engine API suite, including invalid-payload rejection — #1402's second required-slice item. ETH/Sepolia path only. | 2026-12-31 | #1402 |  |
| `hive-consensus` | ethereum/consensus — state-transition conformance. | 2027-03-31 | #1402 |  |
| `hive-consume-engine` | EELS consume-engine — payload import via Engine API. | 2027-03-31 | #1402 |  |
| `hive-rpc-compat` | JSON-RPC method compatibility against the reference corpus. | 2027-03-31 | #1402 |  |
| `hive-graphql` | GraphQL endpoint conformance. | 2027-03-31 | #1402 |  |
| `hive-devp2p` | devp2p discovery and RLPx wire conformance. | 2027-03-31 | #1402 |  |
| `ethereum-tests-nightly` | Nightly ethereum/tests across the full ETC and ETH fork schedules. | 2027-03-31 | #1402 | Runs with continue-on-error today. Promotion requires removing that and establishing a baseline pass count first. |

## Quarantined — excluded from all claims

| Check | Why | Promote by | Issue |
|---|---|---|---|
| `hive-prague` | A parallel, unchecked code path with its own inline `exit 0`. Migrate to _hive-sim.yml (inheriting its gates) or delete. Excluded from public claims until then. | 2026-12-31 | #1402 |
| `hive-osaka` | Same as hive-prague. Migrate to _hive-sim.yml or delete. | 2026-12-31 | #1402 |

## First required slice

Tracked by [#1402](https://github.com/chippr-robotics/fukuii/issues/1402), target **2026-12-31**.

| Item | Gate | Blocked by |
|---|---|---|
| geth<->fukuii sync, BOTH directions | `hive-sync` | `sync-server-geth-from-fukuii`, `sync-client-fukuii-from-nethermind` |
| invalid-payload rejection | `hive-engine` | — |
| ETC-fork consume-rlp | `hive-consume-rlp` | — |

> Nightlies MUST NOT report green while any member of this slice is red. Enforced structurally by _hive-sim.yml (a suite that produced no results, fewer than min_tests results, or any gated-subset failure is red) rather than by convention.

## Active waivers

A waiver suppresses one known-failing test inside an otherwise-gated subset. Every one
has an owner, an issue, and an expiry date; CI fails the day it expires. There is no
bypass — extending a waiver means editing the matrix in a reviewed PR.

| Waiver | Gate | Excluded test | Owner | Issue | Expires |
|---|---|---|---|---|---|
| `sync-server-geth-from-fukuii` | `hive-sync` | `sync go-ethereum from fukuii` | realcodywburns | #1402 | 2026-12-31 |
| `sync-client-fukuii-from-nethermind` | `hive-sync` | `sync fukuii from nethermind` | realcodywburns | #1402 | 2026-12-31 |

