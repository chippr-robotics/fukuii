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

**fukuii target release: 0.9.0.** Implementation is tracked in
[#1409](https://github.com/chippr-robotics/fukuii/issues/1409) (spec `specs/009-amsterdam-fork-support/`).
Nothing on this page is a claim that Glamsterdam is supported; the [status table](#fukuii-implementation-status)
says what exists today.

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

As of 2026-09-24, on the spec 009 branch ([#1408](https://github.com/chippr-robotics/fukuii/pull/1408)). "Implemented"
means the rule exists in code with unit coverage, not that it has passed hive or followed Platåberget; those are
0.9.0 release gates.

**Execution layer** — fukuii's responsibility:

| EIP | Title | fukuii |
|---|---|---|
| [2780](https://eips.ethereum.org/EIPS/eip-2780) | Resource-based intrinsic transaction gas | Implemented |
| [7708](https://eips.ethereum.org/EIPS/eip-7708) | ETH transfers emit a log | Implemented |
| [7778](https://eips.ethereum.org/EIPS/eip-7778) | Block gas accounting without refunds | Implemented |
| [7843](https://eips.ethereum.org/EIPS/eip-7843) | SLOTNUM opcode | Partial — header `slotNumber` only; no `SLOTNUM` (`0x4b`) opcode |
| [7928](https://eips.ethereum.org/EIPS/eip-7928) | Block-level access lists | Partial — header `blockAccessListHash` only; no BAL construction or validation |
| [7954](https://eips.ethereum.org/EIPS/eip-7954) | Increase maximum contract size | Implemented |
| [7976](https://eips.ethereum.org/EIPS/eip-7976) | Increase calldata floor cost | **Not implemented** — the floor still uses the pre-Amsterdam token cost |
| [7981](https://eips.ethereum.org/EIPS/eip-7981) | Increase access list cost | **Not implemented** |
| [7997](https://eips.ethereum.org/EIPS/eip-7997) | Deterministic factory contract | No client code: the EIP forbids checking for the contract at the fork; networks provide it (Platåberget has it in genesis) |
| [8024](https://eips.ethereum.org/EIPS/eip-8024) | Backward-compatible SWAPN, DUPN, EXCHANGE | **Not implemented** — no opcodes `0xe6`–`0xe8` |
| [8037](https://eips.ethereum.org/EIPS/eip-8037) | State creation gas cost increase | Implemented |
| [8038](https://eips.ethereum.org/EIPS/eip-8038) | State-access gas cost update | Implemented |
| [8246](https://eips.ethereum.org/EIPS/eip-8246) | Remove SELFDESTRUCT burn | **Not implemented** — same-transaction SELFDESTRUCT still burns |
| [8282](https://eips.ethereum.org/EIPS/eip-8282) | Builder execution requests | Implemented in execution; `eth_config` omits the `BUILDER_*` system contracts |

**Consensus layer** — implemented by the paired CL client, no fukuii code:
[7688](https://eips.ethereum.org/EIPS/eip-7688) (forward-compatible consensus data structures),
[7732](https://eips.ethereum.org/EIPS/eip-7732) (enshrined proposer-builder separation — its EL-facing surface is the
[Engine API](#engine-api) below), [8045](https://eips.ethereum.org/EIPS/eip-8045) (exclude slashed validators from
proposing), [8061](https://eips.ethereum.org/EIPS/eip-8061) (increase exit and consolidation churn).

**Networking:**

| EIP | Protocol | fukuii |
|---|---|---|
| [7975](https://eips.ethereum.org/EIPS/eip-7975) | eth/70 — partial block receipt lists | Partial — `eth/70` capability exists, opt-in |
| [8159](https://eips.ethereum.org/EIPS/eip-8159) | eth/71 — block access list exchange | **Not implemented** |
| [8070](https://eips.ethereum.org/EIPS/eip-8070) | eth/72 — sparse blobpool | **Not implemented** |
| [8189](https://eips.ethereum.org/EIPS/eip-8189) | snap/2 — BAL-based state healing | **Not implemented** (fukuii speaks snap/1) |
| [8136](https://eips.ethereum.org/EIPS/eip-8136) | Cell-level deltas for data column broadcast | CL only |

Informational: [7904](https://eips.ethereum.org/EIPS/eip-7904) (compute gas cost analysis),
[8261](https://eips.ethereum.org/EIPS/eip-8261) (gas limit schedule).

## Engine API

From [execution-apis `src/engine/amsterdam.md`](https://github.com/ethereum/execution-apis/blob/main/src/engine/amsterdam.md):

| Method / structure | Change | fukuii |
|---|---|---|
| `ExecutionPayloadV4` | `ExecutionPayloadV3` + `blockAccessList` (RLP, EIP-7928) + `slotNumber` (EIP-7843) | **Not implemented** |
| `PayloadAttributesV4` | `PayloadAttributesV3` + `slotNumber` + `targetGasLimit` | **Not implemented** |
| `engine_newPayloadV5` | Takes `ExecutionPayloadV4`; missing `blockAccessList` → `-32602`; undecodable BAL → `INVALID` | **Not implemented** |
| `engine_getPayloadV6` | Returns `ExecutionPayloadV4` | **Not implemented** |
| `engine_forkchoiceUpdatedV4` | Takes `PayloadAttributesV4` | **Not implemented** |
| `engine_getPayloadBodiesByHashV2` / `ByRangeV2` | `ExecutionPayloadBodyV2` adds `blockAccessList` (`null` pre-Amsterdam or pruned) | **Not implemented** |
| `engine_getBlobsV4` | Returns blob cells and proofs, partial responses allowed | **Not implemented** |

`engine_newPayloadV4`, `engine_getPayloadV5` and `engine_forkchoiceUpdatedV3` must reject Amsterdam
timestamps with `-38005 Unsupported fork`; fukuii's guard for this is still a commented-out TODO in
`EngineApiController`, and its payload builder still produces a Prague-shaped header at Amsterdam timestamps.

Until these land, a Glamsterdam CL cannot drive fukuii across the Amsterdam boundary: an unknown `engine_*`
method returns "method not found".

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
   Engine API in your operator config — it is off by default:

    ```hocon
    fukuii.network.engine-api {
      enabled = true
      port = 8551
      jwt-secret-path = "/path/to/jwt.hex"
    }
    ```

2. **Execution client.** `fukuii plataberget` — loads the shipped genesis and the ethpandaops EL bootnodes.
   Confirm block 0's hash matches the table above before going further.
3. **Consensus client.** Run any CL from `ethpandaops/<client>:glamsterdam-devnet-8`, with the devnet-8
   `config.yaml` / `genesis.ssz` / `bootstrap_nodes.txt`, checkpoint sync from the URL above, and its execution
   endpoint pointed at `http://<fukuii-host>:8551` with the same `jwt.hex`.
4. **Check the handshake.** Peers should accept the fork id `0x05842a50`; anything else means the fork schedule
   or genesis is wrong.

What to expect: Amsterdam has been active since epoch 1536, about seven days after genesis, so the chain head is
Amsterdam territory. fukuii can follow Platåberget only as far as its Amsterdam implementation allows — the
[status table](#fukuii-implementation-status) is the honest guide. In particular, a checkpoint-synced CL starts
past the fork and calls `engine_newPayloadV5` / `engine_forkchoiceUpdatedV4` immediately; until those exist,
fukuii answers "method not found". Today the useful checks are the offline ones — genesis hash, fork id, peering
and the `Status` handshake. Pre-Amsterdam blocks exercise genesis, Osaka and BPO rules; the first Amsterdam block
exercises everything in #1409. Block gas limits reach 200M.

## Release 0.9.0

0.9.0 is the Glamsterdam release. Its task list — EIP coverage, Engine API, networking, hive gates, the
Platåberget soak and the Sepolia activation — lives in the Glamsterdam release issue, which links back here.
A fukuii Sepolia node running any build without Amsterdam support stops following the chain at the first
Amsterdam block on 2026-10-06: it cannot validate that block, and its fork id no longer matches its peers'.
