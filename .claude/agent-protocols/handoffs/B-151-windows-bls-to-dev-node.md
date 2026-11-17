# B-151 handoff: Windows-capable BLS12-381 backend → dev-node (2026-11-17)

**From** cloud-retro (the 0.12.1 retro session). **To** dev-node, by the user's decision on 2026-11-17: dev-node
works B-151 through. Claim it before you start (`backlog claim B-151 --agent beacon --branch <yours>`); the tracker
item is open and unclaimed (fukuii-fc's claim from 2026-11-08 expired and was released in the retro).

## What and why

ETH-family nodes (mainnet, Sepolia, Hoodi, Ephemery, Platåberget) need the EIP-2537 BLS12-381 precompiles
(0x0b–0x11, Prague onward). fukuii runs them on `org.hyperledger.besu:bls12-381:1.0.0` (Matter Labs `eth_pairings`
through JNA), which ships **no Windows build**. Since B-135 (#1767) a node on a chain that needs the backend refuses
to start when the native library is missing, instead of returning a wrong precompile result: correct, but it means
**ETH chains do not run on Windows**. ETC is unaffected (no Prague precompiles). Windows is now in scope for users:
releases are published to Chocolatey (#1845).

The user's direction (2026-11-08): keep the refusal (B-135) **and** find a Windows backend.

## Where things stand

- `src/main/scala/com/chipprbots/ethereum/vm/NativeBackends.scala` — the backend seam: `BlsNative` (`enabled`,
  `perform`), `LibEthPairingsNative`, `blsOperation`, `blsAccepts`, `required(config)`, `selfCheck`, `probeBackend`.
  A second `BlsNative` implementation is the natural shape for a new backend.
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala` — the precompiles that call it.
- Tests: `src/test/scala/com/chipprbots/ethereum/vm/BLS12381PrecompileSpec.scala`, `BlsMsmGasSpec.scala`.
- **B-153 (#1764)** built a differential: production `eth_pairings` against Besu `gnark` (`besu-native gnark 2.1.0`,
  test scope, `Dependencies.blsDifferentialOracles`), 7,878 lines of vectors. Reuse it: a new backend must agree with
  both, byte for byte, on every vector, including invalid-input rejection (subgroup checks, field-element bounds,
  infinity encodings) and the MSM/pairing edge cases.
- `gnark` itself ships Linux and macOS binaries only, so it is an oracle, not the Windows answer.

## Candidates (from the item)

1. **supranational `blst` Java bindings** — Windows builds exist; widely used by consensus clients. Check: does it
   expose every EIP-2537 operation (G1/G2 add, MSM, pairing check, map-to-curve for fp and fp2) with the EIP's exact
   input encodings and error behaviour, or only the BLS-signature subset? Wrapping code may be needed.
2. **Pure-Java fallback** — no native dependency, slower. Gas is fixed by the EIP, so speed matters only for block
   import time; measure against the MSM worst cases.
3. Either way: select the backend per platform (or by a config key), keep `selfCheck` honest, keep the B-135 refusal
   for a platform with no working backend.

## How to work it (consensus-critical: CLAUDE.md protocol)

1. **beacon first**: impact analysis before code (ETH-only path; timestamp forks; `OsakaOpCodes`/`forTimestamp`).
2. Byte-identical against both oracles on the EIP-2537 vectors and the B-153 differential; add a Windows CI job (or a
   documented manual run on a Windows host) that runs `BLS12381PrecompileSpec` on the new backend.
3. `eye` validates; `wraith` for build issues. The dependency must come from Maven Central or a resolver CI can reach.
4. If no backend is byte-identical, stop and report to the user: the alternative they named is an "ETH not supported
   on Windows" release note.

## Done means

ETH chains start and import on Windows with the precompiles byte-identical to the reference; Linux/macOS unchanged;
the B-135 refusal still fires where no backend works; PR body carries `Backlog: B-151`.
