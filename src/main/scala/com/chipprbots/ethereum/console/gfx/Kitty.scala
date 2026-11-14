package com.chipprbots.ethereum.console.gfx

import java.util.Base64
import java.util.zip.Deflater

/** The Kitty graphics protocol, as the TUI speaks it (spec 029 research §4): images sent in the escape sequence itself,
  * deflated; shown by a placement at the cursor; removed by id. Every command but the query carries `q=2`, so the
  * terminal answers none of them.
  */
object Kitty:

  private val Start = "\u001b_G"
  private val End = "\u001b\\"

  /** The most payload a chunk carries. */
  val ChunkSize: Int = 4096

  /** The image id of the support query. */
  val QueryId: Int = 31

  /** "Do you speak the protocol?": a terminal that does answers `ESC _ G i=31;OK ESC \` (research §4.2). */
  val Query: String = s"${Start}i=$QueryId,s=1,v=1,a=q,t=d,f=24;AAAA$End"

  /** The size of a cell in pixels: answered `ESC [ 6 ; height ; width t`. */
  val CellSizeQuery: String = "\u001b[16t"

  /** Primary device attributes: every terminal answers it, so it ends the wait for the two above. */
  val DeviceAttributesQuery: String = "\u001b[c"

  /** Sends `image` to the terminal under `id`, without showing it: RGBA, deflated, in chunks. */
  def upload(id: Int, image: PixelBuffer): String =
    val payload = Base64.getEncoder.encodeToString(deflate(image.rgba))
    val chunks = payload.grouped(ChunkSize).toSeq
    val sb = new StringBuilder
    chunks.zipWithIndex.foreach { (chunk, i) =>
      val more = if i < chunks.size - 1 then 1 else 0
      if i == 0 then sb.append(s"${Start}a=t,f=32,o=z,i=$id,s=${image.width},v=${image.height},q=2,m=$more;")
      else sb.append(s"${Start}m=$more,q=2;")
      sb.append(chunk).append(End)
    }
    sb.toString

  /** Shows image `id` with its top-left corner `(xOffset, yOffset)` pixels into the cell at (`row`, `col`), both from
    * 0, as placement `placement`, under the text, leaving the cursor where the frame writer expects it.
    */
  def place(id: Int, placement: Int, row: Int, col: Int, xOffset: Int, yOffset: Int): String =
    s"\u001b[${row + 1};${col + 1}H${Start}a=p,i=$id,p=$placement,X=$xOffset,Y=$yOffset,z=-1,C=1,q=2$End"

  /** Takes placement `placement` of image `id` off the screen; the image stays with the terminal. */
  def remove(id: Int, placement: Int): String = s"${Start}a=d,d=i,i=$id,p=$placement,q=2$End"

  /** Takes every placement off the screen and frees every image: no image outlives the TUI (FR-016). */
  val RemoveAll: String = s"${Start}a=d,d=A,q=2$End"

  private def deflate(bytes: Array[Byte]): Array[Byte] =
    val deflater = new Deflater(Deflater.BEST_SPEED)
    try
      deflater.setInput(bytes)
      deflater.finish()
      val out = new java.io.ByteArrayOutputStream(bytes.length / 8 + 64)
      val buffer = new Array[Byte](8192)
      while !deflater.finished() do out.write(buffer, 0, deflater.deflate(buffer))
      out.toByteArray
    finally deflater.end()
