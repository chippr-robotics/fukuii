# Quickstart: Change-Record Export

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Data model**: [data-model.md](data-model.md)

Three runnable scenarios, one per user story. US1 runs on a laptop with no broker; US2 needs the 023 harness (a
docker Kafka); US3 needs an archive reference node. Every command is from the repository root. Commands that do not
exist until a slice lands say which slice.

Pre-flight (all scenarios):

```bash
sbt compile-all                      # must be clean
./scripts/fukuii-test only "*RecordCodec*"   # slice 0: golden vectors + property tests green
```

---

## US1 — A reorg propagates correctly (file sink, fixture replay; slice 3)

**Goal.** Replay the five FR-009 fixtures through the real import path with the file sink attached, then fold the
written records with the reference consumer and compare with a clean derivation of the final canonical chain.

```bash
# 1. The whole fixture set at unit level (SC-001 on fixtures), both pruning modes, ETC and ETH-family shapes.
./scripts/fukuii-test only "*ReorgFixturesSpec*"

# 2. One shape at a time, verbose, keeping the segment files for inspection:
sbt -Dexport.fixtures.keep=true "testOnly *ReorgFixturesSpec* -- -z deep-etc"
ls target/export-fixtures/deep-etc/mordor/         # records-<firstSeq>.bin segments

# 3. Decode a segment to the manifest's JSON view (offline tool; slice 3):
sbt "runMain com.chipprbots.ethereum.export.tools.DumpRecords target/export-fixtures/deep-etc/mordor" | head -40
```

What to look for in step 3, for `deep-etc`: `finality 0` first, then `block 1..120`, then **100 retractions**
`120, 119, …, 21` (newest first), then `block 21'..120'`, and **no further finality record** (ETC: genesis only).
For `eth-above-finality`: the retractions stop above the finalized height and a `finality` record follows the FCU
that moved it. For `eth-below-finality-injected`: **no** records for the injected move, `exportState = halted`,
`exportLastError = "retraction below finality: #N …"`, and the next imported block still commits (import continued).
For `restart-mid-export`: the last two block records appear twice; the consumer's duplicate rule absorbs them.

The spec asserts, for every shape, `ReferenceConsumer.state == CleanDerivation(canonicalChain).state` (accounts,
slots, code, and every block's `stateRoot` recomputed) — US1 acceptance 1 and 2.

Manual check of the codec on a vector:

```bash
xxd -r -p src/test/resources/export/golden/v1/block-storage-update.hex | head -c 1 | xxd   # 0x01 version byte
```

## US2 — The broker is down and nothing is lost (023 harness; slice 4)

**Goal.** Run `ingest` on Mordor with the Kafka sink, stop the broker, keep importing, restart the broker, and show
(a) import p99 unchanged (SC-002), (b) no gap and no reorder after recovery (SC-003 short outage), (c) halt + health
+ import continues past the bound (SC-003 long outage).

```bash
# 1. Harness bring-up (023 T-007): one broker, one ingest on mordor, export on.
./scripts/fleet-harness up --chain mordor --with-kafka
# ingest config used by the harness (data-model.md §8):
#   fukuii.export.enabled = true, sink = "kafka", kafka.bootstrap-servers = ["kafka:9092"]
#   buffer.max-blocks = 64, buffer.max-bytes = 256MiB      # small bounds so the long outage is reachable in minutes

# 2. Baseline p99 with export on and the broker up (SC-002 arm "on"):
./scripts/fleet-harness measure import-p99 --blocks 500 --out out/p99-on.json

# 3. Short outage: stop the broker for 2 minutes, keep importing, restart it.
./scripts/fleet-harness kafka stop
curl -s localhost:13798/metrics | grep -E 'fukuii_export_(lag_blocks|queue_depth|buffer_bytes|state)'
#   lag and queue_depth climb; state = 0 (running); import keeps going (watch fukuii_regular_sync_current_block)
./scripts/fleet-harness kafka start
#   lag returns to 0; records_total advances by exactly the queued count
./scripts/fleet-harness verify stream --topic fukuii.mordor.records.v1 --from-position <pos-before-outage>
#   asserts: contiguous blockNumber (modulo explicit retractions), no reorder, duplicates only at the boundary

# 4. Long outage: stop the broker until the bound is hit.
./scripts/fleet-harness kafka stop
# wait until queue_depth == 64 (buffer.max-blocks)
curl -s localhost:<admin-port>/healthcheck | jq '.checks[] | select(.name|startswith("export"))'
#   exportState = 1 (halted), exportLastError = "buffer bound reached: blocks=64 …", exportLag still growing,
#   and the node's own head still advances (import never paused)
./scripts/fleet-harness kafka start
#   export stays halted (a gap exists); operator action: unfence/restart after resync of the follower side, per 023

# 5. SC-002 arms "off" and "on, broker down":
./scripts/fleet-harness measure import-p99 --blocks 500 --export off --out out/p99-off.json
./scripts/fleet-harness measure import-p99 --blocks 500 --export on --kafka down --out out/p99-down.json
./scripts/fleet-harness compare p99 out/p99-*.json --band 023-gt3    # all within the GT-3 noise band
```

Without the harness, the sink and drain logic are covered by `./scripts/fukuii-test only "*KafkaRecordSink* *Exporter*"`
(`MockProducer`: delivery timeout ⇒ position holds; ack ⇒ position advances; restart ⇒ re-send from position).

## US3 — The indexer derives balances exactly (reference consumer vs archive; slice 5)

**Goal.** Fold a real record range with the reference consumer and compare every touched account's balance at every
block with `eth_getBalance` on an archive node (SC-004), on Mordor and on Platåberget.

```bash
# 1. Export a range to files from a synced ingest (file sink; or read the topic with the dump tool).
#    fukuii.export.sink = "file", file.directory = /data/export
#    Let it run across the chosen range, e.g. Mordor 9,400,000–9,401,000.

# 2. Seed the consumer's pre-state at the range start from the archive node (balances, nonces, code, slots of the
#    accounts the range touches — the tool asks the archive for exactly the keys the records name):
sbt "runMain com.chipprbots.ethereum.export.tools.BalanceAudit \
      --records /data/export/mordor --from 9400000 --to 9401000 \
      --archive http://archive-mordor:8545 --sample every-block"

# 3. Output: one line per (block, address) with consumer balance, archive balance, equal?; exit 0 only if all equal
#    and every block's recomputed stateRoot equals header.stateRoot. Same for Platåberget:
sbt "runMain com.chipprbots.ethereum.export.tools.BalanceAudit \
      --records /data/export/plataberget --from <start> --to <end> \
      --archive http://archive-plataberget:8545 --sample every-block"
```

The audit also prints the record-size distribution and per-block byte counts for the range, which is how OQ-7's
per-chain buffer bounds and retention figures are read off (`data-model.md` §8 is updated from it in slice 5).

---

## Operating the exporter (single-node profile)

```hocon
fukuii.export {
  enabled = true
  sink = "kafka"
  kafka.bootstrap-servers = ["broker-1:9092", "broker-2:9092"]
  kafka.properties { security.protocol = "SASL_SSL", sasl.mechanism = "SCRAM-SHA-512", sasl.jaas.config = "…" }
}
```

Create the topic first (`contracts/topic.md`): `fukuii.<chainKey>.records.v1`, 1 partition, `cleanup.policy=delete`,
`retention.ms` ≥ the longest follower outage you tolerate, `min.insync.replicas=2`, `message.max.bytes` ≥ 16 MiB.

Health and metrics: `GET /healthcheck` (export entries), `GET :13798/metrics` (`fukuii_export_*`). `exportState` 1
means halted: read `exportLastError`, fix the cause (broker, bound, oversize record), then `unfence` (023) or restart;
the stream resumes from the persisted position and may re-send the last batch.
