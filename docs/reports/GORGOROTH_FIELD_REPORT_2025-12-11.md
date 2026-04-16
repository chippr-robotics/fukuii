# Gorgoroth Six-Node Field Report – 2025-12-11

## Summary
We validated the December 2025 release (ghcr.io/chippr-robotics/fukuii:latest, build 1097) on the internal **Gorgoroth** six-node topology. Two PoW miners (nodes 1 & 2) were brought up sequentially to ensure fresh DAG generation, followed by four follower nodes (3–6) that synchronized from the miners. All followers were observed requesting both block headers and bodies, RPC block heights converged across exposed ports, and the stack was shut down cleanly afterward.

## Environment
- Location: `/chipprbots/blockchain/fukuii/ops/gorgoroth`
- Compose file: `docker-compose-6nodes.yml`
- Container image: `ghcr.io/chippr-robotics/fukuii:latest` (digest `sha256:96c2fe9…`)
- Network layout: miners (node1 @ 8545/8546, node2 @ 8547/8548) plus four validators/watchers (nodes3–6)

## Procedure & Evidence
1. **Network reset**
   - Ran `docker compose -f docker-compose-6nodes.yml down` to clear prior state.
2. **Node1 DAG + mining**
   - Started `fukuii-node1` alone (`docker compose … up -d fukuii-node1`).
   - Monitored `docker logs gorgoroth-fukuii-node1` until `EthashDAGManager` progressed past 0–3% and mining logs (`PoWMiningCoordinator`) appeared.
3. **Node2 DAG + mining**
   - After node1 produced blocks, started `fukuii-node2`.
   - Confirmed DAG generation started at 0% and completed; node2 began serving headers to node1 peers, indicating it was contributing blocks.
4. **Follower bring-up (nodes5 & 6)**
   - Started nodes 5 and 6; logs showed ETH68 handshakes with node1 (`PeerId b6b1…36aa`) and block fetches, e.g.
     - `00:39:41` – `PEER_REQUEST_SUCCESS ... respType=BlockBodies` (node6) with 288 ms latency.
5. **Follower bring-up (nodes3 & 4)**
   - Started nodes 3 and 4 once miners stable. Each node completed fork-ID validation and began streaming data from node1/node2:
     - Node3 `00:48:50` – `GetBlockBodies` request to node1 returned in 84 ms.
     - Node4 `00:48:58` – `GetBlockBodies` request to node1 returned in 115 ms.
6. **Follower health verification**
   - Inspected log tails for nodes3–6 to ensure sustained `GetBlockHeaders` / `GetBlockBodies` cycles with non-zero counts (e.g., node3 receiving block 563, node5 receiving batches of 351 headers, etc.).
7. **RPC height parity**
   - Executed `eth_blockNumber` against ports `8546, 8548, 8550, 8552, 8554` (node1–node5 JSON-RPC endpoints). All responses matched node1’s hex height (~0x230–0x240 range) during the observation window, confirming convergence.
8. **Orderly shutdown**
   - After validation, ran `docker compose -f docker-compose-6nodes.yml down` to stop all containers; `docker ps` confirmed zero `gorgoroth-fukuii-*` processes.

## Observations
- Sequential miner bring-up avoided DAG contention and stabilized mining within ~5 minutes per node.
- Followers immediately consumed headers/bodies from node1 once online, with request/response latencies under 300 ms inside the bridge network.
- No `Received unrequested headers` loops or sync stalls observed during this run.
- Occasional log WARNs about dead letters (`pekko.log-dead-letters`) appeared while fetchers exited; these are known benign during sync ramp-up.

## Conclusion & Follow-Ups
- The six-node stack successfully validated PoW mining plus follower synchronization for release 1097.
- Next steps (optional):
  - Capture Grafana dashboards under `ops/gorgoroth/grafana` for archival.
  - Re-run the `eth_blockNumber` sweep after longer runtimes to monitor drift.
  - Automate this procedure via `ops/tools/fukuii-cli.sh` for repeated soak tests.

_All containers have been shut down; environment is ready for the next test cycle._
