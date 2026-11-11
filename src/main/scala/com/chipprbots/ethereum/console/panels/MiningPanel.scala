package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString
import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.TuiText.formatHashrate
import com.chipprbots.ethereum.console.TuiText.formatNumber
import com.chipprbots.ethereum.console.TuiText.formatShort
import com.chipprbots.ethereum.console.panels.Rows.*

/** The mining panel of the Overview, on an ETC node (spec 027 FR-023, research §3.4): whether mining is on, its
  * coinbase, hashrate, blocks mined, stale shares and `eth_getWork` count, and the DAG with its epoch and progress.
  *
  * With mining off it is one row. With mining on, tier S has one row (the hashrate and the DAG) and from tier M there
  * are two: the coinbase and the DAG, then the hashrate and the counts.
  *
  * It exists only where the probe read the mining metrics (`LocalSample.mining`), which `StdNode` does on an ETC node:
  * an ETH node has no mining panel. It reads the snapshot alone, as every panel does, and it has nothing of Olympia, a
  * treasury or a base fee: Olympia is deferred on every chain.
  *
  * The DAG's epoch is the ECIP-1099 epoch (a block's number over 60,000 after Thanos), not the 30,000-block number
  * older tools name. A DAG that is being built shows its percent and a spinner, which is the one thing here that moves
  * (`TuiRenderer.mayMove`); under reduced motion or pause the spinner is the glyph set's still dot.
  */
private[console] object MiningPanel:

  /** The panel, if the probe read the mining metrics. */
  def panel(c: Ctx): Option[Panel] =
    c.state.snapshot.flatMap(_.local.mining).map { m =>
      val rowCount = if !c.state.nodeSettings.miningEnabled || c.tier == Tier.S then 1 else 2
      Panel(1, rowCount, 4, (w, _) => rows(c, m, w), separated = true)
    }

  /** The panel's rows, each fitted to `w`. */
  def rows(c: Ctx, m: MiningSample, w: Int): Seq[AttributedString] =
    if !c.state.nodeSettings.miningEnabled then Seq(fit(field("Mining", text("off", c.t.muted), c.t), w, c.g))
    else
      val dot = s" ${c.g.dot.text} "
      val hashrate = if m.hashrate > 0 then formatHashrate(m.hashrate) else "none reported"
      val first = new AttributedStringBuilder()
      first.append(field("Mining", text("on", c.t.healthy.bold()), c.t))
      if c.tier == Tier.S then first.append(dot + hashrate, c.t.value)
      else m.coinbase.foreach(a => first.append(s"${dot}coinbase ${ClPanel.shortHex(a, c.g, head = 8)}", c.t.value))
      first.append(dot, c.t.value)
      val (piece, style) = dag(c, m.dag)
      first.append(piece, style)
      val head = fit(first.toAttributedString, w, c.g)
      if c.tier == Tier.S then Seq(head)
      else
        val mined = s"blocks mined ${formatNumber(m.blocksMined)}" +
          m.lastMinedAt.fold("")(at =>
            s" (last ${formatShort(java.time.Duration.between(at, c.state.now).getSeconds)} ago)"
          )
        val getWork =
          s"getWork ${formatNumber(m.getWork)}" + m.getWorkPerMinute.fold("")(r => s" (${formatNumber(r)}/min)")
        val values = Seq(hashrate, mined, s"stale ${formatNumber(m.staleShares)}", getWork).mkString(dot)
        val detail = concat(text(s"   ${"Hashrate".padTo(WormLabelCols, ' ')}", c.t.label), text(values, c.t.value))
        Seq(head, fit(detail, w, c.g))

  /** The DAG as the row says it, and the style it is drawn in. */
  private def dag(c: Ctx, sample: Option[DagSample]): (String, AttributedStyle) =
    val spinner = WormBar.spinner(c.state.tick, c.state.motion, c.g)
    sample match
      case None                => ("DAG not started", c.t.muted)
      case Some(d) if d.loaded => (s"DAG epoch ${d.epoch} ${c.g.check.text} loaded", c.t.healthy)
      case Some(d) =>
        d.phase match
          case DagPhase.Pending    => (s"DAG epoch ${d.epoch} preparing $spinner", c.t.warn)
          case DagPhase.Loading    => (s"DAG epoch ${d.epoch} loading ${d.progress}% $spinner", c.t.warn)
          case DagPhase.Generating => (s"DAG epoch ${d.epoch} generating ${d.progress}% $spinner", c.t.warn)
