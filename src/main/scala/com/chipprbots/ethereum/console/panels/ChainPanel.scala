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
    val s = c.state
    val syncing = s.snapSyncState.isDefined || (s.bestBlock > 0 && !s.isSynchronized)
    if !syncing then chain(c)
    else
      val sync = SyncPanels.panels(c)
      // Without the chain's own vitals strip: the sync's leads. The forks take the rows that are left, as they do at
      // the head; the Recent strip keeps its own.
      sync.init ++ chain(c).drop(1) :+ sync.last

  private def chain(c: Ctx): Seq[Panel] =
    val vitals = vitalsPanel(c, withSummary = true)
    c.state.chain match
      case None =>
        Seq(
          vitals,
          Panel(
            1,
            1,
            2,
            (w, _) => Seq(fit(field("Chain", text("not read yet", c.t.muted), c.t), w, c.g)),
            separated = true
          )
        )
      case Some(chain) =>
        val identity = Panel(1, 1, 2, (w, _) => Seq(identityRow(c, chain, w)), separated = true)
        val forks =
          if c.tier == Tier.S then
            Panel(1, 2, 3, (w, _) => ForkRows.lastAndNext(c, chain, w), separated = true, fill = true)
          else
            Panel(
              1,
              1,
              3,
              (w, _) =>
                fit(text(s" Forks (${chain.scheduleLabel})", c.t.healthy.bold()), w, c.g) +: ForkRows
                  .timeline(c, chain, w),
              separated = true,
              fill = true
            )
        Seq(vitals, identity, forks)

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
