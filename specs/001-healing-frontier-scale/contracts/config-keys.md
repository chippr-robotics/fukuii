# Contract: Configuration keys

New keys under the `snap-sync { … }` block in `src/main/resources/conf/base/sync.conf`, parsed
into `SyncConfig` (`utils/Config.scala`). Defaults apply when the key is absent (backward
compatible).

## `healing-visited-cap` (Layer 1)

```hocon
snap-sync {
  # Max entries in the frontier-rebuild DFS `visited` LRU. Bounds heap during the
  # full-state walk (≈ cap × 80 B). 4,000,000 ≈ 320 MB. Lower on small heaps (more
  # re-walks, still complete); raise on large heaps (fewer re-walks).
  healing-visited-cap = 4000000
}
```

- Type: `Int` (> 0). Default `4000000`.
- Maps to the constant currently hard-coded as `HealingVisitedCap` (`:133`).
- Contract: the live `visited` size never exceeds this value; completeness is independent of it.

## `healing-frontier-persistence` (Layer 2)

```hocon
snap-sync {
  # Persist the outstanding healing frontier to a dedicated column family so a restart
  # resumes (O(frontier)) instead of re-walking the full state (O(full state)). On
  # restart, a non-empty, readable persisted frontier is loaded and the full DFS is
  # skipped; otherwise the node falls back to the full walk.
  healing-frontier-persistence = false
}
```

- Type: `Boolean`. Default: ship `false` (dark) first; flip to `true` after Layer-2 tests pass.
- Contract: `false` ⇒ identical to Layer-1 behaviour (no CF writes, always full DFS on
  restart). `true` ⇒ write-on-queue / delete-on-heal / resume-on-restart with fail-safe
  fallback.

## Backward compatibility

Both keys are additive with safe defaults. Existing config files and running nodes are
unaffected until an operator opts in. The Layer-2 column family auto-creates on next DB open
(`setCreateMissingColumnFamilies(true)`), so no datadir migration is required.
