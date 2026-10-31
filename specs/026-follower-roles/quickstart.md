# Quickstart: Follower Roles — `replica` and `archive`

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Date**: 2026-10-31

Every scenario is runnable once the slice it names has merged (plan.md "Delivery slices"). Commands assume a build of
the `fukuii` launcher with 023's `--chain`/`--role` flags (023 T-003) and 025's file sink (025 T-003). Scenarios 1 and
3 are the MVP and run on the Mordor-class devnet with the file sink, no Kafka. Nothing here needs P2P: a follower that
binds a P2P, discovery or Engine API port fails SC-005.

---

## 0. Fixtures

```
# 025 file sink from an ingest on the Mordor-class devnet (023 FR-007 harness fixture, or a local ingest):
$ ls /tmp/fleet/mordor-dev/records/
000000.rec 000001.rec …                 # one file per N records, versioned RLP, 025 FR-002
# A seed. Until 013 covers ETC (013 §11 row 2), the devnet MVP seed is genesis (GT-1 option (c)):
$ export SEED=genesis
# With 013 Phases 1–2 landed (ETH family) or row 2 accepted (ETC):
$ export SEED=/tmp/fleet/mordor-dev/snapshots/00012340000/      # manifest.json + chunks, 013 §5.3
```

## 1. US1 — a `replica` bootstraps once and follows (slices S1–S5)

```
$ fukuii --chain mordor-dev --role replica \
    -Dfukuii.follow.source.type=file \
    -Dfukuii.follow.source.file.dir=/tmp/fleet/mordor-dev/records \
    -Dfukuii.follow.seed.path=$SEED \
    -Dfukuii.follow.window=600
```

Expected log lines (INFO), in order:

```
[FOLLOW] seed verified: block=12340000 root=9f3a… anchor=ingest-head         # skipped when SEED=genesis
[FOLLOW] following from 12340001, window=600 lagBound=8 source=file
[FOLLOW] applied 12340001..12340600 (600 blocks, p50 apply 4.1 ms, p99 11.8 ms, roots verified)
[FOLLOW] ready: head=12340600 sourceLag=0
```

Checks:

```
$ curl -s localhost:8547/healthcheck | jq '{role, head, sourceLag, ready, window, fault}'
{"role":"replica","head":{"number":12340600,...},"sourceLag":0,"ready":true,
 "window":{"low":12340000,"high":12340600},"fault":null}

$ curl -s localhost:8546 -d '{"jsonrpc":"2.0","id":1,"method":"eth_getBalance","params":["0x…","latest"]}' -i
HTTP/1.1 200 OK
Fukuii-Pinned-Block: 12340600:0x7c…
{"jsonrpc":"2.0","id":1,"result":"0x…","pinnedBlock":{"number":"0xbc49f8","hash":"0x7c…"}}

$ curl -s localhost:8546 -d '{"jsonrpc":"2.0","id":2,"method":"eth_getBalance","params":["0x…","0xbc47a0"]}'
{"jsonrpc":"2.0","id":2,"error":{"code":-39010,"message":"outside retention window",
 "data":{"requested":{"number":12339104},"window":{"low":12340000,"high":12340600},"head":12340600}}}

$ curl -s localhost:8546 -d '{"jsonrpc":"2.0","id":3,"method":"eth_getBalance","params":["0x…","pending"]}'
{"jsonrpc":"2.0","id":3,"error":{"code":-39011,"message":"not served by this role",
 "data":{"role":"replica","method":"eth_getBalance"}}}
```

**SC-001**: the first `eth_getBalance` answered after `ready` is at `lastVerifiedRoot`
(`curl …/healthcheck | jq .head.hash` equals the `pinnedBlock.hash`). **SC-002** is scenario 1b.

### 1b. US1 — a corrupted record halts the replica within the block

```
$ scripts/follow/corrupt-record.sh /tmp/fleet/mordor-dev/records/000003.rec --block 12340777 --flip-slot-value
$ fukuii --chain mordor-dev --role replica … (as above, fresh datadir)
```

Expected:

```
[FOLLOW] applied 12340001..12340776
[FOLLOW] HALT root-mismatch at block 12340777 expected=aa10… computed=0f9e…; last verified head=12340776
```

```
$ curl -s localhost:8547/healthcheck | jq '{ready, fault, head}'
{"ready":false,"fault":"root-mismatch","head":{"number":12340776,...}}
$ curl -s localhost:8546 -d '{"jsonrpc":"2.0","id":1,"method":"eth_getBalance","params":["0x…","latest"]}'
{"jsonrpc":"2.0","id":1,"error":{"code":-39012,"message":"follower halted",
 "data":{"fault":"root-mismatch","blockNumber":12340777,"blockHash":"0x…"}}}
```

The process stays up. Restarting it starts halted (the fault is durable); only replacing or re-seeding the datadir
(Orbita, 023 DC-3/DC-4) clears it. `fukuii follow inspect-halt --datadir …` prints the offending record and both roots.

## 2. US2 — historical state from an `archive` (phase D; slices S9–S10, needs 018 Track R slice R1)

```
$ fukuii --chain mordor --role archive \
    -Dfukuii.follow.source.type=kafka \
    -Dfukuii.follow.source.kafka.bootstrap-servers=kafka:9092 \
    -Dfukuii.follow.seed.path=/seeds/mordor/00012340000
```

```
$ curl -s localhost:8546 -d '{"jsonrpc":"2.0","id":1,"method":"eth_getBalance","params":["0x…","0x1e8480"]}'
{"jsonrpc":"2.0","id":1,"result":"0x…","pinnedBlock":{"number":"0x1e8480","hash":"0x…"}}   # block 2,000,000
$ curl -s localhost:8546 \
    -d '{"jsonrpc":"2.0","id":2,"method":"eth_call","params":[{"to":"0x…","data":"0x70a08231…"},"0x1e8480"]}'
{"jsonrpc":"2.0","id":2,"result":"0x…","pinnedBlock":{...}}
```

**SC-003** harness step: `scripts/follow/compare-archive.sh --fukuii localhost:8546 --reference
http://core-geth-archive:8545 \
--samples 1000` samples blocks across the whole chain and diffs `eth_getBalance`, `eth_getCode`, `eth_getStorageAt`,
`eth_getProof` and `eth_call` byte for byte; exit code 0 means identical. A `replica` asked the same `0x1e8480` returns
`-39010` (US2 scenario 2).

## 3. US3 — a deep ETC reorg is survived or reported (slice S5 with the 025 FR-009 fixture)

The 025 deep-reorg fixture replays ≥ 100 retractions followed by the replacement blocks through the file sink.

```
$ fukuii --chain mordor-dev --role archive  -Dfukuii.follow.source.file.dir=/tmp/fleet/mordor-dev/records-deep-reorg …
[FOLLOW] retraction 12340600 → root verified against parent 12340599 (backward)
…
[FOLLOW] retraction 12340501 → root verified against parent 12340500 (backward)
[FOLLOW] applied 12340501'..12340650' (150 blocks, forward)
[FOLLOW] ready: head=12340650 sourceLag=0

$ fukuii --chain mordor-dev --role replica -Dfukuii.follow.window=50 \
    -Dfukuii.follow.source.file.dir=…records-deep-reorg …
[FOLLOW] HALT reseed-required: retraction of 12340501 is below window low 12340550 (depth 100 > W 50)
```

**SC-004**: the archive's final state equals the reference consumer's clean derivation (025 FR-010) at every sampled
account; the replica's health shows `fault: "reseed-required"` and every read returns `-39012`.

## 4. US4 — a `sim` pool reads a follower (023 harness; slice S5 plus 023 T-006)

```
$ fukuii --chain plataberget-dev --role replica -Dfukuii.follow.window=300 …
$ fukuii --chain plataberget-dev --role sim -Dfukuii.sim.follower=http://replica:8546 …
```

The harness load generator pins each `sim` session to a block (`Fukuii-Pinned-Block` from the first response) and
issues `eth_call` with that explicit block for 10 minutes while the chain advances. Expected:

- every response's `pinnedBlock` equals the pinned block (no drift, DC-6);
- when the pinned block falls below `window.low` the `sim` receives `-39010` with `data.window`, re-pins to
  `window.high` and continues (US4 scenario); the harness asserts zero responses at a block other than the pinned one.

This is 023 SC-002's follower side.

## 5. GT-1 measurement (slice S0; the first thing that runs)

```
$ fukuii follow-bench --chain mordor --seed /seeds/mordor/00012340000 \
    --source file:/data/mordor-records --from 12340001 --to 12347200 --report gt1-mordor-apply.json
$ fukuii follow-bench --chain mordor --seed … --reexecute --from 12340001 --to 12347200 \
    --report gt1-mordor-reexec.json
$ fukuii follow-bench --chain mordor --seed genesis --source file:/data/mordor-records --to 500000 \
    --report gt1-mordor-genesis.json
```

Same three on Platåberget (no `genesis` arm). The report has per-block apply p50/p99, nodes and bytes written, disk
growth per 1,000 blocks, total wall time and blocks/s. Paste into plan.md "GT-1 result"; it sets FR-002's default and
SC-001's bound.

## 6. Verifying the negative space (SC-005)

```
$ ss -lntu | grep -E ':30303|:8551'      # nothing: no P2P, discovery or Engine API port on a follower
$ curl -s localhost:8546 -d '{"jsonrpc":"2.0","id":1,"method":"admin_peers","params":[]}'
{"jsonrpc":"2.0","id":1,"error":{"code":-39011,"message":"not served by this role",...}}
```
