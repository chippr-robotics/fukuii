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

**`fukuii-project/fukuii-cli`.** An independent, ground-up rewrite with no common ancestor and no shared source with this repository. It is NOT this release train and NOT a fork of this tree. See docs/development/fukuii-project.md for the divergence assessment and the clean-room constraint (it replaces issue #1399).

## Required checks

These must be green to merge. Every claim this project makes rests on this list and
nothing else.

| Check | Covers | Owner |
|---|---|---|
| `ci-test-build` | compile-all, formatCheck (scalafmt on every module; scalafix on root, bytes, crypto, rlp), Tier-1 testEssential, Tier-2 testStandard with coverage (develop + PRs into main), KPI baselines, ethereum/tests integration job, assembly build. | realcodywburns |
| `docker-build` | Container images build and push for mainnet/mordor/bootnode variants. | realcodywburns |
| `gate-integrity` | This matrix itself — undeclared checks, required-but-unfailable gates, expired/incomplete waivers, unmapped constitution principles, stale generated status doc, dead badges, and over-claiming documentation — plus committed keystores, private keys and `.env` files (C15). FR-029: the meta-check is itself required, or it guarantees nothing. | realcodywburns |
| `hive-sync` | Cross-client sync interop. fukuii as sync CLIENT (fukuii syncs from go-ethereum/nethermind) and as sync SERVER (go-ethereum/nethermind sync from fukuii). Gated on the `fukuii` subset only, so upstream geth<->nethermind failures do not pollute this signal. | realcodywburns |
| `hive-smoke-genesis` | Client starts from a hive-supplied genesis and reports a chain head. | realcodywburns |
| `hive-smoke-network` | Client accepts peers and forms a network under hive's harness. | realcodywburns |
| `hive-consume-rlp` | EELS consume-rlp — block import from RLP across the fork schedule, including the ETC forks. #1402's third required-slice item. | realcodywburns |
| `hive-engine` | Engine API suite, including invalid-payload rejection — #1402's second required-slice item. ETH/Sepolia path only. | realcodywburns |
| `eest-amsterdam` | execution-specs tests@v21.0.0 Amsterdam and BPO2->Amsterdam blockchain_tests (26,503) replayed from the release's own block RLP through the import path (EestFixtureCorpusSpec), and every block they accept (27,352) rebuilt by the Engine API payload builder from its parent, attributes and transactions, which must reproduce execution-specs' block hash (EngineApiAmsterdamBuilderEestSpec). Fails on any divergence in either, on fewer than EEST_MIN_TESTS tests in either, and on a missing corpus. The import replay then runs a second time with a position mirror on every fixture's storage (EEST_POSITIONS=1, spec 018 Track C T026) and fails on a read or an update a position-keyed trie store would have refused. | realcodywburns |

## Informational — runs, reports, does not block

**These are not compliance claims.** Each carries a tracking issue and a date by which it
must become required or be formally re-scoped in a reviewed PR.

| Check | Covers | Promote by | Issue | Note |
|---|---|---|---|---|
| `hive-upstream-definition` | The fukuii client definition hive itself builds (clients/fukuii/, from chipprbots/fukuii:latest), run unmodified against this checkout's build: smoke/genesis, nightly and on promotions to main. Its `upstream-definition-drift` job fails when hive/fukuii/ and hive's copy differ (B-569). | 2027-03-31 | #1402 | New 2026-11-27 (B-569). Points at the ethereum/hive#1677 branch until that pull request merges; then at ethereum/hive master. Promote to required for main promotions once it has a green run. |
| `hive-consensus` | ethereum/consensus — state-transition conformance. | 2027-03-31 | #1402 |  |
| `hive-consume-engine` | EELS consume-engine — payload import via Engine API. Runs the fixed shard consume-engine-13 of .github/hive-shards.json (874 tests, ~44 min) to completion; the whole suite runs in hive-full. | 2027-03-31 | #1402 |  |
| `hive-full` | consume-engine, consume-rlp and consensus run TO COMPLETION, sharded per .github/hive-shards.json (an exact partition, proven by scripts/hive/gen-shards.py). The release-PR hive-consume-*/hive-consensus workflows only sample these suites. | 2027-03-31 | #1402 | Release gate: runs on a `hive-full`-labelled PR to main, or workflow_dispatch — ~33 jobs x ~3.5 h per pass, so never per push or nightly. Verdict comes from the aggregate job, never the workflow conclusion. |
| `hive-rpc-compat` | JSON-RPC method compatibility against the reference corpus. | 2027-03-31 | #1402 |  |
| `hive-graphql` | GraphQL endpoint conformance. | 2027-03-31 | #1402 |  |
| `hive-devp2p` | devp2p discovery and RLPx wire conformance. | 2027-03-31 | #1402 | Two jobs since 2026-09-24. One runs discv4, discv5, snap and snap2 in full; the other runs eth without GetCells and BlobTxWithInvalidCells, which are skipped via sim_skip and waived until #1409 because they hang the suite. Local hive on 4dc5b77af: discv4 16/16, discv5 11/11, snap 6/6, snap2 4/4, eth 22/24. |
| `ethereum-tests-nightly` | Nightly ethereum/tests across the full ETC and ETH fork schedules, and (job legacy-blockchain-tests, B-429) LegacyPreByzantiumBlockchainTestsSpec over ethereum/legacytests pinned by commit: the Frontier..EIP158 slice (6,817 vectors, a state root per receipt) and Byzantium..Berlin (54,497). That job fails on any failing vector and on a missing or partial corpus. | 2027-03-31 | #1402 | The ethereum/tests job runs with continue-on-error today. Promotion requires removing that and establishing a baseline pass count first. The legacy job's baseline is all 61,314 vectors passing at legacytests 1f581b8 (spec 018 baselines/ethtests-2026-11-22.txt). |
| `windows-bls` | The EIP-2537 BLS12-381 precompiles on Windows (B-151, ADR VM-008), where eth_pairings has no build and blst (tech.pegasys:jblst) runs them. On windows-latest with FUKUII_BLS12381_BACKEND=blst: BlstEip2537Spec (blst loads; its outcomes on the 3,636-input EIP-2537 corpus hash to the committed digests of eth_pairings' and gnark's outcomes; the precompiles run on blst), BLS12381PrecompileSpec, BlsMsmGasSpec and NativeBackendsSpec. Runs on workflow_dispatch and on PRs that touch the BLS files. | 2027-01-31 | #1767 | New with B-151. The case-by-case differential of blst against both oracles runs on Linux in Eip2537DifferentialSpec (ci-test-build). Promote after a green run on windows-latest and a green history on BLS-touching PRs. |

## First required slice

Tracked by [#1402](https://github.com/chippr-robotics/fukuii/issues/1402), target **2026-12-31**.

| Item | Gate | Blocked by |
|---|---|---|
| geth<->fukuii sync, BOTH directions | `hive-sync` | — |
| invalid-payload rejection | `hive-engine` | — |
| ETC-fork consume-rlp | `hive-consume-rlp` | — |

> Nightlies MUST NOT report green while any member of this slice is red. Enforced structurally by _hive-sim.yml (a suite that produced no results, fewer than min_tests results, or any gated-subset failure is red) rather than by convention.

## Active waivers

A waiver suppresses one known-failing test inside an otherwise-gated subset. Every one
has an owner, an issue, and an expiry date; CI fails the day it expires. There is no
bypass — extending a waiver means editing the matrix in a reviewed PR.

| Waiver | Gate | Excluded test | Owner | Issue | Expires |
|---|---|---|---|---|---|
| `graphql-gasprice-stale-fixture` | `hive-graphql` | `07_eth_gasPrice` | realcodywburns | #1407 | 2026-12-31 |
| `graphql-sendraw-38-underpriced-replacement` | `hive-graphql` | `38_eth_sendRawTransaction_transferEther` | realcodywburns | #1629 | 2026-12-31 |
| `devp2p-getcells-needs-sparse-blobpool` | `hive-devp2p` | `GetCells` | realcodywburns | #1409 | 2026-12-31 |
| `devp2p-invalid-cells-needs-sparse-blobpool` | `hive-devp2p` | `BlobTxWithInvalidCells` | realcodywburns | #1409 | 2026-12-31 |

