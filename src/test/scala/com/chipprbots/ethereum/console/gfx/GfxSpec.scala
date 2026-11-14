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

  "The PNG reader" should "read every shipped sheet to the pixels the art script drew" taggedAs (UnitTest) in {
    drawn.size should be >= 12
    val store = ArtStore.bundled()
    store.problems shouldBe empty
    for (name, hash) <- drawn do
      val sprite = store.sprite(name.takeWhile(_ != '.')).get
      withClue(s"$name: ")(hashOf(if name.contains(".lo.") then sprite.lo else sprite.hi) shouldBe hash)
  }

  it should "refuse what is not a sheet, and say why" taggedAs (UnitTest) in {
    Png.read("not a png at all".getBytes) shouldBe Left("not a PNG")
    val good = sheet("badge.lo.png")
    Png.read(good.take(40)).isLeft shouldBe true // cut short
    val corrupt = good.clone()
    corrupt(corrupt.length - 20) = (corrupt(corrupt.length - 20) ^ 0x55).toByte
    Png.read(corrupt).left.toOption.get should include("checksum")
    Png.read(good).map(i => (i.width, i.height)) shouldBe Right((16, 16))
  }

  // ---- the art store ------------------------------------------------------------------------------------------------

  "The art store" should "load the shipped art: seven sprites, their poses and timelines" taggedAs (UnitTest) in {
    val store = ArtStore.bundled()
    store.problems shouldBe empty
    store.sprites.keySet shouldBe ArtStore.Sprites.map(_.name).toSet
    val mascot = store.sprite("mascot").get
    mascot.poses.map(_.name) shouldBe ArtStore.Poses
    mascot.poses.map(_.frames) shouldBe Seq(2, 4, 2, 2, 2, 2, 4)
    (mascot.hi.width, mascot.hi.height) shouldBe ((32 * 8 * 4, 16 * 16 * 7))
    (mascot.lo.width, mascot.lo.height) shouldBe ((32, 32 * 7))
    for pose <- mascot.poses.indices do
      mascot.loFrame(pose).nonEmpty shouldBe true
      (mascot.loFrame(pose).width, mascot.loFrame(pose).height) shouldBe ((32, 32))
      for frame <- 0 until mascot.poses(pose).frames do
        val f = mascot.hiFrame(pose, frame)
        (f.width, f.height) shouldBe ((256, 256))
        f.nonEmpty shouldBe true
    store.sprite("network-etc").get.poses.map(_.frames) shouldBe Seq(1)
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
    same shouldBe layer
  }

  it should "change a frame with one placement and one removal, and send a frame it has shown no second time" taggedAs (
    UnitTest
  ) in {
    val (_, a) = PixelLayer.diff(PixelLayer.Empty, Seq(mascot(2, 0)), cell, art)
    val (blink, b) = PixelLayer.diff(a, Seq(mascot(2, 1)), cell, art)
    uploads(blink) shouldBe 1 // the blink frame, the first time
    places(blink) shouldBe 1
    removes(blink) shouldBe 1
    blink.indexOf("a=p,") should be < blink.indexOf("a=d,") // the new one first: no flicker
    val (back, c) = PixelLayer.diff(b, Seq(mascot(2, 0)), cell, art)
    uploads(back) shouldBe 0
    places(back) shouldBe 1
    back.length should be < 120 // a frame change costs no pixel data (FR-017)
    val (blinkAgain, _) = PixelLayer.diff(c, Seq(mascot(2, 1)), cell, art)
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
