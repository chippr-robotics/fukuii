# 011 - Evict genesis-head crawlers

## Problem
Sepolia v0.9.1: `[SNAP-PEERS] snapCapable=9 excluded=5 served=4 ... best-block-is-genesis(maxBlock=0) x5`.
All five were `cap=ETH71 client=other(enrscout) networkId=11155111 bestHash=<Sepolia genesis> latestBlock=0`:
a crawler occupying ~5 of ~12 peer slots, starving real snap servers. They handshake validly (right network,
right genesis) so the wrong-network path (#88) never fires, and they never advance, so lagging-peer eviction
(`maxBlockNumber > 0`) skips them too.

## Behaviour
When our chain is past genesis (`appStateStorage.getBestBlockNumber() > 0` or `getEstimatedHighestBlock() > 0`,
the latter covering a node still snap-syncing from block 0), a handshaked peer with
`isAtGenesis && maxBlockNumber == 0` that has not advanced within `genesis-head-eviction-grace` is:
1. disconnected with `Disconnect.Reasons.UselessPeer`;
2. excluded from internal dialling (discovery / known-nodes) for `genesis-head-exclusion-duration`, by node ID,
   reusing `PeerManagerActor.WrongNetworkExclusions` (and its explicit-operator-dial bypass);
3. logged at INFO: `GENESIS_HEAD_EVICT: ... client=<id> ...`.

Exempt: maintained and trusted peers; every peer on a chain where we are at genesis too (private/dev nets).
"Advances" = `maxBlockNumber` rises via BlockRangeUpdate / NewBlock / headers (existing `updateMaxBlock`).

## Design
- `NetworkPeerManagerActor` (owns per-peer `PeerInfo`): `CheckGenesisHeadPeersTick` every 10 s tracks first-seen
  time per genesis-head peer and sends `PeerManagerActor.EvictGenesisHeadPeerCmd(peerId, clientId)` after the grace.
  The timer is not started when the grace is zero (disabled).
- `PeerManagerActor` applies policy (exemption, disconnect, exclusion) beside the wrong-network machinery.

## Follow-up: inbound reconnects and stale entries (v0.9.6)
Sepolia v0.9.6 logged `GENESIS_HEAD_EVICT` for the same two enrscout node IDs every ~70 s, and a third (afb622cf)
stayed in the `[SNAP-PEERS]` genesis exclusions without ever being evicted. Each of the three handshook exactly once
(06:37:47) and closed TCP within ~300 ms, before `PeerHandshakeSuccessful` had been processed. No reconnect happened.
- PeerManagerActor ghost (f9b0c644, 7a1d9390): the death-watch `PeerTerminated` overtook the handshake event (which
  takes an extra hop through the event bus), so the pending entry was removed first and the late handshake then
  promoted a dead actor. Each eviction sent `DisconnectPeer` to dead letters; the 10 s tick re-armed the 60 s grace.
- NetworkPeerManagerActor ghost (afb622cf): PeerManagerActor processed both events in order and published
  `PeerDisconnected`, but before this actor's per-peer subscription had reached the bus, so the event was lost. Its
  `EvictGenesisHeadPeerCmd` found nothing in PeerManagerActor and was dropped without a log line.

Fixes:
1. PeerManagerActor ignores a handshake whose actor it no longer tracks (`STALE_HANDSHAKE`) and publishes
   `PeerDisconnected` for it so subscribers drop it again.
2. NetworkPeerManagerActor death-watches every handshaked peer actor and removes its entry on termination, so a lost
   `PeerDisconnected` can no longer leave a stale entry. A second live connection for the same peer ID (the outbound
   an inbound-wins swap displaced, or a dropped duplicate handshake) is kept as a standby and takes over when the
   current entry's actor dies.
3. An inbound handshake from a node ID in the exclusion set (genesis-head or wrong-network), unless maintained or
   trusted, is disconnected at once with `UselessPeer` (`EXCLUDED_PEER_REJECTED`) and gets no slot or grace period.
4. PeerManagerActor remembers the handshake ID of every live actor whose handshake it processed. When a handshake it
   did not promote (excluded, TooManyPeers, AlreadyConnected) ends, it publishes `PeerDisconnected` under that ID
   unless another live connection has the node ID. Subscribers such as PendingTransactionsManager register the peer
   on the handshake event, so without this each rejected crawler redial left a permanent entry.

## Config (`network.peer`)
- `genesis-head-eviction-grace = 60.seconds` (0 disables)
- `genesis-head-exclusion-duration = 1.hour`

## ETC impact
None for peers with real heads. Applies to ETC/Mordor only to peers stuck at genesis while we are past it.
No consensus code touched.

## Tests
`NetworkPeerManagerSpec`: evicts after grace when past genesis; not before grace; not when we are at genesis;
not for a peer past genesis; a peer whose actor died is dropped without `PeerDisconnected`. `PeerManagerSpec`: evicted
peer gets UselessPeer; maintained peer exempt; an evicted node reconnecting inbound is rejected at once; a handshake
from an already-terminated actor is not registered.
