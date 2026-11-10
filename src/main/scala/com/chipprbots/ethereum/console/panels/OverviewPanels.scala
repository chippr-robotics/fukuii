package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.fitToWidth
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Overview, by tier (spec 027 research §3.2–§3.4): the vitals and resources strips; the sync panel (SNAP's five
  * worm rows, or the Blocks worm); the forks; at tier L the longest-connected peers, beside the logo; the node's
  * settings; and a fill panel where slice 4's recent-log strip goes.
  */
private[console] object OverviewPanels:

  /** The right-hand column that holds the logo at tier L. */
  val LogoColumnCols: Int = 46

  def panels(c: Ctx, width: Int): Seq[Panel] =
    val strips = Panel(1, 2, 1, (w, _) => Seq(vitalsRow(c, w), resourcesRow(c, w)))
    val node = Option.when(c.config.showNodeSettings && c.state.nodeSettings.network.nonEmpty)(
      Panel(1, 1, 5, (w, _) => Seq(nodeRow(c, w)), separated = true)
    )
    val fill = Panel(0, 0, 9, (_, _) => Nil, fill = true)
    val middle =
      if c.config.showLogo && c.tier.logo.inDashboard then Seq(withLogo(c, width))
      else Seq(sync(c, c.tier.trackSlots)) ++ forks(c) ++ Option.when(c.tier == Tier.L)(peers(c)).flatten
    Seq(strips) ++ middle ++ node ++ Seq(fill)

  /** The sync panel: SNAP's phase and five worm rows (and the chain download from tier M), or the Blocks worm. */
  def sync(c: Ctx, slots: Int, separated: Boolean = true): Panel =
    def rows(w: Int): Seq[AttributedString] =
      val s = c.state
      val budget = wormBudget(c, slots, w)
      val dot = c.g.dot.text
      s.snapSyncState match
        case Some(snap) =>
          val stale = staleSnapSample(c)
          val right = text(
            (Seq(s"elapsed ${formatShort(snap.elapsedSeconds.toLong)}", eta(snap.etaSeconds)) ++ stale)
              .mkString(s" $dot "),
            if stale.isDefined then c.t.warn else c.t.value
          )
          val head = leftRight(text(s" SNAP ${snap.phaseDescription(c.g)}", c.t.healthy.bold()), right, w)
          val worms = SnapRows.of(snap, c.g).map(r => wormRow(c, r.label, r.progress, r.detail, budget, w))
          val chain = Option.when(c.tier != Tier.S)(chainDownload(c, snap)).flatten.map { d =>
            fitToWidth(concat(text(s"   ${"Chain".padTo(WormLabelCols, ' ')}", c.t.label), text(d, c.t.value)), w)
          }
          (head +: worms) ++ chain
        case None =>
          val (what, right) =
            if s.isSynchronized then (s"${c.g.check.text} at head", "")
            else if s.bestBlock > 0 then ("regular", rateAndEta(c))
            else (s.syncStatus, "")
          val head = leftRight(field("Sync", text(what, c.t.healthy.bold()), c.t), text(right, c.t.value), w)
          head +: (if c.config.showProgressBar then blocksRow(c, budget, w).toSeq else Nil)
    val preferred = c.state.snapSyncState.fold(2)(s => 6 + (if c.tier != Tier.S && s.chainTarget > 0 then 1 else 0))
    Panel(1, preferred, 2, (w, _) => rows(w), separated = separated)

  /** Where the chain is on its fork timeline: one row at S, a title, the timeline and a detail row from M. */
  def forks(c: Ctx): Option[Panel] =
    c.state.chain.map { chain =>
      val oneLine = c.tier == Tier.S
      Panel(1, if oneLine then 1 else 3, 3, (w, _) => ForkRows.overview(c, chain, w, oneLine), separated = true)
    }

  /** The longest-connected peers (tier L): slice 4 adds the client, best block and score, and sorts by score. */
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

  /** ` Node    /var/lib/fukuii/etc · SNAP sync · archive pruning · 50 peers max · mining on` */
  def nodeRow(c: Ctx, width: Int): AttributedString =
    val n = c.state.nodeSettings
    val dot = c.g.dot.text
    val parts = Seq(n.dataDir, s"${n.syncMode} sync", s"${n.pruningMode} pruning", s"${n.maxPeers} peers max") ++
      Seq(if n.miningEnabled then "mining on" else "mining off")
    fitToWidth(field("Node", text(parts.filter(_.nonEmpty).mkString(s" $dot "), c.t.value), c.t), width)
