# RLPx Handshake and Message Encoding Comparative Analysis

## Overview

This document provides a comprehensive comparative analysis of the RLPx handshake protocol and message encoding implementations across three Ethereum/ETC clients: Fukuii, Core-Geth, and Besu.

**Date**: 2025-12-04  
**Context**: Troubleshooting snapsync peer connection issues  
**Clients Analyzed**:
- **Fukuii**: Scala 3 implementation (Ethereum Classic client)
- **Core-Geth**: Go implementation (reference ETC client)
- **Besu**: Java implementation (Hyperledger Ethereum client)

## Executive Summary

### Key Findings

✅ **Fukuii's RLPx implementation is COMPATIBLE with Core-Geth and Besu**

The analysis reveals that all three clients follow the same RLPx specification with some implementation differences in error handling and compression fallback logic:

1. **Handshake Protocol**: All three clients correctly implement the ECIES-based RLPx handshake
2. **Message Framing**: Frame structure and encryption/authentication mechanisms are identical
3. **Snappy Compression**: All clients support Snappy compression for p2pVersion >= 5
4. **Critical Difference**: Fukuii has enhanced fallback logic for handling compression edge cases

### Potential Issue Areas

Based on the analysis, peer connection issues may stem from:
1. **Compression Protocol Deviations**: Some peers may advertise p2pVersion >= 5 but send uncompressed data
2. **Message Size Limits**: Different max decompressed size constraints
3. **Error Recovery**: Differences in how clients handle malformed or unexpected data

## RLPx Handshake Protocol

The RLPx handshake is a cryptographic ceremony that establishes a secure, encrypted connection between two Ethereum nodes.

### Protocol Specification

**Reference**: https://github.com/ethereum/devp2p/blob/master/rlpx.md

The handshake consists of:
1. **Auth** message (initiator → responder)
2. **Ack** message (responder → initiator)
3. **Shared secrets** derivation

Both parties derive:
- **AES key**: For message encryption
- **MAC key**: For message authentication
- **Ingress/Egress MACs**: For frame integrity

### Fukuii Implementation

**Location**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/AuthHandshaker.scala`

```scala
def initiate(uri: URI): (ByteString, AuthHandshaker) = {
  val remotePubKey = publicKeyFromNodeId(uri.getUserInfo)
  val message = createAuthInitiateMessageV4(remotePubKey)
  val encoded: Array[Byte] = message.toBytes
  val padded = encoded ++ randomBytes(Random.nextInt(MaxPadding - MinPadding) + MinPadding)
  val encryptedSize = padded.length + ECIESCoder.OverheadSize
  val sizePrefix = ByteBuffer.allocate(2).putShort(encryptedSize.toShort).array
  val encryptedPayload = ECIESCoder.encrypt(remotePubKey, secureRandom, padded, Some(sizePrefix))
  val packet = ByteString(sizePrefix ++ encryptedPayload)
  
  (packet, copy(isInitiator = true, initiatePacketOpt = Some(packet), remotePubKeyOpt = Some(remotePubKey)))
}
```

**Key Features**:
- Uses BouncyCastle for ECIES encryption
- Supports both v1 and v4 handshake messages
- Variable padding (100-300 bytes) for auth messages
- Size prefix in 2-byte big-endian format

### Core-Geth Implementation

**Location**: `p2p/rlpx/rlpx.go`

```go
// Core-Geth uses the same ECIES encryption scheme
// Handshake is performed via p2p/Server and p2p/peer
```

**Key Features**:
- Uses Go's crypto/ecies package
- Standard ECIES encryption with AES-128-CTR
- Keccak-256 for MAC calculations
- Compatible size prefix format

### Besu Implementation

**Location**: `ethereum/p2p/src/main/java/.../rlpx/handshake/ecies/ECIESHandshaker.java`

```java
@Override
public ByteBuf firstMessage() throws HandshakeException {
  final Bytes32 staticSharedSecret = nodeKey.calculateECDHKeyAgreement(partyPubKey);
  if (version4) {
    initiatorMsg = InitiatorHandshakeMessageV4.create(
      nodeKey.getPublicKey(), ephKeyPair, staticSharedSecret, initiatorNonce);
  } else {
    initiatorMsg = InitiatorHandshakeMessageV1.create(
      nodeKey.getPublicKey(), ephKeyPair, staticSharedSecret, initiatorNonce, false);
  }
  try {
    if (version4) {
      initiatorMsgEnc = EncryptedMessage.encryptMsgEip8(initiatorMsg.encode(), partyPubKey);
    } else {
      initiatorMsgEnc = EncryptedMessage.encryptMsg(initiatorMsg.encode(), partyPubKey);
    }
  } catch (final InvalidCipherTextException e) {
    status.set(Handshaker.HandshakeStatus.FAILED);
    throw new HandshakeException("Encrypting the first handshake message failed", e);
  }
```

**Key Features**:
- Uses BouncyCastle via Tuweni library
- Supports both v1 and EIP-8 (v4) formats
- State machine for handshake status
- Netty ByteBuf for efficient memory management

### Comparison Matrix: Handshake

| Feature | Fukuii | Core-Geth | Besu | Compatible |
|---------|--------|-----------|------|------------|
| **ECIES Encryption** | BouncyCastle | Go crypto/ecies | BouncyCastle (Tuweni) | ✅ |
| **Auth Message Format** | v4 (EIP-8) | v4 (EIP-8) | v1 & v4 | ✅ |
| **Padding** | 100-300 bytes variable | Variable | Variable | ✅ |
| **Size Prefix** | 2 bytes BE | 2 bytes BE | 2 bytes BE | ✅ |
| **Nonce Size** | 32 bytes | 32 bytes | 32 bytes | ✅ |
| **Ephemeral Keys** | ECDH secp256k1 | ECDH secp256k1 | ECDH secp256k1 | ✅ |
| **MAC Algorithm** | Keccak-256 | Keccak-256 | Keccak-256 | ✅ |
| **AES Mode** | AES-128-CTR | AES-128-CTR | AES-128-CTR | ✅ |

**Result**: All three implementations are cryptographically compatible. Handshake phase should succeed between any pair.

## Message Framing

After handshake, all messages are encrypted and authenticated using frames.

### Frame Structure

```
[HEADER (32 bytes)] [FRAME-DATA (variable)] [MAC (16 bytes)]
```

**Header** (32 bytes):
- 16 bytes encrypted header data
- 16 bytes header MAC

**Encrypted Header Data** (16 bytes before encryption):
- 3 bytes: frame size (big-endian uint24)
- 13 bytes: protocol header (RLP-encoded)

**Frame Data**:
- Variable length, padded to 16-byte boundary
- Contains: message ID (1 byte) + message payload

**Frame MAC** (16 bytes):
- HMAC-Keccak-256 of frame data

### Fukuii Implementation

**Location**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/FrameCodec.scala`

```scala
def readFrames(data: ByteString): Seq[Frame] = {
  unprocessedData ++= data
  
  @tailrec
  def readRecursive(framesSoFar: Seq[Frame] = Nil): Seq[Frame] = {
    if (headerOpt.isEmpty) tryReadHeader()
    
    headerOpt match {
      case Some(header) =>
        val padding = (16 - (header.bodySize % 16)) % 16
        val totalSizeToRead = header.bodySize + padding + MacSize
        
        if (unprocessedData.length >= totalSizeToRead) {
          val buffer = unprocessedData.take(totalSizeToRead).toArray
          
          val frameSize = totalSizeToRead - MacSize
          secrets.ingressMac.update(buffer, 0, frameSize)
          dec.processBytes(buffer, 0, frameSize, buffer, 0)
          
          val `type` = rlp.decode[Int](buffer)
          val pos = rlp.nextElementIndex(buffer, 0)
          val payload = buffer.slice(pos, header.bodySize)
          
          // MAC verification and update
          val macBuffer = new Array[Byte](secrets.ingressMac.getDigestSize)
          doSum(secrets.ingressMac, macBuffer)
          updateMac(secrets.ingressMac, macBuffer, 0, buffer, frameSize, egress = false)
```

**Key Features**:
- Stateful frame reader with buffering
- Tail-recursive frame parsing
- BouncyCastle AES-CTR cipher
- Keccak-256 for MAC operations

### Core-Geth Implementation

**Location**: `p2p/rlpx/rlpx.go`

```go
func (h *sessionState) readFrame(conn io.Reader) ([]byte, error) {
  h.rbuf.reset()
  
  // Read the frame header.
  header, err := h.rbuf.read(conn, 32)
  if err != nil {
    return nil, err
  }
  
  // Verify header MAC.
  wantHeaderMAC := h.ingressMAC.computeHeader(header[:16])
  if !hmac.Equal(wantHeaderMAC, header[16:]) {
    return nil, errors.New("bad header MAC")
  }
  
  // Decrypt the frame header to get the frame size.
  h.dec.XORKeyStream(header[:16], header[:16])
  fsize := readUint24(header[:16])
  
  // Frame size rounded up to 16 byte boundary for padding.
  rsize := fsize
  if padding := fsize % 16; padding > 0 {
    rsize += 16 - padding
  }
  
  // Read the frame content.
  frame, err := h.rbuf.read(conn, int(rsize))
  if err != nil {
    return nil, err
  }
  
  // Validate frame MAC.
  frameMAC, err := h.rbuf.read(conn, 16)
  if err != nil {
    return nil, err
  }
  wantFrameMAC := h.ingressMAC.computeFrame(frame)
  if !hmac.Equal(wantFrameMAC, frameMAC) {
    return nil, errors.New("bad frame MAC")
  }
```

**Key Features**:
- Streaming frame reader
- Constant-time MAC comparison
- Go crypto/aes for AES-CTR
- SHA3 (Keccak-256) for MAC

### Besu Implementation

**Location**: `ethereum/p2p/src/main/java/.../rlpx/framing/Framer.java`

```java
public RawMessage deframe(final ByteBuf buf) throws FramingException {
  if (!headerProcessed) {
    if (buf.readableBytes() < LENGTH_FULL_HEADER) {
      return null;
    }
    
    final byte[] header = new byte[LENGTH_FULL_HEADER];
    buf.readBytes(header);
    
    // Verify header MAC
    final byte[] headerMac = Arrays.copyOfRange(header, LENGTH_HEADER_DATA, LENGTH_FULL_HEADER);
    secrets.updateIngress(Arrays.copyOfRange(header, 0, LENGTH_HEADER_DATA));
    if (!MessageDigest.isEqual(secrets.getIngressMac(), headerMac)) {
      throw new FramingException("Header MAC did not match expected MAC");
    }
    
    // Decrypt header
    final byte[] decryptedHeader = new byte[LENGTH_HEADER_DATA];
    decryptor.processBytes(header, 0, LENGTH_HEADER_DATA, decryptedHeader, 0);
    
    // Parse frame size
    frameSize = RLP.decodeInt(Bytes.wrap(decryptedHeader, 0, 3));
    headerProcessed = true;
  }
```

**Key Features**:
- State-based frame parsing
- Netty ByteBuf integration
- BouncyCastle AES-CTR cipher
- Constant-time MAC verification

### Comparison Matrix: Framing

| Feature | Fukuii | Core-Geth | Besu | Compatible |
|---------|--------|-----------|------|------------|
| **Frame Header Size** | 32 bytes | 32 bytes | 32 bytes | ✅ |
| **Header MAC Size** | 16 bytes | 16 bytes | 16 bytes | ✅ |
| **Frame MAC Size** | 16 bytes | 16 bytes | 16 bytes | ✅ |
| **Padding** | 16-byte boundary | 16-byte boundary | 16-byte boundary | ✅ |
| **AES Cipher** | AES-128-CTR | AES-128-CTR | AES-128-CTR | ✅ |
| **MAC Algorithm** | Keccak-256 | Keccak-256 | Keccak-256 | ✅ |
| **Buffering** | Stateful | Streaming | Stateful | ✅ |
| **Frame Size Encoding** | uint24 BE | uint24 BE | uint24 BE | ✅ |

**Result**: Frame encoding/decoding is fully compatible across all three clients.

## Message Encoding and Snappy Compression

### Protocol Overview

Messages are compressed using Snappy when both peers support p2pVersion >= 5 (negotiated during Hello exchange).

### Fukuii Implementation

**Location**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/MessageCodec.scala`

```scala
def readFrames(frames: Seq[Frame]): Seq[Either[DecodingError, Message]] =
  frames.map { frame =>
    val frameData = frame.payload.toArray
    
    // Core-geth compresses ALL messages when p2pVersion >= 5, including wire protocol messages
    // Wire protocol messages (Hello 0x00, Disconnect 0x01, Ping 0x02, Pong 0x03) are also compressed
    // Previous logic excluded wire protocol messages, causing incompatibility with core-geth
    val shouldCompress = remotePeer2PeerVersion >= EtcHelloExchangeState.P2pVersion
    
    val payloadTry =
      if (shouldCompress) {
        // Always attempt decompression when compression is expected (p2pVersion >= 5)
        // If decompression fails, fall back to treating the data as uncompressed
        // This handles CoreGeth's protocol deviation where it advertises compression support
        // but sends uncompressed messages
        decompressData(frameData, frame).recoverWith { case ex =>
          log.warn(
            "COMPRESSION_FALLBACK: Frame type 0x{}: Decompression failed - treating as uncompressed data. " +
              "Peer sent uncompressed despite p2pVersion={}. firstByte=0x{}, size={}, error: {}",
            frame.`type`.toHexString,
            remotePeer2PeerVersion,
            Integer.toHexString(frameData(0) & 0xff),
            frameData.length,
            ex.getMessage
          )
          Success(frameData)
        }
      } else {
        Success(frameData)
      }
```

**Key Features**:
- **Xerial Snappy library** for compression
- **Compression for ALL messages** when p2pVersion >= 5 (including wire protocol)
- **Fallback logic**: If decompression fails, treats data as uncompressed
- **Max decompressed size**: 16MB (16777216 bytes)
- Enhanced logging for compression decision and fallback

### Core-Geth Implementation

**Location**: `p2p/rlpx/rlpx.go`

```go
func (c *Conn) Read() (code uint64, data []byte, wireSize int, err error) {
  if c.session == nil {
    panic("can't ReadMsg before handshake")
  }
  
  frame, err := c.session.readFrame(c.conn)
  if err != nil {
    return 0, nil, 0, err
  }
  code, data, err = rlp.SplitUint64(frame)
  if err != nil {
    return 0, nil, 0, fmt.Errorf("invalid message code: %v", err)
  }
  wireSize = len(data)
  
  // If snappy is enabled, verify and decompress message.
  if c.snappyReadBuffer != nil {
    var actualSize int
    actualSize, err = snappy.DecodedLen(data)
    if err != nil {
      return code, nil, 0, err
    }
    if actualSize > maxUint24 {
      return code, nil, 0, errPlainMessageTooLarge
    }
    c.snappyReadBuffer = growslice(c.snappyReadBuffer, actualSize)
    data, err = snappy.Decode(c.snappyReadBuffer, data)
  }
  return code, data, wireSize, err
}

// SetSnappy enables or disables snappy compression of messages.
func (c *Conn) SetSnappy(snappy bool) {
  if snappy {
    c.snappyReadBuffer = []byte{}
    c.snappyWriteBuffer = []byte{}
  } else {
    c.snappyReadBuffer = nil
    c.snappyWriteBuffer = nil
  }
}
```

**Key Features**:
- **golang/snappy library** for compression
- **Compression state** toggled via SetSnappy() after Hello exchange
- **Max decompressed size**: maxUint24 (16,777,215 bytes)
- **No fallback**: Decompression errors return error immediately
- Compresses all messages when enabled

### Besu Implementation

**Location**: `ethereum/p2p/src/main/java/.../rlpx/framing/Framer.java` and `SnappyCompressor.java`

```java
public RawMessage deframe(final ByteBuf buf) throws FramingException {
  // ... frame reading code ...
  
  // Decompress if compression is enabled
  MessageData messageData;
  if (compressionEnabled) {
    try {
      final byte[] decompressedPayload = compressor.decompress(messagePayload);
      compressionSuccessful = true;
      messageData = new RawMessage(msgId, Bytes.wrap(decompressedPayload));
    } catch (final FramingException e) {
      // If decompression fails but we have had past success, this is a real error
      if (compressionSuccessful) {
        throw e;
      }
      // Otherwise treat as uncompressed for compatibility
      LOG.debug("Treating message as uncompressed due to decompression failure");
      messageData = new RawMessage(msgId, Bytes.wrap(messagePayload));
    }
  } else {
    messageData = new RawMessage(msgId, Bytes.wrap(messagePayload));
  }
```

**Snappy Compression** (`SnappyCompressor.java`):
```java
public byte[] compress(final byte[] uncompressed) {
  try {
    return Snappy.compress(uncompressed);
  } catch (final IOException e) {
    throw new FramingException("Snappy compression failed", e);
  }
}

public byte[] decompress(final byte[] compressed) {
  try {
    return Snappy.uncompress(compressed);
  } catch (final IOException e) {
    throw new FramingException("Snappy decompression failed", e);
  }
}
```

**Key Features**:
- **Xerial Snappy library** (same as Fukuii)
- **Compression state** toggled via enableCompression()/disableCompression()
- **Conditional fallback**: Treats as uncompressed only if compression never succeeded before
- **No explicit size limit** in decompression (relies on Snappy library limits)

### Comparison Matrix: Compression

| Feature | Fukuii | Core-Geth | Besu | Compatible |
|---------|--------|-----------|------|------------|
| **Snappy Library** | Xerial | golang/snappy | Xerial | ✅ |
| **Activation** | p2pVersion >= 5 | SetSnappy() call | enableCompression() | ✅ |
| **Wire Protocol Compression** | YES (all messages) | YES (all messages) | YES (all messages) | ✅ |
| **Max Decompressed Size** | maxUint24 (16,777,215) | maxUint24 (16,777,215) | No explicit limit | ✅ |
| **Decompression Fallback** | Always (graceful) | No | Conditional | ⚠️ |
| **Error on Decompress Fail** | Warn + continue | Hard error | Hard error after first success | ⚠️ |

**Key Difference**: Fukuii has more robust fallback logic for handling compression edge cases.

## Critical Implementation Differences

### 1. Compression Fallback Strategy

**Issue**: Some peers may advertise p2pVersion >= 5 but occasionally send uncompressed data due to bugs or protocol deviations.

**Fukuii Approach**:
```scala
// Always attempt decompression, but fall back gracefully
decompressData(frameData, frame).recoverWith { case ex =>
  log.warn("COMPRESSION_FALLBACK: Treating as uncompressed")
  Success(frameData)
}
```
✅ **Most tolerant** - Always tries to continue communication

**Core-Geth Approach**:
```go
// Return error if decompression fails
data, err = snappy.Decode(c.snappyReadBuffer, data)
if err != nil {
  return code, nil, 0, err
}
```
❌ **Strict** - Disconnects on decompression error

**Besu Approach**:
```java
// Conditional fallback - only if compression never succeeded
if (compressionSuccessful) {
  throw e;  // Real error
} else {
  // Treat as uncompressed for compatibility
}
```
⚠️ **Hybrid** - Strict after first successful decompression

**Recommendation**: Fukuii's approach is most robust for handling real-world protocol deviations.

### 2. Message Size Limits

**Fukuii**:
```scala
val MaxDecompressedLength = 16777215  // maxUint24 (2^24 - 1), matching Core-Geth
```

**Core-Geth**:
```go
if actualSize > maxUint24 {  // 16,777,215 bytes
  return code, nil, 0, errPlainMessageTooLarge
}
```

**Besu**:
- No explicit check in decompression
- Relies on Snappy library internal limits

**Impact**: Now aligned - Fukuii updated to use maxUint24 (16,777,215) matching Core-Geth standard

### 3. Compression Decision Logic

**All three clients** compress ALL messages (including wire protocol) when compression is enabled. This is correct per the devp2p specification.

**Historical Issue in Fukuii** (now fixed):
Previous versions excluded wire protocol messages from compression, causing incompatibility. Current version correctly compresses all messages.

## Potential Incompatibility Scenarios

### Scenario 1: Peer Advertises Compression but Sends Uncompressed

**Manifestation**: Peer sends Hello with p2pVersion=5, but subsequent messages are uncompressed.

**Fukuii Behavior**: ✅ Gracefully handles via fallback, logs warning, continues
**Core-Geth Behavior**: ❌ Decompression error, connection dropped
**Besu Behavior**: ⚠️ Initially accepts (first message), then errors

**Real-world Impact**: Some buggy or non-standard peers may trigger this. Fukuii is most compatible.

### Scenario 2: Oversized Message

**Manifestation**: Peer sends message that decompresses to > 16MB

**Fukuii Behavior**: Snappy library throws error, caught by fallback (treats as uncompressed, may fail downstream)
**Core-Geth Behavior**: Explicit check returns errPlainMessageTooLarge
**Besu Behavior**: Snappy library error (IOException)

**Real-world Impact**: Should not occur with well-behaved peers. All clients will reject.

### Scenario 3: Malformed Snappy Data

**Manifestation**: Compressed data is corrupted or invalid

**Fukuii Behavior**: ✅ Fallback to uncompressed (may cause downstream errors if truly corrupted)
**Core-Geth Behavior**: ❌ Error returned, connection dropped
**Besu Behavior**: ❌ Error thrown (after first successful decompression)

**Real-world Impact**: Fukuii may be too lenient, potentially processing corrupt data.

## Recommendations

### For Troubleshooting Peer Connection Issues

1. **Check Compression Logs**:
   ```bash
   grep "COMPRESSION_DECISION\|COMPRESSION_FALLBACK" logs/fukuii.log
   ```
   Look for frequent fallbacks - indicates peers with compression issues.

2. **Verify p2pVersion Negotiation**:
   ```bash
   grep "PEER_HANDSHAKE_SUCCESS\|Hello.*p2pVersion" logs/fukuii.log
   ```
   Ensure both sides agree on p2pVersion.

3. **Monitor Message Sizes**:
   ```bash
   grep "payloadSize=" logs/fukuii.log | awk '{print $NF}' | sort -n | tail -20
   ```
   Check for unusually large messages (> 16MB would be problematic).

4. **Check for Protocol Deviations**:
   ```bash
   grep "Peer sent uncompressed despite p2pVersion" logs/fukuii.log
   ```
   Identifies peers with compression protocol deviations.

### Code Improvements

1. **Consider Hybrid Fallback**:
   - Track compression success per peer
   - After N successful decompressions, treat failures as errors
   - Balance tolerance with security

2. **Add Metrics**:
   - Count compression fallbacks per peer
   - Track decompression error rates
   - Monitor message size distribution

3. **Enhanced Logging**:
   - Log first 16 bytes of failed decompression attempts (hex)
   - Include peer ID in all compression-related logs
   - Track compression ratio for successfully compressed messages

### Testing Recommendations

1. **Test Against Multiple Peers**:
   - Core-Geth (reference implementation)
   - Besu (alternative implementation)
   - OpenEthereum/Parity (if still accessible)

2. **Compression Edge Cases**:
   - Peer that randomly sends uncompressed messages
   - Peer that sends corrupt Snappy data
   - Peer that advertises p2pVersion=4 then p2pVersion=5

3. **Message Size Tests**:
   - Messages near 16MB limit
   - Highly compressible vs. incompressible data
   - Verify padding calculations

## Conclusion

### Implementation Status: ✅ COMPATIBLE

Fukuii's RLPx handshake and message encoding implementation is **correct and compatible** with both Core-Geth and Besu:

1. **Handshake**: Fully compatible ECIES-based authentication
2. **Framing**: Identical frame structure and MAC verification
3. **Compression**: Correct Snappy implementation with enhanced fallback logic

### Peer Connection Issues - Root Causes

Based on this analysis, peer connection issues are **NOT** caused by fundamental RLPx incompatibilities. More likely causes:

1. **Peer Quality**: Some ETC peers may have buggy compression implementations
2. **Network Issues**: Timeout/latency problems during handshake
3. **Peer Discovery**: Issues finding compatible peers
4. **ForkID Validation**: Already confirmed compatible (see EIP-2124 analysis)
5. **Protocol Capability Mismatch**: Peers advertising capabilities they don't properly support

### Next Steps

1. ✅ RLPx implementation is correct - no changes needed
2. 🔍 Focus investigation on:
   - Peer discovery and selection
   - Network connectivity/timeouts
   - Specific peer compatibility (test against known-good Core-Geth nodes)
3. 📊 Add compression metrics and monitoring
4. 🧪 Test against diverse peer implementations

## References

- **RLPx Specification**: https://github.com/ethereum/devp2p/blob/master/rlpx.md
- **Snappy Compression**: https://google.github.io/snappy/
- **Fukuii Implementation**: `src/main/scala/com/chipprbots/ethereum/network/rlpx/`
- **Core-Geth Implementation**: https://github.com/etclabscore/core-geth/tree/master/p2p/rlpx
- **Besu Implementation**: https://github.com/hyperledger/besu/tree/main/ethereum/p2p

## Appendix: Message Flow Diagram

```
┌─────────────┐                                    ┌─────────────┐
│   Initiator │                                    │  Responder  │
│   (Fukuii)  │                                    │ (Core-Geth) │
└──────┬──────┘                                    └──────┬──────┘
       │                                                  │
       │  1. Auth (ECIES encrypted, padded)              │
       │ ─────────────────────────────────────────────>  │
       │                                                  │
       │  2. Ack (ECIES encrypted)                       │
       │  <─────────────────────────────────────────────│
       │                                                  │
       │  [Both derive shared secrets: AES, MAC keys]    │
       │                                                  │
       │  3. Hello (AES-CTR encrypted frame)             │
       │ ─────────────────────────────────────────────>  │
       │     - p2pVersion = 5                            │
       │     - Capabilities: [eth/68, snap/1, ...]       │
       │                                                  │
       │  4. Hello (AES-CTR encrypted frame)             │
       │  <─────────────────────────────────────────────│
       │     - p2pVersion = 5                            │
       │     - Capabilities: [eth/68, snap/1, ...]       │
       │                                                  │
       │  [Snappy compression enabled if p2pVersion >= 5]│
       │                                                  │
       │  5. Status (Snappy compressed, AES-CTR)         │
       │ ─────────────────────────────────────────────>  │
       │     - Network ID, Genesis, ForkID, ...          │
       │                                                  │
       │  6. Status (Snappy compressed, AES-CTR)         │
       │  <─────────────────────────────────────────────│
       │                                                  │
       │  [Normal message exchange begins]               │
       │                                                  │
```

### Frame Structure Detail

```
┌─────────────────────────────────────────────────────────────┐
│                       RLPx Frame                            │
├───────────────────┬─────────────────────┬───────────────────┤
│  Header (32 bytes)│  Frame-Data (var)   │  MAC (16 bytes)   │
└───────────────────┴─────────────────────┴───────────────────┘
        │                    │                      │
        ▼                    ▼                      ▼
┌──────────────┬───┐  ┌─────┬───────────┐  ┌────────────┐
│ Enc Header   │MAC│  │ Msg │ Payload   │  │ Frame MAC  │
│   (16 bytes) │(16)│  │ ID  │ (+padding)│  │  (16 bytes)│
└──────────────┴───┘  └─────┴───────────┘  └────────────┘
       │                │         │
       ▼                ▼         ▼
┌────────────┐    ┌────────┬─────────────┐
│ Frame Size │    │ 0x00   │ Snappy      │  (if p2pVersion >= 5)
│  (3 bytes) │    │(Hello) │ Compressed  │
└────────────┘    └────────┴─────────────┘
```
