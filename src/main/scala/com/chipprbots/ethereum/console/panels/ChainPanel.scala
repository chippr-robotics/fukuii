package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Chain view (spec 027 FR-017, research §2.1, §3.1): which rules the node runs and what comes next. The chain's
  * identity, then the last and next fork at tier S, the whole timeline from tier M, one fork a row: by block number on
  * ETC, by timestamp on ETH (C-4, C-5).
  *
  * Not here yet: the L tier's per-fork EIP list, which has no source in the configuration.
  */
private[console] object ChainPanel:

  /** The Chain view (spec 029 FR-006). While the node is syncing its sync panels lead, then the chain's identity and
    * forks, then the Recent (sync) strip; at the head, the chain's panels alone.
    */
  def panels(c: Ctx): Seq[Panel] =
    c.state.chain match
      case None        => compose(c, Seq(notRead(c)))
      case Some(chain) =>
        // The network row is there once the node knows any of its parts: the panel asks for the rows it will draw.
        val identityRows = (w: Int) => identityRow(c, chain, w) +: networkRow(c, chain, w).toSeq
        val identity = Panel(1, identityRows(120).size, 5, (w, n) => identityRows(w).take(n), separated = true)
        // The observatory takes the rows nobody else wants where the node's blocks are read (spec 029 FR-023); where
        // they are not, the forks do, as in spec 027.
        val observatory = ObservatoryPanels.observatory(c)
        def view(forks: Panel): Seq[Panel] =
          compose(
            c,
            ObservatoryPanels.head(c).toSeq ++ Seq(identity, forks) ++ ObservatoryPanels.fees(c) ++
              ObservatoryPanels.reorgs(c) ++ observatory
          )
        val short = Panel(1, 2, 6, (w, _) => ForkRows.lastAndNext(c, chain, w), true, fill = observatory.isEmpty)
        if c.tier == Tier.S then view(short)
        else
          def timeline(w: Int) =
            fit(text(s" Forks (${chain.scheduleLabel})", c.t.healthy.bold()), w, c.g) +: ForkRows.timeline(c, chain, w)
          val rows = timeline(120).size
          val whole = Panel(
            1,
            if observatory.isEmpty then 1 else rows,
            6,
            (w, _) => timeline(w),
            separated = true,
            fill = observatory.isEmpty
          )
          // The whole timeline where the screen has the rows for all of it beside the rest; else the last fork and the
          // next. Never a timeline cut after its oldest forks.
          val long = view(whole)
          if observatory.isEmpty || c.gives(long, long.indexOf(whole), rows) then long else view(short)

  /** The Chain view's panels (spec 029 FR-006). While the node is syncing its sync panels lead, then `chain`'s, then
    * the Recent (sync) strip; at the head, the vitals and `chain`'s alone.
    */
  private def compose(c: Ctx, chain: Seq[Panel]): Seq[Panel] =
    val s = c.state
    val syncing = s.snapSyncState.isDefined || (s.bestBlock > 0 && !s.isSynchronized)
    if !syncing then vitalsPanel(c, withSummary = true) +: chain
    else
      // Without the chain's own vitals strip: the sync's leads. The Recent strip keeps its own rows.
      val sync = SyncPanels.panels(c)
      sync.init ++ chain :+ sync.last

  private def notRead(c: Ctx): Panel =
    Panel(1, 1, 2, (w, _) => Seq(fit(field("Chain", text("not read yet", c.t.muted), c.t), w, c.g)), separated = true)

  /** ` Network network id 1 · genesis 0xd4e5…8fa3 · fork id 0xfc64ec04, next 1150000`: what the node tells a peer of
    * its chain (spec 029 FR-027). Each part is there once the node knows it; none of them, no row.
    */
  private def networkRow(c: Ctx, chain: ChainFacts, w: Int): Option[AttributedString] =
    val parts = chain.networkId.map(id => s"network id $id").toSeq ++
      chain.genesisHash.map(hash => s"genesis ${ClPanel.shortHex(hash, c.g)}") ++
      c.state.snapshot.flatMap(_.local.forkId).map(id => s"fork id $id")
    Option.when(parts.nonEmpty)(ObservatoryPanels.partsRow(c, "Network", parts, w))

  /** ` Chain   etc · chain id 61 · PoW · forks by block number` */
  private def identityRow(c: Ctx, chain: ChainFacts, w: Int): AttributedString =
    val dot = c.g.dot.text
    val parts = Seq(
      c.state.networkName,
      s"chain id ${chain.chainId}",
      chain.family.label,
      s"forks by ${chain.scheduleLabel}${if chain.schedule == ForkSchedule.ByBlock then " number" else ""}"
    )
    fit(field("Chain", text(parts.mkString(s" $dot "), c.t.value), c.t), w, c.g)
