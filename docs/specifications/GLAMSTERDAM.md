# Glamsterdam (Amsterdam + Gloas)

Glamsterdam is the Ethereum hard fork after Fusaka: **Amsterdam** on the execution layer (EL, fukuii's
side) and **Gloas** on the consensus layer (CL). Its scope is defined by the hardfork meta
[EIP-7773](https://eips.ethereum.org/EIPS/eip-7773). The two headline changes are enshrined
proposer-builder separation ([EIP-7732](https://eips.ethereum.org/EIPS/eip-7732), CL) and block-level
access lists ([EIP-7928](https://eips.ethereum.org/EIPS/eip-7928), EL).

!!! warning "ETH-family only"
    Glamsterdam applies to Ethereum networks (mainnet, Sepolia, Platåberget, hive). It does **not** apply to
    Ethereum Classic, Mordor or Gorgoroth. Amsterdam rules are reachable only through the timestamp fork
    `amsterdam-timestamp`, which no ETC-family chain config declares — `ChainConfigMatrixSpec` fails the build
    if one ever does. ETC's own upgrade path is Olympia (ECIP-1111/1112/1121).

**fukuii release: 0.9.0**, the Glamsterdam release. Implementation is tracked in
[#1409](https://github.com/chippr-robotics/fukuii/issues/1409) and
[#1415](https://github.com/chippr-robotics/fukuii/issues/1415) (spec `specs/009-amsterdam-fork-support/`). The
[status table](#fukuii-implementation-status) says what exists in 0.9.0 and what does not.

## Activation schedule

| Network | Chain ID | Amsterdam activation (UTC) | `amsterdam-timestamp` | Gloas epoch | fukuii config |
|---|---|---|---|---|---|
| Platåberget | 7091047534 | 2026-08-20 07:50:24 | `1787212224` | 1536 | `conf/base/chains/plataberget-chain.conf` |
| Sepolia | 11155111 | **2026-10-06 13:53:36** | `1791294816` | 353024 | `conf/base/chains/sepolia-chain.conf` |
| Hoodi | 560048 | not scheduled | — | — | not a fukuii network |
| Mainnet | 1 | not scheduled | — | — | `conf/base/chains/eth-chain.conf` (deliberately unset) |

Sources: the EIP-7773 activation table; [eth-clients/sepolia](https://github.com/eth-clients/sepolia)
`metadata/genesis.json` (`amsterdamTime`) and `metadata/config.yaml` (`GLOAS_FORK_EPOCH: 353024`); go-ethereum
`params.SepoliaChainConfig.AmsterdamTime`; ethpandaops
[glamsterdam-devnets](https://github.com/ethpandaops/glamsterdam-devnets) `network-configs/devnet-8` for Platåberget.

Neither network adds a blob-parameter fork at Amsterdam: blob target/max/update-fraction stay at BPO2's
14 / 21 / 11684671 (Platåberget's `eth_config` reports exactly this).

### Fork identifiers (EIP-2124 / EIP-6122)

A node that disagrees on these is refused at the `Status` handshake, so they are pinned in tests
(`ForkIdSepoliaSpec`, `ForkIdPlatabergetSpec`).

| Network | Head | Fork hash | Next | Ground truth |
|---|---|---|---|---|
| Sepolia | BPO2 … Amsterdam − 1 | `0x268956b6` | `1791294816` | go-ethereum `core/forkid/forkid_test.go` |
| Sepolia | Amsterdam onward | `0x6c1d9423` | `0` | go-ethereum `core/forkid/forkid_test.go` |
| Platåberget | genesis … Amsterdam − 1 | `0x94ebe4ed` | `1787212224` | CRC32(genesis hash), computed independently |
| Platåberget | Amsterdam onward | `0x05842a50` | `0` | live `eth_config` → `current.forkId` |

Platåberget activates every fork through Osaka/BPO1/BPO2 **at genesis**. Forks at or before the genesis
timestamp are part of the genesis ruleset, not checksum entries, so Amsterdam is its only fork-id entry.

## EIP scope (EIP-7773)

### fukuii implementation status

As of 0.9.0 (2026-10-03). "Implemented" means the rule exists in code and the execution-specs `tests@v21.0.0`
Amsterdam blockchain corpus passes for it (26,503 / 26,503 tests, replayed in CI by the `EEST Amsterdam` job).
EIP-2780, 8037, 8038, 7778, 7954 and 7708 first shipped in 0.8.5
([#1408](https://github.com/chippr-robotics/fukuii/pull/1408)); 0.9.0 completes and corrects them against that corpus.

**Execution layer** — fukuii's responsibility:

| EIP | Title | fukuii |
|---|---|---|
| [2780](https://eips.ethereum.org/EIPS/eip-2780) | Resource-based intrinsic transaction gas | Implemented; authorization processing and dispatch completed in 0.9.0 ([#1423](https://github.com/chippr-robotics/fukuii/issues/1423)) |
| [7708](https://eips.ethereum.org/EIPS/eip-7708) | ETH transfers emit a log | Implemented |
| [7778](https://eips.ethereum.org/EIPS/eip-7778) | Block gas accounting without refunds | Implemented |
| [7843](https://eips.ethereum.org/EIPS/eip-7843) | SLOTNUM opcode | Implemented ([#1424](https://github.com/chippr-robotics/fukuii/issues/1424)): header `slotNumber` and the `SLOTNUM` (`0x4b`) opcode |
| [7928](https://eips.ethereum.org/EIPS/eip-7928) | Block-level access lists | Implemented ([#1418](https://github.com/chippr-robotics/fukuii/issues/1418), [#1426](https://github.com/chippr-robotics/fukuii/issues/1426)): codec, access recording during execution, per-index diff, validation against the header hash on import, persistence (`blockHash → BAL RLP`) |
| [7954](https://eips.ethereum.org/EIPS/eip-7954) | Increase maximum contract size | Implemented |
| [7976](https://eips.ethereum.org/EIPS/eip-7976) | Increase calldata floor cost | Implemented ([#1421](https://github.com/chippr-robotics/fukuii/issues/1421)), with the floor as a validity rule |
| [7981](https://eips.ethereum.org/EIPS/eip-7981) | Increase access list cost | Implemented ([#1421](https://github.com/chippr-robotics/fukuii/issues/1421)) |
| [7997](https://eips.ethereum.org/EIPS/eip-7997) | Deterministic factory contract | No client code: the EIP forbids checking for the contract at the fork; networks provide it (Platåberget has it in genesis) |
| [8024](https://eips.ethereum.org/EIPS/eip-8024) | Backward-compatible SWAPN, DUPN, EXCHANGE | Implemented ([#1424](https://github.com/chippr-robotics/fukuii/issues/1424)): opcodes `0xe6`–`0xe8` |
| [8037](https://eips.ethereum.org/EIPS/eip-8037) | State creation gas cost increase | Implemented; per-dimension block capacity, the state-gas reservoir across precompile calls and system calls completed in 0.9.0 ([#1420](https://github.com/chippr-robotics/fukuii/issues/1420), [#1437](https://github.com/chippr-robotics/fukuii/issues/1437), [#1440](https://github.com/chippr-robotics/fukuii/issues/1440)) |
| [8038](https://eips.ethereum.org/EIPS/eip-8038) | State-access gas cost update | Implemented; the EXTCODESIZE / EXTCODECOPY code-read surcharge added in 0.9.0 ([#1440](https://github.com/chippr-robotics/fukuii/issues/1440)) |
| [8246](https://eips.ethereum.org/EIPS/eip-8246) | Remove SELFDESTRUCT burn | Implemented ([#1422](https://github.com/chippr-robotics/fukuii/issues/1422)) |
| [8282](https://eips.ethereum.org/EIPS/eip-8282) | Builder execution requests | Implemented ([#1430](https://github.com/chippr-robotics/fukuii/issues/1430) for the RPC side). `eth_config` reports `BUILDER_DEPOSIT_CONTRACT_ADDRESS` and `BUILDER_EXIT_CONTRACT_ADDRESS` from Amsterdam (see [JSON-RPC at Amsterdam](#json-rpc-at-amsterdam)) |

**Consensus layer** — implemented by the paired CL client, no fukuii code:
[7688](https://eips.ethereum.org/EIPS/eip-7688) (forward-compatible consensus data structures),
[7732](https://eips.ethereum.org/EIPS/eip-7732) (enshrined proposer-builder separation — its EL-facing surface is the
[Engine API](#engine-api) below), [8045](https://eips.ethereum.org/EIPS/eip-8045) (exclude slashed validators from
proposing), [8061](https://eips.ethereum.org/EIPS/eip-8061) (increase exit and consolidation churn).

**Networking:**

| EIP | Protocol | fukuii |
|---|---|---|
| [7975](https://eips.ethereum.org/EIPS/eip-7975) | eth/70 — partial block receipt lists | Implemented, opt-in (`fukuii.network.protocols.eth70`, default `false`) |
| [8159](https://eips.ethereum.org/EIPS/eip-8159) | eth/71 — block access list exchange | Serving implemented ([#1428](https://github.com/chippr-robotics/fukuii/issues/1428)): `GetBlockAccessLists` is answered from the stored lists within a 2 MiB / 1,024-entry limit. Opt-in (`fukuii.network.protocols.eth71`, default `false`); fukuii does not yet fetch lists from peers |
| [8070](https://eips.ethereum.org/EIPS/eip-8070) | eth/72 — sparse blobpool | **Not implemented** ([#1431](https://github.com/chippr-robotics/fukuii/issues/1431)); not needed to follow the chain |
| [8189](https://eips.ethereum.org/EIPS/eip-8189) | snap/2 — BAL-based state healing | Opt-in capability (`fukuii.network.protocols.snap2`, default `false`); fukuii's sync uses snap/1 |
| [8136](https://eips.ethereum.org/EIPS/eip-8136) | Cell-level deltas for data column broadcast | CL only |

Informational: [7904](https://eips.ethereum.org/EIPS/eip-7904) (compute gas cost analysis),
[8261](https://eips.ethereum.org/EIPS/eip-8261) (gas limit schedule).

## Engine API

From [execution-apis `src/engine/amsterdam.md`](https://github.com/ethereum/execution-apis/blob/main/src/engine/amsterdam.md):

| Method / structure | Change | fukuii |
|---|---|---|
| `ExecutionPayloadV4` | `ExecutionPayloadV3` + `blockAccessList` (RLP, EIP-7928) + `slotNumber` (EIP-7843) | Implemented: the header commits to `keccak256` of the list's bytes as sent; block execution rebuilds the list and checks it against that hash |
| `PayloadAttributesV4` | `PayloadAttributesV3` + `slotNumber` + `targetGasLimit` | Implemented: `slotNumber` required, `targetGasLimit` optional (as in go-ethereum); both are in the payload ID, and the builder steers the gas limit toward `targetGasLimit` |
| `engine_newPayloadV5` | Takes `ExecutionPayloadV4`; missing `blockAccessList` → `-32602`; undecodable BAL → `INVALID` | Implemented. The access list is decoded strictly and bound to the header through the block hash, and block execution validates it ([#1426](https://github.com/chippr-robotics/fukuii/issues/1426)) |
| `engine_getPayloadV6` | Returns `ExecutionPayloadV4` | Implemented: serves the Amsterdam payloads fukuii builds, block access list included (see below) |
| `engine_forkchoiceUpdatedV4` | Takes `PayloadAttributesV4` and `custodyColumns` | Implemented. `custodyColumns` must be 16-byte DATA or null (else `-32602`) and is otherwise ignored: fukuii does not sample blobs |
| `engine_getPayloadBodiesByHashV2` / `ByRangeV2` | `ExecutionPayloadBodyV2` adds `blockAccessList` (`null` pre-Amsterdam or pruned) | Implemented ([#1428](https://github.com/chippr-robotics/fukuii/issues/1428)): serves the stored list |
| `engine_getBlobsV3` / `engine_getBlobsV4` | Blob cells and proofs, partial responses allowed | **Not implemented** ([#1431](https://github.com/chippr-robotics/fukuii/issues/1431)); `engine_getBlobsV1` and `V2` are served |

`engine_newPayloadV4`, `engine_getPayloadV5` and `engine_forkchoiceUpdatedV3` reject Amsterdam timestamps with
`-38005 Unsupported fork`. `engine_exchangeCapabilities` advertises `engine_newPayloadV5`,
`engine_forkchoiceUpdatedV4` and `engine_getPayloadV6` on every network, as go-ethereum does.

**Proposing.** fukuii builds Amsterdam payloads ([#1427](https://github.com/chippr-robotics/fukuii/issues/1427)):
the 23-field header carries the attributes' `slotNumber` (which `SLOTNUM` reads while the block executes) and the
hash of the block access list its own execution builds, which `engine_getPayloadV6` serves as the payload's
`blockAccessList`. `requestsHash` commits to the EIP-8282 builder requests, and gas used is EIP-8037's maximum of
the execution and state dimensions. The gas limit moves toward `targetGasLimit` at go-ethereum's `CalcGasLimit`
rate (the parent's limit is kept when the attributes carry none), and transactions are packed by EIP-8037's
per-dimension capacity rule: one that would overflow either dimension is left out. Attributes without `slotNumber`
at an Amsterdam timestamp are refused with `-38003`, after the forkchoice state is applied.

Conformance, from execution-specs `tests@v21.0.0`, run by the CI job `EEST Amsterdam`:

- the whole Amsterdam `blockchain_test` corpus replays through block import: 26,503 / 26,503 tests;
- the payload builder rebuilds all 27,352 blocks of that corpus from their parents with identical block hashes;
- the `blockchain_tests_engine` fixtures (`for_amsterdam`, `for_bpo2toamsterdamattime15k`) replay through the real
  Engine API controller with `EestEngineFixtureCorpusSpec`.

## JSON-RPC at Amsterdam

What the JSON-RPC surfaces do from Amsterdam ([#1430](https://github.com/chippr-robotics/fukuii/issues/1430)). Every
change is keyed on the Amsterdam timestamp, except the calldata floor in `eth_estimateGas`, which follows the fork where
a floor becomes a validity rule, and the pool's choice of fork, which follows the chain head.

| Surface | From Amsterdam | Before Amsterdam, and ETC |
|---|---|---|
| `eth_config` | `systemContracts` adds EIP-8282's `BUILDER_DEPOSIT_CONTRACT_ADDRESS` (`0x0000bff46984e3725691fa540a8c7589300d8282`) and `BUILDER_EXIT_CONTRACT_ADDRESS` (`0x000064d678505ad48f8ccb093bc65613800e8282`), the queues block execution calls, as go-ethereum's `ActiveSystemContracts` does. fukuii's response for Platåberget equals the live network's (`EthConfigPlatabergetSpec`). | Unchanged. |
| txpool pre-filter (transactions from peers and from re-orgs) | Admits under the rules of the fork active at the chain head, as go-ethereum's pool does: EIP-2780's intrinsic cost (a self-transfer is valid at 12,000), EIP-7976 / EIP-7981's floor, both at most 2^24. `eth_sendRawTransaction` does not go through this filter. | ETH: the head's fork as well. The filter used to take the latest configured fork up to Osaka, and where Amsterdam was scheduled it applied no floor, because that proxy could not tell which floor applied. So on Sepolia until 2026-10-06, a peer's transaction below EIP-7623's floor is now refused, as it already was on mainnet; it is invalid in any block. ETC: unchanged. The head is not read and no floor rule applies. |
| `eth_estimateGas` | The search starts at the least valid gas limit, `max(intrinsic, floor)`: 12,000 for a self-transfer (21,000 before), and the EIP-7976 floor for a call carrying calldata. It used to answer the intrinsic cost, below the floor, which is a gas limit every node rejects. go-ethereum answers any plain transfer to an EOA with 21,000 without searching lower; fukuii answers the exact minimum. | ETH Prague and Osaka: the same rule with EIP-7623's floor, where the search also answered the intrinsic cost below it. It applies on ETC from the Olympia block as well, under the validator's own predicate, but no shipped chain schedules Olympia. ETH before Prague, and ETC before Olympia: unchanged, starting at 21,000. |
| `eth_simulateV1` | A simulated block at an Amsterdam timestamp has the 23-field header, and the response carries `blockAccessListHash` and `slotNumber`. `blockAccessListHash` is the empty list's hash, because the simulator records no access list, just as `requestsHash` is not computed. `slotNumber` is 0, because a simulated block has no beacon slot; go-ethereum's simulator leaves it unset, so SLOTNUM reads 0 there too. | Unchanged. |
| Trace and debug replays of a stored block (`debug_traceTransaction`, `debug_traceBlockBy*`, `debug_intermediateRoots`, `debug_traceChain`, `trace_transaction`, `trace_block`, `trace_replay*`) | Re-executed as block import executed the block: the EIP-4788 / EIP-2935 system calls, then each transaction through block execution's own path with the tracer attached. The gas each transaction reports, its trace and the state root after it match block execution, and every transaction the block carries is replayed. | Unchanged: the bare VM, below. |

### What the bare VM leaves out

Two kinds of request still run a transaction on the bare VM (`StxLedger.simulateTransaction`). The first is a call built
from RPC arguments, on any fork: `eth_call`, `eth_createAccessList`, GraphQL `call`, `debug_traceCall`, `trace_call`,
`trace_callMany` and `eth_estimateGas`'s search. The second is a replay of a block before Amsterdam, or of any ETC
block. Compared with block execution, that path:

- does not apply an EIP-7702 authorization list, so a Type-4 transaction's delegations and authority nonce bumps are
  missing, and at Amsterdam so are EIP-2780's authorization charges (calls built from RPC arguments carry no list);
- does not charge the calldata floor (EIP-7623 from Prague, EIP-7976 / EIP-7981 at Amsterdam). The gas it reports is
  what execution took, and a gas limit below the floor runs instead of being refused;
- does not refund the sender or pay the coinbase, and does not delete self-destructed or empty touched accounts;
- for replays, starts the block without its EIP-4788 / EIP-2935 preamble and does not persist the world between
  transactions, so `debug_intermediateRoots` before Amsterdam does not report the roots block execution reached;
- for replays, recovers a stored block's transactions through the pool's pre-#1430 rules (the latest configured fork up
  to Osaka), which can drop a valid transaction from the replay. On a chain without Amsterdam, that includes a
  pre-Prague transaction below EIP-7623's floor.

At Amsterdam the bare VM already carries what lives in the frame itself: EIP-2780's recipient-creation and
delegation-access charges, EIP-8037's state-gas reservoir, and EIP-7708's transfer logs. `eth_estimateGas` makes up for
the missing floor by starting its search at `max(intrinsic, floor)`.

The pre-Amsterdam and ETC replays keep this path on purpose, so #1430 changes no pre-Amsterdam or ETC trace output, and
hive's rpc-compat suite checks that output. Moving them to block execution is a separate change, and ETC's side needs
forge's sign-off.

Not changed by #1430: `eth_getBlockByNumber` and `eth_getBlockByHash` do not yet report an Amsterdam header's
`blockAccessListHash` and `slotNumber`, which go-ethereum's `RPCMarshalHeader` does.

## Testing against Platåberget

Platåberget is the public, permissionless Glamsterdam testnet (launched 2026-08-17 on the
`glamsterdam-devnet-8` spec, expected to run a few months). It is fukuii's live-network target for
exercising the Amsterdam implementation **before Sepolia activates on 2026-10-06**.

| | |
|---|---|
| Launcher | `fukuii plataberget` (config `conf/plataberget.conf`) |
| Chain / network ID | `7091047534` (`0x1a6a8cc6e`) |
| Genesis | `2026-08-13 12:00:00 UTC` (`1786622400`), hash `0xee33ef92bbabcf07bcf44fea1d18a7925c5f7f9da8f81334ea19b0f3cb892b31` |
| Network config | [devnet-8 metadata](https://github.com/ethpandaops/glamsterdam-devnets/tree/master/network-configs/devnet-8/metadata), [plataberget.dev](https://plataberget.dev/) |
| RPC / explorer / faucet | `https://rpc.plataberget.ethpandaops.io` · `https://dora.plataberget.ethpandaops.io` · `https://faucet.plataberget.ethpandaops.io` |
| CL checkpoint sync | `https://checkpoint-sync.plataberget.ethpandaops.io` |
| Client images | `ethpandaops/<client>:glamsterdam-devnet-8` |

1. **Engine API.** Create a JWT secret (`openssl rand -hex 32 > jwt.hex`) and enable the authenticated
   Engine API in your operator config. It is off by default:

    ```hocon
    fukuii.network.engine-api {
      enabled = true
      port = 8551
      jwt-secret-path = "/path/to/jwt.hex"
    }
    ```

2. **Execution client.** `fukuii plataberget` loads the shipped genesis and the ethpandaops EL bootnodes.
   Confirm block 0's hash matches the table above before going further.
3. **Consensus client (Lighthouse).** Use `ethpandaops/lighthouse:glamsterdam-devnet-8` (other CLs from
   `ethpandaops/<client>:glamsterdam-devnet-8` work the same way) with the devnet-8 `config.yaml` and
   `genesis.ssz` from the [metadata directory](https://github.com/ethpandaops/glamsterdam-devnets/tree/master/network-configs/devnet-8/metadata).
   Flags that matter:

    ```text
    lighthouse bn \
      --testnet-dir /path/to/devnet-8-metadata \
      --execution-endpoint http://<fukuii-host>:8551 \
      --execution-jwt /path/to/jwt.hex \
      --checkpoint-sync-url https://checkpoint-sync.plataberget.ethpandaops.io \
      --boot-nodes "$(paste -sd, /path/to/devnet-8-metadata/bootstrap_nodes.txt)"
    ```

    Pass `--boot-nodes` explicitly, with the contents of `metadata/bootstrap_nodes.txt` (comma-separated ENRs):
    in the soak setup Lighthouse did not pick them up from `boot_enr.yaml`. Use the same `jwt.hex` on both sides.
4. **Check the handshake.** Peers should accept the fork id `0x05842a50` (and `0x94ebe4ed` before Amsterdam);
   anything else means the fork schedule or genesis is wrong.

**SNAP or regular sync.** `conf/plataberget.conf` sets `fukuii.sync.do-snap-sync = false`: a fresh node imports
the chain from genesis over devp2p, toward the head the CL designates with `forkchoiceUpdated`. Set
`do-snap-sync = true` to SNAP-sync to the head instead (Platåberget uses `storage-scheme = "path"`, which the
config already sets). SNAP on post-merge ETH networks has open fixes in progress; see the
[0.9.0 release notes](https://github.com/chippr-robotics/fukuii/blob/main/docs/releases/0.9.0.md#known-issues).

**CPU.** discv4 can be switched off with `fukuii.network.discovery.discovery-enabled = false`, which saves CPU on a
node that is busy importing blocks. With `reuse-known-nodes = true` (the default) the node still dials its
bootstrap and previously known nodes, so keep that set, or the node has no peer candidates.

What to expect: Amsterdam has been active since epoch 1536, about seven days after genesis, so the chain head is
Amsterdam territory and a checkpoint-synced CL calls `engine_newPayloadV5` / `engine_forkchoiceUpdatedV4`
immediately. fukuii serves both and executes Amsterdam blocks under the rules in the
[status table](#fukuii-implementation-status). Block gas limits reach 200M, so block import on slow disks is the
usual bottleneck.

## Release 0.9.0

0.9.0 is the Glamsterdam release: [release notes](https://github.com/chippr-robotics/fukuii/blob/main/docs/releases/0.9.0.md).
Every Sepolia node must run it before 2026-10-06 13:53:36 UTC. A fukuii Sepolia node running a build without
Amsterdam support stops following the chain at the first Amsterdam block: it cannot validate that block, and its
fork id no longer matches its peers'.
