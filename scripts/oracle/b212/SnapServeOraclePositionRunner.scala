package com.chipprbots.ethereum.network.snapserver

import java.io.File

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage

/** B-212 (spec 018 T052), staging only: [[SnapServeOracleRunner]]'s requests served from the POSITION store of
  * `<seedDir>/position/db` (CF `t` and CF `u`, the anchor in AppState): what a node on the unified layout serves, one
  * read-only view of the anchor for each request, as `stateForRoot` hands one out. v0.12.1 has no such store, so this
  * file is not compiled there.
  *
  * args: `<seedDir> <outFile>`.
  */
object SnapServeOraclePositionRunner:

  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: SnapServeOraclePositionRunner <seedDir> <outFile>")
    val seedDir = new File(args(0))
    val ds = RocksDbDataSource(
      SnapServeOracleRunner.config(new File(seedDir, "position/db").getAbsolutePath),
      Namespaces.nsSeq
    )
    try
      val appState = new AppStateStorage(ds)
      val storage = new PathStateStorage(new PathNodeStorage(ds), appState)
      val anchor = appState.getStateAnchor().getOrElse(throw new IllegalStateException("the datadir has no anchor"))
      val manifest = SnapServeOracleRunner.readManifest(new File(seedDir, "manifest.tsv"))
      require(anchor.stateRoot == manifest.root, "the anchor of the position datadir is not the manifest's root")
      val store = () => storage.readOnly(anchor): MptStorage
      val n = SnapServeOracleRunner.serveAll(seedDir, new File(args(1)), store, new EvmCodeStorage(ds))
      println(s"served $n requests from the position store")
    finally ds.close()
