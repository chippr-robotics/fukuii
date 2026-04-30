# Nethermind SNAP Sync Reference

**Source:** `/media/dev/2tb/dev/nethermind`
**Language:** C# (.NET)
**Role:** Secondary reference — useful for snap/1 protocol edge cases
**Chain:** ETH mainnet primary (PoS post-merge). No ETC support.

> **PoW Warning:** Nethermind is a PoS client with no ETC support. Use for snap/1
> protocol mechanics and edge cases only. Language difference (C#) means no code
> can be directly adapted — patterns only.

---

## SNAP Protocol Location

```
src/Nethermind/Nethermind.Synchronization/SnapSync/
├── SnapProvider.cs          # Main SNAP sync provider
├── SnapSyncDispatcher.cs    # Request dispatch
├── ProgressTracker.cs       # Progress tracking
└── ...

src/Nethermind/Nethermind.Network/P2P/Subprotocols/Snap/
├── SnapProtocolHandler.cs   # snap/1 protocol handler
└── Messages/
    ├── GetAccountRangeMessage.cs
    ├── GetStorageRangeMessage.cs
    └── ...
```

---

## When to Reference Nethermind

- snap/1 protocol edge cases and interoperability issues
- Progress tracking and resumption patterns
- Error handling under diverse network conditions
- Independent verification of protocol behavior

## When NOT to Reference Nethermind

- Architecture (C# async/await ≠ Scala actors)
- PoW chain behavior
- ETC-specific anything
- Direct code adaptation (language gap is too large)
