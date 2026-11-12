package com.chipprbots.ethereum.ephemery

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

import scala.collection.mutable
import scala.util.control.NonFatal

/** The limits of [[StrictTarReader]]. The defaults are the spec 028 rules (R9); a caller never loosens them. Tests pass
  * smaller values to reach one rule at a time.
  */
final case class TarLimits(
    maxEntries: Int = 256,
    maxEntryBytes: Long = 64L << 20,
    maxTotalBytes: Long = 128L << 20,
    maxCompressedBytes: Long = 32L << 20,
    maxRatio: Int = 50,
    maxNameBytes: Int = 255,
    maxDepth: Int = 8,
    maxLongNameBytes: Int = 1024,
    /** Below this many uncompressed bytes the ratio is not judged: a tiny stream has no meaningful ratio. */
    ratioFloorBytes: Long = 1L << 20
)

/** A rule the archive broke. `entry` is the member's name when it is known. */
final case class TarRefusal(entry: Option[String], rule: String):
  def message: String = entry match
    case Some(e) => s"cannot represent the release archive: entry '${TarRefusal.show(e)}': $rule"
    case None    => s"cannot represent the release archive: $rule"

object TarRefusal:
  /** Names come from the archive: keep control characters out of messages. */
  private def show(s: String): String = s.take(120).map(c => if c < ' ' || c == 0x7f then '?' else c)

/** What a read produced. `el` holds the members named in `elNames`; `staged` lists the files written to the stage. */
final case class TarResult(el: Map[String, Array[Byte]], staged: Vector[String], entries: Int, bytes: Long)

/** A strict streaming `.tar.gz` reader (spec 028 R9). It accepts regular files, directories and GNU long names, and
  * refuses everything else, naming the entry. Counts are of bytes actually read. On a refusal, the stage may hold
  * partial files: the caller deletes the stage directory.
  */
object StrictTarReader:

  private val Block = 512
  private val FilePerms = PosixFilePermissions.fromString("rw-r--r--")
  private val DirPerms = PosixFilePermissions.fromString("rwxr-xr-x")

  /** @param in
    *   the gzip stream, e.g. the bytes of `testnet-all.tar.gz`
    * @param elNames
    *   exact member names kept in memory (and also staged unless excluded)
    * @param stage
    *   an existing, empty directory created for this read; every file is created inside it, new, without following
    *   links, 0644 (files) and 0755 (directories)
    * @param excludes
    *   member names not staged; a name ending in `/` excludes that directory and everything under it
    */
  def read(
      in: InputStream,
      elNames: Set[String],
      stage: Path,
      excludes: Set[String],
      limits: TarLimits = TarLimits()
  ): Either[TarRefusal, TarResult] =
    val counted = new CountingInputStream(in)
    var started = false
    var succeeded = false
    try
      val result =
        try
          if !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS) then
            Left(TarRefusal(None, "the stage is not a directory"))
          else if !isEmptyDir(stage) then Left(TarRefusal(None, "the stage is not empty"))
          else
            started = true
            val gz = new CountingInputStream(new GZIPInputStream(counted, 64 * 1024))
            new Run(counted, gz, elNames, stage.toRealPath(), excludes, limits).run()
        catch
          case _: EOFException => Left(TarRefusal(None, "truncated archive"))
          case e: ZipException => Left(TarRefusal(None, s"bad gzip stream (${e.getMessage})"))
          case e: Refused      => Left(e.refusal)
          case e: IOException  => Left(TarRefusal(None, s"read failure (${e.getClass.getSimpleName})"))
          // InvalidPathException, UnsupportedOperationException from the POSIX calls, and the like
          case NonFatal(e) => Left(TarRefusal(None, s"cannot write the entry (${e.getClass.getName})"))
      succeeded = result.isRight
      result
    finally
      // a refusal, or any exception, leaves the stage as it was found: empty
      if started && !succeeded then cleanStage(stage)

  /** Deletes everything under `stage` without following links; the stage itself stays. Best effort, never throws. */
  private def cleanStage(stage: Path): Unit =
    try
      Files.walkFileTree(
        stage,
        new SimpleFileVisitor[Path]:
          override def visitFile(f: Path, a: BasicFileAttributes): FileVisitResult =
            Files.delete(f) // a symlink is deleted as a link: walkFileTree does not follow links by default
            FileVisitResult.CONTINUE
          override def postVisitDirectory(d: Path, e: IOException): FileVisitResult =
            if d != stage then Files.delete(d)
            FileVisitResult.CONTINUE
      )
    catch case NonFatal(_) => ()

  private def isEmptyDir(dir: Path): Boolean =
    val s = Files.list(dir)
    try !s.findAny().isPresent
    finally s.close()

  final private class Refused(val refusal: TarRefusal) extends RuntimeException(refusal.message, null, false, false)

  final private class CountingInputStream(in: InputStream) extends FilterInputStream(in):
    var count: Long = 0L
    override def read(): Int =
      val b = super.read()
      if b >= 0 then count += 1
      b
    override def read(b: Array[Byte], off: Int, len: Int): Int =
      val n = super.read(b, off, len)
      if n > 0 then count += n
      n

  final private class Run(
      compressed: CountingInputStream,
      gz: CountingInputStream,
      elNames: Set[String],
      stage: Path,
      excludes: Set[String],
      limits: TarLimits
  ):
    private val seen = mutable.HashSet.empty[String]
    private val el = mutable.LinkedHashMap.empty[String, Array[Byte]]
    private val staged = Vector.newBuilder[String]
    private var entries = 0

    private def refuse(entry: Option[String], rule: String): Nothing = throw new Refused(TarRefusal(entry, rule))

    /** Checks the limits that depend on bytes read so far. Called after every chunk. */
    private def checkCounts(entry: Option[String]): Unit =
      if compressed.count > limits.maxCompressedBytes then
        refuse(entry, s"compressed size over ${limits.maxCompressedBytes} bytes")
      if gz.count > limits.maxTotalBytes then refuse(entry, s"more than ${limits.maxTotalBytes} bytes in total")
      if gz.count > limits.ratioFloorBytes && gz.count > limits.maxRatio.toLong * compressed.count then
        refuse(entry, s"expansion ratio over ${limits.maxRatio}")

    /** False on a clean end of stream before the first byte; a stream that ends part-way is truncated. */
    private def readFully(buf: Array[Byte], len: Int): Boolean =
      var off = 0
      var eof = false
      while off < len && !eof do
        val n = gz.read(buf, off, len - off)
        if n < 0 then
          if off > 0 then throw new EOFException()
          eof = true
        else off += n
      !eof

    private def skipExactly(n: Long, entry: Option[String]): Unit =
      val buf = new Array[Byte](16384)
      var left = n
      while left > 0 do
        val r = gz.read(buf, 0, math.min(left, buf.length.toLong).toInt)
        if r < 0 then throw new EOFException()
        left -= r
        checkCounts(entry)

    def run(): Either[TarRefusal, TarResult] =
      val header = new Array[Byte](Block)
      var pendingLong: Option[String] = None
      var ended = false
      while !ended do
        if !readFully(header, Block) then refuse(None, "truncated archive: no end-of-archive marker")
        checkCounts(None)
        if header.forall(_ == 0) then
          // the end marker is two zero blocks
          val second = new Array[Byte](Block)
          if !readFully(second, Block) then refuse(None, "truncated archive: the end-of-archive marker is one block")
          if !second.forall(_ == 0) then refuse(None, "the end-of-archive marker is not two zero blocks")
          ended = true
        else
          val h = parseHeader(header)
          h.typeflag match
            case 'L' =>
              if pendingLong.isDefined then refuse(None, "two GNU long-name headers in a row")
              pendingLong = Some(readLongName(h))
            case _ =>
              val rawName = pendingLong.getOrElse(h.name)
              pendingLong = None
              entries += 1
              if entries > limits.maxEntries then refuse(Some(rawName), s"more than ${limits.maxEntries} entries")
              entry(h, rawName)
      if pendingLong.isDefined then refuse(None, "a GNU long-name header is not followed by an entry")
      drain()
      Right(TarResult(el.toMap, staged.result(), entries, gz.count))

    /** After the end marker only zero bytes (record padding) may follow. Reads to the end of the gzip stream, counting
      * the bytes and letting the gzip trailer check run.
      */
    private def drain(): Unit =
      val buf = new Array[Byte](16384)
      var n = gz.read(buf)
      while n >= 0 do
        var i = 0
        while i < n do
          if buf(i) != 0 then refuse(None, "trailing data after the end of the archive")
          i += 1
        checkCounts(None)
        n = gz.read(buf)
      checkCounts(None)

    final private case class Header(name: String, typeflag: Char, size: Long)

    private def parseHeader(b: Array[Byte]): Header =
      // Magic and version: POSIX "ustar\0" "00", or GNU "ustar " " \0".
      val magic = new String(b, 257, 8, StandardCharsets.ISO_8859_1)
      val posix = magic == "ustar\u000000"
      val gnu = magic == "ustar  \u0000"
      if !posix && !gnu then refuse(None, "not a ustar header (unknown magic)")
      // Checksum: the sum of all bytes with the checksum field read as spaces.
      var sum = 0L
      var i = 0
      while i < Block do
        sum += (if i >= 148 && i < 156 then 0x20 else b(i) & 0xff)
        i += 1
      val stored = parseOctal(b, 148, 8).getOrElse(refuse(None, "header checksum is not a number"))
      if stored != sum then refuse(None, "bad header checksum")
      val name0 = cString(b, 0, 100)
      val name = if posix then
        val prefix = cString(b, 345, 155)
        if prefix.isEmpty then name0 else s"$prefix/$name0"
      else name0
      if (b(124) & 0x80) != 0 then refuse(Some(name), "unsupported size encoding")
      val size = parseOctal(b, 124, 12).getOrElse(refuse(Some(name), "size is not a number"))
      Header(name, b(156).toChar, size)

    private def parseOctal(b: Array[Byte], off: Int, len: Int): Option[Long] =
      val s = new String(b, off, len, StandardCharsets.ISO_8859_1).takeWhile(_ != '\u0000').trim
      if s.isEmpty then Some(0L)
      else if s.forall(c => c >= '0' && c <= '7') && s.length <= 20 then Some(java.lang.Long.parseLong(s, 8))
      else None

    private def cString(b: Array[Byte], off: Int, len: Int): String =
      var end = off
      while end < off + len && b(end) != 0 do end += 1
      decode(b, off, end - off)

    private def decode(b: Array[Byte], off: Int, len: Int): String =
      val dec = StandardCharsets.UTF_8.newDecoder
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      try dec.decode(ByteBuffer.wrap(b, off, len)).toString
      catch case _: CharacterCodingException => refuse(None, "a name is not valid UTF-8")

    private def readLongName(h: Header): String =
      if h.size < 1 || h.size > limits.maxLongNameBytes then
        refuse(None, s"GNU long name of ${h.size} bytes (limit ${limits.maxLongNameBytes})")
      val len = h.size.toInt
      val padded = ((len + Block - 1) / Block) * Block
      val data = new Array[Byte](padded)
      if !readFully(data, padded) then throw new EOFException()
      checkCounts(None)
      val end = if data(len - 1) == 0 then len - 1 else len
      if data.take(end).contains(0.toByte) then refuse(None, "a NUL inside a GNU long name")
      decode(data, 0, end)

    private def typeName(t: Char): Option[String] = t match
      case '0' | '\u0000' | '5' => None
      case '1'                  => Some("hard link")
      case '2'                  => Some("symlink")
      case '3'                  => Some("character device")
      case '4'                  => Some("block device")
      case '6'                  => Some("fifo")
      case '7'                  => Some("contiguous file")
      case 'x'                  => Some("pax extended header")
      case 'g'                  => Some("pax global header")
      case 'K'                  => Some("GNU long link target")
      case 'S'                  => Some("sparse entry")
      case other                => Some(s"unsupported typeflag 0x${Integer.toHexString(other.toInt & 0xff)}")

    /** Validates a member name and returns its normalised form (no trailing slash) and its segments. */
    private def validateName(raw: String, isDir: Boolean): (String, List[String]) =
      val shown = Some(raw)
      if raw.isEmpty then refuse(shown, "empty name")
      if raw.indexOf('\u0000') >= 0 then refuse(shown, "NUL in name")
      if raw.indexOf('\\') >= 0 then refuse(shown, "backslash in name")
      if raw.getBytes(StandardCharsets.UTF_8).length > limits.maxNameBytes then
        refuse(shown, s"name over ${limits.maxNameBytes} bytes")
      if raw.startsWith("/") then refuse(shown, "absolute name")
      if raw.length >= 2 && raw(1) == ':' && raw(0).isLetter then refuse(shown, "name starts with a drive letter")
      if !isDir && raw.endsWith("/") then refuse(shown, "regular file with a directory name")
      val trimmed = raw.stripSuffix("/")
      val segs = trimmed.split("/", -1).toList
      if segs.exists(_ == "..") then refuse(shown, "'..' in name")
      if segs.exists(s => s.isEmpty || s == ".") then refuse(shown, "empty or '.' segment in name")
      if segs.exists(seg => !seg.forall(c => c.isLetterOrDigit && c < 128 || c == '.' || c == '_' || c == '-')) then
        refuse(shown, "character outside [A-Za-z0-9._-] in name")
      if segs.size > limits.maxDepth then refuse(shown, s"more than ${limits.maxDepth} levels")
      if !seen.add(trimmed.toLowerCase(Locale.ROOT)) then refuse(shown, "duplicate name")
      (trimmed, segs)

    private def excluded(isDir: Boolean, name: String): Boolean =
      excludes.exists(e =>
        if e.endsWith("/") then name == e.stripSuffix("/") || name.startsWith(e)
        else name == e && !isDir
      )

    private def entry(h: Header, rawName: String): Unit =
      typeName(h.typeflag).foreach(kind => refuse(Some(rawName), s"$kind is not accepted"))
      val isDir = h.typeflag == '5'
      val (name, segs) = validateName(rawName, isDir)
      if h.size > limits.maxEntryBytes then refuse(Some(name), s"entry over ${limits.maxEntryBytes} bytes")
      if isDir then
        if h.size != 0 then refuse(Some(name), "directory with data")
        if !excluded(isDir = true, name) then makeDirs(name, segs)
      else
        val target = resolve(name)
        val keepInMemory = elNames.contains(name)
        val skip = excluded(isDir = false, name)
        val padded = ((h.size + Block - 1) / Block) * Block
        if skip && !keepInMemory then skipExactly(padded, Some(name))
        else
          val sink = if skip then None else Some(openNew(target, segs.init, name))
          val mem = if keepInMemory then Some(new java.io.ByteArrayOutputStream()) else None
          try copyBody(h.size, padded, name, sink, mem)
          finally sink.foreach(_.close())
          mem.foreach(m => el.update(name, m.toByteArray))
          if !skip then staged += name

    private def copyBody(
        size: Long,
        padded: Long,
        name: String,
        sink: Option[java.io.OutputStream],
        mem: Option[java.io.ByteArrayOutputStream]
    ): Unit =
      val buf = new Array[Byte](16384)
      var left = size
      var actual = 0L
      while left > 0 do
        val n = gz.read(buf, 0, math.min(left, buf.length.toLong).toInt)
        if n < 0 then throw new EOFException()
        actual += n
        left -= n
        if actual > limits.maxEntryBytes then refuse(Some(name), s"entry over ${limits.maxEntryBytes} bytes")
        sink.foreach(_.write(buf, 0, n))
        mem.foreach(_.write(buf, 0, n))
        checkCounts(Some(name))
      skipExactly(padded - size, Some(name))

    private def resolve(name: String): Path =
      val target = stage.resolve(name).normalize()
      if !target.startsWith(stage) || target == stage then refuse(Some(name), "name resolves outside the stage")
      target

    private def makeDirs(name: String, segs: List[String]): Unit =
      var cur = stage
      segs.foreach { s =>
        cur = cur.resolve(s)
        if Files.exists(cur, LinkOption.NOFOLLOW_LINKS) then
          if !Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS) then refuse(Some(name), "conflicts with a file")
        else
          Files.createDirectory(cur, PosixFilePermissions.asFileAttribute(DirPerms))
          setExactMode(cur, DirPerms)
      }

    private def openNew(target: Path, parents: List[String], name: String): java.io.OutputStream =
      makeDirs(name, parents)
      val ch =
        try
          Files.newByteChannel(
            target,
            java.util.Set
              .of[OpenOption](StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(FilePerms)
          )
        catch case _: FileAlreadyExistsException => refuse(Some(name), "already exists in the stage")
      try
        setExactMode(target, FilePerms) // the umask may have removed bits at creation
        java.nio.channels.Channels.newOutputStream(ch)
      catch
        case t: Throwable =>
          try ch.close()
          catch case _: IOException => ()
          throw t

    /** Sets the mode through a view that does not follow links. */
    private def setExactMode(path: Path, perms: java.util.Set[PosixFilePermission]): Unit =
      val view = Files.getFileAttributeView(path, classOf[PosixFileAttributeView], LinkOption.NOFOLLOW_LINKS)
      if view == null then throw new UnsupportedOperationException("no POSIX attribute view")
      view.setPermissions(perms)
