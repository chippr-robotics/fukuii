# Contract: Kafka Topic per Chain

**Spec**: [../spec.md](../spec.md) FR-003, DC-3, DC-6 | **Clarifications 2026-10-31, Q1** (binding) | **Schema**:
[record-schema.md](record-schema.md)

## 1. Naming

```
fukuii.<chainKey>.records.v1
```

- `<chainKey>` is the 024 `chainKey` (`etc`, `mordor`, `sepolia`, `plataberget`, operator keys). Never `chainId`
  (024 DC-3).
- `.v1` is the **schema** version of `record-schema.md` (the leading byte, `0x01`). A new schema version is a new
  topic; the old one keeps draining until its retention expires. Consumers subscribe to exactly one version.
- The template is `fukuii.export.kafka.topic-template = "fukuii.{chainKey}.records.v1"`; operators who must fit a
  naming policy may change the prefix, never the `chainKey` or version segments.

## 2. Partitioning

**Exactly one partition.** Kafka orders only within a partition, and DC-3/DC-4 require total order per chain. The
message key is `blockNumber` as 8 big-endian bytes (informational; with one partition it does not route). fukuii
refuses to start exporting if the topic exists with more than one partition (`exportLastError`, halted). The topic
is created by the operator (or by `auto.create.topics.enable` with `num.partitions = 1`; not recommended).

Throughput ceiling of one partition is far above any chain's record rate (a Sepolia block record is 0.2–2 MB every
12 s; a Mordor record is tens of KB every 13 s).

## 3. Retention

| Setting | Value | Why |
|---------|-------|-----|
| `cleanup.policy` | `delete` | **Never `compact`.** Compaction keeps the latest value per key; retractions and replacement blocks share a key and would be collapsed, erasing the reorg (Clarifications Q1). |
| `retention.ms` | ≥ the longest follower outage the operator tolerates; default guidance 7 days (`604800000`) | Followers never bootstrap from Kafka (026 FR-001 seeds from a snapshot); retention only has to cover catch-up after an outage. |
| `retention.bytes` | unset or ≥ `retention.ms × record rate × record size` | Size-based deletion that fires before `retention.ms` silently shortens the outage budget. |
| `min.insync.replicas` | ≥ 2 with `replication.factor` ≥ 3 in production | With `acks=all` this is what makes the ack mean "durable". |
| `message.max.bytes` | ≥ `fukuii.export.kafka.max-request-size` (16 MiB default) | A record above the broker limit cannot be published; fukuii halts rather than drop it. |
| `unclean.leader.election.enable` | `false` | An unclean election can lose acknowledged records, which the position has already passed. |

## 4. Producer settings (fixed by fukuii; not operator-tunable except where noted)

| Property | Value | Reason |
|----------|-------|--------|
| `acks` | `all` | the position advances only on a durable ack (FR-004) |
| `enable.idempotence` | `true` | broker-side dedup of producer retries |
| `max.in.flight.requests.per.connection` | `1` | one batch at a time; order is never reordered by a retry (idempotence would allow 5; 1 is chosen for simplicity, single-partition throughput is not the bottleneck) |
| `retries` | `Integer.MAX_VALUE` bounded by `delivery.timeout.ms` (operator: `delivery-timeout`, 120 s) | the drain loop treats a delivery timeout as "broker down": the position holds, records stay in the buffer |
| `compression.type` | `zstd` (operator: `compression`) | RLP diffs compress 3–5× |
| `max.request.size` | 16 MiB (operator: `max-request-size`) | §3 |
| `client.id` | `fukuii-ingest-<chainKey>` | per-chain attribution on the broker |
| headers | `schemaVersion=1`, `recordType=<0..3>`, `chainKey=<key>` | lets the indexer route without decoding the value |

## 5. Ordering and delivery guarantees

- **Per chain, total order**: record *i* is published before record *i+1*, and the broker writes them in that order
  (one partition, one in-flight request).
- **At-least-once**: after a restart or an unfence, fukuii resumes from the persisted position and may re-send records
  the broker already holds (DC-3). Consumers de-duplicate on `(chainKey, type, blockNumber, blockHash)`.
- **No gaps**: fukuii never skips a log entry. If it cannot describe a canonical change (bound reached, record pruned,
  below-finality retraction, oversize record) it stops exporting and says so (health), rather than publish a stream
  with a hole.
- **Explicit reorgs and finality**: never inferred from offsets, timestamps or depth; see `record-schema.md` §7.

## 6. What consumers may and may not assume

- MAY assume one record per Kafka message, value = `record-schema.md` §1 framing, key = `blockNumber` (8 bytes BE).
- MAY use Kafka offsets as their own position; MUST NOT use them to detect reorgs or order across chains.
- MUST NOT bootstrap state from the topic (snapshot first; 026 FR-001). The topic begins at the exporter's first
  position, not at genesis (OQ-5).
- MUST stop on an unknown version byte or an unknown `type` (FR-011).

## 7. Multi-tenancy, ACLs, encryption

Deployment concerns (spec Non-goals). The node passes `fukuii.export.kafka.properties` through to the producer for
`security.protocol`, `sasl.*`, `ssl.*`. The producer needs `WRITE` and `DESCRIBE` on its topic and `IDEMPOTENT_WRITE`
on the cluster.
