package com.chipprbots.ethereum.console.panels

import com.chipprbots.ethereum.console.*

/** The panels on screen (spec 027 research §7.3, spec 029 FR-005, FR-006): Help when it is open, else the screen opened
  * over the view, else the view.
  */
private[console] object Views:
  def panels(c: Ctx): Seq[Panel] =
    if c.state.helpOpen then HelpPanel.panels(c)
    else
      c.state.screen match
        case Some(Screen.PeerDetail(id)) => PeerDetailPanel.panels(c, id)
        case Some(Screen.Art(page))      => ArtPanel.panels(c, page)
        case None =>
          c.state.view match
            case TuiView.Overview => OverviewPanels.panels(c)
            case TuiView.Chain    => ChainPanel.panels(c)
            case TuiView.Peers    => PeersPanel.panels(c)
            case TuiView.Mempool  => StubPanels.mempool(c)
            case TuiView.Rpc      => StubPanels.rpc(c)
            case TuiView.Storage  => StubPanels.storage(c)
            case TuiView.System   => StubPanels.system(c)
            case TuiView.Logs     => LogsPanel.panels(c)
            case TuiView.Ops      => StubPanels.ops(c)
