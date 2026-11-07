# Spec 018 Track C baselines (C0)

Everything Track C's slices compare against is listed here (tasks.md Phase 1, T001–T017). A file too large to commit is
kept beside the pinned jar, outside the tree, and its SHA-256 is recorded in the table.

| File | Task | Produced by | Status |
| --- | --- | --- | --- |
| `blockdiff-golden-v1.hex`, `-v2.hex`, `-v3.hex` | T014 | `GoldenVectors.java` (run with `java GoldenVectors.java <outdir>`, JDK 25) | **Frozen 2026-11-07.** 153 B `8d0afd3e`, 219 B `707894bf`, 144 B `ae4e831f`; the CRC32C check value `e3069283` holds. Re-derived independently by vault, beacon, forge and this program. Any change reopens S0 (one-way door). |
| `pinned-jar.md` | T002 | the last pre-track release, kept out of tree | open: the release (0.11.0 when cut) and the directory (the user's NFS share is the candidate) |
| `ranges.md` | T003 | user, beacon, forge | open |
| `roots-*.txt` | T004 | the pinned jar | open (needs T002, T003) |
| `hive-<suite>-<date>.txt` | T005 | the pinned jar on the WSL hive | open |
| `eest-<date>.txt` | T006 | the pinned jar | open |
| `ethtests-<date>.txt` | T007 | the pinned jar | open |
| `eip-8246-check.md` | T008 | main session from eips.ethereum.org (2026-11-07); beacon to sign | **drafted; matches dm 3.6 rule 6** |
| `eip8246-fixtures.txt` | T009 | the pinned jar's EEST harness | open |
| ETC 0–5,100,000 chain file | T010 | **decided 2026-11-07:** fukuii-fc's ETC mainnet sync (v0.10.48, SNAP with chain download, started 2026-11-07 15:30Z), then `fukuii etc export <file> 0 5100000` with the node stopped. Kept on the user's NFS share; its SHA-256 is recorded here | sync running |
| `m14-etc-import-cost.md` | T012 | the pinned jar, ETC 0–2M from genesis | open |
| `m1-diff-size.md` | T013 | an instrumented throwaway build | open |
| `oracle-tests.md` | T015 | copied from data-model §8 | **Frozen 2026-11-07** |
