# Contract: follower JSON-RPC behaviour (`replica`, `archive`)

**Owner**: `conduit`. **Depends on**: 023 DC-9 role error and FR-010 forwarding (023 T-004), 023 FR-004 health shape
(023 T-005), 018 FR-027 bounded executors for the `archive` heavy methods (phase D).
**Status**: Planned with spec 026 (2026-10-31).

Codes and payloads: [data-model.md §7](../data-model.md). Resolver design: [research.md R-6](../research.md).

---

## 1. Method matrix

| Method | `replica` | `archive` | Block parameter(s) pinned | Window check |
|---|---|---|---|---|
| `eth_getBalance` | served | served | `block` | yes (`replica`) |
| `eth_getCode` | served | served | `block` | yes |
| `eth_getStorageAt` | served | served | `block` | yes |
| `eth_getTransactionCount` | served | served | `block` | yes |
| `eth_getProof` | served | served | `block` | yes |
| `eth_getBlockByNumber` / `eth_getBlockByHash` (hashes or full transactions) | served (§6) | served | the block itself | yes |
| `eth_getTransactionReceipt` | served (§6) | served | the receipt's block | yes |
| `eth_getTransactionByHash`, `eth_getTransactionByBlockHashAndIndex`, `eth_getTransactionByBlockNumberAndIndex` | served (§6) | served | the transaction's block | yes |
| `eth_getLogs` | served for any range **inside** the window | served, any range | `fromBlock`, `toBlock`, or `blockHash` | both edges (`replica`) |
| `eth_call`, `eth_estimateGas`, `eth_simulateV1` | `-32020 not served by this role` | served at any block on the FR-027 executor | `block` | n/a |
| `debug_trace*` | `-32020` | served at any block on the FR-027 executor | `block`/tx's block | n/a |
| `debug_executionWitness` | `-32020` (017 row 1: within window, **later**) | served at any N (017 row 1, phase D) | `block` | n/a |
| `eth_blockNumber`, `eth_chainId`, `net_version`, `web3_clientVersion`, `eth_syncing` | served (`eth_syncing` reports the follower's lag) | served | — | — |
| `eth_sendRawTransaction`, `eth_sendTransaction` | forwarded to `ingest` (023 FR-010) or `-32020` | same | — | — |
| `eth_newFilter`, `eth_subscribe` (`newHeads`, `logs`) | served from applied blocks | served | — | the subscription emits only applied blocks |
| `eth_getBlockReceipts`, `eth_feeHistory`, `eth_getUncle*` | served | served | `block` | yes |
| `eth_getBlockTransactionCountByNumber`, `eth_getBlockTransactionCountByHash` | served (§6) | served | `block` | yes |
| `miner_*`, `engine_*`, `admin_addPeer`, `admin_peers`, `net_peerCount`, `txpool_*`, `eth_mining`, `eth_hashrate`, `personal_*`, `qa_*`, `test_*`, `testing_*` | `-32020` | `-32020` | — | — |

"Served" means answered at the resolved block's **verified** root only. A method not listed returns `-32020`.

## 2. Block-parameter resolution (DC-6, FR-010)

For every request the follower resolves each block parameter **once**, in this order:

1. `pending` → `-32020` (never resolved; DC-6).
2. `latest` → `follow.appliedHead`. `safe` and `finalized` → `follow.finalized` (ETC: genesis, per 025 DC-5; a
   `replica` then returns `-32023` because genesis is outside its window; this is correct, not a bug). `earliest` →
   block 0, then the window check.
3. A number → the canonical hash at that number as the follower wrote it (`BlockNumberMappingStorage`); above
   `appliedHead` → `-32025 block not applied`.
4. A hash → the header; a hash the follower never applied, or one it retracted (header kept by hash, no number→hash
   mapping) → `-32025`.
5. **Window check** (`replica` only): `number < follow.windowLow` → `-32023 outside retention window` with
   `data.retentionWindow = {fromBlock, toBlock}` so the router can re-pin without a second call.
6. **Halt check** (first, before 1): `follow.halt` set → `-32024 follower halted` for every method in §1 except the
   informational row (`eth_chainId`, `web3_clientVersion`, `eth_syncing`, `eth_blockNumber`, which report the halted
   head).

The resolved `(number, hash)` is fixed for the whole request; a head advance during the request does not change the
answer. Resolution is implemented by `PinnedBlockResolver` (replacing `ResolveBlock.resolveBlock`,
`jsonrpc/ResolveBlock.scala:27-56`, and `EthProofService`'s private resolver, `EthProofService.scala:212-237`, on
follower roles).

## 3. Echo of the pinned block

- Results that already carry `blockNumber`/`blockHash` (blocks, receipts, logs) are unchanged; those fields are the
  echo.
- **Default (HTTP):** every follower response (including errors) carries the header
  `Fukuii-Pinned-Block: <number>:<hash>` when a block was resolved, controlled by `fukuii.follow.rpc.pin-header`
  (default `true`). The body stays strict JSON-RPC 2.0.
- **Opt-in member:** with `fukuii.follow.rpc.pin-member = true` (default `false`), scalar results (`eth_getBalance`,
  `eth_getCode`, `eth_getStorageAt`, `eth_getTransactionCount`, `eth_getProof`, `eth_call`, `eth_estimateGas`) also get
  a JSON-RPC response **extension member** (outside JSON-RPC 2.0; strict clients may reject it):

  ```json
  {"jsonrpc": "2.0", "id": 1, "result": "0x1b", "pinnedBlock": {"number": "0xbc614e", "hash": "0x9f…"}}
  ```

- WS and IPC have no headers, so there the member is the only echo and stays opt-in: without `pin-member` a WS/IPC
  client gets no echo for scalar results and must pass explicit block numbers or hashes.
- Batch requests: with the member on, each element carries its own `pinnedBlock`; the header names the first resolved
  block.
- `ingest` and `sim` responses do not change (header and member are emitted only when `InstanceConfig.role` is a
  follower).

## 4. `eth_getLogs` on a follower

- `blockHash` form: resolve the hash (§2.4), window-check it; answer from the stored receipts of that block.
- Range form: resolve `fromBlock` and `toBlock` (defaults `latest`/`latest` on a follower, not `0`/`latest` as
  `EthFilterService.getLogs` does today at `EthFilterService.scala:113-114`), require `from ≤ to ≤ appliedHead`,
  and on a `replica` require `from ≥ windowLow`; otherwise `-32023` with the window in `data`. There is no implicit
  clamping: a range that straddles the window edge is an error, never a partial answer (DC-3 "never a guess").
- No per-request range cap beyond the window on `replica`; on `archive` the 018 FR-027 executor bounds the work and
  rejects with `-32026 executor saturated` when its queue is full (DC-8 of 023).
- Long-range log queries are the indexer's (023 DC-2); Orbita routes them there.

## 5. Readiness and the admin surface

Chain RPC never reports readiness; the 023 FR-009 admin port serves `GET /healthcheck` with the data-model §8 shape.
A follower that is not ready (lag above the bound, or `PersistentGap`) still answers chain RPC at its applied head
(the state is verified; the router decides whether stale is acceptable); a **halted** follower refuses (§2.6).

## 6. Block bodies and transaction lookups

The 025 block record carries the header, the body (`transactions`, `uncles`, `withdrawals`), receipts with logs and
the state diff (025 DC-2; resolves plan.md Cross-spec note 1). The follower writes `BlockBodiesStorage` and
`TransactionMappingStorage` in the per-block commit batch (data-model.md §1), so these are served on both roles, inside
the window on a `replica`:

- `eth_getBlockByNumber/Hash` with transaction hashes or `fullTransactions = true`, with `uncles` and (post-Shanghai)
  `withdrawals`.
- `eth_getTransactionReceipt(txHash)`: `TransactionMappingStorage` gives (block, index); the receipt comes from
  `ReceiptStorage`. A hash whose block is below `windowLow` on a `replica`, or never applied, is not found
  (`null`, as today); a block-addressed lookup below the window is `-32023`.
- `eth_getTransactionByHash`, `eth_getTransactionByBlockHashAndIndex`, `eth_getTransactionByBlockNumberAndIndex`.
- `eth_getBlockTransactionCountByNumber/Hash`.

On a retraction the retracted block's mappings are removed in the backward batch, so a transaction of an abandoned
block is not found by hash. The `replica` window delete removes bodies and mappings of blocks below `windowLow` with
the record copy.

## 7. Transaction ingress

Per 023 FR-010: with `fukuii.follow.ingest-endpoint` configured (023's key), `eth_sendRawTransaction` is forwarded
unchanged with a per-process rate cap and the `ingest` result returned verbatim; without it, `-32020`. 026 adds nothing
beyond wiring the follower's `JsonRpcController` to 023's forwarder.

## 8. Tests that pin this contract

- `PinnedBlockResolverSpec`: every tag, number and hash case in §2; halt-first ordering; window edges
  inclusive/exclusive
  (`windowLow` is served; `windowLow − 1` is `-32023`).
- `FollowerRpcMatrixSpec`: §1 matrix, one request per method per role, asserting served or the exact error code.
- `PinnedBlockEchoSpec`: §3 header on HTTP by default with no member in the body; member present only with
  `pin-member = true`; WS carries the member only when opted in and never a header; `ingest` responses byte-identical
  to today's fixtures (`JsonRpcControllerSpec` fixtures reused).
- `FollowerGetLogsSpec`: §4 edge cases including straddling ranges and `blockHash` of a retracted block.
