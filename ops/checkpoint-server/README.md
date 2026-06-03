# Checkpoint Distribution Server

Operational runbook for producing and serving `.checkpoint` archives at
`checkpoints.chipprbots.com`, so fresh fukuii nodes can bootstrap a full state
without running SNAP/fast sync.

> Design rationale and the chosen topology (GCS + Cloud CDN, IPFS as an
> optional mirror) are in
> [`docs/adr/consensus/CON-008-checkpoint-distribution-server.md`](../../docs/adr/consensus/CON-008-checkpoint-distribution-server.md).
> Node-operator usage of the checkpoint feature is in
> [`docs/runbooks/checkpoint-service.md`](../../docs/runbooks/checkpoint-service.md).

## What this serves

Large, immutable, write-once binary archives (full state trie + bytecodes +
header + chain weight) produced by `fukuii <chain> checkpoint export`. Nodes
fetch them over plain HTTPS `GET` via the `checkpoint-sync-url` config key. The
client (`CheckpointDownloader`) **requires HTTP `Range` support** for resumable
multi-GiB downloads and **follows redirects**, so a stable `latest` URL can
`302` to a versioned object.

## Bucket / URL layout

```
gs://chipprbots-checkpoints/<chain>/
    <blockNumber>-<shortHash>.checkpoint.gz   # immutable, long-cache
    latest.checkpoint.gz                      # 302 -> newest, short-cache
    manifest.json                             # index + sha256 + blockHash
    manifest.json.sig                         # detached signature
```

`<chain>` is one of `etc`, `mordor`, `sepolia`, `eth` (matching the launcher
chain selector).

## One-time infrastructure setup

1. **Bucket** (origin): create `gs://chipprbots-checkpoints`, enable Object
   Versioning, public read, builder service account = `objectAdmin` only.
2. **HTTPS LB + Cloud CDN**: backend bucket = the GCS bucket, managed TLS cert
   for `checkpoints.chipprbots.com`, CDN enabled. (Or front with Cloudflare;
   for high egress prefer a Cloudflare **R2** origin — zero egress fees — with
   the same layout.)
3. **DNS**: `checkpoints.chipprbots.com` A/AAAA → LB IP (or Cloudflare proxied).
4. **Cache policy**: versioned objects `Cache-Control: public, max-age=31536000,
   immutable`; `latest.*` and `manifest.*` `max-age=300`.
5. **Signing key**: generate a `cosign`/GPG key for the manifest; publish the
   public key fingerprint in the repo.

## Publishing a checkpoint

Run on a **synced** fukuii node (preferably a dedicated exporter replica).
Pick a **reorg-safe** block — finalized on post-merge chains, tip − margin on
PoW chains.

```bash
ops/checkpoint-server/publish-checkpoint.sh \
  --chain etc \
  --block 21070000 \
  --bucket gs://chipprbots-checkpoints \
  --datadir /var/lib/fukuii/etc
```

The script: exports + gzips, computes sha256, reads the block hash, uploads the
immutable object, regenerates and re-signs `manifest.json`, flips `latest`
last, and prunes old versions. See `--help` and inline comments. It is a
**template** — wire it into your secret/identity story before production use.

## Verification (operators & maintainers)

```bash
# 1. Fetch and check the signed manifest
curl -fsSL https://checkpoints.chipprbots.com/etc/manifest.json -o manifest.json
cosign verify-blob --key checkpoints.pub --signature \
  <(curl -fsSL https://checkpoints.chipprbots.com/etc/manifest.json.sig) manifest.json

# 2. Cross-check the checkpoint block hash against an independent source
#    (block explorer, your own synced node) BEFORE trusting the archive.

# 3. Verify the download against the manifest sha256
sha256sum etc-21070000.checkpoint.gz   # compare to manifest entry
```

For production nodes, set both keys for the same block so the trusted hash is
in config:

```hocon
fukuii.sync.checkpoint-sync-url = "https://checkpoints.chipprbots.com/etc/21070000-0x9f8e7d.checkpoint.gz"
fukuii.blockchain.use-bootstrap-checkpoints = true
fukuii.blockchain.bootstrap-checkpoints = ["21070000:0x9f8e7d…"]
```

## Monitoring

Wire CDN/LB access logs into the existing Prometheus/Grafana stack
(`ops/barad-dur/prometheus`, `ops/barad-dur/grafana`). Alert on: 5xx rate,
egress GiB/day, cache hit-rate dropping (origin overload), and publish-job
failures.

## Incident / rotation

- **Bad archive shipped:** remove/redirect `latest`, publish a corrected
  versioned object, regenerate + re-sign the manifest. Versioned objects are
  immutable, so old (good) versions remain valid resume targets.
- **Signing key compromise:** rotate the key, re-sign all manifests, update the
  published fingerprint, and announce.
