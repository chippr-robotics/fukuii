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

## Config (`network.peer`)
- `genesis-head-eviction-grace = 60.seconds` (0 disables)
- `genesis-head-exclusion-duration = 1.hour`

## ETC impact
None for peers with real heads. Applies to ETC/Mordor only to peers stuck at genesis while we are past it.
No consensus code touched.

## Tests
`NetworkPeerManagerSpec`: evicts after grace when past genesis; not before grace; not when we are at genesis;
not for a peer past genesis. `PeerManagerSpec`: evicted peer gets UselessPeer; maintained peer exempt.
