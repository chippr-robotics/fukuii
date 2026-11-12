# Contract: `release.json` (spec 028)

Metadata for one validated Ephemery release. It is written:
- in the node's cache, at `<root>/releases/ephemery-N/release.json`;
- in the bundle, at `classpath:ephemery/bundled/ephemery-N/release.json`;
- by `ephemery-fetch --out`, at `DIR/ephemery-N/release.json`, next to `release.env`.

Its purpose is to record the facts the resolver needs without re-deriving them. It is **never trusted alone**: on load
the entry's `chains/` files are re-validated, and the computed genesis hash must equal `genesisHash` (data-model.md).

```json
{
  "schema": 1,
  "release": "ephemery-165",
  "iteration": 165,
  "chainId": "39438165",
  "genesisTimestamp": 1792695600,
  "resetInterval": 2419200,
  "resetTime": 1795114800,
  "genesisHash": "0x9485bbe8e32e278194666d249590c9167057edeabaecc62e7a68355f68305ecc",
  "source": "https://github.com/ephemery-testnet/ephemery-genesis/releases/tag/ephemery-165",
  "archiveSha256": "<hex of testnet-all.tar.gz, or null for a --from-dir source>",
  "producedBy": "fukuii <version>",
  "producedAt": "2026-11-12T00:00:00Z"
}
```

| Field | Rule |
|-------|------|
| `schema` | 1. An unknown value makes the entry invisible: logged, not deleted |
| `release` | `^ephemery-[0-9]+$`, and equal to `"ephemery-" + iteration` |
| `iteration` | `== chainId - 39438000` |
| `chainId` | decimal string, to avoid JSON number precision loss |
| `genesisTimestamp`, `resetInterval`, `resetTime` | `resetTime == genesisTimestamp + resetInterval` |
| `genesisHash` | `0x` + 64 lowercase hex; equal to the published hash at validation and to the recomputed hash at load |
| `source` | the release **page** URL, or the absolute local path. Never a redirect or asset URL, because those carry signed queries |
| `archiveSha256` | informational, for operators comparing copies. The tarball is not signed, so this proves nothing about authenticity (spec Assumptions) |
| `producedBy`, `producedAt` | informational |

`release.env`, today's script output, keeps its keys for the `--out` layout (FR-015): `ITERATION`, `RELEASE`,
`CHAIN_ID`, `GENESIS_TIMESTAMP`, `RESET_TIME`, `GENESIS_HASH`, `OUT_DIR`, `TESTNET_DIR`, `CHAINS_DIR`,
`FUKUII_CONF_DIR`, `BOOTNODE_ENR_LIST`.
