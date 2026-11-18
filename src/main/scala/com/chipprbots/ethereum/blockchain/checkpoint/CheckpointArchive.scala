package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32C

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockBody.BlockBodyEnc
import com.chipprbots.ethereum.domain.BlockBody.toBlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.toBlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteUtils

/** The `.checkpoint` archive, format version 2 (spec 018 data-model E.1, pinned 2026-11-18): the state of one block,
  * the ancestor window below it, and nothing else. `CheckpointImporter` reads it; `CheckpointExporter` writes it.
  *
  * {{{
  *   #   field              width   value
  *   1   magic              4       C4 C4 C4 C4
  *   2   version            1       0x02 (0x01 is refused, naming the format)
  *   3   chainId            8       u64, big-endian, unsigned
  *   4   genesisHash        32
  *   5   TD-exact flag      1       0x01 exact, 0x00 estimate; any other value is refused
  *   6   header length      4       u32, at most 64 KiB
  *   7   header             (6)     RLP(BlockHeader) of the checkpoint block C; its number N is at least 1
  *   8   TD length          4       u32, 0 to 32
  *   9   TD                 (8)     unsigned, big-endian, no leading zero byte; length 0 is TD 0
  *   10  window headers     tag 0x03 (1), length (4), RLP(BlockHeader): exactly min(256, N), C-1 downwards
  *   11  window bodies      tag 0x04 (1), block hash (32), length (4), RLP(BlockBody): proof of work only
  *   12  nodes              tag 0x01 (1), length (4), node RLP: pre-order, every occurrence, no hash
  *   13  codes              tag 0x02 (1), length (4), code: ascending by keccak(code), no duplicate, no hash
  *   14  end tag            1       0x00
  *   15  CRC32C             4       u32, big-endian, over fields 1 to 14
  * }}}
  *
  * Every integer is fixed-width, big-endian and unsigned; every hash is exactly 32 bytes with no length prefix. Fields
  * 1 to 9 are the '''header block''', and keccak of its bytes is the archive's id ([[Reader.archiveId]]). Sections come
  * in the order of the table; a tag out of that order is refused. Gzip is the operator's wrapper outside the codec.
  *
  * The codec checks '''format''': the fields, the bounds, the order of the sections, the count of window headers (from
  * N), the bound on window bodies, and the order of the codes. Whether the window chains, which bodies belong, whether
  * a node is the one its parent names and whether the archive is this chain's, are the importer's to check: they need
  * the node's chain, or the node stack (data-model E.1, "The importer is a stack machine").
  */
object CheckpointArchive:

  val Magic: Array[Byte] = Array(0xc4.toByte, 0xc4.toByte, 0xc4.toByte, 0xc4.toByte)
  val Version: Byte = 2

  val TagEnd: Byte = 0x00
  val TagNode: Byte = 0x01
  val TagCode: Byte = 0x02
  val TagWindowHeader: Byte = 0x03
  val TagWindowBody: Byte = 0x04

  val HashBytes: Int = 32
  val MaxHeaderBytes: Int = 64 * 1024 // real headers are well under 1 KiB
  val MaxTdBytes: Int = 32
  val MaxEntryBytes: Int = 1 << 26 // 64 MiB: codes are capped at 24 KiB (48 KiB after EIP-7907) and nodes are small

  /** How many headers the window carries below a checkpoint at `number`: `BLOCKHASH`'s reach, or down to genesis. */
  val WindowDepth: Int = 256
  def windowHeaderCount(number: BigInt): Int = BigInt(WindowDepth).min(number).toInt

  /** The most window bodies an archive can carry: the checkpoint block and the four below it, where they have ommers.
    */
  val MaxWindowBodies: Int = 5

  private val MaxU64: BigInt = (BigInt(1) << 64) - 1

  /** The header block (fields 3 to 9).
    *
    * @param chainId
    *   the chain id as the 64 bits of a u64: a `Long` is signed, so compare it with `==` or `java.lang.Long`'s unsigned
    *   methods, never with `<`. [[chainIdUnsigned]] is its value.
    * @param tdExact
    *   whether the exporter's node held the chain-weight invariant
    */
  final case class Header(
      chainId: Long,
      genesisHash: ByteString,
      tdExact: Boolean,
      blockHeader: BlockHeader,
      totalDifficulty: BigInt
  ):
    def chainIdUnsigned: BigInt = BigInt(java.lang.Long.toUnsignedString(chainId))
    def chainWeight: ChainWeight = ChainWeight.totalDifficultyOnly(totalDifficulty)

  object Header:
    /** The 64 bits of `chainId`, when it fits a u64. */
    def chainIdBits(chainId: BigInt): Option[Long] =
      Option.when(chainId >= 0 && chainId <= MaxU64)(chainId.toLong)

  sealed trait Entry extends Product with Serializable

  /** A header of the ancestor window; `rlp` is its bytes as the archive holds them. */
  final case class WindowHeaderEntry(header: BlockHeader, rlp: Array[Byte]) extends Entry

  /** A body of the ancestor window, with the hash of the block it is the body of. */
  final case class WindowBodyEntry(blockHash: ByteString, body: BlockBody, rlp: Array[Byte]) extends Entry

  /** A trie node, by its RLP. Its hash and its position follow from where it stands in the walk. */
  final case class NodeEntry(rlp: Array[Byte]) extends Entry

  /** A contract code. Its hash is keccak of it. */
  final case class CodeEntry(code: Array[Byte]) extends Entry

  case object EndOfStream extends Entry

  /** Why an archive is not of this format. Every one of them is the importer's `BadFormat`. */
  sealed trait DecodeError extends Product with Serializable:
    def describe: String = toString
  case object BadMagic extends DecodeError
  final case class UnsupportedVersion(found: Byte) extends DecodeError:
    override def describe: String =
      if found == 1 then
        "checkpoint archive format version 1 is not supported: this node reads format version 2 only (spec 018 C5c); " +
          "version 1 archives cannot be imported and must be exported again"
      else s"checkpoint archive format version ${found.toInt & 0xff} is not supported: this node reads format version 2"
    override def toString: String = s"UnsupportedVersion(${found.toInt & 0xff}): $describe"
  case object BadCrc extends DecodeError
  final case class Truncated(where: String) extends DecodeError
  final case class Malformed(reason: String) extends DecodeError

  /** Where a stream stands: each section may be followed only by itself (where it allows more) or a later one. */
  private enum Section:
    case HeaderBlock, WindowHeaders, WindowBodies, Nodes, Codes, Ended

  private def sectionOf(tag: Byte): Option[Section] = tag match
    case TagWindowHeader => Some(Section.WindowHeaders)
    case TagWindowBody   => Some(Section.WindowBodies)
    case TagNode         => Some(Section.Nodes)
    case TagCode         => Some(Section.Codes)
    case TagEnd          => Some(Section.Ended)
    case _               => None

  private def tagName(tag: Byte): String = f"0x${tag.toInt & 0xff}%02x"

  /** Unsigned byte order, as the codes are sorted. */
  val unsignedOrder: Ordering[ByteString] = new Ordering[ByteString]:
    def compare(a: ByteString, b: ByteString): Int =
      val n = math.min(a.length, b.length)
      var i = 0
      var c = 0
      while c == 0 && i < n do
        c = Integer.compare(a(i) & 0xff, b(i) & 0xff)
        i += 1
      if c != 0 then c else Integer.compare(a.length, b.length)

  /** The bytes of the header block (fields 1 to 9) of `h`. */
  def headerBlockBytes(h: Header): Array[Byte] =
    val out = new ByteArrayOutputStream()
    def u32(v: Int): Unit =
      out.write((v >>> 24) & 0xff); out.write((v >>> 16) & 0xff); out.write((v >>> 8) & 0xff); out.write(v & 0xff)
    require(h.genesisHash.length == HashBytes, s"genesis hash is ${h.genesisHash.length} bytes, not 32")
    require(h.blockHeader.number.value >= 1, "the checkpoint block is the genesis")
    require(h.totalDifficulty >= 0, s"negative total difficulty ${h.totalDifficulty}")
    val td =
      if h.totalDifficulty == 0 then Array.emptyByteArray else ByteUtils.bigIntToUnsignedByteArray(h.totalDifficulty)
    require(
      td.length <= MaxTdBytes && (td.isEmpty || td(0) != 0),
      s"total difficulty ${h.totalDifficulty} is not a u256"
    )
    val header = h.blockHeader.toBytes: Array[Byte]
    require(header.length <= MaxHeaderBytes, s"header of ${header.length} bytes")
    out.write(Magic)
    out.write(Version.toInt)
    u32((h.chainId >>> 32).toInt)
    u32(h.chainId.toInt)
    out.write(h.genesisHash.toArray)
    out.write(if h.tdExact then 1 else 0)
    u32(header.length)
    out.write(header)
    u32(td.length)
    out.write(td)
    out.toByteArray

  /** keccak of the header block: names the chain, the block and the TD, and is known before the first entry. */
  def archiveIdOf(h: Header): ByteString = ByteString(crypto.kec256(headerBlockBytes(h)))

  /** Writes one archive in section order. A call out of that order is a programming error, and throws. The caller owns
    * `rawOut` and closes it.
    */
  final class Writer(rawOut: OutputStream):
    private val crc = new CRC32C
    // 64 KiB buffer matches geth's snapshot.diskLayer writeBufferSize — friendly to RocksDB downstream.
    private val out = new BufferedOutputStream(rawOut, 65536)
    private var section: Section = Section.HeaderBlock
    private var windowLeft: Int = -1
    private var bodies: Int = 0
    private var lastCode: Option[ByteString] = None
    private var finished = false

    private def w(buf: Array[Byte]): Unit =
      out.write(buf)
      crc.update(buf)
    private def wb(b: Int): Unit =
      out.write(b)
      crc.update(b)
    private def wInt(v: Int): Unit =
      wb((v >>> 24) & 0xff)
      wb((v >>> 16) & 0xff)
      wb((v >>> 8) & 0xff)
      wb(v & 0xff)

    private def enter(next: Section): Unit =
      require(!finished && section != Section.HeaderBlock, "the header block is written first")
      require(next.ordinal >= section.ordinal, s"section $next after $section")
      require(next == Section.WindowHeaders || windowLeft == 0, s"$windowLeft window headers still to write")
      section = next

    private def entry(tag: Byte, payload: Array[Byte]): Unit =
      require(payload.length <= MaxEntryBytes, s"entry of ${payload.length} bytes")
      wb(tag.toInt & 0xff)
      wInt(payload.length)
      w(payload)

    def writeHeader(h: Header): Unit =
      require(section == Section.HeaderBlock, "header already written")
      w(headerBlockBytes(h))
      section = Section.WindowHeaders
      windowLeft = windowHeaderCount(h.blockHeader.number.value)

    def writeWindowHeader(header: BlockHeader): Unit =
      enter(Section.WindowHeaders)
      require(windowLeft > 0, "every window header is written")
      entry(TagWindowHeader, header.toBytes)
      windowLeft -= 1

    def writeWindowBody(blockHash: ByteString, body: BlockBody): Unit =
      enter(Section.WindowBodies)
      require(blockHash.length == HashBytes, s"block hash of ${blockHash.length} bytes")
      require(bodies < MaxWindowBodies, s"more than $MaxWindowBodies window bodies")
      val rlp = body.toBytes: Array[Byte]
      require(rlp.length <= MaxEntryBytes, s"body of ${rlp.length} bytes")
      wb(TagWindowBody.toInt & 0xff)
      w(blockHash.toArray)
      wInt(rlp.length)
      w(rlp)
      bodies += 1

    def writeNode(rlp: Array[Byte]): Unit =
      enter(Section.Nodes)
      entry(TagNode, rlp)

    /** Codes in ascending order of their hash, each once: the caller sorts them. */
    def writeCode(code: Array[Byte]): Unit =
      enter(Section.Codes)
      val hash = ByteString(crypto.kec256(code))
      require(lastCode.forall(unsignedOrder.lt(_, hash)), "codes out of order or repeated")
      lastCode = Some(hash)
      entry(TagCode, code)

    /** Writes the end tag and the CRC32C trailer, the only bytes outside the checksum, and flushes. */
    def finish(): Unit =
      enter(Section.Ended)
      finished = true
      wb(TagEnd.toInt & 0xff)
      val crcValue = crc.getValue.toInt
      out.write((crcValue >>> 24) & 0xff)
      out.write((crcValue >>> 16) & 0xff)
      out.write((crcValue >>> 8) & 0xff)
      out.write(crcValue & 0xff)
      out.flush()

  /** Reads one archive as a stream, checking its format as it goes. The caller owns `rawIn`. */
  final class Reader(rawIn: InputStream):
    private val crc = new CRC32C
    private val in = new BufferedInputStream(rawIn, 65536)
    private var section: Section = Section.HeaderBlock
    private var windowLeft: Int = -1
    private var bodiesLeft: Int = 0
    private var lastCode: Option[ByteString] = None
    private var headerBlock: Option[ByteString] = None
    // the bytes of the header block, kept while it is read
    private var capture: Option[ByteArrayOutputStream] = None

    private def rb(): Int =
      val b = in.read()
      if b < 0 then throw new EOFException("unexpected EOF")
      crc.update(b)
      capture.foreach(_.write(b))
      b
    private def r(n: Int): Array[Byte] =
      val buf = new Array[Byte](n)
      var off = 0
      while off < n do
        val got = in.read(buf, off, n - off)
        if got < 0 then throw new EOFException("unexpected EOF")
        off += got
      crc.update(buf)
      capture.foreach(_.write(buf))
      buf
    private def rInt(): Long =
      (((rb() & 0xff).toLong << 24) | ((rb() & 0xff) << 16).toLong | ((rb() & 0xff) << 8).toLong | (rb() & 0xff).toLong)
    private def rLong(): Long =
      (rInt() << 32) | rInt()

    private def decode[A](what: String)(f: => A): Either[DecodeError, A] =
      try Right(f)
      catch case e: Exception => Left(Malformed(s"$what: ${e.getMessage}"))

    /** keccak of the header block (fields 1 to 9), once [[readHeader]] has read it. */
    def archiveId: Option[ByteString] = headerBlock

    def readHeader(): Either[DecodeError, Header] =
      require(section == Section.HeaderBlock, "header already read")
      section = Section.Ended // until it reads whole
      capture = Some(new ByteArrayOutputStream())
      try
        val result =
          for
            _ <- Either.cond(r(4).sameElements(Magic), (), BadMagic)
            version = rb().toByte
            _ <- Either.cond(version == Version, (), UnsupportedVersion(version))
            chainId = rLong()
            genesisHash = ByteString(r(HashBytes))
            flag = rb()
            _ <- Either.cond(flag == 0 || flag == 1, (), Malformed(s"TD-exact flag ${tagName(flag.toByte)}"))
            headerLength = rInt()
            _ <- Either.cond(headerLength <= MaxHeaderBytes, (), Malformed(s"header length $headerLength"))
            headerRlp = r(headerLength.toInt) // a stream cut short here is truncated, not malformed
            blockHeader <- decode("checkpoint header")(headerRlp.toBlockHeader)
            _ <- Either.cond(blockHeader.number.value >= 1, (), Malformed("the checkpoint block is the genesis"))
            tdLength = rInt()
            _ <- Either.cond(tdLength <= MaxTdBytes, (), Malformed(s"TD length $tdLength"))
            td = r(tdLength.toInt)
            _ <- Either.cond(td.isEmpty || td(0) != 0, (), Malformed("TD with a leading zero byte"))
          yield Header(chainId, genesisHash, flag == 1, blockHeader, if td.isEmpty then BigInt(0) else BigInt(1, td))
        result.foreach { h =>
          headerBlock = capture.map(c => ByteString(crypto.kec256(c.toByteArray)))
          section = Section.WindowHeaders
          windowLeft = windowHeaderCount(h.blockHeader.number.value)
          bodiesLeft = BigInt(MaxWindowBodies).min(h.blockHeader.number.value + 1).toInt
        }
        result
      catch case _: EOFException => Left(Truncated("header block"))
      finally capture = None

    def nextEntry(): Either[DecodeError, Entry] =
      require(section != Section.HeaderBlock, "the header block is read first")
      require(section != Section.Ended, "the stream has ended, or its header block was refused")
      try
        val tag = rb().toByte
        sectionOf(tag) match
          case None => Left(Malformed(s"unknown tag ${tagName(tag)}"))
          case Some(next) if next.ordinal < section.ordinal =>
            Left(Malformed(s"tag ${tagName(tag)} ($next) after the $section section"))
          case Some(next) if next != Section.WindowHeaders && windowLeft > 0 =>
            Left(Malformed(s"tag ${tagName(tag)} ($next) with $windowLeft window headers still to come"))
          case Some(next) =>
            section = next
            next match
              case Section.WindowHeaders =>
                if windowLeft == 0 then Left(Malformed("more window headers than min(256, N)"))
                else
                  windowLeft -= 1
                  for
                    rlp <- payload("window header", MaxHeaderBytes)
                    header <- decode("window header")(rlp.toBlockHeader)
                  yield WindowHeaderEntry(header, rlp)
              case Section.WindowBodies =>
                if bodiesLeft == 0 then Left(Malformed("more window bodies than the window has blocks"))
                else
                  bodiesLeft -= 1
                  val blockHash = ByteString(r(HashBytes))
                  for
                    rlp <- payload("window body", MaxEntryBytes)
                    body <- decode("window body")(rlp.toBlockBody)
                  yield WindowBodyEntry(blockHash, body, rlp)
              case Section.Nodes => payload("node", MaxEntryBytes).map(NodeEntry(_))
              case Section.Codes =>
                payload("code", MaxEntryBytes).flatMap { code =>
                  val hash = ByteString(crypto.kec256(code))
                  if lastCode.exists(!unsignedOrder.lt(_, hash)) then
                    Left(Malformed("a code whose hash is not above the one before it (out of order, or repeated)"))
                  else
                    lastCode = Some(hash)
                    Right(CodeEntry(code))
                }
              case _ => Right(EndOfStream)
      catch case _: EOFException => Left(Truncated(s"$section section"))

    private def payload(what: String, max: Int): Either[DecodeError, Array[Byte]] =
      val length = rInt()
      if length > max then Left(Malformed(s"$what of $length bytes")) else Right(r(length.toInt))

    /** Reads the CRC32C trailer, compares it with the running checksum, and requires the stream to end there. Call only
      * after [[nextEntry]] returned [[EndOfStream]].
      */
    def verifyCrc(): Either[DecodeError, Unit] =
      require(section == Section.Ended && windowLeft == 0, "must reach the end tag first")
      val expected = crc.getValue.toInt
      val b0 = in.read(); val b1 = in.read(); val b2 = in.read(); val b3 = in.read()
      if (b0 | b1 | b2 | b3) < 0 then Left(Truncated("crc trailer"))
      else
        val found = ((b0 & 0xff) << 24) | ((b1 & 0xff) << 16) | ((b2 & 0xff) << 8) | (b3 & 0xff)
        if found != expected then Left(BadCrc)
        else if in.read() >= 0 then Left(Malformed("bytes after the CRC trailer"))
        else Right(())
