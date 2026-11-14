package com.chipprbots.ethereum.console.gfx

/** A terminal cell's size in pixels, as the terminal reported it. */
final case class CellSize(width: Int, height: Int)

/** What a graphic region shows (spec 029 data-model). */
enum Graphic:
  /** A frame of a sprite's pose. */
  case SpriteFrame(sprite: String, pose: Int, frame: Int)

  /** A chart the code drew: `id` names the chart, `buffer` is what it shows now. */
  case Chart(id: String, buffer: PixelBuffer)

/** A rectangle of cells that holds a graphic (spec 029 FR-012): the same cells in every graphics mode.
  *
  * @param id
  *   the same from frame to frame for the same place on screen
  * @param row
  *   the first row, from 0 at the top of the screen
  * @param col
  *   the first column, from 0
  * @param alt
  *   what the region says in words: drawn in its cells in text mode, and whenever its sprite is not there (FR-001)
  */
final case class GraphicRegion(
    id: String,
    row: Int,
    col: Int,
    cols: Int,
    rows: Int,
    graphic: Graphic,
    alt: String = ""
):

  /** The same region, `rows` down and `cols` across: a panel places its regions from its own corner. */
  def shift(rows: Int, cols: Int): GraphicRegion = copy(row = row + rows, col = col + cols)

/** What the terminal holds, as far as the TUI knows (spec 029 research §5.2): the images sent, and where each region's
  * image is shown.
  *
  * @param images
  *   the id each image was sent under, by what it is
  * @param content
  *   the pixels a chart's image held when it was last sent
  * @param placed
  *   by region id: the image shown there, its placement id, and where
  */
final case class PixelLayer(
    images: Map[PixelLayer.ImageKey, Int] = Map.empty,
    content: Map[Int, PixelBuffer] = Map.empty,
    placed: Map[String, PixelLayer.Shown] = Map.empty,
    placements: Map[String, Int] = Map.empty
)

object PixelLayer:

  /** What an image is: one frame of a sprite at a scale, or a chart. */
  enum ImageKey:
    case Frame(sprite: String, pose: Int, frame: Int, scale: Int)
    case ChartImage(id: String)

  /** An image on screen: which, as which placement, and where. */
  final case class Shown(image: Int, placement: Int, row: Int, col: Int, xOffset: Int, yOffset: Int)

  val Empty: PixelLayer = PixelLayer()

  /** The whole factor a `hi` frame, drawn for [[ArtStore.CellWidth]] × [[ArtStore.CellHeight]] cells, is enlarged by at
    * `cell`: the largest that still fits, at least 1 (spec 029 FR-015).
    */
  def scale(cell: CellSize): Int = (cell.width / ArtStore.CellWidth).min(cell.height / ArtStore.CellHeight).max(1)

  /** The bytes that turn what `previous` says the terminal shows into `regions`, and the layer after them.
    *
    * An image the terminal does not hold is sent once. A region whose image or place changed gets the new placement
    * first and loses the old one after, so nothing flickers. A region that left is removed. An unchanged frame costs
    * nothing (spec 029 FR-017).
    */
  def diff(previous: PixelLayer, regions: Seq[GraphicRegion], cell: CellSize, art: ArtStore): (String, PixelLayer) =
    val out = new StringBuilder
    var layer = previous
    val wanted = regions.flatMap { region =>
      image(region, cell, art).map { pixels =>
        val key = region.graphic match
          case Graphic.SpriteFrame(sprite, pose, frame) => ImageKey.Frame(sprite, pose, frame, scale(cell))
          case Graphic.Chart(id, _)                     => ImageKey.ChartImage(id)
        (region, key, pixels)
      }
    }
    for (region, key, pixels) <- wanted do
      val known = layer.images.get(key)
      val id = known.getOrElse(layer.images.size + 1)
      // A sprite's frame never changes; a chart is sent again when its pixels did.
      val stale = known.isEmpty || (key.isInstanceOf[ImageKey.ChartImage] && !layer.content.get(id).contains(pixels))
      if stale then
        out.append(Kitty.upload(id, pixels))
        layer = layer.copy(images = layer.images.updated(key, id), content = layer.content.updated(id, pixels))
      val placement = layer.placements.getOrElse(region.id, layer.placements.size + 1)
      // The image, centred in its region: whole cells of margin move the cursor, the rest is a pixel offset.
      val marginX = ((region.cols * cell.width - pixels.width) / 2).max(0)
      val marginY = ((region.rows * cell.height - pixels.height) / 2).max(0)
      val shown = Shown(
        id,
        placement,
        region.row + marginY / cell.height,
        region.col + marginX / cell.width,
        marginX % cell.width,
        marginY % cell.height
      )
      val before = layer.placed.get(region.id)
      // A chart sent again under the same id is shown again: sending an image drops its placements.
      if !before.contains(shown) || stale then
        out.append(Kitty.place(shown.image, shown.placement, shown.row, shown.col, shown.xOffset, shown.yOffset))
        before.filter(_.image != shown.image).foreach(old => out.append(Kitty.remove(old.image, old.placement)))
        layer = layer.copy(
          placed = layer.placed.updated(region.id, shown),
          placements = layer.placements.updated(region.id, placement)
        )
    val kept = wanted.map(_._1.id).toSet
    for (regionId, old) <- layer.placed.toSeq.sortBy(_._1) if !kept.contains(regionId) do
      out.append(Kitty.remove(old.image, old.placement))
      layer = layer.copy(placed = layer.placed - regionId)
    (out.toString, layer)

  /** The pixels a region shows at `cell`, enlarged by a whole factor: a sprite's frame by [[scale]]; a chart by as much
    * as still fits its region. None for a sprite the art does not have.
    */
  private def image(region: GraphicRegion, cell: CellSize, art: ArtStore): Option[PixelBuffer] = region.graphic match
    case Graphic.SpriteFrame(name, pose, frame) =>
      art
        .sprite(name)
        .filter(s => pose < s.poses.size && frame < s.poses(pose).frames)
        .map(_.hiFrame(pose, frame).enlarge(scale(cell)))
    case Graphic.Chart(_, buffer) =>
      if buffer.width <= 0 || buffer.height <= 0 then None
      else
        val k = ((region.cols * cell.width) / buffer.width).min((region.rows * cell.height) / buffer.height).max(1)
        Some(buffer.enlarge(k))
