# Spec 018 Track C: the pinned oracle jar (T002)

Every "roots equal the pinned jar's" check in Track C (T004–T007, T009, T025, T033, T052, T055, T075, EG-1, EG-4,
EG-13) runs against this jar. Pinned 2026-11-17.

## The jar

| | |
| --- | --- |
| Release | **fukuii v0.12.1** — <https://github.com/chippr-robotics/fukuii/releases/tag/v0.12.1> (an *immutable* GitHub release) |
| Artifact | `fukuii-assembly-0.12.1.jar`, 234,199,208 bytes |
| SHA-256 | `2100c4ce9059a690f26dac3fb71c7afc84229d117a93f9157a4c5bb2e208fec9` |
| Verified | 2026-11-17: downloaded, `sha256sum` equals the release's `SHA256SUMS.txt` and the asset digest; manifest `Implementation-Version: 0.12.1`, `Main-Class: com.chipprbots.ethereum.App` |
| Built from | staging `21cfa13dfdc968c85e3ff393ab249376f37eba73` ("chore: bump version to 0.12.1"; tag `v0.12.1`), promoted to `main` as `b6b96e6f` (#1883) |
| Run with | JDK 25+: `java -XX:+UnlockDiagnosticVMOptions -XX:-ReduceAllocationMerges -jar fukuii-assembly-0.12.1.jar <network>` with the **default** state config (`fukuii.state.path-live = false`, `conf/base/state.conf`): the Hash scheme |

## Why 0.12.1, and what that costs (decision record)

The plan (plan.md "The oracle") asks for **the last pre-track release**: a build with none of Track C's code, so that
every Track C change is measured against code it did not touch. That release is **v0.11.0** (staging `a9896f48`, cut
2026-11-07 before C1).

**The user chose v0.12.1 on 2026-11-17.** 0.12.1 contains Track C slices C1–C5a (positions threaded through the trie,
the staged layer, the head move, the SNAP hand-off) but ships them **off**: `path-live = false` keeps the Hash scheme as
the production path, so the jar's roots, receipts and hive/EEST results are those of the Hash scheme. What this
changes:

- Shared code that C1–C5a modified (`MerklePatriciaTrie`, `BlockExecution`, the importer's head move) is in the oracle.
  A regression introduced there **before 0.12.1** cannot show as a difference against this jar. It is covered instead
  by what those slices already merged: the in-process differentials (T019, T025's half without a jar, T026's EEST half,
  T075's substitute, T035, T052, T055 against the pre-change code) and their mutation runs.
- Everything after 0.12.1 (C5b onward, C6–C8b) is measured against it as the plan intends, run with `path-live = true`
  set for the run: the default stays `false` until C8b deletes the key (plan.md, "Status", "The guard").

**Fallback, verified the same day:** `fukuii-assembly-0.11.0.jar` (v0.11.0, immutable release), 226,783,736 bytes,
SHA-256 `22057022d32e7f3d9a4f3704c83f101329be15ad0c7cb219f7c662f8868568f1`, built from staging `a9896f48`. If a
"roots equal" check against 0.12.1 is ever in doubt, re-run that range on 0.11.0: a difference between the two jars on
the Hash scheme is a C1–C5a regression.

## Where it is kept

- **Canonical source:** the immutable release asset above; the SHA-256 is the identity.
- **Out-of-tree copy:** the user's NFS share, beside the ETC 0–5.1M chain file (T010), placed and checked by the keeper
  with `sha256sum`. *Keeper and the exact path: the user to name (T002 **[user]**); until then the release asset is
  the copy of record.*
- Large recorded outputs that use this jar (T004 roots, hive and EEST pass lists) are kept beside it, their SHA-256 in
  `README.md`.

## Retention

Kept until **all** of: EG-12 passes; the first ETC v2 archive has been re-imported on a second datadir; and one release
past C8b has shipped. Then the keeper may delete the copy; the release asset stays.
