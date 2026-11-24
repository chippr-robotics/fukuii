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
  *   the id each image was sent under, by what it is. Bounded by [[PixelLayer.ImageBudgetBytes]] (B-259): past it, the
  *   least recently shown images not on screen are freed, so the terminal is never left to evict one under memory
  *   pressure (kitty's quota is about 320 MB; the mascot's 28 frames at scale 4 are about 112 MB), which would leave
  *   one listed here that it no longer holds, blank until the next clear
  * @param content
  *   the buffer a chart's image was drawn from when it was last sent
  * @param placed
  *   by region id: the image shown there, its placement id, and where
  * @param bytes
  *   by image id: its size as sent, in RGBA bytes
  * @param shownAt
  *   by image id: the frame it was last shown in ([[frame]])
  * @param frame
  *   how many frames this layer has been diffed for
  * @param nextId
  *   the id the next image sent gets: ids are never reused, so a freed image's id names nothing
  */
final case class PixelLayer(
    images: Map[PixelLayer.ImageKey, Int] = Map.empty,
    content: Map[Int, PixelBuffer] = Map.empty,
    placed: Map[String, PixelLayer.Shown] = Map.empty,
    placements: Map[String, Int] = Map.empty,
    bytes: Map[Int, Long] = Map.empty,
    shownAt: Map[Int, Long] = Map.empty,
    frame: Long = 0L,
    nextId: Int = 1
):
  /** The RGBA bytes of every image the terminal holds. */
  def heldBytes: Long = bytes.values.sum

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

  /** The most image data, in RGBA bytes, the TUI keeps with the terminal (B-259): a fifth of kitty's default quota of
    * about 320 MB, room for every frame of the art at a small scale and the recent ones at a large one.
    */
  val ImageBudgetBytes: Long = 64L * 1024 * 1024

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
  def diff(
      previous: PixelLayer,
      regions: Seq[GraphicRegion],
      cell: CellSize,
      art: ArtStore,
      budgetBytes: Long = ImageBudgetBytes
  ): (String, PixelLayer) =
    val out = new StringBuilder
    var layer = previous.copy(frame = previous.frame + 1)
    val wanted = regions.flatMap(region => image(region, cell, art).map(region -> _))
    for (region, image) <- wanted do
      val known = layer.images.get(image.key)
      val id = known.getOrElse(layer.nextId)
      // A sprite's frame never changes; a chart is sent again when what it was drawn from did.
      val stale = known.isEmpty || image.source.exists(source => !layer.content.get(id).contains(source))
      if stale then
        // The pixels are built here and nowhere else: an image the terminal holds costs no pixel work a frame.
        out.append(Kitty.upload(id, image.pixels()))
        layer = layer.copy(
          images = layer.images.updated(image.key, id),
          content = image.source.fold(layer.content)(layer.content.updated(id, _)),
          bytes = layer.bytes.updated(id, image.width.toLong * image.height * 4),
          nextId = if known.isEmpty then layer.nextId + 1 else layer.nextId
        )
      layer = layer.copy(shownAt = layer.shownAt.updated(id, layer.frame))
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
    // Over the budget, the least recently shown images off screen are freed, oldest first (B-259). The ones on screen
    // stay whatever they cost: the budget is a bound on what is kept, not on what is shown.
    if layer.heldBytes > budgetBytes then
      val onScreen = layer.placed.values.map(_.image).toSet
      val idle = layer.images.toSeq
        .filterNot((_, id) => onScreen.contains(id))
        .sortBy((_, id) => (layer.shownAt.getOrElse(id, 0L), id))
        .iterator
      var held = layer.heldBytes
      while held > budgetBytes && idle.hasNext do
        val (key, id) = idle.next()
        out.append(Kitty.free(id))
        held -= layer.bytes.getOrElse(id, 0L)
        layer = layer.copy(
          images = layer.images - key,
          content = layer.content - id,
          bytes = layer.bytes - id,
          shownAt = layer.shownAt - id
        )
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
