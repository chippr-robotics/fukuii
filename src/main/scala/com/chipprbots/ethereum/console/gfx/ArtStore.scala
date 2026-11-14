package com.chipprbots.ethereum.console.gfx

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigIncludeContext
import com.typesafe.config.ConfigIncluder
import com.typesafe.config.ConfigIncluderClasspath
import com.typesafe.config.ConfigIncluderFile
import com.typesafe.config.ConfigIncluderURL
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigParseOptions

/** One pose of a sprite (contracts/art.md): how many frames it has, and how long each stays up.
  *
  * @param timeline
  *   pairs of (frame, ticks it stays up), played in a loop; a tick is a frame of the TUI's clock
  */
final case class PoseArt(name: String, frames: Int, timeline: Seq[(Int, Int)]):

  /** The frame on screen at `tick`: a function of the frame counter alone (spec 029 FR-019). */
  def frameAt(tick: Long): Int =
    val period = timeline.map(_._2).sum
    if period <= 0 then 0
    else
      val at = Math.floorMod(tick, period.toLong).toInt
      var passed = 0
      timeline
        .find { (_, ticks) =>
          passed += ticks
          at < passed
        }
        .fold(0)(_._1)

/** A sprite: its region in cells, its poses, and its two sheets (contracts/art.md).
  *
  * @param lo
  *   for block mode: one frame a pose, `cols × 2·rows` pixels each, poses down the sheet
  * @param hi
  *   for pixel mode: `8·cols × 16·rows` pixels a frame, frames across, poses down
  */
final case class Sprite(name: String, cols: Int, rows: Int, poses: Seq[PoseArt], lo: PixelBuffer, hi: PixelBuffer):

  /** A pose's frame for block mode. */
  def loFrame(pose: Int): PixelBuffer = lo.crop(0, pose * rows * 2, cols, rows * 2)

  /** A pose's frame for pixel mode. */
  def hiFrame(pose: Int, frame: Int): PixelBuffer =
    hi.crop(
      frame * cols * ArtStore.CellWidth,
      pose * rows * ArtStore.CellHeight,
      cols * ArtStore.CellWidth,
      rows * ArtStore.CellHeight
    )

/** The art the TUI draws (spec 029 FR-060): the sprite sheets that kept contracts/art.md, and what was wrong with the
  * ones that did not. A sprite that breaks a rule is left out, so its region shows the text the frame carries anyway; a
  * bad sheet never stops the node.
  */
final class ArtStore(val sprites: Map[String, Sprite], val problems: Seq[String]):
  def sprite(name: String): Option[Sprite] = sprites.get(name)

object ArtStore:

  /** The canvas of a `hi` sheet: 8 × 16 pixels a cell, the VGA text cell. */
  val CellWidth: Int = 8
  val CellHeight: Int = 16

  /** The largest sheet file read. */
  val MaxBytes: Int = 4 * 1024 * 1024

  /** The poses of a character sprite, in the order of the sheet's rows. */
  val Poses: Seq[String] = Seq("starting", "syncing", "watching", "warning", "error", "asleep", "celebrating")

  /** A sprite the contract names.
    *
    * @param poses
    *   7 for a character, 1 for a still
    * @param mostFrames
    *   the most frames one of its poses may have
    */
  final case class Expected(name: String, cols: Int, rows: Int, poses: Int, mostFrames: Int, optional: Boolean = false)

  val Sprites: Seq[Expected] = Seq(
    Expected("mascot", 32, 16, 7, 4),
    Expected("mascot-small", 16, 8, 7, 2),
    Expected("badge", 16, 8, 1, 1),
    Expected("badge-large", 32, 16, 1, 1),
    Expected("network-etc", 16, 8, 1, 1),
    Expected("network-eth", 16, 8, 1, 1),
    Expected("splash-scene", 80, 24, 1, 1, optional = true)
  )

  val Empty: ArtStore = new ArtStore(Map.empty, Nil)

  /** `art.conf` is read as it stands: an `include` in it fetches nothing, by name, from a file, from the class path or
    * from the network.
    */
  private object NoIncluder
      extends ConfigIncluder
      with ConfigIncluderFile
      with ConfigIncluderURL
      with ConfigIncluderClasspath:
    private def nothing: ConfigObject = ConfigFactory.empty().root()
    override def withFallback(fallback: ConfigIncluder): ConfigIncluder = this
    override def include(context: ConfigIncludeContext, what: String): ConfigObject = nothing
    override def includeFile(context: ConfigIncludeContext, what: java.io.File): ConfigObject = nothing
    override def includeURL(context: ConfigIncludeContext, what: java.net.URL): ConfigObject = nothing
    override def includeResources(context: ConfigIncludeContext, what: String): ConfigObject = nothing

  private val NoIncludes: ConfigParseOptions = ConfigParseOptions.defaults().setIncluder(NoIncluder)

  /** The art shipped with the node, under `tui/art/` in its resources. */
  def bundled(): ArtStore =
    load { name =>
      Option(getClass.getResourceAsStream(s"/tui/art/$name")).map { in =>
        try in.readNBytes(MaxBytes + 1)
        finally in.close()
      }
    }

  /** The art in `directory`: `fukuii.tui.art-dir`, for trying a replacement sheet. */
  def fromDirectory(directory: Path): ArtStore =
    load { name =>
      val file = directory.resolve(name)
      if Files.isRegularFile(file) && Files.size(file) <= MaxBytes + 1 then Try(Files.readAllBytes(file)).toOption
      else if Files.isRegularFile(file) then Some(new Array[Byte](MaxBytes + 1)) // too large: reported below
      else None
    }

  /** The art `read` gives, by file name: `art.conf` and the sheets it implies. */
  def load(read: String => Option[Array[Byte]]): ArtStore =
    read("art.conf") match
      case None => new ArtStore(Map.empty, Seq("art.conf: missing"))
      case Some(bytes) =>
        Try(ConfigFactory.parseString(new String(bytes, StandardCharsets.UTF_8), NoIncludes)).toEither match
          case Left(e) => new ArtStore(Map.empty, Seq(s"art.conf: ${e.getMessage}"))
          case Right(conf) =>
            val results = Sprites.map(expected => sprite(expected, conf, read))
            new ArtStore(
              results.collect { case Right(Some(s)) => s.name -> s }.toMap,
              results.collect { case Left(problem) => problem }
            )

  /** One sprite: Right(None) for an optional one that is not there. */
  private def sprite(e: Expected, conf: Config, read: String => Option[Array[Byte]]): Either[String, Option[Sprite]] =
    val path = s"art.${e.name}"
    val (lo, hi) = (read(s"${e.name}.lo.png"), read(s"${e.name}.hi.png"))
    if !conf.hasPath(path) || lo.isEmpty || hi.isEmpty then
      if e.optional && lo.isEmpty && hi.isEmpty then Right(None)
      else Left(s"${e.name}: ${if !conf.hasPath(path) then "not in art.conf" else "a sheet is missing"}")
    else
      try
        for
          poses <- poses(e, conf.getConfig(path))
          across = poses.map(_.frames).max
          loSheet <- sheet(s"${e.name}.lo.png", lo.get, e.cols, e.rows * 2 * e.poses)
          hiSheet <- sheet(s"${e.name}.hi.png", hi.get, e.cols * CellWidth * across, e.rows * CellHeight * e.poses)
          made = Sprite(e.name, e.cols, e.rows, poses, loSheet, hiSheet)
          _ <- poses.indices
            .find(i => !made.loFrame(i).nonEmpty || !made.hiFrame(i, 0).nonEmpty)
            .fold(Right(()): Either[String, Unit])(i => Left(s"${e.name}: the first frame of pose $i is empty"))
        yield Some(made)
      catch case NonFatal(ex) => Left(s"${e.name}: ${ex.getMessage}")

  private def poses(e: Expected, conf: Config): Either[String, Seq[PoseArt]] =
    if e.poses == 1 then Right(Seq(PoseArt("still", 1, Seq((0, 1)))))
    else
      val listed = conf.getConfigList("poses").asScala.toSeq.map { p =>
        val timeline = p.getList("timeline").unwrapped().asScala.toSeq.map {
          case pair: java.util.List[?] if pair.size == 2 =>
            (pair.get(0).asInstanceOf[Number].intValue, pair.get(1).asInstanceOf[Number].intValue)
          case other => throw new IllegalArgumentException(s"a timeline entry is not [frame, ticks]: $other")
        }
        PoseArt(p.getString("name"), p.getInt("frames"), timeline)
      }
      if listed.map(_.name) != Poses then Left(s"${e.name}: its poses must be ${Poses.mkString(", ")}, in that order")
      else
        listed
          .collectFirst {
            case p if p.frames < 1 || p.frames > e.mostFrames =>
              s"${e.name} ${p.name}: ${p.frames} frames, not 1 to ${e.mostFrames}"
            case p if p.timeline.isEmpty => s"${e.name} ${p.name}: an empty timeline"
            case p if p.timeline.exists((f, t) => f < 0 || f >= p.frames || t < 1 || t > 240) =>
              s"${e.name} ${p.name}: a timeline entry names a frame it does not have, or a time outside 1 to 240"
          }
          .toLeft(listed)

  /** A sheet file as pixels, held to the contract: its size, hard edges, the 240 colours. */
  private def sheet(name: String, bytes: Array[Byte], width: Int, height: Int): Either[String, PixelBuffer] =
    if bytes.length > MaxBytes then Left(s"$name: larger than 4 MiB")
    else
      Png.read(bytes, Some((width, height))).left.map(reason => s"$name: $reason").flatMap { image =>
        if image.width != width || image.height != height then
          Left(s"$name: is ${image.width}x${image.height}, must be ${width}x$height")
        else
          val out = new Array[Byte](width * height)
          var problem: Option[String] = None
          var i = 0
          while i < out.length && problem.isEmpty do
            val pixel = image.argb(i)
            (pixel >>> 24) match
              case 0 => ()
              case 0xff =>
                Palette.indexOf(pixel) match
                  case Some(index) => out(i) = index.toByte
                  case None        => problem = Some(f"$name: a colour outside the 240 (#${pixel & 0xffffff}%06x)")
              case _ => problem = Some(s"$name: has half-transparent pixels")
            i += 1
          problem.toLeft(PixelBuffer(width, height, out))
      }
