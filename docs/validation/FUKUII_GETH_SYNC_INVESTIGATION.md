# Fukuii ↔ Core-Geth Sync Investigation (Dec 15, 2025)

## Goal
Use the existing `fukuii-geth` docker-compose environment with a single Fukuii validator to understand why Core-Geth peers fail to sync with Fukuii on the Gorgoroth network.

## Reproduction Steps
1. Start only the first Fukuii node and one Core-Geth node:
   ```bash
   cd ops/gorgoroth
   docker compose -f docker-compose-fukuii-geth.yml up -d fukuii-node1 geth-node1
   ```
2. Inspect the stack:
   ```bash
   docker compose -f docker-compose-fukuii-geth.yml ps
   ```
3. Capture logs:
   ```bash
   docker logs --tail 200 gorgoroth-geth-node1
   docker logs --tail 200 gorgoroth-fukuii-node1
   docker exec gorgoroth-fukuii-node1 cat /app/data/static-nodes.json
   ```

> Cleanup: `docker compose -f docker-compose-fukuii-geth.yml down`

## Observations

### 1. Core-Geth nodes crash-loop on startup
`docker logs gorgoroth-geth-node1` prints the CLI help text and exits with:
```
flag provided but not defined: -txpool.disable
```
`docker compose ps` shows `gorgoroth-geth-node1` restarting while `gorgoroth-fukuii-node1` is healthy.

**Cause:** The compose file passes `--txpool.disable=blobs`, but the `etclabscore/core-geth:latest` image does not implement this flag. Core-Geth immediately exits and never serves peers.

### 2. Fukuii repeatedly fails to connect to 172.25.0.21
`docker logs gorgoroth-fukuii-node1` while the geth container is crash-looping:
```
2025-12-15 23:19:32,991 ERROR [c.c.e.n.rlpx.RLPxConnectionHandler] - [Stopping Connection] TCP connection to enode://5abcb5...d2af6@172.25.0.21:30303 failed
```
This confirms Fukuii attempts to dial `geth-node1` (172.25.0.21) but the peer drops because the process is repeatedly restarting from the invalid flag.

### 3. Even running Core-Geth nodes cannot discover Fukuii
When bringing up another Core-Geth instance (`gorgoroth-geth-node2`), logs show:
```
INFO  Looking for peers             peercount=1 tried=0 static=0
```
`docker exec gorgoroth-fukuii-node1 cat /app/data/static-nodes.json` returns `[]`.

**Cause:** Both stacks disable discovery (`--nodiscover` for Core-Geth, `discovery-enabled = false` for Fukuii). Static peer lists are empty, so no connections are ever attempted unless someone manually syncs enode lists (and the helper script currently only targets Fukuii containers).

## Root Causes
1. **Invalid Core-Geth CLI flag** — `docker-compose-fukuii-geth.yml` adds `--txpool.disable=blobs`, which is not recognized by Core-Geth and causes the process to crash-loop. No Core-Geth node is available to serve the Fukuii peer, so sync never begins.
2. **Missing cross-client static peers** — Even when a Core-Geth node stays up (e.g., `geth-node2` without the invalid flag), discovery is disabled on both sides and every `static-nodes.json` file under `ops/gorgoroth/conf/node*/` is empty. Without enodes injected, neither implementation knows how to dial the other.

## Recommendations
1. **Fix Core-Geth startup**
   - Remove the `--txpool.disable=blobs` flag (or conditionally gate it to go-ethereum images that support it).
   - Alternatively, switch the Core-Geth image to a version that implements the flag (confirm via `geth --help`).
2. **Publish static peers between stacks**
   - `ops/tools/fukuii-cli.sh sync-static-nodes` now sweeps both Fukuii and Core-Geth containers, wiring every follower to every other client and writing the appropriate `static-nodes.json`/`trusted-nodes.json` files before restarting containers.
   - If you need to debug manually, you can still copy the enode URLs returned by `docker exec gorgoroth-fukuii-node1 fukuii --enode` into the Core-Geth containers via `geth attach --exec 'admin.addPeer(...)'`, and vice versa.
3. **Add validation**
   - Add a preflight check (shell script or CI) that runs `docker-compose config` and verifies every `geth` command-line flag is supported by the current image.
   - Use `fukuii-cli smoke-test fukuii-geth` after `sync-static-nodes` to make sure both clients report peers and matching block heights before launching long-range experiments.

Addressing the CLI flag regression and seeding static peers should unblock Fukuii ↔ Core-Geth sync experiments in the `fukuii-geth` topology.
