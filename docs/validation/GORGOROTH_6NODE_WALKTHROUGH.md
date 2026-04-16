# Gorgoroth Multi-Client Validation Walkthrough (6/9-node)

**Last Updated**: December 21, 2025

**Purpose**: Step-by-step guide for validating Fukuii interoperability with Core-Geth and Besu on the Gorgoroth harness.

This document covers three multi-client deployments:
- `fukuii-geth` (3 Fukuii + 3 Core-Geth)
- `fukuii-besu` (3 Fukuii + 3 Besu)
- `mixed` (3 Fukuii + 3 Core-Geth + 3 Besu)

An optional appendix keeps the long-range “wipe and re-sync” experiment using the Fukuii-only `6nodes` stack.

**Time Required**: 30–90 minutes per multi-client scenario  
**Difficulty**: Intermediate  
**Prerequisites**: Completed 3-node walkthrough, Docker + Compose, monitoring stack, `ops/tools/fukuii-cli.sh` available in your `$PATH`

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Shared Invariants](#shared-invariants)
4. [Scenario A: Fukuii + Core-Geth (`fukuii-geth`)](#scenario-a-fukuii--core-geth-fukuii-geth)
5. [Scenario B: Fukuii + Besu (`fukuii-besu`)](#scenario-b-fukuii--besu-fukuii-besu)
6. [Scenario C: Full Mixed Network (`mixed`)](#scenario-c-full-mixed-network-mixed)
7. [Cleanup](#cleanup)
8. [Appendix: Long-Range Re-sync on `6nodes`](#appendix-long-range-re-sync-on-6nodes)

---

## Overview

**Goal**: Validate cross-client networking, sync, and block acceptance across Fukuii + reference clients.

Success criteria (per scenario):
- All nodes establish peers (no discovery; static nodes only)
- Fukuii mines blocks (single-miner default) and non-miners follow
- Cross-client head agreement (same `latest` block hash across at least one node from each client)
- No recurring handshake errors in logs (RLPx auth failures, capability mismatch, etc.)

Recommended workflow:
1. Start the chosen config (`fukuii-cli start <config>`)
2. Run `fukuii-cli sync-static-nodes` (required; discovery is disabled)
3. Run `fukuii-cli smoke-test <config>`
4. Do one explicit cross-client head/hash check (commands below)

---

## Prerequisites

1. Finish the [Gorgoroth 3-Node Walkthrough](GORGOROTH_3NODE_WALKTHROUGH.md).
2. Hardware: ≥16 GB RAM, ≥4 CPU cores, ≥40 GB free disk (snap sync temp files are larger than fast sync).
3. Software:
   ```bash
   docker --version            # >= 20.10
   docker compose version      # >= 2.0
   curl --version
   jq --version
   watch --version
   ops/tools/fukuii-cli.sh version
   ```
4. Networking: expose RPC ports 8545-8552 locally; keep firewall open for docker bridge 172.25.0.0/16.


---

## Shared Invariants

These are true across the multi-client scenarios in `ops/gorgoroth`:

- **Mordor-aligned chain parameters** while keeping `network = "gorgoroth"` (see `ops/gorgoroth/conf/app-gorgoroth-override.conf`).
- **Discovery disabled / no bootnodes**: peers are created only via static nodes.
- **Deterministic mining model**: the harness is intended to run with a single miner (typically `gorgoroth-fukuii-node1`).
- **RPC enabled and bound for Docker** (`0.0.0.0`, permissive CORS) so tests can run from the host.

---

## Scenario A: Fukuii + Core-Geth (`fukuii-geth`)

**Topology**: 3 Fukuii + 3 Core-Geth

1. Start the network
   ```bash
   cd ops/gorgoroth
   fukuii-cli clean fukuii-geth
   fukuii-cli start fukuii-geth
   ```

2. Establish static peering
   ```bash
   fukuii-cli sync-static-nodes
   ```

3. Run the smoke test
   ```bash
   fukuii-cli smoke-test fukuii-geth
   ```

4. Cross-client head agreement check

   Fukuii node1 (HTTP):
   ```bash
   curl -s -X POST http://localhost:8546 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   Core-geth node1 (HTTP):
   ```bash
   curl -s -X POST http://localhost:8551 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   ✅ *Exit criteria:* the two hashes match after the network has produced a few blocks.

---

## Scenario B: Fukuii + Besu (`fukuii-besu`)

**Topology**: 3 Fukuii + 3 Besu

1. Start the network
   ```bash
   cd ops/gorgoroth
   fukuii-cli clean fukuii-besu
   fukuii-cli start fukuii-besu
   ```

2. Establish static peering
   ```bash
   fukuii-cli sync-static-nodes
   ```

3. Run the smoke test
   ```bash
   fukuii-cli smoke-test fukuii-besu
   ```

4. Cross-client head agreement check

   Fukuii node1 (HTTP):
   ```bash
   curl -s -X POST http://localhost:8546 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   Besu node1 (HTTP):
   ```bash
   curl -s -X POST http://localhost:8551 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   ✅ *Exit criteria:* the two hashes match after the network has produced a few blocks.

---

## Scenario C: Full Mixed Network (`mixed`)

**Topology**: 3 Fukuii + 3 Core-Geth + 3 Besu (9 nodes)

1. Start the network
   ```bash
   cd ops/gorgoroth
   fukuii-cli clean mixed
   fukuii-cli start mixed
   ```

2. Establish static peering
   ```bash
   fukuii-cli sync-static-nodes
   ```

3. Run the smoke test
   ```bash
   fukuii-cli smoke-test mixed
   ```

4. Cross-client head agreement check (one node per client)

   Fukuii node1 (HTTP `8546`):
   ```bash
   curl -s -X POST http://localhost:8546 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   Core-geth node1 (HTTP `8551`):
   ```bash
   curl -s -X POST http://localhost:8551 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   Besu node1 (HTTP `8557`):
   ```bash
   curl -s -X POST http://localhost:8557 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"eth_getBlockByNumber","params":["latest",false],"id":1}' | jq -r '.result.hash'
   ```

   ✅ *Exit criteria:* all three hashes match after the network has produced a few blocks.

---
## Cleanup

```bash
cd ops/gorgoroth
fukuii-cli stop fukuii-geth || true
fukuii-cli stop fukuii-besu || true
fukuii-cli stop mixed || true

# Delete containers + volumes (destructive)
fukuii-cli clean fukuii-geth || true
fukuii-cli clean fukuii-besu || true
fukuii-cli clean mixed || true
```

---

## Appendix: Long-Range Re-sync on `6nodes`

This appendix keeps the original long-range experiment (wipe a node and re-sync it) using the Fukuii-only `6nodes` stack.

**Goal**: Validate that Fukuii can recover a cold node over long ranges using both *fast sync* and *snap sync* while the rest of the cluster advances.

### Setup (Fukuii-only)

```bash
cd ops/gorgoroth
fukuii-cli clean 6nodes
fukuii-cli start 6nodes
fukuii-cli sync-static-nodes
```

> ℹ️ For `6nodes`, Fukuii RPC is typically exposed on host ports `8545/8547/8549/8551/...` (HTTP) with their paired WS ports on the next port.

From here, the long-range phases (baseline → wipe node4 → fast sync → snap sync → stability) can be performed as originally described, but ensure all JSON-RPC calls go to the **HTTP** port for the node (not the WS port).
