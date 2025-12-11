# Gorgoroth Trial Field Report – 3nodes (2025-12-11)

## Gorgoroth Trial Field Report

**Date**: 2025-12-11  
**Tester**: @dontpanic  
**Trial Type**: Gorgoroth 3nodes

---

### System Information

- **OS**: Ubuntu 24.04.3 LTS (Linux 6.x)
- **Docker Version**: 28.5.2, build ecc6942
- **Docker Compose**: v2.40.3
- **Available RAM**: ~15 GiB total (7 GiB free during test)
- **Available Disk Space**: ~25 GiB free on / (107 GiB volume)
- **Network**: Wired lab network (1 Gbps uplink)

### Test Duration

- **Start Time**: 2025-12-11 17:55 UTC
- **End Time**: 2025-12-11 18:20 UTC
- **Total Duration**: ~25 minutes (including restarts + log collection)

---

### Test Results

#### Gorgoroth Tests
- [x] **Network Connectivity**: ✅ (all nodes reported 0x2 peers after `sync-static-nodes`)
- [ ] **Block Propagation**: ⏭️ (blocked by mining being disabled in `docker-compose-3nodes.yml`)
- [ ] **Mining Compatibility**: ❌ (no miners started because `-Dfukuii.mining.mining-enabled=false` for every service)
- [ ] **Consensus Maintenance**: ⏭️ (not observable without block production)
- [ ] **Faucet Service**: ⏭️

#### Cirith Ungol Tests
- [ ] **Peer Discovery**: ⏭️
- [ ] **SNAP/Fast Sync Completion**: ⏭️
- [ ] **State Queryable After Sync**: ⏭️
- [ ] **24+ Hour Stability**: ⏭️

---

### What Worked Well

- `fukuii-cli start 3nodes` + `sync-static-nodes` reliably bootstrapped the cluster once the missing config files were created.
- Static peer synchronization automatically collected enodes, rewrote the host `static-nodes.json` files, and restarted the containers without manual edits.
- Peer counts on the HTTP RPC ports (8546/8548/8550) consistently reported 0x2 connections after the restart, confirming network formation.

### Issues Encountered

1. **Missing config mounts**: `ops/gorgoroth/conf/node1/{gorgoroth.conf,static-nodes.json}` did not exist in the repo, so Docker failed with "not a directory" until the files were created manually.
2. **RPC port mismatch**: JSON-RPC HTTP servers actually listen on 8546/8548/8550, while the walkthrough and helper scripts still reference 8545/8547/8549. Scripts such as `test-connectivity.sh` therefore detect zero nodes and skip most checks.
3. **Mining disabled by default**: Every service in `docker-compose-3nodes.yml` sets `-Dfukuii.mining.mining-enabled=false`, so Phases 2–4 of the walkthrough cannot be completed unless the operator overrides those flags and restarts the network.
4. **Volume cleanup instructions**: The walkthrough references `docker volume rm gorgoroth_node3-data`, but the real volume name is `gorgoroth_fukuii-node3-data`.

### Performance Metrics

- **Peer Count**: `net_peerCount` = `0x2` on ports 8546/8548/8550 after sync.
- **Block Heights**: node1 reported `0xe6` (carried over from previous mining runs); nodes2/3 remained at `0x0` because no new blocks were produced during this session.
- **Logs**: `/tmp/gorgoroth-3node-results` (includes container inspect output and full logs for all 3 nodes).

### Suggestions for Improvement

1. Update the 3-node walkthrough to include the missing pre-flight file setup, the actual HTTP port numbers, and explicit instructions for re-enabling mining when running the validation steps.
2. Patch the helper scripts in `ops/gorgoroth/test-scripts/` to probe the even-numbered HTTP ports and fail loudly when no nodes are detected.
3. Ship example `gorgoroth.conf` and `static-nodes.json` for node1 in git (or add a generator) so first-time users do not hit the mount errors.

### Additional Notes

- Full log bundle collected via `fukuii-cli collect-logs 3nodes /tmp/gorgoroth-3node-results` at 18:12 UTC.
- Network left running for documentation updates; remember to run `fukuii-cli stop 3nodes && fukuii-cli clean 3nodes` after addressing the mining configuration.
- Next validation attempt should re-run after setting `-Dfukuii.mining.mining-enabled=true` for all three nodes to unblock the mining + propagation phases.

### Logs and Evidence

- `/tmp/gorgoroth-3node-results/gorgoroth-fukuii-node*.log`
- `docker compose -f ops/gorgoroth/docker-compose-3nodes.yml ps` output captured in `containers-status.txt`
- Peer count + block number snapshots (commands recorded in shell history and referenced above)
