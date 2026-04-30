# Erigon SNAP Sync Reference

**Source:** `/media/dev/2tb/dev/erigon`
**Language:** Go
**Role:** Secondary reference — different architectural approach (stages pipeline)
**Chain:** ETH mainnet primary (PoS post-merge). No ETC support.

> **PoW Warning:** Erigon dropped PoW/ETC support. Its architecture is fundamentally
> different from Fukuii (stage-based pipeline vs actor model). Use for specific
> protocol mechanics questions only. Do NOT copy architectural patterns.

---

## Key Difference from geth/Fukuii

Erigon uses a **staged sync pipeline** (not a state machine with actors). Each sync
stage is a discrete step. This is a fundamentally different architecture — Fukuii
uses Pekko actors. Borrow protocol-level details, not architecture.

---

## SNAP Protocol Location

```
p2p/protocols/eth/       # ETH protocol (blocks, transactions)
# Erigon's "SNAP" is often called "downloader" — uses torrents for snapshots
# Not a 1:1 match with the snap/1 protocol from geth
```

> **Note:** Erigon's primary sync mechanism uses **snapshot torrent downloads** (not
> live SNAP protocol peer-to-peer). Their "snapshots" are pre-built Bittorrent archives.
> This is NOT the same as the `snap/1` protocol we implement.
>
> For `snap/1` protocol reference (GetAccountRange etc.), prefer go-ethereum or Besu.
> Erigon is primarily useful for understanding state trie optimization patterns.

---

## When to Reference Erigon

- State representation and storage optimization ideas
- MPT flat storage (account key → value) patterns
- Parallel trie construction approaches
- Stage-based progress tracking (concept only, not architecture)

## When NOT to Reference Erigon

- snap/1 protocol specifics
- PoW chain sync behavior
- Actor/concurrent dispatch patterns
- ETC-specific anything
