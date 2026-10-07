# devp2p / hive test-tool interop quirks

**Philosophy:** A hive simulator failure is a claim about fukuii's wire
behavior, not proof of it. The simulator is itself a third-party
implementation with its own assumptions and bugs. Decode the simulator's own
source and fukuii's server log for the SAME run before accepting a hive
failure description as a root cause — a plausible-sounding one-line summary
("responseBytes ignored") can be a paraphrase of the request echo, not the
actual failure text.

Used by: herald, conduit
Referenced by: none yet — promote references here as they accumulate.

---

## Pattern: hive `graphql` simulator `07_eth_gasPrice` fixture predates the chain's EIP-1559 extension

**Symptom:** `{ gasPrice }` against hive's `simulators/ethereum/graphql` chain
returns a value in the hundreds-of-millions-of-wei range (fukuii: `0x3437004b`
= 876,019,787); `testcases/07_eth_gasPrice.json` accepts only `0x10` (16) or
`0x1` (1) wei. This is the sole failure in an otherwise-clean run (51/52 as of
2026-09).

**Root cause:** the fixture is stale, not the client. `graphql.go`'s own
comment admits it: "The chain has originated from the Besu client. It
consisted of Frontier blocks. It has been since extended with post-merge
blocks." `init/testGenesis.json` sets `londonBlock: 33`; the chain has 35
blocks (0-34), so head (block 34) is EIP-1559-active with a real `baseFee`.
Confirmed three independent ways against the actual fixture set (not just the
one hive artifact in hand):
1. Direct RLP decode of `init/testBlockchain.blocks`, block 34 header field
   15 (`baseFee`) = `0x3437004a` = 876,019,786.
2. Sibling fixture `51_eth_getBlock_4844.json` (`block(number: 34) {
   baseFeePerGas }`) independently pins the SAME value.
3. Sibling fixture `01_eth_blockNumber.json` pins head = `0x22` = 34 (not the
   last pre-London block, 32) — so head really is the EIP-1559 block, not an
   artifact of a client importing further than intended.

Current go-ethereum's own resolver (`graphql/graphql.go:
Resolver.GasPrice`) is `tipcap + head.BaseFee` when `head.BaseFee != nil` —
no conforming implementation of that formula, fukuii's or a freshly-run
geth's, can land on 16 or 1 wei once baseFee is ~876M. The fixture's two
accepted values were authored against the chain's original (pre-extension,
Frontier-only, no-baseFee) state and never regenerated after blocks 33-34
were appended.

**Diagnosis checklist (don't redo the RLP decode from scratch — this has now
been independently re-derived 3+ times in the same session across separate
commits):**
1. Check `init/testGenesis.json`'s fork-activation blocks against the actual
   head block number (`01_eth_blockNumber.json` or the client's own import
   log — `"Chain import: N imported"`) to see if the head postdates a fork
   the failing fixture predates.
2. Cross-check against ANY sibling fixture that independently pins a fact
   about the same head block (here, `51_eth_getBlock_4844.json`'s
   `baseFeePerGas`) — if two fixtures in the same file set are mutually
   contradictory under the reference client's OWN current formula, the newer
   ground truth (the field-specific fixture, corroborated by an RLP decode)
   wins over the older/coarser one (`07_eth_gasPrice.json`).
3. Pull current upstream source for the resolver in question
   (`graphql/graphql.go`, `eth/gasprice/oracle.go`) — don't assume the
   fixture encodes the CURRENT reference formula; fixtures get created once
   and chains get extended later without regenerating every dependent case.

**Do not "fix" this by changing fukuii's gas price oracle.**
`EthTxService.minimumGasPrice()`/`suggestGasPrice()` (shared by
`eth_gasPrice` and the GraphQL `gasPrice` field, `GraphQLSchema.scala`) already
implement the same `baseFee + tip` shape as current go-ethereum; matching the
stale fixture would require literally ignoring `baseFee`, which would be a
real EIP-1559 regression on every live ETH/Sepolia chain. Recorded as a
known-permanent hive-side floor (`GasPriceOracleSpec`, the pin test tagged
`RPCTest`); `specs/009-amsterdam-fork-support/plan.md` tracks it as a
non-regression oracle that must not move.

## Pattern: hardcoded protocol-multiplexing offsets in `cmd/devp2p`

**Symptom:** Every hive `snap` (or any `eth`+subprotocol) request that
requires real trie/DB work times out (`i/o timeout`) on the client side, but
trivial fast-path requests (empty input → immediate empty response) succeed.
Server-side log shows either:
- `DECODE_ERROR: Cannot decode <WrongMessageType>. Expected RLPList[N]...`
  where `<WrongMessageType>` is a DIFFERENT message in the same protocol
  family than what was actually sent, or
- `Unknown <protocol> message type: <N>` for a wire id with no registered
  decoder.

**Root cause:** go-ethereum's `cmd/devp2p/internal/ethtest` package
(`protocol.go`) does NOT use the real devp2p multiplexing algorithm
(`p2p/peer.go: matchProtocols`, which sizes each subprotocol's reserved
wire-id slots per the ACTUAL NEGOTIATED VERSION via
`protocolLengths[version]`). Instead it hardcodes fixed slot counts
(`baseProtoLen`, `ethProtoLen`, `snapProtoLen`) sized for the tool's OWN
highest offered version of each subprotocol, with an explicit comment
admitting the shortcut: "assuming the negotiated capabilities are exactly
{eth,snap}". This holds only when the SUT negotiates the SAME highest
version the tool offers. Any SUT that negotiates a lower version of a
subprotocol whose real go-ethereum `protocolLengths` entry differs from the
tool's hardcoded constant will have every message in every LATER
alphabetically-sorted subprotocol shifted by the size difference.

Confirmed concretely 2026-09-22: go-ethereum's real
`eth/protocols/eth/protocol.go: protocolLengths = {69:18, 70:18, 71:20,
72:22}`, but `cmd/devp2p/internal/ethtest/protocol.go: ethProtoLen = 22`
(fixed). A SUT (fukuii) negotiating eth/69 (Length 18, because it doesn't
yet advertise eth/70+) computes `snapBase = 0x22`; the tool's own wire
writes/reads for `snap` assume `0x26`. A 4-slot shift misroutes every real
snap message onto the wrong canonical slot (e.g. `GetAccountRange` →
decoded as `GetByteCodes`).

**Diagnosis checklist:**
1. Pull BOTH sides' logs for the same run: the simulator/tool's client-side
   detail log AND the SUT's own server log. A one-sided read is not enough —
   the "last line printed" in a failure block is often just the pretty-printed
   request echo, not the actual defect.
2. Grep the SUT's server log for `DECODE_ERROR` / `Unknown message type`
   around the SAME timestamps/ports as the failing sub-tests. A message
   decoded as the WRONG type in the SAME protocol family, or landing on an
   unregistered wire id, is the signature of an offset shift — not a logic
   bug in the handler that was (mis-)invoked.
3. Fetch the CURRENT real upstream source for BOTH the multiplexing algorithm
   (`p2p/peer.go`) and the protocol's real length table
   (`eth/protocols/<proto>/protocol.go: protocolLengths`), then compare
   against the test tool's own hardcoded assumption
   (`cmd/devp2p/internal/ethtest/protocol.go`). Don't assume the test tool's
   assumption is authoritative just because it's shipped by the reference
   client org — it's a CLI helper, not the reference server implementation.
4. If the SUT's own offset computation already matches the real
   `protocolLengths` table for the negotiated version (and has regression
   coverage proving it), the SUT is compliant and this is a test-tool
   limitation, not a defect to fix. Matching the test tool's fixed
   assumption instead would break real interop with any compliant peer at
   the SUT's actual negotiated version — do not do this.
5. The only real fix on the SUT side is to negotiate the SAME version the
   tool assumes (i.e. implement whatever later subprotocol version has the
   matching `protocolLengths` entry). Treat that as a scoped, separate
   decision — it is a real protocol-version implementation, not a
   multiplexing fix.

**Where fukuii already gets this right:** `RLPxConnectionHandler.scala`
(`ethWireSizeFor`, `computeCapabilityOffsets`) computes offsets per the
ACTUAL negotiated eth version, matching real go-ethereum's
`protocolLengths`-driven `matchProtocols`. See
`RLPxCapabilityOffsetsSpec.scala` for the regression coverage, including a
test pinning the exact hive devp2p CLI tool peer shape.
