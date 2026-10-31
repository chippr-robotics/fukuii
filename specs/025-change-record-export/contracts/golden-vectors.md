# Contract: Golden-Bytes Vectors

**Spec**: [../spec.md](../spec.md) GT-1, FR-011, SC-005 | **Schema**: [record-schema.md](record-schema.md)

## 1. Purpose

One vector set pins the bytes of every record type so that (a) this spec's codec tests, (b) spec 018 F2's changeset
tests and (c) any external consumer's decoder agree byte for byte (SC-005: "the vectors are identical files"). A change
to any reused encoder (`BlockHeaderEnc`, `ReceiptCodecs`, `AccountEnc`, `rlpBigIntSerializer`) that alters these bytes
fails the test and forces a schema-version decision instead of a silent format drift.

## 2. Location

```
src/test/resources/export/golden/v1/
├── manifest.json
├── block-empty.hex              # empty block: no txs, no state change (ETC post-reward-removal shape? no: ETC pays
│                                #   a reward, so this one is ETH-family: header only, accounts = [], code = [])
├── block-etc-reward.hex         # ETC block, one coinbase balance change (prev absent → new), no txs
├── block-transfer.hex           # one value transfer: sender nonce+balance, recipient balance, coinbase
├── block-contract-create.hex    # contract creation: new account with codeHash, code entry, two slots written
├── block-storage-update.hex     # existing contract: one slot changed, one slot cleared (newValue ""), one unchanged
│                                #   slot absent (netting)
├── block-selfdestruct.hex       # ETC-style destruction: newAccount "", all previous slots listed with newValue ""
├── block-typed-receipts.hex     # type 0x02 and 0x03 receipts with logs (ETH family)
├── retraction.hex
├── finality-genesis.hex         # ETC genesis watermark
├── finality-eth.hex             # ETH-family watermark at a non-zero height
└── witness-reserved.hex         # type 3 with witnessFormat 0 and 4 opaque bytes
```

Each `.hex` file is lowercase hex of the complete message value (version byte included), one line, no `0x` prefix, no
trailing newline. Spec 018's tests read these files by the same path (a symlink or a shared test-resources module is a
build detail decided when 018 F2 lands; the files are not copied).

## 3. Manifest

`manifest.json` lists, per file: `name`, `type`, `chainKey`, `chainId`, `blockNumber`, `blockHash`, `parentHash`, a
`description`, the **source** of the bytes (`"constructed"` for the hand-built cases; `"replayed"` plus the fixture
name for records produced by executing a fixture block through the real import path), and the decoded JSON view of the
body so a reviewer can read what the bytes mean without a decoder. Example entry:

```json
{
  "name": "block-storage-update",
  "type": 0,
  "chainKey": "mordor",
  "chainId": 63,
  "blockNumber": 1234,
  "blockHash": "0x…",
  "parentHash": "0x…",
  "source": { "kind": "replayed", "fixture": "ReorgFixtures.storageUpdate" },
  "description": "one slot changed, one slot cleared, one unchanged slot netted out",
  "body": { "accounts": [ { "address": "0x…",
                            "slots": [ { "slotKey": "0x…01", "prev": "0x05", "new": "0x07" }, … ] } ] }
}
```

## 4. How the vectors are produced

- Hand-constructed cases are built in `GoldenVectorsSpec` from literal field values (no execution), encoded with the
  production codec, and **compared** with the file; a mismatch fails. The spec has a `-Dexport.golden.write=true`
  mode that writes the files instead, used once when the schema is frozen and again only with a version bump.
- Replayed cases execute a fixture block through `BlockExecution` on an `EphemDataSource` chain (`ReorgFixtures`) with
  the recorder on, and compare the recorder's output with the file. They prove the recorder and the codec agree with
  the hand-written expectation of what a block changes.
- Every vector round-trips: `decode(encode(x)) == x` and `encode(decode(bytes)) == bytes` (canonical encoding; no two
  encodings of one record).

## 5. Property tests beside the vectors

`RecordCodecPropertySpec` (ScalaCheck, `scalacheck-1-18`, `project/Dependencies.scala:69-72`) generates arbitrary
records (random accounts, slots, code, receipts of every type, all four record types, `chainId` up to 2^64) and checks
round-trip, canonical sort order of entries, netting (an entry with `prev == new` is rejected by the builder), and that
the version byte is byte 0. These complement the vectors; they do not replace them.

## 6. Freeze procedure (GT-1)

1. This file, `record-schema.md` and `data-model.md` §1–§5 are reviewed by `vault` against 018 F2's draft
   `data-model.md`; differences are resolved in **this** contract (it is the shared one).
2. The vectors are generated (§4) and committed with the codec in the same PR (slice 0 in `plan.md`).
3. 018 F2's tests are pointed at the same files. From then on, a change to any file here is a version bump.
