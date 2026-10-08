# 012 - Remember good snap peers

## Problem
After a restart the three geth peers that carried ~80% of storage traffic (83719e@34.62.15.50, dfd16d@34.72.230.69,
c00917@34.55.116.156) never reconnected on their own; re-adding one by hand with `net_connectToPeer` raised
storage throughput ~10x (48k to 625k tasks/h). Discovery and known-nodes give no preference to proven servers.

## Behaviour
- Peers that answer SNAP requests with data (non-empty AccountRange / StorageRanges / ByteCodes / TrieNodes) are
  scored: weight = 1 per answered request + 1 per 64 KiB. The score halves every `half-life`.
- The best `max-entries` (16) are persisted in `<datadir>/snap-good-peers.v1` with their enode URI.
- At startup they are dialled first (before the discovery scan) as ordinary, non-explicit dials, so wrong-network
  exclusions and IP blacklists are respected (skipped, not counted). Unconnected ones are re-dialled every
  `redial-interval`.
- Not permanent (unlike maintained peers): each dial attempt increments `failedDials`; a handshake resets it; past
  `max-failed-dials` the entry is dropped. Entries unserved for `max-age` are pruned.
- Only outbound peers are recorded (an inbound remote port is ephemeral, so its URI would be undialable).

## File format
Text, versioned. Line 1 `fukuii-snap-good-peers v1`; then `nodeIdHex<TAB>host<TAB>port<TAB>score<TAB>lastServedMs<TAB>failedDials`.
Missing / unreadable / wrong-version file loads as empty; malformed lines are skipped; writes are atomic
(temp + rename) and failures are logged, never thrown. Bounded at load and on every insert.

## Design
- `network/SnapGoodPeers.scala`: pure bounded store + load/save + `SnapGoodPeersConfig`.
- `NetworkPeerManagerActor`: counts data-bearing SNAP responses per peer, flushes every 60 s as
  `PeerManagerActor.SnapServedReportCmd`.
- `PeerManagerActor`: owns the store; records on report, resets on handshake, dials via `RedialSnapGoodPeers`.
- SnapPeerHealth (#1491) is per-coordinator state and not exposed to the network layer; instead only data-bearing
  responses score, so a peer that times out earns nothing, and decay/`max-age` retire stale entries.

## Config (`network.snap-good-peers`)
`enabled = true`, `max-entries = 16`, `half-life = 12.hours`, `max-failed-dials = 10`, `max-age = 7.days`,
`redial-interval = 5.minutes`, `file = "snap-good-peers.v1"`.

## ETC impact
Network-layer only; applies identically on ETC/Mordor when snap syncing. No consensus code touched.

## Tests
`SnapGoodPeersSpec` (ranking, decay, bound, failed-dial drop/reset, staleness, round-trip, corrupt/missing file,
unwritable file); `PeerManagerSpec` (startup dial, persistence after handshake and report, wrong-network respected);
`NetworkPeerManagerSpec` (flush reports only data-bearing responses).
