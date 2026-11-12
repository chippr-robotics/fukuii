package com.chipprbots.ethereum.ephemery

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** Builds tar and tar.gz bytes in memory for the reader specs. */
object TarFixture:

  def header(
      name: String,
      typeflag: Char = '0',
      size: Long = 0,
      mode: Int = 420,
      gnu: Boolean = true,
      badChecksum: Boolean = false,
      prefix: String = ""
  ): Array[Byte] =
    val b = new Array[Byte](512)
    def put(off: Int, s: String, max: Int): Unit =
      val bytes = s.getBytes(StandardCharsets.UTF_8)
      System.arraycopy(bytes, 0, b, off, math.min(bytes.length, max))
    put(0, name, 100)
    put(100, f"$mode%07o", 7)
    put(108, "0000000", 7)
    put(116, "0000000", 7)
    put(124, f"$size%011o", 11)
    put(136, "00000000000", 11)
    java.util.Arrays.fill(b, 148, 156, 0x20.toByte)
    b(156) = typeflag.toByte
    if gnu then put(257, "ustar  ", 8) else put(257, "ustar\u000000", 8)
    if prefix.nonEmpty then put(345, prefix, 155)
    var sum = 0L
    b.foreach(x => sum += (x & 0xff))
    put(148, f"$sum%06o", 6)
    b(154) = 0
    b(155) = 0x20
    if badChecksum then b(148) = '7'.toByte
    b

  def padded(data: Array[Byte]): Array[Byte] =
    val n = ((data.length + 511) / 512) * 512
    java.util.Arrays.copyOf(data, n)

  def file(
      name: String,
      data: Array[Byte] = Array.emptyByteArray,
      mode: Int = 420,
      gnu: Boolean = true,
      typeflag: Char = '0'
  ): Array[Byte] =
    header(name, typeflag, data.length.toLong, mode, gnu) ++ padded(data)

  def dir(name: String, mode: Int = 493): Array[Byte] = header(name, '5', 0, mode)

  /** A GNU long-name header carrying `name`, followed by the entry itself under a truncated name. */
  def longNamed(name: String, data: Array[Byte]): Array[Byte] =
    val payload = name.getBytes(StandardCharsets.UTF_8) :+ 0.toByte
    header("././@LongLink", 'L', payload.length.toLong) ++ padded(payload) ++ file(name.take(99), data)

  def tar(parts: Array[Byte]*): Array[Byte] = parts.toArray.flatten ++ new Array[Byte](1024)

  def gz(bytes: Array[Byte]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val g = new GZIPOutputStream(out)
    g.write(bytes)
    g.close()
    out.toByteArray

  def tgz(parts: Array[Byte]*): Array[Byte] = gz(tar(parts*))
