# core-geth SNAP Sync Reference

**Source:** `/media/dev/2tb/dev/core-geth`
**Language:** Go
**Role:** Primary ETC production client. THE reference for ETC/Mordor network config.
**Chain:** ETC mainnet (PoW/Ethash) + Mordor testnet (PoW/Ethash). No PoS.

> **Critical:** core-geth is the **only functional production PoW client** with ETC's
> actual network configuration. For any question about ETC chain behavior, fork blocks,
> genesis hashes, or network IDs — core-geth is the authoritative source.
> SNAP sync code itself is largely identical to go-ethereum, but with 6 serving
> improvements and a CVE fix that geth does not have.

---

## Key Files

```
eth/protocols/snap/
├── sync.go          # Syncer — IDENTICAL to go-ethereum (see GETH_SNAP.md)
├── handler.go       # SNAP serving — 6 improvements over go-ethereum
├── protocol.go      # Wire protocol — IDENTICAL to go-ethereum
├── peer.go          # Peer abstraction — IDENTICAL to go-ethereum
├── gentrie.go       # Path-mode trie — IDENTICAL to go-ethereum
├── msgvalidate.go   # CVE-2026-26313 message validation — NOT in go-ethereum
└── metrics.go       # Enhanced metrics

params/
├── config_classic.go   # ETC mainnet: ChainID=61, NetworkID=1, fork blocks
└── config_mordor.go    # Mordor testnet: ChainID=63, NetworkID=7, fork blocks
```

---

## What is Identical to go-ethereum

- `sync.go` — the entire SNAP syncer state machine
- `protocol.go` — all 8 message types, packet structs, wire encoding
- `peer.go` — peer request methods
- `gentrie.go` — trie construction during sync
- All concurrency limits (16 account tasks, 16 storage chunks)
- All request/response validation

SNAP is **chain-agnostic**. Chain identity is enforced at the ETH protocol layer
(handshake, chain ID negotiation), not inside SNAP sync code.

---

## ETC Network Configuration

### ETC Mainnet (ChainID 61)
`params/config_classic.go`:
- `ChainID: big.NewInt(61)`, `NetworkID: 1`
- Ethash proof of work (not Clique, not PoS)
- ETC-specific fork blocks: ECIP-1017 (monetary policy), ECIP-1010 (difficulty fix),
  ECIP-1099 (ETChash), Spiral (EIP-1559), Mordor activation for testnet
- **Genesis hash:** defined in `genesis_alloc_classic.go`

### Mordor Testnet (ChainID 63)
`params/config_mordor.go`:
- `ChainID: big.NewInt(63)`, `NetworkID: 7`
- Same Ethash PoW with testnet genesis

### Why This Matters for Fukuii SNAP Sync

- Pivot block selection uses PoW best-block consensus (not beacon finality)
- Bootstrap checkpoints reference ETC-specific block heights
- Fork ID calculation uses ETC fork blocks, not ETH fork blocks
- ETChash (ECIP-1099) modified DAG algorithm — matters for block validation, not SNAP

---

## 6 SNAP Serving Improvements (Not in go-ethereum)

These are critical for Fukuii as both a client and a server.

### 1. Snapshot Readiness Check
`handler.go` — `isSnapServingReady()` function

**Problem:** After snap sync completes, snapshots rebuild. During generation,
`AccountIterator` returns `ErrNotConstructed` → handlers returned nil silently.
Peers hang until timeout.

**Fix:** Check `snapshot.Generating()` before serving. If not ready, return empty
response immediately so peers can try other nodes.
- Rate-limited warning logged once per 60s when unavailable

### 2. Time Limits on ALL Handlers
`handler.go` — `maxSnapServingTime` constant, applied to all 4 handlers

**Problem:** `ServiceGetAccountRangeQuery`, `ServiceGetStorageRangesQuery`, and
`ServiceGetByteCodesQuery` could iterate indefinitely, starving ETH protocol
responses (GetBlockHeaders etc.) via write token serialization.

**Fix:** 2-second time limit on all four serving handlers. Previously only
`ServiceGetTrieNodesQuery` had this.

### 3. Iterator Error Checking
`handler.go` — `it.Error()` check after `it.Release()`

**Problem:** Iterator errors after `Release()` were silently lost, causing partial
responses with no diagnostic signal.

**Fix:** Check `it.Error()` after release in `ServiceGetAccountRangeQuery()` and
`ServiceGetStorageRangesQuery()`. Log warning if error occurred.

### 4. Gosched Yielding Between Requests
`handler.go` — `runtime.Gosched()` after each snap message

**Problem:** SNAP handler ran in tight loop, monopolizing per-peer write token.
Starved ETH protocol response goroutines.

**Fix:** `runtime.Gosched()` after each snap message, yielding to Go scheduler.

### 5. Snapshot Status Reporting
Multiple files — logging improvements

**Problem:** No operator visibility into snapshot health after restart. No indication
whether snap/1 is available, generation in progress, or why requests fail.

**Fix:** Log snapshot status at blockchain init and `enableSyncedFeatures()`.
Export `Generating()` method. Upgrade snap serving failure logs Debug → Warn.

### 6. Snapshot Rebuild Safety Net
`eth/handler.go` — redundant `Rebuild()` in `enableSyncedFeatures()`

**Problem:** After snap sync, `Rebuild()` called at pivot commit but generation takes
hours. If node restarts during generation, `Rebuild()` never re-called, snapshots
stay disabled forever.

**Fix:** Redundant `Rebuild()` in `enableSyncedFeatures()` as safety net. Restarts
generation if interrupted.

---

## CVE-2026-26313: Message Validation

`msgvalidate.go` — **unique to core-geth, not in go-ethereum**

```go
const maxSnapResponseItems = 2048
var snapResponseItemLimits = map[uint64]int{
    AccountRangeMsg:  maxSnapResponseItems,
    StorageRangesMsg: maxSnapResponseItems,
    ByteCodesMsg:     maxSnapResponseItems,
    TrieNodesMsg:     maxSnapResponseItems,
}
```

Validates item count in RLP payload before full decoding — prevents DoS via oversized
responses. Early-exit counting avoids iterating malicious large payloads.

**Fukuii should implement equivalent validation.**

---

## What to Use from core-geth for Fukuii

| Aspect | Use |
|--------|-----|
| ETC/Mordor chain config (ChainID, NetworkID, fork blocks) | Yes — authoritative source |
| Genesis files and genesis hashes | Yes — authoritative |
| 6 SNAP serving improvements | Yes — implement all 6 in our SnapServer |
| CVE-2026-26313 message validation | Yes — implement in our SNAP message handler |
| PoW pivot selection (best-block consensus) | Yes — the PoW approach to use |
| Ethash/ETChash block validation approach | Yes — when validating during SNAP |
| SNAP syncer algorithm | Same as geth — see GETH_SNAP.md |

---

## Reference: ETC vs ETH Fork Divergence

For Fukuii's SNAP sync specifically, the PoW/PoS split affects:

1. **Pivot selection:** ETC uses N-128 PoW best-block; ETH uses beacon finalized block
2. **Fork ID calculation:** ETC fork IDs use different block numbers
3. **Block validation during chain download:** Ethash vs PoS validation
4. **Bootstrap checkpoints:** ETC-specific block heights (e.g., Spiral: 19,250,000)

SNAP *state download* itself is identical — state tries have the same structure on ETC
and ETH. The differences are all at the **chain sync** and **consensus** layers.
