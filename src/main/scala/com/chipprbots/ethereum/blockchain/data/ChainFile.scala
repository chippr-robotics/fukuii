package com.chipprbots.ethereum.blockchain.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.domain.Block.BlockEnc
import com.chipprbots.ethereum.utils.Logger

/** The chain file format shared by `admin_exportChain`, `admin_importChain`, the `export` / `import` launcher commands
  * and the startup `import-chain-file`: go-ethereum's.
  *
  * A chain file is a stream of RLP-encoded blocks, back to back, with no framing: go-ethereum `BlockChain.ExportN`
  * (core/blockchain.go, `block.EncodeRLP(w)`), read back by `rlp.NewStream` in `cmd/utils.ImportChain` and
  * `AdminAPI.ImportChain`. A path ending in `.gz` is gzip-compressed on write and decompressed on read, as geth does.
  *
  * Writing uses the block's consensus (network) encoding — header, transactions, ommers and, from Shanghai, withdrawals
  * — byte-identical to geth's `admin_exportChain`. geth's `export` command may append a fifth item, the EIP-7928 block
  * access list (`exportBlock`, cmd/utils/cmd.go); [[Block.BlockDec]] accepts and ignores it, and the importer
  * recomputes the list and checks it against the header's `blockAccessListHash` anyway.
  *
  * Files written by fukuii's `admin_exportChain` before this format was fixed put a 4-byte big-endian length before
  * each block. Those are still read (see [[ChainFile.Reader]]), with a warning.
  */
object ChainFile extends Logger:

  /** A malformed or unreadable chain file. `index` is the 0-based position of the block in the file. */
  final case class FormatError(index: Long, offset: Long, message: String):
    def describe: String = s"chain file: block #$index in file (byte offset $offset): $message"

  /** Upper bound on one block's encoded size. Bigger than any block a chain can produce (blocks are bounded by gas,
    * blobs travel outside the block) and small enough that a corrupt length cannot exhaust the heap.
    */
  val MaxBlockBytes: Long = 64L * 1024 * 1024

  def isGzip(path: Path): Boolean = path.getFileName.toString.endsWith(".gz")

  /** The bytes a block is written as: its consensus RLP encoding. */
  def encode(block: Block): Array[Byte] = block.toBytes

  /** Opens `path` for reading, gunzipping a `.gz` path. */
  def openInput(path: Path): InputStream =
    val raw = new BufferedInputStream(Files.newInputStream(path), 1 << 16)
    if isGzip(path) then new BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16) else raw

  /** Wraps `out` for writing to `path`, gzipping a `.gz` path. Closing the returned stream closes `out`. */
  def wrapOutput(path: Path, out: OutputStream): OutputStream =
    val buffered = new BufferedOutputStream(out, 1 << 16)
    if isGzip(path) then new GZIPOutputStream(buffered, 1 << 16) else buffered

  /** Reads one block at a time from a chain file stream; never holds more than one block in memory.
    *
    * The format is detected from the first bytes. A bare stream starts with an RLP list prefix (`0xc0`..`0xff`; a block
    * is always longer than 55 bytes, so in practice `0xf8`..`0xff`). A legacy length-prefixed file starts with the
    * 4-byte big-endian length of its first block — for any real block, `0x00` — followed by that list prefix. The two
    * cannot be confused: no RLP list starts with a byte below `0xc0`.
    */
  final class Reader(in: InputStream, source: String):
    private val stream: InputStream = if in.markSupported() then in else new BufferedInputStream(in)
    private var offset: Long = 0
    private var index: Long = 0
    private var formatKnown = false
    private var lengthPrefixed = false

    /** Whether the file uses fukuii's pre-0.9 length-prefixed framing. Valid after the first [[next]]. */
    def isLegacyLengthPrefixed: Boolean = lengthPrefixed

    /** Number of blocks read so far. */
    def blocksRead: Long = index

    /** The next block, `Right(None)` at a clean end of file, or the reason the file cannot be read further. */
    def next(): Either[FormatError, Option[Block]] =
      try
        if !formatKnown then detectFormat()
        val first = stream.read()
        if first == -1 then Right(None)
        else
          val start = offset
          offset += 1
          val bytes =
            if lengthPrefixed then readLengthPrefixed(first, start)
            else readRlpItem(first, start)
          val block =
            try bytes.toBlock
            catch
              case e: Exception =>
                throw ChainFileException(FormatError(index, start, s"not a valid block: ${e.getMessage}"))
          index += 1
          Right(Some(block))
      catch
        case ChainFileException(err) => Left(err)
        case e: java.util.zip.ZipException =>
          Left(FormatError(index, offset, s"gzip stream is corrupt: ${e.getMessage}"))
        case _: EOFException =>
          Left(FormatError(index, offset, "file ends in the middle of a block (truncated)"))

    private def detectFormat(): Unit =
      formatKnown = true
      stream.mark(8)
      val head = new Array[Byte](5)
      val n = stream.readNBytes(head, 0, 5)
      stream.reset()
      if n == 5 && (head(0) & 0xff) < 0xc0 && (head(4) & 0xff) >= 0xc0 then
        lengthPrefixed = true
        log.warn(
          s"$source uses the length-prefixed framing of fukuii's old admin_exportChain, not go-ethereum's chain format; " +
            "reading it anyway. Re-export with this version to get a file geth can import."
        )

    /** Reads the rest of an RLP list whose first byte (already consumed) is `first`. */
    private def readRlpItem(first: Int, start: Long): Array[Byte] =
      if first < 0xc0 then
        throw ChainFileException(
          FormatError(index, start, f"expected an RLP list (a block), found prefix byte 0x$first%02x")
        )
      val lengthBytes: Array[Byte] =
        if first <= 0xf7 then Array.emptyByteArray
        else
          val bytes = readFully(first - 0xf7)
          if bytes(0) == 0 then
            throw ChainFileException(FormatError(index, start, "non-canonical RLP length (leading zero)"))
          bytes
      val payloadLength =
        if first <= 0xf7 then (first - 0xc0).toLong
        else lengthBytes.foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff))
      checkSize(payloadLength, start)
      val lengthOfLength = lengthBytes.length
      val out = new Array[Byte](1 + lengthOfLength + payloadLength.toInt)
      out(0) = first.toByte
      System.arraycopy(lengthBytes, 0, out, 1, lengthOfLength)
      readInto(out, 1 + lengthOfLength)
      out

    private def readLengthPrefixed(first: Int, start: Long): Array[Byte] =
      val rest = readFully(3)
      val length = ((first.toLong & 0xff) << 24) | ((rest(0).toLong & 0xff) << 16) |
        ((rest(1).toLong & 0xff) << 8) | (rest(2).toLong & 0xff)
      checkSize(length, start)
      val out = new Array[Byte](length.toInt)
      readInto(out, 0)
      out

    private def checkSize(length: Long, start: Long): Unit =
      if length <= 0 || length > MaxBlockBytes then
        throw ChainFileException(
          FormatError(index, start, s"block length $length is out of range (corrupt file?)")
        )

    private def readFully(n: Int): Array[Byte] =
      val out = new Array[Byte](n)
      readInto(out, 0)
      out

    private def readInto(buf: Array[Byte], from: Int): Unit =
      val wanted = buf.length - from
      val got = stream.readNBytes(buf, from, wanted)
      offset += got
      if got < wanted then throw new EOFException()

  final private case class ChainFileException(error: FormatError) extends Exception(error.describe)
