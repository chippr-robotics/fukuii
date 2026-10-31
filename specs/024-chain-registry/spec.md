# Feature Specification: Chain Registry (ChainSpec) — A Chain Is Data

**Feature Branch**: `024-chain-registry`

**Created**: 2026-10-30

**Status**: Clarified (2026-10-31). OQ-1..OQ-3 resolved; ready for `/speckit-plan`. Companion of spec 023 (fleet topology); independent
of 025 and 026 and of spec 018. Can start now.

**Input**: Spec 023 Goal 2 and the user decision D-6 (MESS off by default): adding a network whose consensus type fukuii
already implements must be a registry entry and a genesis file, with no code change and no release.

---

## Clarifications

### Session 2026-10-31

- Q: What is a ChainSpec entry on disk? → A: A **HOCON entry** (today's `<key>-chain.conf` shape, so the nine
  built-ins need no migration) that **embeds or references a geth-format genesis JSON**, plus a **published JSON
  Schema** for the HOCON's shape so Orbita can validate an entry without running fukuii.
- Q: Operator entry with the same `chainKey` as a built-in: shadow or refuse? → A: **Refuse**, naming both files; a
  development-only flag `--chain-registry-allow-shadow` permits the override for devnets.
- Q: Which keys are chain and which are deployment? → A: **Chain**: everything derived from `consensus`
  (`mining.protocol`, `storage-scheme`, validator set, fork dispatch) plus a per-chain **default** for the sync mode
  (`sync.do-snap-sync`). **Deployment profile**: ports, datadir, RPC APIs, peer limits, and an override of the sync
  mode. Consensus-derived keys are not operator-settable (DC-5).
- Q: Keep FR-007's optional per-role resource profiles? → A: **Keep** as optional defaults (heap hint, engine memory
  budget, `replica` retention window) that operators and Orbita override; fukuii never enforces them.

## Problem

A chain is already *mostly* data, but not safely and not completely. Read from `staging` 0f1a904 (0.10.17):

- **Chain parameters are config.** `conf/base/blockchains.conf` includes nine `chains/<name>-chain.conf` files;
  `BlockchainsConfig` (`utils/Config.scala:350-400`) also loads any `<name>-chain.conf` from
  `fukuii.blockchains.custom-chains-dir`. That is the right direction.
- **The CLI gates chains in code.** `App.knownNetworks` (`App.scala:18-28`) is a hardcoded set of nine names. A custom
  chain can be loaded through `-Dconfig.file` but `fukuii <mychain>` does not work without a release.
- **Bad entries are swallowed.** A custom chain file that fails to parse is logged at ERROR and dropped
  (`result.toOption` in `BlockchainsConfig.apply`); the node then fails later with `NoSuchElementException: blockchain
  network '<name>' is not configured`, after logging has started and possibly after the datadir is opened. That is the
  "silent fallback" the working discipline forbids.
- **Three keys must agree and nothing checks them.** The consensus family is spread over `network-type = "eth"` in the
  chain file (read as `NetworkType.ETH` by `StdSignedTransactionValidator`, `TxAdmission`, `GasPriceOracle`),
  `mining.protocol = "engine-api" | "pow"` in the profile (`conf/sepolia.conf`), and `sync.snap-sync.storage-scheme`
  in the profile. `conf/sepolia.conf` carries a 15-line comment explaining what happens when `mining.protocol` is
  wrong: every PoS header fails with `HeaderPoWError`. A registry entry must make that class of error impossible.
- **MESS has a default nobody validates.** `MESSConfig(enabled = false)` and `mess.enabled = false` in both ETC chain
  files; nothing states it as a constraint or checks it at load.
- **Genesis and fork schedule are not checked against a synced datadir.** Changing a fork block for a chain already
  synced past it is accepted and produces a consensus split at the next restart.

---

## Goals

1. A **ChainSpec** is one validated registry entry per chain, loaded from a directory, with a genesis file beside it.
2. Adding a chain whose consensus type fukuii implements requires **no code change and no release** (SC-001).
3. Every existing built-in chain produces a **byte-identical** `BlockchainConfig` through the registry (SC-002), so
   this spec is consensus-neutral by construction.
4. Invalid entries **fail fast with a named error before the datadir is opened** (SC-003).
5. The MESS default is a validated constraint (DC-2).

## Non-goals

- New consensus families (spec 023 D-2). The consensus-type enum has exactly the values the build implements.
- Changing any fork rule, genesis handling, or the `BlockchainConfig` type's semantics.
- Chain *discovery* or a networked registry. The registry is files on disk (or on the classpath).
- Per-role resource tuning beyond declaring defaults (FR-007); sizing is Orbita's or the operator's.

---

## Key entities

- **ChainSpec**: the registry entry for one chain. Fields: `chainKey`, `chainId`, `networkId`, `consensus`
  (enum, FR-003), `family` (`etc` | `eth`, today's `network-type`), fork schedule (block-number keys for `etc`,
  timestamp keys for `eth`), genesis reference, bootnodes and DNS discovery domains, supported sync modes and storage
  scheme, finality rule (`genesis-only` for `etc`, `consensus-layer` for `eth`; consumed by 025), MESS block (ETC
  family only), and optional default resource profiles per role (FR-007).
- **Registry**: a directory of ChainSpec entries plus genesis files, with the built-in nine on the classpath and an
  operator directory layered over it (today's `custom-chains-dir`, kept and promoted).
- **`chainKey`**: the fukuii-local name (`etc`, `sepolia`, …); the only key used to select a chain. `chainId` is never
  used alone as a key (two testnets or a fork may share one).

---

## Gates

- **GT-1: Golden config.** Before the loader replaces `BlockchainsConfig`, a test captures the `BlockchainConfig`
  produced today for every built-in chain (all nine) and the registry loader must reproduce each one exactly (SC-002).
  No field-level change to any built-in chain ships in this spec.

---

## Design constraints *(mandatory)*

**DC-1: A chain is data; invalid data fails fast.** Adding a chain whose consensus type is implemented is a ChainSpec
entry and a genesis file. Entries are validated at load: unknown fields, missing required forks for the family, a
`consensus` value the build does not provide, a `family` that disagrees with `consensus`, a `chainKey` that collides,
or a genesis whose hash does not match a declared `genesisHash` **fail the process with a named error before the datadir
is opened and before any port is bound**. There is no "log and continue".

**DC-2: MESS is off by default and the registry says so.** ECIP-1100 MESS stays disabled unless the ChainSpec enables
it. The loader rejects an ETC-family entry that omits the MESS block (explicit `enabled = false` is required so the
choice is visible), and rejects a MESS block on an ETH-family entry. Enabling MESS changes nothing in 025: ETC
finality is genesis-only and consumers must not assume MESS bounds reorg depth (023 D-6).

**DC-3: `chainKey` everywhere, `chainId` never alone.** Every log line, metric label, health field, record (025) and
datadir path that names a chain uses `chainKey`; `chainId` travels beside it for consumers that need it.

**DC-4: Consensus-neutral by construction.** For every built-in chain, the registry loader produces a
`BlockchainConfig` equal to today's (GT-1). The ETC/ETH code-path separation (`OlympiaOpCodes`/`forBlock()` vs
`OsakaOpCodes`/`forTimestamp()`) is unchanged; the `family` field only selects which validator set applies to the entry.

**DC-5: One source of truth for the consensus family.** `consensus`, `family`, `mining.protocol` and `storage-scheme`
are derived from or validated against the ChainSpec; a profile cannot set `mining.protocol = "pow"` on an
`engine-api` chain and start. The 15-line warning comment in `conf/sepolia.conf` becomes a load-time error.

**DC-6: The fork schedule is checked against the datadir.** On open, the node compares the ChainSpec's fork schedule
and genesis hash to what the datadir recorded at first start. A change to a fork activation that the datadir has
already synced past is refused with a named error (geth's `ChainConfig` compatibility check is the model). Adding a
future fork or a bootnode is allowed.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — An operator adds a chain without a release (Priority: P1)

An operator adds a new EVM network whose consensus type fukuii already implements (an Engine API devnet, or
Platåberget treated as if it were not built in), using a ChainSpec entry and a genesis file, and starts `ingest`.

**Independent Test**: SC-001.

**Acceptance Scenarios**:
1. **Given** a valid ChainSpec for an Engine API chain in the operator registry directory, **When** `fukuii --chain
   <key>` (or `fukuii <key>`) starts, **Then** the node loads it, syncs and imports with no code change.
2. **Given** a ChainSpec naming `consensus = clique`, **When** it loads, **Then** the process exits with a named error
   listing the supported values, before opening the datadir.
3. **Given** two entries with the same `chainKey`, **When** the registry loads, **Then** it fails naming both paths.

### User Story 2 — Existing chains are unchanged (Priority: P1)

**Independent Test**: SC-002; `testEssential` and the ethereum/tests jobs at baseline.

**Acceptance Scenarios**:
1. **Given** the nine built-in chains, **When** loaded through the registry, **Then** each `BlockchainConfig` equals
   the golden capture field for field.

### User Story 3 — A misconfigured profile cannot start (Priority: P2)

**Independent Test**: SC-003.

**Acceptance Scenarios**:
1. **Given** a Sepolia ChainSpec and a profile that sets `mining.protocol = "pow"`, **When** the node starts, **Then**
   it exits with a named error naming both values, instead of failing every header with `HeaderPoWError`.
2. **Given** an ETC ChainSpec with `mess` omitted, **When** it loads, **Then** it fails with an error that says MESS
   must be declared and that the default is `false`.
3. **Given** a datadir synced past Spiral and a ChainSpec that moves `spiral-block-number` later, **When** the node
   opens it, **Then** it refuses with a named error.

### Edge cases

- `custom-chains-dir` points at a path that does not exist: fail (today: silently no custom chains).
- A ChainSpec for an ETH-family chain omits `terminal-total-difficulty` or the Engine API JWT: fail with the field name.
- A `chainId` wider than 32 bits (Platåberget, 7091047534): accepted; the type is `BigInt`/`Long` as today.
- A chain whose genesis file is large (Platåberget's allocation): the hash check streams; no full-file duplicate.
- The same `chainKey` in the built-in set and the operator directory: the loader **refuses**, naming both files;
  `--chain-registry-allow-shadow` (development only) lets the operator entry win and logs at WARN which file won.

---

## Requirements *(mandatory)*

### Functional requirements

- **FR-001** fukuii MUST load chains from a ChainSpec registry: the built-in entries on the classpath plus an operator
  directory, each entry validated at startup per DC-1, DC-2, DC-5 and DC-6, with every error named and the process
  exiting before the datadir is opened. An entry is a HOCON file `<chainKey>-chain.conf` that embeds or references a
  geth-format genesis JSON; the repository MUST publish a JSON Schema for the entry's shape (`specs/024-chain-registry/
  contracts/chainspec.schema.json`, kept in sync by a test) so Orbita can validate entries offline. A `chainKey`
  present in both the built-in set and the operator directory MUST be refused unless `--chain-registry-allow-shadow` is
  set.
- **FR-002** `App.knownNetworks` MUST be removed; `fukuii <key>` and `--chain <key>` MUST accept any `chainKey` the
  registry holds, and MUST fail with the list of known keys otherwise.
- **FR-003** The `consensus` field MUST be an enum with exactly the values the build implements today:
  `ethash-pow` (ETC family) and `engine-api` (ETH family, PoS). Each value MUST imply the validator set, the fork
  dispatch mode (block-number vs timestamp) and the allowed sync modes. Adding a value is a code change by definition
  (023 D-2).
- **FR-004** The loader MUST reject: unknown fields; a `family` that disagrees with `consensus`; a fork schedule missing
  a fork the family requires; a `genesisHash` that does not match the genesis file; a profile `mining.protocol` or
  `storage-scheme` incompatible with the entry (DC-5); an ETC entry without an explicit `mess` block or an ETH entry
  with one (DC-2).
- **FR-005** On datadir open, the node MUST compare the ChainSpec's genesis hash and fork schedule to the values the
  datadir recorded at first start and MUST refuse a change to any activation already synced past (DC-6). The recorded
  values MUST be written at first start for new datadirs and backfilled from the existing genesis header for old ones.
- **FR-006** fukuii MUST provide `fukuii chains list` (keys, chainId, family, consensus, source path) and
  `fukuii chains validate [<dir>]` (run the loader and report every error without starting a node).
- **FR-007** A ChainSpec MAY declare default resource profiles per role (heap hint, RocksDB/engine memory budget,
  retention window for `replica`); they are defaults an operator or Orbita overrides, never limits fukuii enforces
  (kept, 2026-10-31). A ChainSpec MUST declare the chain's default sync mode (`sync.do-snap-sync`); the deployment
  profile MAY override it. Ports, datadir, RPC APIs and peer limits are deployment keys and MUST NOT appear in a
  ChainSpec.
- **FR-008** The ChainSpec MUST carry `finalityRule` (`genesis-only` | `consensus-layer`), derived from `consensus`
  and not operator-settable, so 025 reads one field.

### Success criteria

- **SC-001** A chain supported by an existing consensus type (Platåberget loaded as an operator entry, or a fresh
  Engine API devnet) is brought up from a registry entry and a genesis file with zero code changes, in the 023 harness.
- **SC-002** All nine built-in chains produce a `BlockchainConfig` identical to the golden capture; `testEssential`
  and both ethereum/tests jobs are at baseline.
- **SC-003** Every FR-004 and FR-005 rejection class has a test that asserts the process exits before `db.rocksdb.path`
  (or the 018 engine path) is created or opened, and that the error names the field.
- **SC-004** `conf/sepolia.conf`'s `mining.protocol` warning comment is replaced by a one-line reference to the
  load-time check; no profile in `conf/` needs `mining.protocol` set by hand.

---

## Open questions

- **OQ-1** *(resolved 2026-10-31)*: HOCON entry + embedded/referenced geth genesis JSON + published JSON Schema (FR-001).
- **OQ-2** *(resolved 2026-10-31)*: refuse; `--chain-registry-allow-shadow` for development (FR-001, edge cases).
- **OQ-3** *(resolved 2026-10-31)*: consensus-derived keys and the sync-mode default are chain; ports, datadir, RPC
  APIs, peer limits and the sync-mode override are deployment (FR-007).

---

## Related specs

- **023 fleet-topology**: consumes `chainKey` and `finalityRule`; `--chain` flag (023 FR-001).
- **025 change-record-export**: reads `finalityRule`, `chainKey`, `chainId` from the ChainSpec.
- **009 Amsterdam**: Platåberget is the natural SC-001 fixture (public, timestamp forks, wide `chainId`).
- `fukuii-custom-networks` skill: its genesis authoring steps become "write a ChainSpec"; update after FR-006.

## Known tasks (seed for `/speckit-tasks`)

- **T-001** Golden capture of the nine built-in `BlockchainConfig`s (GT-1).
- **T-002** ChainSpec schema (HOCON shape + JSON Schema, OQ-1), the `consensus` enum (FR-003), `finalityRule`
  (FR-008); loader with every FR-004 check; `ConfigValidator` integration so errors report through one path.
- **T-003** Remove `App.knownNetworks` (FR-002); `fukuii chains list|validate` (FR-006).
- **T-004** Datadir compatibility record and check (FR-005, DC-6); `vault` reviews the AppState key.
- **T-005** DC-5 derivation of `mining.protocol` / `storage-scheme`; remove the hand-set values from `conf/*.conf`
  (SC-004); `beacon` confirms the Sepolia validator selection is unchanged, `forge` the ETC one.
- **T-006** SC-001 in the 023 harness; docs and the `fukuii-custom-networks` skill update.
