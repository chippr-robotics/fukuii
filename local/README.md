# ./local/ — Working Directory

This directory is **gitignored** and never committed to upstream. It is the
persistent workspace for session notes, reference client analysis, and
operational context that would otherwise be re-derived on every context reset.

## Structure

```
local/
├── README.md               # This file
├── context/
│   ├── PROJECT_CONTEXT.md  # Read this first — project overview, rules, goals
│   └── PR_GUIDE.md         # Commit hygiene, PR format, branch naming
├── reference/
│   ├── SNAP_PROTOCOL.md    # snap/1 protocol spec summary (EIP-4644)
│   ├── GETH_SNAP.md        # go-ethereum SNAP sync (canonical implementation)
│   ├── BESU_SNAP.md        # Besu SNAP sync (ETC-compatible serving peer)
│   ├── CORE_GETH_SNAP.md   # core-geth SNAP sync (primary ETC production client)
│   ├── ERIGON_SNAP.md      # Erigon SNAP sync
│   └── NETHERMIND_SNAP.md  # Nethermind SNAP sync
├── snap/
│   ├── CURRENT_STATE.md    # What is working/broken/WIP on may-fields RIGHT NOW
│   └── BUG_LOG.md          # Active bugs with status
└── notes/
    └── ATTEMPT26_NOTES.md  # Prior sync attempt notes (moved from repo root)
```

## How to Use

**Start of every session:**
1. Read `context/PROJECT_CONTEXT.md` to orient
2. Read `snap/CURRENT_STATE.md` to know where work left off
3. Run `git fetch upstream && git log upstream/main --oneline -5` for recent upstream

**End of every session:**
1. Update `snap/CURRENT_STATE.md` with what changed
2. Add new bugs to `snap/BUG_LOG.md`
3. Commit code changes to `may-fields` following `context/PR_GUIDE.md`
