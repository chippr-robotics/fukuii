# RLPx Hello Regression Investigation

## Summary
- Validation run `ops/gorgoroth/logs/rplx-20251209-211024` shows every RLPx session breaking before STATUS exchange with `MessageDecoder$MalformedMessageError: Cannot decode Hello`.
- Our logs also show `COMPRESSION_POLICY` enabling Snappy on both directions before the remote hello is fully handled, followed by each peer emitting `SEND_MSG: type=Hello` again after the connection is already marked "FULLY ESTABLISHED".
- When this second hello is sent from the `handshaked` state it is serialized through `MessageCodec` (which compresses because both sides advertise p2pVersion ≥ 5), so the remote peer's `HelloCodec` tries to RLP-decode Snappy data and aborts the handshake.

## Evidence from the failed run
```
2025-12-10 03:11:06,605 INFO  [c.c.e.network.rlpx.MessageCodec] - COMPRESSION_POLICY: ... compressOutbound=true, expectInboundCompressed=true
2025-12-10 03:11:06,607 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - [RLPx] Connection FULLY ESTABLISHED with peer 172.25.0.11:30303, entering handshaked state
2025-12-10 03:11:06,614 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - SEND_MSG: peer=172.25.0.11:30303, type=Status, code=0x10, seqNum=1
2025-12-10 03:11:06,652 ERROR [o.a.p.a.OneForOneStrategy] - Cannot decode Hello
...
2025-12-10 03:11:21,950 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - [RLPx] Connection FULLY ESTABLISHED with peer 172.25.0.13:30303, entering handshaked state
2025-12-10 03:11:21,951 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - SEND_MSG: peer=172.25.0.13:30303, type=Hello, code=0x0, seqNum=0
2025-12-10 03:11:21,958 INFO  [c.c.e.n.rlpx.RLPxConnectionHandler] - SEND_MSG: peer=172.25.0.13:30303, type=Status, code=0x10, seqNum=1
2025-12-10 03:11:21,969 INFO  [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer ... Reason: Some other reason specific to a subprotocol
```
`SEND_MSG type=Hello` is emitted by `RLPxConnectionHandler.sendMessage` (see `src/main/scala/.../RLPxConnectionHandler.scala` lines 340-386), which means the message went through the Snappy-enabled `MessageCodec`. The receiving side is still in `awaitInitialHello`, so it crashes before handshake completion, matching the stack trace rooted at `HelloCodec.extractHello` (lines 575-609 of the same file).

## Current Fukuii implementation
- `markHelloAckReceived` (lines 91-119) flips `helloWriteAcknowledged` as soon as TCP acknowledges the outbound hello and eagerly calls `messageCodec.enableInboundCompression("hello-write-ack")` the moment a codec instance exists. This enables Snappy before both sides are guaranteed to have exchanged the plain-text hello frames.
- `registerMessageCodec` (lines 121-128) immediately enables inbound compression if that flag was pre-set, so the very first DEVp2p frame processed after the scaler enters `handshaked` mode is forced through Snappy.
- The generic `handshaked` receive block (lines 329-415) does not special-case `HelloEnc`, so any late-arriving `SendMessage(HelloEnc)` is serialized via `MessageCodec` instead of `HelloCodec`. Because `MessageCodec` compresses whenever both peers advertise p2p ≥ 5 (`CompressionPolicy.fromHandshake` in `MessageCodec.scala` lines 26-70), we end up sending a compressed hello.
- Actor-mailbox ordering makes this race observable: if the peer manager enqueues `SendMessage(HelloEnc)` roughly when the TCP selector is also delivering the remote hello frame, whichever arrives second at the actor will run either the `awaitInitialHello` or `handshaked` handler. We saw the second case in the logs.

## Core-Geth reference implementation
- Core-Geth never re-encodes hello through the Snappy pipeline. Their `rlpxTransport.doProtoHandshake` (see `p2p/transport.go` lines 134-153 in `etclabscore/core-geth`) writes our hello, waits for the remote hello via `readProtocolHandshake`, and only then calls `t.conn.SetSnappy(their.Version >= snappyProtocolVersion)`.
- `rlpx.Conn.SetSnappy` ( `p2p/rlpx/rlpx.go` lines 99-126 ) simply toggles the read/write buffers used during frame compression; it is invoked strictly after both sides complete the handshake, so hello/status exchanges happen uncompressed as mandated by devp2p.
- Because hello is only handled inside the transport handshake, there is no opportunity for a second hello to be sent via the Snappy path, and the receiver will never try to RLP-decode compressed data while still inside its `Hello` codec.

## Root cause and remediation ideas
1. **Compressed hello leak** – The actor currently uses the same `SendMessage` entry point for both handshake and post-handshake traffic. As soon as the state machine flips to `handshaked`, any residual `SendMessage(HelloEnc)` in the mailbox is serialized via `MessageCodec`, which compresses it. This violates devp2p (hello must be plain RLP) and causes the remote side to throw `Cannot decode Hello`. We should always route `HelloEnc` through `HelloCodec.writeHello`, even if the actor has already transitioned, or ensure the hello send happens synchronously before we process any inbound frames.
2. **Over-eager compression** – `CompressionPolicy` sets `expectInboundCompressed=true` immediately whenever both peers advertise p2p ≥ 5. In contrast, Core-Geth only begins decompressing after `readProtocolHandshake` succeeds, i.e., after `hello` exchange completes. We should defer enabling inbound compression until after we have *both* sent and received hello (and ideally status), or at least until we observe the remote `Hello` frame being parsed.

## Next steps
- Modify `RLPxConnectionHandler.handshaked` to intercept `SendMessage(h: HelloEnc)` and delegate to `HelloCodec` so hello is never compressed. This will also make the log noise (`SEND_MSG ... type=Hello`) disappear after the connection is fully established.
- Gate `CompressionPolicy.enableInboundCompression` on an explicit "hello exchange completed" signal (both write acknowledged *and* remote hello parsed) to mirror Core-Geth's `SetSnappy` timing.
- Re-run the Gorgoroth validation after applying the above fixes and confirm that hello decoding succeeds and peers progress into the ETC handshake/status stages.
