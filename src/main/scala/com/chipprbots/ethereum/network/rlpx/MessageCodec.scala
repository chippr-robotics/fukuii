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
      val isWireProtocolMessage = frame.`type` >= 0x00 && frame.`type` <= 0x03

      // Check if data looks like RLP (starts with 0xc0-0xff for lists, 0x80-0xbf for strings)
      val looksLikeRLP = frameData.nonEmpty && {
        val firstByte = frameData(0) & 0xff
        firstByte >= 0xc0 || (firstByte >= 0x80 && firstByte < 0xc0)
      }

      val shouldCompress = remotePeer2PeerVersion >= EtcHelloExchangeState.P2pVersion && !isWireProtocolMessage

      log.debug(
        "Processing frame type 0x{}: wireProtocol={}, p2pVersion={}, willDecompress={}, looksLikeRLP={}",
        frame.`type`.toHexString,
        isWireProtocolMessage,
        remotePeer2PeerVersion,
        shouldCompress,
        looksLikeRLP
      )

      val payloadTry =
        if (shouldCompress && !looksLikeRLP) {
          // Only attempt decompression if it doesn't look like RLP
          decompressData(frameData, frame)
        } else if (shouldCompress && looksLikeRLP) {
          // Peer sent uncompressed data when compression was expected - protocol deviation but handle gracefully
          log.warn(
            "Frame type 0x{}: Peer sent uncompressed RLP data despite p2pVersion >= 5 (protocol deviation)",
            frame.`type`.toHexString
          )
          Success(frameData)
        } else {
          log.debug(
            "Skipping decompression for frame type 0x{} (wire protocol or p2pVersion < 5)",
            frame.`type`.toHexString
          )
          Success(frameData)
        }

      payloadTry.toEither.flatMap { payload =>
        val result = messageDecoder.fromBytes(frame.`type`, payload)
        result match {
          case Left(error) =>
            // Enhanced debug logging for decode failures
            val payloadHex = if (payload.length <= 64) {
              Hex.toHexString(payload)
            } else {
              Hex.toHexString(payload.take(32)) + "..." + Hex.toHexString(payload.takeRight(32))
            }
            log.error(
              "MESSAGE_DECODE_ERROR: Failed to decode message - " +
              s"messageType: 0x${frame.`type`.toHexString}, " +
              s"payloadSize: ${payload.length}, " +
              s"p2pVersion: $remotePeer2PeerVersion, " +
              s"payloadHex: $payloadHex, " +
              s"error: ${error.getMessage}"
            )
          case Right(msg) =>
            log.trace("Successfully decoded message type 0x{}: {}", frame.`type`.toHexString, msg.toShortString)
        }
        result
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
      val hexData = if (data.length <= 64) {
        Hex.toHexString(data)
      } else {
        Hex.toHexString(data.take(32)) + "..." + Hex.toHexString(data.takeRight(32))
      }

      // Check if this might be uncompressed data by looking for patterns
      val possibleUncompressed = if (data.length > 0) {
        // Check if first byte looks like a message type (reasonable range for ETH protocol)
        val firstByte = data(0) & 0xff
        firstByte >= 0x10 && firstByte <= 0x20
      } else false

      log.error(
        "DECOMPRESSION_DEBUG: Failed to decompress frame - " +
          s"frameType: 0x${frame.`type`.toHexString}, " +
          s"frameSize: ${data.length}, " +
          s"p2pVersion: $remotePeer2PeerVersion, " +
          s"possibleUncompressed: $possibleUncompressed, " +
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

      // If it looks like uncompressed data, try to decode it directly
      if (possibleUncompressed && data.length < 1024) { // reasonable size limit
        log.warn("DECOMPRESSION_DEBUG: Attempting to decode as uncompressed data (peer protocol deviation)")
        Success(data) // Return the data as-is to see if it decodes
      } else {
        Failure(ex)
      }
    }
  }

  def encodeMessage(serializable: MessageSerializable): ByteString = {
    val encoded: Array[Byte] = serializable.toBytes
    val numFrames = Math.ceil(encoded.length / MaxFramePayloadSize.toDouble).toInt
    val contextId = contextIdCounter.incrementAndGet()
    val frames = (0 until numFrames).map { frameNo =>
      val framedPayload = encoded.drop(frameNo * MaxFramePayloadSize).take(MaxFramePayloadSize)
      val isWireProtocolMessage = serializable.code >= 0x00 && serializable.code <= 0x03
      val payload =
        if (remotePeer2PeerVersion >= EtcHelloExchangeState.P2pVersion && !isWireProtocolMessage) {
          Snappy.compress(framedPayload)
        } else {
          framedPayload
        }

      val totalPacketSize = if (frameNo == 0) Some(encoded.length) else None
      val header =
        if (numFrames > 1) Header(payload.length, 0, Some(contextId), totalPacketSize)
        else Header(payload.length, 0, None, None)
      Frame(header, serializable.code, ByteString(payload))
    }

    frameCodec.writeFrames(frames)
  }

}
