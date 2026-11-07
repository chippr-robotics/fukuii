# Track C oracle tests, frozen for C1 (T015)

This list is frozen 2026-11-07, copied from [data-model.md](../data-model.md) §8 ("Oracles"). beacon and forge signed it in S0
round 2 (item 1). C1 merges only when every test below passes. Each test is named in its C1 task.

| # | Oracle | What must hold | C1 task |
| --- | --- | --- | --- |
| 1 | **Incremental versus full diff** | For random `put` and `remove` sequences, the positions emitted incrementally equal the diff of two whole-trie walks of stored positions, old and new. The sequences include branch collapses into leaves and extensions, extension splits, nodes crossing the 32-byte boundary in both directions, and empty-trie transitions. | T019 |
| 2 | **`StackTrie` rebuild** | After the mutations, the position-to-bytes map reachable from the root equals what `StackTrie` emits through `onTrieNode` when it rebuilds the same key set (`StackTrie.scala:21-35`, `finalise` `:267`). | T020 |
| 3 | **Reverse exactness, single and multi-block** | Applying a block and then reversing it restores the parent's reachable map. Applying blocks N..N+k, where a later block writes a position an earlier block vanished, and then reversing them all is also exact. Only a later write at the same position plus a deeper reverse exposes a missed vanished position (S0 item 1d). | T021 |
| 4 | **Several persist rounds per block** | With one persist per transaction, rule 1's first-event prior holds: a position created by transaction 1 and replaced by transaction 2 reverses to absent. | T021 |
| 5 | **Real fixtures** | These run through the staged layer: the EEST Amsterdam fixtures (EIP-7928 block access lists, the EIP-8246 clear); the pre-Byzantium and pre-Atlantis ethereum/tests networks (Frontier, Homestead, EIP-150), where per-transaction receipts carry state roots; and the ETC import from genesis (plan EG-1, using the T010 chain file). Mordor never exercises per-transaction roots, because its Atlantis is at block 0. | T026; EG-1 in C7 |
| 6 | **Genesis emits nothing vanished** | A genesis build (insert-only, `AppendOnlyMptStorage`, `GenesisDataLoader.scala:288-294`) emits no vanished positions. | T023 |
| 7 | **Consensus unchanged, both chains** | The roots and node sets of the random sequences, and of the recorded Mordor and Sepolia ranges (T003, T004), equal those of the pinned pre-track jar (T002). | T025 |

Also pinned in C1:
- the report rules: only replaced or absorbed nodes are reported, not the branch `fix` resolves and keeps (T022);
- decorator completeness by reflection (T024);
- M7, the allocation merge gate on Mordor (T033).
