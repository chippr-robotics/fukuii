# Quickstart: validating spec 021 (GT-2, GT-3, GT-4)

*Spec 021, Phase 1, 2026-11-07; S4, mutable `Memory`, added the same day. This is a run guide. What each check
proves is in [plan.md](./plan.md) (gates) and [research.md](./research.md) (R-11 to R-14), and results go into
research.md §B. Commands that could not be checked
from this host are marked **VERIFY** and are checked the first time they run.*

## 0. Environment and prerequisites

**Where things run.**
- sbt, unit tests and the bench harness run in WSL (`~/fukuii`, Temurin 25). From Git Bash:
  `MSYS_NO_PATHCONV=1 wsl.exe -e bash -lc 'cd ~/fukuii && <command>' | tr -d '\0'`.
- In a linked worktree, sbt needs the untracked `zz-local-worktree-git-shim.sbt` (never committed).
- Never run sbt beside a running node.
- Import differentials and trace profiles run on the measurement host with **every node stopped**, serially (spec 020
  R-4 host rules).
- CI workflows are dispatched on the slice's branch with
  `gh workflow run <file> --repo chippr-robotics/fukuii --ref <branch>`, run from WSL (`cd ~` first).

| # | Prerequisite | Needed by | State (2026-11-07) |
|---|---|---|---|
| P-1 | A frozen post-Amsterdam ETH range: Platåberget if glamsterdam-devnet-8 still produces blocks, else post-Amsterdam Sepolia (spec 020 F-13). Freeze it by spec 020 R-4 (stop the unit, copy the datadir, fetch `debug_getRawBlock` for H+1…H+N; never `admin_exportChain`). N = 2,000, with at least 200 blocks of ≥ 30 Mgas | GT-3 row 5, GT-4 | to do before S1's GT-3 |
| P-2 | A Mordor export, blocks 0 to ≥ 10,000,000: `java -jar <jar> mordor export mordor-0-10000000.rlp.gz 0 10000000`, on the **stopped** Mordor node | GT-3 row 9 | to do before S1's GT-3 |
| P-3 | The ETC mainnet chain file at `~/fukuii-artifacts/etc-0-5100000.rlp.gz` on `fukuii-fc`, SHA-256 `8c52ee9dcb20e733065fad51a2cf289cc300546f61f13371748cc442ce1f89e8`; on the NFS share later. Runs read this copy only, never a path in a node's tree. Check it before each run: `sha256sum ~/fukuii-artifacts/etc-0-5100000.rlp.gz` | GT-3 row 10 | **present** |
| P-4 | Trace fixture blocks: the last 64 blocks of each fixed range, with state retained in the differential's final datadir. **VERIFY** the pruning window; if it is shorter, use the last N blocks it does retain | GT-4 §5.2 | checked at S0's baseline run |
| P-5 | Jars: A = the tree before the slice; B = the slice's tip. `sbt assembly`, nodes stopped | GT-3, GT-4 | per slice |
| P-6 | **The ETC mainnet head window** (forge C2). ETC mainnet is synced on `fukuii-fc` (SNAP pivot 25,688,690, root verified). (1) On a **planned stop** of that node, copy its datadir: state at head H, root verified. (2) At a later planned stop, export H+1..H+5,000 with `java -jar <jar> etc export etc-head-<H+1>-<H+5000>.rlp.gz <H+1> <H+5000>`, and record H, the range and its SHA-256. **Never copy a live datadir.** Row 10's runs already stop the node, so P-6 needs no extra outage. (3) **Run row 10b once with jar A** (the pre-S1 tree) before it gates S1 (forge R4-a). The datadir comes from a SNAP-synced node, so a state hole would fail jar B for reasons unrelated to 021. A clean jar-A import of the same 5,000 blocks proves the copy and the window, and gives S8's A figure on that range | GT-3 row 10b; S8 (C5) | to do before S1's GT-3 |

## 1. Every commit

```bash
sbt compile-all                      # main and test sources, every module
sbt scalafmtAll                      # all modules (never plain scalafmt)
sbt "testOnly <the suites the commit touches>"
scripts/spec021/check_uint256_bigint.sh            # from S1: the DC-3 usage check (research R-15)
scripts/spec021/check_uint256_bigint.sh --self-test
```

**Expected**: 0 compile errors, no formatting diff, the targeted suites green, and the check printing `OK`.

## 2. GT-2: the reference differential (S1 onward)

```bash
sbt "testOnly *UInt256DifferentialSpec"            # fast: ≥ 10^4 cases per op + every named row; VMTest; Tier 1
sbt "testOnly *UInt256DifferentialFullSpec"        # full: ≥ 10^7 cases per op + every named row; DifferentialTest tag
sbt "testOnly *UInt256Spec *UInt256RlpEncodingSpec *TimestampUnsignedSpec"
```

**Expected output.**
- One line per operation: `op=<name> cases=<n> seed=<hex> diffs=0`.
- The named rows E, C, P and L, each listed as passed.
- At the end, `0 differences`.

A single difference fails the suite. Its line prints the seed and the operands as hex, so it can be replayed exactly
(fixed seeds, shrinking off).

**Exclusions** (the suite prints them):
- `<<`, `>>` and `sshift` are compared on shifts 0–255;
- `apply` of more than 32 bytes is compared as "both throw".

**Coverage of the kernels** (research §B row 2; the target is 100% of statements and branches, including Algorithm
D's add-back):

```bash
sbt "coverage; testOnly *UInt256DifferentialSpec *UInt256Spec; coverageReport"
# open target/scala-3.9.0/scoverage-report/index.html → domain/UInt256.scala, domain/UInt256Kernels.scala
```

**No closures after inlining** (research R-1; the expected output is empty):

```bash
for c in target/scala-3.9.0/classes/com/chipprbots/ethereum/domain/UInt256*.class; do
  javap -c -p "$c" | grep -n "LambdaMetafactory" && echo "closure in $c"
done
# from S3, also vm/Stack*.class and the arithmetic opcode classes (vm/ADD$.class … vm/CLZ$.class)
```

**DifferentialTest tag.** The full suite is excluded from `testEssential` and `testStandard` by `-l DifferentialTest`
in their aliases. CI runs it as a step nightly and on PRs that touch `domain/UInt256*` or `vm/`. **VERIFY at S1** that
a plain `testOnly` of the full suite is not filtered by the build's default `testOptions`.

## 2b. GT-2 for S4: the memory differential (FR-012)

```bash
sbt "testOnly *MemoryDifferentialSpec"         # fast: ≥ 10^3 generated operation sequences + M01–M15; VMTest; Tier 1
sbt "testOnly *MemoryDifferentialFullSpec"     # full: ≥ 10^6 sequences (~3·10^7 operations) + M01–M15; DifferentialTest
sbt "testOnly *MemorySpec *OpCodeEdgeRowsSpec *TraceGoldenSpec"
```

**How it works.** Each sequence of 1 to 64 operations (`write`, `writePadded`, the word and byte stores and loads,
`copyOut`, `hashRegion`, `expand`, `move`, `snapshot`) is applied to `vm.oracle.ImmutableMemory`, today's
`vm/Memory.scala` copied verbatim into the test tree, and to the new mutable `Memory`.

**Expected output.**
- One line per sequence batch: `memory batch=<k> sequences=<n> ops=<m> seed=<hex> diffs=0`.
- The M rows listed as passed.
- At the end, `0 differences`.

**Compared after every operation**:
- `size`;
- every byte returned;
- `hashRegion` against `kec256` of the oracle's region;
- that every earlier `copyOut` result and snapshot is unchanged (isolation, M06);
- that the buffer's capacity is ≤ 1.5 × `written` + 1 KiB (heap bound).

At the end of each sequence, `equals`, `hashCode` and `toString` are compared with the oracle's.

**Tests first.** In S4's first commit the suite runs against today's immutable `Memory` (oracle against itself, plus
the M rows) and is green. The swap commit then must leave it green.

**Heap bounds** (rows M07 and M08, §B row 15), under the bench harness's hive JVM flags (512 MB heap):

```bash
scripts/bench/blockchain-test-bench.sh <ethereum/tests BlockchainTests fixture holding randomStatetest94> 'randomStatetest94' 1
scripts/bench/blockchain-test-bench.sh <fixture holding static_Call1MB1024Calldepth> 'static_Call1MB1024Calldepth' 1
```

**Expected**: PASS (gas used, receipts and state root match the fixture), and no `OutOfMemoryError`. **VERIFY** the
fixture file paths under `ets/tests` at S0, when the GT-4 baseline is taken.

**CALL input is not copied per CALL** (row M15 (c), §B row 16; forge R1-a). Use a bench fixture written at S4's
tests-first commit: a loop of warm, value-free CALLs to an EOA, with written inputs of 1 KiB and of 1 MiB.

```bash
scripts/bench/blockchain-test-bench.sh <m15-call-input-fixture.json> 'm15_input_1KiB' 3
scripts/bench/blockchain-test-bench.sh <m15-call-input-fixture.json> 'm15_input_1MiB' 3
```

**Expected**: allocated bytes per CALL (the allocated-bytes column ÷ CALL count) equal within noise for 1 KiB and
1 MiB. A dependence on the input size fails S4. M15 (a) and (b), identity output owned and retained input owned, run
in `MemoryDifferentialSpec`'s row set.

## 3. The pins: goldens and opcode-level rows (written in S0, re-run unchanged at every slice)

```bash
sbt "testOnly *UInt256GoldenBytesSpec"   # codec goldens (forge S2): RLP, Account RLP, CREATE address, access-list key → tx hash,
                                         # storage-trie key hash, JSON quantities, toHexString/toString, InvalidJump string
sbt "testOnly *OpCodeEdgeRowsSpec"       # O01–O15, FR-010's truncation invariants, and forge's F01–F05: every S7 vector
                                         # (calcMemCost incl. the exact sentinel; wordsForBytes), ETC SSTORE across
                                         # Phoenix and Magneto, pre-EIP-150 CALL gas, the Olympia opcodes on the ETC table
sbt "testOnly *OpCodeTableGoldenSpec"    # F06: (byte, name, delta, alpha, constant gas) per ETC and ETH fork (forge S5)
sbt "testOnly *OpCodeContractSpec"       # F07: state-gas delta zero over the ETC (config, block) matrix; W04 from S3
sbt "testOnly *TraceGoldenSpec"          # structLog (memory capture off AND on), callTracer, prestateTracer and vmTrace
                                         # JSON over the fixture cases, including error steps (OOG, InvalidJump, underflow,
                                         # RETURNDATACOPY overflow, CREATE initcode limit and preflight OOG, the EIP-8037
                                         # shortfall, a CALL that fails without revert and one that reverts)
```

**Expected**: green at S0's tip, then green with **no change to any expected value** at every later slice. A diff to
a golden file in a slice PR fails review by definition (S2, DC-8).

## 4. GT-3: the consensus oracles (before each slice merges)

The pass rule is spec 020 R-4: the canonical header is the oracle. Every block of a range imports (state root,
receipts root and gas used validated against its header), with 0 failed.

### 4.1 ETH (beacon signs)

```bash
# ethereum/tests (GeneralStateTests, VMTests, BlockchainTests), ETH network type
gh workflow run ethereum-tests-nightly.yml --repo chippr-robotics/fukuii --ref <branch>
#   or locally: FUKUII_DEV=true sbt "IntegrationTest / testOnly com.chipprbots.ethereum.ethtest.*"
gh workflow run eest-amsterdam.yml   --repo chippr-robotics/fukuii --ref <branch>     # EEST Amsterdam fixtures
gh workflow run hive-consensus.yml   --repo chippr-robotics/fukuii --ref <branch>     # 40–80 min per suite

# import differentials (measurement host, nodes stopped)
scripts/spec020/differential.sh ephemery <jarB> s<k>        # 135,638–147,609 (N = 11,972)
# the P-1 range: a new case in the same script (or a spec021 copy), added when P-1 is frozen
```

**Pass.**
- The CI runs: failures = the baseline's (zero new, and none fixed silently: "both directions count", spec 009 rule).
- Each range: `N imported, 0 already present`, exit 0.

### 4.2 ETC (forge signs: S4)

```bash
# (a) the ETC-typed legacy suite
FUKUII_DEV=true FUKUII_LEGACY_TESTS_NETWORK_TYPE=ETC \
  sbt "IntegrationTest / testOnly *LegacyPreByzantiumBlockchainTestsSpec"
# (b) pre-Spiral ethereum/tests: the fork names up to Shanghai in the 4.1 run, through the one shared EVM

# (c) Mordor: the fixed range, then from genesis across Agharta, Phoenix, Magneto, Mystique and Spiral (P-2)
scripts/spec020/differential.sh mordor <jarB> s<k>           # 17,207,612–17,212,611 (N = 5,000)
FUKUII_CONF_DIR=<fresh-conf-with-empty-datadir> java -Xss8M -jar <jarB> mordor import mordor-0-10000000.rlp.gz

# (d) ETC mainnet from genesis, on fukuii-fc (P-3), from the artifact copy only
sha256sum ~/fukuii-artifacts/etc-0-5100000.rlp.gz   # must be 8c52ee9dcb20e733065fad51a2cf289cc300546f61f13371748cc442ce1f89e8
FUKUII_CONF_DIR=<fresh-conf-with-empty-datadir> java -Xss8M -jar <jarB> etc import ~/fukuii-artifacts/etc-0-5100000.rlp.gz

# (10b) the ETC mainnet head window, on fukuii-fc (P-6), from S1 on; a fresh copy of P-6's datadir per run
FUKUII_CONF_DIR=<conf-pointing-at-a-copy-of-P-6's-datadir> java -Xss8M -jar <jarB> etc import etc-head-<H+1>-<H+5000>.rlp.gz
```

**Pass.**
- (a) and (b) green.
- (c) and (d) report `Imported <file>: <N> imported, <0 or 1> already present` with exit 0. N is 10,000,000 or
  5,100,000; **VERIFY** whether each file includes genesis, which would count as already present.
- (d) validates pre-Atlantis receipts roots, which commit the per-transaction intermediate state roots, so those are
  covered.
- (10b) reports `Imported <file>: 5000 imported, 0 already present` with exit 0, from S1 on.
- Record row 10's wall time with A at the S0 baseline and with B at every slice tip (§B row 7c; C8, not a gate).

**Declared gap (forge signed it on 2026-11-07).**
- ETC mainnet is synced on `fukuii-fc` (pivot 25,688,690, root verified), so the head is covered by row 10b.
- The gap is ETC mainnet blocks between 5,100,000 and the head window, whose rules rows 7 to 10b cover (research
  R-13).
- It closes with spec 018's EG-12, or with a state-free strict replay of a `fukuii-fc` export from 0 to ≥ 19,260,000,
  run once at S3's tip.
- No slice waits for either.

## 5. GT-4: measurements (every slice; gates none, except S8)

### 5.1 Allocation per opcode (bench harness)

```bash
sbt "IntegrationTest / compile"
JFR=/tmp/021-s<k>-<case>.jfr scripts/bench/blockchain-test-bench.sh <fixture.json> '<caseRegex>' 5
jfr view allocation-by-class /tmp/021-s<k>-<case>.jfr | head -30
jfr print --events jdk.ObjectAllocationSample --stack-depth 12 /tmp/021-s<k>-<case>.jfr \
  | grep -B2 -A12 "java.math.BigInteger" | grep -c "UInt256\|vm.OpCode"   # SC-003: 0 from S1 on
```

**Fixtures**:
- hive's `loopMul` (`loopMul_d2g0v0_Cancun`);
- one arithmetic-dense, one memory-dense and one DIV/MULMOD/EXP-dense GeneralStateTest, chosen and frozen at S0.

Opcodes per case come from one traced run of the case (the structLog count). **Allocated bytes per case** come from
an allocated-bytes column that S0 adds to `BlockchainTestBench` (`ThreadMXBean.getCurrentThreadAllocatedBytes` around
each run). This is a test-tree change.

**Record** bytes per opcode and the top 10 classes (§B rows 4 and 5, and rows 9 to 12 for the residual shares).

### 5.2 Trace re-execution (`debug_traceBlockByNumber`)

On a **copy** of each differential's final datadir, with networking off and JSON-RPC on its default `localhost:8546`
(constitution VI: never a public interface; `debug` is in the default `apis`):

```bash
FUKUII_CONF_DIR=<conf-pointing-at-the-copy> \
  java -XX:StartFlightRecording=filename=/tmp/021-trace-s<k>.jfr,settings=profile,dumponexit=true -jar <jar> <net>
for n in $(seq <H-63> <H>); do
  /usr/bin/time -f "%e" curl -s -H 'content-type: application/json' \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"debug_traceBlockByNumber\",\"params\":[\"$(printf '0x%x' $n)\",{}]}" \
    http://localhost:8546 > /dev/null
done
jfr view allocation-by-class /tmp/021-trace-s<k>.jfr | head -30
```

**Record** allocation per traced block, p50 and p99 seconds per block, and the `BigInteger` samples on the arithmetic
path (§B row 6). From S2 on, the structLog stack is rendered by `toHexQuantity`, and the S0 trace goldens prove the
output is unchanged.

### 5.3 `[IMPORT-TIMING]` and S8

The differential runs in §4 already log `[IMPORT-TIMING]`. Follow spec 020 R-4:
- alternate A, B, A, B;
- drop the first 200 blocks;
- take p50 and p99 per run, and the median of the two runs per jar.

`differential.sh` writes them to `runs/<chain>-<label>/result.txt`.

**S8 (forge).**
- S1's figure is recorded.
- **The budget is judged at S2's tip**: A = the pre-S1 tree, B = the S2 tip, on **both** the Mordor fixed range and
  the ETC head window (row 10b, P-6). The condition, on each range, is `p50_B ≤ 1.10 × p50_A` and
  `p99_B ≤ 1.10 × p99_A`.
- **Re-judged at S3's tip and S4's tip**: the same A, with B = that tip, on the same two ranges. A breach blocks that
  slice's merge.
- **The release rule**:
  - no release tag, image or operator deployment is cut from a `staging` commit that has S1 without S2;
  - if S2 cannot meet S8 before the next release cut, S1 is reverted.
- A breach is answered with more T-005 conversions (measured). Memoising `toBigInt` is the last resort.
- Optional, recorded only: `[IMPORT-TIMING]` p50/p99 over ETC 2,200,000–2,500,000 from row 10's run (pre-EIP-150,
  the 2016 DoS-era blocks; **VERIFY** the window from the timings).

## 6. End-of-slice checklist

| Slice | Before code | At the end |
|---|---|---|
| S0 | RP-1 signed (incl. F01–F07) | §3 green (the O and F rows, the opcode-table golden, the trace goldens); §5 baseline rows recorded (A = S0 tip), including row 10's wall time |
| S1 | RP-2 signed; P-6 done, including its clean jar-A run of row 10b | §1, §2 (full, 0 differences, coverage, javap), §3 unchanged, §4 all rows including 10b, §5 recorded; `testEssential` once; the release rule in force until S2 lands |
| S2 | RP-3 signed | §1 (the check now includes the opcode fence), §2, §3 unchanged, §4 including 10b, §5 with **S8 judged** on Mordor and the ETC head window; `testEssential` once |
| S3 | RP-4 signed | §1, §2 (+ the stack-vs-value kernel differential), §3 unchanged (trace goldens, F06), §4 all rows including 10b, §5 (SC-003, **S8 re-judged**); `testEssential` once |
| S4 | RP-5 signed | §1, §2 (the UInt256 suites still green), **§2b** (memory differential, full, 0 differences; the heap bounds), §3 unchanged (trace goldens with memory capture), §4 all rows including 10b, §5 (**S8 re-judged**; memory share, §B rows 6 and 12); `testEssential` once |
