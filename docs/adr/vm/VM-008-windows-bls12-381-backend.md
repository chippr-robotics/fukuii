# VM-008: A Windows-capable BLS12-381 backend for the EIP-2537 precompiles (B-151)

**Status**: Proposed (2026-11-26). Impact analysis written before any code, per the consensus-change protocol.

**Backlog**: B-151. **Related**: B-135 (#1767, the missing-library refusal), B-153 (#1764, the EIP-2537 differential).

## Context

The EIP-2537 precompiles (0x0b-0x11: G1ADD, G1MSM, G2ADD, G2MSM, PAIRING_CHECK, MAP_FP_TO_G1, MAP_FP2_TO_G2) run on
Matter Labs' `eth_pairings` through `org.hyperledger.besu:bls12-381:1.0.0` (JNA). That artifact ships Linux and macOS
binaries only. Since B-135 a node whose fork schedule needs the precompiles refuses to start without the library, so
no ETH-family chain (mainnet, Sepolia, Hoodi, Ephemery, Platåberget) runs on Windows. ETC is affected only from Olympia
(ECIP-1121), which takes the Osaka precompile set.

Fork position: the precompiles are enabled from ETH Prague (timestamp, `forTimestamp()`), stay in Osaka and
Amsterdam, and from ETC Olympia (block). Their semantics are one EIP, the same on every chain; this change touches
which library computes them, not when they are active. `NativeBackends.required` is unchanged.

## Candidate survey

| Candidate | Every EIP-2537 operation? | Windows x86_64 natives on a resolver CI uses? | Licence |
|---|---|---|---|
| `org.hyperledger.besu:bls12-381:1.0.0` (eth_pairings, today) | Yes, the EIP's own ABI | No (Linux, macOS) | Apache-2.0 |
| `org.hyperledger.besu:gnark` 2.1.0 / 2.2.0 (Besu's backend, our oracle) | Yes, the EIP's own ABI | No (Linux, macOS; jfrog) | Apache-2.0 |
| `org.hyperledger.besu:constantine` 2.2.0 | Yes | No (Linux, macOS; jfrog) | Apache-2.0 / MIT |
| `tech.pegasys:jblst:0.3.18` (supranational blst, SWIG Java) | Partly, see below | **Yes**: `Windows/amd64/blst.dll`, plus Linux amd64/aarch64, macOS x86_64/aarch64; Maven Central | Apache-2.0 (blst: Apache-2.0) |
| Pure JVM (Milagro AMCL Java and similar) | No map-to-curve for raw field elements; pairing in `BigInteger` is too slow for block import | n/a | Apache-2.0 |

What jblst's Java API gives, and what it does not:

- **Has**: G1/G2 point decoding (on-curve check, field bound check), subgroup check (`in_group`), complete addition
  (`add` is `blst_p1_add_or_double`), scalar multiplication (`mult(BigInteger)`), Miller loop (`PT(P1_Affine,
  P2_Affine)`), `PT.mul`, `final_exp`, `is_one`.
- **Lacks**: multi-scalar multiplication (no Pippenger in the Java API) and **map-to-curve of a raw field element**.
  The Java API only offers `hash_to` / `encode_to`, which hash a message to the field first, so `MAP_FP_TO_G1` and
  `MAP_FP2_TO_G2` cannot be reached through it.
- The C API has both (`blst_p1s_mult_pippenger`, `blst_map_to_g1`), and the Linux `.so` exports it, but the Windows
  `blst.dll` exports only the `Java_supranational_blst_*` JNI symbols (checked with `objdump -p`). Calling the C API
  through JNA would work on Linux and fail on exactly the platform this is for, so it is not an option.

No candidate on Maven Central or the Besu resolver ships a Windows build with the full EIP-2537 ABI. jblst is the
only Windows-capable native, and it covers everything except MSM and the map.

## Decision

A second `BlsNative`, `BlstNative`, built on jblst 0.3.18:

- **Encoding (Scala)**: the EIP-2537 codec, done before blst sees a byte. A 64-byte field element must have 16 zero
  top bytes and a value below p. All-zero is the point at infinity; any other point must satisfy the curve equation,
  checked in Scala as `Eip2537Reasons` already does. The point is then handed to blst in its own serialisation.
- **Group operations (blst)**: G1ADD and G2ADD use blst addition with no subgroup check, as the EIP says. MSM checks
  each point's subgroup (`in_group`). It then sums `mult(scalar)` over the pairs, skipping zero scalars and the point
  at infinity. Scalars are not reduced, and need not be: every point is in the subgroup. The pairing check checks
  every point's subgroup and multiplies the Miller loops of the pairs with no point at infinity. It applies one final
  exponentiation and returns `is_one`, and an input whose every pair holds a point at infinity is 1.
- **Map to curve (Scala, then blst)**: simplified SWU to the isogenous curve and the 11-isogeny (G1) or 3-isogeny
  (G2), straight from RFC 9380 sections 6.6.2 and 8.8, appendices E.2 and E.3, with `sgn0(y) = sgn0(u)` fixing the root.
  The output is unique whichever square root is computed. Cofactor clearing is blst `mult(h_eff)` with the RFC's h_eff,
  and that path is generic: blst uses its GLV/GLS endomorphisms only for scalars of 144/176 to 256 bits. The SWU
  output is not in the subgroup, so GLV/GLS would be wrong for it; h_eff is 64 bits (G1) or 636 bits (G2), outside
  that range.
- **Selection**: the config key `fukuii.vm.bls12-381-backend` = `auto` (default) | `eth-pairings` | `blst`, overridable
  by `FUKUII_BLS12381_BACKEND`. `auto` keeps eth_pairings wherever it loads, so Linux and macOS are unchanged, and
  uses blst where it does not (Windows). `Fukuii.main` applies the key before the self-check.
- **B-135 stays honest**: `selfCheck` and `probeBackend` probe the *selected* backend through the path its precompiles
  use. If neither library loads under `auto`, the probe of blst fails and the node refuses to start, as today. A
  forced backend that does not load is refused the same way. A blst exception that is not a rejected input is a
  backend failure (`NativeBackendUnavailable`), never a precompile result.

### byte-exact traps (recorded here so the next reader does not rediscover them)

- blst serialises an Fp2 element as `c1 || c0`; EIP-2537 is `c0 || c1`. Every G2 coordinate is swapped both ways.
- blst's point at infinity is the uncompressed encoding with flag `0x40`; EIP-2537's is all zeros. blst *rejects*
  (0, 0) as not on the curve, so the codec maps zeros to the flag before decoding and the flag to zeros after.
- `mult(BigInteger)` mutates the receiver and rejects a negative value; `add`, `neg` and `final_exp` also mutate.

## Consequences and risks

- **Consensus divergence** is the risk. The bar is byte identity with both oracles (eth_pairings and gnark) on every
  vector of the B-153 corpus. That corpus holds the published EIP-2537 and go-ethereum vectors, the generated edge
  cases (field bounds, infinity encodings, off-curve and off-subgroup points, MSM and pairing edge cases) and the
  seeded fuzz, including every invalid-input rejection. The differential runs blst as a third backend on Linux CI.
- **Speed**: MSM without Pippenger, and the map in `BigInteger` arithmetic, are slower than eth_pairings. Gas is fixed
  by the EIP, so this costs block-import time on Windows only. It is measured, and the numbers are in the PR.
- **Windows evidence**: Linux CI cannot load the Windows DLL. A `windows-latest` job runs the BLS specs with
  `FUKUII_BLS12381_BACKEND=blst`; it is informational until it has a green history.
- **Dependency**: jblst brings Guava 18 as a runtime dependency, which the build's newer Guava evicts. Teku maintains
  jblst, on supranational blst, which is audited.
- **ETC**: an ETC node on Windows past Olympia uses the same backend. The precompiles are the same EIP on both
  chains, so no ETC-specific rule changes.

## Alternatives rejected

- Shipping our own Windows build of eth_pairings or blst: a native toolchain and signing in our release pipeline,
  and an artifact no other client runs.
- Pure-JVM pairing: correct but far too slow for import at the pairing gas price.
- "ETH not supported on Windows" release note: the user's fallback, kept in reserve if byte identity fails.
