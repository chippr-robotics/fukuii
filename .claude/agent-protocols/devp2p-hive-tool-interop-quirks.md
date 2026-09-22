# devp2p / hive test-tool interop quirks

**Philosophy:** A hive simulator failure is a claim about fukuii's wire
behavior, not proof of it. The simulator is itself a third-party
implementation with its own assumptions and bugs. Decode the simulator's own
source and fukuii's server log for the SAME run before accepting a hive
failure description as a root cause — a plausible-sounding one-line summary
("responseBytes ignored") can be a paraphrase of the request echo, not the
actual failure text.

Used by: herald
Referenced by: none yet — promote references here as they accumulate.

---

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
