# CON-008: Checkpoint Distribution Server

**Status**: Proposed

**Date**: 2026-06-03

## Context

Fukuii can now sync to chain tip, and it ships a complete **checkpoint sync**
pipeline that lets a fresh node skip SNAP/fast sync entirely by importing a
pre-built state archive:

| Stage | Component | File |
|-------|-----------|------|
| Produce | `fukuii <chain> checkpoint export` | `CheckpointCli.scala`, `CheckpointExporter.scala` |
| Encode | `.checkpoint` / `.checkpoint.gz` binary format | `CheckpointArchive.scala` |
| Fetch | HTTP downloader (resumable) | `CheckpointDownloader.scala` |
| Import | atomic state load + mark SNAP done | `CheckpointImporter.scala` |
| Wire-up | startup resolution of file/URL | `SyncController.scala` (~L723-774) |

A node operator enables it with one config key (`src/main/resources/conf/base/sync.conf`):

```hocon
fukuii.sync.checkpoint-sync-url = "https://checkpoints.chipprbots.com/<chain>/latest.checkpoint.gz"
```

The comment for that key already anticipates this work: *"Operator-supplied
(chipprbots-hosted or ethpandaops-style infra)."* What does **not** exist yet
is the server that produces, hosts, and serves those archives. This ADR
designs that service for the `checkpoints.chipprbots.com` TLD.

### What is actually being served

A `.checkpoint` archive is a full state snapshot at one block:

- the block **header** (RLP) + **chain weight** (total difficulty),
- **every state-trie node** reachable from `header.stateRoot`,
- **every storage-trie node** for each non-empty account,
- **every referenced contract bytecode**,
- a trailing **CRC32** over the whole stream.

These are large, immutable, write-once blobs. Expect **single-digit to
low-tens of GiB** per archive for ETC / Mordor / Sepolia, and **substantially
larger** for ETH mainnet. (Exact sizes must be measured from a real export
before committing to a storage class — the archive stores raw trie nodes, so
it is larger than a flat-state snapshot like geth's.) A new archive is cut on
a schedule (e.g. weekly), so the workload is **large objects, low write rate,
high read fan-out** — a classic static-object CDN problem, not a database
problem.

### Constraints imposed by the existing client

The server design is fixed by what `CheckpointDownloader` already does — we
should host to the client we have, not change the client to fit the host:

1. **Plain HTTPS `GET`.** No auth headers, no signing on the request side.
2. **Resumable.** It sends `Range: bytes=N-` and expects `206 Partial
   Content`; if the server answers `200` it restarts from byte 0. The origin
   **must** honour byte ranges or multi-GiB resumes break.
3. **Follows redirects** (`HttpClient.Redirect.NORMAL`). So a stable vanity URL
   can `302` to a versioned object or a CDN/IPFS gateway — the client just
   follows it.
4. **Timeouts:** 30 s connect, 30 min per request. The origin must sustain a
   multi-GiB transfer inside 30 min, i.e. ≳ a few MiB/s to the slowest client;
   a CDN edge is what makes this comfortable.
5. **Integrity is CRC32 only.** That catches truncation/bit-rot, **not**
   tampering — a malicious archive can carry a valid CRC. `CheckpointImporter`
   additionally checks the embedded `chainId`, but it does **not** verify the
   imported block hash against any trusted reference. **Whoever controls the
   URL controls the entire imported state.** Transport authenticity (TLS) and
   an out-of-band integrity/authenticity channel are therefore part of the
   design, not optional polish.

## Decision

Host checkpoint archives as **versioned, immutable objects in a Google Cloud
Storage bucket, fronted by Cloud CDN behind an HTTPS load balancer mapped to
`checkpoints.chipprbots.com`**. Treat **IPFS as an optional integrity mirror**,
not the primary serving path.

### 1. Topology

```
                         checkpoints.chipprbots.com  (managed TLS cert)
                                      │
                          ┌───────────▼───────────┐
                          │  HTTPS Load Balancer   │
                          │      + Cloud CDN       │   <-- caches multi-GiB blobs at edge
                          └───────────┬───────────┘
                                      │ (cache miss)
                          ┌───────────▼───────────┐
                          │   GCS bucket (origin)  │   gs://chipprbots-checkpoints
                          │  versioned, immutable  │
                          └───────────▲───────────┘
                                      │ publish
        ┌─────────────────────────────┴───────────────────────┐
        │  Checkpoint builder (cron on a synced fukuii node)   │
        │  export → gzip → sha256 → upload → flip latest →     │
        │  update manifest → (optional) pin to IPFS            │
        └──────────────────────────────────────────────────────┘
```

### 2. URL / object layout

The node config holds a **single** URL per chain, so we need a stable entry
point plus immutable versioned objects:

```
gs://chipprbots-checkpoints/
  etc/
    21000000-0x1a2b3c.checkpoint.gz          # immutable, content-addressable name
    21070000-0x9f8e7d.checkpoint.gz
    latest.checkpoint.gz                     # 302 redirect object -> newest version
    manifest.json                            # machine-readable index (see §4)
    manifest.json.sig                        # detached signature over manifest.json
  mordor/ …
  sepolia/ …
  eth/ …
```

- **Versioned objects** are named `<blockNumber>-<shortBlockHash>.checkpoint.gz`
  and are **never overwritten** (object versioning + a retention policy
  enforce this). Long `Cache-Control: public, max-age=31536000, immutable`.
- **`latest`** is the operator-facing pointer. Implement it as a small object
  whose only job is to `302` to the current versioned object (or, simpler, as a
  CDN/LB URL-map rewrite). Short TTL (`max-age=300`) so a new publish is picked
  up quickly. Because the client follows redirects, `…/latest.checkpoint.gz`
  resolves transparently.
- **`manifest.json`** lists every available checkpoint with verification
  metadata; short TTL.

Recommended operator config:

```hocon
# Pin to a specific, independently-verified archive (preferred for production):
fukuii.sync.checkpoint-sync-url = "https://checkpoints.chipprbots.com/etc/21070000-0x9f8e7d.checkpoint.gz"

# Or track latest (convenient for dev / ephemeral nodes):
fukuii.sync.checkpoint-sync-url = "https://checkpoints.chipprbots.com/etc/latest.checkpoint.gz"
```

### 3. Why GCS + CDN over IPFS (primary path)

| Criterion | GCS + Cloud CDN | Public IPFS |
|-----------|-----------------|-------------|
| Plain HTTPS `GET` from existing client | ✅ native | ✅ via gateway |
| **HTTP `Range`/206 resume** (required) | ✅ guaranteed | ⚠️ inconsistent across public gateways for multi-GiB |
| Throughput / latency for multi-GiB | ✅ edge-cached, predictable | ⚠️ gateway-dependent, often slow |
| Stable URL under our TLD | ✅ | ⚠️ CID changes every export → still need a manifest/redirect anyway |
| Availability guarantee | ✅ SLA | ⚠️ must run/pay a pinning service → reintroduces a central SPOF |
| Integrity | CRC32 (in-file) + published SHA-256 + signed manifest | CID is integrity-by-construction |
| Operational complexity | low (gsutil + lifecycle) | higher (pinning, gateway ops) |

The decisive point: IPFS's headline benefit — **content-addressed integrity**
— is the one thing our archive format already provides cheaply (CRC32 in-file)
and that we will additionally publish as a signed SHA-256 manifest. To get
IPFS's other properties (availability, throughput, Range support) in
production you end up running a pinning service and a dedicated gateway, which
is a centralized component anyway — so you pay IPFS's operational cost without
escaping the centralization you were trying to avoid. GCS + CDN gives the
required Range semantics, an SLA, and a stable TLD URL with far less moving
parts.

**Cost note.** Egress dominates the bill (every node pulls multi-GiB). Two
levers worth evaluating before launch:
- **Cloud CDN** in front of the bucket collapses repeated edge reads and is
  strongly recommended regardless.
- **Cloudflare R2** (S3-compatible, **zero egress fees**, Range-capable) is the
  strongest pure-cost alternative to a GCS origin and can sit behind the same
  `checkpoints.chipprbots.com` Cloudflare zone. If projected egress is high,
  prefer R2 as the origin; the rest of this design (layout, manifest, signing,
  builder) is storage-agnostic.

### 4. Manifest + integrity / authenticity

Publish a per-chain `manifest.json` alongside the archives. Fukuii does **not**
consume it today (it just takes a URL), but it is essential for tooling,
human verification, and a future client enhancement (§7):

```json
{
  "network": "etc",
  "chainId": 61,
  "checkpoints": [
    {
      "blockNumber": 21070000,
      "blockHash": "0x9f8e7d…",
      "stateRoot": "0x…",
      "file": "21070000-0x9f8e7d.checkpoint.gz",
      "url": "https://checkpoints.chipprbots.com/etc/21070000-0x9f8e7d.checkpoint.gz",
      "sizeBytes": 9876543210,
      "sha256": "…",
      "gzip": true,
      "createdAt": "2026-06-03T00:00:00Z",
      "fukuiiVersion": "x.y.z"
    }
  ]
}
```

Defence in depth, because CRC32 ≠ authenticity:

1. **TLS only** on `checkpoints.chipprbots.com` (HSTS). Prevents on-path
   tampering of the transport.
2. **Detached signature** (`cosign` or GPG) over `manifest.json`, published as
   `manifest.json.sig`, signing key fingerprint documented in the repo. This is
   the authenticity anchor.
3. **The archive's block hash is published in the manifest** so operators can
   cross-check it against a block explorer or their own `bootstrap-checkpoints`
   entry **before** trusting the imported state.
4. **Recommended operator practice:** for a given chain, set
   `checkpoint-sync-url` *and* a matching `bootstrap-checkpoints` /
   `use-bootstrap-checkpoints` entry for the same block, so the trusted hash is
   already in config.

### 5. Build & publish pipeline

A scheduled job on a **synced fukuii node** (one of the `barad-dur` /
`gorgoroth` hosts, ideally a dedicated exporter replica so the export's
read-only trie walk doesn't contend with a serving node):

1. Pick a **finalized / reorg-safe** block:
   - post-merge chains (Sepolia, ETH): the finalized block;
   - PoW chains (ETC, Mordor): tip minus a safety margin (e.g. ≥ 50k blocks),
     or a known fork-activation block.
2. `fukuii <chain> checkpoint export --block <N> --output <N>-<hash>.checkpoint --gzip`
3. Compute `sha256`.
4. Upload the versioned object to the bucket (immutable, long cache header).
5. **Atomically** flip `latest` → new object and regenerate + re-sign
   `manifest.json` **last** (so a half-published state is never the advertised
   one).
6. (Optional) `ipfs add` / pin the archive and record the CID in the manifest.
7. Apply a **retention policy**: keep the last *N* per chain (e.g. 4), lifecycle
   the rest, to bound storage cost while preserving resume targets.

A template script and runbook live in `ops/checkpoint-server/`.

### 6. Security & operational hardening

- Bucket is **not** publicly writable; only the builder's service account has
  `objectAdmin`. Reads are public (these are public-network snapshots) via the
  CDN.
- **Object Versioning + retention/lock** so a published archive can't be
  silently mutated.
- CDN/LB access logs → existing Prometheus/Grafana stack (`ops/.../grafana`,
  `ops/.../prometheus`) for egress, hit-rate, and 4xx/5xx alerting.
- Document a **rotation/incident procedure**: if a bad archive ships, remove
  `latest`, publish a corrected one, rotate the signing key if compromise is
  suspected.

### 7. Recommended follow-up code change (out of scope for the server, tracked separately)

`CheckpointImporter` currently trusts the archive's block hash implicitly. A
small, high-value hardening: have `SyncController` (or the importer) **verify
the imported `blockHash` against a configured trusted reference** — reuse the
existing `bootstrap-checkpoints` for the same height — and refuse the import on
mismatch, falling through to SNAP. This turns the distribution server from a
"trusted" component into a "verifiable" one and removes the single largest risk
of hosting state centrally. Recommend filing this as its own consensus change.

## Consequences

**Positive**
- Fast bootstrap for any chain where SNAP-serving peers are scarce
  (post-merge Sepolia/ETH especially) using infrastructure we already run.
- Uses the client exactly as built — no protocol or client changes required to
  ship the server.
- Stable, branded, TLS-terminated URL under `checkpoints.chipprbots.com`;
  versioned + immutable objects make resumes and rollbacks safe.
- Egress controlled by CDN; cost path has a clear R2 escape hatch.

**Negative / risks**
- Centralized trust anchor: until the §7 client check lands, a node trusting
  our URL trusts us with its entire state. Mitigated by TLS + signed manifest +
  documented cross-check, but not eliminated.
- Egress cost scales with adoption; needs monitoring and possibly R2/Cloudflare.
- Builder must run against a synced node and pick reorg-safe blocks; a bad
  block choice ships a uselessly-old or unstable checkpoint.

**Neutral**
- IPFS remains available as an opt-in mirror; the manifest can carry CIDs so a
  future, more decentralized distribution mode is additive, not a rewrite.

## References

- `src/main/scala/com/chipprbots/ethereum/blockchain/checkpoint/` — full pipeline
- `src/main/resources/conf/base/sync.conf` — `checkpoint-sync-file` / `checkpoint-sync-url`
- [CON-002: Bootstrap Checkpoints](CON-002-bootstrap-checkpoints.md)
- [Checkpoint Service runbook](../../runbooks/checkpoint-service.md)
- `ops/checkpoint-server/` — build/publish runbook and template script
