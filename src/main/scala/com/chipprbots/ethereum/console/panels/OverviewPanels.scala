package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Overview, by tier (spec 027 research §3.2–§3.4): the vitals and resources strips; the sync panel (SNAP's five
  * worm rows, or the Blocks worm); the forks; at tier L the top peers by score, beside the logo; the node's settings;
  * and the Recent strip of the newest log lines.
  */
private[console] object OverviewPanels:

  /** The right-hand column that holds the logo at tier L. */
  val LogoColumnCols: Int = 46

  def panels(c: Ctx, width: Int): Seq[Panel] =
    val strips = Panel(1, 2, 1, (w, _) => Seq(vitalsRow(c, w), resourcesRow(c, w)))
    val node = Option.when(c.config.showNodeSettings && c.state.nodeSettings.network.nonEmpty)(
      Panel(1, 1, 5, (w, _) => Seq(nodeRow(c, w)), separated = true)
    )
    val middle =
      if c.config.showLogo && c.tier.logo.inDashboard then Seq(withLogo(c, width))
      else Seq(sync(c, c.tier.trackSlots)) ++ forks(c) ++ Option.when(c.tier == Tier.L)(peers(c)).flatten
    Seq(strips) ++ middle ++ node :+ LogsPanel.recent(c, "Recent", _ => true)

  /** The sync panel: SNAP's phase and five worm rows (and the chain download from tier M), or the Blocks worm. */
  def sync(c: Ctx, slots: Int, separated: Boolean = true): Panel =
    def rows(w: Int): Seq[AttributedString] =
      val s = c.state
      s.snapSyncState match
        case Some(snap) =>
          snapHeader(c, snap, w, "SNAP", withPhaseTime = false) +:
            snapWorms(c, snap, slots, w, withChain = c.tier != Tier.S)
        case None =>
          val budget = wormBudget(c, slots, w)
          val (what, right) =
            if s.isSynchronized then (s"${c.g.check.text} at head", "")
            else if s.bestBlock > 0 then ("regular", rateAndEta(c))
            else (s.syncStatus, "")
          val head = leftRight(field("Sync", text(what, c.t.healthy.bold()), c.t), text(right, c.t.value), w, c.g)
          head +: (if c.config.showProgressBar then blocksRow(c, budget, w).toSeq else Nil)
    val preferred = c.state.snapSyncState.fold(2)(s => 6 + (if c.tier != Tier.S && s.chainTarget > 0 then 1 else 0))
    Panel(1, preferred, 2, (w, _) => rows(w), separated = separated)

  /** Where the chain is on its fork timeline: one row at S, a title, the timeline and a detail row from M. */
  def forks(c: Ctx): Option[Panel] =
    c.state.chain.map { chain =>
      val oneLine = c.tier == Tier.S
      Panel(1, if oneLine then 1 else 3, 3, (w, _) => ForkRows.overview(c, chain, w, oneLine), separated = true)
    }

  /** The top peers by score (tier L, §3.4): client, best block, score and trend. */
  def peers(c: Ctx): Option[Panel] =
    Option.when(c.state.snapshot.isDefined)(
      Panel(3, 10, 4, (w, n) => PeersPanel.table(c, w, n, top = true), separated = true)
    )

  /** The sync panel, forks and peers on the left, and the 12-line logo centred in a column on the right (§3.4). Its
    * body takes tier M's worm track: the logo column leaves fewer columns than a tier-M screen has (C-18).
    */
  def withLogo(c: Ctx, width: Int): Panel =
    val leftCols = width - LogoColumnCols - 1
    val left = Seq(sync(c, Tier.M.trackSlots, separated = false)) ++ forks(c) ++ peers(c)
    val logo = TuiRenderer.SmallLogo.map(row => centred(text(row, c.t.healthy.bold()), LogoColumnCols))
    val preferred = Layout.allocate(left, 1000).zip(left).map((n, p) => n + (if p.separated && n > 0 then 1 else 0)).sum
    Panel(
      1,
      preferred.max(logo.size),
      2,
      (_, n) =>
        Layout.columns(
          Layout.render(left, leftCols, n, c.separator(leftCols)),
          logo,
          leftCols,
          LogoColumnCols,
          text(c.g.divider.text, c.t.muted)
        ),
      separated = true
    )

  /** ` Node /var/lib/fukuii/etc · SNAP sync · archive pruning · 50 peers max · mining on`: the datadir is cut from the
    * left, with the ellipsis, so the row always ends with the mining flag.
    */
  def nodeRow(c: Ctx, width: Int): AttributedString =
    val n = c.state.nodeSettings
    val dot = s" ${c.g.dot.text} "
    val rest = Seq(s"${n.syncMode} sync", s"${n.pruningMode} pruning", s"${n.maxPeers} peers max") ++
      Seq(if n.miningEnabled then "mining on" else "mining off")
    val tail = rest.filter(_.nonEmpty).mkString(dot)
    val room = width - 1 - (1 + FieldLabelCols) - dot.length - new AttributedString(tail).columnLength()
    val shown = shortPath(n.dataDir, room, c.g)
    val value = if shown.isEmpty then tail else shown + dot + tail
    fit(field("Node", text(value, c.t.value), c.t), width, c.g)

  /** `path` in `room` columns: whole if it fits, else its last directories after a marker that cannot be read as a path
    * (`…/fukuii/etc`, ASCII `.../fukuii/etc`; `../` would read as the parent), else nothing.
    */
  private[panels] def shortPath(path: String, room: Int, g: TuiGlyphs): String =
    def cols(s: String) = new AttributedString(s).columnLength()
    val marker = if g.set == GlyphSet.Ascii then ".../" else s"${g.ellipsis.text}/"
    if path.isEmpty || cols(path) <= room then path
    else
      val parts = path.split('/').filter(_.nonEmpty).toSeq
      (1 to parts.size).reverse.iterator
        .map(k => marker + parts.takeRight(k).mkString("/"))
        .find(cols(_) <= room)
        .getOrElse("")
