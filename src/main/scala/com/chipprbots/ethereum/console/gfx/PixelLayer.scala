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
  *   the id each image was sent under, by what it is. It only grows until a clear frees every image: the art bounds it
  *   (some fifty sprite frames at one scale), but a terminal that evicts images under memory pressure would leave one
  *   listed here that it no longer holds, blank until the next clear (`r`, a resize). Freeing the least recently shown
  *   is B-259
  * @param content
  *   the buffer a chart's image was drawn from when it was last sent
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

  /** The most an image is enlarged by: a cell of 64 × 128 pixels, more than any display has. A terminal's report of its
    * cell is input, and an image's size goes with the square of this.
    */
  val MaxScale: Int = 8

  /** The whole factor a `hi` frame, drawn for [[ArtStore.CellWidth]] × [[ArtStore.CellHeight]] cells, is enlarged by at
    * `cell`: the largest that still fits, at least 1 and at most [[MaxScale]] (spec 029 FR-015).
    */
  def scale(cell: CellSize): Int =
    (cell.width / ArtStore.CellWidth).min(cell.height / ArtStore.CellHeight).max(1).min(MaxScale)

  /** The bytes that turn what `previous` says the terminal shows into `regions`, and the layer after them.
    *
    * An image the terminal does not hold is sent once. A region whose image or place changed gets the new placement
    * first and loses the old one after, so nothing flickers. A region that left is removed. An unchanged frame costs
    * nothing (spec 029 FR-017).
    */
  def diff(previous: PixelLayer, regions: Seq[GraphicRegion], cell: CellSize, art: ArtStore): (String, PixelLayer) =
    val out = new StringBuilder
    var layer = previous
    val wanted = regions.flatMap(region => image(region, cell, art).map(region -> _))
    for (region, image) <- wanted do
      val known = layer.images.get(image.key)
      val id = known.getOrElse(layer.images.size + 1)
      // A sprite's frame never changes; a chart is sent again when what it was drawn from did.
      val stale = known.isEmpty || image.source.exists(source => !layer.content.get(id).contains(source))
      if stale then
        // The pixels are built here and nowhere else: an image the terminal holds costs no pixel work a frame.
        out.append(Kitty.upload(id, image.pixels()))
        layer = layer.copy(
          images = layer.images.updated(image.key, id),
          content = image.source.fold(layer.content)(layer.content.updated(id, _))
        )
      val placement = layer.placements.getOrElse(region.id, layer.placements.size + 1)
      // The image, centred in its region: whole cells of margin move the cursor, the rest is a pixel offset.
      val marginX = ((region.cols * cell.width - image.width) / 2).max(0)
      val marginY = ((region.rows * cell.height - image.height) / 2).max(0)
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

  /** An image a region shows: what it is, its size once enlarged, and how to build its pixels when they have to be
    * sent.
    *
    * @param source
    *   for a chart, the buffer it is drawn from: a chart is sent again when this changes
    */
  final private case class Wanted(
      key: ImageKey,
      width: Int,
      height: Int,
      source: Option[PixelBuffer],
      pixels: () => PixelBuffer
  )

  /** What a region shows at `cell`, enlarged by a whole factor: a sprite's frame by [[scale]]; a chart by as much as
    * still fits its region, and no more than [[MaxScale]]. None for a sprite the art does not have.
    */
  private def image(region: GraphicRegion, cell: CellSize, art: ArtStore): Option[Wanted] = region.graphic match
    case Graphic.SpriteFrame(name, pose, frame) =>
      art.sprite(name).filter(s => pose < s.poses.size && frame < s.poses(pose).frames).map { sprite =>
        val k = scale(cell)
        Wanted(
          ImageKey.Frame(name, pose, frame, k),
          sprite.cols * ArtStore.CellWidth * k,
          sprite.rows * ArtStore.CellHeight * k,
          None,
          () => sprite.hiFrame(pose, frame).enlarge(k)
        )
      }
    case Graphic.Chart(id, buffer) =>
      if buffer.width <= 0 || buffer.height <= 0 then None
      else
        val fits = ((region.cols * cell.width) / buffer.width).min((region.rows * cell.height) / buffer.height)
        val k = fits.max(1).min(MaxScale)
        Some(
          Wanted(ImageKey.ChartImage(id), buffer.width * k, buffer.height * k, Some(buffer), () => buffer.enlarge(k))
        )
