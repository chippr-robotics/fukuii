# fukuii-project/fukuii-cli: relationship and the clean-room rule

This page is the record of how this repository relates to `fukuii-project/fukuii-cli`. It replaces issue #1399
(closed 2026-11-25), which held the original assessment. `.github/gates.yml` (`sister_project`) and
`docs/STATUS.md` point here.

## What fukuii-cli is

`fukuii-project/fukuii-cli` is **not a fork of this repository**. It is an independent, ground-up rewrite with no
common ancestor and no shared source: different git history (its root commit is 2026-07-21; ours is IOHK's Mantis
commit of 2016-12-12), a different package namespace (`org.fukuii` against `com.chipprbots.ethereum`), and zero
`chipprbots`, `io.iohk` or `mantis` references in its sources at the time of the assessment. "Drift" between the
two is the wrong model: they share nothing to drift from.

At the assessment (this repo at `9ad5f70`, v0.8.0; fukuii-cli at `f9bd52f`), fukuii-cli had built the lower layers of
a client (`bytes`, `rlp`, `crypto`, `trie`, `types`, `storage`, `chainspec`, `evm`, `execution`, `consensus`,
`consensus-pow`, `consensus-pos`) and had no networking, JSON-RPC, sync, node runtime, packaging or CI. This
repository is the side that runs a node: sync, JSON-RPC, networking, and the operational surface (`ops/`, `hive/`,
`ets/`, `docker/`, `debian/`, the workflows).

## The binding constraint: no code moves into fukuii-cli

fukuii-cli's `NOTICE` states that it contains no Mantis source code and is not a derivative work of Mantis. This
repository's `NOTICE` states the opposite, and its history bears it out.

**So no Mantis-lineage code from this repository may go into fukuii-cli.** Its non-derivation is its central asset,
and a port of our `src/` would destroy it. This is a legal constraint, not an engineering preference, and no
refactoring changes it. The reverse direction (fukuii-cli into this repo) is permitted under Apache-2.0, but would
replace working code with an incomplete one and fork the org's active work. A code merge in either direction is the
wrong instrument.

### The clean-room rule

Anyone working across both trees:

- does not copy, port or paraphrase code from `src/`, `bytes/`, `crypto/`, `rlp/`, `scalanet/`, `build.sbt`, the
  base Docker images, `README.md`, `ets/` or `insomnia_workspace.json` (Mantis lineage) into fukuii-cli;
- writes anything for fukuii-cli above its existing layers (network, JSON-RPC, sync) against specifications and
  reference corpora, never against this tree's source.

## What is contributable: the clean surface

Paths with no commit before 2025-01-01 (no Mantis lineage, Chippr-authored), measured on the full history:

| Path | Notes |
|---|---|
| `ops/` | barad-dur, cirith-ungol, gorgoroth, grafana, checkpoint server |
| `hive/` | hive client adapter |
| `.github/workflows/` | CI, release, packaging |
| `debian/` | packaging, PPA |
| `docs/`, `mkdocs.yml` | |
| `.claude/`, `.specify/`, `.devcontainer/` | |
| `ARCHITECTURE.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `fukuii.sh` | |
| `docker/Dockerfile.{bootnode,distroless,mainnet,mordor}`, `docker/fukuii/**`, `docker/bootnode/**`, `install-fukuii*.sh` | not the base images |

These are exactly the operational layer fukuii-cli lacked at the assessment.

## The chosen direction: role split (option C)

- **`chippr-robotics/fukuii`** stays the running ETC/Mordor infrastructure and the reference node, keeping its
  Mantis lineage and `NOTICE`.
- **`fukuii-project/fukuii-cli`** is the client going forward.
- Chippr contributes to the org through the clean surface above, plus clean-room work above fukuii-cli's existing
  layers. Operational knowledge transfers; source does not.
- This repository is retired as a client only when fukuii-cli reaches sync parity on Mordor. Parity is the gate;
  there is no date.
- This repository's infrastructure (checkpoint server, bootnodes, Gorgoroth) stays alive whichever client wins: it
  is load-bearing for the network.

## Open governance items

These are decisions between the organisations, not engineering tasks in this repo:

- whether fukuii-cli accepts outside contributions yet (its contribution policy, at the assessment, did not);
- Chippr's standing in `fukuii-project` governance, and the role of the other copyright holder named in its
  `NOTICE`;
- the role split above, agreed in writing;
- the scope of a CI / hive / ETS harness contribution, the org's largest gap at the assessment.
