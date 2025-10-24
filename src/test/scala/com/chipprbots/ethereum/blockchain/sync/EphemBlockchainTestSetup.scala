package com.chipprbots.ethereum.blockchain.sync

import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder

trait EphemBlockchainTestSetup extends ScenarioSetup {

  trait LocalPruningConfigBuilder extends PruningConfigBuilder {
    override lazy val pruningMode: PruningMode = ArchivePruning
  }

  //+ cake overrides
  override lazy val vm: VMImpl = new VMImpl
  override lazy val storagesInstance
      : EphemDataSourceComponent with LocalPruningConfigBuilder with Storages.DefaultStorages =
    new EphemDataSourceComponent with LocalPruningConfigBuilder with Storages.DefaultStorages
  //- cake overrides

  def getNewStorages: EphemDataSourceComponent with LocalPruningConfigBuilder with Storages.DefaultStorages =
    new EphemDataSourceComponent with LocalPruningConfigBuilder with Storages.DefaultStorages
}
