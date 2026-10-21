# Contract: published library artifacts

**Scope**: the five projects with `publishSettings` (`build.sbt` lines 143, 172, 201, 218, 236). Organisation
`com.chipprbots`, artifact suffix `_3`.

| sbt project | Artifact | Consensus-relevant |
|---|---|---|
| `bytes` | `fukuii-bytes_3` | indirectly (byte utilities) |
| `crypto` | `fukuii-crypto_3` | yes (signatures, hashing) |
| `rlp` | `fukuii-rlp_3` | **yes** (wire and trie encoding) |
| `scalanet` | `fukuii-scalanet_3` | no (P2P transport) |
| `scalanetDiscovery` | `fukuii-scalanet-discovery_3` | no (discovery) |

## Before (≤ 0.9.x)

- Built by Scala 3.3.8, so the TASTy is the 3.3 format. Any Scala ≥ 3.3 consumer can read it.
- `crossScalaVersions = List("3.3.8")`.

## After (≥ 0.10.0)

| Clause | Guarantee |
|---|---|
| C-1 Compiler | All five are built by the same Scala 3.9.x as the node, in one release (DC-7). No cross-build. |
| C-2 TASTy | 3.9 TASTy. **Consumers on Scala < 3.9 cannot use them.** This is a breaking change, flagged in the release notes (R-7). |
| C-3 Bytecode | Targets the build's JDK (25). No change in JDK target. |
| C-4 Behaviour | Every encode/decode, hash and signature result is byte-identical to 0.9.x. Checked by the existing `rlp`/`bytes`/`crypto` test suites (run by `testEssential`) and by the import differential (research R-4). |
| C-5 Source API | Unchanged except `fukuii-rlp`: the anonymous `given RLPEncoder[X]` / `given RLPDecoder[X]` duplicates in `RLPImplicits` are removed (DC-3, R-5). The named `xEncDec` givens remain, so `import RLPImplicits.{given, *}` still resolves every codec. P3b (S4 scope, `src/main/` only) does not touch the published modules; their `implicit class` sites stay, legal under `-source:3.9`. |
| C-6 Version | MINOR bump (0.9.x → 0.10.0) per `.github/VERSIONING.md` rule 2. |

## Verification

- `sbt "bytes/publishLocal; crypto/publishLocal; rlp/publishLocal; scalanet/publishLocal; scalanetDiscovery/publishLocal"`
  succeeds on the final tree. The resulting POMs name `scala3-library_3` at the 3.9.x version.
- No workflow runs `sbt publish` today (GT-1 §6). The contract applies to whoever publishes (manual, or a future
  workflow).
