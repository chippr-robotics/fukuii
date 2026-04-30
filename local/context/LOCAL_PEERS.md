# Local Multi-Client Peer Configuration — ETC Mainnet

**Machine:** 10.0.0.32 (Intel NUC, 32GB RAM)
**Network:** ETC mainnet (chain ID 61)
**Purpose:** Three-client SNAP sync test rig — Besu serves SNAP to Fukuii; core-geth provides chain tip and discovery cross-check.

---

## Client Summary

| Client | HTTP RPC | WS RPC | P2P | Data Dir |
|--------|----------|--------|-----|----------|
| core-geth | 8545 | 8546 | 30303 | `/media/dev/2tb/data/blockchain/core-geth/classic` |
| besu | 8548 | 8549 | 30304 | `/media/dev/2tb/data/blockchain/besu/classic` |
| fukuii | 8553 | 8552 | 30305 | `/media/dev/2tb/data/blockchain/fukuii/etc` |

**Startup order:** core-geth first → Besu → Fukuii  
(Besu waits for discovery peers before serving SNAP; core-geth provides the chain tip both rely on)

---

## Run Scripts

```bash
~/.local/bin/run-geth-classic.sh    # core-geth
~/.local/bin/run-besu-classic.sh    # Besu (SNAP server, --snapsync-server-enabled)
~/.local/bin/run-fukuii-classic.sh  # Fukuii (SNAP client, -Xmx6g)
```

---

## Static Peers (Enode IDs)

Fukuii reads `${datadir}/static-nodes.json` at startup (`StdNode.loadStaticNodes()`) and sends `AddMaintainedPeer` for each entry. Both files are identical:

- `/media/dev/2tb/data/blockchain/fukuii/etc/static-nodes.json`
- `/media/dev/2tb/data/blockchain/fukuii/mordor/static-nodes.json`

```json
[
  "enode://b81f5e097871cae54b14198a69c51d6984cfa0370f79fac0bff66d73d49619c759834c36195498d067b2620317e3c5f900215725044f365d458d22df94d55d54@127.0.0.1:30303",
  "enode://235067a020f7a0191c37f18962022ee7939e11fa765136205fdc023354dec92f8ef4757aef93e4009adf122a09af68836e14efc3638179d3f47f9f9094527b50@127.0.0.1:30304"
]
```

| Enode prefix | Client | Port |
|-------------|--------|------|
| `b81f5e09...` | core-geth | 30303 |
| `235067a0...` | Besu | 30304 |

---

## How Static Peers Work in may-fields

`StdNode.loadStaticNodes()` (line ~147) runs after `startPeerManager()` in Phase 3:
1. Calls `StaticNodesLoader.load(datadir)` → reads `${datadir}/static-nodes.json`
2. Sends `PeerManagerActor.AddMaintainedPeer(uri)` for each valid enode
3. `PeerManagerActor` stores each in `maintainedPeersByNodeId: Map[String, URI]`
4. On `Terminated` (peer disconnect), schedules `ConnectToPeer(uri)` with 30s delay for maintained peers

**Log line to confirm:** `Loading 2 static peer(s) from .../etc/static-nodes.json`

---

## Known Gap: Pre-Handshake Reconnect (NOT yet in may-fields)

`april-confluence` commit `fd1f8c1db` fixed a reconnect failure mode:

> When a maintained peer's TCP connection dies **before** the ETH handshake completes (refused, max-peers on startup, dial backoff), the `Terminated` handler finds no `PeerId` in `connectedPeers`, so the maintained-peer retry loop never fires.

The fix adds `pendingConnections: Map[ActorRef, URI]` — tracks outbound TCP actors before handshake. If they die early, the pre-handshake path schedules exponential backoff retry (1s → 2s → 4s … → 30s cap).

**Symptom without the fix:** fukuii connects once, both peers refuse (at max-peers on startup), connection dies pre-handshake, and fukuii never retries. You'd see 0 peers in `admin_peers` and no retry log lines after startup.

**Workaround if needed:** Call `admin_addPeer` manually (see below) to force a reconnect attempt.

**Port priority:** This fix should be the first SNAP-adjacent commit ported to may-fields if peer connectivity stalls.

---

## Besu SNAP Server Notes

Besu runs with `--snapsync-server-enabled`, `--sync-mode=FULL`, `--data-storage-format=BONSAI`.  
BONSAI + SNAP serving requires full state; it won't serve ranges for blocks outside its `--bonsai-historical-block-limit=131072`.

Besu's `--max-peers=10` — Fukuii connects as one of those 10. If Besu is at max-peers when Fukuii starts, the TCP connection is refused before the ETH handshake, which triggers the pre-handshake reconnect gap above.

---

## Verification Commands

### Check who fukuii is peered with
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  http://127.0.0.1:8553 | python3 -m json.tool
```

### Check fukuii's own enode (to add to other clients' static-nodes)
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_nodeInfo","params":[],"id":1}' \
  http://127.0.0.1:8553 | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['enode'])"
```

### Manually force-add Besu as peer (workaround for pre-handshake gap)
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["enode://235067a020f7a0191c37f18962022ee7939e11fa765136205fdc023354dec92f8ef4757aef93e4009adf122a09af68836e14efc3638179d3f47f9f9094527b50@127.0.0.1:30304"],"id":1}' \
  http://127.0.0.1:8553
```

### Manually force-add core-geth as peer
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["enode://b81f5e097871cae54b14198a69c51d6984cfa0370f79fac0bff66d73d49619c759834c36195498d067b2620317e3c5f900215725044f365d458d22df94d55d54@127.0.0.1:30303"],"id":1}' \
  http://127.0.0.1:8553
```

### Check Besu peers
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  http://127.0.0.1:8548 | python3 -m json.tool
```

### Check core-geth peers
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_peers","params":[],"id":1}' \
  http://127.0.0.1:8545 | python3 -m json.tool
```

---

## What to Watch for on Fukuii Startup

| Log line | Meaning |
|----------|---------|
| `Loading 2 static peer(s) from .../static-nodes.json` | Static nodes loaded — AddMaintainedPeer sent |
| `Connected to peer enode://b81f5e...` | core-geth handshake OK |
| `Connected to peer enode://235067...` | Besu handshake OK |
| `Peer enode://235067... supports snap/1` | Besu confirmed as SNAP server |
| `Pivot block selected: #XXXXXXX` | SNAP sync starting |
| `0 peers after static load, no retry` | Pre-handshake gap hit — use admin_addPeer |

---

## SNAP Capability

Besu with `--snapsync-server-enabled` advertises `snap/1` in its Hello message.  
core-geth on ETC also serves SNAP (all 6 serving improvements committed in pre-olympia branch).  
Fukuii checks `supportsSnap` flag in `PeerInfo` after handshake to select SNAP-capable workers.

---

---

## fukuii → core-geth Handshake Diagnosis

### The Symptom
**fukuii→core-geth** shows a 15s reconnect loop.  
**core-geth→fukuii** (via `admin_addPeer`) works consistently.

### Why the Asymmetry Exists

When **core-geth initiates** via `admin_addPeer`, it adds fukuii as a trusted peer. Trusted peers bypass core-geth's `--maxpeers` limit — the connection always succeeds regardless of how many peers core-geth has.

When **fukuii initiates** (from static-nodes.json), it dials core-geth:30303 as an ordinary outbound peer. If core-geth is at its max-peers count (default 50, no `--maxpeers` flag in run script), core-geth sends `DiscTooManyPeers (0x04)` at the Hello stage — before the ETH Status message, before fukuii assigns a PeerId.

### The Two Retry Paths

| Path | Timing | Trigger |
|------|--------|---------|
| **Standard retry** (`connect-retry-delay = 15s`) | 15s | Connection dies *before* ETH handshake completes (no PeerId assigned) |
| **Maintained-peer reconnect** | 30s | Peer `Terminated` *with* a known PeerId (post-handshake disconnect) |

**15s confirms pre-handshake failure.** The connection died before fukuii assigned a PeerId to core-geth. The `Terminated` handler looks up the PeerId in `maintainedPeersByNodeId` — finds nothing — so the 30s maintained-peer reconnect never fires. Only the standard 15s retry path runs.

After `connect-max-retries = 2` failures (3 total attempts, 30-45s elapsed), core-geth's IP gets a `shortBlacklistDuration = 3 minutes` (TooManyPeers maps to short blacklist). After 3 min, the blacklist expires but nothing re-adds core-geth — there's no automatic reconnect from the maintained-peer set at this point. **Connection is silently lost.**

### Known Gap: Pre-Handshake Reconnect (NOT in may-fields)

**april-confluence** `fd1f8c1db` adds `pendingConnections: Map[ActorRef, URI]` to `PeerManagerActor`. Outbound TCP actors are tracked before the ETH handshake completes. When `Terminated` fires with no PeerId, the map identifies the URI and schedules exponential backoff retry (1s→2s→4s…→30s cap).

Without this fix: maintained peers that die pre-handshake never re-establish automatically. **This is the root cause of the observed loop ending in silence.**

### Key Log Lines to Watch

```
# Confirms static nodes loaded
Loading 2 static peer(s) from .../etc/static-nodes.json

# Connection attempt
Connecting to peer enode://b81f5e...

# What core-geth sends back — look for the Disconnect reason code
Disconnecting from peer ... reason=TooManyPeers        # 0x04 → shortBlacklist 3min
Disconnecting from peer ... reason=DisconnectRequested # 0x01 → shortBlacklist 3min
Disconnecting from peer ... reason=BreachOfProtocol    # 0x02 → permanent blacklist (365d)
Disconnecting from peer ... reason=IncompatibleP2pProtocol # 0x08 → permanent blacklist

# Standard retry (15s path — pre-handshake, no PeerId)
[no maintained-peer reconnect log — the 30s path is silent here]

# If/when connection SUCCEEDS post-handshake:
Connected to peer enode://b81f5e... (ETH/68 or ETH/69)
Peer enode://b81f5e... supports snap/1
```

### ETH/68 vs ETH/69 — Known Gaps in may-fields

Even after a successful handshake with core-geth, these three issues affect peer usability:

| Gap | Commit | Effect |
|-----|--------|--------|
| `BlockRangeUpdate` not in `msgCodesWithInfo` | `29d289c81` | ETH/69 peer height never updates after handshake — stale peer selection |
| `peerHasUpdatedBestBlock` gates on `maxBlockNumber > 0` | `de8e59dde` | ETH/68 peers excluded from `HandshakedPeers` responses — core-geth invisible to sync if on ETH/68 |
| `supportsSnap` not forced `true` for ETH/69 peers | `348fb213f` | ETH/69 peers excluded from SNAP dispatch (EIP-7642 requires SNAP co-protocol with ETH/69) |

**SNAP/ETH-69 code collision** (`2dc57424d`) is **already fixed** in upstream/main — `SnapProtocolOffset = 0x30`, `ethWireSizeFor(ETH69) = 0x12`. No SNAP decode corruption on ETH/69 handshakes.

### Workarounds While Diagnosing

**Force reconnect to core-geth manually:**
```bash
curl -s -X POST \
  --data '{"jsonrpc":"2.0","method":"admin_addPeer","params":["enode://b81f5e097871cae54b14198a69c51d6984cfa0370f79fac0bff66d73d49619c759834c36195498d067b2620317e3c5f900215725044f365d458d22df94d55d54@127.0.0.1:30303"],"id":1}' \
  http://127.0.0.1:8553
```

**Reduce core-geth max-peers** (add to `run-geth-classic.sh`):
```bash
--maxpeers=15   # leaves room for fukuii to connect as non-trusted peer
```

**Add fukuii as trusted peer on core-geth** (add to `run-geth-classic.sh`):
```bash
--trustedpeers="enode://<fukuii-enode>@127.0.0.1:30305"
```
Get fukuii's enode: `curl -s ... admin_nodeInfo | python3 -c "import sys,json; print(json.load(sys.stdin)['result']['enode'])"`

### Port Priority

If the 15s loop confirms `TooManyPeers`, the fix is:
1. **`fd1f8c1db` pre-handshake reconnect** — prevents silent peer loss after blacklist expiry; core-geth doesn't need config changes  
2. **`de8e59dde` peerHasUpdatedBestBlock fix** — may be needed even after connectivity is fixed if core-geth uses ETH/68

---

## Static Peer Improvement Plan (may-fields)

*Researched 2026-04-25 against go-ethereum (`p2p/dial.go`, `eth/dropper.go`) and Besu (`MaintainedPeers.java`, `PeerDenylistManager.java`, `EthPeers.java`). Four clean cherry-pickable commits planned.*

| # | Commit | Problem | Reference |
|---|--------|---------|-----------|
| 1 | `fix(peers): exempt maintained peers from inbound max-peer limit` | `PeerHandshakeSuccessful` sends `DiscTooManyPeers` to inbound maintained peers when `maxIncomingPeers` reached | Besu `EthPeers.java` — `canExceedConnectionLimits()` filter |
| 2 | `fix(peers): exempt maintained and trusted peers from incoming peer pruning` | `ConnectedPeers.canPrune` has no maintained-peer check; static inbound peer can be pruned after 30min | Both geth (`selectDoNotDrop`) and Besu (`!canExceedPeerLimits()` filter) exempt static peers |
| 3 | `fix(peers): skip blacklisting for maintained peers on disconnect` | Any disconnect (TooManyPeers → 3min, protocol violation → 365 days) blacklists the maintained peer; retry fires but hits blacklist | Besu `PeerDenylistManager.onDisconnect` — maintained peers skipped entirely |
| 4 | `fix(peers): reconnect maintained peers that fail pre-handshake TCP connection` | Pre-handshake death (no PeerId assigned) → 30s maintained-peer retry never fires → silent loss after retry exhaustion | Both clients: track outbound actors before handshake, retry on `Terminated` if no PeerId found |

### Key design choices (validated against reference clients)

- **No exponential backoff** — both geth (35s flat) and Besu (30s flat cache) use flat intervals. Keep `connect-retry-delay = 15s`.
- **Inbound exemption follows Besu** — geth uses separate "trusted" concept for inbound bypass; Besu treats maintained = can exceed limits for both directions. We follow Besu.
- **No `Peer` data model change** — pass `maintainedPeersByNodeId.keySet` as exclusion set into `ConnectedPeers.prunePeers` rather than adding `isMaintained` field to `Peer` case class.
- **Blacklist bypass is unconditional** — Besu skips denylist for maintained peers regardless of disconnect reason (even breach of protocol). We do the same.

---

## Session Log

| Date | Notes |
|------|-------|
| 2026-04-25 | Doc created. Besu confirmed running (enode verified). core-geth not running. Fukuii JAR built from may-fields (3 init commits). Pre-handshake reconnect gap (`fd1f8c1db`) identified. Three ETH/69 gaps identified (`29d289c81`, `de8e59dde`, `348fb213f`). SNAP/ETH-69 code collision already fixed in upstream/main. fukuii→core-geth 15s reconnect loop analyzed — likely `TooManyPeers` pre-handshake. |
| 2026-04-25 | Static peer improvement plan finalized. 4 commits designed. go-ethereum + Besu reference analysis complete. PROJECT_CONTEXT.md updated with development methodology, trust hierarchy, project vision. |
| 2026-04-25 | All 4 static peer commits implemented and green on may-fields: `95201c5d4` (inbound exemption), `c1438c76b` (pruning exemption), `e6711087b` (blacklist bypass), `45fed499c` (pre-handshake reconnect). 100/100 peer tests pass. |
