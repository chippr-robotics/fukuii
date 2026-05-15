package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderByteArrayDec
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteUtils

/** Binary file format for an exported chain state — used by `CheckpointImporter` to bootstrap a fresh datadir at a known
  * block without running SNAP. PR-2 adds an HTTP downloader; PR-3 adds the producer (`fukuii checkpoint export`).
  *
  * Layout:
  * {{{
  *   magic            : 4 bytes  (0xC4 0xC4 0xC4 0xC4)
  *   version          : 1 byte   (currently 0x01)
  *   chainId          : 8 bytes  (big-endian uint64)
  *   blockHeaderLen   : 4 bytes  (big-endian uint32)
  *   blockHeader      : <len> bytes (RLP(BlockHeader))
  *   chainWeightLen   : 4 bytes  (big-endian uint32)
  *   chainWeight      : <len> bytes (big-endian unsigned integer — totalDifficulty)
  *   entries          : zero or more {tag:1, hashLen:1, hash:hashLen, dataLen:4, data:dataLen}
  *                      tag=0x01 = trie node, tag=0x02 = bytecode
  *   end-of-stream    : 1 byte (tag=0x00; no hash/data follow)
  *   crc32            : 4 bytes  (CRC32 over every preceding byte; excludes this field itself)
  * }}}
  *
  * Gzip wrapping is the operator's choice — pass a `GZIPInputStream`/`GZIPOutputStream` to `Reader`/`Writer` if desired.
  * The codec itself doesn't compress, so a `.checkpoint.gz` is just gzip-of-uncompressed-checkpoint.
  */
object CheckpointArchive {

  val Magic: Array[Byte] = Array(0xc4.toByte, 0xc4.toByte, 0xc4.toByte, 0xc4.toByte)
  val Version: Byte = 1

  val TagEnd: Byte = 0x00
  val TagNode: Byte = 0x01
  val TagBytecode: Byte = 0x02

  private val MaxHeaderBytes: Int = 64 * 1024 // real headers are well under 1 KiB
  private val MaxWeightBytes: Int = 64 // 256-bit unsigned int + slack
  private val MaxEntryBytes: Int = 1 << 26 // 64 MiB — bytecodes capped at 24 KiB on-chain, trie nodes << 1 KiB

  /** Header section of an archive — small, always loaded into memory. */
  final case class Header(
      chainId: Long,
      blockHeader: BlockHeader,
      chainWeight: ChainWeight
  )

  sealed trait Entry extends Product with Serializable
  final case class NodeEntry(hash: ByteString, rlp: Array[Byte]) extends Entry
  final case class BytecodeEntry(hash: ByteString, code: Array[Byte]) extends Entry
  case object EndOfStream extends Entry

  sealed trait DecodeError extends Product with Serializable
  case object BadMagic extends DecodeError
  final case class UnsupportedVersion(found: Byte) extends DecodeError
  case object BadCrc extends DecodeError
  final case class Truncated(where: String) extends DecodeError
  final case class Malformed(reason: String) extends DecodeError

  /** Stream-oriented writer. Caller owns the OutputStream and is responsible for closing it. */
  final class Writer(rawOut: OutputStream) {
    private val crc = new CRC32
    // 64 KiB buffer matches geth's snapshot.diskLayer writeBufferSize — friendly to RocksDB downstream.
    private val out = new BufferedOutputStream(rawOut, 65536)
    private var headerWritten = false
    private var finished = false

    private def w(buf: Array[Byte]): Unit = {
      out.write(buf)
      crc.update(buf)
    }
    private def wb(b: Int): Unit = {
      out.write(b)
      crc.update(b)
    }
    private def wInt(v: Int): Unit = {
      wb((v >>> 24) & 0xff)
      wb((v >>> 16) & 0xff)
      wb((v >>> 8) & 0xff)
      wb(v & 0xff)
    }
    private def wLong(v: Long): Unit = {
      wInt(((v >>> 32) & 0xffffffffL).toInt)
      wInt((v & 0xffffffffL).toInt)
    }

    def writeHeader(h: Header): Unit = {
      require(!headerWritten, "header already written")
      headerWritten = true
      w(Magic)
      wb(Version.toInt & 0xff)
      wLong(h.chainId)
      val hdrBytes = h.blockHeader.toBytes
      wInt(hdrBytes.length)
      w(hdrBytes)
      val weightBytes = ByteUtils.bigIntToUnsignedByteArray(h.chainWeight.totalDifficulty)
      wInt(weightBytes.length)
      w(weightBytes)
    }

    def writeNode(hash: ByteString, rlpBytes: Array[Byte]): Unit = {
      require(headerWritten && !finished, "writer state invalid")
      require(hash.length > 0 && hash.length <= 255, s"hash length out of range: ${hash.length}")
      wb(TagNode.toInt & 0xff)
      wb(hash.length & 0xff)
      w(hash.toArray)
      wInt(rlpBytes.length)
      w(rlpBytes)
    }

    def writeBytecode(hash: ByteString, code: Array[Byte]): Unit = {
      require(headerWritten && !finished, "writer state invalid")
      require(hash.length > 0 && hash.length <= 255, s"hash length out of range: ${hash.length}")
      wb(TagBytecode.toInt & 0xff)
      wb(hash.length & 0xff)
      w(hash.toArray)
      wInt(code.length)
      w(code)
    }

    /** Write the end-of-stream marker followed by the CRC32 trailer. The trailer is the only data that bypasses the
      * running checksum.
      */
    def finish(): Unit = {
      require(headerWritten && !finished, "writer state invalid")
      finished = true
      wb(TagEnd.toInt & 0xff)
      val crcValue = crc.getValue.toInt
      out.write((crcValue >>> 24) & 0xff)
      out.write((crcValue >>> 16) & 0xff)
      out.write((crcValue >>> 8) & 0xff)
      out.write(crcValue & 0xff)
      out.flush()
    }
  }

  /** Stream-oriented reader. Caller owns the InputStream. */
  final class Reader(rawIn: InputStream) {
    private val crc = new CRC32
    private val in = new BufferedInputStream(rawIn, 65536)
    private var headerRead = false
    private var endSeen = false

    private def rb(): Int = {
      val b = in.read()
      if (b < 0) throw new EOFException("unexpected EOF")
      crc.update(b)
      b
    }
    private def r(n: Int): Array[Byte] = {
      val buf = new Array[Byte](n)
      var off = 0
      while (off < n) {
        val got = in.read(buf, off, n - off)
        if (got < 0) throw new EOFException("unexpected EOF")
        off += got
      }
      crc.update(buf)
      buf
    }
    private def rInt(): Int =
      ((rb() & 0xff) << 24) | ((rb() & 0xff) << 16) | ((rb() & 0xff) << 8) | (rb() & 0xff)
    private def rLong(): Long =
      ((rInt().toLong & 0xffffffffL) << 32) | (rInt().toLong & 0xffffffffL)

    def readHeader(): Either[DecodeError, Header] =
      try {
        require(!headerRead, "header already read")
        headerRead = true
        val magicBuf = r(4)
        if (!magicBuf.sameElements(Magic)) Left(BadMagic)
        else {
          val version = rb().toByte
          if (version != Version) Left(UnsupportedVersion(version))
          else {
            val chainId = rLong()
            val hdrLen = rInt()
            if (hdrLen < 0 || hdrLen > MaxHeaderBytes) Left(Malformed(s"hdrLen=$hdrLen"))
            else {
              val hdrBytes = r(hdrLen)
              val header =
                try hdrBytes.toBlockHeader
                catch { case e: Exception => return Left(Malformed(s"blockHeader: ${e.getMessage}")) }
              val weightLen = rInt()
              if (weightLen < 0 || weightLen > MaxWeightBytes) Left(Malformed(s"weightLen=$weightLen"))
              else {
                val weightBytes = r(weightLen)
                val totalDifficulty = if (weightBytes.isEmpty) BigInt(0) else BigInt(1, weightBytes)
                Right(Header(chainId, header, ChainWeight(totalDifficulty)))
              }
            }
          }
        }
      } catch {
        case _: EOFException => Left(Truncated("header-section"))
      }

    def nextEntry(): Either[DecodeError, Entry] =
      try {
        require(headerRead, "must read header first")
        require(!endSeen, "stream already ended")
        val tag = rb().toByte
        tag match {
          case TagEnd =>
            endSeen = true
            Right(EndOfStream)
          case TagNode | TagBytecode =>
            val hashLen = rb() & 0xff
            if (hashLen == 0 || hashLen > 64) Left(Malformed(s"hashLen=$hashLen"))
            else {
              val hash = r(hashLen)
              val dataLen = rInt()
              if (dataLen < 0 || dataLen > MaxEntryBytes) Left(Malformed(s"dataLen=$dataLen"))
              else {
                val data = r(dataLen)
                if (tag == TagNode) Right(NodeEntry(ByteString(hash), data))
                else Right(BytecodeEntry(ByteString(hash), data))
              }
            }
          case other => Left(Malformed(s"unknown tag=0x${(other.toInt & 0xff).toHexString}"))
        }
      } catch {
        case _: EOFException => Left(Truncated("entry"))
      }

    /** Read the 4-byte CRC trailer and compare against the running checksum. Bytes read here are not folded into the
      * checksum. Must be called only after `nextEntry()` returned `EndOfStream`.
      */
    def verifyCrc(): Either[DecodeError, Unit] = {
      require(endSeen, "must reach end-of-stream first")
      val expected = crc.getValue.toInt
      val b0 = in.read(); val b1 = in.read(); val b2 = in.read(); val b3 = in.read()
      if ((b0 | b1 | b2 | b3) < 0) Left(Truncated("crc-trailer"))
      else {
        val found = ((b0 & 0xff) << 24) | ((b1 & 0xff) << 16) | ((b2 & 0xff) << 8) | (b3 & 0xff)
        if (found == expected) Right(()) else Left(BadCrc)
      }
    }
  }
}
