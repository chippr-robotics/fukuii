package com.chipprbots.ethereum.console.gfx

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.io.Source
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.console.TuiTestTerminal
import com.chipprbots.ethereum.testing.Tags.*

/** The pure parts of the TUI's graphics (spec 029 slice 2): the palette, pixel buffers, the PNG reader, the art store,
  * half-block cells, the Kitty encoder and the pixel layer's diff. None of them needs a terminal.
  */
class GfxSpec extends AnyFlatSpec with Matchers:

  private def resource(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(name)
    try in.readAllBytes()
    finally in.close()

  private def sheet(name: String): Array[Byte] = resource(s"/tui/art/$name")

  /** A PNG of `width × height` pixels all `argb`: RGBA, one IDAT, no filter. */
  private def png(width: Int, height: Int, argb: Int): Array[Byte] =
    def chunk(kind: String, body: Array[Byte]): Array[Byte] =
      val crc = new java.util.zip.CRC32()
      crc.update(kind.getBytes(StandardCharsets.US_ASCII))
      crc.update(body)
      ByteBuffer
        .allocate(12 + body.length)
        .putInt(body.length)
        .put(kind.getBytes(StandardCharsets.US_ASCII))
        .put(body)
        .putInt(crc.getValue.toInt)
        .array()
    val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(Array[Byte](8, 6, 0, 0, 0)).array()
    val row = Array[Byte](0) ++ Array.fill(width)(
      Array((argb >> 16).toByte, (argb >> 8).toByte, argb.toByte, (argb >>> 24).toByte)
    ).flatten
    val raw = Array.fill(height)(row).flatten
    val deflater = new java.util.zip.Deflater()
    deflater.setInput(raw)
    deflater.finish()
    val buffer = new Array[Byte](raw.length + 1024)
    val data = buffer.take(deflater.deflate(buffer))
    deflater.end()
    Array(0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n').map(_.toByte) ++ chunk("IHDR", header) ++ chunk("IDAT", data) ++
      chunk("IEND", Array.empty)

  // ---- the palette --------------------------------------------------------------------------------------------------

  "The palette" should "be the 240 xterm colours from 16, each found again by its RGB" taggedAs (UnitTest) in {
    val all = (Palette.First to Palette.Last).map(Palette.rgb)
    all.distinct should have size 240
    Palette.rgb(16) shouldBe 0x000000
    Palette.rgb(46) shouldBe 0x00ff00
    Palette.rgb(231) shouldBe 0xffffff
    Palette.rgb(232) shouldBe 0x080808
    Palette.rgb(255) shouldBe 0xeeeeee
    for i <- Palette.First to Palette.Last do Palette.indexOf(Palette.rgb(i)) shouldBe Some(i)
    Palette.indexOf(0x010203) shouldBe None
    an[IllegalArgumentException] should be thrownBy Palette.rgb(15)
  }

  // ---- pixel buffers ------------------------------------------------------------------------------------------------

  private val small = PixelBuffer.of(Seq("ab", ".a"), Map('a' -> 46, 'b' -> 196))

  "A pixel buffer" should "read a pixel, transparent outside it" taggedAs (UnitTest) in {
    small(0, 0) shouldBe 46
    small(1, 0) shouldBe 196
    small(0, 1) shouldBe Palette.Transparent
    small(5, 5) shouldBe Palette.Transparent
    small(-1, 0) shouldBe Palette.Transparent
  }

  it should "enlarge by a whole factor, every pixel a square" taggedAs (UnitTest) in {
    val big = small.enlarge(3)
    (big.width, big.height) shouldBe ((6, 6))
    for x <- 0 until 6; y <- 0 until 6 do big(x, y) shouldBe small(x / 3, y / 3)
    small.enlarge(1) shouldBe small
  }

  it should "crop, compare by its pixels, and give RGBA with clear transparent pixels" taggedAs (UnitTest) in {
    small.crop(1, 0, 1, 2) shouldBe PixelBuffer.of(Seq("b", "a"), Map('a' -> 46, 'b' -> 196))
    small.crop(1, 1, 3, 3)(2, 2) shouldBe Palette.Transparent
    small shouldBe PixelBuffer.of(Seq("ab", ".a"), Map('a' -> 46, 'b' -> 196))
    small.hashCode shouldBe PixelBuffer.of(Seq("ab", ".a"), Map('a' -> 46, 'b' -> 196)).hashCode
    small should not be small.enlarge(2)
    small.rgba.toSeq.map(_ & 0xff) shouldBe Seq(0, 255, 0, 255, 255, 0, 0, 255, 0, 0, 0, 0, 0, 255, 0, 255)
    PixelBuffer.empty(2, 2).nonEmpty shouldBe false
    an[IllegalArgumentException] should be thrownBy PixelBuffer(1, 1, Array[Byte](5)) // not a sprite colour
  }

  // ---- bitmaps ------------------------------------------------------------------------------------------------------

  private val Red = 0xffff0000
  private val Blue = 0xff0000ff

  "A bitmap" should "keep full colour and soft alpha, and send them as RGBA" taggedAs (UnitTest) in {
    val b = Bitmap(2, 1, Array(0x80123456, 0x00abcdef))
    b(0, 0) shouldBe 0x80123456
    b(1, 0) shouldBe 0 // no alpha: no colour either, so two that look alike are equal
    b shouldBe Bitmap(2, 1, Array(0x80123456, 0))
    b.rgba.toSeq.map(_ & 0xff) shouldBe Seq(0x12, 0x34, 0x56, 0x80, 0, 0, 0, 0)
    b.nonEmpty shouldBe true
    Bitmap.empty(3, 3).nonEmpty shouldBe false
    Bitmap.of(small)(1, 0) shouldBe (0xff000000 | Palette.rgb(196))
    Bitmap.of(small)(0, 1) shouldBe 0
    Kitty.upload(7, small) shouldBe Kitty.upload(7, Bitmap.of(small))
  }

  it should "shrink by averaging what each new pixel covers, and grow by blending neighbours" taggedAs (UnitTest) in {
    val stripes = Bitmap(4, 2, Array(Red, Blue, Red, Blue, Red, Blue, Red, Blue))
    val half = stripes.resize(2, 1)
    (half.width, half.height) shouldBe ((2, 1))
    half(0, 0) shouldBe 0xff800080 // red and blue, half each
    half(1, 0) shouldBe half(0, 0)
    stripes.resize(4, 2) shouldBe stripes
    val grown = Bitmap(2, 1, Array(Red, Blue)).resize(4, 1)
    grown(0, 0) shouldBe Red // the edges keep their colour
    grown(3, 0) shouldBe Blue
    (grown(1, 0) >> 16 & 0xff) should be > (grown(1, 0) & 0xff) // nearer red than blue
    (grown(2, 0) & 0xff) should be > (grown(2, 0) >> 16 & 0xff)
  }

  it should "lend a transparent pixel's colour to nothing: a soft edge keeps the colour of what shows" taggedAs (
    UnitTest
  ) in {
    val edge = Bitmap(2, 1, Array(Red, 0x000000ff)).resize(1, 1)
    edge(0, 0) shouldBe 0x80ff0000 // half covered, and red: not purple
  }

  "A sprite" should "enlarge pixel art by whole squares, and fit drawn art to the cell smoothly" taggedAs (UnitTest) in {
    val stripes = PixelBuffer(8, 16, Array.tabulate(8 * 16)(i => (if i % 2 == 0 then 46 else 196).toByte))
    val still = Seq(PoseArt("still", 1, Seq((0, 1))))
    val pixelArt = Sprite("p", 1, 1, still, PixelBuffer.empty(1, 2), Bitmap.of(stripes))
    pixelArt.density shouldBe 1
    val big = pixelArt.hiFrame(0, 0, 3)
    (big.width, big.height) shouldBe ((24, 48))
    big(0, 0) shouldBe big(2, 2) // a 3 × 3 square, untouched
    Set(big(0, 0), big(3, 0)) shouldBe Set(0xff000000 | Palette.rgb(46), 0xff000000 | Palette.rgb(196))
    val drawn = pixelArt.copy(hi = Bitmap(16, 32, Array.fill(16 * 32)(0xff336699)), drawn = true)
    drawn.density shouldBe 2
    drawn.hiFrame(0, 0).width shouldBe 16
    drawn.hiFrame(0, 0, 1)(4, 4) shouldBe 0xff336699
    (drawn.hiFrame(0, 0, 3).width, drawn.hiFrame(0, 0, 3).height) shouldBe ((24, 48))
  }

  // ---- the PNG reader -----------------------------------------------------------------------------------------------

  /** `name` → the SHA-256 the art script wrote for that sheet's pixels. */
  private val drawn: Map[String, String] =
    Using.resource(
      Source.fromInputStream(getClass.getResourceAsStream("/tui/art-pixels.sha256"), StandardCharsets.UTF_8.name())
    )(
      _.getLines().map(_.split("  ")).collect { case Array(hash, name) => name -> hash }.toMap
    )

  private def hashOf(b: PixelBuffer): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(ByteBuffer.allocate(8).putInt(b.width).putInt(b.height).array())
    md.update(b.indices)
    md.digest().map(x => f"${x & 0xff}%02x").mkString

  /** A `hi` sheet's hash: its width and height, then every pixel as `0xAARRGGBB`, four bytes each, big-endian. */
  private def hashOf(b: Bitmap): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(ByteBuffer.allocate(8).putInt(b.width).putInt(b.height).array())
    val pixels = ByteBuffer.allocate(b.width * b.height * 4)
    b.argb.foreach(pixels.putInt)
    md.update(pixels.array())
    md.digest().map(x => f"${x & 0xff}%02x").mkString

  "The PNG reader" should "read every shipped sheet to the pixels the art script drew" taggedAs (UnitTest) in {
    drawn.size should be >= 12
    val store = ArtStore.bundled()
    store.problems shouldBe empty
    for (name, hash) <- drawn do
      val sprite = store.sprite(name.takeWhile(_ != '.')).get
      withClue(s"$name: ")((if name.contains(".lo.") then hashOf(sprite.lo) else hashOf(sprite.hi)) shouldBe hash)
  }

  it should "refuse what is not a sheet, and say why" taggedAs (UnitTest) in {
    Png.read("not a png at all".getBytes) shouldBe Left("not a PNG")
    val good = sheet("badge.lo.png")
    Png.read(good.take(40)).isLeft shouldBe true // cut short
    val corrupt = good.clone()
    corrupt(corrupt.length - 20) = (corrupt(corrupt.length - 20) ^ 0x55).toByte
    Png.read(corrupt).left.toOption.get should include("checksum")
    Png.read(good).map(i => (i.width, i.height)) shouldBe Right((16, 16))
    // A sheet of the wrong size is refused at its header, before a pixel of it is decoded.
    Png.read(good, Some((16, 16))).isRight shouldBe true
    Png.read(good, Some((4096, 4096))) shouldBe Left("is 16x16, must be 4096x4096")
  }

  // ---- the art store ------------------------------------------------------------------------------------------------

  "The art store" should "load the shipped art: seven sprites, their poses and timelines" taggedAs (UnitTest) in {
    val store = ArtStore.bundled()
    store.problems shouldBe empty
    store.sprites.keySet shouldBe ArtStore.Sprites.map(_.name).toSet
    val mascot = store.sprite("mascot").get
    mascot.poses.map(_.name) shouldBe ArtStore.Poses
    mascot.poses.map(_.frames) shouldBe Seq(2, 4, 2, 2, 2, 2, 4)
    // Drawn art, at twice the VGA cell: sharp on a high-density display, fitted down on any other.
    (mascot.density, mascot.drawn) shouldBe ((2, true))
    (mascot.hi.width, mascot.hi.height) shouldBe ((32 * 16 * 4, 16 * 32 * 7))
    (mascot.lo.width, mascot.lo.height) shouldBe ((32, 32 * 7))
    for pose <- mascot.poses.indices do
      mascot.loFrame(pose).nonEmpty shouldBe true
      (mascot.loFrame(pose).width, mascot.loFrame(pose).height) shouldBe ((32, 32))
      for frame <- 0 until mascot.poses(pose).frames do
        val f = mascot.hiFrame(pose, frame)
        (f.width, f.height) shouldBe ((512, 512))
        f.nonEmpty shouldBe true
        val small = mascot.hiFrame(pose, frame, 1)
        (small.width, small.height) shouldBe ((256, 256))
        mascot.hiFrame(pose, frame, 2) shouldBe f
    store.sprite("network-etc").get.poses.map(_.frames) shouldBe Seq(1)
  }

  "art.conf" should "be read as it stands: an include in it fetches nothing" taggedAs (UnitTest) in {
    def shipped(name: String): Option[Array[Byte]] =
      Option(getClass.getResourceAsStream(s"/tui/art/$name")).map { in =>
        try in.readAllBytes()
        finally in.close()
      }
    val including = ArtStore.load {
      case "art.conf" =>
        shipped("art.conf").map(conf =>
          ("include required(file(\"/nonexistent/art-include.conf\"))\n" + new String(conf, "UTF-8")).getBytes("UTF-8")
        )
      case other => shipped(other)
    }
    including.problems shouldBe empty
    including.sprites.keySet shouldBe ArtStore.bundled().sprites.keySet
  }

  "A pose's frame" should "follow its timeline at the frame counter, and loop" taggedAs (UnitTest) in {
    val blink = PoseArt("watching", 2, Seq((0, 15), (1, 1)))
    (0L until 15L).map(blink.frameAt).distinct shouldBe Seq(0)
    blink.frameAt(15) shouldBe 1
    blink.frameAt(16) shouldBe 0
    blink.frameAt(31) shouldBe 1
    PoseArt("syncing", 4, Seq((0, 1), (1, 1), (2, 1), (3, 1))).frameAt(6) shouldBe 2
    PoseArt("still", 1, Seq((0, 1))).frameAt(123456789L) shouldBe 0
  }

  "A sheet that breaks the contract" should "leave its sprite out, be reported by name, and stop nothing" taggedAs (
    UnitTest
  ) in {
    def storeWith(change: PartialFunction[String, Option[Array[Byte]]]): ArtStore =
      ArtStore.load(name =>
        change.applyOrElse(
          name,
          (n: String) => Option(getClass.getResourceAsStream(s"/tui/art/$n")).map(_.readAllBytes())
        )
      )
    val missing = storeWith { case "badge.hi.png" => None }
    missing.sprite("badge") shouldBe None
    missing.problems.mkString should include("badge: a sheet is missing")
    missing.sprite("mascot") should not be empty // the others load
    val wrongSize = storeWith { case "badge.lo.png" => Some(sheet("badge-large.lo.png")) }
    wrongSize.problems.mkString should include("badge.lo.png: is 32x32, must be 16x16")
    // A hi sheet comes at either density, and in any colour; a third size is refused, naming both.
    val stretched = storeWith { case "badge.hi.png" => Some(sheet("badge-large.hi.png")) }
    stretched.problems.mkString should include("badge.hi.png: is 512x512, must be 128x128 or 256x256")
    val lowDensity = storeWith { case "network-etc.hi.png" => Some(png(128, 128, 0xc0123456)) }
    lowDensity.problems shouldBe empty
    (lowDensity.sprite("network-etc").get.density, lowDensity.sprite("network-etc").get.drawn) shouldBe ((1, true))
    // A lo sheet keeps to the 240 colours and hard edges: block mode draws only those.
    val offPalette = storeWith { case "network-etc.lo.png" => Some(png(16, 16, 0xff123456)) }
    offPalette.problems.mkString should include("network-etc.lo.png: a colour outside the 240 (#123456)")
    val soft = storeWith { case "network-etc.lo.png" => Some(png(16, 16, 0x80000000 | Palette.rgb(46))) }
    soft.problems.mkString should include("network-etc.lo.png: has half-transparent pixels")
    val noScene = storeWith { case n if n.startsWith("splash-scene") => None }
    noScene.problems shouldBe empty // the scene is optional
    noScene.sprite("splash-scene") shouldBe None
    val noManifest = storeWith { case "art.conf" => None }
    noManifest.sprites shouldBe empty
    noManifest.problems shouldBe Seq("art.conf: missing")
    val badTimeline = storeWith { case "art.conf" =>
      Some(
        new String(resource("/tui/art/art.conf"), StandardCharsets.UTF_8)
          .replace("[[0, 15], [1, 1]]", "[[0, 15], [7, 1]]")
          .getBytes
      )
    }
    badTimeline.problems.mkString should include("names a frame it does not have")
    badTimeline.sprite("badge") should not be empty
  }

  // ---- half-block cells ---------------------------------------------------------------------------------------------

  "Half-block cells" should "draw two pixels a cell: both, the upper, the lower, or neither" taggedAs (UnitTest) in {
    val terminal = new TuiTestTerminal()
    try
      val buffer = PixelBuffer.of(Seq("ab.a", "a.b."), Map('a' -> 46, 'b' -> 21))
      val rows = Blocks.render(buffer, 5, 1)
      rows should have size 1
      rows.head.columnLength() shouldBe 5
      rows.head.toString shouldBe "▀▀▄▀ "
      val ansi = rows.head.toAnsi(terminal.terminal)
      ansi should include("38;5;46;48;5;46m▀") // both pixels: upper as foreground, lower as background
      (ansi should include).regex("38;5;21[;m]") // one pixel: its colour as the foreground
      (ansi should not).include("48;5;21") // and never as a background
      (ansi should not).include("38;2;") // indices, never RGB
      Blocks.render(buffer, 4, 3).map(_.columnLength()) shouldBe Seq(4, 4, 4) // past the buffer: blank
      Blocks.render(buffer, 4, 3).last.toString shouldBe "    "
    finally terminal.close()
  }

  // ---- the Kitty encoder --------------------------------------------------------------------------------------------

  private val Esc = "\u001b"

  "An upload" should "send RGBA, deflated, in chunks of at most 4,096, the last one marked" taggedAs (UnitTest) in {
    val one = Kitty.upload(7, small)
    one should startWith(s"${Esc}_Ga=t,f=32,o=z,i=7,s=2,v=2,q=2,m=0;")
    one should endWith(s"$Esc\\")
    // Noise does not deflate: it takes several chunks.
    val random = new scala.util.Random(29)
    val noisy = PixelBuffer(96, 96, Array.fill(96 * 96)((16 + random.nextInt(240)).toByte))
    val chunks = Kitty.upload(9, noisy).split(java.util.regex.Pattern.quote(s"$Esc\\")).toSeq
    chunks.size should be > 2
    chunks.head should include("m=1")
    chunks.last should startWith(s"${Esc}_Gm=0,q=2;")
    all(chunks.map(c => c.substring(c.indexOf(';') + 1).length)) should be <= Kitty.ChunkSize
    all(chunks.init.map(c => c.substring(c.indexOf(';') + 1).length % 4)) shouldBe 0
    all(chunks) should include("q=2")
    // The payload is the image: inflating it gives the pixels back.
    val payload = java.util.Base64.getDecoder.decode(chunks.map(c => c.substring(c.indexOf(';') + 1)).mkString)
    val inflater = new java.util.zip.Inflater()
    inflater.setInput(payload)
    val out = new Array[Byte](96 * 96 * 4)
    inflater.inflate(out) shouldBe out.length
    out.toSeq shouldBe noisy.rgba.toSeq
  }

  "A placement" should "move the cursor to the cell, then show the image under the text without moving the cursor" taggedAs (
    UnitTest
  ) in {
    Kitty.place(
      7,
      2,
      row = 4,
      col = 9,
      xOffset = 3,
      yOffset = 5
    ) shouldBe s"$Esc[5;10H${Esc}_Ga=p,i=7,p=2,X=3,Y=5,z=-1,C=1,q=2$Esc\\"
    Kitty.remove(7, 2) shouldBe s"${Esc}_Ga=d,d=i,i=7,p=2,q=2$Esc\\"
    Kitty.RemoveAll shouldBe s"${Esc}_Ga=d,d=A,q=2$Esc\\"
    Kitty.Query shouldBe s"${Esc}_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA$Esc\\"
  }

  // ---- the pixel layer ----------------------------------------------------------------------------------------------

  private val art = ArtStore.bundled()
  private val cell = CellSize(10, 20)
  private def mascot(pose: Int, frame: Int, row: Int = 5, col: Int = 100): GraphicRegion =
    GraphicRegion("operator", row, col, 32, 16, Graphic.SpriteFrame("mascot", pose, frame))
  private def uploads(bytes: String): Int = "a=t,".r.findAllIn(bytes).size
  private def places(bytes: String): Int = "a=p,".r.findAllIn(bytes).size
  private def removes(bytes: String): Int = "a=d,".r.findAllIn(bytes).size

  "The pixel layer" should "send an image once, place it centred, and send nothing for an unchanged frame" taggedAs (
    UnitTest
  ) in {
    val (first, layer) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(2, 0)), cell, art)
    uploads(first) shouldBe 1
    places(first) shouldBe 1
    removes(first) shouldBe 0
    // 320 × 320 pixels of region, a 256 × 256 frame: 32 pixels of margin, three cells and two pixels across.
    first should include(s"$Esc[${5 + 1 + 1};${100 + 3 + 1}H")
    first should include("X=2,Y=12")
    val (again, same) = PixelLayer.diff(layer, Seq(mascot(2, 0)), cell, art)
    again shouldBe ""
    // Only the bookkeeping of when each image was last shown moves.
    same.copy(frame = layer.frame, shownAt = layer.shownAt) shouldBe layer
  }

  // B-259: the images the terminal holds were never freed until a clear; at scale 4 the mascot's frames alone are about
  // 112 MB, a third of kitty's quota.
  it should "free the least recently shown images off screen once the budget is passed, and send one again when shown" taggedAs (
    UnitTest
  ) in {
    val frameBytes = 256L * 256 * 4
    val budget = 2 * frameBytes
    val (_, a) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(1, 0)), cell, art, budget)
    val (_, b) = PixelLayer.diff(a, Seq(mascot(1, 1)), cell, art, budget)
    b.heldBytes shouldBe 2 * frameBytes
    val (third, c) = PixelLayer.diff(b, Seq(mascot(1, 2)), cell, art, budget)
    // Three frames held, two allowed: the least recently shown, frame 0 (image 1), is freed with its data.
    third should include(s"${Esc}_Ga=d,d=I,i=1,q=2")
    c.heldBytes shouldBe 2 * frameBytes
    c.images.values.toSet shouldBe Set(2, 3)
    // Shown again, it is sent again, under a new id: a freed image's id is never reused.
    val (back, d) = PixelLayer.diff(c, Seq(mascot(1, 0)), cell, art, budget)
    uploads(back) shouldBe 1
    d.images.get(PixelLayer.ImageKey.Frame("mascot", 1, 0, PixelLayer.scale(cell))) shouldBe Some(4)
    // The image on screen is never freed, whatever it costs.
    val (_, tiny) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(1, 0)), cell, art, budgetBytes = 1)
    tiny.images should have size 1
  }

  it should "change a frame with one placement and one removal, and send a frame it has shown no second time" taggedAs (
    UnitTest
  ) in {
    val (_, a) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(1, 0)), cell, art)
    val (blink, b) = PixelLayer.diff(a, Seq(mascot(1, 1)), cell, art)
    uploads(blink) shouldBe 1 // the blink frame, the first time
    places(blink) shouldBe 1
    removes(blink) shouldBe 1
    blink.indexOf("a=p,") should be < blink.indexOf("a=d,") // the new one first: no flicker
    val (back, c) = PixelLayer.diff(b, Seq(mascot(1, 0)), cell, art)
    uploads(back) shouldBe 0
    places(back) shouldBe 1
    back.length should be < 120 // a frame change costs no pixel data (FR-017)
    val (blinkAgain, _) = PixelLayer.diff(c, Seq(mascot(1, 1)), cell, art)
    uploads(blinkAgain) shouldBe 0
  }

  it should "remove a region that left, move one that moved, and place everything again after a clear" taggedAs (
    UnitTest
  ) in {
    val (_, a) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(2, 0)), cell, art)
    val (gone, b) = PixelLayer.diff(a, Nil, cell, art)
    (uploads(gone), places(gone), removes(gone)) shouldBe ((0, 0, 1))
    b.placed shouldBe empty
    val (moved, _) = PixelLayer.diff(a, Seq(mascot(2, 0, row = 9)), cell, art)
    (uploads(moved), places(moved), removes(moved)) shouldBe ((0, 1, 0)) // the same placement, re-issued, moves
    // After a clear the terminal holds nothing: the layer is forgotten, and everything goes again.
    val (afterClear, _) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(2, 0)), cell, art)
    (uploads(afterClear), places(afterClear)) shouldBe ((1, 1))
  }

  it should "enlarge a sprite by a whole factor only, by the cell's size" taggedAs (UnitTest) in {
    PixelLayer.scale(CellSize(8, 16)) shouldBe 1
    PixelLayer.scale(CellSize(10, 20)) shouldBe 1
    PixelLayer.scale(CellSize(15, 31)) shouldBe 1
    PixelLayer.scale(CellSize(16, 32)) shouldBe 2
    PixelLayer.scale(CellSize(27, 54)) shouldBe 3
    PixelLayer.scale(CellSize(6, 12)) shouldBe 1 // never below one
    val (big, _) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(2, 0)), CellSize(16, 32), art)
    big should include("s=512,v=512")
  }

  it should "send a chart again only when its pixels change, and skip a sprite the art does not have" taggedAs (
    UnitTest
  ) in {
    def chart(b: PixelBuffer) = GraphicRegion("observatory", 3, 20, 10, 2, Graphic.Chart("observatory", b))
    val bars = PixelBuffer.of(Seq("a.a.", "aaaa"), Map('a' -> 46))
    val (first, a) = PixelLayer.diff(PixelLayer.Empty, Seq(chart(bars)), cell, art)
    (uploads(first), places(first)) shouldBe ((1, 1))
    PixelLayer.diff(a, Seq(chart(bars)), cell, art)._1 shouldBe ""
    val (changed, _) = PixelLayer.diff(a, Seq(chart(PixelBuffer.of(Seq("aaaa", "aaaa"), Map('a' -> 46)))), cell, art)
    (uploads(changed), places(changed)) shouldBe ((1, 1))
    val unknown = GraphicRegion("x", 0, 0, 4, 2, Graphic.SpriteFrame("no-such-sprite", 0, 0))
    PixelLayer.diff(PixelLayer.Empty, Seq(unknown), cell, art)._1 shouldBe ""
  }
