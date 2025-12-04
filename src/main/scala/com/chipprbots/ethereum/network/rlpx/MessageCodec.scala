package com.chipprbots.ethereum.network.rlpx

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.xerial.snappy.Snappy
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.handshaker.EtcHelloExchangeState
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageDecoder
import com.chipprbots.ethereum.network.p2p.MessageDecoder.DecodingError
import com.chipprbots.ethereum.network.p2p.MessageSerializable

import com.chipprbots.ethereum.utils.Logger

object MessageCodec {
  val MaxFramePayloadSize: Int = Int.MaxValue // no framing
  // 16Mb in base 2
  val MaxDecompressedLength = 16777216
  // Maximum bytes to show fully in hex strings (larger data will be truncated)
  val MaxFullHexLength = 64

  /** Utility method to truncate hex strings for logging.
    * For data up to MaxFullHexLength bytes: shows complete hex string
    * For larger data: shows first 32 bytes + "..." + last 32 bytes
    */
  def truncateHex(data: Array[Byte]): String = {
    if (data.length <= MaxFullHexLength) {
      Hex.toHexString(data)
    } else {
      Hex.toHexString(data.take(32)) + "..." + Hex.toHexString(data.takeRight(32))
    }
  }
}

class MessageCodec(
    frameCodec: FrameCodec,
    messageDecoder: MessageDecoder,
    val remotePeer2PeerVersion: Long
) extends Logger {
  import MessageCodec._

  val contextIdCounter = new AtomicInteger

  // TODO: ETCM-402 - messageDecoder should use negotiated protocol version
  def readMessages(data: ByteString): Seq[Either[DecodingError, Message]] = {
    log.debug("readMessages: Received {} bytes of data, p2pVersion: {}", data.length, remotePeer2PeerVersion)
    val frames = frameCodec.readFrames(data)
    log.debug("readMessages: Decoded {} frames from {} bytes", frames.length, data.length)

    frames.zipWithIndex.foreach { case (frame, idx) =>
      log.debug(
        "Frame[{}]: type=0x{}, payloadSize={}, header={}",
        idx,
        frame.`type`.toHexString,
        frame.payload.length,
        frame.header
      )
    }

    readFrames(frames)
  }

  def readFrames(frames: Seq[Frame]): Seq[Either[DecodingError, Message]] =
    frames.map { frame =>
      val frameData = frame.payload.toArray
      
      // Core-geth compresses ALL messages when p2pVersion >= 5, including wire protocol messages
      // Wire protocol messages (Hello 0x00, Disconnect 0x01, Ping 0x02, Pong 0x03) are also compressed
      // Previous logic excluded wire protocol messages, causing incompatibility with core-geth
      val shouldCompress = remotePeer2PeerVersion >= EtcHelloExchangeState.P2pVersion

      // Heuristic to check if data looks like RLP-encoded data
      // RLP encoding has predictable first-byte patterns:
      // - 0x80-0xbf: RLP string (0x80 = empty string, 0x81-0xb7 = short string, 0xb8-0xbf = long string)
      // - 0xc0-0xff: RLP list (0xc0 = empty list, 0xc1-0xf7 = short list, 0xf8-0xff = long list)
      // - 0x00-0x7f: Single byte value (direct value encoding)
      // This is used as a fallback check after decompression fails to handle protocol deviations
      // where peers send uncompressed RLP data when compression is expected.
      def looksLikeRLP(data: Array[Byte]): Boolean = data.nonEmpty && {
        // Bitwise AND with 0xff converts signed byte to unsigned int (Scala bytes are signed -128 to 127, so this masks to 0-255 range for comparison with RLP encoding markers)
        val firstByte = data(0) & 0xff
        // Accept values >= 0x80 (RLP strings 0x80-0xbf and lists 0xc0-0xff).
        // Note: Single-byte values 0x00-0x7f are also valid RLP but excluded here as they're less common in SNAP messages.
        firstByte >= 0x80
      }

      // Enhanced logging for compression decision
      log.debug(
        "COMPRESSION_DECISION: frame=0x{}, p2pVersion={}, shouldCompress={}, payloadSize={}, firstByte=0x{}",
        frame.`type`.toHexString,
        remotePeer2PeerVersion,
        shouldCompress,
        frameData.length,
        if (frameData.length > 0) Integer.toHexString(frameData(0) & 0xff) else "N/A"
      )

      val payloadTry =
        if (shouldCompress) {
          // Always attempt decompression when compression is expected (p2pVersion >= 5)
          // If decompression fails, fall back to uncompressed data if it looks like valid RLP
          decompressData(frameData, frame).recoverWith { case ex =>
            if (looksLikeRLP(frameData)) {
              log.warn(
                "COMPRESSION_FALLBACK: Frame type 0x{}: Decompression failed but data looks like RLP - using as uncompressed. " +
                  "This indicates peer sent uncompressed data despite p2pVersion={} (compression expected). " +
                  "firstByte=0x{}, size={}, error: {}",
                frame.`type`.toHexString,
                remotePeer2PeerVersion,
                Integer.toHexString(frameData(0) & 0xff),
                frameData.length,
                ex.getMessage
              )
              Success(frameData)
            } else {
              // For better diagnostics, log the frame type and data sample
              val dataSample = if (frameData.length > 0) {
                s"firstByte=0x${Integer.toHexString(frameData(0) & 0xff)}, size=${frameData.length}"
              } else {
                "empty"
              }
              log.error(
                "COMPRESSION_ERROR: Frame type 0x{}: Decompression failed and data doesn't look like RLP ({}). " +
                  "This may indicate corrupt data or protocol mismatch. p2pVersion={}, error: {}",
                frame.`type`.toHexString,
                dataSample,
                remotePeer2PeerVersion,
                ex.getMessage
              )
              Failure(ex)
            }
          }
        } else {
          log.debug(
            "COMPRESSION_SKIP: Frame type 0x{} - skipping decompression (p2pVersion={} < {})",
            frame.`type`.toHexString,
            remotePeer2PeerVersion,
            EtcHelloExchangeState.P2pVersion
          )
          Success(frameData)
        }

      payloadTry.toEither
        .left.map {
          // Wrap decompression exceptions in DecompressionFailure for type-safe error handling
          case ex: RuntimeException if ex.getMessage != null && ex.getMessage.contains("FAILED_TO_UNCOMPRESS") =>
            MessageDecoder.DecompressionFailure(ex.getMessage, ex)
          case ex =>
            // Other errors are wrapped as MalformedMessageError
            MessageDecoder.MalformedMessageError(Option(ex.getMessage).getOrElse(ex.toString), Some(ex))
        }
        .flatMap { payload =>
          messageDecoder.fromBytes(frame.`type`, payload)
        }
    }

  private def decompressData(data: Array[Byte], frame: Frame): Try[Array[Byte]] = {
    // First, let's check if this might be uncompressed data sent by mistake
    val dataHex = if (data.length <= 32) Hex.toHexString(data) else Hex.toHexString(data.take(32)) + "..."

    log.debug(
      "decompressData: Attempting to decompress frame type 0x{}, size {} bytes, hex: {}",
      frame.`type`.toHexString,
      data.length,
      dataHex
    )

    val result = Try(Snappy.uncompressedLength(data))
      .flatMap { decompressedSize =>
        log.debug("decompressData: Snappy header indicates uncompressed size: {} bytes", decompressedSize)
        if (decompressedSize > MaxDecompressedLength)
          Failure(new RuntimeException(s"Message size larger than 16mb: $decompressedSize bytes"))
        else
          Try(Snappy.uncompress(data)).recoverWith { case ex =>
            Failure(new RuntimeException(s"FAILED_TO_UNCOMPRESS(${ex.getClass.getSimpleName}): ${ex.getMessage}"))
          }
      }
      .recoverWith { case ex =>
        Failure(
          new RuntimeException(
            s"FAILED_TO_UNCOMPRESS(InvalidHeader): Cannot read uncompressed length - ${ex.getMessage}"
          )
        )
      }

    // Log debug information when decompression fails
    result.recoverWith { case ex =>
      val hexData = truncateHex(data)

      log.warn(
        "DECOMPRESSION_DEBUG: Failed to decompress frame - " +
          s"frameType: 0x${frame.`type`.toHexString}, " +
          s"frameSize: ${data.length}, " +
          s"p2pVersion: $remotePeer2PeerVersion, " +
          s"hexData: $hexData, " +
          s"error: ${ex.getMessage}"
      )

      // Additional detailed logging for investigation
      log.debug(
        "DECOMPRESSION_DEBUG: Frame details - " +
          s"header: ${frame.header}, " +
          s"payload.length: ${frame.payload.length}, " +
          s"first8bytes: ${if (data.length >= 8) Hex.toHexString(data.take(8)) else "N/A"}"
      )

      // Propagate the failure - fallback logic is handled in readFrames
      Failure(ex)
    }
  }

  def encodeMessage(serializable: MessageSerializable): ByteString = {
    val encoded: Array[Byte] = serializable.toBytes
    val numFrames = Math.ceil(encoded.length / MaxFramePayloadSize.toDouble).toInt
    val contextId = contextIdCounter.incrementAndGet()

    // Log message encoding details for protocol debugging
    val encodedHex = truncateHex(encoded)
    log.debug(
      "ENCODE_MSG: Encoding message code=0x{}, type={}, rawBytes={}, hex={}",
      serializable.code.toHexString,
      serializable.underlyingMsg.getClass.getSimpleName,
      encoded.length,
      encodedHex
    )

    val frames = (0 until numFrames).map { frameNo =>
      val framedPayload = encoded.drop(frameNo * MaxFramePayloadSize).take(MaxFramePayloadSize)
      
      // Core-geth compresses ALL messages when p2pVersion >= 5, including wire protocol messages
      // Matches core-geth behavior: no exceptions for wire protocol (Ping, Pong, etc.)
      val shouldCompressThis = remotePeer2PeerVersion >= EtcHelloExchangeState.P2pVersion
      
      val payload =
        if (shouldCompressThis) {
          val compressed = Snappy.compress(framedPayload)
          // Safe compression ratio calculation (avoid division by zero)
          val ratio = if (framedPayload.length > 0) compressed.length.toDouble / framedPayload.length else 0.0
          log.debug(
            "ENCODE_MSG: Snappy compressed frame {} from {} to {} bytes (ratio: {}), code=0x{}, p2pVersion={}",
            frameNo,
            framedPayload.length,
            compressed.length,
            "%.2f".format(ratio),
            serializable.code.toHexString,
            remotePeer2PeerVersion
          )
          compressed
        } else {
          log.debug(
            "ENCODE_MSG: Skipping compression for frame {} (p2pVersion={} < {}), code=0x{}",
            frameNo,
            remotePeer2PeerVersion,
            EtcHelloExchangeState.P2pVersion,
            serializable.code.toHexString
          )
          framedPayload
        }

      val totalPacketSize = if (frameNo == 0) Some(encoded.length) else None
      val header =
        if (numFrames > 1) Header(payload.length, 0, Some(contextId), totalPacketSize)
        else Header(payload.length, 0, None, None)
      Frame(header, serializable.code, ByteString(payload))
    }

    val result = frameCodec.writeFrames(frames)
    log.debug(
      "ENCODE_MSG: Final encoded message code=0x{} totalBytes={} numFrames={}",
      serializable.code.toHexString,
      result.length,
      numFrames
    )
    result
  }

}
