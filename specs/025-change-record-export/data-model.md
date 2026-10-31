# Data Model: Change-Record Export (spec 025)

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Wire layout**: [contracts/record-schema.md](contracts/record-schema.md)

Field names are the names the implementation uses. RLP item order and byte-level encoding are in `contracts/`; this file
is the logical model plus the node-local store, metrics and configuration. Everything that leaves the node is in the
"Record" sections; everything else is private to `ingest`.

Schema version of this model: **1** (`0x01`).

---

## 1. Record envelope (every type)

| # | Field | Type | Notes |
|---|-------|------|-------|
| — | `schemaVersion` | 1 byte, **outside** the RLP | `0x01`. A consumer that sees another value stops (FR-011). |
| 0 | `type` | byte | `0` block, `1` retraction, `2` finality, `3` witness (reserved, FR-012). |
| 1 | `chainKey` | UTF-8 string | 024 DC-3; partition identity. Until 024 lands, the value of `fukuii.blockchains.network`. |
| 2 | `chainId` | unsigned integer (BigInt; Platåberget is wider than 32 bits) | informational (024 DC-3). |
| 3 | `blockNumber` | unsigned integer | the block this record is about; for finality, the finalized block. |
| 4 | `blockHash` | 32 bytes | |
| 5 | `parentHash` | 32 bytes | identity only; lets a consumer check continuity without decoding the body. |
| 6 | `body` | RLP list, per type | §2–§5. Empty list for a retraction. |

Identity = `(chainKey, type, blockNumber, blockHash)`; a duplicate identity is a no-op for consumers (DC-3).

## 2. Block record body (`type = 0`)

| # | Field | Type | Source in the node |
|---|-------|------|--------------------|
| 0 | `header` | `BlockHeader` RLP (`BlockHeaderEnc.toRLPEncodable`, `domain/BlockHeader.scala:250`) | `Block.header` |
| 1 | `receipts` | list of receipt RLP (`ReceiptCodecs`, eth/68 bloom-inclusive form; typed receipts prefixed by type byte) | `BlockData.receipts` |
| 2 | `accounts` | list of **account entries** (§2.1), ordered by `addressHash` ascending | `BlockWriteSetRecorder` (research R-1) |
| 3 | `code` | list of **code entries** (§2.2), ordered by `codeHash` ascending | recorder: union of `accountCodes` snapshots |

Ordering is fixed so that the bytes of a record are a function of its content alone (DC-2: byte-identical across
layouts). The diff is netted over the whole block: an entry whose previous and new values are equal is absent.

### 2.1 Account entry

| # | Field | Type | Notes |
|---|-------|------|-------|
| 0 | `addressHash` | 32 bytes | `keccak(address)`; the apply key (FR-008; `Address.hashedAddressEncoder`). |
| 1 | `address` | 20 bytes | preimage, for consumers that work by address (indexer; SC-004). |
| 2 | `prevAccount` | `Account` RLP `[nonce, balance, storageRoot, codeHash]` or empty string | empty = absent before the block. |
| 3 | `newAccount` | same | empty = deleted/absent after the block. |
| 4 | `slots` | list of **slot entries** (§2.1.1), ordered by `slotHash` ascending | may be empty. |

`storageRoot` and `codeHash` inside the account values are consensus data and are always present; a follower on
today's layout uses `storageRoot` to verify, a follower on 018's flat layout recomputes it.

#### 2.1.1 Slot entry

| # | Field | Type | Notes |
|---|-------|------|-------|
| 0 | `slotHash` | 32 bytes | `keccak(pad32(slot))`; flat key = `addressHash ++ slotHash` (018 flat stores). |
| 1 | `slotKey` | 32 bytes | preimage, left-padded. |
| 2 | `prevValue` | unsigned integer (RLP minimal big-endian), empty string for zero/absent | |
| 3 | `newValue` | same | empty = deleted (zero). |

When `newAccount` is absent and `prevAccount.storageRoot` was non-empty, `slots` lists **every** previous slot with
`newValue` empty (research R-1 step 4): applying the record backward restores the storage; forward deletes it.

### 2.2 Code entry

| # | Field | Type | Notes |
|---|-------|------|-------|
| 0 | `codeHash` | 32 bytes | `keccak(code)`. |
| 1 | `code` | bytes | the blob. New blobs only; code is content-addressed, so there is no previous value. |

Every `newAccount.codeHash` that is neither `EmptyCodeHash` nor present in the consumer's pre-state appears in `code`
(FR-008: code is a dependency of its leaf). Backward application leaves blobs in place.

## 3. Retraction record (`type = 1`)

Body: empty list. Identity names the block that is no longer canonical. Emitted newest-first for a reorg, before the
replacement block records (FR-007). Never emitted for a block at or below the finality watermark (FR-007; the attempt
halts export, research R-6).

## 4. Finality record (`type = 2`)

Body: empty list. `blockNumber`/`blockHash` are the finalized block, `parentHash` its parent. ETC: exactly one, for
genesis, on the first start with export enabled. ETH family: one per change of `finalizedBlockHash` in an applied
`engine_forkchoiceUpdated`.

## 5. Witness record (`type = 3`, reserved)

| # | Field | Type | Notes |
|---|-------|------|-------|
| 0 | `witnessFormat` | byte | `0` = spec 017 `ExecutionWitness` RLP; others reserved. |
| 1 | `witness` | bytes | opaque to this spec. |

Never produced unless `fukuii.export.witness.enabled = true`; this spec ships the codec and the type byte only.

---

## 6. Node-local store (private to `ingest`; never exported)

### 6.1 Column family `x` — `Namespaces.ExportNamespace` (new; added to `nsSeq`, `db/storage/Namespaces.scala:33-56`)

| Key space | Key (bytes) | Value | Written by | Deleted by |
|-----------|-------------|-------|------------|------------|
| Staged record | `0x01 ++ blockNumber (8, BE) ++ blockHash (32)` | full block record bytes (§1–§2) | execution-commit batch (`BlockExecution.executeAndCommit` line 698; `executeAndValidateBlockFull` line 82) | drain housekeeping, once `blockNumber < position.blockNumber − buffer.side-block-retention` |
| Emission log | `0x02 ++ seq (8, BE)` | RLP `[kind, blockNumber, blockHash]`, kind `0` block / `1` retraction / `2` finality | canonical-commit batch of the family (plan.md GT-2 table) | drain housekeeping, `deleteRange` up to the position's `seq` |

Both key spaces are scanned with `scanRange`/`seekFrom` (never `get` in a loop; storage protocol). `seq` is a
monotonic 64-bit counter kept in memory and seeded from the log's last key at start.

### 6.2 AppState keys (CF `s`, `AppStateStorage.Keys`)

| Key | Value | Written when |
|-----|-------|--------------|
| `export.position` | RLP `[seq, blockNumber, blockHash]` of the last acknowledged log entry | after sink ack (`update`); `updateSync` on fence and shutdown (FR-004) |
| `export.finality` | RLP `[blockNumber, blockHash]` of the last emitted watermark | in the batch that logs the finality entry |
| `export.bufferBytes` | decimal string, total bytes of staged records | in every batch that adds/deletes a staged record; recomputed by scan at start |
| `export.halted` | reason string, absent when running | when the exporter halts (bound reached, below-finality retraction, pruned readopted record, oversize record); cleared by `unfence` or restart with the cause removed |

### 6.3 In-memory state of the exporter

| Field | Type | Notes |
|-------|------|-------|
| `position` | `(seq, blockNumber, blockHash)` | mirror of `export.position` |
| `lastEmitted` | `(blockNumber, blockHash)` | highest `block` entry appended to the log (not yet necessarily acked); the FCU hook emits from `lastEmitted + 1` (research R-6) |
| `watermark` | `(blockNumber, blockHash)` | mirror of `export.finality` |
| `wake` | `cats.effect.std.Queue[IO, Unit]` of capacity 1 | the hook `tryOffer`s after a batch commits; the drain loop reads the log, not the queue (DC-6: the hook never waits) |
| `state` | `Running \| Halted(reason) \| Fenced` | `Fenced` is set by 023's fence; `Halted` by this spec's bounds and refusals |

---

## 7. Metrics (Micrometer, `MetricsContainer`, prefix `fukuii_export_`) and health fields (FR-006)

| Metric | Health field (023 FR-004 shape) | Type | Definition |
|--------|---------------------------------|------|------------|
| `fukuii_export_lag_blocks` | `exportLag` | gauge | `head.number − position.blockNumber` (0 when idle and up to date) |
| `fukuii_export_queue_depth` | `exportQueueDepth` | gauge | emission-log entries with `seq > position.seq` |
| `fukuii_export_buffer_bytes` | `exportBufferBytes` | gauge | `export.bufferBytes` |
| `fukuii_export_last_error` | `exportLastError` | info gauge (1 when set) + the string in health | last halt or sink error; empty when running |
| `fukuii_export_records_total{type}` | — | counter | records acknowledged by the sink, by type |
| `fukuii_export_sink_latency_seconds` | — | timer | `publish` call duration |
| `fukuii_export_state` | `exportState` | gauge (0 running, 1 halted, 2 fenced) | |

Until 023 T-005 lands, the health fields are added as `HealthcheckResult` entries on the existing `/healthcheck`
(`healthcheck/HealthcheckResult.scala`), named exactly as above so 023's shape adopts them unchanged.

---

## 8. Configuration (`fukuii.export`, HOCON; defaults)

```hocon
fukuii {
  export {
    enabled = false                       # the whole feature; nothing is recorded or stored when false
    sink = "kafka"                        # "kafka" | "file"
    chain-key = ${fukuii.blockchains.network}  # replaced by the 024 ChainSpec chainKey when 024 lands

    kafka {
      bootstrap-servers = ["localhost:9092"]
      topic-template = "fukuii.{chainKey}.records.v1"   # contracts/topic.md
      client-id-template = "fukuii-ingest-{chainKey}"
      acks = "all"                        # fixed; not a tuning knob
      compression = "zstd"
      max-request-size = 16MiB            # a record above this halts export with exportLastError
      request-timeout = 30s
      delivery-timeout = 120s
      properties = {}                     # passthrough for security.protocol, sasl.*, ssl.* (deployment)
    }

    file {
      directory = ${fukuii.datadir}"/export"
      segment-bytes = 256MiB
    }

    buffer {
      max-blocks = 4096                   # emission-log entries above the position (OQ-7; harness sets per chain)
      max-bytes = 4GiB                    # staged record bytes (OQ-7)
      side-block-retention = 1024         # blocks below the position a staged record is kept for readoption
    }

    drain {
      batch = 64                          # log entries per publish
      idle-poll = 1s                      # fallback poll when no wake arrives
    }

    witness {
      enabled = false                     # FR-012: reserved type; 017 decides production
    }
  }
}
```

Retention guidance for the topic (operator-side, not a node config): `retention.ms` ≥ the longest tolerated follower
outage; 7 days default; `cleanup.policy = delete`, never `compact` (contracts/topic.md).

---

## 9. Test-side entities

| Entity | Where | Purpose |
|--------|-------|---------|
| `ReferenceConsumer` | `src/test/scala/com/chipprbots/ethereum/export/ReferenceConsumer.scala` | folds records into flat maps, recomputes roots, exposes `balance(address, blockNumber)` (FR-010) |
| `ReorgFixtures` | `src/test/scala/com/chipprbots/ethereum/export/fixtures/ReorgFixtures.scala` | the five FR-009 shapes, deterministic seed (research R-8) |
| Golden vectors | `src/test/resources/export/golden/v1/` | one `.hex` per record type plus `manifest.json` (contracts/golden-vectors.md) |
