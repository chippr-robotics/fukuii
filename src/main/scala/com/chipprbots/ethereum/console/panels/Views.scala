package com.chipprbots.ethereum.console.panels

import com.chipprbots.ethereum.console.*

/** The panels of the view on screen (spec 027 research §7.3: `View.panels(view, tier)`). */
private[console] object Views:

  def panels(c: Ctx, width: Int): Seq[Panel] =
    if c.state.helpOpen then HelpPanel.panels(c)
    else
      c.state.view match
        case TuiView.Overview => OverviewPanels.panels(c, width)
        case TuiView.Sync     => SyncPanels.panels(c)
        case TuiView.Peers    => PeersPanel.panels(c)
        case TuiView.Chain    => ChainPanel.panels(c)
        case TuiView.Logs     => LogsPanel.panels(c)
