# Quickstart: validating each spec 020 PR

This file lists the commands and the expected outcome for each PR. The research sections it cites (R-n, §B) are in
[research.md](./research.md). Run sbt on chipprbots-server only with every node stopped, under
`flock /tmp/fukuii-sbt.lock`. Record results as `VERIFY: ran <command> — result: PASS | FAIL | DID NOT RUN`.

## Q-0 Every PR (the per-PR gate)

| Step | Command | Expected |
|---|---|---|
| compile | `sbt clean compile-all 2>&1 \| tee compile.log` | 0 errors. Count the warnings as in GT-1 §4 (`[warn] -- ` header lines) and put the per-category table in the PR body. The 605 ceiling applies under `-source:3.9` (PR 6 onward; one-off record compiles before that) |
| format | `sbt "bytes/scalafmtCheckAll; crypto/scalafmtCheckAll; rlp/scalafmtCheckAll; scalanet/scalafmtCheckAll; scalanetDiscovery/scalafmtCheckAll; scalafmtCheckAll"` | green (scalafmt 3.8.3). CI's plain `scalafmtCheckAll` covers the root only (root has no `aggregate`), so run the per-module form locally |
| guard | `scripts/ci/check_scala_imports.sh && scripts/ci/test_check_scala_imports.sh` (from PR 2) | exit 0 |
| Tier 1 | CI `ci.yml` "Run Essential Tests" | succeeded = §B row 1, failed 0 |
| oracles | CI hive rpc-compat, graphql | equal to §B rows 4–5. **A rise counts as a change too** |
| DC-8 | `git diff --stat origin/staging -- '*Spec.scala' '*Test.scala' src/test src/it` | only the expected rewrite or hand-fix files. No changed assertion or expected value (review by reading) |

## Q-1 PR 1: sbt 1.13.0 and plugins

```bash
sbt about                                   # reports sbt 1.13.0
sbt pp                                      # green
sbt "bytes/scalafixAll --check; rlp/scalafixAll --check"   # green (both clean on staging)
sbt "crypto/scalafixAll --check" 2>&1 | grep -c error      # ≤ §B row 12; repeat for scalanet, scalanetDiscovery, root
sbt assembly dist                           # jar and zip produced; compare the file lists with the GT-2 build
sbt "show javaAgents"                       # kanela agent still listed (sbt-javaagent org move)
```

Expected: no `.scala` file under `src/`, `bytes/`, `crypto/`, `rlp/` or `scalanet/` is in the diff. If sbt-scalafix
0.14.9 errors on 3.3.8, revert that one line and record it, so it moves to PR 3. Report the coverage figure (Q-8).
If it moved by more than 0.1 pp, split out the scoverage bump.

## Q-2 PR 2: import guard proofs

```bash
scripts/ci/test_check_scala_imports.sh      # self-test: OK cases exit 0; `import x._`, `{a, _}`, `{a => b}`, `x._, y.*` exit 1
scripts/ci/check_scala_imports.sh           # whole tree: exit 0
# scalafix half (scratch, not committed):
echo 'import scala.util._' >> bytes/src/main/scala/com/chipprbots/ethereum/utils/ByteUtils.scala
sbt "bytes/scalafix --check"                # expect DisableSyntax.scala2ImportWildcard error
git checkout -- bytes/
```

## Q-3 PR 3: compiler 3.9.0 and the 3.4 step

1. For each hand-fix commit: `sbt compile-all` **on 3.3.8** before the flip, to show the fix is version-neutral. If a
   fix does not compile on 3.3.8, it moves after the flip commit and the commit message says so.
2. With `-source:3.4-migration -rewrite` and `-Wconf:id=E198` / `cat=unchecked` demoted to `:w` (in the flip commit,
   as on GT-1's inventory runs): `sbt compile-all` writes the rewrite. Commit the output as is. Expected: 16 files,
   +19 / −33, including the 13 unused-import deletions (GT-1 C1). A different count is investigated before
   committing.
3. Flag → `-source:3.4`, ratchets back at `:error`: `sbt clean compile-all`. Expected: 0 errors, no
   `-Ykind-projector` option warning. For the record, one more compile under `-source:3.9` (GT-1 C2 gave 605). Warning
   counts under the interim `-source:3.4`/`3.6`/`3.7` levels are not comparable to the 605 ceiling (measured under
   `-source:3.9`); the ceiling is enforced in PR 6.
4. `git diff <pre-rewrite>..<post-rewrite> -- src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`: forge confirms
   that only backticks and `?` wildcards were added.
5. Q-7 differential on both ranges.

## Q-4 PR 4: 3.5, 3.6 and DC-3

```bash
# 3.6 site list
sbt clean compile-all   # with -source:3.6-migration
grep -F '[E205]' compile.log | sort -u > /tmp/e205.txt; wc -l /tmp/e205.txt     # expect 371 (GT-1)
git log -S'given RLPEncoder[Byte]' --oneline -- rlp/src/main/scala/com/chipprbots/ethereum/rlp/RLPImplicits.scala
grep -rn 'given_RLPEncoder_\|given_RLPDecoder_' --include=*.scala .               # expect 0 references by name
# after the RLPImplicits and JsonRpcController commits, one-off check:
sbt clean compile-all   # with -source:3.7-migration, no -rewrite
grep -c '\[E205\]' compile.log                                                   # expect 0
```

Run the Q-7 differential after the `RLPImplicits` commit alone. `sbt "testOnly *RLP* *JsonRpcController*"` plus
`rlp/test` are green.

## Q-5 PR 5: 3.7 and P3b

```bash
sbt compile-all          # -source:3.7-migration -rewrite → expect 187 files, +514/−514
git diff --word-diff=porcelain HEAD~1 | grep '^[+-][^+-]' | grep -v '^+using ' | wc -l   # expect 0 (only `using` inserted)
# P3b scope (scala3-style.md S4)
grep -rn "implicit class\b" src/main/ --include="*.scala" | grep -v "//\|consensus/\|vm/" | wc -l   # before: record; after: 0
grep -rn "not given.*override\|given is final\|overridden in subclass" src/main/ --include="*.scala"  # G3 sites unchanged
```

Q-7 differential.

## Q-6 PR 6: 3.8, 3.9, `-source:3.9` and the ratchet

```bash
sbt clean compile-all    # -source:3.8-migration -rewrite → expect no diff; then 3.9-migration → no diff
# @unchecked probe (research R-9): confirm 3.9.0 accepts `val P(x) = e: @unchecked` silently; record the result
sbt clean compile-all    # final flags, -source:3.9 → 0 errors; warnings per research R-6 (expected 85)
grep -c '\[E205\]\|Implicit parameters should be provided' compile.log   # 0
```

`dc4-ledger.md` is committed. The **full DC-5 run** follows: Q-7 on both ranges, timing within budget; forge signs
off ETC and beacon ETH; `ethereum-tests-nightly.yml` dispatched on the branch; `snap-synctest.yml`; Q-8 coverage.

## Q-7 Two-tree import differential and `[IMPORT-TIMING]` (host, JDK 21)

**Once, at GT-2** (per chain: Mordor `fukuii-mordor.service`, datadir `/home/dontpanic/mordor-data`; Platåberget
`fukuii-plataberget.service`, datadir `/home/dontpanic/plataberget-data`. Never alongside each other or
`fukuii-sepolia`. First confirm devnet-8 is live, per research R-4):

```bash
systemctl --user stop fukuii-mordor
H=$(...)   # head of the stopped datadir: read from the last log line, or start briefly with RPC only and call eth_blockNumber
cp -a /home/dontpanic/mordor-data /home/dontpanic/mordor-data.spec020-base-$H     # check df first
systemctl --user start fukuii-mordor     # let it advance past H+N
# later, with the node live: build the range file in geth-export format (NOT admin_exportChain, research F-6)
for n in $(seq $((H+1)) $((H+N))); do
  curl -s -X POST -H 'content-type: application/json' 127.0.0.1:8546 \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"debug_getRawBlock\",\"params\":[\"$(printf '0x%x' $n)\"]}" \
  | jq -r .result | sed 's/^0x//' | xxd -r -p
done > mordor-$((H+1))-$((H+N)).rlp
sha256sum mordor-*.rlp     # → research §B row 7
systemctl --user stop fukuii-mordor
```

Apply the range selection rule (research R-4) before fixing H and N.

**Per run** (order A, B, A, B; A = the GT-2 jar, B = the PR jar):

```bash
rm -rf /tmp/s020-run && cp -a /home/dontpanic/mordor-data.spec020-base-$H /tmp/s020-run   # or the SSD path if /tmp is small
java <the unit's ExecStart JVM flags> \
  -Dfukuii.datadir=/tmp/s020-run \
  -Dfukuii.import-chain-file=$PWD/mordor-$((H+1))-$((H+N)).rlp \
  -Dfukuii.state-read-caches.import-timing-log=true \
  -Dfukuii.network.discovery.discovery-enabled=false \
  -Dfukuii.network.peer.max-outgoing-peers=0 -Dfukuii.network.peer.max-incoming-peers=0 \
  -jar <jar> <the unit's program arguments> 2>&1 | tee run-<jar>-<k>.log
# wait for: "Chain import complete: N imported, 0 skipped, 0 failed", then query the last block and stop:
curl -s ... eth_getBlockByNumber [H+N,false] | jq '.result | {stateRoot, receiptsRoot}'
```

Confirm the exact config paths (`import-chain-file` is read from the instance config by `StdNode.importChainData`;
the peer limits are in `conf/base/network.conf`) once at GT-2, and record the command line in §B. It must not change
afterwards. The scratch-dir `rm -rf` is the user's step if the permission classifier blocks it. Otherwise copy into a
new numbered directory each run.

Per-block roots (for the forge/beacon sign-off), with the final datadir of one A run and one B run each started
RPC-only:

```bash
for n in $(seq $((H+1)) $((H+N))); do
  curl -s -X POST -H 'content-type: application/json' 127.0.0.1:8546 \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getBlockByNumber\",\"params\":[\"$(printf '0x%x' $n)\",false]}" \
  | jq -r '[.result.number,.result.stateRoot,.result.receiptsRoot]|@tsv'
done > roots-<jar>.tsv
diff roots-A.tsv roots-B.tsv    # expect empty
```

**Pass**: every run `imported = N, skipped = 0, failed = 0`; `roots-A.tsv` and `roots-B.tsv` identical, and equal to
the canonical headers.

**Timing**:
```bash
grep -o '\[IMPORT-TIMING\] block=[0-9]* .*total=[0-9.]*ms' run-*.log  # per-run file
# drop the first 200 blocks; p50, p99 of total=; median of the two runs per jar
# budget: p50_B <= 1.10*p50_A and p99_B <= 1.10*p99_A, per chain
```

## Q-8 Coverage (host, nodes stopped)

```bash
sbt "clean; coverage; testEssential; testStandard; coverageReport; coverageAggregate"
# read the aggregate statement % from target/scala-*/scoverage-report/scoverage.xml (statement-rate)
```

Expected: ≥ §B row 2 and ≥ 70%.

## Q-9 PR 7: protocol text

```bash
# every grep named in scala3-style.md, scala3-given-migration.md and warning-ratchet.md runs on the tree and gives the stated target
grep -rn "source:future" CLAUDE.md .claude/agent-protocols/ .specify/memory/constitution.md   # only historical mentions remain
grep -n "1.10.7\|3.3.8" CLAUDE.md docs/development/*.md docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md   # updated or marked historical
```
