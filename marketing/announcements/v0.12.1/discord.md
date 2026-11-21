## 🦗 fukuii v0.12.1 is out

Everything since 0.11.0, over 130 merged changes: some you can use today, some laying the foundation for what's next.

**What's new for operators**
🖥️ **TUI Command Center**: run with `--tui` for a full terminal dashboard covering chain, mempool, RPC, Engine API, mining and storage, with alerts and operator actions.
🌐 **Hoodi and Ephemery are built in**: `fukuii hoodi` / `fukuii ephemery`. Ephemery exits at each 28-day reset; under a supervisor, the restart picks up the next iteration.
🔌 **Engine API on by default** for chains that need a consensus client, on `localhost:8551`. A persistent JWT secret is created at `<datadir>/jwt.hex`. Point your CL's `--execution-jwt` at it.
📦 **New install paths**: `ppa:chipprbots/fukuii` (Ubuntu noble/jammy) and the Chocolatey community repo.

**Correctness and hardening**
• `eth_simulateV1`, VM error strings, precompile failures and the execution-apis fixtures now match geth
• EIP-1459 DNS discovery trees are fully verified
• WebSocket Origin allowlist, per-transport RPC namespaces, per-deployment TLS certs
• SNAP pivot anchored to the CL head post-merge; safer pruning; exact stored TD
• Miner and block import now build the identical block on ETC

**Groundwork for what's next**
Most of this cycle went into spec 018 Track C, a **unified, position-keyed state layout** for both ETC and ETH:
• forward layers and a reverse changeset index, so the node can read state behind the head and on forks
• heavier branches executed on pinned layers and adopted in one move (reorgs in place)
• SNAP sync handing off in one synced write with **nothing copied**
• a node that can tell when it can't follow the chain with the state it holds, and recover

It ships behind a dev guard (off by default) while the last slices land. When it flips, the Hash/Path duality goes away.

⬇️ https://github.com/chippr-robotics/fukuii/releases/tag/v0.12.1
JAR, zip and tgz are available (JDK 25+). Verify with `SHA256SUMS.txt`.
