# Feature Specification: Ephemery and Hoodi as Built-in Networks

**Feature Branch**: `spec/028-builtin-ephemery-hoodi`

**Created**: 2026-11-12

**Status**: Clarified (2026-11-12). Written from the user's decisions of 2026-11-12: bundle Ephemery with a
self-refresh at reset, switch a running node to the new iteration in place (FR-016), add Hoodi, dev-node implements.

**In flight**: `spec/028-builtin-ephemery-hoodi` (`dev-node`, local session): spec clarified; plan next

**Input**: User request of 2026-11-12, "Ephemery should be shipped with fukuii by default same as mordor, hoodi, and
sepolia", and the answers that followed: bundle the current Ephemery iteration plus a startup self-refresh after the
reset; add Hoodi as a static built-in network; dev-node implements now. Backlog B-227 (supersedes B-225).

---

## Problem

**Ephemery.**
- `fukuii ephemery` fails at startup with "blockchain network 'ephemery' is not configured", unless the operator has
  first run `scripts/ephemery/fetch-genesis.sh` and launched with `FUKUII_CONF_DIR=<out>/current/conf`.
- The default place it looks is `~/.fukuii/ephemery-network/current/chains`. An operator who ran the script with
  another `--out` gets an error that still says "run fetch-genesis.sh first" (B-225). The user hit exactly this on
  2026-11-12.
- No other network fukuii names on its command line needs a separate preparation step.

**Hoodi.**
- Hoodi, a long-lived public Ethereum testnet, is not built in at all.
- The built-in networks are ETC, Mordor, Gorgoroth, ETH mainnet, Sepolia and Platåberget, plus the hive and test
  chains.

**Why Ephemery cannot simply be bundled like Mordor.**
- Ephemery resets every 28 days (2,419,200 s). Each iteration `N` is a new chain: chain id `39438000 + N`, a new
  genesis timestamp, new BPO timestamps and a new genesis hash.
- Iterations can also change the fork schedule. `ephemery-164` had Shanghai to Osaka at genesis and BPO1/BPO2 by
  timestamp, with no Amsterdam. `ephemery-165` has every fork through Amsterdam, BPO1 and BPO2 at genesis.
- So a release that carries one iteration goes stale at the next reset.
- Each iteration is published as release `ephemery-N` of `ephemery-testnet/ephemery-genesis`. `ephemery-165` was
  published on 2026-10-22, and 166 is due around 2026-11-19.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run Ephemery with no setup (Priority: P1)

An operator installs fukuii, starts it with `fukuii ephemery` next to a consensus client, and the node follows the
current Ephemery iteration. They do not download anything, run a script or set an environment variable.

**Why this priority**: This is the request. Every other built-in network works this way, and today Ephemery fails on
first contact.

**Independent Test**: On a machine with no Ephemery files, start `fukuii ephemery` while the bundled iteration is
current. Check two things:
- the genesis it builds has the iteration's published genesis hash;
- with a consensus client attached, it follows the chain head.

**Acceptance Scenarios**:

1. **Given** a clean machine and a release whose bundled iteration has not reset, **When** the operator runs
   `fukuii ephemery`, **Then** it starts on the bundled iteration with the published genesis hash, its chain id and
   fork schedule, and the EL bootnodes, using a datadir for that iteration.
2. **Given** that running node and a consensus client on its Engine API, **When** the chain advances, **Then** the
   node imports and follows the head.
3. **Given** a clean machine, **When** the node starts, **Then** it logs the iteration number, its chain id, its
   genesis hash and the time of its next reset.

---

### User Story 2 - Refresh to the new iteration after a reset (Priority: P1)

After Ephemery resets, the operator restarts `fukuii ephemery`, possibly running a release older than the reset. The
node finds that its iteration has ended, fetches the new one, checks it, and starts on it in a fresh datadir.

**Why this priority**: Without it, the bundled iteration is useless after 28 days. The user chose bundle plus refresh
precisely so that `fukuii ephemery` keeps working between releases.

**Independent Test**: Set the clock past the bundled iteration's reset time. Serve the next iteration from a local fake
release server. Start the node and check that it:
- fetches the new release, validates it and matches the published genesis hash;
- starts in a new datadir for that iteration;
- leaves the old iteration's data untouched.

**Acceptance Scenarios**:

1. **Given** the bundled or cached iteration's reset time has passed and the network is reachable, **When** the node
   starts, **Then** it fetches the newest published iteration, validates it, checks its genesis hash against the
   published value, caches it, and starts on it in that iteration's datadir.
2. **Given** a cached iteration whose reset time has not passed, **When** the node starts, **Then** it does not
   contact the network and starts on the cached iteration.
3. **Given** a release whose content fukuii cannot represent, **When** the node fetches it, **Then** it refuses to
   start and names the reason. Examples: a fork scheduled by block number, an unknown fork key, a non-zero terminal
   total difficulty, a header field it cannot encode.
4. **Given** a release whose genesis, as fukuii builds it, does not match the release's published genesis hash,
   **When** the node fetches it, **Then** it refuses to start and names both hashes.

---

### User Story 3 - Run Hoodi with no setup (Priority: P2)

An operator runs `fukuii hoodi` next to a consensus client and the node joins Hoodi and syncs, like `fukuii sepolia`.

**Why this priority**: The user asked for Hoodi in the same breath. It is a static network, so it is the simpler half.

**Independent Test**: The genesis fukuii builds for Hoodi must equal Hoodi's published genesis hash. Then, with a
consensus client attached, `fukuii hoodi` must reach Hoodi peers and start syncing.

**Acceptance Scenarios**:

1. **Given** a clean machine, **When** the operator runs `fukuii hoodi`, **Then** it starts with chain id 560048, the
   published Hoodi genesis hash, Hoodi's fork schedule and blob schedule, and Hoodi's bootnodes.
2. **Given** that node and a consensus client, **When** it runs, **Then** it peers with Hoodi nodes and syncs, using
   the same default sync mode as Sepolia.
3. **Given** a peer on another network, **When** they handshake, **Then** the fork-id check refuses it, as it does on
   Sepolia today.

---

### User Story 4 - Control the refresh (Priority: P2)

An operator in a locked-down network, or one who wants a fixed iteration for a test, controls where Ephemery comes
from. They can:
- pin the bundled iteration and never fetch;
- point the node at a local copy of a release;
- keep using a directory prepared by `fetch-genesis.sh`.

**Why this priority**: The startup fetch is a new network dependency. Zero-trust deployments need to turn it off, and
existing operators must keep working.

**Independent Test**: With fetching disabled and the network unreachable, start the node before and after a reset and
check the result in each case. With a prepared directory configured, check that the node uses it unchanged.

**Acceptance Scenarios**:

1. **Given** fetching is disabled and the iteration's reset time has not passed, **When** the node starts, **Then** it
   runs that iteration without contacting the network.
2. **Given** fetching is disabled, or the network is unreachable, and the reset time has passed, **When** the node
   starts, **Then** it refuses, saying the iteration has ended and how to supply the new one.
3. **Given** an operator who launches with a `fetch-genesis.sh` directory (`FUKUII_CONF_DIR=<out>/current/conf`),
   **When** the node starts, **Then** it uses that directory exactly as it does today.

---

### User Story 5 - A node running across a reset switches in place (Priority: P2)

A long-running Ephemery node, run as a service for the network (an RPC endpoint, a bootnode, a validator's EL),
reaches its iteration's reset time. It moves onto the new iteration by itself, without exiting, so the service stays
up across resets.

**Why this priority**: The user chose this so that long-running Ephemery services do not need an operator, or a
supervisor restart, every 28 days. Every running node meets a reset every 28 days.

**Independent Test**: Run the node with a controllable clock and a fake release server. Cross the reset time, then
check four things:
- the same process is still running and its listeners never closed;
- the old chain's components stopped;
- the new iteration was fetched, validated and opened in its own datadir;
- the node is syncing the new chain with its new chain id and fork id.

**Acceptance Scenarios**:

1. **Given** a running node and a published next iteration, **When** the reset time passes, **Then** it logs at WARN
   that the iteration has ended, obtains and validates the new iteration, and continues on it in its own datadir. The
   process does not exit.
2. **Given** that switch, **When** a client calls JSON-RPC, WebSocket or the Engine API during and after it, **Then**
   the listeners stay open. Chain-dependent answers reflect a node syncing the new chain from genesis until it has
   caught up.
3. **Given** the next iteration is not yet published at the reset time, **When** the node reaches the reset, **Then**
   it stops serving the old chain as current, logs that it is waiting for the new iteration, and retries on a bounded
   schedule until it can switch.
4. **Given** a consensus client still on the old iteration, **When** it sends old-chain payloads after the switch,
   **Then** fukuii does not import them.

---

### Edge Cases

- **Several iterations behind.** The node starts many iterations after its cached one. It goes straight to the newest
  published release; it does not step through the ones in between.
- **Release not yet published.** The reset has passed, but the next release is not out yet. The node waits within a
  bounded time and retries a few times. If it still finds nothing newer than its own, it refuses with "iteration N
  ended; N+1 is not published yet".
- **Network trouble.** A partial download, a truncated archive or a TLS failure is refused. Nothing partial is cached,
  and the next start tries again.
- **Retracted release.** A published release is retracted and republished with a different hash. The cached copy's
  hash no longer matches the published one, so the node fetches again.
- **Old datadirs.** A datadir from an earlier iteration exists. It is left untouched, never deleted by the node. The
  node logs where earlier iterations' datadirs are and how much space they use.
- **Explicit datadir.** The operator set an explicit datadir. The per-iteration subdirectory is created under it, so
  two iterations never share one.
- **Clock skew.** The machine clock is wrong. The reset decision uses the published reset time against the local
  clock, and the start-up log prints both, so a skewed clock is visible.
- **Hoodi forks.** Hoodi schedules a fork after the release. As with Sepolia today, a newer fukuii release carries the
  new schedule; nothing is fetched at runtime.

## Requirements *(mandatory)*

### Functional Requirements

**Ephemery, bundled**

- **FR-001**: The distribution MUST include one Ephemery iteration: its chain configuration, genesis, published genesis
  hash, reset time, chain id, fork schedule, blob schedule and EL bootnodes. With it, `fukuii ephemery` starts with no
  preparation step.
- **FR-002**: The bundled iteration MUST be built from the published release by the same rules the refresh uses. A test
  MUST pin that its genesis equals the published hash.
- **FR-003**: Maintainers MUST have one command that regenerates the bundled iteration from a named release, so a
  release cut after a reset can carry the new iteration. Doing so is optional: the refresh covers the gap.

**Ephemery, refresh**

- **FR-004**: At startup, when the iteration it would run has passed its reset time, the node MUST look up the newest
  published iteration over HTTPS.
- **FR-005**: The node MUST NOT contact the network when its iteration is still current, unless the operator asks it
  to.
- **FR-006**: A fetched release MUST pass the validation rules `scripts/ephemery/fetch-genesis.sh` applies today, with
  one source of truth for those rules rather than two copies that can drift. The rules are:
  - the timestamp and header quantities are re-encoded;
  - the result is cross-checked against the release's own consistency data;
  - a release is refused for any of these:
    - a fork scheduled by block number;
    - an unknown fork key;
    - a non-zero terminal total difficulty;
    - a header quantity that cannot be encoded;
    - an invalid blob schedule.
- **FR-007**: The node MUST compute the genesis hash of the release as it will use it, and refuse unless that hash
  equals the release's published genesis hash.
- **FR-008**: A validated release MUST be cached under the data directory with its iteration number. A later start
  before that iteration's reset MUST use the cache without fetching.
- **FR-009**: Each iteration MUST run in its own datadir. The node MUST never open an iteration's datadir for another
  iteration, and MUST never delete an earlier iteration's datadir.
- **FR-010**: A failed fetch or validation MUST leave no partial cache entry behind. The refusal message MUST name the
  cause, for example: unreachable, not yet published, cannot represent (with the reason), or hash mismatch (with both
  hashes).
- **FR-011**: The fetch MUST have a bounded total time, and MUST honour the standard proxy settings.
- **FR-012**: The fetch MUST only download data. It MUST NOT execute anything from the release, and MUST NOT follow a
  redirect to a non-HTTPS location.
- **FR-013**: When it cannot represent a release, the node MUST say that a newer fukuii is needed and which feature is
  missing.

**Ephemery, operator control**

- **FR-014**: The operator MUST be able to:
  - disable fetching entirely, and run only the bundled or cached iteration;
  - point the node at a local copy of a release, instead of the default source.
- **FR-015**: A directory prepared by `fetch-genesis.sh` and launched with `FUKUII_CONF_DIR` MUST keep working
  unchanged. If such a directory is configured, it MUST take precedence over the bundled and refreshed iteration.
- **FR-016**: When a running node passes its iteration's reset time, it MUST switch to the new iteration in place,
  without the process exiting (user, 2026-11-12: "it would allow long running services for the network"). The switch
  MUST:
  - log a WARN that names the ended iteration;
  - obtain the new iteration as in FR-004 to FR-008, retrying within a bounded schedule until it is published;
  - stop everything that belongs to the old chain: sync, peers and their handshakes, and the transaction pool;
  - open the new iteration's datadir (FR-009);
  - start again on the new genesis, chain id, fork schedule and bootnodes.

  A process with a single running chain does not change for anything outside the chain:
  - The process, its configuration, its JSON-RPC, WebSocket and Engine API listeners, its JWT secret, its metrics
    endpoint and the TUI stay up across the switch.
  - Until the new chain is ready, chain-dependent requests answer as a node that is syncing from genesis would.
  - If the switch cannot complete (the new iteration cannot be obtained, cannot be represented, or fails to start), the
    node MUST log an ERROR naming the cause and keep retrying on its schedule. It MUST NOT keep serving the old chain
    as current.
- **FR-016a**: A consensus client attached over the Engine API must also move to the new iteration. When it sends
  payloads or forkchoice updates for the old chain after the switch, fukuii MUST answer them as unknown-chain payloads
  and MUST NOT import them. The new iteration's consensus-layer files (FR-017) MUST be in place, and their path logged,
  before the switch completes.
- **FR-017**: The node MUST unpack the release's consensus-layer network directory next to its cache and log its path,
  so a consensus client can be pointed at the same iteration without a separate download.

**Hoodi**

- **FR-018**: The distribution MUST include Hoodi as a built-in network. Its values MUST be taken from Hoodi's
  authoritative published configuration and cross-checked against go-ethereum's parameters:
  - chain id 560048;
  - the published genesis;
  - the fork schedule by timestamp;
  - the blob schedule;
  - the deposit contract;
  - EL bootnodes.
- **FR-019**: A test MUST pin that fukuii's Hoodi genesis hash equals the published Hoodi genesis hash, and that its
  fork ids match go-ethereum's at each fork.
- **FR-020**: `fukuii hoodi` MUST use the same default sync behaviour and the same consensus-client requirement as
  `fukuii sepolia`.

**Compatibility and documentation**

- **FR-021**: ETC, Mordor and Gorgoroth behaviour MUST be unchanged byte for byte, and their existing tests MUST pass
  with no assertion changes.
- **FR-022**: The bundled and refreshed Ephemery entries and the Hoodi entry MUST already carry what spec 024 (Chain
  Registry) will require of a chain entry: the consensus type and the genesis hash. Spec 024 can then take them over
  without rework.
- **FR-023**: The not-configured error MUST no longer be reachable for `fukuii ephemery` or `fukuii hoodi`. For other
  unknown networks it MUST name the directory it searched (B-225).
- **FR-024**: The Ephemery runbook, a Hoodi runbook or section, the CHANGELOG and every document or skill that lists
  the supported networks MUST be updated. `fetch-genesis.sh` becomes the optional way to pin or prepare an iteration.

### Key Entities

- **Ephemery iteration**: one published Ephemery chain. Attributes: iteration number, chain id, genesis, published
  genesis hash, reset time, fork schedule, blob schedule, EL bootnodes, and the consensus-layer network files. Exactly
  one is bundled, any number may be cached, and the node runs one at a time.
- **Release source**: where iterations are looked up. The default is the public ephemery-genesis releases; the operator
  may substitute a local copy or disable the source.
- **Iteration cache**: validated iterations stored under the data directory, keyed by iteration number.
- **Per-iteration datadir**: the chain database of one iteration. It is never shared with, or deleted by, another
  iteration.
- **Hoodi network entry**: the static chain configuration and genesis for Hoodi.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: From a clean machine, an operator starts an Ephemery node with one command and no preparation steps.
  Today it takes three: run the script, set the variable, launch.
- **SC-002**: After a reset, a restarted node is on the new iteration with no operator action beyond the restart. Each
  startup spends at most 60 seconds on the refresh, including retries.
- **SC-003**: Every startup on a current iteration makes zero network requests for Ephemery data.
- **SC-004**: Every refusal (offline after a reset, not yet published, cannot represent, hash mismatch) gives a message
  that names the cause and the next step. The tests cover each one.
- **SC-005**: `fukuii hoodi` builds the published Hoodi genesis hash and reaches Hoodi peers on a clean machine.
- **SC-006**: All ETC, Mordor and Gorgoroth tests pass unchanged, and no ETC chain configuration changes.
- **SC-007**: Live check on dev-node: `fukuii ephemery`, with a consensus client and no `FUKUII_CONF_DIR`, follows the
  live iteration for 24 hours.
- **SC-008**: Across a reset, the same node process keeps running with no operator action. The live check is the
  166 reset, due around 2026-11-19, if the code is ready by then; otherwise a test reset with a controllable clock.
  Within 10 minutes of the new iteration being published, the node is importing the new chain. Its listeners stay open
  throughout.

## Assumptions

- **The trusted source.** The ephemery-genesis GitHub releases are the published source of truth for each iteration.
  Their published genesis hash is the integrity check: fukuii trusts that source over HTTPS, as the operator's own run
  of `fetch-genesis.sh` does today. A second, independent source is out of scope.
- **Release format.** It stays as it is: the assets listed above and the published consistency data. A format change
  is handled like an unknown fork key, by refusing with a clear reason.
- **Size.** An Ephemery chain is at most 28 days long, so keeping earlier iterations' datadirs costs little. Removing
  them stays the operator's choice; the node only reports them.
- **Hoodi.** Its configuration changes only at scheduled forks, which ship in fukuii releases, as for Sepolia.
- **Spec 024.** It is planned but not started. This spec keeps today's chain-configuration format and adds the two
  fields 024 will require; it does not wait for 024.
- **The consensus client.** It is the operator's to run, as on Sepolia. FR-017 makes pointing it at the right
  iteration easier, but fukuii does not launch one.
- **Release target.** 0.12.0, alongside the TUI work. Any decision that affects consensus-critical configuration
  (chain id, genesis, fork schedule) is reviewed by beacon before it lands; forge confirms the ETC paths are untouched.
