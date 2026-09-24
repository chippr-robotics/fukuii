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
[#1409](https://github.com/chippr-robotics/fukuii/issues/1409) (spec
[`specs/009-amsterdam-fork-support`](https://github.com/chippr-robotics/fukuii/tree/main/specs/009-amsterdam-fork-support)).
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

<!-- STATUS-TABLE -->

## Engine API

From [execution-apis `src/engine/amsterdam.md`](https://github.com/ethereum/execution-apis/blob/main/src/engine/amsterdam.md):

| Method / structure | Change |
|---|---|
| `ExecutionPayloadV4` | `ExecutionPayloadV3` + `blockAccessList` (RLP, EIP-7928) + `slotNumber` (EIP-7843) |
| `PayloadAttributesV4` | `PayloadAttributesV3` + `slotNumber` + `targetGasLimit` |
| `engine_newPayloadV5` | Takes `ExecutionPayloadV4`; missing `blockAccessList` → `-32602`; undecodable BAL → `INVALID` |
| `engine_getPayloadV6` | Returns `ExecutionPayloadV4` |
| `engine_forkchoiceUpdatedV4` | Takes `PayloadAttributesV4` |
| `engine_getPayloadBodiesByHashV2` / `ByRangeV2` | `ExecutionPayloadBodyV2` adds `blockAccessList` (`null` pre-Amsterdam or pruned) |
| `engine_getBlobsV4` | Returns blob cells and proofs, partial responses allowed |

`engine_newPayloadV4`, `engine_getPayloadV5` and `engine_forkchoiceUpdatedV3` must reject Amsterdam
timestamps with `-38005 Unsupported fork`.

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
[status table](#fukuii-implementation-status) is the honest guide. Pre-Amsterdam blocks exercise genesis,
Osaka and BPO rules; the first Amsterdam block exercises everything in #1409. Block gas limits reach 200M.

## Release 0.9.0

0.9.0 is the Glamsterdam release. Its task list — EIP coverage, Engine API, networking, hive gates, the
Platåberget soak and the Sepolia activation — lives in the Glamsterdam release issue, which links back here.
A fukuii Sepolia node running any build without Amsterdam support stops following the chain at the first
Amsterdam block on 2026-10-06: it cannot validate that block, and its fork id no longer matches its peers'.
