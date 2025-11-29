package com.chipprbots.ethereum.network.rlpx

import java.io.IOException

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

import org.bouncycastle.crypto.StreamCipher
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.Logger

case class Frame(header: Header, `type`: Int, payload: ByteString)

case class Header(bodySize: Int, protocol: Int, contextId: Option[Int], totalPacketSize: Option[Int])

class FrameCodec(private val secrets: Secrets) extends Logger {

  val HeaderLength = 32
  val MacSize = 16

  private val allZerosIV = Array.fill[Byte](16)(0)

  // needs to be lazy to enable mocking
  private lazy val enc: StreamCipher = {
    val cipher = new SICBlockCipher(new AESEngine): @annotation.nowarn("cat=deprecation")
    cipher.init(true, new ParametersWithIV(new KeyParameter(secrets.aes), allZerosIV))
    cipher
  }

  // needs to be lazy to enable mocking
  private lazy val dec: StreamCipher = {
    val cipher = new SICBlockCipher(new AESEngine): @annotation.nowarn("cat=deprecation")
    cipher.init(false, new ParametersWithIV(new KeyParameter(secrets.aes), allZerosIV))
    cipher
  }

  private var unprocessedData: ByteString = ByteString.empty

  private var headerOpt: Option[Header] = None

  /** Note, this method is not reentrant.
    *
    * @param data
    * @return
    */
  def readFrames(data: ByteString): Seq[Frame] = {
    unprocessedData ++= data
    log.debug("FRAME_READ: Received {} bytes, total unprocessed: {}", data.length, unprocessedData.length)

    @tailrec
    def readRecursive(framesSoFar: Seq[Frame] = Nil): Seq[Frame] = {
      if (headerOpt.isEmpty) tryReadHeader()

      headerOpt match {
        case Some(header) =>
          val padding = (16 - (header.bodySize % 16)) % 16
          val totalSizeToRead = header.bodySize + padding + MacSize

          log.debug(
            "FRAME_READ: Header parsed - bodySize={}, padding={}, totalSizeToRead={}, available={}",
            header.bodySize,
            padding,
            totalSizeToRead,
            unprocessedData.length
          )

          if (unprocessedData.length >= totalSizeToRead) {
            val buffer = unprocessedData.take(totalSizeToRead).toArray

            val frameSize = totalSizeToRead - MacSize
            secrets.ingressMac.update(buffer, 0, frameSize)
            dec.processBytes(buffer, 0, frameSize, buffer, 0)

            val `type` = rlp.decode[Int](buffer)

            val pos = rlp.nextElementIndex(buffer, 0)
            val payload = buffer.slice(pos, header.bodySize)
            val macBuffer = new Array[Byte](secrets.ingressMac.getDigestSize)

            doSum(secrets.ingressMac, macBuffer)
            updateMac(secrets.ingressMac, macBuffer, 0, buffer, frameSize, egress = false)

            log.debug(
              "FRAME_READ: Decoded frame type=0x{}, payloadLen={}, header={}",
              `type`.toHexString,
              payload.length,
              header
            )

            // Log payload hex for protocol debugging
            if (payload.nonEmpty) {
              log.debug("FRAME_READ: Frame payload hex: {}", MessageCodec.truncateHex(payload))
            }

            headerOpt = None
            unprocessedData = unprocessedData.drop(totalSizeToRead)
            readRecursive(framesSoFar ++ Seq(Frame(header, `type`, ByteString(payload))))
          } else {
            log.debug("FRAME_READ: Waiting for more data ({} < {})", unprocessedData.length, totalSizeToRead)
            framesSoFar
          }

        case None =>
          log.debug("FRAME_READ: No header yet, waiting for more data (have {} bytes)", unprocessedData.length)
          framesSoFar
      }
    }

    val result = readRecursive()
    log.debug("FRAME_READ: Parsed {} frame(s)", result.length)
    result
  }

  private def tryReadHeader(): Unit =
    if (unprocessedData.size >= HeaderLength) {
      val headBuffer = unprocessedData.take(HeaderLength).toArray

      updateMac(secrets.ingressMac, headBuffer, 0, headBuffer, 16, egress = false)

      dec.processBytes(headBuffer, 0, 16, headBuffer, 0)

      var bodySize: Int = headBuffer(0)
      bodySize = (bodySize << 8) + (headBuffer(1) & 0xff)
      bodySize = (bodySize << 8) + (headBuffer(2) & 0xff)

      val rlpList = rlp.decode[Seq[Int]](headBuffer.drop(3))
      val protocol = rlpList.headOption.getOrElse(
        throw new IllegalStateException("Protocol field missing in RLP header")
      )
      val contextId = rlpList.lift(1)
      val totalPacketSize = rlpList.lift(2)

      unprocessedData = unprocessedData.drop(HeaderLength)
      headerOpt = Some(Header(bodySize, protocol, contextId, totalPacketSize))
    }

  def writeFrames(frames: Seq[Frame]): ByteString = {
    log.debug("FRAME_WRITE: Writing {} frame(s)", frames.size)

    val bytes = frames.zipWithIndex.flatMap { case (frame, index) =>
      val firstFrame = index == 0
      val lastFrame = index == frames.size - 1

      log.debug(
        "FRAME_WRITE: Frame[{}] type=0x{}, payloadLen={}, header={}",
        index,
        frame.`type`.toHexString,
        frame.payload.length,
        frame.header
      )

      var out: ByteString = ByteString()

      val headBuffer = new Array[Byte](HeaderLength)
      val ptype = rlp.encode(frame.`type`)

      val totalSize =
        if (firstFrame) frame.payload.length + ptype.length
        else frame.payload.length

      headBuffer(0) = (totalSize >> 16).toByte
      headBuffer(1) = (totalSize >> 8).toByte
      headBuffer(2) = totalSize.toByte

      // Build header data as a sequence of raw int values, then encode once
      // Previously this was double-encoding: each value was RLP-encoded, then the
      // sequence was encoded again, causing interoperability issues with other
      // RLPx implementations (e.g., core-geth). The correct approach is to encode
      // the sequence of integers directly.
      var headerDataElems: Seq[Int] = Nil
      headerDataElems :+= frame.header.protocol
      frame.header.contextId.foreach(cid => headerDataElems :+= cid)
      frame.header.totalPacketSize.foreach(tfs => headerDataElems :+= tfs)

      val headerData = rlp.encode(headerDataElems)
      log.debug(
        "FRAME_WRITE: Frame[{}] headerRLP={} ({} bytes), totalSize={}",
        index,
        Hex.toHexString(headerData),
        headerData.length,
        totalSize
      )

      System.arraycopy(headerData, 0, headBuffer, 3, headerData.length)
      enc.processBytes(headBuffer, 0, 16, headBuffer, 0)
      updateMac(secrets.egressMac, headBuffer, 0, headBuffer, 16, egress = true)

      val buff: Array[Byte] = new Array[Byte](256)
      out ++= ByteString(headBuffer)

      if (firstFrame) {
        // packet-type only in first frame
        enc.processBytes(ptype, 0, ptype.length, buff, 0)
        out ++= ByteString(buff.take(ptype.length))
        secrets.egressMac.update(buff, 0, ptype.length)
        log.debug("FRAME_WRITE: First frame packet-type RLP={} ({} bytes)", Hex.toHexString(ptype), ptype.length)
      }

      out ++= processFramePayload(frame.payload)

      if (lastFrame) {
        // padding and mac only in last frame
        out ++= processFramePadding(totalSize)
        out ++= processFrameMac()
      }

      log.debug("FRAME_WRITE: Frame[{}] outputLen={}", index, out.length)
      out
    }

    val result = ByteString(bytes.toArray)
    log.debug("FRAME_WRITE: Total output {} bytes", result.length)
    result
  }

  private def processFramePayload(payload: ByteString): ByteString = {
    import com.chipprbots.ethereum.utils.ByteStringUtils._
    var i = 0
    val elements = new ArrayBuffer[ByteStringElement]()
    while (i < payload.length) {
      val bytes = payload.drop(i).take(256).toArray
      enc.processBytes(bytes, 0, bytes.length, bytes, 0)
      secrets.egressMac.update(bytes, 0, bytes.length)
      elements.append(bytes)
      i += bytes.length
    }
    concatByteStrings(elements.iterator)
  }

  private def processFramePadding(totalSize: Int): ByteString = {
    val padding = 16 - (totalSize % 16)
    if (padding < 16) {
      val pad = new Array[Byte](16)
      val buff = new Array[Byte](16)
      enc.processBytes(pad, 0, padding, buff, 0)
      secrets.egressMac.update(buff, 0, padding)
      ByteString(buff.take(padding))
    } else ByteString()
  }

  private def processFrameMac(): ByteString = {
    val macBuffer = new Array[Byte](secrets.egressMac.getDigestSize)
    doSum(secrets.egressMac, macBuffer)
    updateMac(secrets.egressMac, macBuffer, 0, macBuffer, 0, egress = true)
    ByteString(macBuffer.take(16))
  }

  private def makeMacCipher: AESEngine = {
    val macc = new AESEngine: @annotation.nowarn("cat=deprecation")
    macc.init(true, new KeyParameter(secrets.mac))
    macc
  }

  private def updateMac(
      mac: KeccakDigest,
      seed: Array[Byte],
      offset: Int,
      out: Array[Byte],
      outOffset: Int,
      egress: Boolean
  ): Array[Byte] = {
    val aesBlock = new Array[Byte](mac.getDigestSize)
    doSum(mac, aesBlock)
    makeMacCipher.processBlock(aesBlock, 0, aesBlock, 0)

    val length = 16

    (0 until length).foreach { i =>
      aesBlock(i) = (aesBlock(i) ^ seed(i + offset)).toByte
    }

    mac.update(aesBlock, 0, length)
    val result = new Array[Byte](mac.getDigestSize)
    doSum(mac, result)

    if (egress) System.arraycopy(result, 0, out, outOffset, length)
    else
      (0 until length).foreach { i =>
        if (out(i + outOffset) != result(i)) throw new IOException("MAC mismatch")
      }

    result
  }

  private def doSum(mac: KeccakDigest, out: Array[Byte]) =
    new KeccakDigest(mac).doFinal(out, 0)

}
