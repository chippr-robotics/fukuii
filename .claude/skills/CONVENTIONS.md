# Shared conventions for Fukuii operations skills

Every skill in this directory assumes the contract below. Skills reference this
file instead of repeating it.

## 1. Locating the node

- **Binary / entry point**: `./bin/fukuii <network>` (e.g. `etc`, `mordor`,
  `sepolia`), or the wrapper `./fukuii.sh` → `ops/tools/fukuii-cli.sh`.
- **Data directory**: `~/.fukuii/<network>/` by default (overridable via
  `-Dfukuii.datadir=...` or config). Contains `node.key`, `keystore/`, `logs/`,
  `rocksdb/`, `knownNodes.json`, `app-state.json`.
- **Config file**: `conf/fukuii.conf` (HOCON). Network sub-configs live under
  `src/main/resources/conf/`.
- **JSON-RPC endpoint**: HTTP on the configured RPC port. The **default is
  `8546`** (`network.rpc.http.port`; WebSocket default `8552`, Engine API
  authrpc `8551`). Some older runbook examples say `8545` — **confirm the actual
  port** from config before calling. Never assume.

## 2. Calling the node

Standard JSON-RPC 2.0 over HTTP. Canonical shape:

```bash
curl -s -X POST http://localhost:<RPC_PORT> \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"<method>","params":[<args>]}'
```

The relevant namespace must be enabled in config. The shipped default is
`network.rpc.apis = "eth,web3,net,personal,fukuii,debug,qa,admin"` — note that
**`mcp` is NOT enabled by default**, so the in-process MCP tools require adding
`mcp` to that list. If a method returns "method not found", the namespace is
likely disabled — surface that, don't silently retry.

Useful read-only methods for diagnostics (safe, no state change):
`web3_clientVersion`, `net_version`, `net_listening`, `net_peerCount`,
`eth_syncing`, `eth_blockNumber`, `eth_mining`, `eth_hashrate`, `eth_coinbase`,
`admin_nodeInfo`, `admin_peers`, `admin_datadir`, `admin_listBlockedIPs`,
`miner_getStatus`. The in-process **MCP read tools** (`mcp_node_status`,
`mcp_sync_status`, `mcp_peer_list`, `mcp_blockchain_info`, `mcp_node_info`,
`mcp_mining_rpc_summary`, …) return the same data in agent-friendly form.

## 3. Guarded-write protocol (read this)

Skills are allowed to perform **write / control** operations, but must gate them.
Classify every action before running it:

- 🟢 **Read-only** — run freely (the methods in §2, log reading, `df`, status
  checks). No confirmation needed.
- 🟡 **Reversible write** — e.g. `admin_addPeer` / `admin_removePeer`,
  `admin_changeLogLevel`, `admin_maxPeers`, `admin_blockIP` / `admin_unblockIP`,
  `admin_addTrustedPeer` / `admin_removeTrustedPeer`, `miner_start` /
  `miner_stop` / `miner_setEtherbase`. **State the exact call and expected
  effect, then confirm with the operator before executing.**
- 🔴 **Irreversible / disruptive** — e.g. `admin_importChain`, restoring a
  datadir over an existing one, deleting/pruning `rocksdb/`, rotating
  `node.key`, editing config that requires a restart, anything that stops the
  node or can lose data. **Require explicit confirmation, take a backup first
  (see `fukuii-backup-restore`), and never run unprompted.**

Rules:
1. Show the operator the precise command(s) before a 🟡/🔴 action; proceed only
   on a clear go-ahead.
2. For 🔴 actions, confirm a current backup exists first.
3. Prefer the least-destructive path that achieves the goal.
4. After a write, re-run the matching read check to confirm the effect.
5. Report faithfully — if a step failed or was skipped, say so with the output.

## 4. Output contract

End each skill run with a short, structured result:
- **Verdict / outcome** (e.g. `HEALTHY` / `DEGRADED` / `ACTION REQUIRED`).
- **Evidence** — the specific values observed (block height, peer count, error
  lines), not vibes.
- **Actions taken** — every 🟡/🔴 call actually executed.
- **Recommended next steps** — exact commands, ready to run.

## 5. Network reality check

Fukuii is a **multi-network EVM client** supporting two independent chain families:

- **ETC/Mordor** (chain-ID 61/63): Proof-of-Work, Ethash, ECIP-1017 fixed-supply
  emission. Mining is real and relevant here. No PoS, no Merge, no EIP-1559
  base-fee burn. Fork dispatch: `forBlock()` (block-number based).
- **ETH/Sepolia** (chain-ID 1/11155111): Proof-of-Stake (post-Merge), Engine API
  driven, EIP-1559, EIP-4844 blobs, timestamp fork dispatch. No mining.

See `CLAUDE.md` for the authoritative code-path rules (OlympiaOpCodes vs OsakaOpCodes).
Do NOT recommend ETH-mainnet procedures for ETC networks, or ETC/PoW procedures for
ETH/Sepolia networks. Always identify the target chain before advising on consensus,
mining, fee, or fork-related operations.

## Skill authoring template

```markdown
---
name: fukuii-<verb-noun>
description: >-
  <What the skill does in one sentence>. Use when <concrete triggers / phrases
  the operator might say>. <Any important scope limit or safety note>.
---

# <Title>

## When to use
<Bullet triggers.>

## Inputs to gather first
<Network, RPC port, datadir — per CONVENTIONS §1.>

## Procedure
<Numbered steps. Mark each write 🟡/🔴 per CONVENTIONS §3. Cite exact methods/commands.>

## Decision guide
<Symptom → action table where useful.>

## Deep reference
<Link the backing docs/runbooks/*.md (Level-3 progressive disclosure).>

## Output
<Per CONVENTIONS §4.>
```

Frontmatter rules: `name` lowercase letters/numbers/hyphens, ≤64 chars, no
reserved words; `description` ≤1024 chars, says **what** and **when**.
