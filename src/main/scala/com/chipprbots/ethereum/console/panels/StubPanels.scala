package com.chipprbots.ethereum.console.panels

import org.jline.utils.AttributedString

import com.chipprbots.ethereum.console.*
import com.chipprbots.ethereum.console.panels.Rows.*

/** The Mempool, RPC, Storage, System and Ops views as they stand before their slices of spec 029 land: what the
  * dashboard already reads for each, and one line naming what the view will show (slices 4 to 7 of spec 029; the line
  * fits 80 columns). Nothing here is invented: a row is a figure the probe reads today, or it is not drawn (FR-021).
  */
private[console] object StubPanels:

  private def view(c: Ctx, title: String, rows: Seq[(String, AttributedString)], toCome: String): Seq[Panel] =
    def lines(w: Int): Seq[AttributedString] =
      fit(text(s" $title", c.t.healthy.bold()), w, c.g) +: rows.map((label, value) =>
        fit(field(label, value, c.t), w, c.g)
      )
    Seq(
      vitalsPanel(c, withSummary = true),
      Panel(1, rows.size + 1, 2, (w, _) => lines(w), separated = true),
      Panel(1, 1, 3, (w, _) => Seq(fit(text(s" Not built yet: $toCome", c.t.muted), w, c.g)), separated = true),
      fillPanel
    )

  def mempool(c: Ctx): Seq[Panel] =
    view(
      c,
      "Mempool",
      Seq("Pending" -> txpoolValue(c)),
      "fee tiers, flow in and out, senders, replacements"
    )

  def rpc(c: Ctx): Seq[Panel] =
    view(
      c,
      "RPC",
      Seq("RPC" -> endpointsValue(c)),
      "calls and latency by method, the consensus-client link"
    )

  def storage(c: Ctx): Seq[Panel] =
    val n = c.state.nodeSettings
    view(
      c,
      "Storage",
      Seq(
        "Disk" -> diskValue(c),
        "Datadir" -> text(TuiText.external(n.dataDir, c.g), c.t.value),
        "Pruning" -> text(n.pruningMode, c.t.value)
      ),
      "database size, compaction, caches, the integrity audit"
    )

  def system(c: Ctx): Seq[Panel] =
    view(
      c,
      "System",
      Seq("Heap" -> heapValue(c), "GC" -> gcValue(c), "Discover" -> discoveryValue(c)),
      "CPU, threads, network throughput, health bars"
    )

  def ops(c: Ctx): Seq[Panel] =
    val readOnly = if c.config.readOnly then "read-only: no action can run" else "actions allowed"
    view(
      c,
      "Ops",
      Seq(
        "Mode" -> text(readOnly, if c.config.readOnly then c.t.warn else c.t.value),
        "q" -> text("ask to stop the node", c.t.value),
        "d" -> text("detach the TUI; the node keeps running", c.t.value)
      ),
      "log levels, peers, mining, RPC servers, configuration"
    )
