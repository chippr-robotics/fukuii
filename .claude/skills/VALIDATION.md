# Skills ↔ node validation

These skills drive the node **as implemented today**, so every interface they
name is cross-checked against the source on the branch. This file records the
method and how to re-run it when the node changes.

> Re-run this whenever the JSON-RPC surface, CLI, or config schema changes
> (e.g. after touching `JsonRpcController.scala`, `cli/CliCommands.scala`, or
> `src/main/resources/conf/`). A skill that references a removed/renamed
> interface is a bug.

## How to re-validate

```bash
# 1) Every RPC method named in a skill must be registered in the controller
grep -rhoE '\b(eth|admin|miner|net|fukuii|qa|web3|rpc|personal|debug)_[a-zA-Z]+' .claude/skills/ | sort -u
grep -rhoE '"(eth|admin|miner|net|fukuii|qa|web3|rpc|personal|debug)_[a-zA-Z]+"' \
  src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcController.scala | tr -d '"' | sort -u
# (diff the two; every referenced method must appear in the controller list)

# 2) Every MCP tool name must exist in McpTools.scala (prompts in McpPrompts.scala)
grep -rhoE 'mcp_[a-z_]+' .claude/skills/ | sort -u
grep -E 'val name = ' src/main/scala/com/chipprbots/ethereum/jsonrpc/mcp/McpTools.scala

# 3) Every `fukuii cli <cmd>` must be wired in CliCommands.scala
grep -nE 'Command\b' src/main/scala/com/chipprbots/ethereum/cli/CliCommands.scala

# 4) Every config key must exist under src/main/resources/conf/
#    (grep the key path in base/*.conf and the network templates)
```

## Result of the last run (branch base: `staging`)

| Surface | Referenced | Status |
| :-- | :-- | :-- |
| JSON-RPC methods (`admin_*`, `eth_*`, `miner_*`, `net_*`, `fukuii_*`, `web3_*`, `rpc_*`) | 41 distinct | **All present** in `JsonRpcController.scala` |
| MCP read tools (`mcp_node_status`, `mcp_sync_status`, `mcp_peer_list`, `mcp_blockchain_info`, `mcp_node_info`, `mcp_mining_rpc_summary`) | 6 | **All present** in `McpTools.scala` |
| MCP prompt (`mcp_node_health_check`) | 1 | **Present** in `McpPrompts.scala` (a prompt, not a tool) |
| CLI (`generate-private-key`, `generate-key-pairs`, `derive-address`, `encrypt-key`, `generate-allocs`) | 5 | **All wired** in `CliCommands.scala` |
| Config keys (`network.rpc.*`, `sync.*`, `mining.*`, `network.server-address/discovery.port`) | 12 | **All present** under `conf/base/*.conf` |
| Doc/source links | 27 | **All resolve** (`conf/fukuii.conf` is the operator-supplied override, by design not a repo file) |

## Mismatches found between the legacy runbooks and the node — corrected in the skills

The validation surfaced several places where the existing prose runbooks have
drifted from the code. The skills use the **code's** truth and flag the drift:

1. **RPC port** — default is **`8546`** (`network.rpc.http.port`), not `8545` as
   some runbooks/MCP examples show. CONVENTIONS and skills use 8546.
2. **P2P port** — both server and discovery default to **`30303`**; the peering
   runbook's `9076` is stale. Corrected in `fukuii-peer-management`.
3. **MCP namespace** — `mcp` is **not** in the default
   `network.rpc.apis = "eth,web3,net,personal,fukuii,debug,qa,admin"`; it must be
   added to use the in-process MCP tools. Documented in CONVENTIONS.
4. **RPC TLS keys** — the node uses a **nested** `network.rpc.http.certificate {
   keystore-path, keystore-type, password-file }` block (default
   `certificate = null`), **not** the flat `certificate-keystore-path` form
   (that's the separate *faucet* config). Corrected in `fukuii-tls-operations`
   and `fukuii-node-configuration`.
5. **`compact-database` CLI** — does **not** exist (only a commented example in
   `known-issues.md`). `fukuii-disk-management` explicitly says so instead of
   recommending it.
6. **Checkpoint service** — config-driven (`sync.checkpoint-sync-file` /
   `-url`), there is **no `checkpoint_*` RPC**. `fukuii-checkpoint-service`
   reflects this.

## Caveat

This validates that every interface a skill names **exists and is spelled
correctly** in the current source. It does not execute the skills against a live
node (no node is running in this environment). End-to-end behavioral validation
(start a node, run each skill's read path, dry-run the guarded writes) should be
done in a staging deployment before relying on the guarded-write steps in
production.
